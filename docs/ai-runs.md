# Durable read-only run API (P11)

`AI_COPILOT_ENABLED=true` and server-only `AI_RUNS_ENABLED=true` enable
`POST /api/ops/ai/runs`. Defaults are false. Configure the existing fixed AI/Java
origins, signing key and actor HMAC key; Python must also enable signed durable
runs against dedicated AI PostgreSQL. No credentials enter browser JS.

Input is a strict union:

```json
{"action":"start","question":"List imports"}
{"action":"inspect","run_id":"<server UUID>"}
{"action":"resume","run_id":"<server UUID>","expected_revision":0}
{"action":"cancel","run_id":"<server UUID>","expected_revision":0}
```

Start persists a fresh conversation/run before model/tool dispatch. Preserve the
returned reference; resume is a separate current-authorized request. Successful
responses contain only `{run: RunState}` and request correlation header. Current
OPERATOR/CSRF, same origin, owner HMAC and current scopes apply to every command.
Each read dispatch and public response rechecks session, and Java independently
authorizes its fixed GET. The BFF alone can send signed plan/claim/record/recover
commands; browser tool results, actor, lease, approval and URL fields are rejected.

The BFF performs at most three closed P09 read tools under a25-second request
deadline. Its20/minute command limit is process-local; database revisions, lease
fencing and eight active actor slots survive process restarts. A stale duplicate
resume returns409. An active lease is not resumed by another worker. After the
lease expires, inspection surfaces OUTCOME_UNKNOWN. Explicit resume records that
uncertain read as unavailable and ends with an abstention, without sending the
GET again. No automatic retry/provider fallback or background continuation exists.

Business handoff is terminal for AI continuation, with `can_resume=false`. Resume
cannot grant profile/import/stock approval. Operators continue through the existing
P10 UI. A cancelled run fences late results; a GET already accepted by Java cannot
be retracted. If a request times out or its reply is lost, inspect before deciding
what to do next. A completed run describes controller completion, not a business
mutation or the present truth of an earlier observation.

Public snapshots expose historical status/count/time summaries. Raw questions,
cached Java result bodies, document excerpts, leases and transport headers stay
out of browser snapshots. Knowledge text/citations are not checkpointed for reuse.
Runs expire at a fixed600seconds; Python owner maintenance scrubs/purges according
to its documented retention. Ownership inherits P09's verified-username HMAC and
shared synthetic scope limitations. Production tenancy/identity recycling and
distributed rate control are not claimed.

The API has no new UI in P11; P12 adds the operator chat/panels. Java source,
business/auth contracts, existing gateway and mapping flows remain unchanged.
