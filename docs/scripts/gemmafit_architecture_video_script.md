# GemmaFit 3-Minute Architecture Video Script

Purpose: Kaggle Gemma 4 Impact Challenge demo video / architecture explainer.

Primary story: Taiwan is becoming super-aged, and strength decline is a daily movement-safety problem. GemmaFit does not diagnose sarcopenia or predict injury. It provides offline, evidence-bounded movement-quality feedback on Pixel 8 Pro, and it abstains when the camera view cannot support a judgment.

Recommended Canva format: 16:9 video, 1920 x 1080, 10 pages/scenes.

Recommended voiceover language: English, with short English on-screen text. Chinese notes below are production guidance.

---

## Claim Guardrails

Use these terms:

- movement-quality feedback
- pose-based estimate
- camera-limited observation
- cannot judge from this view
- strength decline support
- senior movement support
- structured summary / caregiver-safe export

Avoid these terms:

- sarcopenia diagnosis
- fall-risk score
- injury prediction
- rehabilitation prescription
- medical-grade assessment
- muscle activation percentage
- force / GRF / EMG claims

Safe positioning sentence:

> GemmaFit is not a sarcopenia diagnosis tool. It is an offline movement-quality coach that gives feedback only when pose evidence is usable.

---

## Timeline Overview

| Scene | Time | Role |
| --- | ---: | --- |
| 1 | 0:00-0:12 | Taiwan senior context |
| 2 | 0:12-0:26 | Daily movement challenge |
| 3 | 0:26-0:42 | Product promise and safety boundary |
| 4 | 0:42-1:02 | Live deterministic safety path |
| 5 | 1:02-1:24 | Cannot-judge / abstain behavior |
| 6 | 1:24-1:44 | Senior Layer 2 temporal interpreter |
| 7 | 1:44-2:06 | MotionZip compression |
| 8 | 2:06-2:28 | Gemma 4 LiteRT local summary path |
| 9 | 2:28-2:46 | Android validator and memory/export boundary |
| 10 | 2:46-3:00 | Proof matrix and closing |

---

## Canva Page Storyboard

### Page 1 - Taiwan Is Super-Aged

Time: 0:00-0:12

Layout:

- Full-screen Taiwan / home-living visual.
- Large left-aligned headline.
- Small source label in bottom-left if exact statistic is shown.

On-screen text:

```text
Taiwan is now a super-aged society.
1 in 5 people is over 65.
```

Production note:

- Use a calm home setting, not a hospital visual.
- If the exact "1 in 5" line is used, add a small source label such as `Taiwan MOI / 2025`.

Voiceover:

```text
Taiwan is now a super-aged society. For many older adults, strength decline is not an abstract health topic. It shows up in daily movement.
```

---

### Page 2 - Daily Movement Is the Problem

Time: 0:12-0:26

Layout:

- Three horizontal panels: stand from chair, supported squat, balance hold.
- Add simple line icons above each panel: chair rise, squat, balance.
- Keep icons Material-style / simple line glyphs, no emoji.

On-screen text:

```text
Standing up.
Squatting.
Balancing.

Small daily movements need safer feedback.
```

Voiceover:

```text
Standing from a chair, lowering into a supported squat, or holding balance can all become safety-sensitive moments during home exercise.
```

---

### Page 3 - The Boundary

Time: 0:26-0:42

Layout:

- Left: Pixel 8 Pro camera HUD / app recording.
- Right: dark evidence card.
- Use green for allowed feedback and orange for camera-limited cases.

On-screen text:

```text
Not a diagnosis engine.

Movement quality only.
Pose-based estimate only.
Cannot judge when evidence is limited.
```

Voiceover:

```text
GemmaFit is not a sarcopenia diagnosis tool. It is an offline movement-quality coach that only gives feedback when the camera evidence is usable.
```

Evidence anchor:

- `implementation_plan.md`: non-diagnostic movement-quality boundary.

---

### Page 4 - Live Safety Path

Time: 0:42-1:02

Layout:

- Animated architecture strip across the center:
  `CameraX -> MediaPipe Pose -> Confidence Gate -> Deterministic Safety Rules -> HUD`
- Under the strip, add a small callout:
  `LIVE_FRAME = deterministic only`

On-screen text:

```text
Live safety verdicts do not call Gemma.

LIVE_FRAME:
SKIP_DETERMINISTIC
build_panel = false
call_backend = false
```

Voiceover:

```text
The live path is deliberately conservative. Camera frames go through pose detection and confidence gates first. Live safety verdicts stay deterministic; Gemma is not called on every frame.
```

Evidence anchor:

- `docs/benchmark/live_safety_contract_report_2026-05-16/report.md`
- Key status: `PASS`
- Raw proof: `model_invocation_smoke`

---

### Page 5 - It Abstains Instead of Guessing

Time: 1:02-1:24

Layout:

- Three equal columns:
  1. no person
  2. occlusion
  3. low visibility
- Each column gets a small status chip:
  `NO PERSON`, `VIEW LIMITED`, `LOW CONFIDENCE`

On-screen text:

```text
When the view is not usable,
GemmaFit does not guess.

No person: pose hit 0%
Occlusion: no immediate AI cue
Low visibility: gated
```

Voiceover:

```text
When no person is visible, when the body is occluded, or when landmark visibility is low, GemmaFit abstains or monitors instead of producing an unsupported safety judgment.
```

Evidence anchor:

- `docs/benchmark/person_recovery_yolo_fallback_report_2026-05-16/report.md`
- Key status: `PASS_WITH_GAP`
- Suggested raw clips:
  - `docs/benchmark/local_validation_run_2026-05-17/raw/no_person_blank_3s.mp4`
  - `docs/benchmark/local_validation_run_2026-05-17/raw/video_realtime_smoke_senior_occluded_720p.json`
  - `docs/benchmark/local_validation_run_2026-05-17/raw/video_realtime_smoke_senior_chair_stand_cdc_phonewin_t004.mp4.json`

Production note:

- Show the gap honestly if there is room: `Multi-person device replay still needs final capture`.
- Do not over-focus on the gap in the main video; put it in tiny text.

---

### Page 6 - Senior Layer 2

Time: 1:24-1:44

Layout:

- Top: senior chair/squat replay clip.
- Bottom: temporal lane with phase chips:
  `standing -> descending -> seated_low -> rising -> standing`
- Use orange for monitor-only and blue for low-confidence support states.

On-screen text:

```text
Senior Layer 2 turns motion into bounded events.

chair_sit_to_stand
supported_squat
balance_hold
monitor_only when unsupported
```

Voiceover:

```text
For senior movement, Layer 2 interprets short motion windows into bounded events such as chair sit-to-stand, supported squat, and balance hold. Unsupported movements are demoted to monitor-only.
```

Evidence anchor:

- `docs/benchmark/senior_layer2_video_replay_report_2026-05-16/report.md`
- Key status: `PASS_WITH_VIDEO_SCOPE_NOTE`
- Key proof: `layer2_smoke.success = True`

---

### Page 7 - MotionZip

Time: 1:44-2:06

Layout:

- Left: dense frame timeline with many small frame ticks.
- Center: `Motion Feature Window` and `Layer 2 Interpreter`.
- Right: compact `MotionZip` packet card.

On-screen text:

```text
MotionZip is task-preserving evidence compression.

It keeps:
angle extrema
confidence windows
event boundaries
judgeability
```

Voiceover:

```text
Instead of sending raw video or every landmark frame to the model, GemmaFit compresses motion into a compact MotionZip packet. It keeps the evidence needed for explanation while reducing noise and hallucination risk.
```

Evidence anchor:

- MotionZip benchmark docs.
- Stable wording: task-preserving evidence compression, not lossless video compression.

Production note:

- Do not say MotionZip is mathematically lossless.
- Do not show raw video being uploaded to cloud.

---

### Page 8 - Local Gemma 4 LiteRT

Time: 2:06-2:28

Layout:

- Pixel 8 Pro device visual.
- Chip card labeled `Gemma 4 E2B · LiteRT · Local`.
- Right-side runtime proof card.

On-screen text:

```text
Local summary and explanation path.

Pixel 8 Pro
LiteRT readiness: pass
100-run anchor: 100 / 100

Not used for live frame verdicts.
```

Voiceover:

```text
Gemma is used as a local explanation and summary layer, not as the live safety judge. On Pixel 8 Pro, the LiteRT path passed readiness, prewarm, prompt inference, and a 100-run stability anchor.
```

Evidence anchor:

- `docs/benchmark/litert_runtime_stability_report_2026-05-16/report.md`
- Key status: `PASS_WITH_CAVEAT`
- 100-run anchor: `100/100`

Caveat line:

```text
Caveat: constrained-decoding proof is not claimed from the single run.
```

---

### Page 9 - Validator + Memory Boundary

Time: 2:28-2:46

Layout:

- Left: JSON output from model.
- Center: `Android Validator` gate.
- Right: evidence card + caregiver-safe summary.
- Add red blocked tags for forbidden claim types.

On-screen text:

```text
Every output is checked.

Blocked:
diagnosis
fall-risk prediction
injury claims
force / EMG claims
raw-video memory
```

Voiceover:

```text
Every model output is validated on Android before it reaches the user. Memory and caregiver exports use structured summaries and trends, not raw video or medical claims.
```

Evidence anchor:

- `docs/benchmark/memory_export_boundary_report_2026-05-16/report.md`
- Key status: `PASS_WITH_RUNTIME_STATE_GAP`
- Unit tests: `MemoryWritePolicyTest`, `CaregiverExportBuilderTest`, `SeniorCareLogRendererTest`

Gap line:

```text
Runtime export state still needs one final real-session capture.
```

---

### Page 10 - Proof Matrix Close

Time: 2:46-3:00

Layout:

- Full-screen `validation_matrix.svg`.
- Add closing title at the top.
- Add GitHub / Kaggle / Pixel 8 Pro local validation footer.

On-screen text:

```text
Evidence-bounded AI for safer home movement.

Validated locally on Pixel 8 Pro.
Built for Kaggle Gemma 4 Impact Challenge.
```

Voiceover:

```text
GemmaFit combines deterministic motion safety, local Gemma summaries, and explicit abstention. The result is not a diagnosis engine, but a safer offline coach for aging-at-home movement support.
```

Evidence anchor:

- `docs/benchmark/local_validation_run_2026-05-17/validation_matrix.svg`
- Raw evidence: `docs/benchmark/local_validation_run_2026-05-17/raw/`

---

## Full Voiceover Draft

Target length: about 410-440 English words.

```text
Taiwan is now a super-aged society. For many older adults, strength decline is not an abstract health topic. It shows up in daily movement: standing from a chair, lowering into a supported squat, or holding balance during home exercise.

GemmaFit is not a sarcopenia diagnosis tool. It is an offline movement-quality coach that only gives feedback when the camera evidence is usable.

The live path is deliberately conservative. Camera frames go through pose detection and confidence gates first. Live safety verdicts stay deterministic; Gemma is not called on every frame. In our local validation, LIVE_FRAME stays on the deterministic path, with no panel built and no backend call.

When no person is visible, when the body is occluded, or when landmark visibility is low, GemmaFit abstains or monitors instead of producing an unsupported safety judgment. This is the core safety contract: if the camera cannot support the conclusion, the app says so.

For senior movement, Layer 2 interprets short motion windows into bounded events such as chair sit-to-stand, supported squat, and balance hold. Unsupported movements are demoted to monitor-only, keeping the feedback inside movement-quality language.

To explain a session efficiently, GemmaFit uses MotionZip: task-preserving evidence compression. Instead of sending raw video or every landmark frame to the model, it keeps angle extrema, confidence windows, event boundaries, and judgeability. This gives the model compact evidence without turning it into a live video judge.

Gemma runs locally as an explanation and summary layer through LiteRT on Pixel 8 Pro. The runtime path passed readiness, prewarm, prompt inference, and a one hundred run stability anchor, with caveats documented.

Every model output is checked by the Android validator before it reaches the user. Diagnosis, fall-risk prediction, injury claims, force claims, EMG claims, and raw-video memory are outside the product boundary.

GemmaFit combines deterministic motion safety, local Gemma summaries, and explicit abstention. The result is not a diagnosis engine, but a safer offline coach for aging-at-home movement support.
```

---

## Canva Build Checklist

1. Create or duplicate a 16:9 video design with 10 pages.
2. Use dark HUD background throughout: near-black / charcoal.
3. Use status tokens consistently:
   - OK / PASS: `#00E676`
   - Monitor / caveat: `#448AFF`
   - Warning / limited view: `#FF9100`
   - Blocked / forbidden claim: `#FF1744`
4. Keep all icons line-based and consistent. Avoid emoji.
5. Add page durations to match the storyboard timings.
6. Import `docs/benchmark/local_validation_run_2026-05-17/validation_matrix.svg` for the closing proof scene.
7. Use real Pixel 8 Pro screen recordings where possible for Pages 3-6.
8. Add tiny caveat labels on Pages 5, 8, and 9 instead of hiding known gaps.
9. Export at 1080p and keep final length under 3:00.

---

## Asset Checklist

Local artifacts to use or screen-capture:

- `docs/benchmark/local_validation_run_2026-05-17/validation_matrix.svg`
- `docs/benchmark/live_safety_contract_report_2026-05-16/report.md`
- `docs/benchmark/person_recovery_yolo_fallback_report_2026-05-16/report.md`
- `docs/benchmark/litert_runtime_stability_report_2026-05-16/report.md`
- `docs/benchmark/memory_export_boundary_report_2026-05-16/report.md`
- `docs/benchmark/senior_layer2_video_replay_report_2026-05-16/report.md`
- `docs/benchmark/local_validation_run_2026-05-17/raw/no_person_blank_3s.mp4`
- `docs/benchmark/local_validation_run_2026-05-17/raw/video_realtime_smoke_senior_occluded_720p.json`
- `docs/benchmark/local_validation_run_2026-05-17/raw/video_realtime_smoke_senior_chair_stand_cdc_phonewin_t004.mp4.json`
- `docs/benchmark/local_validation_run_2026-05-17/raw/video_realtime_smoke_senior_chair_stand_cdc_phonewin_t062.mp4.json`
- `docs/benchmark/local_validation_run_2026-05-17/raw/video_realtime_smoke_senior_balance_4stage_cdc_phonewin_t060.mp4.json`

Still needed for the strongest final edit:

- One clear chair sit-to-stand or supported squat screen recording from the app.
- One low-confidence / cannot-judge screen recording.
- One real caregiver export runtime capture, if the final video wants to claim runtime export proof rather than unit-test proof.
- Optional multi-person replay capture to close the current `PASS_WITH_GAP` note.
