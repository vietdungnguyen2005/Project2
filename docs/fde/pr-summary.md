# PR summary: supplier onboarding without importer replacement

## Review scope

Base: `ff9fde325f3afe7be556deb28dc89d0bfe28770d`.
Feature branch: `feat/supplier-data-onboarding`.
No push, remote PR, merge, deployment, credential rotation or historical migration edit is performed. Pre-existing uncommitted `PROJECT_DEEP_DIVE_PREP.md` is outside this change and must not be staged with it.

## Changes

- Add immutable, declarative supplier profile versions, activation/confirmation audit and V14 profile provenance. Publish through protected APIs; select in the existing ops ledger.
- Add strict read-only preview, bounded batched catalog enrichment and ten-minute signed confirmation receipt. Recompute enrichment before new staging; preserve exact original-byte identity.
- Reuse existing parser, staging/work transaction, 50-row chunk runner, dispatcher, Batch identity, recovery and reconciliation. Supplier rows additionally guard the catalog product version so price enrichment cannot overwrite later metadata.
- Reject alternate profile/version or legacy namespaces over the same bytes. Store no rejected raw text cells that could overflow staging columns.
- Add work-state inspection, safe restart eligibility and sanitized report export; retain session, CSRF, BFF/ops secrets and request budgets.
- Add integration/SSR/BFF/browser checks, seeded benchmark, genuine supplier STARTED crash proof and English FDE handoff/evidence.

## Verification

- Baseline: 98 backend / 52 frontend tests plus existing frontend static/build gates.
- Final: 115 backend / 58 frontend tests, frontend lint/typecheck/production build, Cloudflare Worker bundle, npm audit (0), Docker build/non-root user and Compose/Helm.
- Existing real-server Windows runner passed supplier acceptance, original checkout/operator/import regressions, READY/STARTED process restart, database interruption observation, fixture browser contracts and committed-checkout/BFF-deadline recovery.
- See [traceability](acceptance-criteria.md), [measured synthetic results](impact-evaluation.md), and [implementation record](implementation-record.md). Local gates are not described as remote feature CI.

## Reviewer focus / rollout

1. Review checksum lookup both before and under staging advisory lock, including profile identity conflicts.
2. Review receipt binding/expiry, read-only preview and fresh-state comparison before staging.
3. Review additive V14 constraints/immutability and supplier expected-product-version guard; existing fencing/recovery implementation is not rewritten.
4. Review OPERATOR/CSRF/BFF boundaries and sanitized row reports; no credentials or source files enter committed evidence.
5. Roll out backend/migration before UI. Do not downgrade workers lacking the new product-version guard while supplier jobs remain pending. See [handoff](operator-handoff.md).

Business limits: fictional suppliers, one shared store, no trusted source-age/sequence contract, no semantic cross-file exactly-once claim or automatic stale-row repair. Two CV bullets and a 90-second English walkthrough are in [demo script](customer-demo-script.md).
