variable "aws_profile" {
  type    = string
  default = "cv-portfolio"
}
variable "aws_region" {
  type    = string
  default = "us-east-1"
}
variable "expires_at" { type = string }
variable "image_tag" { type = string }
variable "desired_count" {
  type    = number
  default = 0
  validation {
    condition     = contains([0, 1], var.desired_count)
    error_message = "Evidence mode supports zero or one task."
  }
}
variable "bff_shared_secret" {
  type      = string
  sensitive = true
  validation {
    condition     = length(var.bff_shared_secret) >= 32
    error_message = "The BFF secret must contain at least 32 characters."
  }
}
variable "ops_secret" {
  type      = string
  sensitive = true
  validation {
    condition     = length(var.ops_secret) >= 32 && var.ops_secret != var.bff_shared_secret
    error_message = "The operations secret must be distinct and contain at least 32 characters."
  }
}
variable "lab_role_name" {
  type    = string
  default = "LabRole"
}
