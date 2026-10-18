$ErrorActionPreference = 'Stop'
$qaRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $qaRoot
if (Get-NetTCPConnection -State Listen -LocalPort 18082,18083,13002 -ErrorAction SilentlyContinue) {
    throw 'QA ports 18082/18083/13002 are already in use'
}
$qaOutput = Join-Path $qaRoot 'backend/target/operator-browser'
New-Item -ItemType Directory -Path $qaOutput -Force | Out-Null
$qaSuffix = [guid]::NewGuid().ToString('N').Substring(0,8)
$qaPostgres = "vmarket-auth-pg-$qaSuffix"
$qaRedis = "vmarket-auth-redis-$qaSuffix"
$qaBackend = $null
$qaFrontend = $null
$qaBridge = $null

function Wait-QaHttp([string]$Url, [System.Diagnostics.Process]$Process) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 60) {
        if ($Process.HasExited) { throw "QA server exited: $Url. Inspect $qaOutput" }
        try {
            if ((Invoke-WebRequest -Uri $Url -TimeoutSec 2).StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 200
    }
    throw "QA server did not become ready: $Url. Inspect $qaOutput"
}

try {
    docker run --rm -d --name $qaPostgres -e POSTGRES_DB=vmarket -e POSTGRES_USER=vmarket -e POSTGRES_PASSWORD=qa-local-password -p '127.0.0.1::5432' postgres:17.6-alpine
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL QA container failed' }
    docker run --rm -d --name $qaRedis -p '127.0.0.1::6379' redis:8.2-alpine
    if ($LASTEXITCODE -ne 0) { throw 'Redis QA container failed' }
    $qaPgPort = (docker port $qaPostgres 5432) -replace '^.*:', ''
    $qaRedisPort = (docker port $qaRedis 6379) -replace '^.*:', ''
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:$qaPgPort/vmarket"
    $env:SPRING_DATASOURCE_USERNAME = 'vmarket'
    $env:SPRING_DATASOURCE_PASSWORD = 'qa-local-password'
    $env:SPRING_DATA_REDIS_URL = "redis://127.0.0.1:$qaRedisPort"
    $env:BFF_SHARED_SECRET = 'qa-bff-session-only'
    $env:VMARKET_OPS_SECRET = 'qa-ops-session-only'
    $env:AUTH_SECURE_COOKIE = 'false'
    $env:IMPORT_WORKER_ENABLED = 'false'
    $env:AUTH_BOOTSTRAP_USERNAME = 'qa-operator@example.test'
    $env:AUTH_BOOTSTRAP_PASSWORD_HASH = python -c "import bcrypt; print(bcrypt.hashpw(b'qa-session-password', bcrypt.gensalt()).decode())"
    if ($LASTEXITCODE -ne 0) { throw 'Python bcrypt is required' }
    $env:TEST_OPERATOR_USERNAME = 'qa-operator@example.test'
    $env:TEST_OPERATOR_PASSWORD = 'qa-session-password'
    $env:BACKEND_ORIGIN = 'http://127.0.0.1:18082'
    $env:BASE_URL = 'http://127.0.0.1:13002'
    $env:TEST_POSTGRES_CONTAINER = $qaPostgres
    # Ephemeral QA capability stays in the runner/child environments, never in logs or artifacts.
    $env:TEST_CHECKOUT_CAPABILITY = python -c "import secrets; print(secrets.token_hex(32))"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a disposable checkout capability' }
    $env:TEST_CHECKOUT_KEY = [guid]::NewGuid().ToString()
    $qaBackend = Start-Process -FilePath 'java' -ArgumentList '-jar','backend/target/v-market-backend-0.1.0-SNAPSHOT.jar','--server.port=18082' -WorkingDirectory $qaRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qaOutput 'backend.log') -RedirectStandardError (Join-Path $qaOutput 'backend-error.log')
    Wait-QaHttp 'http://127.0.0.1:18082/actuator/health' $qaBackend
    $qaFrontend = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c','npm run start -- --hostname 127.0.0.1 --port 13002' -WorkingDirectory $qaRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qaOutput 'frontend.log') -RedirectStandardError (Join-Path $qaOutput 'frontend-error.log')
    Wait-QaHttp 'http://127.0.0.1:13002/ops' $qaFrontend
    python tools/qa_durable_dispatch.py stage
    if ($LASTEXITCODE -ne 0) { throw 'Durable staging verification failed' }
    # Kill only this runner-owned Java process, while READY work is durably committed.
    taskkill /PID $qaBackend.Id /T /F | Out-Null
    $qaBackend.WaitForExit()
    $env:IMPORT_WORKER_ENABLED = 'true'
    $qaBackend = Start-Process -FilePath 'java' -ArgumentList '-jar','backend/target/v-market-backend-0.1.0-SNAPSHOT.jar','--server.port=18082' -WorkingDirectory $qaRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qaOutput 'resumed-backend.log') -RedirectStandardError (Join-Path $qaOutput 'resumed-backend-error.log')
    Wait-QaHttp 'http://127.0.0.1:18082/actuator/health' $qaBackend
    python tools/qa_durable_dispatch.py resume
    if ($LASTEXITCODE -ne 0) { throw 'Durable work did not survive the owned Java process restart' }
    python tools/qa_started_recovery.py stage
    if ($LASTEXITCODE -ne 0) { throw 'Could not reach controlled STARTED crash window' }
    taskkill /PID $qaBackend.Id /T /F | Out-Null
    $qaBackend.WaitForExit()
    python tools/qa_started_recovery.py prepare
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare disposable STARTED recovery proof' }
    $qaBackend = Start-Process -FilePath 'java' -ArgumentList '-jar','backend/target/v-market-backend-0.1.0-SNAPSHOT.jar','--server.port=18082' -WorkingDirectory $qaRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qaOutput 'recovered-backend.log') -RedirectStandardError (Join-Path $qaOutput 'recovered-backend-error.log')
    Wait-QaHttp 'http://127.0.0.1:18082/actuator/health' $qaBackend
    python tools/qa_started_recovery.py resume
    if ($LASTEXITCODE -ne 0) { throw 'STARTED recovery proof failed' }
    python tools/qa_operator_session.py
    if ($LASTEXITCODE -ne 0) { throw 'Operator browser verification failed' }
    # Separate phase: delay only the real Java response, after its checkout transaction commits.
    taskkill /PID $qaFrontend.Id /T /F | Out-Null
    $qaFrontend.WaitForExit()
    $env:TEST_JAVA_ORIGIN = $env:BACKEND_ORIGIN
    $qaBridge = Start-Process -FilePath 'python' -ArgumentList '-u','tools/qa_bff_deadline.py','serve' -WorkingDirectory $qaRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qaOutput 'deadline-bridge.log') -RedirectStandardError (Join-Path $qaOutput 'deadline-bridge-error.log')
    Wait-QaHttp 'http://127.0.0.1:18083/__qa/observation' $qaBridge
    $env:BACKEND_ORIGIN = 'http://127.0.0.1:18083'
    $qaFrontend = Start-Process -FilePath 'cmd.exe' -ArgumentList '/d','/c','npm run start -- --hostname 127.0.0.1 --port 13002' -WorkingDirectory $qaRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qaOutput 'deadline-frontend.log') -RedirectStandardError (Join-Path $qaOutput 'deadline-frontend-error.log')
    Wait-QaHttp 'http://127.0.0.1:13002/' $qaFrontend
    python tools/qa_bff_deadline.py verify
    if ($LASTEXITCODE -ne 0) { throw 'Committed-checkout BFF deadline proof failed' }
} finally {
    foreach ($qaProcess in @($qaFrontend, $qaBridge, $qaBackend)) {
        if ($qaProcess -and -not $qaProcess.HasExited) {
            taskkill /PID $qaProcess.Id /T /F | Out-Null
        }
    }
    docker stop $qaPostgres $qaRedis
}
