package dev.vmarket.reconciliation;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record ReconciliationRequest(@NotNull UUID migrationJobId) {}

record ReconciliationReport(
        UUID id,
        UUID migrationJobId,
        String status,
        int mismatchCount,
        Instant createdAt,
        List<Discrepancy> discrepancies) {}

record Discrepancy(
        UUID id,
        String sku,
        String type,
        String expectedValue,
        String actualValue,
        String status,
        String resolutionNote) {}
