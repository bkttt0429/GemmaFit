param(
    [int]$Count = 100,
    [string]$Model = "official",
    [string]$PromptFile = "litert_ab_a_no_narrative_v2.json",
    [string]$OutDir = "",
    [switch]$ForceStopBeforeRun,
    [switch]$StopOnFailure,
    [int]$MaxEndpointRetries = 2,
    [switch]$PauseOnThermalSevere,
    [int]$ThermalPauseSeconds = 60,
    [int]$MaxThermalPauseCycles = 0,
    [switch]$Constrained,
    [switch]$Stream
)

$ErrorActionPreference = "Stop"

if ($Count -lt 1) {
    throw "Count must be >= 1"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $safePrompt = [IO.Path]::GetFileNameWithoutExtension($PromptFile) -replace '[^A-Za-z0-9_-]', '_'
    $OutDir = Join-Path "docs\benchmark" "litert_prompt_smoke_${stamp}_${Model}_${safePrompt}"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-GemmaFitDebugEndpoint {
    param([string]$Uri)

    # Keep the URI quoted inside the remote shell. Otherwise `&model=...` is
    # treated as a shell control operator and later query params are dropped.
    $remoteCommand = "content read --uri '$Uri'"
    $lines = adb shell $remoteCommand
    return ($lines -join "`n")
}

function Convert-FirstJsonObject {
    param([string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    $trimmed = $Text.Trim()
    try {
        return $trimmed | ConvertFrom-Json
    } catch {
        $start = $trimmed.IndexOf('{')
        $end = $trimmed.LastIndexOf('}')
        if ($start -lt 0 -or $end -le $start) {
            return $null
        }
        try {
            return $trimmed.Substring($start, $end - $start + 1) | ConvertFrom-Json
        } catch {
            return $null
        }
    }
}

function Convert-DebugEndpointPayload {
    param([string]$Raw)

    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return $null
    }
    try {
        return $Raw.Trim() | ConvertFrom-Json
    } catch {
        $finalPayload = $null
        $lines = $Raw -split "`r?`n"
        foreach ($line in $lines) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            try {
                $event = $line.Trim() | ConvertFrom-Json
                if ($event.event -eq "done" -and $null -ne $event.payload) {
                    $finalPayload = $event.payload
                } elseif ($event.event -eq "error" -and $null -ne $event.payload -and $null -eq $finalPayload) {
                    $finalPayload = $event.payload
                }
            } catch {
                continue
            }
        }
        return $finalPayload
    }
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile
    )

    if ($Values.Count -eq 0) {
        return $null
    }
    $sorted = @($Values | Sort-Object)
    $rank = [Math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    $rank = [Math]::Max(0, [Math]::Min($rank, $sorted.Count - 1))
    return [Math]::Round($sorted[$rank], 1)
}

function New-SmokeRecord {
    param(
        [int]$Index,
        [long]$WallMs,
        [string]$Raw,
        [string]$ResponseFile,
        [int]$RetryCount,
        [string[]]$RetryReasons
    )

    $payload = $null
    $payloadParseError = ""
    $payload = Convert-DebugEndpointPayload $Raw
    if ($null -eq $payload) {
        $payloadParseError = "endpoint payload was not JSON or JSONL"
    }

    $rawResponse = if ($null -ne $payload -and $null -ne $payload.raw_response) {
        [string]$payload.raw_response
    } else {
        ""
    }
    $modelJson = Convert-FirstJsonObject $rawResponse
    $functionName = ""
    if ($null -ne $modelJson) {
        if ($null -ne $modelJson.function) {
            $functionName = [string]$modelJson.function
        } elseif ($null -ne $modelJson.name) {
            $functionName = [string]$modelJson.name
        }
    }

    $success = $false
    $generationSuccess = $false
    $backend = ""
    $errorMessage = ""
    $elapsedMs = $null
    $generateMs = $null
    $engineInitMs = $null
    $sessionCreateMs = $null
    $constrainedDecoding = $false
    $constrainedDecodingMs = $null
    $constrainedToolCallObserved = $false
    $firstTokenMs = $null
    $streamTokenCount = $null
    $reusedEngine = $false
    $promptChars = $null
    if ($null -ne $payload) {
        $success = [bool]$payload.success
        $generationSuccess = [bool]$payload.generation_success
        $backend = [string]$payload.backend
        $errorMessage = [string]$payload.error
        $elapsedMs = $payload.elapsed_ms
        $generateMs = $payload.generate_content_ms
        $engineInitMs = $payload.engine_initialize_ms
        $sessionCreateMs = $payload.session_create_ms
        $constrainedDecoding = [bool]$payload.constrained_decoding
        $constrainedDecodingMs = $payload.constrained_decoding_ms
        $constrainedToolCallObserved = [bool]$payload.constrained_tool_call_observed
        $firstTokenMs = $payload.first_token_ms
        $streamTokenCount = $payload.stream_token_count
        $reusedEngine = [bool]$payload.reused_engine
        $promptChars = $payload.prompt_chars
    }

    return [PSCustomObject]@{
        index = $Index
        wall_ms = $WallMs
        endpoint_success = $success
        generation_success = $generationSuccess
        payload_parse_success = ($null -ne $payload)
        model_json_parse_success = ($null -ne $modelJson)
        function_name = $functionName
        backend = $backend
        elapsed_ms = $elapsedMs
        generate_content_ms = $generateMs
        engine_initialize_ms = $engineInitMs
        session_create_ms = $sessionCreateMs
        constrained_decoding = $constrainedDecoding
        constrained_decoding_ms = $constrainedDecodingMs
        constrained_tool_call_observed = $constrainedToolCallObserved
        first_token_ms = $firstTokenMs
        stream_token_count = $streamTokenCount
        reused_engine = $reusedEngine
        prompt_chars = $promptChars
        raw_response_chars = $rawResponse.Length
        error = $errorMessage
        payload_parse_error = $payloadParseError
        retry_count = $RetryCount
        retry_reasons = $RetryReasons
        response_file = $ResponseFile
    }
}

function Test-SmokeRecordOk {
    param([object]$Record)
    return $Record.endpoint_success -and $Record.generation_success -and $Record.model_json_parse_success
}

function Get-RetryReason {
    param([object]$Record)
    if (-not $Record.payload_parse_success) { return "empty_or_invalid_endpoint_payload" }
    if (-not $Record.endpoint_success) { return "endpoint_success_false:$($Record.error)" }
    if (-not $Record.generation_success) { return "generation_success_false:$($Record.error)" }
    if (-not $Record.model_json_parse_success) { return "model_json_parse_failed" }
    return "unknown"
}

function Get-DeviceThermalStatus {
    $raw = adb shell dumpsys thermalservice
    $line = $raw | Select-String -Pattern "Thermal Status:" | Select-Object -First 1
    if ($null -eq $line) {
        return $null
    }
    $text = [string]$line
    $match = [regex]::Match($text, "Thermal Status:\s*(\d+)")
    if (-not $match.Success) {
        return $null
    }
    return [int]$match.Groups[1].Value
}

if ($ForceStopBeforeRun) {
    adb shell am force-stop com.gemmafit | Out-Null
    Start-Sleep -Seconds 2
}

$readinessUri = "content://com.gemmafit.debug/model_readiness?model=$Model"
$readinessRaw = Invoke-GemmaFitDebugEndpoint $readinessUri
$readinessRaw | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "model_readiness.json")

$uri = "content://com.gemmafit.debug/litert_prompt_infer?file=$PromptFile&model=$Model"
if ($Constrained) {
    $uri = "$uri&constrained=true"
}
if ($Stream) {
    $uri = "$uri&stream=true"
}
$records = New-Object System.Collections.Generic.List[object]
$startedAt = Get-Date
$thermalAbortReason = ""

for ($i = 1; $i -le $Count; $i++) {
    if ($PauseOnThermalSevere) {
        $thermalPauseCycles = 0
        while ($true) {
            $thermalStatus = Get-DeviceThermalStatus
            if ($null -eq $thermalStatus -or $thermalStatus -lt 3) {
                break
            }
            $thermalPauseCycles += 1
            if ($MaxThermalPauseCycles -gt 0 -and $thermalPauseCycles -gt $MaxThermalPauseCycles) {
                $thermalAbortReason = "thermal_status_$thermalStatus_after_$MaxThermalPauseCycles`_pauses"
                Write-Host ("[{0}/{1}] aborting: {2}" -f $i, $Count, $thermalAbortReason)
                break
            }
            Write-Host ("[{0}/{1}] thermal_status={2}; pausing {3}s before next prompt" -f `
                $i, $Count, $thermalStatus, $ThermalPauseSeconds)
            Start-Sleep -Seconds $ThermalPauseSeconds
        }
        if (-not [string]::IsNullOrWhiteSpace($thermalAbortReason)) {
            break
        }
    }

    $runName = "run_{0:D3}.json" -f $i
    $retryReasons = New-Object System.Collections.Generic.List[string]
    $totalWallMs = 0L
    $record = $null
    $raw = ""

    for ($attempt = 1; $attempt -le ($MaxEndpointRetries + 1); $attempt++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $raw = Invoke-GemmaFitDebugEndpoint $uri
        $sw.Stop()
        $totalWallMs += $sw.ElapsedMilliseconds

        $record = New-SmokeRecord `
            -Index $i `
            -WallMs $totalWallMs `
            -Raw $raw `
            -ResponseFile $runName `
            -RetryCount ($attempt - 1) `
            -RetryReasons @($retryReasons.ToArray())

        if (Test-SmokeRecordOk $record) {
            break
        }

        $reason = Get-RetryReason $record
        if ($attempt -gt $MaxEndpointRetries) {
            break
        }
        $retryReasons.Add($reason)
        $attemptName = "attempt_{0:D3}_{1:D2}.json" -f $i, $attempt
        $raw | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir $attemptName)
        Write-Host ("[{0}/{1}] retry={2}/{3} reason={4}" -f `
            $i, $Count, $attempt, $MaxEndpointRetries, $reason)
        Start-Sleep -Seconds ([Math]::Min(15, 3 + ($attempt * 3)))
    }

    $raw | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir $runName)
    $records.Add($record)

    $ok = Test-SmokeRecordOk $record
    Write-Host ("[{0}/{1}] ok={2} retries={3} backend={4} wall={5}ms gen={6}ms function={7}" -f `
        $i, $Count, $ok, $record.retry_count, $record.backend, $record.wall_ms, $record.generate_content_ms, $record.function_name)

    if ($StopOnFailure -and -not $ok) {
        break
    }
}

$finishedAt = Get-Date
$recordArray = @($records.ToArray())
$validJsonCount = @($recordArray | Where-Object { $_.model_json_parse_success }).Count
$endpointSuccessCount = @($recordArray | Where-Object { $_.endpoint_success }).Count
$generationSuccessCount = @($recordArray | Where-Object { $_.generation_success }).Count
$backendValues = @($recordArray | Select-Object -ExpandProperty backend -Unique | Where-Object { $_ })
$wallValues = @($recordArray | ForEach-Object { [double]$_.wall_ms })
$generateValues = @($recordArray | Where-Object { $null -ne $_.generate_content_ms } | ForEach-Object { [double]$_.generate_content_ms })
$constrainedValues = @($recordArray | Where-Object { $null -ne $_.constrained_decoding_ms } | ForEach-Object { [double]$_.constrained_decoding_ms })
$initCount = @($recordArray | Where-Object { $null -ne $_.engine_initialize_ms -and [double]$_.engine_initialize_ms -gt 0 }).Count
$retryCount = @($recordArray | Where-Object { $_.retry_count -gt 0 }).Count

$summary = [PSCustomObject]@{
    started_at = $startedAt.ToString("o")
    finished_at = $finishedAt.ToString("o")
    count_requested = $Count
    count_completed = $recordArray.Count
    model = $Model
    prompt_file = $PromptFile
    uri = $uri
    endpoint_success_count = $endpointSuccessCount
    generation_success_count = $generationSuccessCount
    model_json_parse_success_count = $validJsonCount
    model_json_parse_success_rate = if ($recordArray.Count -gt 0) { [Math]::Round($validJsonCount / $recordArray.Count, 4) } else { 0 }
    pass_99_percent_gate = ($recordArray.Count -gt 0 -and ($validJsonCount / $recordArray.Count) -ge 0.99)
    backend_values = $backendValues
    constrained = [bool]$Constrained
    stream = [bool]$Stream
    constrained_success_count = @($recordArray | Where-Object { $_.constrained_decoding }).Count
    constrained_tool_call_count = @($recordArray | Where-Object { $_.constrained_tool_call_observed }).Count
    engine_reinitialize_count = $initCount
    retried_run_count = $retryCount
    max_endpoint_retries = $MaxEndpointRetries
    thermal_abort_reason = $thermalAbortReason
    max_thermal_pause_cycles = $MaxThermalPauseCycles
    wall_ms_avg = if ($wallValues.Count -gt 0) { [Math]::Round(($wallValues | Measure-Object -Average).Average, 1) } else { $null }
    wall_ms_p50 = Get-Percentile $wallValues 50
    wall_ms_p95 = Get-Percentile $wallValues 95
    generate_ms_avg = if ($generateValues.Count -gt 0) { [Math]::Round(($generateValues | Measure-Object -Average).Average, 1) } else { $null }
    generate_ms_p50 = Get-Percentile $generateValues 50
    generate_ms_p95 = Get-Percentile $generateValues 95
    constrained_ms_avg = if ($constrainedValues.Count -gt 0) { [Math]::Round(($constrainedValues | Measure-Object -Average).Average, 1) } else { $null }
    constrained_ms_p50 = Get-Percentile $constrainedValues 50
    constrained_ms_p95 = Get-Percentile $constrainedValues 95
    records = $recordArray
}

$summary | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "summary.json")
$recordArray | ConvertTo-Json -Depth 6 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "records.json")

$report = @"
# LiteRT Prompt JSON Smoke

Model: $Model

Prompt file: $PromptFile

Runs: $($summary.count_completed) / $Count

JSON parse success: $($summary.model_json_parse_success_count) / $($summary.count_completed) = $($summary.model_json_parse_success_rate)

99% gate: $($summary.pass_99_percent_gate)

Backends: $($backendValues -join ', ')

Constrained decoding: $($summary.constrained)

Streaming endpoint: $($summary.stream)

Constrained tool calls observed: $($summary.constrained_tool_call_count) / $($summary.count_completed)

Engine reinitializations: $initCount

Retried runs: $retryCount

Thermal abort: $thermalAbortReason

| Metric | Avg | P50 | P95 |
| --- | ---: | ---: | ---: |
| Wall ms | $($summary.wall_ms_avg) | $($summary.wall_ms_p50) | $($summary.wall_ms_p95) |
| Generate ms | $($summary.generate_ms_avg) | $($summary.generate_ms_p50) | $($summary.generate_ms_p95) |
| Constrained ms | $($summary.constrained_ms_avg) | $($summary.constrained_ms_p50) | $($summary.constrained_ms_p95) |

Artifacts:

- summary.json
- records.json
- model_readiness.json
- run_###.json
"@

$report | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "report.md")

Write-Host "Wrote $OutDir"
Write-Host ("JSON parse success: {0}/{1} ({2})" -f $validJsonCount, $recordArray.Count, $summary.model_json_parse_success_rate)
