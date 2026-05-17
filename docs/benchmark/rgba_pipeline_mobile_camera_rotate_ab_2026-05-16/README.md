# Mobile Camera Rotate A/B

Date: 2026-05-16

Device: Pixel 8 Pro

Flow: GemmaFit live camera, 30 seconds per variant.

Purpose: verify the camera pipeline optimization on-device, not with host-side OpenCV simulation. The question is whether CameraX output rotation can replace app-layer `Bitmap.createBitmap(... Matrix ...)` rotation without changing the final MediaPipe bitmap orientation.

## Mobile Architecture Difference

Host video simulation only measures decoded frame buffer operations. The Android live path has extra moving parts:

- CameraX may move rotation work before the analyzer callback.
- `ImageProxy.toBitmap()` still runs on-device and may change cost when output rotation is enabled.
- MediaPipe `detectAsync()` requires stable bitmap ownership, so direct `ImageProxy` zero-copy is still unsafe.
- The audit must read the main app process state file. The content provider runs in `:debug`, so it does not reset or inspect the live-camera in-memory collector.

## Variants

| Variant | CameraX output rotation | Analyzer rotation | Input proxy | Final MediaPipe bitmap |
| --- | --- | --- | --- | --- |
| `default_optimized` / `CAMERAX_ROTATED_YUV_BITMAP` | enabled | none | `480x640`, `rotation=0` | `480x640` |
| `old_baseline` / `CURRENT_YUV_BITMAP_ROTATE` | disabled | app `Bitmap` rotate | `640x480`, `rotation=90` | `480x640` |

Both variants keep `YUV_420_888` and `BitmapImageBuilder`. The RGBA path remains a debug-only candidate.

## Results

Times are microseconds from the app analyzer path. CameraX internal work before analyzer callback is not separately visible in this collector.

| Variant | Samples | Hz | Rotation seen by app | YUV to Bitmap avg / p95 | App rotate avg / p95 | Total accepted frame avg / p95 |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| `default_optimized` | 374 | 12.43 | `0` | 1,272 / 3,672 | 0 / 0 | 5,507 / 11,012 |
| `old_baseline` | 382 | 12.36 | `90` | 949 / 1,529 | 9,171 / 15,426 | 13,702 / 21,552 |

## Delta

| Metric | Change |
| --- | ---: |
| App rotate avg | `9,171 us -> 0 us` |
| App rotate p95 | `15,426 us -> 0 us` |
| Total accepted frame avg | `13,702 us -> 5,507 us` |
| Total accepted frame p95 | `21,552 us -> 11,012 us` |
| Avg total saving | `8,195 us` |
| P95 total saving | `10,540 us` |

The optimized path has a slightly higher measured `ImageProxy.toBitmap()` average in this run, but the removed app rotate stage is much larger, so the net analyzer cost still drops by about 60 percent on average.

Sample rate stayed near the app throttle in both runs, so this optimization primarily reduces analyzer headroom and p95 spikes rather than increasing the visible sampling rate.

## Decision

Keep `CAMERAX_ROTATED_YUV_BITMAP` as the live-camera default.

Keep `CURRENT_YUV_BITMAP_ROTATE` as an A/B fallback through `files/debug/live_camera_image_pipeline.txt`.

Keep `CAMERAX_ROTATED_RGBA_BITMAP` behind a debug flag until landmark correctness is verified with a visible person.

Do not move to a direct `ImageProxy` buffer path until MediaPipe input ownership is redesigned around async lifetime.

## Commands

Default optimized:

```powershell
adb shell am force-stop com.gemmafit
adb shell "run-as com.gemmafit sh -c 'rm -f files/debug/live_camera_image_pipeline.txt'"
adb shell monkey -p com.gemmafit 1
adb shell input tap 505 1545
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run_rgba_pipeline_audit.ps1 -DurationSeconds 30 -OutDir docs\benchmark\rgba_pipeline_mobile_camera_rotate_ab_2026-05-16\default_optimized -SkipReset
```

Old baseline:

```powershell
adb shell am force-stop com.gemmafit
adb shell "run-as com.gemmafit sh -c 'mkdir -p files/debug; echo current_yuv_bitmap_rotate > files/debug/live_camera_image_pipeline.txt'"
adb shell monkey -p com.gemmafit 1
adb shell input tap 505 1545
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run_rgba_pipeline_audit.ps1 -DurationSeconds 30 -OutDir docs\benchmark\rgba_pipeline_mobile_camera_rotate_ab_2026-05-16\old_baseline -SkipReset
```

## Artifacts

- `default_optimized/summary.json`
- `default_optimized/report.md`
- `old_baseline/summary.json`
- `old_baseline/report.md`
