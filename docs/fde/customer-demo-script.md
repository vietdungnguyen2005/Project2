# Customer demo and interview walkthrough

## Reproduce locally

Prerequisites: Java 21, Node/npm matching lockfile, Python with Playwright/Chromium and bcrypt, Docker Linux containers; Helm for infrastructure checks. Existing DB/Redis containers must not be reused for destructive QA. Test configuration is synthetic; do not point the runner at production.

```powershell
# From repository root
npm ci
npm run verify
npm run build:worker
npm audit --audit-level=high
Push-Location backend
.\mvnw.cmd verify
Pop-Location
docker build --tag v-market-backend:fde-local backend
docker compose config --quiet
helm lint infra/helm/v-market
python tools/generate_supplier_samples.py
.\tools\verify-operator-session.ps1
```

The existing Windows runner starts its own PostgreSQL/Redis and real packaged Java/Next servers, injects temporary test credentials, force-kills only owned Java processes, runs READY and STARTED recovery, supplier acceptance, original operator/checkout regression, then cleans owned processes/containers. It deliberately pauses only its disposable PostgreSQL for the STARTED unavailability observation; cleanup restores it. Existing sessions/cookies never go into committed reports.

For fixture UI tests, CI starts `npm run start` and runs `npm run test:e2e`. These fixtures cover browser behavior, not database correctness. The real acceptance script is `tools/qa_supplier_onboarding.py`, invoked by the runner. On Windows, terminate the full owned npm/Next process tree before rebuilding; the generic Python server helper does not reliably reap npm's grandchildren on this host.

## Five-minute customer acceptance

1. Open `/ops` and sign in. Explain that this is one retailer's shared store and two **fictional** suppliers.
2. Select supplier B v1. Show SKU/ProductName/AvailableQuantity → sku/name/on_hand, UTF-8, vendor binding and fixed ranges. Explain that vendor/price/concurrency versions are taken from the canonical catalog.
3. Preview the mixed default file. Show one accepted row and one rejected numeric stock row. Confirm that no job/movement is created. Export the sanitized report; it contains no raw cells or approval receipt.
4. Change profile to supplier A. Previous approval disappears. Upload the generated CP932 file; preview Japanese characters correctly. No inventory change is required to prove decoding.
5. Return to supplier B, preview, approve explicitly, confirm. Inspect the durable job and quarantined row; wait for final status. Reconcile applied proof, including unverified/changed counts rather than just mismatch count.
6. Repeat the same file/profile: original job, one movement. A new version over identical bytes conflicts, rather than creating another namespace. Show the protected API contract for publishing another mapping.
7. Show the recorded partial-crash proof: 50 rows committed, owned JVM killed, injected lease expiry, same Batch instance resumes to 51 unique movements. State the injection limitations before discussing recovery.

## 90-second English walkthrough

> V-Market is a portfolio integration case for a fictional Japanese retailer. The problem is that suppliers send different CSV headers and encodings, while inventory changes must remain safe and auditable.
>
> I extended the existing importer rather than adding another queue. Operators select an immutable profile version defining the encoding, column mapping and approved normalization rules. A read-only preview checks the file, shows accepted examples and explains rejected rows. A short-lived signed receipt binds confirmation to the operator, original bytes and catalog snapshot they reviewed.
>
> The three-column supplier files do not invent prices or concurrency versions. The backend enriches them from the canonical catalog, then checks product and stock versions under locks. A stale row is rejected instead of overwriting a later price or restoring sold stock.
>
> Confirmation commits to the existing PostgreSQL work ledger. Original-byte checksum identity is global: selecting another profile version cannot silently replay the same file. Existing chunk markers, worker fencing and bounded recovery still control retries.
>
> I verified this with real PostgreSQL and Redis tests and browser acceptance, including CP932, malformed data, duplicate and concurrent confirmations, reconciliation, and a partial process-crash recovery. The benchmark is seeded synthetic evidence, not a claim of production customer savings. The main remaining limits are single-store administration, byte-level rather than semantic deduplication, and manual review for unsafe recovery states.

## Evidence-backed CV bullets

- Extended a Java/Spring Boot commerce importer with immutable supplier profiles, strict UTF-8/CP932 mapping, read-only preview and explicit confirmation; preserved global original-byte deduplication and stock/product-version validation under PostgreSQL locks.
- Validated onboarding with PostgreSQL/Redis integration tests and real-browser acceptance, including a seeded 220-row synthetic dataset and recovery from 50 committed rows to 51 unique inventory movements after an owned worker crash.

Use these only if you can explain the implementation and reproduce the evidence. Do not present this synthetic case as real client delivery or claim authorship/production experience beyond your actual contribution.
