# Architecture + MotionZip Real Video Report

This artifact turns GemmaFit's current architecture into a reproducible report
video using a real local clip:

`test_assets/Real/800432825.004389.mp4`

## Outputs

| Artifact | Path |
| --- | --- |
| Report video | `docs/assets/video/gemmafit_architecture_motionzip_real_report.mp4` |
| Thumbnail | `docs/assets/video/gemmafit_architecture_motionzip_real_report_thumb.jpg` |
| Contact sheet | `docs/assets/video/gemmafit_architecture_motionzip_real_report_contact_sheet.jpg` |
| Vision scene anchor | `docs/benchmark/architecture_report_video_real_2026-05-17/vision_scene_anchor.jpg` |
| Vision MotionZip panel | `docs/benchmark/architecture_report_video_real_2026-05-17/vision_motionzip_panel.jpg` |
| MotionZip packet | `docs/benchmark/architecture_report_video_real_2026-05-17/motionzip_packet.json` |
| E2B prompt packet | `docs/benchmark/architecture_report_video_real_2026-05-17/motionzip_e2b_prompt.json` |
| Summary JSON | `docs/benchmark/architecture_report_video_real_2026-05-17/report_video_summary.json` |

Main evidence directories:

- `docs/benchmark`
- `docs/design`
- `docs/assets`

## What Was Actually Run

```text
real video -> MediaPipe landmarks -> angle / velocity proxies -> MotionZip packet -> report video
```

The report video uses the same numbered architecture labels throughout:

```text
1 Pose -> 2 Gates -> 3 Features -> 4 Layer 2 -> 5 MotionZip -> 6 Gemma -> 7 Validator
```

Later slides show an `Architecture context` chip so the viewer can map each
benchmark or visual panel back to the pipeline stage it proves.

Measured packet summary:

| Metric | Value |
| --- | ---: |
| Source frames | 525 |
| Pose samples | 88 |
| Sample stride | every 6 frames |
| MotionZip blocks | 3 |
| Output state | abstain |
| Low-visibility abstain blocks | 3 |
| Max angular velocity proxy | 43.721 deg/s |
| Source video size | 4.52 MB |
| MotionZip packet size | 5.9 KB |
| E2B prompt size | 7.7 KB |
| Packet vs source reduction | 784.96x |
| Prompt vs source reduction | 600.4x |

## Technical Highlights Shown In The Video

| Audience-facing claim | Evidence shown |
| --- | --- |
| Saves phone resources | `4.52 MB` video becomes `5.9 KB` MotionZip evidence, a `784.96x` reduction before Gemma. |
| Reduces waiting | The dense-vs-MotionZip equivalence run improves wall time from `69.3s` to `39.8s` (`~43%` less wall time). |
| Keeps the important facts | MotionZip preserved `8/8` expected key facts in the equivalence gate. This is an evidence-preservation claim, not a medical accuracy claim. |
| Keeps live safety deterministic | Live frames stay on MediaPipe + deterministic gates. Vision/Gemma can explain after evidence exists, but cannot override warnings, reps, or form score. |

## Proof Artifacts Used In The Video

| Audience signal | Key number | Why it matters | Path |
| --- | --- | --- | --- |
| 100-run reliability | 100/100 | Pixel parsed every local Gemma JSON result, so the summary contract is stable. | `docs/benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json` |
| Same key facts | 8/8 | Compressed MotionZip evidence matched the dense evidence checks. | `docs/benchmark/motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json` |
| Less waiting | ~43% | Dense evidence 69.3s -> MotionZip 39.8s in the equivalence run. | `docs/benchmark/motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json` |
| First text appears | 0.96s | Warm streaming can show coach-summary progress quickly. | `docs/benchmark/litert_prompt_stream_dev_2_warm_official_2026-05-16/summary.json` |
| Live safety stays local | PASS | Live frames stay deterministic; Gemma explains later, not every frame. | `docs/benchmark/live_safety_contract_report_2026-05-16/report.md` |
| Camera CPU known | ~0.3ms | RGBA saves conversion CPU; the main cost is still model generation. | `docs/benchmark/rgba_pipeline_audit/2026-05-16_pixel8pro/summary.md` |

## How The Judgment Works

This video intentionally shows the trust boundary:

1. MediaPipe extracts pose samples from the real clip.
2. Deterministic code computes angle extrema, velocity proxy, and confidence
   floor.
3. MotionZip selects the highest-information windows.
4. Each selected window has low keypoint visibility, so the policy state is
   `abstain`.
5. The correct user-facing behavior is a camera/review cue, not a hard posture
   verdict.

## Vision Sidecar Strategy

The updated report video includes GemmaFit's low-frequency Vision sidecar
contract. Vision is not part of the live safety verdict. It is only allowed to
enrich context after deterministic evidence exists.

Allowed backend triggers:

```text
SESSION_ENDED
USER_QUESTION
CAREGIVER_EXPORT
WARNING_PERSISTED optional async explanation
```

Blocked triggers:

```text
LIVE_FRAME
SETUP_CHECK
```

Budget gates skip or downgrade the call when the device is under pressure:

```text
low_battery | high_thermal_load | model_disabled | sidecar_already_in_flight
```

The two-image input strategy shown in the video is:

| Image | Purpose | Path |
| --- | --- | --- |
| 1. Scene anchor | environment, support object, person visibility | `docs/benchmark/architecture_report_video_real_2026-05-17/vision_scene_anchor.jpg` |
| 2. MotionZip panel | selected motion windows plus compact deterministic facts | `docs/benchmark/architecture_report_video_real_2026-05-17/vision_motionzip_panel.jpg` |

Pixel-side model output shown in the video:

```text
env=outdoor;support=chair;person=visible;overlay_readable=true;limited=false
```

Real example shown in the video:

| Stage | What the app knows |
| --- | --- |
| Before Vision | Pose evidence says low visibility / abstain, but scene support and environment are not explicit. |
| After Vision | `env=outdoor`, `support=chair`, `person=visible`, `overlay_readable=true`, `limited=false`. |
| What improves | Summary wording and review guidance can mention the support object and camera context. |
| What does not change | Deterministic safety verdict, warning state, rep count, and form score. |

Normalized `SessionVisualContext`:

```json
{
  "env": "outdoor",
  "support": "chair",
  "person": "visible",
  "overlay_readable": true,
  "limited": false,
  "source": "litert_vision_sidecar",
  "evidence_refs": [
    "visual_context.env",
    "visual_context.support",
    "visual_context.person",
    "visual_context.overlay_readable",
    "visual_context.limited"
  ]
}
```

The Vision result may help summary wording, but it cannot override
deterministic safety state, warning state, rep count, or form score.

## MotionZip Blocks

| # | Source frames | Time range ms | State | Confidence floor | Peak velocity deg/s | Reason |
| ---: | --- | --- | --- | ---: | ---: | --- |
| 1 | 18-66 | 600-2201 | abstain | 0.4334 | 42.11 | low_keypoint_visibility |
| 2 | 168-216 | 5602-7203 | abstain | 0.3369 | 43.721 | low_keypoint_visibility |
| 3 | 306-354 | 10204-11804 | abstain | 0.3239 | 37.895 | low_keypoint_visibility |

## Rebuild

```powershell
python prototype\build_motionzip_packet_from_video.py --video test_assets\Real\800432825.004389.mp4 --activity-hint chair_supported_movement --label architecture_report_real_800432825 --sample-every 6 --window-ms 1600 --max-blocks 3 --min-event-velocity 20 --min-frame-gap 60 --reuse-csv --out docs\benchmark\architecture_report_video_real_2026-05-17\motionzip_packet.json --prompt-out docs\benchmark\architecture_report_video_real_2026-05-17\motionzip_e2b_prompt.json
python docs\assets\video_sources\generate_architecture_motionzip_real_video.py
```

## Claim Boundary

This artifact supports an architecture/demo claim: GemmaFit compresses
task-relevant pose evidence before local Gemma summary generation, and it
abstains when evidence is not strong enough. It does not validate clinical
diagnosis, injury prediction, force, GRF, EMG, muscle activation, fall-risk
scoring, or rehabilitation outcomes.
