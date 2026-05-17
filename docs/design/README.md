# Design Docs Index

This folder contains architecture and product-contract documents. For current
P0 decisions, start with `project_architecture_and_technical_highlights.md` and
then open the focused spec for the subsystem you are changing.

## Current Architecture

| Document | Use For |
| --- | --- |
| `project_architecture_and_technical_highlights.md` | One-page map of the current P0 architecture, runtime split, model role, evidence, readiness, and source docs. |
| `official_e2b_motionzip_runtime_architecture.md` | Official `Gemma-4-E2B-it` LiteRT runtime, MotionZip prompt contract, streaming, constrained-output caveats, and demo/runtime gates. |
| `realtime_person_detection_and_finetune_plan.md` | Camera/pose tracking, subject lock, judgeability, scheduler placement, and low-frequency E2B policy. |
| `layer2_senior_activity_model.md` | Senior-only Layer 2 activity/phase/event/judgeability model and recorder plan. |
| `model_invocation_scheduler.md` | Scheduler contract: when to call, skip, defer, or fallback. |
| `motionzip_v4_temporal_evidence_compression.md` | MotionZip compression policy and preserved evidence. |

## Diagrams

| File | Purpose |
| --- | --- |
| `gemmafit_current_architecture.drawio` | Current architecture diagram. |
| `gemmafit_current_flow.drawio` | Current flow diagram, including video summary quality cues and TTS rate limiting. |
| `gemmafit_realtime_evidence_architecture.drawio` | Realtime evidence architecture. |
| `gemmafit_realtime_evidence_architecture_en.drawio` | English realtime evidence architecture. |

## UI / Product Notes

| File | Purpose |
| --- | --- |
| `01_design_system.md` | Visual and interaction design system. |
| `02_main_screen.md` | Main screen design. |
| `03_onboarding.md` | Onboarding design. |
| `04_training_summary.md` | Training summary design. |

## Current Baseline Decisions

- Official `Gemma-4-E2B-it` LiteRT-LM is the P0 model baseline.
- GemmaFit v5 and FunctionGemma are optional quality/latency layers, not
  deadline dependencies.
- Live frames do not call E2B.
- `Senior Layer 2`, `ActivityContextTracker`, and `SeniorInteractionPolicy`
  are deterministic runtime layers.
- MotionZip feeds compact evidence to E2B; it does not change Gemma itself.
- Video summaries add `quality_cues` from rep traces so E2B can choose one
  concrete observation/focus without seeing raw video or every frame.
- TTS cadence is controlled by app-side `VoiceCuePolicy` cooldowns and rolling
  budgets, not by model output frequency.
- Native LiteRT tool-call emission is not proven for the official artifact, so
  Android parser/validator/fallback remains mandatory.
- `llama.cpp` vision sidecar is P3 benchmark-only unless new Pixel evidence
  shows it can run within memory and thermal budget.
