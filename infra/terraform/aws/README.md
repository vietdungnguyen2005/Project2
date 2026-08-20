# AWS evidence environment

This short-lived stack deploys V-Market through API Gateway, ALB, ECS Fargate, encrypted non-public RDS PostgreSQL, encrypted ElastiCache Redis, ECR, SSM Parameter Store, and CloudWatch. Apply with `desired_count=0`, push the backend image using the full Git SHA, then apply with `desired_count=1`.

The AWS Academy account blocks CloudFront, Budgets, GitHub OIDC creation, and Tokyo workload APIs. API Gateway supplies HTTPS for the recorded evidence; a personal account should use a least-privilege GitHub OIDC role, remote encrypted Terraform state, and cost budgets. State contains generated credentials and must never be committed.

Every supported resource is tagged with an expiry. Run environments sequentially, capture sanitized evidence, verify a destroy plan, and destroy immediately.
