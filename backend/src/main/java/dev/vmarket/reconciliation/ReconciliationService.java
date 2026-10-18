package dev.vmarket.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
    private final JdbcClient jdbc;
    private final MeterRegistry metrics;

    public ReconciliationService(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    @Transactional
    public ReconciliationReport reconcile(UUID migrationJobId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM migration_job WHERE id = :id)")
                .param("id", migrationJobId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new InvalidReconciliationException("Migration job does not exist.");
        }

        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        jdbc.sql("""
                        INSERT INTO reconciliation_run (id, migration_job_id, status, created_at)
                        VALUES (:id, :jobId, 'RUNNING', NOW())
                        """).param("id", runId).param("jobId", migrationJobId).update();

        var sourceRows = jdbc.sql("""
                        SELECT s.external_sku AS sku, s.applied_on_hand AS expected_quantity, s.price_minor AS expected_price,
                               i.on_hand AS actual_quantity, p.price_minor AS actual_price,
                               s.applied_stock_version AS expected_stock_version,i.version AS actual_stock_version,
                               s.applied_product_version AS expected_product_version,p.version AS actual_product_version
                        FROM staged_import_row s
                        LEFT JOIN product p ON p.sku = s.external_sku
                        LEFT JOIN inventory_stock i ON i.product_id = p.id
                        WHERE s.job_id = :jobId AND s.valid = TRUE AND s.applied_at IS NOT NULL
                        ORDER BY s.external_sku
                        """)
                .param("jobId", migrationJobId)
                .query(SourceState.class)
                .list();

        List<Discrepancy> discrepancies = new ArrayList<>();
        int changedRows = 0;
        int stockNotVerifiedRows = 0;
        for (var row : sourceRows) {
            boolean changed = false;
            if (row.actualQuantity() == null) {
                discrepancies.add(insert(runId, row.sku(), "MISSING_PRODUCT", "present", "missing"));
                stockNotVerifiedRows++;
                continue;
            }
            if (row.expectedStockVersion() == null) {
                stockNotVerifiedRows++;
            } else if (row.actualStockVersion() > row.expectedStockVersion()) {
                changed = true;
                stockNotVerifiedRows++;
            } else if (row.actualStockVersion() < row.expectedStockVersion()) {
                stockNotVerifiedRows++;
                discrepancies.add(insert(
                        runId,
                        row.sku(),
                        "STOCK_VERSION_REGRESSION",
                        row.expectedStockVersion().toString(),
                        row.actualStockVersion().toString()));
            } else if (!row.expectedQuantity().equals(row.actualQuantity())) {
                discrepancies.add(insert(
                        runId,
                        row.sku(),
                        "QUANTITY_MISMATCH",
                        row.expectedQuantity().toString(),
                        row.actualQuantity().toString()));
            }
            if (row.expectedProductVersion() != null && row.actualProductVersion() > row.expectedProductVersion()) {
                changed = true;
            } else if (row.expectedProductVersion() != null
                    && row.actualProductVersion() < row.expectedProductVersion()) {
                discrepancies.add(insert(
                        runId,
                        row.sku(),
                        "PRODUCT_VERSION_REGRESSION",
                        row.expectedProductVersion().toString(),
                        row.actualProductVersion().toString()));
            } else if (row.expectedProductVersion() != null
                    && !row.expectedPrice().equals(row.actualPrice())) {
                discrepancies.add(insert(
                        runId,
                        row.sku(),
                        "PRICE_MISMATCH",
                        row.expectedPrice().toString(),
                        row.actualPrice().toString()));
            }
            if (changed) changedRows++;
        }

        jdbc.sql("""
                        UPDATE reconciliation_run SET mismatch_count = :count, changed_since_import_rows=:changed, stock_not_verified_rows=:unverified, status = 'COMPLETED', completed_at = NOW()
                        WHERE id = :id
                        """)
                .param("count", discrepancies.size())
                .param("changed", changedRows)
                .param("unverified", stockNotVerifiedRows)
                .param("id", runId)
                .update();
        metrics.summary("vmarket.reconciliation.mismatches").record(discrepancies.size());
        return new ReconciliationReport(
                runId,
                migrationJobId,
                "COMPLETED",
                discrepancies.size(),
                changedRows,
                stockNotVerifiedRows,
                createdAt,
                discrepancies);
    }

    @Transactional(readOnly = true)
    public List<ReconciliationReport> recent() {
        return jdbc.sql("""
                        SELECT id, migration_job_id, status, mismatch_count, changed_since_import_rows, stock_not_verified_rows, created_at
                        FROM reconciliation_run ORDER BY created_at DESC LIMIT 20
                        """)
                .query((rs, rowNum) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ReconciliationReport(
                            id,
                            rs.getObject("migration_job_id", UUID.class),
                            rs.getString("status"),
                            rs.getInt("mismatch_count"),
                            rs.getInt("changed_since_import_rows"),
                            rs.getInt("stock_not_verified_rows"),
                            rs.getTimestamp("created_at").toInstant(),
                            findDiscrepancies(id));
                })
                .list();
    }

    private List<Discrepancy> findDiscrepancies(UUID runId) {
        return jdbc.sql("""
                        SELECT id, sku, discrepancy_type AS type, expected_value, actual_value, status, resolution_note
                        FROM reconciliation_discrepancy WHERE reconciliation_run_id = :runId ORDER BY sku, discrepancy_type
                        """).param("runId", runId).query(Discrepancy.class).list();
    }

    private Discrepancy insert(UUID runId, String sku, String type, String expected, String actual) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO reconciliation_discrepancy (
                            id, reconciliation_run_id, sku, discrepancy_type, expected_value, actual_value
                        ) VALUES (:id, :runId, :sku, :type, :expected, :actual)
                        """)
                .param("id", id)
                .param("runId", runId)
                .param("sku", sku)
                .param("type", type)
                .param("expected", expected)
                .param("actual", actual)
                .update();
        return new Discrepancy(id, sku, type, expected, actual, "OPEN", null);
    }

    private record SourceState(
            String sku,
            Integer expectedQuantity,
            Integer expectedPrice,
            Integer actualQuantity,
            Integer actualPrice,
            Long expectedStockVersion,
            Long actualStockVersion,
            Long expectedProductVersion,
            Long actualProductVersion) {}
}
