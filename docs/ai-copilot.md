# Operator Copilot (P12)

The existing `/ops` page has an optional evidence desk. All flags are server-only
and default OFF: `AI_COPILOT_ENABLED` plus `AI_COPILOT_PANEL_ENABLED` display the
desk; `AI_QA_ENABLED`, `AI_RUNS_ENABLED` and `AI_MAPPING_DRAFTS_ENABLED` enable
their individual capabilities. The public storefront does not load the desk.
Existing authenticated manual operations remain available without AI.

Policy questions choose an actual active Java profile/version. POST
`/api/ops/ai/ask` accepts only that reference, question and en/ja language. The BFF
checks current OPERATOR/CSRF/same origin, derives the synthetic supplier scope,
signs the exact bounded body, then rechecks the session and immutable active
profile before releasing evidence. Browser authority, provider URLs and arbitrary
collection IDs are rejected. A source button POSTs the profile reference and chunk
ID to `/api/ops/ai/citations/read`. Every click rechecks Java and Python current
document grants, active snapshot and effective version. Missing and revoked
sources share the same public 404. No browser link follows a model-generated URL.
Text is rendered as escaped React text; source dialogs restore keyboard focus.

Investigation questions start a durable run before a bounded read-only resume.
Live resume replies include projected observations actually read during that
request, after authorization and successful checkpointing. Inspect returns only
historical status/count/time summaries. It never replays cached Java or document
bodies. A terminal turn can be followed by another independent question; at most
five historical turn summaries stay in component memory, without model memory
across runs. The page distinguishes investigation completion from Java job status.
Human business changes link to the existing current-profile/preview/confirm flow.
No AI control confirms an import, activates a profile or restarts a job.

Only a run UUID is retained in tab session storage. Questions, excerpts and results
are not written there. The BFF owner check still governs a restored or pasted UUID.
After reload, a stopped request or unknown outcome, inspect explicitly. No polling,
retry, automatic resume on reload or hidden background execution was added. A new
question explicitly resumes only the run just saved by that same user gesture.
Stopping a browser wait does not assert server cancellation; explicit cancel uses
the latest inspected revision. Forgetting a reference does not cancel the run.

Current-session refresh, expiry, lost authority and account changes clear privileged
results. An identity-keyed component boundary clears local conversations when the
account changes. Source/current-profile changes can still invalidate a historical
answer; reopening always requires current access. This is a shared synthetic demo
with the two documented supplier collections, not a production tenant model.

Validation: `npm run verify`, `npm run build:worker`, and
`BASE_URL=... QA_ARTIFACT_DIR=... npm run test:e2e:copilot` against a local server
with the panel/QA/runs flags enabled. The browser script explicitly mocks HTTP and
does not establish model quality or actual Java integration. The AI_Applied repo
contains separately labelled local-model and optional disposable integration tools.
Real customer UAT, full assistive-technology coverage and production deployment
remain pending. See AI_Applied `docs/evidence/P12.md` for current executed evidence.
