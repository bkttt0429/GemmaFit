# Mobile YOLO ReID Burst and Lunge Pose Timeline Audit

Date: 2026-05-11

## Scope

This audit checks two separate issues:

- Whether YOLO person detections can be used as a mobile-friendly burst fallback for target relocalization in crowded basketball frames.
- Whether `lunge_forward_army.mp4` truly has no pose in the first 60 app samples, or whether the missing skeleton is a render / judgment-gating issue.

The audit is prototype-only. Android production should still use MediaPipe tracking as the primary path. YOLO is a burst fallback, not an always-on phone path.

## Scripts

- `prototype/mobile_yolo_reid_burst_audit.py`
- `prototype/audit_pose_sample_timeline.py`

## Basketball Target ReID Result

Input video: `tmp_gemmafit_pixel_line_error.mp4`

Problem frames:

- App sample 39 = 7800 ms
- App sample 40 = 8000 ms
- App sample 41 = 8200 ms

### P3 / rank-based initialization

Command:

```powershell
python prototype\mobile_yolo_reid_burst_audit.py --video tmp_gemmafit_pixel_line_error.mp4 --model yolov8n-seg.pt --label pixel_line_error_p3_rank3 --start-frame 39 --end-frame 41 --target-rank 3
```

Result:

```json
{
  "selected_frames": 1,
  "selected_ratio": 0.33333,
  "hold_frames": 2,
  "warm_avg_yolo_ms": 66.946,
  "warm_p95_yolo_ms": 68.235
}
```

Interpretation: YOLO rank 3 is not a stable person identity. At sample 39 it is a small partial box around the upper body / head region. Samples 40 and 41 are rejected or held by ReID. Therefore `p0/p1/p2/p3` must not be treated as stable IDs.

### Corrected target: right-side black-shirt player

The intended tracked target is the right-side black-shirt player. The earlier `tap_640` result was still wrong because there are two black-shirt players and the selected box locked onto the wrong black-shirt player or a mixed foreground box.

Corrected command with a black-target profile gate:

```powershell
python prototype\mobile_yolo_reid_burst_audit.py --video tmp_gemmafit_pixel_line_error.mp4 --model yolov8n-seg.pt --label pixel_line_error_black_target_profile_tap_640 --start-frame 39 --end-frame 41 --tap-x 0.50 --tap-y 0.38 --imgsz 640 --long-side 640 --cpu-budget-ms 45 --target-profile black
```

Result:

```json
{
  "selected_frames": 1,
  "selected_ratio": 0.33333,
  "hold_frames": 1,
  "warm_avg_yolo_ms": 58.028,
  "warm_p95_yolo_ms": 63.446
}
```

Frame-level interpretation:

- Frame 39: YOLO does not provide a clean box for the right-side black-shirt target. The apparent candidate is merged with the foreground green-shirt player, so the profile gate correctly refuses initialization.
- Frame 40: a cleaner black-shirt candidate appears and can initialize.
- Frame 41: the top ReID margin is too small, so the prototype holds instead of switching target.

Interpretation: the correct behavior is not "track 3/3" here. The safe behavior is `ambiguous -> initialize when clean -> hold when identity margin is weak`. This prevents silently switching to the wrong black-shirt player or the green-shirt foreground player.

### Foreground green-shirt control case

This was an earlier control case, not the intended target. It is still useful because it shows why tap / anchor initialization is better than YOLO rank labels.

Command:

```powershell
python prototype\mobile_yolo_reid_burst_audit.py --video tmp_gemmafit_pixel_line_error.mp4 --model yolov8n-seg.pt --label pixel_line_error_ballcarrier_tap_480 --start-frame 39 --end-frame 41 --tap-x 0.48 --tap-y 0.58 --imgsz 480 --long-side 480 --cpu-budget-ms 45
```

Result:

```json
{
  "selected_frames": 3,
  "selected_ratio": 1.0,
  "hold_frames": 0,
  "warm_avg_yolo_ms": 31.259,
  "warm_p95_yolo_ms": 33.805,
  "warm_budget_ok_ratio": 1.0
}
```

Interpretation: Tap / anchor initialization plus ReID is the right direction. The foreground green-shirt control target stays stable even when the per-frame detection rank changes. This is the candidate mobile fallback policy:

```text
MediaPipe primary tracking
-> if target lost / confused, run YOLO burst on 1-3 frames
-> initialize or relocalize from tap / active anchor / best ReID score
-> return to MediaPipe ROI tracking
```

## Lunge First 60 App Samples

Input video: `test_assets/videos/internet_public_mp4/lunge_forward_army.mp4`

Command:

```powershell
python prototype\audit_pose_sample_timeline.py --video test_assets\videos\internet_public_mp4\lunge_forward_army.mp4 --label lunge_forward_army_first60_video_n1 --max-samples 60 --every-ms 200 --num-poses 1 --mode video
```

Result:

```json
{
  "samples": 60,
  "pose_hits": 56,
  "renderable_hits": 54,
  "first_pose_sample": 1,
  "first_renderable_sample": 1,
  "missing_samples": [0, 34, 42, 44],
  "non_renderable_samples": [33, 43]
}
```

Interpretation: the first 60 app samples are not globally missing pose. Local MediaPipe detects pose from sample 1, which is 200 ms. The missing skeleton seen on phone is therefore more likely caused by render / judgment gating, frame sampling state, or review overlay state, not by MediaPipe being unable to detect the person for the whole first 60 samples.

## Mobile Performance Policy

Recommended phone policy:

- Keep MediaPipe as the primary per-frame / every-other-frame path.
- Use YOLO only as a burst fallback when subject tracking is lost or multi-person confusion is detected.
- Use 640 input when the target is partially occluded or tightly overlapping another person, but still require a clean target-profile gate.
- Use 480 input only when Pixel profiling confirms it is stable enough for the current target and scene.
- 320 is faster but too unstable in this sample.
- Keep YOLO burst to 1-3 frames with cooldown.
- Never use YOLO detection rank as identity. Use tap / active target anchor / ReID state.
- If no clean target proposal exists, hold or abstain.
- If ReID margin is low, hold or abstain instead of switching target.
