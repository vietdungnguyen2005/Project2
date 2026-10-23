package dev.vmarket.migration;

import dev.vmarket.catalog.CatalogCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ImportChunkService {
    private static final int CHUNK_SIZE = 50;
    private final JdbcClient jdbc;
    private final MeterRegistry metrics;
    private final CatalogCache catalogCache;
    private final ImportWorkService work;

    public ImportChunkService(
            JdbcClient jdbc, MeterRegistry metrics, CatalogCache catalogCache, ImportWorkService work) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.catalogCache = catalogCache;
        this.work = work;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int applyNextChunk(ImportWorkClaim claim) {
        if (claim == null || claim.jobId() == null) throw new ImportConflictException("A worker claim is required.");
        UUID jobId = claim.jobId();
        // Serialize chunks for this job; an empty selection cannot finish another worker's in-flight work.
        String mode = jdbc.sql("SELECT import_mode FROM migration_job WHERE id=:id FOR UPDATE")
                .param("id", jobId)
                .query(String.class)
                .single();
        work.requireOwner(claim);
        var rows = jdbc.sql("""
                SELECT row_number, external_sku, product_name, vendor_code, on_hand, price_minor, expected_stock_version, expected_product_version
                FROM staged_import_row
                WHERE job_id=:jobId AND valid=TRUE AND applied_at IS NULL
                ORDER BY external_sku, row_number LIMIT :limit FOR UPDATE
                """)
                .param("jobId", jobId)
                .param("limit", CHUNK_SIZE)
                .query(ImportRow.class)
                .list();
        if (rows.isEmpty()) {
            finish(jobId);
            // Keep ownership until Batch has also persisted terminal step/job metadata.
            work.renew(claim);
            return 0;
        }
        int applied = 0;
        int rejected = 0;
        for (var row : rows) {
            if ("LEGACY_UNVERIFIED".equals(mode)) {
                reject(
                        jobId,
                        row.rowNumber(),
                        "REUPLOAD_REQUIRED: historical pending rows have no verified stock contract");
                rejected++;
                continue;
            }
            var product = findProduct(row.externalSku());
            String error = null;
            if (product == null) error = "UNKNOWN_SKU: SKU does not exist in the canonical catalog";
            else if (!product.vendorCode().equals(row.vendorCode()))
                error = "VENDOR_MISMATCH: vendor does not match the canonical product";
            else if (row.expectedProductVersion() != null
                    && !Long.valueOf(product.productVersion()).equals(row.expectedProductVersion()))
                error = "STALE_PRODUCT_VERSION: canonical metadata changed since supplier preview";
            else if ("VERSIONED_STOCK".equals(mode)
                    && !Long.valueOf(product.stockVersion()).equals(row.expectedStockVersion()))
                error = "STALE_STOCK_VERSION: inventory changed since this snapshot";
            else if ("VERSIONED_STOCK".equals(mode) && row.onHand() < product.reserved())
                error = "BELOW_RESERVED_STOCK: imported stock is below the current reservation";
            if (error != null) {
                reject(jobId, row.rowNumber(), error);
                rejected++;
                continue;
            }
            long productVersion = jdbc.sql("""
                    UPDATE product SET name=:name,price_minor=:price,version=version+1,updated_at=NOW()
                    WHERE id=:id RETURNING version
                    """)
                    .param("name", row.productName())
                    .param("price", row.priceMinor())
                    .param("id", product.id())
                    .query(Long.class)
                    .single();
            Long stockVersion = null;
            UUID movementId = null;
            if ("VERSIONED_STOCK".equals(mode)) {
                stockVersion = jdbc.sql("""
                        UPDATE inventory_stock SET on_hand=:onHand,version=version+1,updated_at=NOW()
                        WHERE product_id=:id AND version=:expected RETURNING version
                        """)
                        .param("onHand", row.onHand())
                        .param("id", product.id())
                        .param("expected", row.expectedStockVersion())
                        .query(Long.class)
                        .single();
                movementId = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO inventory_movement(id,product_id,movement_type,quantity_delta,reason,reference_id)
                        VALUES(:id,:product,'LEGACY_IMPORT',:delta,'Version-checked stock snapshot',:job)
                        """)
                        .param("id", movementId)
                        .param("product", product.id())
                        .param("delta", row.onHand() - product.onHand())
                        .param("job", jobId.toString())
                        .update();
            }
            jdbc.sql("""
                    UPDATE staged_import_row SET applied_at=NOW(),applied_product_version=:productVersion,
                        applied_stock_version=:stockVersion,applied_on_hand=:onHand,stock_movement_id=:movement
                    WHERE job_id=:job AND row_number=:row
                    """)
                    .param("productVersion", productVersion)
                    .param("stockVersion", stockVersion)
                    .param("onHand", stockVersion == null ? null : row.onHand())
                    .param("movement", movementId)
                    .param("job", jobId)
                    .param("row", row.rowNumber())
                    .update();
            jdbc.sql("""
                    UPDATE migration_job SET applied_rows=applied_rows+1,checkpoint_row=GREATEST(checkpoint_row,:row),
                        status='RUNNING',updated_at=NOW() WHERE id=:job
                    """).param("row", row.rowNumber()).param("job", jobId).update();
            applied++;
        }
        work.renew(claim);
        recordAfterCommit(applied, rejected);
        return rows.size();
    }

    private ProductState findProduct(String sku) {
        return jdbc.sql("""
                SELECT p.id,v.code AS vendor_code,s.on_hand,s.reserved,s.version AS stock_version,p.version AS product_version
                FROM product p JOIN vendor v ON v.id=p.vendor_id JOIN inventory_stock s ON s.product_id=p.id
                WHERE p.sku=:sku FOR UPDATE OF p,s
                """)
                .param("sku", sku)
                .query(ProductState.class)
                .optional()
                .orElse(null);
    }

    private void reject(UUID jobId, int row, String error) {
        jdbc.sql(
                        "UPDATE staged_import_row SET valid=FALSE,validation_error=:error WHERE job_id=:job AND row_number=:row")
                .param("error", error)
                .param("job", jobId)
                .param("row", row)
                .update();
        jdbc.sql("""
                UPDATE migration_job SET valid_rows=valid_rows-1,rejected_rows=rejected_rows+1,
                    checkpoint_row=GREATEST(checkpoint_row,:row),status='RUNNING',updated_at=NOW() WHERE id=:job
                """).param("row", row).param("job", jobId).update();
    }

    private void recordAfterCommit(int applied, int rejected) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (applied > 0) {
                    metrics.counter("vmarket.legacy.import.rows", "outcome", "applied")
                            .increment(applied);
                    catalogCache.invalidate();
                }
                if (rejected > 0)
                    metrics.counter("vmarket.legacy.import.rows", "outcome", "rejected")
                            .increment(rejected);
            }
        });
    }

    private void finish(UUID jobId) {
        jdbc.sql("""
                UPDATE migration_job SET status=CASE WHEN rejected_rows>0 THEN 'COMPLETED_WITH_ERRORS' ELSE 'COMPLETED' END,updated_at=NOW()
                WHERE id=:job AND status NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS')
                """).param("job", jobId).update();
    }

    private record ImportRow(
            int rowNumber,
            String externalSku,
            String productName,
            String vendorCode,
            int onHand,
            int priceMinor,
            Long expectedStockVersion,
            Long expectedProductVersion) {}

    private record ProductState(
            UUID id, String vendorCode, int onHand, int reserved, long stockVersion, long productVersion) {}
}
