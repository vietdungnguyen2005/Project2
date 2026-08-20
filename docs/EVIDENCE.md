# Evidence matrix

| Recruiter-facing claim | Implementation | Automated proof |
| --- | --- | --- |
| Checkout cannot oversell | PostgreSQL row locks acquired in sorted SKU order inside one Spring transaction | `concurrentCheckoutCannotOversellInventory` asserts responses `201/409` and final stock `7` |
| Network retry is safe | Unique idempotency key and deterministic order identity | `idempotentCheckoutReturnsTheSameOrderWithoutDoubleDecrementingStock` |
| Prices are trusted | Java reloads price and inventory from PostgreSQL and calculates totals | `checkoutCreatesATrackedOrderAndConsumesInventoryAtomically` |
| Tracking protects PII | SHA-256 token hash stored; lookup requires order number + opaque token | `customerCanTrackAnOrderOnlyWithItsOpaqueToken` |
| Operations are auditable | Forward-only fulfillment state machine and JSONB audit ledger | `operatorCanAdvanceFulfillmentWithAnAuditedTransition` |
| Japanese legacy data is supported | Explicit Windows-31J decoder and staged validation | `legacyImportDecodesJapaneseCp932Feeds` uses real CP932 bytes |
| Bad legacy rows do not poison good rows | Staging/quarantine and restartable 50-row Spring Batch chunks | `legacyImportAppliesValidRowsAndQuarantinesInvalidRows` |
| Migration completeness is measurable | Snapshot-to-canonical reconciliation with discrepancy records | `reconciliationMakesPostMigrationDriftVisible` |
| Redis is used, not decorative | Five-minute cache-aside, fail-open reads, invalidation, Micrometer outcomes | `catalogResponseIsCachedInRedis` runs against Redis Testcontainer |
| Browser cannot forge trust headers | Next.js BFF overwrites allow-listed headers; Spring filter rejects bypass | `backend-proxy.test.ts` and `mutationsRejectRequestsThatBypassTheCloudflareBff` |
| Frontend contracts remain typed | Strict TypeScript adapters for catalog, orders, tracking, and operations | Vitest contract tests plus `npm run typecheck` |
| Delivery is repeatable | Non-root image, Compose, Helm probes/resources/security context, CI | `docker build`, `docker compose config`, `helm lint/template`, GitHub Actions |

Machine-generated JUnit reports live under `backend/target/surefire-reports` after verification. Browser screenshots are generated into `docs/evidence` by `npm run test:e2e` and intentionally reflect the current revision rather than hand-written claims.
