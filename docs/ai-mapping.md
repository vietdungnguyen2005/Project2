# Human-owned mapping draft workflow

The minimal draft panel is off by default. Enable server-only
`AI_COPILOT_ENABLED=true` and `AI_MAPPING_DRAFTS_ENABLED=true`, with the existing
BFF signing configuration. `/ops` reads flags at request time. Do not expose the
private signing key or actor HMAC key to browser or Python environments.

`POST /api/ops/ai/mapping/suggest` accepts original file base64, explicit UTF-8 or
CP932 encoding, an active source profile ID/version, method rule/ai, and the
synthetic-fixture declaration. The browser cannot supply vendor, actor, profile
candidates or an executable mapping. BFF checks current OPERATOR/CSRF, reads Java
profiles, derives vendor and the one public synthetic document scope, signs the
body, and calls Python's draft endpoint. It checks returned digest/profile binding
and re-reads session/profile before returning. Limits: original5MB, JSON6.7MB,
response64KiB, outer25seconds. There is no inference or business-write retry.

Header rules are the default, selected by P08's known-case comparison. Optional
local AI uses the existing Ollama adapter; it has no paid fallback. Both are
untrusted hints. The panel shows proposed headers, uncertainty, reason, current
versus edited fields and any bounded authorized SOP excerpts. Vendor/encoding
remain tied to the selected Java source profile. Manual edits remain usable when
draft inference fails. No quality improvement, independent labels or operator
time saving is inferred from plumbing tests.

The operator edits exact source headers, chooses an immutable ID/version and SKU
normalization, and explicitly approves creating an **inactive** profile. The
client calls the existing protected Java publication endpoint through its existing
BFF proxy. Activation requires a second click. Only then can the same original
bytes be adopted for preview. No preview or confirm happens automatically. Java
continues to enforce immutable definitions, active vendor/profile, CSV policy,
receipt identity/expiry, actor, checksum uniqueness and stock/product guards.

If create/activate returns an unknown result, editing/sending within the panel
locks until the operator reads current profiles and acknowledges inspection.
Matching definitions can be resumed without creating another version; absence
from recent profiles does not prove a write failed. Acknowledgement never sends
another write. The existing [preview/confirm recovery](supplier-handoff.md) then
requires a new preview and explicit review after a lost confirm response. These
UI guards are browser-local; Java remains authoritative across reloads/tabs.

`npm run test:e2e:mapping` exercises mocked browser HTTP against an enabled local
Next server. It forces a wrong hint, human correction, separate create/activate,
renewed preview review, draft503 manual fallback and lost-create inspection.
`tools/qa_mapping_real.py` is invoked by AI_Applied's disposable integration runner;
it takes ephemeral operator credentials via stdin and executes real authenticated
UTF-8/CP932 browser journeys, including a deliberately lost real202 response.
Never point the fault-injection runner at a non-disposable business database.

No Java source, auth/import/checkout semantics, dependency lock, deployment or
persistent key provisioning is part of this change. Production multi-tenancy,
distributed limits, durable agent runs and real stakeholder acceptance remain
outside this synthetic local integration.
