package dev.vmarket.migration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LegacyImportService {
    private static final int MAX_DATA_ROWS = 1000;
    private static final List<String> REQUIRED_HEADERS =
            List.of("sku", "vendor_code", "name", "on_hand", "price_minor");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final ImportJobRunner runner;
    private final ImportWorkService work;

    public LegacyImportService(
            JdbcClient jdbc, TransactionTemplate transactions, ImportJobRunner runner, ImportWorkService work) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.runner = runner;
        this.work = work;
    }

    public MigrationSummary importCsv(String sourceName, String encodingName, byte[] bytes) {
        return importCsv(sourceName, encodingName, bytes, false);
    }

    public MigrationSummary importCsv(String sourceName, String encodingName, byte[] bytes, boolean asynchronous) {
        if (sourceName == null || sourceName.isBlank() || sourceName.length() > 160) {
            throw new InvalidImportException("A valid X-Source-Name header is required.");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > dev.vmarket.shared.RequestBodyLimits.CSV_MAX_BYTES) {
            throw new InvalidImportException("CSV input must contain between 1 byte and 5 MB.");
        }

        Charset charset = supportedCharset(encodingName);
        String checksum = sha256(bytes);
        var existing = findByChecksum(checksum, charset);
        if (existing != null) {
            return existing;
        }

        List<StagedRow> rows = parse(bytes, charset);
        UUID jobId = UUID.randomUUID();
        var staged = transactions.execute(status -> {
            // Serialize the lookup+insert race; a hash collision only reduces concurrency.
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                    .param("key", "import-file:" + checksum)
                    .query((rs, row) -> true)
                    .single();
            var concurrent = findByChecksum(checksum, charset);
            if (concurrent != null) return concurrent;
            stage(jobId, sourceName.trim(), checksum, charset.name(), rows);
            if (asynchronous)
                jdbc.sql("UPDATE import_work_item SET dispatch_requested=TRUE WHERE job_id=:job")
                        .param("job", jobId)
                        .update();
            return find(jobId);
        });

        if (staged == null) throw new IllegalStateException("Staging transaction returned no job.");
        if (!asynchronous && staged.id().equals(jobId)) execute(jobId);
        return find(staged.id());
    }

    public MigrationSummary restart(UUID jobId) {
        return restart(jobId, false);
    }

    public MigrationSummary restart(UUID jobId, boolean asynchronous) {
        boolean launch;
        try {
            launch = Boolean.TRUE.equals(transactions.execute(transaction -> {
                String state = jdbc.sql("SELECT status FROM migration_job WHERE id=:id FOR UPDATE NOWAIT")
                        .param("id", jobId)
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> new InvalidImportException("Migration job does not exist."));
                if (state.equals("COMPLETED") || state.equals("COMPLETED_WITH_ERRORS")) return false;
                if (!state.equals("FAILED"))
                    throw new ImportConflictException(
                            "Only a failed import can be restarted; active or unverified jobs require review.");
                int ready = jdbc.sql(
                                "UPDATE import_work_item SET state='READY',dispatch_requested=:async,updated_at=NOW() WHERE job_id=:job AND state='FAILED'")
                        .param("job", jobId)
                        .param("async", asynchronous)
                        .update();
                if (ready != 1)
                    throw new ImportConflictException("The work requires ownership recovery before restart.");
                jdbc.sql("UPDATE migration_job SET status=:state,updated_at=NOW() WHERE id=:id")
                        .param("id", jobId)
                        .param("state", asynchronous ? "STAGED" : "RUNNING")
                        .update();
                return true;
            }));
        } catch (DataAccessException conflict) {
            if (conflict instanceof CannotAcquireLockException
                    || (conflict.getMostSpecificCause() instanceof SQLException sql
                            && "55P03".equals(sql.getSQLState()))) {
                throw new ImportConflictException(
                        "The import is active or being restarted; read its status before retrying.");
            }
            throw conflict;
        }
        if (launch && !asynchronous) execute(jobId);
        return find(jobId);
    }

    private void execute(UUID jobId) {
        runner.execute(work.claim(jobId));
    }

    public MigrationSummary find(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, source_name, encoding, status, total_rows, valid_rows,
                               applied_rows, rejected_rows, checkpoint_row, created_at, import_mode
                        FROM migration_job WHERE id = :id
                        """)
                .param("id", jobId)
                .query(MigrationSummary.class)
                .optional()
                .orElseThrow(() -> new InvalidImportException("Migration job does not exist."));
    }

    public List<MigrationSummary> recent() {
        return jdbc.sql("""
                        SELECT id, source_name, encoding, status, total_rows, valid_rows,
                               applied_rows, rejected_rows, checkpoint_row, created_at, import_mode
                        FROM migration_job ORDER BY created_at DESC LIMIT 20
                        """).query(MigrationSummary.class).list();
    }

    private MigrationSummary findByChecksum(String checksum, Charset charset) {
        var existing = jdbc.sql("""
                        SELECT id, source_name, encoding, status, total_rows, valid_rows,
                               applied_rows, rejected_rows, checkpoint_row, created_at, import_mode
                        FROM migration_job WHERE file_checksum = :checksum
                        """)
                .param("checksum", checksum)
                .query(MigrationSummary.class)
                .optional()
                .orElse(null);
        if (existing != null && !supportedCharset(existing.encoding()).equals(charset))
            throw new ImportConflictException(
                    "These file bytes already belong to an import with a different declared encoding. Review the existing job before submitting another operation.");
        return existing;
    }

    private void stage(UUID jobId, String sourceName, String checksum, String encoding, List<StagedRow> rows) {
        int validRows = Math.toIntExact(rows.stream().filter(StagedRow::valid).count());
        int rejectedRows = rows.size() - validRows;
        String importMode = rows.getFirst().versioned() ? "VERSIONED_STOCK" : "METADATA_ONLY";
        jdbc.sql("""
                        INSERT INTO migration_job (
                            id, source_name, file_checksum, encoding, status,
                            total_rows, valid_rows, rejected_rows, import_mode
                        ) VALUES (:id, :source, :checksum, :encoding, 'STAGED', :total, :valid, :rejected, :mode)
                        """)
                .param("id", jobId)
                .param("source", sourceName)
                .param("checksum", checksum)
                .param("encoding", encoding)
                .param("total", rows.size())
                .param("valid", validRows)
                .param("rejected", rejectedRows)
                .param("mode", importMode)
                .update();

        jdbc.sql("INSERT INTO import_work_item(job_id) VALUES(:job)")
                .param("job", jobId)
                .update();

        for (var row : rows) {
            jdbc.sql("""
                            INSERT INTO staged_import_row (
                                job_id, row_number, external_sku, product_name, vendor_code,
                                on_hand, price_minor, valid, validation_error, expected_stock_version
                            ) VALUES (
                                :jobId, :rowNumber, :sku, :name, :vendorCode,
                                :onHand, :price, :valid, :error, :stockVersion
                            )
                            """)
                    .param("jobId", jobId)
                    .param("rowNumber", row.rowNumber())
                    .param("sku", row.sku())
                    .param("name", row.name())
                    .param("vendorCode", row.vendorCode())
                    .param("onHand", row.onHand())
                    .param("price", row.priceMinor())
                    .param("valid", row.valid())
                    .param("error", row.error())
                    .param(
                            "stockVersion",
                            row.expectedStockVersion() != null && row.expectedStockVersion() >= 0
                                    ? row.expectedStockVersion()
                                    : null)
                    .update();
        }
    }

    private List<StagedRow> parse(byte[] bytes, Charset charset) {
        var rows = new ArrayList<StagedRow>();
        try (var reader = new InputStreamReader(
                        new ByteArrayInputStream(bytes),
                        charset.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT));
                var parser = CSVParser.parse(
                        reader,
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader()
                                .setDuplicateHeaderMode(org.apache.commons.csv.DuplicateHeaderMode.DISALLOW)
                                .setSkipHeaderRecord(true)
                                .setIgnoreEmptyLines(true)
                                .setTrim(true)
                                .get())) {
            if (!parser.getHeaderNames().containsAll(REQUIRED_HEADERS)) {
                throw new InvalidImportException("CSV headers must include: " + String.join(", ", REQUIRED_HEADERS));
            }
            boolean versioned = parser.getHeaderNames().contains("schema_version");
            if (versioned != parser.getHeaderNames().contains("expected_stock_version")) {
                throw new InvalidImportException(
                        "Versioned stock CSV requires both schema_version and expected_stock_version.");
            }
            parser.forEach(record -> {
                if (rows.size() >= MAX_DATA_ROWS)
                    throw new InvalidImportException("CSV input must contain at most " + MAX_DATA_ROWS + " data rows.");
                int rowNumber = Math.toIntExact(record.getRecordNumber() + 1);
                if (!record.isConsistent())
                    throw new InvalidImportException(
                            "CSV row " + rowNumber + " must have the same number of fields as its header.");
                String sku = record.get("sku").trim().toUpperCase(Locale.ROOT);
                String vendorCode = record.get("vendor_code").trim().toUpperCase(Locale.ROOT);
                String name = record.get("name").trim();
                Integer onHand = parseInteger(record.get("on_hand"));
                Integer price = parseInteger(record.get("price_minor"));
                Long stockVersion = versioned ? parseLong(record.get("expected_stock_version")) : null;
                List<String> errors = new ArrayList<>();
                if (sku.isBlank() || sku.length() > 64) errors.add("invalid SKU");
                if (vendorCode.isBlank() || vendorCode.length() > 32) errors.add("invalid vendor code");
                if (name.isBlank() || name.length() > 160) errors.add("invalid product name");
                if (onHand == null || onHand < 0) errors.add("on_hand must be a non-negative integer");
                if (price == null || price < 0) errors.add("price_minor must be a non-negative integer");
                if (versioned && !"2".equals(record.get("schema_version"))) errors.add("schema_version must be 2");
                if (versioned && (stockVersion == null || stockVersion < 0))
                    errors.add("expected_stock_version must be a non-negative integer");
                rows.add(new StagedRow(
                        rowNumber,
                        sku,
                        vendorCode,
                        name,
                        onHand,
                        price,
                        versioned,
                        stockVersion,
                        errors.isEmpty(),
                        errors.isEmpty() ? null : String.join("; ", errors)));
            });
        } catch (IOException | UncheckedIOException exception) {
            throw new InvalidImportException(
                    "CSV input is malformed or cannot be decoded using the declared encoding.");
        } catch (IllegalArgumentException exception) {
            throw new InvalidImportException("CSV headers or row structure are invalid.");
        }
        if (rows.isEmpty()) {
            throw new InvalidImportException("CSV input does not contain data rows.");
        }
        var counts = rows.stream()
                .collect(
                        java.util.stream.Collectors.groupingBy(StagedRow::sku, java.util.stream.Collectors.counting()));
        return rows.stream()
                .map(row -> counts.get(row.sku()) > 1
                        ? new StagedRow(
                                row.rowNumber(),
                                row.sku(),
                                row.vendorCode(),
                                row.name(),
                                row.onHand(),
                                row.priceMinor(),
                                row.versioned(),
                                row.expectedStockVersion(),
                                false,
                                row.error() == null
                                        ? "DUPLICATE_SKU: every occurrence is quarantined"
                                        : row.error() + "; DUPLICATE_SKU")
                        : row)
                .toList();
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Charset supportedCharset(String name) {
        if (name == null) throw new InvalidImportException("X-Source-Encoding is required.");
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "UTF-8", "UTF8" -> StandardCharsets.UTF_8;
            case "CP932", "SHIFT_JIS", "WINDOWS-31J" -> Charset.forName("windows-31j");
            default -> throw new InvalidImportException("Only UTF-8 and CP932 imports are supported.");
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record StagedRow(
            int rowNumber,
            String sku,
            String vendorCode,
            String name,
            Integer onHand,
            Integer priceMinor,
            boolean versioned,
            Long expectedStockVersion,
            boolean valid,
            String error) {}
}
