# Architecture and modernization decisions

## Chosen pain point

The case study targets controlled legacy migration for a Japanese commerce client: vendor masters arrive as UTF-8 or CP932 CSV, valid and invalid records are mixed, a failed restart can duplicate work, and stakeholders need a numerical answer to “did the new system match the old one?”

This is deliberately different from a generic CRUD portfolio. The user-visible storefront proves the new system works, while the operations ledger exposes staging, quarantine, checkpoints, reconciliation, and fulfillment audit trails.

## Boundaries

- Next.js is the public UI and backend-for-frontend. It forwards only approved request headers and injects trust secrets server-side.
- Spring Boot owns business invariants. Direct mutations without the BFF secret are rejected.
- PostgreSQL is canonical for products, inventory, orders, migration checkpoints, reconciliation, and audit events.
- Redis accelerates catalog reads only. An outage reduces performance but does not reduce correctness.
- Cloudflare provides the free long-lived frontend edge. The backend is a portable OCI image; hosting is an environment choice, not application logic.

## Consistency choices

Checkout locks requested inventory rows in SKU order to avoid deadlock cycles. All totals and inventory movements commit together. A unique idempotency key turns transport retries into reads of the original result.

Imports first create an immutable logical job identity from the file checksum. Rows are staged and validated before 50-row chunks apply in independent transactions. A checkpoint is advanced with each applied or quarantined row. Reconciliation compares the staged snapshot with canonical state and persists discrepancies for review.

## Security and privacy

Guest checkout intentionally supports invoice and cash-on-delivery only; no fake card processor is presented. Tracking responses exclude name, email, and address. Ops credentials live only in the Next.js server environment. Production startup rejects short or known development secrets. Logs correlate by request ID and application audit details contain business identifiers, not customer PII.

## Evolution path

The same container can later run on AWS ECS/EKS. Terraform can be added when an AWS account exists, mapping PostgreSQL to RDS, Redis to ElastiCache, OTLP to the selected observability backend, and secrets to Secrets Manager. Deferring those resources avoids fake IaC that has never been applied.
