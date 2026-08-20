package dev.vmarket.migration;

import java.time.Instant;
import java.util.UUID;

record MigrationSummary(
        UUID id,
        String sourceName,
        String encoding,
        String status,
        int totalRows,
        int validRows,
        int appliedRows,
        int rejectedRows,
        int checkpointRow,
        Instant createdAt) {}
