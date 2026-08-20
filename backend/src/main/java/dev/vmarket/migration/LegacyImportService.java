package dev.vmarket.migration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LegacyImportService {
    private static final List<String> REQUIRED_HEADERS =
            List.of("sku", "vendor_code", "name", "on_hand", "price_minor");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JobLauncher jobLauncher;
    private final Job legacyCatalogImportJob;

    public LegacyImportService(
            JdbcClient jdbc, TransactionTemplate transactions, JobLauncher jobLauncher, Job legacyCatalogImportJob) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.jobLauncher = jobLauncher;
        this.legacyCatalogImportJob = legacyCatalogImportJob;
    }

    public MigrationSummary importCsv(String sourceName, String encodingName, byte[] bytes) {
        if (sourceName == null || sourceName.isBlank() || sourceName.length() > 160) {
            throw new InvalidImportException("A valid X-Source-Name header is required.");
        }
        if (bytes == null || bytes.length == 0 || bytes.length > 5_000_000) {
            throw new InvalidImportException("CSV input must contain between 1 byte and 5 MB.");
        }

        String checksum = sha256(bytes);
        var existing = findByChecksum(checksum);
        if (existing != null) {
            return existing;
        }

        Charset charset = supportedCharset(encodingName);
        List<StagedRow> rows = parse(bytes, charset);
        UUID jobId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> stage(jobId, sourceName.trim(), checksum, charset.name(), rows));

        try {
            jobLauncher.run(
                    legacyCatalogImportJob,
                    new JobParametersBuilder()
                            .addString("jobId", jobId.toString())
                            .toJobParameters());
        } catch (Exception exception) {
            throw new ImportExecutionException("Legacy import job failed", exception);
        }
        return find(jobId);
    }

    public MigrationSummary find(UUID jobId) {
        return jdbc.sql("""
                        SELECT id, source_name, encoding, status, total_rows, valid_rows,
                               applied_rows, rejected_rows, checkpoint_row, created_at
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
                               applied_rows, rejected_rows, checkpoint_row, created_at
                        FROM migration_job ORDER BY created_at DESC LIMIT 20
                        """).query(MigrationSummary.class).list();
    }

    private MigrationSummary findByChecksum(String checksum) {
        return jdbc.sql("""
                        SELECT id, source_name, encoding, status, total_rows, valid_rows,
                               applied_rows, rejected_rows, checkpoint_row, created_at
                        FROM migration_job WHERE file_checksum = :checksum
                        """)
                .param("checksum", checksum)
                .query(MigrationSummary.class)
                .optional()
                .orElse(null);
    }

    private void stage(UUID jobId, String sourceName, String checksum, String encoding, List<StagedRow> rows) {
        int validRows = Math.toIntExact(rows.stream().filter(StagedRow::valid).count());
        int rejectedRows = rows.size() - validRows;
        jdbc.sql("""
                        INSERT INTO migration_job (
                            id, source_name, file_checksum, encoding, status,
                            total_rows, valid_rows, rejected_rows
                        ) VALUES (:id, :source, :checksum, :encoding, 'STAGED', :total, :valid, :rejected)
                        """)
                .param("id", jobId)
                .param("source", sourceName)
                .param("checksum", checksum)
                .param("encoding", encoding)
                .param("total", rows.size())
                .param("valid", validRows)
                .param("rejected", rejectedRows)
                .update();

        for (var row : rows) {
            jdbc.sql("""
                            INSERT INTO staged_import_row (
                                job_id, row_number, external_sku, product_name, vendor_code,
                                on_hand, price_minor, valid, validation_error
                            ) VALUES (
                                :jobId, :rowNumber, :sku, :name, :vendorCode,
                                :onHand, :price, :valid, :error
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
                    .update();
        }
    }

    private List<StagedRow> parse(byte[] bytes, Charset charset) {
        var rows = new ArrayList<StagedRow>();
        try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), charset);
                var parser = CSVParser.parse(
                        reader,
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader()
                                .setSkipHeaderRecord(true)
                                .setIgnoreEmptyLines(true)
                                .setTrim(true)
                                .get())) {
            if (!parser.getHeaderNames().containsAll(REQUIRED_HEADERS)) {
                throw new InvalidImportException("CSV headers must include: " + String.join(", ", REQUIRED_HEADERS));
            }
            parser.forEach(record -> {
                int rowNumber = Math.toIntExact(record.getRecordNumber() + 1);
                String sku = record.get("sku").trim().toUpperCase(Locale.ROOT);
                String vendorCode = record.get("vendor_code").trim().toUpperCase(Locale.ROOT);
                String name = record.get("name").trim();
                Integer onHand = parseInteger(record.get("on_hand"));
                Integer price = parseInteger(record.get("price_minor"));
                List<String> errors = new ArrayList<>();
                if (sku.isBlank() || sku.length() > 64) errors.add("invalid SKU");
                if (vendorCode.isBlank() || vendorCode.length() > 32) errors.add("invalid vendor code");
                if (name.isBlank() || name.length() > 160) errors.add("invalid product name");
                if (onHand == null || onHand < 0) errors.add("on_hand must be a non-negative integer");
                if (price == null || price < 0) errors.add("price_minor must be a non-negative integer");
                rows.add(new StagedRow(
                        rowNumber,
                        sku,
                        vendorCode,
                        name,
                        onHand,
                        price,
                        errors.isEmpty(),
                        errors.isEmpty() ? null : String.join("; ", errors)));
            });
        } catch (IOException exception) {
            throw new InvalidImportException("CSV input could not be read.");
        }
        if (rows.isEmpty()) {
            throw new InvalidImportException("CSV input does not contain data rows.");
        }
        return rows;
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value.trim());
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
            boolean valid,
            String error) {}
}
