# Security notes

- Real secrets belong in platform secret stores, never `.env.example`, source, images, Helm values, or browser bundles.
- `BFF_SHARED_SECRET` authenticates the Next.js BFF; `VMARKET_OPS_SECRET` adds authorization for operations endpoints. They must be distinct.
- Production startup rejects the known local defaults and secrets shorter than 32 characters.
- Request headers are allow-listed at the BFF. Client-supplied trust headers are discarded and replaced.
- Checkout validation and all money calculations run in Java against canonical PostgreSQL data.
- Tracking tokens are stored only as SHA-256 hashes. Tracking responses contain no customer PII.
- Audit events intentionally store actor, action, aggregate identity, and state changes—not customer name, email, or address.
- Card payments are out of scope. The UI exposes only invoice and COD and truthfully reports payment as pending.
- Dependency scanning runs in CI; Dependabot covers npm, Maven, and GitHub Actions.
