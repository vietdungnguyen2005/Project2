package dev.vmarket.migration;

import java.io.IOException;
import java.io.StringWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventorySnapshotController {
    private final JdbcClient jdbc;
    private final OpsAuthorizer authorizer;

    public InventorySnapshotController(JdbcClient jdbc, OpsAuthorizer authorizer) {
        this.jdbc = jdbc;
        this.authorizer = authorizer;
    }

    @GetMapping(value = "/api/ops/inventory/snapshot", produces = "text/csv;charset=UTF-8")
    ResponseEntity<String> export(@RequestHeader("X-V-Market-Ops-Secret") String secret) throws IOException {
        authorizer.authorize(secret);
        // One SQL statement reads quantities and versions from the same PostgreSQL snapshot.
        var rows = jdbc.sql("""
                SELECT p.sku,v.code AS vendor_code,p.name,s.on_hand,p.price_minor,s.version AS stock_version
                FROM product p JOIN vendor v ON v.id=p.vendor_id JOIN inventory_stock s ON s.product_id=p.id
                WHERE p.active=TRUE ORDER BY p.sku
                """).query(SnapshotRow.class).list();
        var text = new StringWriter();
        try (var csv = new CSVPrinter(
                text,
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader(
                                "schema_version",
                                "sku",
                                "vendor_code",
                                "name",
                                "on_hand",
                                "price_minor",
                                "expected_stock_version")
                        .get())) {
            for (var row : rows)
                csv.printRecord(
                        2, row.sku(), row.vendorCode(), row.name(), row.onHand(), row.priceMinor(), row.stockVersion());
        }
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=\"inventory-snapshot.csv\"")
                .body(text.toString());
    }

    private record SnapshotRow(
            String sku, String vendorCode, String name, int onHand, int priceMinor, long stockVersion) {}
}
