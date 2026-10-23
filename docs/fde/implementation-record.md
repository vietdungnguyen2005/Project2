# Supplier onboarding implementation record

## Baseline and scope

Base commit: `ff9fde325f3afe7be556deb28dc89d0bfe28770d`.
Branch: `feat/supplier-data-onboarding`. Existing uncommitted interview preparation is outside this change.
Before feature edits, `backend/mvnw.cmd -q verify` passed 98 tests (real PostgreSQL 17.6 and Redis 8.2 Testcontainers); `npm run verify` passed 52 tests, lint, typecheck and production Next build.

## Audit / reuse decisions

| Existing source | Verified behavior | Decision |
| --- | --- | --- |
| `LegacyImportService` | REPORT decoding, 5,000,000 bytes / 1,000 records, raw checksum advisory lock and unique constraint; five-column metadata-only or versioned stock | Reuse parser, staging, identity and restart; accept mapped canonical rows without changing original-byte identity |
| `ImportChunkService` | Groups of 50; canonical SKU/vendor and expected stock version under product/stock locks; atomic movement/proof/applied markers | Reuse; additionally fence the catalog enrichment product version for supplier rows |
| `ImportBatchConfiguration`, `ImportJobRunner` | Same jobId / Spring Batch instance across retries; applied markers select remaining work | No second job or queue |
| `ImportDispatcher`, `ImportRecoveryService`, `ImportRepositoryFence` | PostgreSQL dispatch intent, generation/token/lease fencing of business and Batch metadata; UNKNOWN or exhausted recovery is BLOCKED | No recovery rewrite; immutable staged supplier rows use the same path |
| `ReconciliationService` | Compares applied proof; later legitimate changes and unverified rows are separate from mismatches | Reuse unchanged |
| `OrderService`, `OpsOrderService` | Deterministic locks, atomic stock/order, scoped checkout replay, audited forward fulfillment | Keep commerce behavior unchanged |
| `SecurityConfiguration`, `OpsAuthorizer`, BFF, `/ops` | OPERATOR JDBC session + CSRF + server-injected BFF and ops secrets; bounded proxy | Extend the same boundaries and operations ledger |
| CI, Testcontainers, Windows QA runner | Backend integration + fixture browsers, builds/audit/container/Helm | Extend tests and existing real-server runner; do not deploy |

## Design contract before implementation

- V14 is additive. Profiles have immutable identifier/version/encoding/vendor/mapping/normalization, mutable activation with authenticated audit. Supported normalization is only TRIM or TRIM_UPPER; types/ranges are fixed server policy, never executable scripts.
- Three supplier columns map SKU/name/stock. Canonical vendor and price are read from the single-store catalog. Preview captures both product and stock versions. Unknown/wrong-vendor SKUs are rejected, not created.
- Preview is read-only, with no staged jobs. A short-lived HMAC confirmation receipt binds raw file bytes, profile definition/version, normalized rows and canonical versions. Confirmation recomputes and checks the receipt. Changed enrichment requires a new preview; changes after staging are rejected under locks.
- Global raw-byte checksum remains the identity across both old and new endpoints. Same profile/version replay returns the original job. Different profile/version (including legacy versus profiled) conflicts. Never use transformed bytes as a new dedup namespace.
- Confirmation stages mapped rows and their profile FK/audit in the existing transaction. Async acknowledgement and dispatcher/recovery remain unchanged. Activation is rechecked and locked during staging.
- Preview/report errors contain row numbers and allowlisted reasons, not raw cells. Export excludes file content, source name and confirmation receipt.

## Verification record

Incremental observed RED → GREEN checks (raw transient logs: `backend/target/fde-red-*.log` and `fde-green-*.log`):

| Missing behavior / fault established | Observed RED | GREEN proof |
| --- | --- | --- |
| Profile list / publication | 404 / 405 | Real profile list, immutable version and audited activation tests |
| Preview / confirmation / inspection | 404 endpoints | Read-only preview, durable confirmation/dedup and safe inspection tests |
| Rejected oversized SKU | PostgreSQL varying(64) overflow | Valid row applies while oversized rejected text is omitted |
| Legacy extra-column compatibility | Negative internal-named column reached a new database constraint | Adapter owns enrichment; legacy unknown columns remain ignored (`supplierEnrichmentDoesNotChangeLegacyUnknownColumnCompatibility`) |
| Explicit policy serialization | Missing requiredFields JSON path | Required/type/range/normalization policy assertion |
| Frontend supplier API / components | Missing function / module | Typed API and SSR tests |
| New BFF CSV path | 413 at the JSON budget | Original 70 KB bytes and receipt forwarded under CSV budget |
| Full positive Java version range | BFF 400 | Max Java integer version path accepted and bounded |
| Browser label ambiguity | Exact label selection timed out | Explicit aria-labelledby; fixture and real browser flows passed |

Existing recovery/fencing behavior was tested as regression, not represented as newly written behavior or a manufactured RED. The batched catalog query refactor retained the existing mapping/benchmark assertions.

Results, actual measurements, screenshots and limitations are recorded in [acceptance criteria](acceptance-criteria.md) and [impact evaluation](impact-evaluation.md). No feature CI run exists until an authorized push/PR; local checks are not described as GitHub CI success.
