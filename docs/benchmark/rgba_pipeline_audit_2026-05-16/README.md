# RGBA/RGB Pipeline Audit - Pixel 8 Pro

Date: 2026-05-16

Device: Pixel 8 Pro (`3A251FDJG004RX`)

Build: installed debug APK from current working tree

## Summary

This run measured the existing live-camera preprocessing path:

```text
CameraX YUV_420_888 -> ImageProxy.toBitmap() -> Bitmap ARGB_8888 -> rotate -> BitmapImageBuilder -> MediaPipe Pose
```

The audit instrumentation itself does not optimize the pipeline, so this report is a current-device baseline, not a before/after speedup result. A real improvement percentage requires rerunning the same audit after a zero-copy / RGB path change.

## Valid Run

Collection method:

1. `adb shell am force-stop com.gemmafit`
2. Start `com.gemmafit/.MainActivity`
3. Tap `Skip` to enter `Live Camera`
4. Confirmed `SurfaceView`, `Live Camera`, and `Pause` in the UI tree
5. Waited 30 seconds with live camera active
6. Read the main app process state file directly:

```powershell
adb exec-out run-as com.gemmafit cat files/debug/gemmafit_debug_state.json
```

The content-provider endpoint was intentionally not used for the formal timing read; see caveat below.

## Result

| Metric | Value |
| --- | ---: |
| Sample count | 442 |
| Window duration | 35.267 s |
| Estimated sample rate | 12.53 Hz |
| Input format | YUV_420_888 |
| Raw bitmap config | ARGB_8888 |
| Frame bitmap config | ARGB_8888 |
| ImageProxy size | 640x480 |
| Frame bitmap size after rotation | 480x640 |
| Rotation | 90 deg |

## Stage Timing

| Stage | Count | Avg us | P50 us | P95 us | Max us |
| --- | ---: | ---: | ---: | ---: | ---: |
| YUV to Bitmap | 442 | 1065 | 758 | 2963 | 7651 |
| Rotate | 442 | 9200 | 7984 | 15281 | 39295 |
| BitmapImageBuilder | 442 | 153 | 113 | 237 | 7269 |
| detectAsync enqueue | 442 | 2476 | 2134 | 4325 | 9407 |
| Appearance snapshot copy | 118 | 1368 | 1193 | 2737 | 4237 |
| Total accepted frame preprocess | 442 | 13309 | 12162 | 19952 | 46040 |

## Interpretation

The measurable optimization budget is mostly rotation, not `ImageProxy.toBitmap()`:

| Area | Finding |
| --- | --- |
| `ImageProxy.toBitmap()` | About 1.1 ms avg, 3.0 ms p95. Worth tracking, but not the biggest live-camera cost in this run. |
| Rotation | About 9.2 ms avg, 15.3 ms p95. This is the main preprocessing target. |
| `BitmapImageBuilder` | About 0.15 ms avg, effectively negligible except rare spikes. |
| `detectAsync` enqueue | About 2.5 ms avg, 4.3 ms p95. This is queue/enqueue cost, not full MediaPipe inference. |
| Total accepted-frame preprocess | About 13.3 ms avg, 20.0 ms p95. |

Practical conclusion:

```text
Zero-copy/RGB work should first try to avoid the Bitmap rotation/copy path.
If rotation can be eliminated or moved into the camera transform / downstream coordinate mapping,
the realistic reclaim is roughly 8-10 ms average and about 15 ms p95 on this device.
```

This does not imply end-to-end FPS will rise by the full amount because MediaPipe inference, UI rendering, and frame throttling are outside this instrumentation window.

## Memory Snapshot

`dumpsys meminfo com.gemmafit` after the valid run:

| Metric | Value |
| --- | ---: |
| TOTAL PSS | 361,498 KB |
| TOTAL RSS | 484,924 KB |
| Native Heap PSS | 81,836 KB |
| Java Heap PSS | 15,804 KB |
| Graphics PSS | 123,704 KB |
| GL mtrack | 78,584 KB |
| EGL mtrack | 45,120 KB |

## Endpoint Caveat

The existing endpoint:

```text
content://com.gemmafit.debug/rgba_pipeline_audit
```

currently runs through `DebugReportProvider`, which is declared with:

```xml
android:process=":debug"
```

`RgbaPipelineAudit` is an in-memory collector in the main app process. Reading the endpoint from `:debug` sees an empty in-memory collector and can return `sample_count = 0` even while the live camera is actively collecting samples in the main process.

For this report, the reliable source of truth was the main process state written under:

```text
files/debug/gemmafit_debug_state.json
```

## Artifacts

- `rgba_pipeline_audit_formal2_30s_snapshot.json` - formal timing snapshot
- `timing_formal2_30s.json` - extracted timing table
- `state_formal2_30s.json` - full debug state file from the main app process
- `meminfo_formal2_30s.txt` - memory snapshot
- `gfxinfo_formal2_30s.txt` - gfxinfo framestats
- `screen_formal2_live.png` - live-camera screenshot during valid run
- `window_formal2_live.xml` - UI tree confirming live camera
- `script_smoke_main_state2/` - 5 second smoke run for the updated collection script

## Script Update

`tools/run_rgba_pipeline_audit.ps1` was updated so the default path reads the main app process state file through `run-as` instead of reading the `:debug` content endpoint. The old endpoint path is still available with `-UseContentEndpoint`, but it should not be used for the live-camera timing window unless the provider/process boundary is fixed.

Script smoke result after the update:

| Metric | Value |
| --- | ---: |
| Duration requested | 5 s |
| Sample count | 121 |
| Window duration | 9.386 s |
| Estimated sample rate | 12.89 Hz |
