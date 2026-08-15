[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$ElasticsearchUrl = "http://127.0.0.1:9201",
    [ValidateRange(1, 30)]
    [int]$PollSeconds = 2,
    [ValidateRange(60, 7200)]
    [int]$TimeoutSeconds = 1800,
    [switch]$ExerciseBrokerRestart,
    [switch]$LongAsr,
    [switch]$SkipCacheFailover,
    [switch]$KeepData,
    [switch]$KeepArtifacts,
    [switch]$FixtureOnly,
    [switch]$PreflightOnly,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$repoRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $repoRoot "runtime\e2e"
$runId = [Guid]::NewGuid().ToString("N").Substring(0, 12)
$runDirectory = Join-Path $runtimeRoot $runId
$videoPath = Join-Path $runDirectory "timeline-evidence.mp4"
$speechPath = Join-Path $runDirectory "speech.wav"
$pdfPath = Join-Path $runDirectory "document-evidence.pdf"
$videoToken = "VIDEO TOKEN $($runId.ToUpperInvariant())"
$documentToken = "DOCUMENT TOKEN $($runId.ToUpperInvariant())"
$cacheStopped = $false
$createdVideoId = $null
$createdKnowledgeBaseId = $null
$authHeaders = $null

function Assert-Condition {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Get-LoopbackUrl {
    param([Parameter(Mandatory = $true)][string]$Value)
    $uri = [Uri]$Value
    if ($uri.Scheme -notin @("http", "https")) {
        throw "URL must use http or https: $Value"
    }
    if ($uri.Host -notin @("127.0.0.1", "localhost", "::1")) {
        throw "The local E2E script only accepts loopback URLs: $Value"
    }
    return $uri.GetLeftPart([UriPartial]::Authority).TrimEnd("/")
}

function Get-ApiData {
    param([Parameter(Mandatory = $true)]$Response)
    if ($null -eq $Response -or $Response.code -ne 0) {
        $message = if ($null -ne $Response -and $Response.message) { $Response.message } else { "empty response" }
        throw "VideoMind API request failed: $message"
    }
    return $Response.data
}

function Invoke-JsonApi {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("Get", "Post", "Delete")][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        $Body,
        [switch]$Anonymous
    )
    $parameters = @{
        Method = $Method
        Uri = "$script:localBaseUrl$Path"
        TimeoutSec = 180
    }
    if (-not $Anonymous) {
        $parameters.Headers = $script:authHeaders
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = $Body | ConvertTo-Json -Depth 12 -Compress
    }
    return Get-ApiData -Response (Invoke-RestMethod @parameters)
}

function Invoke-FileApi {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string]$MediaType
    )
    Add-Type -AssemblyName System.Net.Http
    $client = [Net.Http.HttpClient]::new()
    $multipart = [Net.Http.MultipartFormDataContent]::new()
    $stream = $null
    $fileContent = $null
    try {
        $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new(
            "Bearer", [string]$script:authHeaders.Authorization.Substring(7))
        $stream = [IO.File]::OpenRead($FilePath)
        $fileContent = [Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::Parse($MediaType)
        $multipart.Add($fileContent, "file", [IO.Path]::GetFileName($FilePath))
        $response = $client.PostAsync("$script:localBaseUrl$Path", $multipart).GetAwaiter().GetResult()
        $payload = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "Multipart request failed: status=$([int]$response.StatusCode)"
        }
        return Get-ApiData -Response ($payload | ConvertFrom-Json)
    } finally {
        if ($null -ne $fileContent) { $fileContent.Dispose() }
        if ($null -ne $stream) { $stream.Dispose() }
        $multipart.Dispose()
        $client.Dispose()
    }
}

function Get-ConfiguredEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = [Environment]::GetEnvironmentVariable($Name, "User")
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            [Environment]::SetEnvironmentVariable($Name, $value, "Process")
        }
    }
    return $value
}

function Resolve-Executable {
    param(
        [Parameter(Mandatory = $true)][string]$EnvironmentName,
        [Parameter(Mandatory = $true)][string[]]$Candidates,
        [Parameter(Mandatory = $true)][string]$CommandName
    )
    $configured = Get-ConfiguredEnvironmentValue -Name $EnvironmentName
    if ($configured -and (Test-Path -LiteralPath $configured)) {
        return (Resolve-Path -LiteralPath $configured).Path
    }
    foreach ($candidate in $Candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $command = Get-Command $CommandName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }
    throw "$CommandName was not found. Configure $EnvironmentName or add it to PATH."
}

function Get-MediaStreamDuration {
    param(
        [Parameter(Mandatory = $true)][string]$Ffprobe,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][ValidateSet("v:0", "a:0")][string]$Stream
    )
    $raw = & $Ffprobe -v error -select_streams $Stream -show_entries stream=duration `
        -of "default=noprint_wrappers=1:nokey=1" $Path
    if ($LASTEXITCODE -ne 0) { throw "ffprobe failed for stream $Stream" }
    $value = 0.0
    if (-not [double]::TryParse(([string]$raw).Trim(), [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
        throw "ffprobe returned an invalid duration for stream $Stream"
    }
    return $value
}

function New-MinimalPdf {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Token
    )
    $content = "BT /F1 18 Tf 72 730 Td ($Token) Tj 0 -34 Td /F1 13 Tf " +
        "(Duplicate delivery is handled by an idempotency state machine.) Tj 0 -24 Td " +
        "(The compensating action checks the inbox key before applying side effects.) Tj ET"
    $contentLength = [Text.Encoding]::ASCII.GetByteCount($content)
    $objects = @(
        "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj",
        "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj",
        "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >> endobj",
        "4 0 obj << /Length $contentLength >> stream`n$content`nendstream`nendobj",
        "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj"
    )
    $builder = [Text.StringBuilder]::new("%PDF-1.4`n")
    $offsets = [Collections.Generic.List[int]]::new()
    foreach ($object in $objects) {
        $offsets.Add([Text.Encoding]::ASCII.GetByteCount($builder.ToString()))
        [void]$builder.Append($object)
        [void]$builder.Append("`n")
    }
    $xrefOffset = [Text.Encoding]::ASCII.GetByteCount($builder.ToString())
    [void]$builder.Append("xref`n0 6`n0000000000 65535 f `n")
    foreach ($offset in $offsets) {
        [void]$builder.Append($offset.ToString("0000000000"))
        [void]$builder.Append(" 00000 n `n")
    }
    [void]$builder.Append("trailer << /Size 6 /Root 1 0 R >>`nstartxref`n")
    [void]$builder.Append($xrefOffset)
    [void]$builder.Append("`n%%EOF`n")
    [IO.File]::WriteAllBytes($Path, [Text.Encoding]::ASCII.GetBytes($builder.ToString()))
}

function New-TimelineVideo {
    param(
        [Parameter(Mandatory = $true)][string]$Ffmpeg,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$WavePath,
        [Parameter(Mandatory = $true)][string]$Token
    )
    $speechScript = (("$Token. The video explains CAS lease ownership and checkpoint resume after a crash. " * 7).Trim())
    New-SpeechWave -Path $WavePath -Text $speechScript
    $font = "C\:/Windows/Fonts/arial.ttf"
    $drawText = "drawtext=fontfile='$font':text='$Token CAS LEASE':fontcolor=white:fontsize=48:" +
        "x=(w-text_w)/2:y=(h-text_h)/2:enable='between(t,0,19.9)'," +
        "drawtext=fontfile='$font':text='$Token CHECKPOINT RESUME':fontcolor=white:fontsize=44:" +
        "x=(w-text_w)/2:y=(h-text_h)/2:enable='gte(t,20)'"
    & $Ffmpeg -hide_banner -loglevel error -y `
        -f lavfi -i "color=c=black:s=1280x720:r=25:d=40" `
        -i $WavePath -vf $drawText -af "apad" -t 40 `
        -c:v libx264 -pix_fmt yuv420p -g 25 -c:a aac -b:a 96k $OutputPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $OutputPath)) {
        throw "ffmpeg failed to create the E2E video"
    }
}

function New-SpeechWave {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    $speechGenerated = $false
    $speaker = $null
    try {
        Add-Type -AssemblyName System.Speech
        $speaker = [System.Speech.Synthesis.SpeechSynthesizer]::new()
        $speaker.Rate = -1
        $speaker.SetOutputToWaveFile($Path)
        $speaker.Speak($Text)
        $speechGenerated = Test-Path -LiteralPath $Path
    } catch {
        $speechGenerated = $false
    } finally {
        if ($null -ne $speaker) { $speaker.Dispose() }
    }
    if (-not $speechGenerated) {
        $voice = $null
        $waveStream = $null
        try {
            $voice = New-Object -ComObject SAPI.SpVoice
            $waveStream = New-Object -ComObject SAPI.SpFileStream
            $waveStream.Format.Type = 22
            $waveStream.Open($Path, 3, $false)
            $voice.AudioOutputStream = $waveStream
            $null = $voice.Speak($Text)
            $speechGenerated = Test-Path -LiteralPath $Path
        } finally {
            if ($null -ne $waveStream) {
                try { $waveStream.Close() } catch { }
                [void][Runtime.InteropServices.Marshal]::ReleaseComObject($waveStream)
            }
            if ($null -ne $voice) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($voice) }
        }
    }
    if (-not $speechGenerated) {
        throw "No local Windows text-to-speech engine could create the E2E speech fixture"
    }
}

function New-LongAsrTimelineVideo {
    param(
        [Parameter(Mandatory = $true)][string]$Ffmpeg,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [Parameter(Mandatory = $true)][string]$WavePath,
        [Parameter(Mandatory = $true)][string]$Token
    )
    $boundaryOne = [IO.Path]::Combine([IO.Path]::GetDirectoryName($WavePath), "speech-boundary-one.wav")
    $boundaryTwo = [IO.Path]::Combine([IO.Path]::GetDirectoryName($WavePath), "speech-boundary-two.wav")
    New-SpeechWave -Path $WavePath -Text "$Token. This is the opening evidence for long audio transcription."
    New-SpeechWave -Path $boundaryOne -Text "$Token. Boundary one confirms checkpoint resume near two minutes."
    New-SpeechWave -Path $boundaryTwo -Text "$Token. Boundary two confirms absolute timestamps near four minutes."
    $font = "C\:/Windows/Fonts/arial.ttf"
    $drawText = "drawtext=fontfile='$font':text='$Token LONG ASR START':fontcolor=white:fontsize=30:" +
        "x=(w-text_w)/2:y=(h-text_h)/2:enable='between(t,0,119.9)'," +
        "drawtext=fontfile='$font':text='$Token BOUNDARY ONE':fontcolor=white:fontsize=30:" +
        "x=(w-text_w)/2:y=(h-text_h)/2:enable='between(t,120,239.9)'," +
        "drawtext=fontfile='$font':text='$Token BOUNDARY TWO':fontcolor=white:fontsize=30:" +
        "x=(w-text_w)/2:y=(h-text_h)/2:enable='gte(t,240)'"
    $audioMix = "[2:a]adelay=5000|5000[a0];[3:a]adelay=119000|119000[a1];" +
        "[4:a]adelay=239000|239000[a2];[1:a][a0][a1][a2]amix=inputs=4:duration=first:dropout_transition=0[aout]"
    & $Ffmpeg -hide_banner -loglevel error -y `
        -f lavfi -i "color=c=black:s=640x360:r=10:d=251" `
        -f lavfi -t 250 -i "anullsrc=r=16000:cl=mono" `
        -i $WavePath -i $boundaryOne -i $boundaryTwo `
        -filter_complex $audioMix -map 0:v -map "[aout]" -vf $drawText -t 250 `
        -c:v libx264 -preset ultrafast -pix_fmt yuv420p -g 10 -c:a aac -b:a 64k $OutputPath
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $OutputPath)) {
        throw "ffmpeg failed to create the long ASR E2E video"
    }
}

function Wait-VideoTask {
    param([Parameter(Mandatory = $true)][long]$TaskId)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($script:TimeoutSeconds)
    do {
        try {
            $task = Invoke-JsonApi -Method Get -Path "/api/tasks/$TaskId"
            $status = [string]$task.taskStatus
            if ($status -eq "SUCCESS") { return $task }
            if ($status -in @("FAILED", "CANCELLED")) {
                throw "Video analysis reached terminal status $status for taskId=$TaskId"
            }
        } catch {
            if ($_.Exception.Message -like "Video analysis reached terminal status*") { throw }
            if ([DateTimeOffset]::UtcNow -ge $deadline) { throw }
        }
        Start-Sleep -Seconds $script:PollSeconds
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Video analysis timed out for taskId=$TaskId"
}

function Wait-KnowledgeBaseReady {
    param([Parameter(Mandatory = $true)][long]$KnowledgeBaseId)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($script:TimeoutSeconds)
    do {
        $knowledgeBase = Invoke-JsonApi -Method Get -Path "/api/knowledge-bases/$KnowledgeBaseId"
        $status = [string]$knowledgeBase.status
        if ($status -eq "READY") { return $knowledgeBase }
        if ($status -eq "FAILED") { throw "Knowledge base processing failed: id=$KnowledgeBaseId" }
        Start-Sleep -Seconds $script:PollSeconds
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Knowledge base processing timed out: id=$KnowledgeBaseId"
}

function Wait-DeletionTask {
    param([Parameter(Mandatory = $true)][long]$TaskId)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($script:TimeoutSeconds)
    do {
        $task = Invoke-JsonApi -Method Get -Path "/api/tasks/$TaskId"
        $status = [string]$task.status
        if ($status -eq "SUCCESS") { return }
        if ($status -in @("FAILED", "DEAD", "CANCELLED")) {
            throw "Deletion reached terminal status $status for taskId=$TaskId"
        }
        Start-Sleep -Seconds $script:PollSeconds
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Deletion timed out for taskId=$TaskId"
}

function Invoke-MySqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $username = Get-ConfiguredEnvironmentValue -Name "MYSQL_USERNAME"
    $password = Get-ConfiguredEnvironmentValue -Name "MYSQL_PASSWORD"
    $database = Get-ConfiguredEnvironmentValue -Name "MYSQL_DATABASE"
    if ([string]::IsNullOrWhiteSpace($username)) { $username = "root" }
    if ([string]::IsNullOrWhiteSpace($password)) { $password = "root" }
    if ([string]::IsNullOrWhiteSpace($database)) { $database = "videomind" }
    $output = & docker compose --project-directory $script:repoRoot exec -T `
        -e "MYSQL_PWD=$password" mysql mysql -N -B -u $username $database -e $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Local MySQL verification query failed" }
    $value = @($output | ForEach-Object { [string]$_ } | Where-Object { $_ -match '^\d+$' } | Select-Object -Last 1)
    if ($value.Count -ne 1) { throw "Local MySQL verification did not return one numeric value" }
    return [long]$value[0]
}

function Invoke-MySqlUtf8Text {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $username = Get-ConfiguredEnvironmentValue -Name "MYSQL_USERNAME"
    $password = Get-ConfiguredEnvironmentValue -Name "MYSQL_PASSWORD"
    $database = Get-ConfiguredEnvironmentValue -Name "MYSQL_DATABASE"
    if ([string]::IsNullOrWhiteSpace($username)) { $username = "root" }
    if ([string]::IsNullOrWhiteSpace($password)) { $password = "root" }
    if ([string]::IsNullOrWhiteSpace($database)) { $database = "videomind" }
    $output = & docker compose --project-directory $script:repoRoot exec -T `
        -e "MYSQL_PWD=$password" mysql mysql -N -B -u $username $database -e $Sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Local MySQL UTF-8 verification query failed" }
    $encoded = @($output | ForEach-Object { [string]$_ } | Where-Object { $_ -match '^__VM__[A-Za-z0-9+/=]+$' } |
        Select-Object -Last 1)
    if ($encoded.Count -ne 1) { throw "Local MySQL UTF-8 verification did not return one value" }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded[0].Substring(6)))
}

function Get-ElasticsearchCount {
    param([Parameter(Mandatory = $true)][long]$KnowledgeBaseId)
    $alias = Get-ConfiguredEnvironmentValue -Name "ELASTICSEARCH_INDEX_ALIAS"
    if ([string]::IsNullOrWhiteSpace($alias)) { $alias = "videomind-chunks" }
    $body = @{ query = @{ term = @{ knowledgeBaseId = $KnowledgeBaseId } } } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$script:localElasticsearchUrl/$alias/_count" `
        -ContentType "application/json" -Body $body -TimeoutSec 30
    return [long]$response.count
}

function Get-ElasticsearchContent {
    param([Parameter(Mandatory = $true)][long]$KnowledgeBaseId)
    $alias = Get-ConfiguredEnvironmentValue -Name "ELASTICSEARCH_INDEX_ALIAS"
    if ([string]::IsNullOrWhiteSpace($alias)) { $alias = "videomind-chunks" }
    $body = @{
        size = 30
        query = @{ term = @{ knowledgeBaseId = $KnowledgeBaseId } }
        _source = @("content", "startMs", "endMs")
    } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod -Method Post -Uri "$script:localElasticsearchUrl/$alias/_search" `
        -ContentType "application/json" -Body $body -TimeoutSec 30
    return (@($response.hits.hits | ForEach-Object { [string]$_."_source".content }) -join "`n")
}

function Invoke-ComposeService {
    param(
        [Parameter(Mandatory = $true)][ValidateSet("stop", "start", "restart")][string]$Action,
        [Parameter(Mandatory = $true)][string]$Service
    )
    & docker compose --project-directory $script:repoRoot $Action $Service
    if ($LASTEXITCODE -ne 0) { throw "docker compose $Action failed for $Service" }
}

function Wait-CacheRedisReady {
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(90)
    do {
        $result = & docker compose --project-directory $script:repoRoot exec -T redis-cache redis-cli ping 2>$null
        if ($LASTEXITCODE -eq 0 -and (($result | Select-Object -Last 1) -eq "PONG")) { return }
        Start-Sleep -Seconds 2
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "Cache Redis did not recover within 90 seconds"
}

function Test-HotSnapshotExists {
    param([Parameter(Mandatory = $true)][long]$ConversationId)
    $result = & docker compose --project-directory $script:repoRoot exec -T redis-cache `
        redis-cli EXISTS "hot:conversation:$ConversationId" 2>$null
    return $LASTEXITCODE -eq 0 -and (($result | Select-Object -Last 1) -eq "1")
}

function Restart-BrokerDuringSecondAsrChunk {
    param([Parameter(Mandatory = $true)][long]$VideoId)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(180)
    do {
        $submitted = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_chunk WHERE video_id=$VideoId AND chunk_index=1 AND state='SUBMITTED'"
        if ($submitted -gt 0) {
            Write-Host "[..] Restarting RocketMQ while ASR chunk 1 has a persisted provider task"
            Invoke-ComposeService -Action restart -Service "rocketmq-broker"
            return
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw "ASR chunk 1 never reached SUBMITTED before the broker restart deadline"
}

function Remove-E2eRemoteData {
    if ($null -ne $script:createdKnowledgeBaseId) {
        $deletion = Invoke-JsonApi -Method Delete -Path "/api/knowledge-bases/$script:createdKnowledgeBaseId"
        Wait-DeletionTask -TaskId ([long]$deletion.taskId)
        $script:createdKnowledgeBaseId = $null
    }
    if ($null -ne $script:createdVideoId) {
        $deletion = Invoke-JsonApi -Method Delete -Path "/api/videos/$script:createdVideoId"
        Wait-DeletionTask -TaskId ([long]$deletion.taskId)
        $script:createdVideoId = $null
    }
}

$localBaseUrl = Get-LoopbackUrl -Value $BaseUrl
$localElasticsearchUrl = Get-LoopbackUrl -Value $ElasticsearchUrl

if ($ValidateOnly) {
    Assert-Condition -Condition ($TimeoutSeconds -ge 60) -Message "TimeoutSeconds is too small"
    Assert-Condition -Condition ((Split-Path -Leaf $PSScriptRoot) -eq "ops") -Message "Script must remain under ops"
    Write-Host "VideoMind local E2E script validation succeeded. No credentials were read and no services were changed."
    exit 0
}

if ($FixtureOnly) {
    $fixtureFfmpeg = Resolve-Executable -EnvironmentName "FFMPEG_BINARY_PATH" `
        -Candidates @((Join-Path $repoRoot "runtime\tools\ffmpeg-8.1.2-essentials_build\bin\ffmpeg.exe"),
            "E:\FFmpeg\bin\ffmpeg.exe") -CommandName "ffmpeg.exe"
    $fixtureFfprobe = Resolve-Executable -EnvironmentName "FFPROBE_BINARY_PATH" `
        -Candidates @((Join-Path $repoRoot "runtime\tools\ffmpeg-8.1.2-essentials_build\bin\ffprobe.exe"),
            "E:\FFmpeg\bin\ffprobe.exe") -CommandName "ffprobe.exe"
    New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
    try {
        New-MinimalPdf -Path $pdfPath -Token $documentToken
        if ($LongAsr) {
            New-LongAsrTimelineVideo -Ffmpeg $fixtureFfmpeg -OutputPath $videoPath `
                -WavePath $speechPath -Token $videoToken
            $duration = Get-MediaStreamDuration -Ffprobe $fixtureFfprobe -Path $videoPath -Stream "v:0"
            $audioDuration = Get-MediaStreamDuration -Ffprobe $fixtureFfprobe -Path $videoPath -Stream "a:0"
            Assert-Condition -Condition ($duration -ge 249.9 -and $duration -le 250.1) `
                -Message "Generated long ASR video must be 250 seconds"
            Assert-Condition -Condition ($audioDuration -ge 249.0) `
                -Message "Generated long ASR fixture must contain a full-length audio stream"
        } else {
            New-TimelineVideo -Ffmpeg $fixtureFfmpeg -OutputPath $videoPath -WavePath $speechPath -Token $videoToken
            $duration = Get-MediaStreamDuration -Ffprobe $fixtureFfprobe -Path $videoPath -Stream "v:0"
            Assert-Condition -Condition ($duration -ge 30 -and $duration -le 60) `
                -Message "Generated video duration must be between 30 and 60 seconds"
        }
        Assert-Condition -Condition ((Get-Item -LiteralPath $videoPath).Length -gt 10000) `
            -Message "Generated video is unexpectedly small"
        $pdfBytes = [IO.File]::ReadAllBytes($pdfPath)
        Assert-Condition -Condition ($pdfBytes.Length -gt 256) -Message "Generated PDF is unexpectedly small"
        Assert-Condition -Condition ([Text.Encoding]::ASCII.GetString($pdfBytes, 0, 5) -eq "%PDF-") `
            -Message "Generated PDF header is invalid"
        Write-Host "VideoMind E2E fixtures passed local validation: duration=$([math]::Round($duration, 1))s"
        if ($KeepArtifacts) { Write-Host "Fixtures: $runDirectory" }
    } finally {
        if (-not $KeepArtifacts -and (Test-Path -LiteralPath $runDirectory)) {
            Remove-Item -LiteralPath $runDirectory -Recurse -Force
        }
    }
    exit 0
}

foreach ($name in @("TENCENT_CLOUD_SECRET_ID", "TENCENT_CLOUD_SECRET_KEY", "SILICONFLOW_API_KEY")) {
    $value = Get-ConfiguredEnvironmentValue -Name $name
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$name is not configured in the process or user environment"
    }
}
Write-Host "[OK] Required external API environment variables are present; values were not displayed."

$physicalMemoryBytes = $null
try {
    $physicalMemoryBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
} catch {
    try {
        Add-Type -AssemblyName Microsoft.VisualBasic
        $physicalMemoryBytes = ([Microsoft.VisualBasic.Devices.ComputerInfo]::new()).TotalPhysicalMemory
    } catch {
        $physicalMemoryBytes = $null
    }
}
if ($null -eq $physicalMemoryBytes) {
    throw "Unable to verify physical memory. Real E2E refuses to continue."
}
$physicalMemoryGiB = [math]::Round($physicalMemoryBytes / 1GB, 1)
if ($physicalMemoryBytes -lt 16000000000) {
    throw "Real MinerU E2E requires a 16 GB-class machine; detected $physicalMemoryGiB GiB"
}
Write-Host "[OK] Physical memory satisfies MinerU CPU requirement: $physicalMemoryGiB GiB (16 GB-class)"

$ffmpeg = Resolve-Executable -EnvironmentName "FFMPEG_BINARY_PATH" `
    -Candidates @((Join-Path $repoRoot "runtime\tools\ffmpeg-8.1.2-essentials_build\bin\ffmpeg.exe"),
        "E:\FFmpeg\bin\ffmpeg.exe") -CommandName "ffmpeg.exe"
$ffprobe = Resolve-Executable -EnvironmentName "FFPROBE_BINARY_PATH" `
    -Candidates @((Join-Path $repoRoot "runtime\tools\ffmpeg-8.1.2-essentials_build\bin\ffprobe.exe"),
        "E:\FFmpeg\bin\ffprobe.exe") -CommandName "ffprobe.exe"
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw "docker.exe was not found in PATH" }

$health = Invoke-RestMethod -Method Get -Uri "$localBaseUrl/actuator/health" -TimeoutSec 5
Assert-Condition -Condition ([string]$health.status -eq "UP") -Message "VideoMind backend is not healthy"

if ($PreflightOnly) {
    $requiredServices = @("mysql", "minio", "rocketmq-namesrv", "rocketmq-broker", "redis-stack",
        "redis-cache", "elasticsearch", "mineru", "paddleocr")
    $runningServices = @(& docker compose --project-directory $repoRoot ps --status running --services 2>$null)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect the local Docker Compose stack" }
    $missingServices = @($requiredServices | Where-Object { $_ -notin $runningServices })
    Assert-Condition -Condition ($missingServices.Count -eq 0) `
        -Message "Local Compose services are not all running: $($missingServices -join ', ')"
    Write-Host "[OK] Real E2E preflight passed; no external API call was made and no credential value was displayed."
    exit 0
}

New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
New-MinimalPdf -Path $pdfPath -Token $documentToken
if ($LongAsr) {
    New-LongAsrTimelineVideo -Ffmpeg $ffmpeg -OutputPath $videoPath -WavePath $speechPath -Token $videoToken
} else {
    New-TimelineVideo -Ffmpeg $ffmpeg -OutputPath $videoPath -WavePath $speechPath -Token $videoToken
}

$username = "e2e_$runId"
$password = "VmE2e-$runId!"

try {
    $null = Invoke-JsonApi -Method Post -Path "/api/auth/register" `
        -Body @{ username = $username; password = $password } -Anonymous
    $auth = Invoke-JsonApi -Method Post -Path "/api/auth/login" `
        -Body @{ username = $username; password = $password } -Anonymous
    $authHeaders = @{ Authorization = "Bearer $([string]$auth.accessToken)" }

    $fixtureDescription = if ($LongAsr) { "250-second chunked-ASR fixture" } else { "40-second multimodal fixture" }
    Write-Host "[..] Uploading the $fixtureDescription"
    $video = Invoke-FileApi -Path "/api/videos/upload" -FilePath $videoPath -MediaType "video/mp4"
    $createdVideoId = [long]$video.videoId
    $analysis = Invoke-JsonApi -Method Post -Path "/api/tasks/analyze" -Body @{ videoId = $createdVideoId }
    if ($ExerciseBrokerRestart) {
        if ($LongAsr) {
            Restart-BrokerDuringSecondAsrChunk -VideoId $createdVideoId
        } else {
            Write-Host "[..] Restarting the local RocketMQ broker during processing"
            Invoke-ComposeService -Action restart -Service "rocketmq-broker"
        }
    }
    $null = Wait-VideoTask -TaskId ([long]$analysis.taskId)

    $transcription = Invoke-JsonApi -Method Get -Path "/api/videos/$createdVideoId/transcription"
    Assert-Condition -Condition (-not [string]::IsNullOrWhiteSpace([string]$transcription.transcriptionText)) `
        -Message "Tencent ASR did not return transcription text"
    $asrCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_segment WHERE video_id=$createdVideoId AND end_ms>=start_ms AND CHAR_LENGTH(TRIM(text))>0"
    $ocrCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_ocr_observation WHERE video_id=$createdVideoId AND end_ms>=start_ms AND CHAR_LENGTH(TRIM(text))>0"
    $timelineCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_timeline WHERE video_id=$createdVideoId AND status='READY'"
    Assert-Condition -Condition ($asrCount -gt 0) -Message "No timestamped ASR segments were persisted"
    Assert-Condition -Condition ($ocrCount -gt 0) -Message "No keyframe OCR observations were persisted"
    Assert-Condition -Condition ($timelineCount -eq 1) -Message "No READY timeline was persisted"
    $asrChunkCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_chunk WHERE video_id=$createdVideoId"
    $succeededAsrChunkCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_chunk WHERE video_id=$createdVideoId AND state='SUCCEEDED'"
    Assert-Condition -Condition ($asrChunkCount -ge 1 -and $asrChunkCount -eq $succeededAsrChunkCount) `
        -Message "ASR chunk state is incomplete"
    if ($LongAsr) {
        Assert-Condition -Condition ($asrChunkCount -ge 3) -Message "Long ASR fixture did not create at least three chunks"
        $boundaryOneCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_segment WHERE video_id=$createdVideoId AND start_ms BETWEEN 115000 AND 130000"
        $boundaryTwoCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_segment WHERE video_id=$createdVideoId AND start_ms BETWEEN 235000 AND 250000"
        $duplicateAsrCount = Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM (SELECT start_ms,end_ms,text,COUNT(*) c FROM video_asr_segment WHERE video_id=$createdVideoId GROUP BY start_ms,end_ms,text HAVING c>1) duplicate_segments"
        Assert-Condition -Condition ($boundaryOneCount -gt 0) -Message "No absolute ASR timestamp was persisted near 120 seconds"
        Assert-Condition -Condition ($boundaryTwoCount -gt 0) -Message "No absolute ASR timestamp was persisted near 240 seconds"
        Assert-Condition -Condition ($duplicateAsrCount -eq 0) -Message "Overlapped ASR chunks created duplicate timestamped segments"
    }

    $knowledgeBases = @(Invoke-JsonApi -Method Get -Path "/api/knowledge-bases")
    $videoKnowledgeBase = $knowledgeBases | Where-Object {
        [string]$_.type -eq "VIDEO" -and [long]$_.videoId -eq $createdVideoId
    } | Select-Object -First 1
    Assert-Condition -Condition ($null -ne $videoKnowledgeBase) -Message "Video system knowledge base was not created"
    $videoKnowledgeBaseId = [long]$videoKnowledgeBase.id

    Write-Host "[..] Uploading the short PDF through local MinerU"
    $documentKnowledgeBase = Invoke-JsonApi -Method Post -Path "/api/knowledge-bases" `
        -Body @{ name = "E2E document $runId" }
    $createdKnowledgeBaseId = [long]$documentKnowledgeBase.id
    $null = Invoke-FileApi -Path "/api/knowledge-bases/$createdKnowledgeBaseId/documents" `
        -FilePath $pdfPath -MediaType "application/pdf"
    $null = Wait-KnowledgeBaseReady -KnowledgeBaseId $createdKnowledgeBaseId

    Assert-Condition -Condition ((Get-ElasticsearchCount -KnowledgeBaseId $videoKnowledgeBaseId) -gt 0) `
        -Message "Elasticsearch contains no video timeline chunks"
    Assert-Condition -Condition ((Get-ElasticsearchCount -KnowledgeBaseId $createdKnowledgeBaseId) -gt 0) `
        -Message "Elasticsearch contains no PDF chunks"
    $timelineContent = Get-ElasticsearchContent -KnowledgeBaseId $videoKnowledgeBaseId
    if ($LongAsr) {
        $speechProbe = Invoke-MySqlUtf8Text -Sql "SELECT CONCAT('__VM__',TO_BASE64(text)) FROM video_asr_segment WHERE video_id=$createdVideoId AND CHAR_LENGTH(TRIM(text))>0 ORDER BY start_ms LIMIT 1"
        $visualProbe = Invoke-MySqlUtf8Text -Sql "SELECT CONCAT('__VM__',TO_BASE64(text)) FROM video_ocr_observation WHERE video_id=$createdVideoId AND CHAR_LENGTH(TRIM(text))>0 ORDER BY start_ms LIMIT 1"
        Assert-Condition -Condition (-not [string]::IsNullOrWhiteSpace($speechProbe) -and $timelineContent.Contains($speechProbe)) `
            -Message "Indexed long timeline has no persisted ASR evidence"
        Assert-Condition -Condition (-not [string]::IsNullOrWhiteSpace($visualProbe) -and $timelineContent.Contains($visualProbe)) `
            -Message "Indexed long timeline has no persisted OCR evidence"
    } else {
        Assert-Condition -Condition ($timelineContent.Contains("CAS LEASE")) `
            -Message "Indexed timeline has no speech evidence"
        Assert-Condition -Condition ($timelineContent.Contains("CHECKPOINT RESUME")) `
            -Message "Indexed timeline has no visual evidence"
    }

    $repeat = Invoke-JsonApi -Method Post -Path "/api/tasks/analyze" -Body @{ videoId = $createdVideoId }
    Assert-Condition -Condition ([bool]$repeat.reused) -Message "Repeated analysis did not reuse the completed task"
    Assert-Condition -Condition ([long]$repeat.taskId -eq [long]$analysis.taskId) `
        -Message "Repeated analysis created a duplicate business task"
    Assert-Condition -Condition ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_timeline WHERE video_id=$createdVideoId") -eq 1) `
        -Message "Repeated analysis created duplicate timeline results"
    Assert-Condition -Condition ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_chunk WHERE video_id=$createdVideoId") -eq $asrChunkCount) `
        -Message "Repeated analysis created duplicate ASR chunks"

    $session = Invoke-JsonApi -Method Post -Path "/api/chat/session" `
        -Body @{ videoId = $createdVideoId; knowledgeBaseIds = @($createdKnowledgeBaseId) }
    $conversationId = [long]$session.sessionId
    $question = "Use both sources: what does $videoToken say about CAS lease and checkpoint resume, " +
        "and what does $documentToken say about duplicate delivery compensation?"

    if (-not $SkipCacheFailover) {
        Invoke-ComposeService -Action stop -Service "redis-cache"
        $cacheStopped = $true
    }
    $answer = Invoke-JsonApi -Method Post -Path "/api/chat/message" -Body @{
        sessionId = $conversationId
        videoId = $createdVideoId
        question = $question
        answerScope = "KNOWLEDGE_ONLY"
        deepThinkingEnabled = $false
        webSearchEnabled = $false
    }
    $references = @($answer.references)
    $videoReferences = @($references | Where-Object { [string]$_.sourceType -eq "VIDEO_TIMELINE" })
    $documentReferences = @($references | Where-Object { [string]$_.sourceType -eq "USER_DOCUMENT" })
    Assert-Condition -Condition ($videoReferences.Count -gt 0) -Message "Joint answer has no video evidence"
    Assert-Condition -Condition ($documentReferences.Count -gt 0) -Message "Joint answer has no document evidence"
    Assert-Condition -Condition (@($videoReferences | Where-Object {
        $null -ne $_.startSeconds -and $null -ne $_.endSeconds
    }).Count -gt 0) -Message "Video evidence has no time range"

    if ($cacheStopped) {
        Invoke-ComposeService -Action start -Service "redis-cache"
        Wait-CacheRedisReady
        $cacheStopped = $false
        $null = Invoke-JsonApi -Method Post -Path "/api/chat/message" -Body @{
            sessionId = $conversationId
            videoId = $createdVideoId
            question = "Summarize the two cited sources in one sentence."
            answerScope = "KNOWLEDGE_ONLY"
            deepThinkingEnabled = $false
            webSearchEnabled = $false
        }
        Assert-Condition -Condition (Test-HotSnapshotExists -ConversationId $conversationId) `
            -Message "Cache Redis did not receive the rebuilt hot conversation snapshot"
    }

    if (-not $KeepData) {
        $deletedKnowledgeBaseId = $createdKnowledgeBaseId
        $deletedVideoId = $createdVideoId
        Remove-E2eRemoteData
        Assert-Condition -Condition ((Get-ElasticsearchCount -KnowledgeBaseId $deletedKnowledgeBaseId) -eq 0) `
            -Message "Document knowledge chunks remain after physical deletion"
        Assert-Condition -Condition ((Get-ElasticsearchCount -KnowledgeBaseId $videoKnowledgeBaseId) -eq 0) `
            -Message "Video knowledge chunks remain after physical deletion"
        Assert-Condition -Condition ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_segment WHERE video_id=$deletedVideoId") -eq 0) `
            -Message "ASR segments remain after video deletion"
        Assert-Condition -Condition ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_asr_chunk WHERE video_id=$deletedVideoId") -eq 0) `
            -Message "ASR chunk state remains after video deletion"
        Assert-Condition -Condition ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_ocr_observation WHERE video_id=$deletedVideoId") -eq 0) `
            -Message "OCR observations remain after video deletion"
        Assert-Condition -Condition ((Invoke-MySqlScalar -Sql "SELECT COUNT(*) FROM video_timeline WHERE video_id=$deletedVideoId") -eq 0) `
            -Message "Timeline rows remain after video deletion"
    }

    Write-Host "[PASS] Local multimodal knowledge E2E completed." -ForegroundColor Green
    Write-Host "       ASR chunks=$asrChunkCount, segments=$asrCount, OCR observations=$ocrCount, timeline rows=$timelineCount"
    Write-Host "       Video references=$($videoReferences.Count), document references=$($documentReferences.Count)"
} finally {
    if ($cacheStopped) {
        try {
            Invoke-ComposeService -Action start -Service "redis-cache"
            Wait-CacheRedisReady
        } catch {
            Write-Warning "Cache Redis recovery failed; run 'docker compose start redis-cache' manually."
        }
    }
    if (-not $KeepData -and ($null -ne $createdKnowledgeBaseId -or $null -ne $createdVideoId) -and $null -ne $authHeaders) {
        try {
            Remove-E2eRemoteData
        } catch {
            Write-Warning "Best-effort remote fixture cleanup failed: $($_.Exception.Message)"
        }
    }
    if (-not $KeepArtifacts -and (Test-Path -LiteralPath $runDirectory)) {
        $resolvedRuntime = [IO.Path]::GetFullPath($runtimeRoot).TrimEnd('\') + '\'
        $resolvedRun = [IO.Path]::GetFullPath($runDirectory)
        if (-not $resolvedRun.StartsWith($resolvedRuntime, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove an artifact directory outside runtime\e2e"
        }
        Remove-Item -LiteralPath $resolvedRun -Recurse -Force
    }
}
