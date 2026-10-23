# Acceptance criteria and traceability

Tests named below are in `backend/src/test/java/dev/vmarket/VMarketApplicationTest.java` unless a script/source path is shown. They use real PostgreSQL/Redis Testcontainers. The browser runner uses separate disposable real services.

| ID | Acceptance criterion | Actual implementation / proof |
| --- | --- | --- |
| P0.1 | Declarative version, encoding, vendor, required/type/range policy | `IntegrationProfileService`; `supplierProfilesExposeVersionedMappingsWithoutCreatingImportWork`, `profilesExposeRequiredFieldsAndApprovedTypeRangePolicy` |
| P0.2 | Immutable definitions, audited activation, retained history | V14 trigger/FK; `operatorPublishesImmutableProfileVersionAndAuditsActivation`, `supplierProfileMigrationPreservesLegacyHistoryAndMakesDefinitionsImmutable` |
| P0.3 | Same bytes cannot replay under another version/legacy namespace | Reused checksum lock/unique constraint; `identicalSupplierBytesCannotReplayThroughAnotherProfileVersionOrLegacyEndpoint` |
| P1.1 | Strict original UTF-8/CP932, bounded rows/body, required headers | `supplierProfilesDecodeOriginalCp932AndRejectWrongEncodingHeadersAndOversizedInputsBeforeStaging`; original Java/BFF limit regressions |
| P1.2 | Preview mapping/counts/errors without business/import writes | `supplierPreviewMapsAndQuarantinesRowsWithoutAnyBusinessWrites`; real browser compares jobs/orders/movements/product+stock version totals |
| P1.3 | Explicit approval binds bytes/operator/profile/enrichment | HMAC receipt; `previewConfirmationIsBoundToTheOperatorBytesAndCurrentCatalogState`; browser approval invalidation |
| P1.4 | Bad cells cannot poison valid staging | `oversizedRejectedSupplierCellsCannotPoisonTheValidRowsStagingTransaction`; rejected text omitted, row/reason retained |
| P2.1 | Existing ledger selects, previews, confirms and inspects | `components/supplier-onboarding.tsx`, `components/import-inspection.tsx`; SSR tests + `tools/qa_supplier_onboarding.py` |
| P2.2 | Export contains no file cells/receipt | Real browser checks downloaded JSON's exact allowlisted keys and absence of sample name |
| P2.3 | Async acknowledgement is not completion; safe restart only | `confirmedSupplierPreviewUsesExistingDurableImportAndRawByteDedup`; original injected FAILED → inspected restart browser regression |
| P3.1 | Concurrent confirmations create one job/movement | `concurrentSupplierConfirmationsShareOneDurableIdentityAndOneMovement`; original competing-dispatcher regressions |
| P3.2 | Inactive/stale approval cannot overwrite later state | `stagedSupplierSnapshotRejectsLaterCatalogChangesAndInactiveProfilesCannotConfirm`; original stale-stock/checkout tests |
| P3.3 | Partial commit/process crash resumes without duplicate effects | Existing `tools/qa_started_recovery.py` extended with supplier profile; real 50 committed rows, owned JVM kill, same Batch instance resumes to 51 unique movements |
| P3.4 | Stale workers / unsafe recovery remain fenced | Original `recoveredWorkerCannotRewriteJobStepOrExecutionContextMetadata`, `expiredOwnershipCanBeFencedWithoutLettingTheOldWorkerAdoptOrFailTheNewGeneration`, `unknownBatchMetadataIsBlockedRatherThanBlindlyRestarted`, `repeatedOwnerLossStopsAfterThreeRecoveriesAndRequiresReview` |
| P3.5 | Database failure does not partially commit a chunk | `supplierDatabaseFailureRollsBackChunkAndUsesOnlyTheExistingSafeRestart` injects PostgreSQL SQLSTATE 08006; real STARTED script separately pauses disposable DB and observes status read exceeding a 1,500 ms client budget |
| SEC.1 | OPERATOR/trust/CSRF/BLOCKED boundaries preserved | `onboardingPreservesOperatorCsrfAndOpsSecretBoundariesAndBlocksUnsafeRestarts`; real anonymous 401/missing-CSRF 403 and original revocation tests |
| MEASURE.1 | Seeded counts, duration, dedup, reconciliation | `seededSupplierOnboardingBenchmarkProducesMeasuredImportAndReconciliationEvidence`; [metrics](evidence/benchmark.json) |
| DOC.1 | Problem, contract, acceptance, handoff, demo, measured impact | This directory and [PR summary](pr-summary.md) |

## Local verification

Windows 11 / Java 21 / Node 24 / Docker Linux containers. Tests use synthetic credentials/data, not production.

| Gate | Result |
| --- | --- |
| Baseline Maven verify | 98 tests passed at `ff9fde325f3afe7be556deb28dc89d0bfe28770d` |
| Feature Maven verify | 115 passed, 0 failures/errors; fresh migrations and V13→V14 history/immutability |
| Baseline frontend verify | 52 tests + lint/typecheck/Next production build passed |
| Feature frontend verify | 58 tests + ESLint/typecheck/Next production build passed |
| Dependency audit | `npm audit --audit-level=high`: 0 vulnerabilities; no dependency/lockfile changes |
| Cloudflare production bundle | `npm run build:worker` passed; `.open-next/worker.js` generated, **not deployed** |
| Docker | `docker build --tag v-market-backend:fde-local backend` passed; runtime user `10001:10001` |
| Infrastructure | Compose config / Helm lint/template passed; informational Helm icon recommendation only |
| Real browser | `tools/verify-operator-session.ps1`: supplier acceptance + original checkout/import/auth regression; [sanitized result](evidence/browser-results.json) |
| Recovery / outage | [Recovery record](evidence/recovery.json); injected expiry/orphan-query termination explicitly identified |
| Fixture browser / CI contracts | Existing `npm run test:e2e` extended for preview, disabled confirm, editing invalidates approval, confirm and mobile width |

Transient logs: `backend/target/fde-*.log`, JUnit XML: `backend/target/surefire-reports`, real-server logs: `backend/target/operator-browser`. Sanitized JSON and screenshots are retained here; raw logs/cookies are not committed.

## CI provenance

[Existing workflow](../../.github/workflows/ci.yml) runs frontend verify/audit/browser/Worker, Maven verify/Docker and Compose/Helm/Terraform on PRs/main pushes. New tests are included by these existing commands. The real process-kill/outage browser runner is a **local Windows acceptance gate**; fixture E2E in Linux CI is not that proof.

This feature was **not pushed, merged or deployed**; no feature GitHub Actions run is claimed. Local green commands are not remote CI success. Terraform resources were not changed or applied; AWS is outside scope.

## Customer sign-off

- [x] Fictional supplier mappings/encodings, read-only preview and explicit approval demonstrated.
- [x] Bad rows, duplicate/alternate-version files and concurrent confirmations tested.
- [x] Same durable queue, provenance, partial recovery, database-failure rollback and manual-review boundaries verified.
- [x] Seeded applied stock reconciles with zero mismatches and zero unverified scope.
- [x] Handoff includes deployment/rollback and unknown acknowledgement.
- [ ] Real supplier validates business meaning/freshness of quantities (no real customer involved).
- [ ] Production deployment, load/SLA and remote feature CI approval (not performed).

## Synthetic local screenshots

- [Desktop preview](evidence/supplier-preview-desktop.png)
- [Mobile viewport](evidence/supplier-preview-mobile.png)
- [Original CP932 preview](evidence/supplier-cp932-preview.png)
- [Import inspection](evidence/supplier-import-inspection.png)
- [Reconciliation scope](evidence/supplier-reconciliation.png)
