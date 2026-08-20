# AWS deployment evidence

V-Market was deployed and verified in `us-east-1` on `2026-08-20T16:04:38Z` from commit `0c56022ec14237f5c1ef80a5cf620fa4116696bf`.

| Check | Recorded result |
|---|---|
| Terraform bootstrap | `43 added, 0 changed, 0 destroyed` |
| Verified teardown | `43 destroyed`; Terraform state contains `0` resources |
| HTTPS readiness | API Gateway → ALB → ECS returned HTTP `200` |
| Trust boundary | Direct operations request without BFF/Ops credentials returned HTTP `401` |
| Runtime | ECS desired `1`, running `1`, pending `0` |
| Data services | encrypted, non-public RDS PostgreSQL; Redis with in-transit and at-rest encryption |
| Container | immutable ECR tag equals the full Git SHA; digest begins `sha256:e1001e4605d4` |
| Operations | Container Insights, seven-day logs, dashboard, target 5xx and unhealthy-target alarms |

The time-limited endpoint was `https://lc2wixz6k8.execute-api.us-east-1.amazonaws.com`. Cloudflare/Render remains the persistent free-tier demo.

## Legacy migration proof

An operations-authorized UTF-8 legacy stock file contained two rows: one valid existing SKU and one invalid unknown SKU with negative stock. Import job `7baa739f-06fe-4df7-b770-6a4255ffa3dc` finished as `COMPLETED_WITH_ERRORS`, reported `totalRows=2`, applied exactly one row, and quarantined exactly one row. The batch did not fail wholesale and did not silently accept corrupt data.

This is intentionally different from V-Core's concurrency proof and V-Pulse's payment-recovery proof. Infrastructure source and Academy-account limits are documented in [`infra/terraform/aws`](../../infra/terraform/aws/README.md). Terraform state, non-public AWS identifiers, ARNs, and credentials are excluded.
