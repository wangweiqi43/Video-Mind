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
$mavenCandidates = @(
    "E:\Maven\bin\mvn.cmd",
    (Join-Path $runtimeDir "tools\apache-maven-3.9.9\bin\mvn.cmd")
)
$maven = $mavenCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$nodeDir = "E:\NodeJS"
$npm = Join-Path $nodeDir "npm.cmd"
$javaHome = "E:\Java"
$bundledFfmpegBin = Join-Path $runtimeDir "tools\ffmpeg-8.1.2-essentials_build\bin"
$ffmpeg = @(
    (Join-Path $bundledFfmpegBin "ffmpeg.exe"),
    "E:\FFmpeg\bin\ffmpeg.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$ffprobe = @(
    (Join-Path $bundledFfmpegBin "ffprobe.exe"),
    "E:\FFmpeg\bin\ffprobe.exe"
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
$backendLog = Join-Path $logDir "backend-start.out.log"
$backendErrorLog = Join-Path $logDir "backend-start.err.log"
$frontendLog = Join-Path $logDir "frontend-start.out.log"
$frontendErrorLog = Join-Path $logDir "frontend-start.err.log"
$localSecretsFile = Join-Path $runtimeDir "local-secrets.env"
$backendHealthUrl = "http://localhost:8080/actuator/health"
$frontendHealthUrl = "http://localhost:5173"

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Import-EnvironmentFile {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith("#")) {
            continue
        }
        $separator = $trimmed.IndexOf("=")
        if ($separator -le 0) {
            continue
        }
        $name = $trimmed.Substring(0, $separator).Trim()
        $value = $trimmed.Substring($separator + 1).Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
    }
}

function Import-UserEnvironmentVariable {
    param(
        [Parameter(Mandatory)]
        [string]$Name
    )

    if ([Environment]::GetEnvironmentVariable($Name, "Process")) {
        return
    }
    $value = [Environment]::GetEnvironmentVariable($Name, "User")
    if ($value) {
        [Environment]::SetEnvironmentVariable($Name, $value, "Process")
    }
}

function Get-EnvironmentFileValue {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        if ($line -match ('^' + [regex]::Escape($Name) + '=(.+)$')) {
            return $Matches[1].Trim()
        }
    }
    return $null
}

function Test-UsableSecret {
    param([AllowNull()][string]$Value)

    if (-not $Value -or $Value -match '^(replace-with|your_)') {
        return $false
    }
    return [Text.Encoding]::UTF8.GetByteCount($Value) -ge 32
}

function New-RandomSecret {
    $bytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    } finally {
        $generator.Dispose()
    }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Ensure-LocalDevelopmentSecret {
    param(
        [Parameter(Mandatory)]
        [string]$Name
    )

    $current = [Environment]::GetEnvironmentVariable($Name, "Process")
    if (Test-UsableSecret -Value $current) {
        return
    }
    $stored = Get-EnvironmentFileValue -Path $localSecretsFile -Name $Name
    if (Test-UsableSecret -Value $stored) {
        [Environment]::SetEnvironmentVariable($Name, $stored, "Process")
        return
    }
    $generated = New-RandomSecret
    Add-Content -LiteralPath $localSecretsFile -Encoding UTF8 -Value "$Name=$generated"
    [Environment]::SetEnvironmentVariable($Name, $generated, "Process")
    Write-Warning "$Name was missing or too short. A persistent local development value was created in runtime\local-secrets.env."
}

Import-EnvironmentFile -Path (Join-Path $repoRoot ".env")
foreach ($name in @(
    "SILICONFLOW_API_KEY",
    "DASHSCOPE_API_KEY",
    "TENCENT_CLOUD_SECRET_ID",
    "TENCENT_CLOUD_SECRET_KEY",
    "VIDEOMIND_DATA_ROOT",
    "VIDEOMIND_JWT_SECRET",
    "VIDEOMIND_TOKEN_ENCRYPTION_KEY",
    "FFMPEG_BINARY_PATH",
    "FFPROBE_BINARY_PATH"
)) {
    Import-UserEnvironmentVariable -Name $name
}
Ensure-LocalDevelopmentSecret -Name "VIDEOMIND_JWT_SECRET"
Ensure-LocalDevelopmentSecret -Name "VIDEOMIND_TOKEN_ENCRYPTION_KEY"

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
        [int]$TimeoutSeconds = 90,
        [System.Diagnostics.Process]$Process,
        [string]$OutputLog,
        [string]$ErrorLog,
        [string]$PidFile
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-HttpEndpoint -Url $Url) {
            Write-Host "[OK] $Name is ready: $Url" -ForegroundColor Green
            return
        }
        if ($Process -and $Process.HasExited) {
            if ($PidFile) {
                Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
            }
            $details = [System.Collections.Generic.List[string]]::new()
            foreach ($log in @($ErrorLog, $OutputLog)) {
                if ($log -and (Test-Path -LiteralPath $log)) {
                    $tail = (Get-Content -LiteralPath $log -Tail 35 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
                    if ($tail) {
                        $details.Add("--- $log ---`n$tail")
                    }
                }
            }
            throw "$Name process exited before becoming ready (exit code $($Process.ExitCode)).`n$($details -join "`n")"
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
if (-not (Test-Path -LiteralPath $npm)) {
    $npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if (-not $npmCommand) {
        throw "npm.cmd was not found at E:\NodeJS or in PATH."
    }
    $npm = $npmCommand.Source
}
if (-not $maven) {
    throw "Maven was not found at E:\Maven or in runtime\tools."
}

if (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe")) {
    $env:JAVA_HOME = $javaHome
    $env:PATH = "$javaHome\bin;$env:PATH"
}
if ($ffmpeg -and (Test-Path -LiteralPath $ffmpeg)) {
    $env:FFMPEG_BINARY_PATH = $ffmpeg
} else {
    throw "ffmpeg.exe was not found. Configure FFMPEG_BINARY_PATH or install the bundled runtime tool."
}
if ($ffprobe -and (Test-Path -LiteralPath $ffprobe)) {
    $env:FFPROBE_BINARY_PATH = $ffprobe
} else {
    throw "ffprobe.exe was not found. Configure FFPROBE_BINARY_PATH or install the bundled runtime tool."
}
$env:PATH = "$nodeDir;$env:PATH"

if (-not (Test-DockerReady)) {
    Start-DockerDesktop
}

try {
    $physicalMemoryBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    $physicalMemoryGiB = [math]::Round(
        $physicalMemoryBytes / 1GB,
        1
    )
    if ($physicalMemoryBytes -lt 16000000000) {
        Write-Warning "Only $physicalMemoryGiB GiB physical memory was detected. MinerU pipeline CPU mode requires a 16 GB-class machine for real E2E; ops\e2e-local.ps1 will refuse the real run."
    } else {
        Write-Host "[OK] Physical memory: $physicalMemoryGiB GiB (16 GB-class)" -ForegroundColor Green
    }
} catch {
    Write-Warning "Physical memory could not be detected. Verify a 16 GB-class machine before running real MinerU E2E."
}

$composeHelp = (& docker compose up --help 2>&1) -join "`n"
if ($composeHelp -notmatch '--wait') {
    throw "Docker Compose does not support 'up --wait'. Upgrade Docker Desktop before starting VideoMind."
}

$dependencyServices = @(
    "mysql",
    "minio",
    "rocketmq-namesrv",
    "rocketmq-broker",
    "redis-stack",
    "redis-cache",
    "elasticsearch",
    "mineru",
    "paddleocr"
)

Write-Host "[..] Starting Docker dependencies..."
& docker compose --project-directory $repoRoot config --quiet
if ($LASTEXITCODE -ne 0) {
    throw "docker-compose.yml validation failed."
}
& docker compose --project-directory $repoRoot up -d --wait --wait-timeout 1800 @dependencyServices
if ($LASTEXITCODE -ne 0) {
    & docker compose --project-directory $repoRoot ps
    throw "Docker dependencies failed to become healthy. Inspect the unhealthy service logs with 'docker compose logs <service>'."
}

if (-not $env:SILICONFLOW_API_KEY) {
    Write-Warning "SILICONFLOW_API_KEY is not configured. Real AI requests will fail."
}
if (-not $env:DASHSCOPE_API_KEY) {
    Write-Warning "DASHSCOPE_API_KEY is not configured. Document image understanding will degrade safely."
}
if (-not $env:TENCENT_CLOUD_SECRET_ID -or -not $env:TENCENT_CLOUD_SECRET_KEY) {
    Write-Warning "Tencent ASR credentials are not configured. Set rotated TENCENT_CLOUD_SECRET_ID and TENCENT_CLOUD_SECRET_KEY as local environment variables; never write them to the repository."
}

$env:VIDEOMIND_ASR_MODE = "real"
$env:VIDEOMIND_SUMMARY_MODE = "real"
$env:VIDEOMIND_EMBEDDING_MODE = "real"
$env:VIDEOMIND_CHAT_MODE = "real"
$env:VIDEOMIND_VISION_MODE = "real"
$env:KNOWLEDGE_TTL_SECONDS = "2592000"
$env:REDIS_PORT = "6380"

$backendProcess = $null
$frontendProcess = $null
$backendPidFile = Join-Path $runtimeDir "backend.pid"
$frontendPidFile = Join-Path $runtimeDir "frontend.pid"

if (Test-HttpEndpoint -Url $backendHealthUrl) {
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
    Set-Content -LiteralPath $backendPidFile -Value $backendProcess.Id
}

Wait-ForEndpoint -Name "Backend" -Url $backendHealthUrl -TimeoutSeconds 120 `
    -Process $backendProcess -OutputLog $backendLog -ErrorLog $backendErrorLog -PidFile $backendPidFile

if (-not (Test-Path -LiteralPath (Join-Path $frontendDir "node_modules"))) {
    Write-Host "[..] Installing frontend dependencies..."
    & $npm install --prefix $frontendDir
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend dependency installation failed."
    }
}

if (Test-HttpEndpoint -Url $frontendHealthUrl) {
    Write-Host "[OK] Frontend is already running." -ForegroundColor Green
} else {
    $portOwner = Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue
    if ($portOwner) {
        throw "Port 5173 is occupied by another process (PID $($portOwner[0].OwningProcess))."
    }

    Write-Host "[..] Starting frontend..."
    $frontendProcess = Start-Process `
        -FilePath $npm `
        -ArgumentList @("run", "dev") `
        -WorkingDirectory $frontendDir `
        -RedirectStandardOutput $frontendLog `
        -RedirectStandardError $frontendErrorLog `
        -WindowStyle Hidden `
        -PassThru
    Set-Content -LiteralPath $frontendPidFile -Value $frontendProcess.Id
}

Wait-ForEndpoint -Name "Frontend" -Url $frontendHealthUrl -TimeoutSeconds 60 `
    -Process $frontendProcess -OutputLog $frontendLog -ErrorLog $frontendErrorLog -PidFile $frontendPidFile

Write-Host ""
Write-Host "VideoMind is running." -ForegroundColor Cyan
Write-Host "Frontend:     http://localhost:5173"
Write-Host "Backend API:  http://localhost:8080"
Write-Host "Backend health:http://localhost:8080/actuator/health"
Write-Host "MinIO:        http://localhost:9002"
Write-Host "RedisInsight: http://localhost:8001"
Write-Host "Elasticsearch:http://localhost:9201"
Write-Host "MinerU:      http://localhost:8003"
Write-Host "PaddleOCR:   http://localhost:8868"
Write-Host "Logs:         $logDir"

if ($OpenBrowser) {
    Start-Process "http://localhost:5173"
}
