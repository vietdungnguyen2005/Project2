output "ecr_repository_url" { value = aws_ecr_repository.backend.repository_url }
output "api_base_url" { value = aws_apigatewayv2_api.api.api_endpoint }
output "cloudwatch_dashboard" {
  value = "https://${var.aws_region}.console.aws.amazon.com/cloudwatch/home?region=${var.aws_region}#dashboards:name=${aws_cloudwatch_dashboard.operations.dashboard_name}"
}
output "deployment_identity" {
  value = {
    account_suffix = substr(data.aws_caller_identity.current.account_id, 8, 4)
    region         = var.aws_region
    image_tag      = var.image_tag
    expires_at     = var.expires_at
  }
}

