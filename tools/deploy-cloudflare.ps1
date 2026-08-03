$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path (Split-Path -Parent $repoRoot) ".env"

if (!(Test-Path $envPath)) {
  throw "Missing .env at $envPath"
}

Get-Content $envPath | ForEach-Object {
  if ($_ -match "^\s*([^#=]+)=(.*)$") {
    $name = $matches[1].Trim()
    $value = $matches[2].Trim()
    Set-Item -Path "Env:$name" -Value $value
  }
}

if (!$env:CLOUDFLARE_ACCOUNT_ID -and $env:ACCOUNT_ID) {
  $env:CLOUDFLARE_ACCOUNT_ID = $env:ACCOUNT_ID
}

if (!$env:CLOUDFLARE_API_TOKEN -and $env:API_TOKEN) {
  $env:CLOUDFLARE_API_TOKEN = $env:API_TOKEN
}

if (!$env:CLOUDFLARE_ACCOUNT_ID -or !$env:CLOUDFLARE_API_TOKEN) {
  throw "Cloudflare credentials are incomplete. Required: ACCOUNT_ID/API_TOKEN or CLOUDFLARE_ACCOUNT_ID/CLOUDFLARE_API_TOKEN."
}

Push-Location $repoRoot
try {
  npm run deploy
} finally {
  Pop-Location
}
