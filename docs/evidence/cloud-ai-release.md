# Cloud AI release — 2026-11-03

Follow-up: [live signed-in mapping/profile/preview checks and agent task
timings](live-ai-mapping-time.md), 2026-11-04. The owner authorized a dedicated
operator account. The original pre-account validation below remains historical;
the follow-up does not claim human onboarding time saved.

The source checkout is a worktree of `D:/CV/Project_Frontend2`, on
`codex/cloudflare-ai-release`, carrying the P09–P12 AI integration and P14
dependency fixes from the other checkout of the same Project2 repository.
The original checkout's `main` is unchanged.

This release enables authenticated supplier policy QA, citation reads,
bounded investigations with durable runs, lexical SOP search and mapping drafts
through a separate Cloudflare Worker, `vmarket-ai-applied`. Signed Ed25519
requests bind the actor, supplier collection, body, deadline and nonce. The BFF
still validates Java operator sessions and the current active profile before
and after each call. AI cannot approve imports or change business records.

The Copilot panel exposes policy QA and import investigation. Mapping drafts are
available in supplier onboarding. The edge adapters preserve existing signed
contracts while the original Python implementation remains available locally.
The cloud adapter uses a bounded full-context synthetic corpus and Workers AI
Qwen3 30B A3B; it is a separate profile from the local E5/Ollama evaluation.

Secrets are injected into Cloudflare's secret store. The AI Worker has only the
public verification key. Existing BACKEND_ORIGIN, BFF_SHARED_SECRET and
VMARKET_OPS_SECRET bindings are retained. Never copy `.env` into the release.

Validation before publication: 128 frontend tests, TypeScript, ESLint and OpenNext
Worker build pass. Direct live AI probe: health/readiness 200, unsigned request
401, valid signed QA 200 with exact source quote, nonce replay 409, citation read
200. Native local workerd BFF with synthetic Java session fixtures successfully
created and resumed a run through real deployed AI/D1 and obtained a signed
mapping draft. Direct live probes also verified UTF-8, CP932 and AI mapping,
and a complete start/plan/claim/record/finalize sequence. These are not production
operator-session tests. Authenticated end-to-end validation requires an existing operator
session; deployment checks must not invent a production user or broaden roles.

A native workerd check found that Workers rejects `redirect: "error"`. The
policy transport and shared Java session reader use `manual` and reject non-OK
responses, preserving the no-follow policy. A regression test covers this
platform behavior; Node-only fetch mocks previously hid it.

Rollback: restore the previous `v-market` Worker version using the predeployment
version recorded in the private deployment inventory. The new D1 database is
isolated from the business database; turn off the QA, runs and mapping feature
flags to remove cloud AI while preserving
manual operations. No Render deployment or business database migration is part
of this release.

Cloud limits: 60 model attempts/day shared by all AI features, four per actor per
minute, maximum three read attempts/run. Runs expire after ten minutes and are
purged after a further day; knowledge excerpts are never checkpointed. Mapping
accepts up to 45 KB original CSV / 100 columns / 1,000 rows, with explicit human
acceptance and Java preview/validation before any business change.
