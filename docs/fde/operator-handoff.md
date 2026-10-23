# Operator handoff

## Routine operation

1. Sign in at `/ops` using an OPERATOR account. This is one shared store; check the selected supplier/vendor and version before any confirmation.
2. In **Supplier onboarding**, choose an active profile. Review source header mapping, encoding and fixed ranges. CP932 requires the original file upload; do not paste decoded Japanese text and call it CP932.
3. Preview. No business/import state is committed. Inspect accepted examples, rejected counts and row numbers. Export the sanitized JSON report if needed. Correct source errors and preview again; all-rejected files cannot be confirmed.

   **Confirm business freshness separately.** A three-column file has no trusted supplier sequence/as-of timestamp. Preview versions protect changes after review, not the historical age or truth of supplier quantities. Approve only a snapshot that should apply now; do not refresh stale-file approval merely to force its old numbers through.
4. Approve the checkbox and confirm within ten minutes. Input/profile changes invalidate browser approval. The server also binds approval to the actual bytes, actor, profile version and canonical enrichment.
5. 202 means durable staging, **not** success. Inspect Import runs (latest 20), refresh on a read failure, and inspect the relevant run for work state/recovery reason/errors. Active jobs poll every two seconds while visible; there is no automatic mutation replay.
6. Reconcile after application. Require both zero mismatches and an appropriate comparison scope. Changed-since-import or unverified rows mean the report is not blanket stock verification. Reconciliation does not repair stock.

## Failure / recovery decisions

| Observation | What is committed | Safe next action |
| --- | --- | --- |
| Preview validation or decode error | No staged import or business writes | Fix source/profile; preview again |
| Receipt expired/mismatch or pre-confirm catalog change | No new job (unless an earlier attempt already committed) | Inspect existing jobs if outcome uncertain; re-preview same original bytes |
| 202 / STAGED + READY | Immutable rows, job, work, profile FK, confirmation audit | Wait for existing dispatcher; no second upload namespace |
| RUNNING / CLAIMED | Previous chunks may be committed; current chunk is atomic | Observe; do not reset status or force a claim |
| Caught FAILED + work FAILED | Prior committed rows remain; failed chunk rolled back | Resolve root cause, inspect, use existing restart with same job ID |
| Expired asynchronous CLAIMED | Prior chunks remain | Existing bounded recovery validates metadata, revokes generation and resumes pending markers |
| FAILED + BLOCKED / UNKNOWN or mismatched metadata / recovery allowance exhausted | Exact safe resumption is not established | Manual engineering review of job/work/Batch metadata and movement proof; **no restart shortcut** |
| COMPLETED or COMPLETED_WITH_ERRORS | Applied rows and rejected reasons retained | Inspect rejected rows, reconcile applied proof; duplicate upload returns original job |
| Different profile/version over the same original bytes | Original job only | 409; inspect original mapping/job; do not mutate bytes just to bypass dedup |
| Database unavailable / response lost | Read or mutation acknowledgement may be unknown; committed chunks survive | Restore connectivity, inspect original job/work; do not assume rollback or create replacement operation |
| STALE_STOCK_VERSION / STALE_PRODUCT_VERSION | That row was not applied; earlier chunks may be applied | Review changes; prepare a deliberately reviewed fresh source operation if required, not a blind replay |

The existing worker has a 60-second DB-clock lease renewed at chunk boundaries, not an independent heartbeat. Recovery allowance is bounded to three; metadata mismatch is blocked. No hard availability/recovery-time guarantee is claimed. A schema/profile change never reinterprets stored staged rows.

## Publishing a new supplier version

Use the existing authenticated BFF API, not direct SQL or browser-exposed trust secrets. Example in the site's developer console **while signed in**:

```javascript
const session = await (await fetch('/api/auth/session')).json();
const headers = {'Content-Type':'application/json', 'X-CSRF-TOKEN':session.csrfToken};
const definition = {profileId:'supplier-b',version:2,encoding:'UTF-8',vendorCode:'RIVERBYTE',
  mapping:{sku:'SKU',name:'ProductName',on_hand:'AvailableQuantity'},skuNormalization:'TRIM_UPPER'};
const published = await fetch('/api/ops/integration-profiles', {method:'POST',headers,body:JSON.stringify(definition)});
if (published.status !== 201) throw new Error('Publication failed; inspect the sanitized response');
// Activate only after reviewing the immutable definition and sample contract.
const activated = await fetch('/api/ops/integration-profiles/supplier-b/2',
  {method:'PATCH',headers,body:JSON.stringify({active:true})});
if (!activated.ok) throw new Error('Activation failed');
```

Reload profiles in the ledger (or reload the page). Old versions remain queryable and may remain active for a controlled migration. Disabling a profile prevents **new** previews/confirmations; already committed work uses its immutable stored rows and is not cancelled. This is global operator configuration, not supplier self-service.

## Deployment and rollback

No production deployment or external credential change is part of this delivery.

1. Back up PostgreSQL and rehearse V13→V14 on a disposable copy. Review seeded synthetic vendor contracts before any real-business use.
2. Verify backend tests, frontend/Worker builds, image, configuration/Helm and browser acceptance. Supply the existing BFF/ops/session/database settings through the deployment secret mechanism. A stable production ops secret signs receipts; no new external credential is needed.
3. Roll out the new backend/migration before the frontend onboarding UI; run a tiny reviewed sample. Do not run an unfenced historical worker against this database.
4. For an incident, disable new confirmations by deactivating profiles and/or routing away the new UI; preserve job/work/Batch/movement evidence. Inspect and drain/review pending supplier jobs.
5. Frontend can roll back independently while the compatible new backend remains. **Do not downgrade workers to a version without the expected-product-version guard while supplier jobs are pending.** Prefer roll-forward repair. V14 is additive and retained; do not drop columns, profiles, checksums or Batch history as a rollback technique.
6. Restore a database backup only under an approved whole-system recovery plan accounting for orders placed since the backup. Never manually reset applied markers to re-run inventory.

## Remaining operational limits

100 listed profile versions / 20 recent jobs; no complete searchable history UI or mapping editor. Raw-byte dedup is not semantic event identity. The demo only updates existing catalog products. No supplier accounts, tenant isolation, semantic exactly-once delivery across different files, production load/SLA certification, or automatic correction of stale rows is claimed. Repository docs/screenshots are synthetic local evidence, not proof of production hosting.
