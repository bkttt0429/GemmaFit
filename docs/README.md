# GemmaFit Docs Index

This folder is the project-facing documentation hub. The authoritative product
and architecture plan remains `../implementation_plan.md`; this docs folder
contains supporting design specs, benchmark evidence, writeups, scripts,
references, and media assets.

## Start Here

| Need | Read |
| --- | --- |
| Current architecture and technical story | `design/project_architecture_and_technical_highlights.md` |
| Official E2B + MotionZip runtime | `design/official_e2b_motionzip_runtime_architecture.md` |
| Realtime person tracking and scheduling policy | `design/realtime_person_detection_and_finetune_plan.md` |
| Senior Layer 2 activity interpreter | `design/layer2_senior_activity_model.md` |
| Benchmark evidence registry | `benchmark/README.md` |
| Kaggle writeup draft | `writeup_draft.md` |
| Chinese writeup draft | `writeup_draft_zh.md` |
| Submission package checklist | `submission_package.md` |
| Competition constraints | `competition_rules.md` |

## Folder Map

| Folder | Purpose | Notes |
| --- | --- | --- |
| `design/` | Architecture specs, product contracts, draw.io diagrams, UI design notes. | Use `design/README.md` as the index. |
| `benchmark/` | Reproducible results, Pixel smoke outputs, model performance, MotionZip proof artifacts. | Prefer timestamped run folders with `summary.json` and `report.md`. |
| `assets/` | Submission/media gallery images, video source assets, generated preview images. | Source files and generated outputs are separated by subfolder. |
| `papers/` | Literature reviews, research extraction notes, claim-support references. | Claim boundaries must stay non-clinical. |
| `scripts/` | Demo narration, movement-command scripts, video voiceover copy. | Keep script language in the filename when possible. |

## Root-Level Files

| File | Role |
| --- | --- |
| `writeup_draft.md` | Main English submission/writeup draft. |
| `writeup_draft_zh.md` | Chinese working draft. |
| `submission_package.md` | Final submission checklist for writeup, video, repo, live demo, and media gallery. |
| `competition_rules.md` | Competition requirements and constraints. |
| `android_debug_api.md` | Debug API reference. |

Root should stay small. New architecture notes go in `design/`, benchmark
outputs go in `benchmark/`, media goes in `assets/`, and demo narration goes in
`scripts/`.

## Current P0 Evidence To Cite

| Evidence | Canonical artifact |
| --- | --- |
| Official E2B JSON reliability | `benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json` |
| Official E2B streaming first token | `benchmark/litert_prompt_stream_dev_2_warm_official_2026-05-16/summary.json` |
| MotionZip dense-vs-compressed equivalence | `benchmark/motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json` |
| Layer 2 senior activity A/B | `benchmark/layer2_senior_activity_ab_2026-05-16/README.md` |
| RGBA/YUV camera pipeline audit | `benchmark/rgba_pipeline_mobile_default_optimized_2026-05-16/summary.json` |
| Official E2B Edge Gallery baseline | `benchmark/edge_gallery_official_e2b_litert_smoke_2026-05-15.md` |

## Documentation Rules

- Keep product claims inside movement-quality support. Do not write diagnosis,
  fall-risk prediction, sarcopenia detection, rehabilitation prescription,
  force/GRF, EMG, or clinical-improvement claims.
- MotionZip should be described as task-preserving temporal evidence
  compression, not lossless video compression.
- Live coaching remains deterministic. E2B is low-frequency and bounded by
  scheduler, parser, validator, evidence refs, forbidden-claim checks, and
  deterministic fallback.
- Benchmark folders should include a short `report.md` or `README.md` plus
  machine-readable `summary.json` when possible.
- Do not store raw video, raw full landmarks, biometric identity data, API
  keys, or model binaries in docs.
