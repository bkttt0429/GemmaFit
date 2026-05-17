# Architecture Validation Status Report

Date: 2026-05-16
Updated: 2026-05-17 local validation run

## Conclusion

The architecture has moved from "planned proof reports" to a proof-backed demo
package for the highest-risk claims. The core story is now:

```text
MediaPipe / fallback person recovery / deterministic motion evidence
-> Trust Matrix + Evidence Card
-> low-frequency Local Gemma explanation and summary
```

The live safety path remains deterministic. Gemma and multimodal evidence are
sidecars for explanation, user questions, session summary, and caregiver export;
they do not create or change `WARNING`, `MONITOR`, or `LOW_CONFIDENCE`
verdicts.

Latest visual proof matrix:
`docs/benchmark/local_validation_run_2026-05-17/validation_matrix.svg`

## Updated Validation Matrix

| Architecture contract | Status after 2026-05-17 run | Evidence | Remaining gap |
| --- | --- | --- | --- |
| Deterministic live safety path contract | `closed_for_demo` | `docs/benchmark/live_safety_contract_report_2026-05-16/report.md` proves `LIVE_FRAME` returns `SKIP_DETERMINISTIC` and `call_backend=false`, including a multimodal-enabled debug case. | Longer live-camera trace with call counters would strengthen the proof, but the demo claim is now supported. |
| Pose ownership, person recovery, and abstain | `closed_with_gap` | `docs/benchmark/person_recovery_yolo_fallback_report_2026-05-16/report.md` covers no-person, occlusion, low visibility, predicted tracking, and subject policy tests. | Fresh multi-person device replay is still needed for a stronger wrong-person proof. |
| Official Gemma-4-E2B LiteRT runtime | `closed_with_caveat` | `docs/benchmark/litert_runtime_stability_report_2026-05-16/report.md` covers readiness, prewarm, prompt inference, and the 100-run official JSON anchor. | The single current constrained endpoint run returned `constrained_decoding=false`; app-side parser/validator remains mandatory. |
| Structured memory and caregiver export boundary | `closed_with_runtime_gap` | `docs/benchmark/memory_export_boundary_report_2026-05-16/report.md` covers memory/export unit policy and raw-output forbidden-claim scanning. | A real on-device caregiver export payload still needs capture. |
| Senior Layer 2 replay | `closed_with_scope_note` | `docs/benchmark/senior_layer2_video_replay_report_2026-05-16/report.md` covers Layer 2 smoke plus chair/balance/occlusion replay clips. | Some replay clips prove conservative gating more than full rep recognition; use one clear clip plus one abstain clip in the video. |
| MotionZip compact evidence / compression path | `strong` | `docs/benchmark/motionzip_model_equivalence/report.md`, `docs/benchmark/motionzip_sparse_understanding/report.md`, and `docs/benchmark/multimodal_image_compression_reproof_2026-05-16/report.md`. | Keep wording as task-preserving evidence compression, not lossless video compression. |
| CameraX RGBA/YUV image pipeline | `partial` | `docs/benchmark/rgba_pipeline_mobile_default_optimized_2026-05-16/report.md` and related rotation/audit reports. | 10-30 minute thermal/memory run still useful. |
| Native biomechanics thresholds and Trust Matrix calibration | `partial` | Native/unit tests and implementation plan describe the thresholds. | Consolidated threshold calibration report still missing. |
| Low-frequency multimodal evidence panel | `partial_but_gated` | Selector, packet, builder, validator, scheduler tests, and sidecar compression reports exist. | Backend image/audio path is not a P0 dependency; do not claim always-on Gemma vision. |
| Senior UI, voice cue policy, and TTS cooldown | `partial` | Voice policy and senior parsing tests exist; Pixel demo screenshots exist. | Focused accessibility/device UX report still useful. |

## Architecture Tightening

The 2026-05-17 pass also tightened one trigger boundary:

- `LIVE_FRAME`: never builds a panel and never calls Gemma/multimodal backend.
- `SETUP_CHECK`: deterministic setup UI only in v1; no multimodal backend call.
- `REP_COMPLETED`: no default multimodal backend call; debug panel only.
- `WARNING_PERSISTED`: optional async sidecar after deterministic warning exists.
- `USER_QUESTION`, `SESSION_ENDED`, `CAREGIVER_EXPORT`: allowed low-frequency
  panel/backend triggers when device and backend gates pass.

## Claim Rules

Do claim:

- deterministic live safety gates before model explanation;
- local LiteRT summary/explanation with app-side parser, validator, and
  fallback;
- task-preserving MotionZip evidence compression;
- abstention when pose/person evidence is weak.

Do not claim:

- frame-by-frame Gemma or vision safety verdicting;
- clinical progress, fall-risk scoring, sarcopenia detection, force/load, EMG,
  heart-rate status, or medical diagnosis;
- raw-video, raw frame history, or full-landmark long-term memory.
