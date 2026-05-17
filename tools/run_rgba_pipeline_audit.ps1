param(
    [int]$DurationSeconds = 30,
    [string]$OutDir = "",
    [ValidateSet("current_yuv_bitmap_rotate", "camerax_rotated_yuv_bitmap", "camerax_rotated_rgba_bitmap")]
    [string]$PipelineVariant = "",
    [switch]$LaunchApp,
    [switch]$SkipReset,
    [switch]$UseContentEndpoint
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $OutDir = "docs\benchmark\rgba_pipeline_audit_$stamp"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Invoke-AdbContent {
    param([string]$Uri)
    $remoteCommand = "content read --uri '$Uri'"
    & adb shell $remoteCommand
}

function Read-MainProcessAuditSnapshot {
    param([string]$StatePath)
    $stateRaw = & adb exec-out run-as com.gemmafit cat files/debug/gemmafit_debug_state.json
    $stateRaw | Set-Content -Encoding UTF8 -Path $StatePath
    $state = $stateRaw | ConvertFrom-Json
    $snapshot = $state.sections.rgba_pipeline_audit.data.snapshot
    if ($null -eq $snapshot) {
        throw "No rgba_pipeline_audit snapshot found in main-process debug state. Keep the app on Live Camera and avoid reading the content endpoint during collection."
    }
    return $snapshot
}

if ($LaunchApp) {
    if (-not [string]::IsNullOrWhiteSpace($PipelineVariant)) {
        & adb shell "run-as com.gemmafit sh -c 'mkdir -p files/debug; echo $PipelineVariant > files/debug/live_camera_image_pipeline.txt'"
    }
    & adb shell monkey -p com.gemmafit 1 | Out-Null
    Start-Sleep -Seconds 3
}

$resetUri = "content://com.gemmafit.debug/rgba_pipeline_audit?reset=true"
$readUri = "content://com.gemmafit.debug/rgba_pipeline_audit"

if ($UseContentEndpoint -and -not $SkipReset) {
    Invoke-AdbContent -Uri $resetUri | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "reset.json")
} elseif (-not $SkipReset) {
    @{
        skipped = $true
        reason = "Default collection reads the main app process state file. The content provider runs in :debug, so endpoint reset does not reset the live-camera in-memory collector."
        reset_hint = "For a clean window, force-stop com.gemmafit, relaunch, enter Live Camera, then run this script."
    } | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 -Path (Join-Path $OutDir "reset.json")
}

Write-Host "Collecting RGBA/RGB pipeline audit for $DurationSeconds seconds."
Write-Host "Keep the live camera screen active during this window."
Start-Sleep -Seconds $DurationSeconds

$summaryPath = Join-Path $OutDir "summary.json"
if ($UseContentEndpoint) {
    $summaryRaw = Invoke-AdbContent -Uri $readUri
    $summaryRaw | Set-Content -Encoding UTF8 -Path $summaryPath
    $summary = $summaryRaw | ConvertFrom-Json
} else {
    $statePath = Join-Path $OutDir "state.json"
    $summary = Read-MainProcessAuditSnapshot -StatePath $statePath
    $summary | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 -Path $summaryPath
}
$timing = $summary.timing_us

$reportPath = Join-Path $OutDir "report.md"
$report = @"
# RGBA/RGB Pipeline Audit

Duration requested: $DurationSeconds seconds

Sample count: $($summary.sample_count)

Total seen since reset: $($summary.total_seen_since_reset)

Estimated sample rate: $($summary.estimated_sample_rate_hz) Hz

Pipeline:

```text
$($summary.pipeline)
```

Pipeline variants:

```json
$($summary.pipeline_variants | ConvertTo-Json -Depth 8)
```

CameraX output rotation enabled:

```json
$($summary.camerax_output_rotation_enabled | ConvertTo-Json -Depth 8)
```

Input formats:

```json
$($summary.input_formats | ConvertTo-Json -Depth 8)
```

Frame bitmap configs:

```json
$($summary.frame_bitmap_configs | ConvertTo-Json -Depth 8)
```

| Stage | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: |
| YUV to Bitmap | $($timing.yuv_to_bitmap.avg) | $($timing.yuv_to_bitmap.p50) | $($timing.yuv_to_bitmap.p95) | $($timing.yuv_to_bitmap.max) |
| Rotate | $($timing.rotate.avg) | $($timing.rotate.p50) | $($timing.rotate.p95) | $($timing.rotate.max) |
| BitmapImageBuilder | $($timing.bitmap_image_build.avg) | $($timing.bitmap_image_build.p50) | $($timing.bitmap_image_build.p95) | $($timing.bitmap_image_build.max) |
| detectAsync enqueue | $($timing.detect_async_enqueue.avg) | $($timing.detect_async_enqueue.p50) | $($timing.detect_async_enqueue.p95) | $($timing.detect_async_enqueue.max) |
| Appearance snapshot copy | $($timing.appearance_snapshot_copy.avg) | $($timing.appearance_snapshot_copy.p50) | $($timing.appearance_snapshot_copy.p95) | $($timing.appearance_snapshot_copy.max) |
| Total accepted frame | $($timing.total_accepted_frame.avg) | $($timing.total_accepted_frame.p50) | $($timing.total_accepted_frame.p95) | $($timing.total_accepted_frame.max) |

Artifacts:

- summary.json
- reset.json
"@

$report | Set-Content -Encoding UTF8 -Path $reportPath

Write-Host "Wrote $summaryPath"
Write-Host "Wrote $reportPath"
