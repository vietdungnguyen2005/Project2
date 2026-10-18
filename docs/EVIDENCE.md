# Evidence matrix

## Deployment evidence

Deployment files below are historical evidence, not proof that the current working-tree remediation is live. Current local fixes, commands, results and remaining limitations are tracked in [remediation evidence](evidence/remediation-2026-10-18.md).

- [Persistent free-tier deployment](evidence/live-deployment.md)
- [Short-lived AWS deployment and legacy-import proof](evidence/aws-deployment.md)
- [Machine-readable AWS verification](evidence/aws-verification.json)

## Capability evidence

| Recruiter-facing claim | Implementation | Automated proof |
| --- | --- | --- |
| Checkout cannot oversell | PostgreSQL row locks acquired in sorted SKU order inside one Spring transaction | `concurrentCheckoutCannotOversellInventory` asserts responses `201/409` and final stock `7` |
| Same-operation retry replays the original order within its window | Guest-scoped key/hash and durable protected response; new order IDs are random | `idempotentCheckoutReturnsTheSameOrderWithoutDoubleDecrementingStock` |
| Guest namespaces cannot replay each other's checkout | Domain-separated capability-scope/key hash; raw capability/key not stored for new orders | `differentGuestCapabilitiesCannotReplayEachOthersCheckoutKey`, `malformedOrMissingGuestCapabilitiesCannotCreateCheckoutWork` |
| Tracking capability is not derived from a checkout key | SecureRandom 256-bit token, hash lookup and order-bound AES-GCM replay copy | `trackingCapabilityIsIndependentOfTheCheckoutKeyButStableOnRetry`, `CheckoutTrackingTokensTest`; real encrypted response replay after forced Java restart |
| Expired/corrupt replay does not repeat the sale | 24-hour DB-clock replay guard; key/order/hash remain, unsafe response is 409 | `anExpiredCheckoutKeyCannotCreateAnotherOrderOrInvalidateItsOriginalTrackingLink`, `corruptReplayStorageCannotRotateTheTrackingCapabilityOrRepeatTheSale` |
| Replay-copy retention does not delete dedup identity | At most 100 expired ciphertexts/tick with SKIP LOCKED; live/locked rows retained | `expiredReplayCiphertextIsPurgedWithoutDeletingTheOrderKeyOrTrackingHash`, `replayCleanupIsBoundedAndSkipsAnExpiredOrderLockedByAnotherTransaction` |
| Legacy checkout key cannot silently create a scoped duplicate | No fabricated historical owner; raw legacy key conflict before new create | `aHistoricalUnscopedKeyRequiresReviewInsteadOfCreatingAnotherScopedOrder`, `fingerprintMigrationPreservesAnExistingLegacyOrder` |
| Prices are trusted | Java reloads price and inventory from PostgreSQL and calculates totals | `checkoutCreatesATrackedOrderAndConsumesInventoryAtomically` |
| Tracking protects PII | SHA-256 token hash stored; lookup requires order number + opaque token | `customerCanTrackAnOrderOnlyWithItsOpaqueToken` |
| Operations are auditable | Forward-only fulfillment state machine and JSONB audit ledger | `operatorCanAdvanceFulfillmentWithAnAuditedTransition` |
| Japanese legacy data is supported | Explicit Windows-31J decoder and staged validation | `legacyImportDecodesJapaneseCp932Feeds` uses real CP932 bytes |
| Bad legacy rows do not poison good rows | Staging/quarantine and independently committed 50-row groups | `legacyImportAppliesValidRowsAndQuarantinesInvalidRows` |
| Controlled import failure can resume without repeated movements | Same job/JobInstance, skip applied markers, completed replay no-op | `aFailed120RowImportRestartsTheSameInstanceWithoutRepeatingCommittedMovements` |
| A second restart does not wait behind an active chunk | Nonwaiting DB claim; busy/active job returns 409 | `aSecondRestartIsRejectedPromptlyWhileTheFirstWorkerHoldsTheJobRow` |
| Work persistence is atomic with staging | V7 work ledger inserted in the staging transaction | `anUploadedImportHasADurableWorkRecordAndTerminalOutcome`, `aStagingFailureRollsBackTheJobAndItsDurableWorkTogether` |
| Async acknowledgement precedes execution | `Prefer: respond-async` returns 202 after job/work commit; explicit dispatch intent in V8 | `asynchronousUploadReturnsDurablyStagedWorkWithoutRunningItInTheRequest`, `dispatcherConsumesCommittedAsyncWorkButLeavesLegacyReadyWorkAlone` |
| Async job is not applied twice by competing dispatchers | Job-row SKIP LOCKED and generation/token claims | `competingDispatchersConsumeAQueuedJobOnlyOnce` |
| Duplicate concurrent file uploads share one durable identity | Checksum-scoped advisory transaction lock around lookup and staging | `concurrentDuplicateAsyncUploadsCreateOneDurableJobInsteadOfFailingWithDuplicateChecksum` |
| READY work survives process loss before launch | Job/work persisted outside the JVM; scheduler picks it up after restart | `tools/qa_durable_dispatch.py` with `tools/verify-operator-session.ps1` force-kills/restarts only its owned Java process; **not STARTED recovery** |
| Expired async STARTED work resumes applied-row selection | Atomic metadata retirement/generation revocation/requeue; bounded retries; no reset of applied markers | `recoveryResumesThePendingRowAfterFiftyRealStockMovementsAlreadyCommitted`; `tools/qa_started_recovery.py` force-kill with injected expiry, not a timing/SLA proof |
| Late workers cannot overwrite Batch metadata | Fenced standard JDBC repository shares the ownership transaction | `recoveredWorkerCannotRewriteJobStepOrExecutionContextMetadata`, `importMetadataCannotCreateAnExecutionWithoutItsCurrentWorkerScope` |
| Recovery cannot partially retire metadata | Metadata writes and generation/state/audit update join one transaction | `recoveryMetadataRetirementAndGenerationRevocationRollBackTogetherOnFailure` |
| Completed metadata needs acknowledgement, not another import | Business completion retains claim through Batch finalization | `recoveryAcknowledgesCompletedMetadataWithoutLaunchingAnotherExecution`, `completedImportAlsoCompletesItsFencedBatchMetadataBeforeReleasingOwnership` |
| Unsafe or repeated recovery stops for review | UNKNOWN/mismatched metadata and exhausted allowance become BLOCKED | `unknownBatchMetadataIsBlockedRatherThanBlindlyRestarted`, `repeatedOwnerLossStopsAfterThreeRecoveriesAndRequiresReview` |
| Failed async work can be restarted from UI | Poll active jobs; explicit FAILED restart; no automatic mutation retry | `asyncRestartQueuesExistingFailedJobAndOnlyTheDispatcherAppliesIt` and real fault-injection browser smoke |
| Upgrade does not steal existing synchronous work | V8 dispatch flag defaults false | `dispatchMigrationDoesNotEnqueueExistingSynchronousReadyWork` |
| Stale workers cannot change business data or fail a replacement owner | Token/generation/DB-clock lease checked under the job/work lock protocol | `expiredOwnershipCanBeFencedWithoutLettingTheOldWorkerAdoptOrFailTheNewGeneration`, `losingTheLeaseInsideAChunkRollsBackStockProofAndAfterCommitEffects` |
| Historical active jobs are not silently claimed on upgrade | V7 uses BLOCKED with no synthetic owner/generation | `workMigrationPreservesOldJobsWithoutInventingOwnersForActiveExecutions` |
| Batch Observation meters register without tag collisions | Separate Observation namespace from legacy Batch meters | `batchObservationMetersDoNotCollideWithLegacyBatchMeters` (real Prometheus registry enabled in tests) |
| Old stock snapshots cannot restore already sold inventory | Expected-version check under product/stock locks; legacy feeds are metadata-only | `anOldVersionedStockSnapshotCannotRestoreAlreadySoldInventory`, `unversionedLegacyFilesOnlyChangeMetadataAndCannotClaimStockVerification` |
| Reconciliation exposes its scope | Applied baseline, changed-since-import/unverified counts and discrepancy records | `aLegitimateSaleAfterImportIsChangedSinceImportNotCorruption`, `reconciliationMakesPostMigrationDriftVisible` |
| Import rollback leaves no successful side effects | Movement, stock, metadata, proof/counters atomic; metrics/invalidation after commit | `movementFailureRollsBackTheWholeChunkIncludingProofMetricsAndInvalidation` |
| Stock policy upgrade preserves history | Additive V5/V6, no synthetic version proof for old imports | `stockPolicyMigrationPreservesHistoryWithoutInventingVersionProof` |
| Redis is used, not decorative | Five-minute cache-aside, fail-open reads, invalidation, Micrometer outcomes | `catalogResponseIsCachedInRedis` runs against Redis Testcontainer |
| A warm cache cannot hide committed checkout stock | Metadata-only Redis v2 plus current stock/active eligibility from PostgreSQL on each GET | `aWarmCatalogCacheCannotHideStockConsumedByCheckout`, `catalogCacheContainsOnlyMetadataAndCannotKeepAnInactiveProductVisible`; real BFF catalog before/after sale |
| Invalid file structure/encoding cannot create import work | REPORT decoding, duplicate-header rejection, consistent row widths and 1,000-record bound before staging | `malformedUtf8IsRejectedBeforeAnyImportJobIsStaged`, `malformedCp932AlsoRejectsTheWholeFileBeforeStaging`, `duplicateHeadersCannotSilentlyChooseOneOfTwoStockColumns`, `anImportOverOneThousandDataRowsIsRejectedBeforeStaging` |
| Body budgets do not rely only on Content-Length | Independent BFF stream counting/cancel and Java bounded controller reads, explicit 413 | `oversizedCsvHasAnExplicitPayloadLimitResponse`, `RequestBodyLimitAdviceTest`, `backend-proxy.test.ts` |
| Duplicate file cannot silently change encoding interpretation | Validate encoding before checksum lookup and under locked duplicate recheck | `aDuplicateUploadCannotBypassEncodingValidation`, `byteIdenticalUploadsWithDifferentDeclaredEncodingsConflict` |
| Browser cannot forge trust headers | Next.js BFF overwrites allow-listed headers; Spring filter rejects bypass | `backend-proxy.test.ts` and `mutationsRejectRequestsThatBypassTheCloudflareBff` |
| Frontend contracts remain typed | Strict TypeScript adapters for catalog, orders, tracking, and operations | Vitest contract tests plus `npm run typecheck` |
| Delivery is repeatable | Non-root image, Compose, Helm probes/resources/security context, CI | `docker build`, `docker compose config`, `helm lint/template`, GitHub Actions |

Checkout reload/unknown-outcome evidence is recorded in [the remediation log](evidence/remediation-2026-10-18.md): scoped GET recovery, unused-key closure, capacity race, client identity fencing and real upstream-commit/browser-response-abort scenarios. A 404 never proves the absence of a delayed request.

Machine-generated JUnit reports live under `backend/target/surefire-reports` after verification. Browser screenshots are generated into `docs/evidence` by `npm run test:e2e` and intentionally reflect the current revision rather than hand-written claims.
