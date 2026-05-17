# Pixel 8 Pro RGBA Pipeline Audit

Date: 2026-05-16
Device: Pixel 8 Pro
Package: `com.gemmafit`
Build: debug APK installed from current workspace
Flow: wait 15s on onboarding for app-launch LiteRT prewarm, tap Skip into live camera, sample about 40s.
Resolution: 480x640 live camera analysis frames.

## Result

| Variant | Samples | Window | Hz | Input | Convert avg | Convert p95 | Rotate p95 | Total avg | Total p95 | Total max |
|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|
| CameraX rotated YUV baseline | 389 | 39.8s | 9.77 | YUV_420_888 | 785 us | 1276 us | 0 us | 3166 us | 4464 us | 10678 us |
| CameraX rotated RGBA default | 390 | 40.0s | 9.76 | RGBA_8888 | 453 us | 973 us | 0 us | 2942 us | 4508 us | 9214 us |

## Interpretation

The RGBA default reduces the bitmap conversion bucket by about 332 us average
and 303 us at p95 compared with the CameraX-rotated YUV baseline.

End-to-end accepted-frame time improves modestly on average, from 3166 us to
2942 us, but p95 is effectively flat: 4464 us vs 4508 us. This confirms the
change saves conversion CPU, but it does not justify a claim of several
milliseconds saved per frame at the current 480x640 analysis resolution.

CameraX output rotation is already effective in both variants: `rotation_degrees`
is 0 and `rotate` p95 is 0 us for both runs.

## Device State

After the RGBA run, `dumpsys thermalservice` reported thermal status 1. The
app was still alive. `dumpsys meminfo com.gemmafit` reported total PSS about
3.52 GB with large graphics/GL residency, which is consistent with the current
LiteRT/GPU-heavy debug session and should not be attributed to the camera
pipeline alone.

## Artifacts

- `yuv_state.json`
- `rgba_state.json`
- `yuv_events_tail.jsonl`
- `rgba_events_tail.jsonl`
- `thermal_after_rgba.txt`
- `meminfo_after_rgba.txt`
