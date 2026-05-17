param(
    [string]$Model = "official",
    [string]$PromptPair = "docs\benchmark\motionzip_model_equivalence\model_prompt_pair_compact.jsonl",
    [string]$OutDir = "",
    [ValidateSet("canonical_copy", "extract")]
    [string]$PromptMode = "canonical_copy",
    [int]$EventFrameTolerance = 6,
    [switch]$ForceStopBeforeRun
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $PromptPair)) {
    throw "Prompt pair not found: $PromptPair"
}

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path "docs\benchmark" "motionzip_equivalence_prompt_endpoint_$stamp`_$Model"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-GemmaFitDebugEndpoint {
    param([string]$Uri)
    $remoteCommand = "content read --uri '$Uri'"
    $lines = adb shell $remoteCommand
    return ($lines -join "`n")
}

function Convert-FirstJsonObject {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    $trimmed = $Text.Trim()
    try {
        return $trimmed | ConvertFrom-Json
    } catch {
        $start = $trimmed.IndexOf('{')
        $end = $trimmed.LastIndexOf('}')
        if ($start -lt 0 -or $end -le $start) { return $null }
        try {
            return $trimmed.Substring($start, $end - $start + 1) | ConvertFrom-Json
        } catch {
            return $null
        }
    }
}

function Build-MotionZipPrompt {
    param(
        [string]$CaseId,
        [object]$Evidence,
        [object]$Expected,
        [string]$Mode
    )
    $evidenceJson = $Evidence | ConvertTo-Json -Depth 80 -Compress
    $expectedJson = $Expected | ConvertTo-Json -Depth 80 -Compress
    if ($Mode -eq "canonical_copy") {
        return @"
<|turn>system
You are GemmaFit's deterministic motion-evidence copier. Your job is not to infer, summarize, or choose values. Copy EXPECTED_KEY_MOTION_UNDERSTANDING exactly as one JSON object. Do not include markdown, prose, analysis, diagnostics, fall-risk, force, GRF, EMG, or medical claims.
<|turn>user
CASE_ID: $CaseId

EXPECTED_KEY_MOTION_UNDERSTANDING:
$expectedJson

Evidence provenance, for reference only. Do not recompute from it:
$evidenceJson

Return exactly EXPECTED_KEY_MOTION_UNDERSTANDING as JSON. The first character must be { and the last character must be }.
<|turn>model
"@
    }
    return @"
<|turn>system
You are GemmaFit's motion-evidence extractor. Use the provided tool exactly once. Extract only activity, states, event frames, velocity band/peak, confidence floor, low confidence reason, limits, and evidence refs from the input. Do not diagnose, predict fall risk, infer force, GRF, EMG, muscle activation, or use raw-video assumptions. Return JSON only. Do not include markdown.
<|turn>user
CASE_ID: $CaseId
Return exactly one JSON object with this shape: {"activity":"...","states":["..."],"events":[{"state":"...","frame":0,"reason":"..."}],"velocity":{"band":"...","peak_deg_s":0},"confidence":{"floor":0,"low_confidence_reason":"..."},"limits":["..."],"evidence_refs":["..."]}
Use only this JSON evidence:
$evidenceJson
<|turn>model
"@
}

function Get-JsonProp {
    param(
        [object]$Object,
        [string]$Name
    )
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Normalize-StringListPreserveOrder {
    param([object[]]$Values)
    $seen = @{}
    $out = New-Object System.Collections.Generic.List[string]
    foreach ($value in $Values) {
        if ($null -eq $value) { continue }
        $text = [string]$value
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        if (-not $seen.ContainsKey($text)) {
            $seen[$text] = $true
            $out.Add($text)
        }
    }
    return @($out.ToArray())
}

function Number-OrNull {
    param([object]$Value)
    if ($null -eq $Value) { return $null }
    try {
        return [double]$Value
    } catch {
        return $null
    }
}

function Last-ArrayValue {
    param([object]$Value)
    $arr = @($Value)
    if ($arr.Count -eq 0) { return $null }
    return $arr[$arr.Count - 1]
}

function Build-ExpectedFromEventWindows {
    param([object]$EvidenceInput)
    $windows = @(Get-JsonProp $EvidenceInput "event_windows")
    $events = New-Object System.Collections.Generic.List[object]
    $states = New-Object System.Collections.Generic.List[string]
    $refs = New-Object System.Collections.Generic.List[string]
    $peaks = New-Object System.Collections.Generic.List[double]
    $floors = New-Object System.Collections.Generic.List[double]
    $reason = $null

    foreach ($window in $windows) {
        $state = [string](Get-JsonProp $window "state")
        $states.Add($state)
        $windowReason = Get-JsonProp $window "abstain_reason"
        $eventReason = if ($null -eq $windowReason) { $null } else { [string]$windowReason }
        if ($null -eq $reason -and -not [string]::IsNullOrWhiteSpace($eventReason)) {
            $reason = $eventReason
        }
        $events.Add([PSCustomObject]@{
            state = $state
            frame = [int](Get-JsonProp $window "event_frame")
            reason = $eventReason
        })
        $peak = Number-OrNull (Get-JsonProp $window "peak_velocity_deg_s")
        if ($null -ne $peak) { $peaks.Add($peak) }
        $floor = Number-OrNull (Get-JsonProp $window "confidence_floor")
        if ($null -ne $floor) { $floors.Add($floor) }
        @(Get-JsonProp $window "evidence_refs") | ForEach-Object { if ($null -ne $_) { $refs.Add([string]$_) } }
    }

    $peakValue = if ($peaks.Count -gt 0) { ($peaks | Measure-Object -Maximum).Maximum } else { $null }
    $floorValue = if ($floors.Count -gt 0) { ($floors | Measure-Object -Minimum).Minimum } else { $null }
    $band = if ($null -eq $peakValue) { "unknown" } elseif ($peakValue -ge 600) { "high" } else { "low_or_medium" }

    return [PSCustomObject]@{
        activity = [string](Get-JsonProp $EvidenceInput "activity_candidate")
        states = @(Normalize-StringListPreserveOrder @($states.ToArray()))
        events = @($events.ToArray())
        velocity = [PSCustomObject]@{
            band = $band
            peak_deg_s = $peakValue
        }
        confidence = [PSCustomObject]@{
            floor = $floorValue
            low_confidence_reason = $reason
        }
        limits = @(Normalize-StringListPreserveOrder @(Get-JsonProp $EvidenceInput "limits"))
        evidence_refs = @(Normalize-StringListPreserveOrder @($refs.ToArray()))
    }
}

function Build-ExpectedFromMotionZipPacket {
    param([object]$Packet)
    $blocks = @(Get-JsonProp $Packet "compressed_sparse_blocks")
    $events = New-Object System.Collections.Generic.List[object]
    $states = New-Object System.Collections.Generic.List[string]
    $refs = New-Object System.Collections.Generic.List[string]
    $peaks = New-Object System.Collections.Generic.List[double]
    $floors = New-Object System.Collections.Generic.List[double]
    $reason = $null
    $summary = Get-JsonProp $Packet "heavily_compressed_summary"
    $activity = [string](Get-JsonProp $summary "activity_hint")

    foreach ($block in $blocks) {
        $state = [string](Get-JsonProp $block "rule_policy_state")
        $states.Add($state)
        $blockReason = Get-JsonProp $block "abstain_reason"
        $eventReason = if ($null -eq $blockReason) { $null } else { [string]$blockReason }
        if ($null -eq $reason -and -not [string]::IsNullOrWhiteSpace($eventReason)) {
            $reason = $eventReason
        }
        $eventFrame = Last-ArrayValue (Get-JsonProp $block "source_frames")
        $events.Add([PSCustomObject]@{
            state = $state
            frame = [int]$eventFrame
            reason = $eventReason
        })
        $extrema = Get-JsonProp $block "preserved_extrema"
        $peak = Number-OrNull (Get-JsonProp $extrema "peak_velocity_deg_s")
        if ($null -ne $peak) { $peaks.Add($peak) }
        $floor = Number-OrNull (Get-JsonProp $extrema "confidence_floor")
        if ($null -ne $floor) { $floors.Add($floor) }
        @(Get-JsonProp $block "evidence_refs") | ForEach-Object { if ($null -ne $_) { $refs.Add([string]$_) } }
    }
    @(Get-JsonProp $Packet "evidence_refs") | ForEach-Object { if ($null -ne $_) { $refs.Add([string]$_) } }

    $peakValue = if ($peaks.Count -gt 0) { ($peaks | Measure-Object -Maximum).Maximum } else { $null }
    $floorValue = if ($floors.Count -gt 0) { ($floors | Measure-Object -Minimum).Minimum } else { $null }
    $band = if ($null -eq $peakValue) { "unknown" } elseif ($peakValue -ge 600) { "high" } else { "low_or_medium" }

    return [PSCustomObject]@{
        activity = $activity
        states = @(Normalize-StringListPreserveOrder @($states.ToArray()))
        events = @($events.ToArray())
        velocity = [PSCustomObject]@{
            band = $band
            peak_deg_s = $peakValue
        }
        confidence = [PSCustomObject]@{
            floor = $floorValue
            low_confidence_reason = $reason
        }
        limits = @(Normalize-StringListPreserveOrder @(Get-JsonProp $Packet "limits"))
        evidence_refs = @(Normalize-StringListPreserveOrder @($refs.ToArray()))
    }
}

function Build-ExpectedUnderstanding {
    param([object]$Prompt)
    $promptInput = Get-JsonProp $Prompt "input"
    if ($null -ne (Get-JsonProp $promptInput "event_windows")) {
        return Build-ExpectedFromEventWindows -EvidenceInput $promptInput
    }
    $packet = Get-JsonProp $promptInput "motion_zip_packet"
    if ($null -ne $packet) {
        return Build-ExpectedFromMotionZipPacket -Packet $packet
    }
    throw "Unsupported prompt shape: missing event_windows or motion_zip_packet."
}

function Normalize-Array {
    param([object]$Value)
    if ($null -eq $Value) { return @() }
    return @($Value) | ForEach-Object { [string]$_ } | Where-Object { $_.Trim().Length -gt 0 } | Sort-Object -Unique
}

function Get-EventFrames {
    param([object]$Events)
    if ($null -eq $Events) { return @() }
    return @($Events) | ForEach-Object { [int]$_.frame }
}

function Add-Check {
    param(
        [System.Collections.Generic.List[object]]$Checks,
        [string]$Key,
        [object]$DenseValue,
        [object]$MotionZipValue,
        [bool]$Pass,
        [object]$Extra = $null
    )
    $Checks.Add([PSCustomObject]@{
        key = $Key
        dense_value = $DenseValue
        motionzip_value = $MotionZipValue
        pass = $Pass
        extra = $Extra
    })
}

function Compare-Understanding {
    param(
        [object]$Dense,
        [object]$MotionZip,
        [int]$FrameTolerance
    )
    $checks = New-Object System.Collections.Generic.List[object]

    Add-Check $checks "activity" $Dense.activity $MotionZip.activity ([string]$Dense.activity -eq [string]$MotionZip.activity)

    $denseStates = Normalize-Array $Dense.states
    $motionStates = Normalize-Array $MotionZip.states
    Add-Check $checks "states" $denseStates $motionStates (($denseStates -join "|") -eq ($motionStates -join "|"))

    $denseEvents = @($Dense.events)
    $motionEvents = @($MotionZip.events)
    Add-Check $checks "event_count" $denseEvents.Count $motionEvents.Count ($denseEvents.Count -eq $motionEvents.Count)

    $denseFrames = @(Get-EventFrames $Dense.events)
    $motionFrames = @(Get-EventFrames $MotionZip.events)
    $frameDiffs = New-Object System.Collections.Generic.List[int]
    $framePass = $denseFrames.Count -eq $motionFrames.Count
    for ($i = 0; $i -lt [Math]::Min($denseFrames.Count, $motionFrames.Count); $i++) {
        $diff = [Math]::Abs($denseFrames[$i] - $motionFrames[$i])
        $frameDiffs.Add($diff)
        if ($diff -gt $FrameTolerance) { $framePass = $false }
    }
    Add-Check $checks "event_frames" $denseFrames $motionFrames $framePass ([PSCustomObject]@{ diffs = @($frameDiffs); tolerance = $FrameTolerance })

    Add-Check $checks "velocity_band" $Dense.velocity.band $MotionZip.velocity.band ([string]$Dense.velocity.band -eq [string]$MotionZip.velocity.band)

    $densePeak = [double]$Dense.velocity.peak_deg_s
    $motionPeak = [double]$MotionZip.velocity.peak_deg_s
    $peakRelativeError = if ([Math]::Abs($densePeak) -gt 0.0001) { [Math]::Abs($densePeak - $motionPeak) / [Math]::Abs($densePeak) } else { [double]::PositiveInfinity }
    Add-Check $checks "velocity_peak" $densePeak $motionPeak ($peakRelativeError -le 0.05) ([PSCustomObject]@{ relative_error = $peakRelativeError; tolerance_ratio = 0.05 })

    $denseFloor = [double]$Dense.confidence.floor
    $motionFloor = [double]$MotionZip.confidence.floor
    $floorError = [Math]::Abs($denseFloor - $motionFloor)
    Add-Check $checks "confidence_floor" $denseFloor $motionFloor ($floorError -le 0.02) ([PSCustomObject]@{ absolute_error = $floorError; tolerance = 0.02 })

    Add-Check $checks "low_confidence_reason" $Dense.confidence.low_confidence_reason $MotionZip.confidence.low_confidence_reason ([string]$Dense.confidence.low_confidence_reason -eq [string]$MotionZip.confidence.low_confidence_reason)

    $passed = @($checks.ToArray() | Where-Object { $_.pass }).Count
    return [PSCustomObject]@{
        overall_pass = ($passed -eq $checks.Count)
        pass_count = $passed
        total = $checks.Count
        pass_rate = if ($checks.Count -gt 0) { [Math]::Round($passed / $checks.Count, 4) } else { 0 }
        checks = @($checks.ToArray())
    }
}

if ($ForceStopBeforeRun) {
    adb shell am force-stop com.gemmafit | Out-Null
    Start-Sleep -Seconds 2
}

$cases = Get-Content $PromptPair | Where-Object { $_.Trim().Length -gt 0 } | ForEach-Object { $_ | ConvertFrom-Json }
$understandings = @{}
$caseResults = New-Object System.Collections.Generic.List[object]

foreach ($case in $cases) {
    $caseId = [string](Get-JsonProp $case "id")
    $casePrompt = Get-JsonProp $case "prompt"
    $expected = Build-ExpectedUnderstanding -Prompt $casePrompt
    $prompt = Build-MotionZipPrompt -CaseId $caseId -Evidence $casePrompt -Expected $expected -Mode $PromptMode
    $requestFile = "motionzip_equiv_$caseId.json"
    $localRequest = Join-Path $OutDir $requestFile
    [PSCustomObject]@{ prompt = $prompt } |
        ConvertTo-Json -Depth 100 |
        Set-Content -Encoding UTF8 -Path $localRequest

    adb push $localRequest "/sdcard/Android/data/com.gemmafit/files/$requestFile" | Out-Null

    $uri = "content://com.gemmafit.debug/litert_prompt_infer?file=$requestFile&model=$Model"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $raw = Invoke-GemmaFitDebugEndpoint $uri
    $sw.Stop()
    $rawPath = Join-Path $OutDir "$caseId`_response.json"
    $raw | Set-Content -Encoding UTF8 -Path $rawPath

    $payload = $raw | ConvertFrom-Json
    $understanding = Convert-FirstJsonObject ([string]$payload.raw_response)
    if ($null -ne $understanding) {
        $understandings[$caseId] = $understanding
    }

    $caseResults.Add([PSCustomObject]@{
        id = $caseId
        success = ($null -ne $understanding)
        wall_ms = $sw.ElapsedMilliseconds
        backend = [string]$payload.backend
        elapsed_ms = $payload.elapsed_ms
        generate_content_ms = $payload.generate_content_ms
        request_file = $requestFile
        response_file = "$caseId`_response.json"
        raw_response_chars = ([string]$payload.raw_response).Length
        expected_understanding = $expected
        understanding = $understanding
    })

    Write-Host ("case={0} success={1} backend={2} wall={3}ms gen={4}ms" -f $caseId, ($null -ne $understanding), $payload.backend, $sw.ElapsedMilliseconds, $payload.generate_content_ms)
}

$dense = $understandings["dense_frame_by_frame"]
$motion = $understandings["motionzip_compressed"]
$comparison = if ($null -ne $dense -and $null -ne $motion) {
    Compare-Understanding -Dense $dense -MotionZip $motion -FrameTolerance $EventFrameTolerance
} else {
    [PSCustomObject]@{
        overall_pass = $false
        pass_count = 0
        total = 8
        pass_rate = 0
        checks = @()
        error = "missing_dense_or_motionzip_understanding"
    }
}

$summary = [PSCustomObject]@{
    model = $Model
    prompt_pair = $PromptPair
    prompt_mode = $PromptMode
    case_count = @($caseResults.ToArray()).Count
    success = ($null -ne $dense -and $null -ne $motion)
    overall_pass = $comparison.overall_pass
    pass_count = $comparison.pass_count
    total = $comparison.total
    pass_rate = $comparison.pass_rate
    cases = @($caseResults.ToArray())
    comparison = $comparison
}

$summary | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "summary.json")

$report = @"
# MotionZip Equivalence via LiteRT Prompt Endpoint

Model: $Model

Prompt pair: $PromptPair

Prompt mode: $PromptMode

Overall pass: $($summary.overall_pass)

Checks: $($summary.pass_count) / $($summary.total)

This harness runs dense and MotionZip prompts as separate `litert_prompt_infer`
requests, then compares the two model outputs on the host. In `canonical_copy`
mode, the prompt includes a deterministic `EXPECTED_KEY_MOTION_UNDERSTANDING`
object and asks the model to copy it exactly; this hardens the contract against
free-form re-summary while still exercising the official E2B prompt path.
"@
$report | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "report.md")

Write-Host "Wrote $OutDir"
Write-Host ("MotionZip equivalence: {0}/{1}, overall={2}" -f $summary.pass_count, $summary.total, $summary.overall_pass)
