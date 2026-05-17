# Report Video Demo Notes

Source video: `test_assets\videos\internet_public\lunge_forward_army.webm`

## What To Show

1. Start with the left side: the raw workout clip is real video evidence, but it is too expensive and too broad to send as every-frame multimodal input.
2. Move to the right side: the Evidence Panel / MotionZip sidecar keeps only selected visual evidence plus bounded facts.
3. Point at the bottom metrics: dense montage recovered fewer constrained fields, while the sidecar preserved the task-critical evidence fields.
4. End on the boundary statement: this supports low-frequency explanation and summary, not live verdict changes.

## Numbers On Screen

- Source video size: `6677033` bytes.
- Panel q70 size: `23195` bytes.
- Panel q70 reduction vs source video: `99.65%`.
- Dense visual field pass: `3 / 9`.
- Sidecar visual field pass: `9 / 9`.
- Official LiteRT text equivalence: `8 / 8`.
- Generate-time reduction: `15.88%`.

## Generated Assets

- Video: `docs\benchmark\multimodal_image_compression_reproof_2026-05-16\report_video_comparison_demo.mp4`
- Storyboard: `docs\benchmark\multimodal_image_compression_reproof_2026-05-16\report_video_comparison_storyboard.png`

## Safety Boundary

`LIVE_FRAME` remains deterministic-only. The sidecar is for `USER_QUESTION`, `SESSION_ENDED`, `CAREGIVER_EXPORT`, and optional `WARNING_PERSISTED`.
