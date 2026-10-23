# Measured impact — synthetic, local, reproducible

## What the evidence establishes

The extension demonstrates a repeatable mapping/preview/approval process on top of the existing importer. It detects invalid source data before application, prevents byte-identical remapping from creating a second job, and retains existing chunk/recovery correctness. It does **not** measure real operator labor saved, customer adoption, production latency, availability or financial benefit.

## Seeded benchmark

Reproduce from `backend`:

```powershell
.\mvnw.cmd '-Dtest=VMarketApplicationTest#seededSupplierOnboardingBenchmarkProducesMeasuredImportAndReconciliationEvidence' test
```

The same test also runs during `verify`; generated output is `backend/target/onboarding-benchmark.json`. It creates 200 synthetic existing catalog SKUs with deterministic quantities using Java Random seed **20261023**, plus 20 rejected numeric rows. No production data is read. Fixture setup and Spring/Testcontainers startup occur **before** the measured interval.

[Recorded final verification sample](evidence/benchmark.json), Windows 11 / Java 21 / PostgreSQL 17.6 + Redis 8.2 Docker Testcontainers:

| Measurement | Observed |
| --- | --- |
| Source rows | 220 |
| Accepted / rejected | 200 / 20 |
| Preview duration | 51.07 ms |
| Preview through confirm + completed import | 1,195.95 ms |
| End-to-end through duplicate check, operator authentication and reconciliation | 1,582.44 ms |
| Accepted rows per second through completed import | 167.23 |
| Jobs after duplicate confirmation | 1 |
| Inventory movements after duplicate | 200 |
| Reconciliation mismatches | 0 |
| Stock rows without comparable proof | 0 |

The original CSV SHA-256 is recorded in JSON for dataset identity. This is a **single local run**, not a distribution or capacity claim. Direct service dispatch is used for deterministic timing; HTTP/BFF network, scheduled tick delay, cold starts, WAN database latency and production contention are not included. End-to-end means the described test workflow, not a customer-facing SLA.

Baseline correctness gates were recorded before edits: backend 98 tests, frontend 52 tests and lint/typecheck/build. There is **no reproducible pre-feature mapping/preview benchmark**, so no before/after speedup or time-savings percentage is asserted. Batched catalog enrichment avoids one query per CSV row by design; that alone is not a measured production improvement.

## Process recovery and database interruption

[Recovery evidence](evidence/recovery.json) comes from the existing real-server runner, extended to publish/activate a supplier profile and confirm a 51-row file through the new API:

1. The real scheduled worker commits 50 stock movements and remains Batch STARTED in a deliberately held second chunk.
2. The runner pauses **only its disposable PostgreSQL container**. A status read cannot complete within its 1,500 ms client observation budget; the container is unpaused in `finally`. The previously committed count remains 50.
3. The runner kills only its owned Java process, ends the orphaned sleeping **test query**, and injects lease expiry in the disposable DB.
4. The restarted existing dispatcher/recovery resumes stored rows: **51 applied rows, 51 unique movements, one Batch JobInstance, two executions, one recovery**. No profile re-interpretation or new queue is used.

The JSON contains observation durations for reproduction diagnostics, not recovery-time promises. Startup/login time and natural 60-second expiry are not measured by those fields. The short paused-DB observation does not prove a particular server 503/504 deadline or long-outage availability; the client timeout is explicitly identified. PostgreSQL SQLSTATE 08006 injection in the integration suite separately verifies caught database-failure rollback and safe restart. Neither test proves all possible commit/crash windows or a cross-region outage policy.

## Real browser acceptance

[Sanitized browser result](evidence/browser-results.json): read-only business-state comparison, actual UTF-8/CP932 preview, one accepted/one rejected row, approval, report download, async completion, inspection, one movement after duplicate, zero mismatches/unverified scope, mobile width and auth/CSRF. Screenshots and reproduction instructions are in [acceptance criteria](acceptance-criteria.md) and [demo script](customer-demo-script.md).

## Remaining risks / limits

- One shared store; vendor binding does not isolate tenants or supplier users.
- Raw-byte dedup is not semantic event dedup: changed bytes can represent the same business snapshot. Supplier sequence/as-of identity is not implemented.
- Preview fences races after review, not the age/truth of quantities before preview. Operators must validate source business freshness.
- Existing-catalog updates only; no automatic product/vendor creation, mapping editor or source system connector.
- Receipts expire after ten minutes; stable backend ops secret and sensible clock synchronization are required. Lost acknowledgements still require inspection, not automatic replay.
- Existing bounded lease recovery is retained; unsafe metadata and recovery exhaustion require manual review. No independent heartbeat or exhaustive crash-window proof was added.
- Profile list is capped at 100 versions; job list at 20 recent jobs. No full history search/pagination UI.
- Local tests and Worker/image builds passed; this feature has not run remote CI or been deployed. Free-host cold starts and production ingress/concurrent-memory limits remain deployment concerns.
