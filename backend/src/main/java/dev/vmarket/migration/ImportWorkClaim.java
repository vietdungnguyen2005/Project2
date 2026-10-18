package dev.vmarket.migration;

import java.util.UUID;

public record ImportWorkClaim(UUID jobId, UUID token, long generation) {}
