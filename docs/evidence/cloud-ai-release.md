# Cloud policy QA release — 2026-11-03

The source checkout is a worktree of `D:/CV/Project_Frontend2`, on
`codex/cloudflare-ai-release`, carrying the P09–P12 AI integration and P14
dependency fixes from the other checkout of the same Project2 repository.
The original checkout's `main` is unchanged.

This release enables only authenticated supplier policy QA and citation reads
through a separate Cloudflare Worker, `vmarket-ai-applied`. Signed Ed25519
requests bind the actor, supplier collection, body, deadline and nonce. The BFF
still validates Java operator sessions and the current active profile before
and after each call. AI cannot approve imports or change business records.

Agent panel, durable runs and AI mapping drafts remain disabled for this initial
edge release. Their local Python implementation has not been ported to Workers.
The cloud adapter uses a bounded full-context synthetic corpus and Workers AI
Qwen3 30B A3B; it is a separate profile from the local E5/Ollama evaluation.

Secrets are injected into Cloudflare's secret store. The AI Worker has only the
public verification key. Existing BACKEND_ORIGIN, BFF_SHARED_SECRET and
VMARKET_OPS_SECRET bindings are retained. Never copy `.env` into the release.

Validation before publication: 125 frontend tests, TypeScript, ESLint and OpenNext
Worker build pass. Direct live AI probe: health/readiness 200, unsigned request
401, valid signed QA 200 with exact source quote, nonce replay 409, citation read
200. Authenticated end-to-end validation still requires an existing operator
session; deployment checks must not invent a production user or broaden roles.

A native workerd check found that Workers rejects `redirect: "error"`. The
policy transport and shared Java session reader use `manual` and reject non-OK
responses, preserving the no-follow policy. A regression test covers this
platform behavior; Node-only fetch mocks previously hid it.

Rollback: restore the previous `v-market` Worker version using the predeployment
version recorded in the private deployment inventory. The new D1 database is
isolated from business data; disabling AI_QA_ENABLED removes QA while preserving
manual operations. No Render deployment or business database migration is part
of this release.
