package dev.vmarket.migration;

import dev.vmarket.catalog.CatalogCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportChunkService {
    private static final int CHUNK_SIZE = 50;
    private final JdbcClient jdbc;
    private final MeterRegistry metrics;
    private final CatalogCache catalogCache;

    public ImportChunkService(JdbcClient jdbc, MeterRegistry metrics, CatalogCache catalogCache) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.catalogCache = catalogCache;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int applyNextChunk(UUID jobId) {
        var rows = jdbc.sql("""
                        SELECT row_number, external_sku, product_name, vendor_code, on_hand, price_minor
                        FROM staged_import_row
                        WHERE job_id = :jobId AND valid = TRUE AND applied_at IS NULL
                        ORDER BY row_number LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("jobId", jobId)
                .param("limit", CHUNK_SIZE)
                .query(ImportRow.class)
                .list();
        if (rows.isEmpty()) {
            finish(jobId);
            return 0;
        }

        for (var row : rows) {
            var product = findProduct(row.externalSku());
            if (product == null) {
                reject(jobId, row.rowNumber(), "SKU does not exist in the canonical catalog");
                continue;
            }
            if (!product.vendorCode().equals(row.vendorCode())) {
                reject(jobId, row.rowNumber(), "Vendor code does not match the canonical product");
                continue;
            }
            if (row.onHand() < product.reserved()) {
                reject(jobId, row.rowNumber(), "Imported stock is below currently reserved quantity");
                continue;
            }

            jdbc.sql("""
                            UPDATE product SET name = :name, price_minor = :price,
                                version = version + 1, updated_at = NOW()
                            WHERE id = :id
                            """)
                    .param("name", row.productName())
                    .param("price", row.priceMinor())
                    .param("id", product.id())
                    .update();
            jdbc.sql("""
                            UPDATE inventory_stock SET on_hand = :onHand,
                                version = version + 1, updated_at = NOW()
                            WHERE product_id = :id
                            """)
                    .param("onHand", row.onHand())
                    .param("id", product.id())
                    .update();
            jdbc.sql("""
                            INSERT INTO inventory_movement (
                                id, product_id, movement_type, quantity_delta, reason, reference_id
                            ) VALUES (:movementId, :productId, 'LEGACY_IMPORT', :delta, 'Legacy stock synchronization', :jobId)
                            """)
                    .param("movementId", UUID.randomUUID())
                    .param("productId", product.id())
                    .param("delta", row.onHand() - product.onHand())
                    .param("jobId", jobId.toString())
                    .update();
            jdbc.sql("UPDATE staged_import_row SET applied_at = NOW() WHERE job_id = :jobId AND row_number = :row")
                    .param("jobId", jobId)
                    .param("row", row.rowNumber())
                    .update();
            jdbc.sql("""
                            UPDATE migration_job SET applied_rows = applied_rows + 1,
                                checkpoint_row = GREATEST(checkpoint_row, :row), status = 'RUNNING', updated_at = NOW()
                            WHERE id = :jobId
                            """).param("row", row.rowNumber()).param("jobId", jobId).update();
            metrics.counter("vmarket.legacy.import.rows", "outcome", "applied").increment();
        }
        catalogCache.invalidate();
        return rows.size();
    }

    private ProductState findProduct(String sku) {
        return jdbc.sql("""
                        SELECT p.id, v.code AS vendor_code, s.on_hand, s.reserved
                        FROM product p JOIN vendor v ON v.id = p.vendor_id
                        JOIN inventory_stock s ON s.product_id = p.id
                        WHERE p.sku = :sku
                        FOR UPDATE OF p, s
                        """)
                .param("sku", sku)
                .query(ProductState.class)
                .optional()
                .orElse(null);
    }

    private void reject(UUID jobId, int rowNumber, String message) {
        jdbc.sql("""
                        UPDATE staged_import_row SET valid = FALSE, validation_error = :message
                        WHERE job_id = :jobId AND row_number = :row
                        """)
                .param("message", message)
                .param("jobId", jobId)
                .param("row", rowNumber)
                .update();
        jdbc.sql("""
                        UPDATE migration_job SET valid_rows = valid_rows - 1, rejected_rows = rejected_rows + 1,
                            checkpoint_row = GREATEST(checkpoint_row, :row), status = 'RUNNING', updated_at = NOW()
                        WHERE id = :jobId
                        """).param("row", rowNumber).param("jobId", jobId).update();
        metrics.counter("vmarket.legacy.import.rows", "outcome", "rejected").increment();
    }

    private void finish(UUID jobId) {
        jdbc.sql("""
                        UPDATE migration_job
                        SET status = CASE WHEN rejected_rows > 0 THEN 'COMPLETED_WITH_ERRORS' ELSE 'COMPLETED' END,
                            updated_at = NOW()
                        WHERE id = :jobId AND status <> 'COMPLETED' AND status <> 'COMPLETED_WITH_ERRORS'
                        """).param("jobId", jobId).update();
    }

    private record ImportRow(
            int rowNumber, String externalSku, String productName, String vendorCode, int onHand, int priceMinor) {}

    private record ProductState(UUID id, String vendorCode, int onHand, int reserved) {}
}
