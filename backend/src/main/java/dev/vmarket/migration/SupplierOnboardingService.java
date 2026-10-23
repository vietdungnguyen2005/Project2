package dev.vmarket.migration;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierOnboardingService {
    private final IntegrationProfileService profiles;
    private final LegacyImportService imports;
    private final JdbcClient jdbc;
    private final byte[] receiptKey;

    public SupplierOnboardingService(
            IntegrationProfileService profiles,
            LegacyImportService imports,
            JdbcClient jdbc,
            @org.springframework.beans.factory.annotation.Value("${vmarket.ops-secret}") String secret) {
        this.profiles = profiles;
        this.imports = imports;
        this.jdbc = jdbc;
        this.receiptKey = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record RowError(int rowNumber, String reason) {}

    public record Sample(int rowNumber, String sku, String name, Integer onHand) {}

    public record Preview(
            String profileId,
            int version,
            String encoding,
            Map<String, String> mapping,
            int acceptedRows,
            int rejectedRows,
            List<Sample> sample,
            List<RowError> errors,
            String confirmationReceipt) {}

    record Prepared(byte[] canonical, List<LegacyImportService.StagedRow> rows) {}

    @Transactional(
            readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
            timeout = 10)
    public Preview preview(String id, int version, byte[] bytes, String actor) {
        var profile = profiles.requireActive(id, version, false);
        var prepared = prepare(profile, bytes);
        var rows = prepared.rows();
        var errors = rows.stream()
                .filter(row -> !row.valid())
                .map(row -> new RowError(row.rowNumber(), row.error()))
                .toList();
        var sample = rows.stream()
                .filter(LegacyImportService.StagedRow::valid)
                .limit(10)
                .map(row -> new Sample(row.rowNumber(), row.sku(), row.name(), row.onHand()))
                .toList();
        String payload = (java.time.Instant.now().getEpochSecond() + 600) + ":" + LegacyImportService.sha256(bytes)
                + ":" + LegacyImportService.sha256(prepared.canonical());
        return new Preview(
                id,
                version,
                profile.encoding(),
                profile.mapping(),
                rows.size() - errors.size(),
                errors.size(),
                sample,
                errors,
                payload + ":" + sign(id, version, actor, payload));
    }

    public MigrationSummary confirm(
            String id, int version, byte[] bytes, String sourceName, String receipt, String actor) {
        var profile = profiles.requireActive(id, version, false);
        verifyReceipt(id, version, actor, bytes, receipt);
        // Verify original identity before recomputing mutable enrichment: completed retries return their original job.
        var duplicate = imports.duplicate(bytes, profile.encoding(), id, version);
        if (duplicate != null) return duplicate;
        var prepared = prepare(profile, bytes);
        if (!receipt.split(":")[2].equals(LegacyImportService.sha256(prepared.canonical())))
            throw new ImportConflictException(
                    "Catalog or inventory changed after preview. Preview this file again before confirming.");
        if (prepared.rows().stream().noneMatch(LegacyImportService.StagedRow::valid))
            throw new InvalidImportException("No acceptable rows. Correct the validation errors and preview again.");
        return imports.stagePrepared(
                sourceName,
                LegacyImportService.sha256(bytes),
                LegacyImportService.supportedCharset(profile.encoding()),
                prepared.rows(),
                true,
                id,
                version,
                actor);
    }

    private void verifyReceipt(String id, int version, String actor, byte[] bytes, String receipt) {
        if (bytes == null || bytes.length == 0 || bytes.length > dev.vmarket.shared.RequestBodyLimits.CSV_MAX_BYTES)
            throw new InvalidImportException("CSV input must contain between 1 byte and 5 MB.");
        if (receipt == null || !receipt.matches("[0-9]{10,12}:[a-f0-9]{64}:[a-f0-9]{64}:[a-f0-9]{64}"))
            throw new ImportConflictException("A valid explicit preview confirmation receipt is required.");
        String[] fields = receipt.split(":");
        String payload = String.join(":", fields[0], fields[1], fields[2]);
        long expires = Long.parseLong(fields[0]);
        if (expires < java.time.Instant.now().getEpochSecond()
                || expires > java.time.Instant.now().getEpochSecond() + 600
                || !fields[1].equals(LegacyImportService.sha256(bytes))
                || !java.security.MessageDigest.isEqual(
                        sign(id, version, actor, payload).getBytes(StandardCharsets.US_ASCII),
                        fields[3].getBytes(StandardCharsets.US_ASCII)))
            throw new ImportConflictException(
                    "Preview confirmation expired or does not match this operator, file or profile version. Preview again; inspect existing jobs if a response was lost.");
    }

    private String sign(String id, int version, String actor, String payload) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(receiptKey, "HmacSHA256"));
            return java.util.HexFormat.of()
                    .formatHex(mac.doFinal(("vmarket-supplier-preview:v1:" + id + ":" + version + ":"
                                    + LegacyImportService.sha256(actor.getBytes(StandardCharsets.UTF_8)) + ":"
                                    + payload)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Preview receipt signing unavailable", failure);
        }
    }

    Prepared prepare(IntegrationProfileService.Profile profile, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > dev.vmarket.shared.RequestBodyLimits.CSV_MAX_BYTES)
            throw new InvalidImportException("CSV input must contain between 1 byte and 5 MB.");
        var canonical = new StringWriter();
        var domainErrors = new java.util.HashMap<Integer, String>();
        var sourceRows = new ArrayList<SourceRow>();
        try (var reader = new InputStreamReader(
                        new ByteArrayInputStream(bytes),
                        LegacyImportService.supportedCharset(profile.encoding())
                                .newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT));
                var parser = CSVParser.parse(
                        reader,
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader()
                                .setSkipHeaderRecord(true)
                                .setDuplicateHeaderMode(org.apache.commons.csv.DuplicateHeaderMode.DISALLOW)
                                .setTrim(true)
                                .get())) {
            if (!parser.getHeaderNames().containsAll(profile.mapping().values()))
                throw new InvalidImportException(
                        "Missing required source headers. Required mapping: sku, name and on_hand. Review the selected profile's column names.");
            int count = 0;
            for (var record : parser) {
                if (++count > 1000) throw new InvalidImportException("CSV input must contain at most 1000 data rows.");
                int row = count + 1;
                if (!record.isConsistent())
                    throw new InvalidImportException(
                            "CSV row " + row + " must have the same number of fields as its header.");
                String sku = record.get(profile.mapping().get("sku")).trim();
                if (profile.skuNormalization().equals("TRIM_UPPER")) sku = sku.toUpperCase(Locale.ROOT);
                String name = record.get(profile.mapping().get("name")).trim();
                String stock = record.get(profile.mapping().get("on_hand")).trim();
                sourceRows.add(new SourceRow(row, sku, name, stock));
            }
        } catch (java.io.IOException | java.io.UncheckedIOException | IllegalArgumentException failure) {
            throw new InvalidImportException(
                    "CSV structure is invalid or bytes cannot be decoded with profile encoding " + profile.encoding()
                            + ". Use the original file; do not relabel its encoding.");
        }
        if (sourceRows.isEmpty()) throw new InvalidImportException("CSV input does not contain data rows.");
        // One bounded catalog read, not one network round-trip per CSV row.
        var catalog = jdbc
                .sql("""
                SELECT p.sku,v.code AS vendor_code,p.price_minor,s.version,s.reserved,p.version AS product_version
                FROM product p JOIN vendor v ON v.id=p.vendor_id JOIN inventory_stock s ON s.product_id=p.id
                WHERE p.sku IN (:skus) AND p.active=TRUE AND v.active=TRUE
                """)
                .param(
                        "skus",
                        sourceRows.stream().map(SourceRow::sku).distinct().toList())
                .query(Canonical.class)
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Canonical::sku, java.util.function.Function.identity()));
        try (var printer = new CSVPrinter(canonical, CSVFormat.DEFAULT)) {
            printer.printRecord(
                    "schema_version",
                    "sku",
                    "vendor_code",
                    "name",
                    "on_hand",
                    "price_minor",
                    "expected_stock_version",
                    "expected_product_version",
                    "preview_reserved");
            for (var source : sourceRows) {
                int row = source.row();
                String sku = source.sku();
                String name = source.name();
                String stock = source.stock();
                var product = catalog.get(sku);
                if (product == null) domainErrors.put(row, "UNKNOWN_SKU: use an active canonical product");
                else if (!product.vendorCode().equals(profile.vendorCode()))
                    domainErrors.put(row, "VENDOR_MISMATCH: SKU is outside this supplier profile");
                try {
                    int quantity = Integer.parseInt(stock);
                    if (quantity < 0 || quantity > 1_000_000)
                        domainErrors.put(row, "on_hand must be an integer between 0 and 1000000");
                    else if (product != null && quantity < product.reserved())
                        domainErrors.put(row, "BELOW_RESERVED_STOCK: quantity is below current reservations");
                } catch (NumberFormatException failure) {
                    domainErrors.put(row, "on_hand must be an integer between 0 and 1000000");
                }
                printer.printRecord(
                        "2",
                        sku,
                        profile.vendorCode(),
                        name,
                        stock,
                        product == null ? 0 : product.priceMinor(),
                        product == null ? 0 : product.version(),
                        product == null ? 0 : product.productVersion(),
                        product == null ? 0 : product.reserved());
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not prepare normalized CSV", failure);
        }
        byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.UTF_8);
        var rows = imports.parse(canonicalBytes, StandardCharsets.UTF_8, false).stream()
                .map(row -> {
                    String extra = domainErrors.get(row.rowNumber());
                    var product = catalog.get(row.sku());
                    return new LegacyImportService.StagedRow(
                            row.rowNumber(),
                            row.sku(),
                            row.vendorCode(),
                            row.name(),
                            row.onHand(),
                            row.priceMinor(),
                            row.versioned(),
                            row.expectedStockVersion(),
                            product == null ? null : product.productVersion(),
                            row.valid() && extra == null,
                            extra == null ? row.error() : row.error() == null ? extra : row.error() + "; " + extra);
                })
                .toList();
        return new Prepared(canonicalBytes, rows);
    }

    private record SourceRow(int row, String sku, String name, String stock) {}

    private record Canonical(
            String sku, String vendorCode, int priceMinor, long version, int reserved, long productVersion) {}
}
