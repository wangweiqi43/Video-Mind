[CmdletBinding()]
param(
    [switch]$Execute,
    [ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9_.-]*$')]
    [string]$RedisContainer = 'videomind-redis-stack'
)

$ErrorActionPreference = 'Stop'
$LegacyIndex = 'idx:videomind_knowledge'
$LegacyPatterns = @('knowledge:chunk:*', 'knowledge:task:*')

function Invoke-RedisCommand {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $output = @(& docker exec $RedisContainer redis-cli --raw @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "redis-cli failed for '$($Arguments[0])': $($output -join ' ')"
    }
    return @($output | ForEach-Object { "$_" })
}

function Get-MatchingKeys {
    param([Parameter(Mandatory)][string]$Pattern)

    $keys = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $cursor = '0'
    do {
        $response = @(Invoke-RedisCommand -Arguments @('SCAN', $cursor, 'MATCH', $Pattern, 'COUNT', '500'))
        if ($response.Count -lt 1) {
            throw "Redis SCAN returned no cursor for fixed pattern '$Pattern'"
        }
        $cursor = $response[0]
        foreach ($key in $response | Select-Object -Skip 1) {
            if ($key.StartsWith($Pattern.Substring(0, $Pattern.Length - 1), [System.StringComparison]::Ordinal)) {
                [void]$keys.Add($key)
            }
        }
    } while ($cursor -ne '0')
    return @($keys | Sort-Object)
}

$indexes = @(Invoke-RedisCommand -Arguments @('FT._LIST'))
$indexExists = $indexes -contains $LegacyIndex
$chunkKeys = @(Get-MatchingKeys -Pattern $LegacyPatterns[0])
$taskKeys = @(Get-MatchingKeys -Pattern $LegacyPatterns[1])
$allKeys = @($chunkKeys + $taskKeys | Sort-Object -Unique)

Write-Output "LEGACY_REDISEARCH_INDEX=$LegacyIndex"
Write-Output "LEGACY_REDISEARCH_INDEX_EXISTS=$($indexExists.ToString().ToLowerInvariant())"
Write-Output "LEGACY_CHUNK_KEYS=$($chunkKeys.Count)"
Write-Output "LEGACY_TASK_KEYS=$($taskKeys.Count)"
Write-Output "LEGACY_TOTAL_KEYS=$($allKeys.Count)"

if (-not $Execute) {
    Write-Output 'MODE=PREVIEW'
    Write-Output 'No data was deleted. Re-run with -Execute after reviewing the exact counts.'
    exit 0
}

$droppedIndex = $false
if ($indexExists) {
    [void](Invoke-RedisCommand -Arguments @('FT.DROPINDEX', $LegacyIndex))
    $droppedIndex = $true
}

$deleted = 0
for ($offset = 0; $offset -lt $allKeys.Count; $offset += 100) {
    $last = [Math]::Min($offset + 99, $allKeys.Count - 1)
    $batch = @($allKeys[$offset..$last])
    $response = @(Invoke-RedisCommand -Arguments (@('UNLINK') + $batch))
    $deleted += [int]$response[-1]
}

Write-Output 'MODE=EXECUTE'
Write-Output "DROPPED_INDEX=$($droppedIndex.ToString().ToLowerInvariant())"
Write-Output "DELETED_KEYS=$deleted"
