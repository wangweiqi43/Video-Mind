[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [ValidateRange(0, 2147483647)]
    [int]$MaxVideos = 0,
    [ValidateRange(1, 300)]
    [int]$PollSeconds = 2,
    [ValidateRange(10, 86400)]
    [int]$TimeoutSeconds = 1800,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-ApiData {
    param([Parameter(Mandatory = $true)]$Response)
    if ($null -eq $Response -or $Response.code -ne 0) {
        $message = if ($null -ne $Response -and $Response.message) { $Response.message } else { "empty response" }
        throw "VideoMind API request failed: $message"
    }
    return $Response.data
}

function Assert-LoopbackBaseUrl {
    param([Parameter(Mandatory = $true)][string]$Value)
    $uri = [Uri]$Value
    if ($uri.Scheme -notin @("http", "https")) {
        throw "BaseUrl must use http or https"
    }
    if ($uri.Host -notin @("127.0.0.1", "localhost", "::1")) {
        throw "This operations script only accepts a loopback BaseUrl"
    }
    return $uri.GetLeftPart([UriPartial]::Authority).TrimEnd("/")
}

$localBaseUrl = Assert-LoopbackBaseUrl -Value $BaseUrl
if ($ValidateOnly) {
    Write-Host "Video knowledge rebuild script validation succeeded for $localBaseUrl"
    exit 0
}

$username = $env:VIDEOMIND_REBUILD_USERNAME
$password = $env:VIDEOMIND_REBUILD_PASSWORD
if ([string]::IsNullOrWhiteSpace($username) -or [string]::IsNullOrWhiteSpace($password)) {
    throw "Set VIDEOMIND_REBUILD_USERNAME and VIDEOMIND_REBUILD_PASSWORD in the current process"
}

$webSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$loginBody = @{ username = $username; password = $password } | ConvertTo-Json -Compress
$loginResponse = Invoke-RestMethod -Method Post -Uri "$localBaseUrl/api/auth/login" `
    -WebSession $webSession -ContentType "application/json" -Body $loginBody
$null = Get-ApiData -Response $loginResponse

$listResponse = Invoke-RestMethod -Method Get -Uri "$localBaseUrl/api/videos/list" -WebSession $webSession
$videos = @(Get-ApiData -Response $listResponse)
if ($MaxVideos -gt 0) {
    $videos = @($videos | Select-Object -First $MaxVideos)
}

$rebuilt = 0
$reused = 0
foreach ($video in $videos) {
    $body = @{ videoId = [long]$video.id } | ConvertTo-Json -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$localBaseUrl/api/tasks/analyze" `
        -WebSession $webSession -ContentType "application/json" -Body $body
    $task = Get-ApiData -Response $response
    if ($task.reused -and $task.status -eq "SUCCESS") {
        $reused++
        Write-Host "READY videoId=$($video.id) taskId=$($task.taskId)"
        continue
    }

    $rebuilt++
    Write-Host "REBUILD videoId=$($video.id) taskId=$($task.taskId)"
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $completed = $false
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        Start-Sleep -Seconds $PollSeconds
        $pollResponse = Invoke-RestMethod -Method Get `
            -Uri "$localBaseUrl/api/tasks/$($task.taskId)" -WebSession $webSession
        $current = Get-ApiData -Response $pollResponse
        $status = [string]$current.taskStatus
        if ($status -eq "SUCCESS") {
            Write-Host "DONE videoId=$($video.id) taskId=$($task.taskId)"
            $completed = $true
            break
        }
        if ($status -in @("FAILED", "CANCELLED")) {
            throw "Rebuild failed for videoId=$($video.id), taskId=$($task.taskId), status=$status"
        }
    }
    if (-not $completed) {
        throw "Rebuild timed out for videoId=$($video.id), taskId=$($task.taskId)"
    }
}

Write-Host "Sequential rebuild finished: scanned=$($videos.Count), rebuilt=$rebuilt, reused=$reused"
