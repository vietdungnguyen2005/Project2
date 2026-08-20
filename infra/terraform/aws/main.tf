data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" { state = "available" }
data "aws_iam_role" "lab" { name = var.lab_role_name }

locals {
  prefix = "vmarket-evidence"
  tags = {
    Project    = "V-Market", Environment = "aws-evidence", ManagedBy = "Terraform",
    Repository = "vietdungnguyen2005/Project_Frontend2", ExpiresAt = var.expires_at
  }
}

resource "aws_vpc" "main" {
  cidr_block           = "10.20.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = "${local.prefix}-vpc" }
}
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.prefix}-igw" }
}
resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index)
  map_public_ip_on_launch = true
  tags                    = { Name = "${local.prefix}-public-${count.index + 1}" }
}
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
  tags = { Name = "${local.prefix}-public" }
}
resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "alb" {
  name        = "${local.prefix}-alb"
  description = "Evidence API origin"
  vpc_id      = aws_vpc.main.id
}
resource "aws_security_group" "app" {
  name        = "${local.prefix}-app"
  description = "Spring Boot task"
  vpc_id      = aws_vpc.main.id
}
resource "aws_security_group" "database" {
  name        = "${local.prefix}-database"
  description = "PostgreSQL from application only"
  vpc_id      = aws_vpc.main.id
}
resource "aws_security_group" "cache" {
  name        = "${local.prefix}-cache"
  description = "Redis from application only"
  vpc_id      = aws_vpc.main.id
}
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "Public API Gateway origin; business API requires BFF trust"
}
resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_egress_rule" "app_outbound" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}
resource "aws_vpc_security_group_ingress_rule" "database_from_app" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "cache_from_app" {
  security_group_id            = aws_security_group.cache.id
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "random_password" "database" {
  length  = 32
  special = false
}
resource "random_password" "redis" {
  length  = 32
  special = false
}
resource "aws_db_subnet_group" "main" {
  name       = "${local.prefix}-database"
  subnet_ids = aws_subnet.public[*].id
}
resource "aws_db_instance" "postgres" {
  identifier              = "${local.prefix}-postgres"
  engine                  = "postgres"
  engine_version          = "17.6"
  instance_class          = "db.t4g.micro"
  allocated_storage       = 20
  storage_type            = "gp3"
  storage_encrypted       = true
  db_name                 = "vmarket"
  username                = "vmarket_admin"
  password                = random_password.database.result
  db_subnet_group_name    = aws_db_subnet_group.main.name
  vpc_security_group_ids  = [aws_security_group.database.id]
  publicly_accessible     = false
  multi_az                = false
  backup_retention_period = 1
  deletion_protection     = false
  skip_final_snapshot     = true
  apply_immediately       = true
}
resource "aws_elasticache_subnet_group" "main" {
  name       = "${local.prefix}-cache"
  subnet_ids = aws_subnet.public[*].id
}
resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = "${local.prefix}-redis"
  description                = "V-Market evidence cache"
  engine                     = "redis"
  engine_version             = "7.1"
  node_type                  = "cache.t4g.micro"
  port                       = 6379
  num_cache_clusters         = 1
  automatic_failover_enabled = false
  multi_az_enabled           = false
  subnet_group_name          = aws_elasticache_subnet_group.main.name
  security_group_ids         = [aws_security_group.cache.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.redis.result
  apply_immediately          = true
}

resource "aws_ecr_repository" "backend" {
  name                 = "${local.prefix}-backend"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true
  image_scanning_configuration { scan_on_push = true }
}
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy     = jsonencode({ rules = [{ rulePriority = 1, description = "Retain five images", selection = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 5 }, action = { type = "expire" } }] })
}
resource "aws_ssm_parameter" "database_password" {
  name  = "/${local.prefix}/database-password"
  type  = "SecureString"
  value = random_password.database.result
}
resource "aws_ssm_parameter" "redis_url" {
  name  = "/${local.prefix}/redis-url"
  type  = "SecureString"
  value = "rediss://:${random_password.redis.result}@${aws_elasticache_replication_group.redis.primary_endpoint_address}:6379"
}
resource "aws_ssm_parameter" "bff_secret" {
  name  = "/${local.prefix}/bff-secret"
  type  = "SecureString"
  value = var.bff_shared_secret
}
resource "aws_ssm_parameter" "ops_secret" {
  name  = "/${local.prefix}/ops-secret"
  type  = "SecureString"
  value = var.ops_secret
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.prefix}/backend"
  retention_in_days = 7
}
resource "aws_ecs_cluster" "main" {
  name = local.prefix
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}
resource "aws_lb" "api" {
  name                       = "${local.prefix}-api"
  internal                   = false
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = aws_subnet.public[*].id
  drop_invalid_header_fields = true
}
resource "aws_lb_target_group" "api" {
  name                 = "${local.prefix}-api"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 15
  health_check {
    enabled             = true
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 20
    timeout             = 5
    matcher             = "200"
  }
}
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}
resource "aws_apigatewayv2_api" "api" {
  name          = "${local.prefix}-api"
  protocol_type = "HTTP"
}
resource "aws_apigatewayv2_integration" "alb" {
  api_id                 = aws_apigatewayv2_api.api.id
  integration_type       = "HTTP_PROXY"
  integration_method     = "ANY"
  integration_uri        = "http://${aws_lb.api.dns_name}"
  payload_format_version = "1.0"
  timeout_milliseconds   = 30000
  depends_on             = [aws_lb_listener.http]
}
resource "aws_apigatewayv2_route" "default" {
  api_id    = aws_apigatewayv2_api.api.id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"
}
resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.api.id
  name        = "$default"
  auto_deploy = true
  default_route_settings {
    detailed_metrics_enabled = true
    throttling_burst_limit   = 50
    throttling_rate_limit    = 25
  }
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "${local.prefix}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = data.aws_iam_role.lab.arn
  task_role_arn            = data.aws_iam_role.lab.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name         = "backend", image = "${aws_ecr_repository.backend.repository_url}:${var.image_tag}", essential = true,
    portMappings = [{ containerPort = 8080, hostPort = 8080, protocol = "tcp" }],
    environment = [
      { name = "SPRING_DATASOURCE_URL", value = "jdbc:postgresql://${aws_db_instance.postgres.address}:5432/vmarket?sslmode=require" },
      { name = "SPRING_DATASOURCE_USERNAME", value = "vmarket_admin" },
      { name = "DEPLOYMENT_ENVIRONMENT", value = "production" },
      { name = "SPRINGDOC_ENABLED", value = "false" }
    ],
    secrets = [
      { name = "SPRING_DATASOURCE_PASSWORD", valueFrom = aws_ssm_parameter.database_password.arn },
      { name = "SPRING_DATA_REDIS_URL", valueFrom = aws_ssm_parameter.redis_url.arn },
      { name = "BFF_SHARED_SECRET", valueFrom = aws_ssm_parameter.bff_secret.arn },
      { name = "VMARKET_OPS_SECRET", valueFrom = aws_ssm_parameter.ops_secret.arn }
    ],
    logConfiguration = { logDriver = "awslogs", options = { awslogs-group = aws_cloudwatch_log_group.backend.name, awslogs-region = var.aws_region, awslogs-stream-prefix = "api" } }
  }])
}
resource "aws_ecs_service" "backend" {
  name                               = "backend"
  cluster                            = aws_ecs_cluster.main.id
  task_definition                    = aws_ecs_task_definition.backend.arn
  desired_count                      = var.desired_count
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 120
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = true
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "backend"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener.http]
}

resource "aws_cloudwatch_metric_alarm" "unhealthy_targets" {
  alarm_name          = "${local.prefix}-unhealthy-targets"
  alarm_description   = "V-Market has an unhealthy ECS target."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions          = { LoadBalancer = aws_lb.api.arn_suffix, TargetGroup = aws_lb_target_group.api.arn_suffix }
}
resource "aws_cloudwatch_metric_alarm" "target_5xx" {
  alarm_name          = "${local.prefix}-target-5xx"
  alarm_description   = "Spring Boot returned a server error."
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions          = { LoadBalancer = aws_lb.api.arn_suffix, TargetGroup = aws_lb_target_group.api.arn_suffix }
}
resource "aws_cloudwatch_dashboard" "operations" {
  dashboard_name = "${local.prefix}-operations"
  dashboard_body = jsonencode({ widgets = [
    { type = "metric", x = 0, y = 0, width = 12, height = 6, properties = { title = "ECS CPU and memory", region = var.aws_region, view = "timeSeries", stat = "Average", period = 60, metrics = [["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.backend.name], [".", "MemoryUtilization", ".", ".", ".", "."]] } },
    { type = "metric", x = 12, y = 0, width = 12, height = 6, properties = { title = "API latency and failures", region = var.aws_region, view = "timeSeries", period = 60, metrics = [["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", aws_lb.api.arn_suffix, { stat = "p95" }], ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", aws_lb.api.arn_suffix, { stat = "Sum", yAxis = "right" }]] } }
  ] })
}
