# GemmaFit Submission Package Checklist

Use this file as the final submission staging checklist. Deadline in local
rules file: 2026-05-19 07:59 GMT+8.

## 1. Kaggle Writeup

Primary file:

- `docs/writeup_draft.md`

Status:

- Architecture aligned to 2026-05-16 P0 stack.
- Official E2B is the stated primary LiteRT model path.
- MotionZip is framed as task-preserving evidence compression.
- v5 fine-tune and vision sidecar are optional/future, not P0 dependencies.
- Non-clinical and forbidden-claim boundaries are explicit.

Before submission:

- Paste into Kaggle report editor.
- Confirm final Kaggle word count is under 1,500 words.
- Add final demo video URL and public repo URL if Kaggle form asks inside the
  report body.

## 2. Public Video

Target:

- Public YouTube video, 3 minutes or less.

Suggested story order:

1. Problem: single-camera coaching often overclaims.
2. Senior scenario: chair sit-to-stand / supported squat / balance hold.
3. Show trust behavior: judged evidence plus visible "cannot judge" boundary.
4. Show MotionZip / local Gemma summary: compact evidence -> E2B -> validated
   care log.
5. Show offline/privacy boundary: no raw video memory, no clinical claims.

Supporting scripts:

- `docs/scripts/gemmafit_evidence_intro_voiceover_en.md`
- `docs/scripts/senior_demo_movement_commands_zh.md`
- `docs/scripts/senior_demo_safe_error_variants_zh.md`

## 3. Public Code Repository

Required repo evidence:

- Android app source in `app/`.
- Prototype and validation scripts in `prototype/`.
- Native/Kotlin deterministic motion modules in `native/` and `app/`.
- Design docs in `docs/design/`.
- Benchmark registry in `docs/benchmark/README.md`.
- Submission writeup in `docs/writeup_draft.md`.

Do not publish:

- API keys.
- Private raw videos.
- Model binaries unless explicitly intended and allowed.
- Raw personal landmarks or biometric identity data.

## 4. Live Demo / APK

Deliverable options:

- APK upload/download link.
- GitHub release artifact.
- Kaggle dataset artifact if allowed by the competition flow.

Demo path to verify:

1. App launches on Pixel.
2. Model readiness shows official E2B or deterministic fallback status.
3. Senior demo route opens.
4. Sit-to-stand or supported squat shows deterministic live feedback.
5. Summary/export path shows backend, timing, fallback status, and evidence refs.
6. Unsupported medical/fall-risk question is refused or bounded.

## 5. Media Gallery

Candidate assets:

- `docs/assets/gemmafit_cover_1920x1080.png`
- `docs/assets/gemmafit_cover_deadlift_1920x1080.png`
- `docs/assets/gemmafit_social_1200x630.png`
- `docs/assets/gemmafit_phone_1080x1920.png`
- `docs/assets/video/`

Cover image should communicate:

- Offline motion coach.
- Evidence / trust / refusal boundary.
- Senior-friendly interaction.
- Local Gemma / LiteRT if shown textually.

## 6. Evidence To Cite

Canonical current artifacts:

- `docs/benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json`
- `docs/benchmark/litert_prompt_stream_dev_2_warm_official_2026-05-16/summary.json`
- `docs/benchmark/motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json`
- `docs/benchmark/layer2_senior_activity_ab_2026-05-16/README.md`
- `docs/benchmark/rgba_pipeline_mobile_default_optimized_2026-05-16/summary.json`
- `docs/benchmark/edge_gallery_official_e2b_litert_smoke_2026-05-15.md`

## 7. Claims To Avoid

Do not submit wording that says or implies:

- medical diagnosis,
- fall-risk prediction,
- sarcopenia detection,
- rehabilitation prescription,
- cognitive decline or dementia scoring,
- precise joint force, GRF, or EMG,
- muscle activation percentage,
- MotionZip is lossless video compression,
- Gemma sees every frame in the live path.

