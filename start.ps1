[CmdletBinding()]
param(
    [switch]$OpenBrowser
)

$ErrorActionPreference = "Stop"
$repoRoot = $PSScriptRoot
$runtimeDir = Join-Path $repoRoot "runtime"
$logDir = Join-Path $runtimeDir "logs"
$backendDir = Join-Path $repoRoot "backend\videomind-server"
$frontendDir = Join-Path $repoRoot "frontend\videomind-web"
$maven = Join-Path $runtimeDir "tools\apache-maven-3.9.9\bin\mvn.cmd"
$backendLog = Join-Path $logDir "backend-start.out.log"
$backendErrorLog = Join-Path $logDir "backend-start.err.log"
$frontendLog = Join-Path $logDir "frontend-start.out.log"
$frontendErrorLog = Join-Path $logDir "frontend-start.err.log"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Test-HttpEndpoint {
    param(
        [Parameter(Mandatory)]
        [string]$Url
    )

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 3
        return $response.StatusCode -ge 200 -and $response.StatusCode -lt 500
    } catch {
        return $false
    }
}

function Wait-ForEndpoint {
    param(
        [Parameter(Mandatory)]
        [string]$Name,
        [Parameter(Mandatory)]
        [string]$Url,
        [int]$TimeoutSeconds = 90
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpEndpoint -Url $Url) {
            Write-Host "[OK] $Name is ready: $Url" -ForegroundColor Green
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    throw "$Name did not become ready in $TimeoutSeconds seconds. Check runtime\logs."
}

function Test-DockerReady {
    try {
        docker info *> $null
        return $LASTEXITCODE -eq 0
    } catch {
        return $false
    }
}

function Start-DockerDesktop {
    $candidates = @(
        "E:\Docker\Docker Desktop.exe",
        "C:\Program Files\Docker\Docker\Docker Desktop.exe"
    )
    $dockerDesktop = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if (-not $dockerDesktop) {
        throw "Docker Desktop is not running and Docker Desktop.exe was not found."
    }

    Write-Host "[..] Starting Docker Desktop..."
    Start-Process -FilePath $dockerDesktop -WindowStyle Hidden | Out-Null
    $deadline = (Get-Date).AddMinutes(2)
    do {
        Start-Sleep -Seconds 3
        if (Test-DockerReady) {
            Write-Host "[OK] Docker Desktop is ready." -ForegroundColor Green
            return
        }
    } while ((Get-Date) -lt $deadline)

    throw "Docker Desktop did not become ready within 2 minutes."
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker.exe was not found in PATH."
}
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw "npm.cmd was not found in PATH."
}
if (-not (Test-Path -LiteralPath $maven)) {
    throw "Bundled Maven was not found: $maven"
}

if (-not (Test-DockerReady)) {
    Start-DockerDesktop
}

Write-Host "[..] Starting Docker dependencies..."
& docker compose --project-directory $repoRoot up -d mysql minio rocketmq-namesrv rocketmq-broker redis-stack
if ($LASTEXITCODE -ne 0) {
    throw "Docker dependencies failed to start."
}

$apiKey = [Environment]::GetEnvironmentVariable("SILICONFLOW_API_KEY", "User")
if (-not $env:SILICONFLOW_API_KEY -and $apiKey) {
    $env:SILICONFLOW_API_KEY = $apiKey
}
if (-not $env:SILICONFLOW_API_KEY) {
    Write-Warning "SILICONFLOW_API_KEY is not configured. Real AI requests will fail."
}

$env:VIDEOMIND_ASR_MODE = "real"
$env:VIDEOMIND_SUMMARY_MODE = "real"
$env:VIDEOMIND_EMBEDDING_MODE = "real"
$env:VIDEOMIND_CHAT_MODE = "real"
$env:KNOWLEDGE_TTL_SECONDS = "2592000"

if (Test-HttpEndpoint -Url "http://localhost:8080/api/videos/list") {
    Write-Host "[OK] Backend is already running." -ForegroundColor Green
} else {
    $portOwner = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
    if ($portOwner) {
        throw "Port 8080 is occupied by another process (PID $($portOwner[0].OwningProcess))."
    }

    Write-Host "[..] Starting backend..."
    $backendProcess = Start-Process `
        -FilePath $maven `
        -ArgumentList @("spring-boot:run") `
        -WorkingDirectory $backendDir `
        -RedirectStandardOutput $backendLog `
        -RedirectStandardError $backendErrorLog `
        -WindowStyle Hidden `
        -PassThru
    Set-Content -LiteralPath (Join-Path $runtimeDir "backend.pid") -Value $backendProcess.Id
}

if (-not (Test-Path -LiteralPath (Join-Path $frontendDir "node_modules"))) {
    Write-Host "[..] Installing frontend dependencies..."
    & npm.cmd install --prefix $frontendDir
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend dependency installation failed."
    }
}

if (Test-HttpEndpoint -Url "http://localhost:5173") {
    Write-Host "[OK] Frontend is already running." -ForegroundColor Green
} else {
    $portOwner = Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue
    if ($portOwner) {
        throw "Port 5173 is occupied by another process (PID $($portOwner[0].OwningProcess))."
    }

    Write-Host "[..] Starting frontend..."
    $frontendProcess = Start-Process `
        -FilePath "npm.cmd" `
        -ArgumentList @("run", "dev") `
        -WorkingDirectory $frontendDir `
        -RedirectStandardOutput $frontendLog `
        -RedirectStandardError $frontendErrorLog `
        -WindowStyle Hidden `
        -PassThru
    Set-Content -LiteralPath (Join-Path $runtimeDir "frontend.pid") -Value $frontendProcess.Id
}

Wait-ForEndpoint -Name "Backend" -Url "http://localhost:8080/api/videos/list" -TimeoutSeconds 120
Wait-ForEndpoint -Name "Frontend" -Url "http://localhost:5173" -TimeoutSeconds 60

Write-Host ""
Write-Host "VideoMind is running." -ForegroundColor Cyan
Write-Host "Frontend:     http://localhost:5173"
Write-Host "Backend API:  http://localhost:8080"
Write-Host "MinIO:        http://localhost:9002"
Write-Host "RedisInsight: http://localhost:8001"
Write-Host "Logs:         $logDir"

if ($OpenBrowser) {
    Start-Process "http://localhost:5173"
}
