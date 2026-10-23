# Integration contract

All paths below are same-origin BFF paths. Every endpoint requires an authenticated, currently enabled OPERATOR JDBC session. Mutations require CSRF. BFF and operations secrets are injected server-side, never embedded in browser code. Direct backend calls additionally require both trust headers; those alone do not replace the user session.

## API

| Method / path | Request | Result |
| --- | --- | --- |
| GET `/api/ops/integration-profiles` | Session | Up to 100 profile versions, ordered by ID/version; includes immutable definition, fixed required/type/range policy and activation provenance |
| POST `/api/ops/integration-profiles` | JSON definition below + CSRF | 201, new **inactive** immutable version; duplicate identity 409 |
| PATCH `/api/ops/integration-profiles/{id}/{version}` | JSON `{"active":true}` or false + CSRF | Audited activation change; existing staged work is not cancelled |
| POST `/api/ops/onboarding/{id}/{version}/preview` | Original `text/csv` bytes + CSRF | 200 mapping, accepted/rejected counts, first 10 accepted rows, all bounded row errors, signed `confirmationReceipt`; **no business/import writes** |
| POST `/api/ops/onboarding/{id}/{version}/confirm` | Same bytes + CSRF + `X-Source-Name` + `X-Preview-Receipt` | 202 after existing job/work/profile provenance/audit commit, `Preference-Applied: respond-async`; not completion |
| GET `/api/ops/imports` | Session | Existing latest 20 job summaries, extended with nullable `profileId`/`profileVersion` |
| GET `/api/ops/imports/{job}/inspection` | Session | Job, work state, recovery count/reason, server-derived restart eligibility, sanitized row errors; no claim token or Batch stack trace |
| POST `/api/ops/imports/{job}/restart` | Existing CSRF + `Prefer: respond-async` | Existing safe restart; no new profile/rows/mapping or checksum identity |
| POST `/api/ops/reconciliations` | Existing JSON `{"migrationJobId":"uuid"}` + CSRF | Applied proof comparison, including drift/unverified scope |

Definition example (publishing/activation intentionally use the protected API, not another admin application):

```json
{"profileId":"supplier-b","version":2,"encoding":"UTF-8","vendorCode":"RIVERBYTE","mapping":{"sku":"SKU","name":"ProductName","on_hand":"AvailableQuantity"},"skuNormalization":"TRIM_UPPER"}
```

IDs match `[a-z][a-z0-9-]{0,47}`, positive integer version, distinct nonblank trimmed source headers up to 80 characters without control characters. Vendor must already exist and be active. Encodings are UTF-8 or CP932. SKU normalization is TRIM or TRIM_UPPER, never executable code. All required fields and ranges are fixed server policy. Definitions and creation provenance cannot be updated/deleted even directly through ordinary SQL; a database trigger requires a new version. Activation changes and import confirmations use authenticated actor audit in the same transaction.

## Preview receipt and freshness

Preview uses a read-only repeatable-read transaction and one bounded catalog query. It creates no job, work item, order, movement, inventory change or application audit. Existing session/authentication bookkeeping is not business import state.

The receipt expires after 600 seconds and HMAC-SHA256 binds domain, profile/version, actor hash, raw-byte hash, normalized enriched content hash and expiry. It uses the existing backend operations secret; no new external key is required. Do not log/export the receipt. The immutable version identifies its mapping. Confirmation verifies the receipt and rechecks the active profile; it recomputes enrichment for a new job and rejects changed catalog/stock/reservations. Profile activation is locked/rechecked in the staging transaction. A race after staging is handled by stock and product version checks under the existing locks.

This is explicit operator approval, **not** a stock reservation, distributed transaction, cross-tenant identity, or signed third-party document standard. Stable secrets and synchronized server clocks are required. Secret rotation invalidates outstanding previews; re-preview instead of bypassing validation. Existing job status remains inspectable after receipt expiry.

## Duplicate semantics — global original bytes, not transformed bytes

| Existing original-byte SHA-256 | New request | Outcome |
| --- | --- | --- |
| None | Valid confirmed preview | One staged job, profile FK, audit and existing durable work item atomically |
| Same profile/version and encoding | Confirmed retry | Return existing job; no second application, even if current catalog changed since original import |
| Different profile or version | Same bytes | 409 requiring review; never create another mapping namespace |
| Legacy job vs profiled request (either direction) | Same bytes | 409, no replay |
| Conflicting encoding | Same bytes | Existing 409 encoding conflict |
| Different bytes/newline/encoding | Any profile | Different identity; normal validation/version checks still apply |

Source filename is only a label. An expired receipt requires re-preview; the new receipt may still retrieve the same immutable existing job. A duplicate upload does not restart a failed job. Use inspection and the existing restart API only when eligible.

Raw checksum advisory locking plus the existing unique constraint resolves concurrent staging. Rejected rows have no business effects. Accepted rows are staged immutably for the existing runner; restart never re-reads a mutable profile definition or current source file. There is no second task queue.

## Limits and failure responses

CSV upload budget remains 5,000,000 bytes and 1,000 records at both new BFF routes and Java controller reads. Other JSON remains 65,536 bytes. BFF request/response deadlines and 5 MB response budget remain in force. 400 is invalid contract/structure; 401/403 is auth/trust/CSRF; 409 is inactive/stale/mismatched approval or identity; 413 is ingress size. Database failures are sanitized. A BFF 502/503/504 after confirmation is an **unknown outcome**, not cancellation: inspect jobs before retrying with the same bytes/profile.
