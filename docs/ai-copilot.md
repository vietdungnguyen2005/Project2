# Optional read-only AI gateway (P09)

`POST /api/ops/ai/agent` is additive and **OFF by default** (`AI_COPILOT_ENABLED=false`). Existing operations, checkout, import, profile and Java authorization code are unchanged. There is no AI UI, write tool, deployment or persistent key provisioning in this change.

With the flag off, it returns 503 AI_DISABLED and makes no upstream call. When explicitly configured, input is only a bounded question and optional collection IDs. The BFF checks the connection Host/current origin (never forwarded-host), current Java OPERATOR session and timing-safe current CSRF token. Unknown browser identity/tool/result/URL fields are rejected. It generates its own request/run IDs, pseudonymizes the verified username with a BFF-only HMAC key, and signs exact body/route/context using an independent Ed25519 private key. Python receives only its public key; no Java secret, cookie, CSRF token or username is forwarded.

Required server-only configuration is listed in `.env.example`: fixed Java/AI origins, existing Java BFF/ops credentials, AI signing key ID and base64 DER PKCS8 private key, and an independent >=32-byte actor HMAC key. `AI_COLLECTION_IDS_JSON` may narrow the fixed two public synthetic collection IDs. This is a shared synthetic store for all OPERATORs, not tenant isolation. Key rotation changes pseudonyms and invalidates old transport contexts. Keys are not generated or installed by the route.

The live request owns result history, call IDs, deadline and tool budget. Limits: 25 seconds including authentication, at most four Python step calls/three tool executions, 65,536 request/step bytes, 16,384 projected tool-result bytes. Current process rate limit is eight accepted requests per pseudonymous actor/minute, with at most 1024 rate windows. It caches no sessions/results and is not a distributed rate limiter. Browser cancellation aborts fetch/stream work; no later privileged step continues. Reads already received by Java cannot be retroactively cancelled or revoked.

Before every tool and final response, the BFF checks the current Java session again with the same request-local cookie jar. A different account/expired/disabled/revoked operator stops the loop. Legitimate VMARKET_SESSION refresh/deletion is propagated only to the same browser response. Each Java GET independently reauthorizes. No model envelope is accepted as a new callback/execution request.

Closed tools:

- get_integration_profiles -> GET /api/ops/integration-profiles, verifies exact three-target Java policy and strips audit identities.
- get_imports -> GET /api/ops/imports, at most 20, omits filename/account data.
- inspect_import_job -> GET /api/ops/imports/{canonical UUID}/inspection; rowNumber/reason is converted to bounded row-number/code pairs, no raw errors/recovery details.
- get_reconciliation_report -> GET /api/ops/reconciliations, latest 20 only, filter exact job ID and choose newest stored report. No invented by-ID GET or reconciliation POST.
- search_supplier_knowledge -> separately signed Python POST /ai/knowledge/search, only a subset of current signed scopes. Excerpts/IDs are data, not links/executable markup.

Tool names/arguments/IDs, response identity/step, per-result bytes and call uniqueness are checked. Final UI summary is rebuilt from validated observation statuses; unsupported Python business-success prose is discarded. The response contains bounded structured observations with timestamps, not proof of freshness or business approval. Failed/truncated tools remain explicit. No retries, arbitrary fetch, SQL, headers, preview, confirm, restart, profile mutation or stock/checkout write can be selected by an intent.

Tests follow the incremental red/green workflow and use a typed Spec/Handler Result interface. Run `npm run verify` and `npm run build:worker` (bundle only, no deploy). The paired AI_Applied P09 evidence contains disposable real Java/Next/Python auth/read checks, unchanged business-table snapshots, separate local-model/mock-tool smoke and known limitations. Java source/tests stay unchanged; its 115-test baseline remains verified. Existing dependency audit has 19 advisories (17 moderate/two high), tracked separately for P14.
