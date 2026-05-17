# Phone Validation For Real Demo Videos

Date: 2026-05-17
Device: Pixel 8 Pro
Package: `com.gemmafit`

The selected real demo videos were pushed to:

- `/sdcard/Android/data/com.gemmafit/files/demo_real/800432268.335036.mp4`
- `/sdcard/Android/data/com.gemmafit/files/demo_real/800432435.743618.mp4`

For debug smoke validation, the same files were also copied into app-owned
internal storage:

- `/data/user/0/com.gemmafit/files/test_videos/800432268.335036.mp4`
- `/data/user/0/com.gemmafit/files/test_videos/800432435.743618.mp4`

## Video Realtime Smoke

Endpoint:

```text
content://com.gemmafit.debug/video_realtime_smoke?file=<video>
```

| Video | Success | Frames | Pose hit rate | 33-landmark rate | Avg visibility | Avg pose | P95 pose | Avg convert | P95 convert | Live AI invoked |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `800432268.335036.mp4` | yes | 61 | 1.00 | 1.00 | 0.978 | 73.7 ms | 94 ms | 28.4 ms | 44 ms | no |
| `800432435.743618.mp4` | yes | 81 | 1.00 | 1.00 | 0.767 | 75.7 ms | 96 ms | 31.9 ms | 44 ms | no |

Both clips pass the current 200 ms sample-interval smoke budget. The live AI
path was not invoked for either clean/monitor-only video, which is expected:
real-time frames stay deterministic unless a gated warning/refusal path is
required.

## Notes

- `800432268.335036.mp4` remains the best main demo clip: stronger visibility
  and front-facing chair sit-to-stand phases.
- `800432435.743618.mp4` is useful as the side-view support-object clip. Its
  visibility is lower because it is partial side view, but pose tracking still
  succeeds on every sampled frame.
- The first attempted smoke read defaulted to the bundled sample because files
  pushed under external storage were owned by `shell`; app-internal copies were
  created for reliable endpoint resolution.

## Artifacts

- `800432268.335036.video_realtime_smoke.json`
- `800432435.743618.video_realtime_smoke.json`
- `phone_demo_launch_ui.xml`
