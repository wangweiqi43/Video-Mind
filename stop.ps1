[CmdletBinding()]
param(
    [switch]$KeepDocker
)

$ErrorActionPreference = "Stop"
$repoRoot = $PSScriptRoot
$runtimeDir = Join-Path $repoRoot "runtime"

function Get-ProcessCommandLine {
    param(
        [Parameter(Mandatory)]
        [int]$ProcessId
    )

    return (Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue).CommandLine
}

function Get-DescendantProcessIds {
    param(
        [Parameter(Mandatory)]
        [int]$ProcessId
    )

    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue)
    $result = [System.Collections.Generic.List[int]]::new()
    foreach ($child in $children) {
        foreach ($descendantId in @(Get-DescendantProcessIds -ProcessId $child.ProcessId)) {
            $result.Add($descendantId)
        }
        $result.Add([int]$child.ProcessId)
    }
    return $result
}

function Stop-VideoMindProcessTree {
    param(
        [Parameter(Mandatory)]
        [int]$ProcessId,
        [Parameter(Mandatory)]
        [string]$Name
    )

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (-not $process) {
        Write-Host "[OK] $Name is already stopped." -ForegroundColor Green
        return $false
    }

    $descendantIds = @(Get-DescendantProcessIds -ProcessId $ProcessId)
    $belongsToProject = (Get-ProcessCommandLine -ProcessId $ProcessId) -like "*$repoRoot*"
    if (-not $belongsToProject) {
        foreach ($descendantId in $descendantIds) {
            if ((Get-ProcessCommandLine -ProcessId $descendantId) -like "*$repoRoot*") {
                $belongsToProject = $true
                break
            }
        }
    }
    if (-not $belongsToProject) {
        Write-Warning "Skipped PID $ProcessId because it does not belong to $repoRoot."
        return $false
    }

    foreach ($descendantId in $descendantIds) {
        Stop-Process -Id $descendantId -Force -ErrorAction SilentlyContinue
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    Write-Host "[OK] $Name stopped." -ForegroundColor Green
    return $true
}

function Stop-ProcessFromPidFile {
    param(
        [Parameter(Mandatory)]
        [string]$PidFile,
        [Parameter(Mandatory)]
        [string]$Name
    )

    if (-not (Test-Path -LiteralPath $PidFile)) {
        Write-Host "[..] No PID file found for $Name."
        return
    }

    $rawPid = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    $processId = 0
    if ([int]::TryParse($rawPid, [ref]$processId)) {
        Stop-VideoMindProcessTree -ProcessId $processId -Name $Name | Out-Null
    } else {
        Write-Warning "Invalid PID file: $PidFile"
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Stop-VerifiedPortProcess {
    param(
        [Parameter(Mandatory)]
        [int]$Port,
        [Parameter(Mandatory)]
        [string]$Name
    )

    $connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    foreach ($connection in $connections) {
        $processId = [int]$connection.OwningProcess
        $commandLine = Get-ProcessCommandLine -ProcessId $processId
        if ($commandLine -and $commandLine -like "*$repoRoot*") {
            Stop-VideoMindProcessTree -ProcessId $processId -Name $Name | Out-Null
        } elseif ($processId) {
            Write-Warning "Port $Port is still used by unrelated PID $processId; it was not stopped."
        }
    }
}

Stop-ProcessFromPidFile -PidFile (Join-Path $runtimeDir "frontend.pid") -Name "Frontend"
Stop-ProcessFromPidFile -PidFile (Join-Path $runtimeDir "backend.pid") -Name "Backend"

# Handles stale PID files or child processes that outlived their launcher.
Stop-VerifiedPortProcess -Port 5173 -Name "Frontend"
Stop-VerifiedPortProcess -Port 8080 -Name "Backend"

if ($KeepDocker) {
    Write-Host "[OK] Docker dependencies were kept running." -ForegroundColor Green
} elseif (Get-Command docker -ErrorAction SilentlyContinue) {
    try {
        docker info *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[..] Stopping Docker dependencies..."
            & docker compose --project-directory $repoRoot stop
            if ($LASTEXITCODE -ne 0) {
                throw "docker compose stop failed."
            }
            Write-Host "[OK] Docker dependencies stopped. Data volumes were preserved." -ForegroundColor Green
        } else {
            Write-Host "[OK] Docker is already stopped." -ForegroundColor Green
        }
    } catch {
        Write-Warning "Docker dependencies could not be stopped: $($_.Exception.Message)"
    }
} else {
    Write-Warning "docker.exe was not found; Docker dependencies were not changed."
}

$remainingPorts = @(Get-NetTCPConnection -State Listen -LocalPort 8080,5173 -ErrorAction SilentlyContinue)
if ($remainingPorts) {
    Write-Warning "Some project ports are still occupied. Review the warnings above."
} else {
    Write-Host ""
    Write-Host "VideoMind has stopped." -ForegroundColor Cyan
}
