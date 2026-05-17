# GemmaFit — On-device Movement Coach That Knows Its Limits

> Kaggle Gemma 4 Good Hackathon submission.
> **Primary tracks/themes**: Main · Health & Sciences · Safety & Trust.
> **Special technology focus**: LiteRT / Google AI Edge.

## What GemmaFit Does

GemmaFit is an Android movement coach, validated on a Pixel 8 Pro, that runs
entirely on-device on Google AI Edge LiteRT + Gemma 4 E2B. It targets older
adults practicing chair sit-to-stand, supported squats, and standing balance at
home, plus beginners who want bounded, honest feedback. Live cues are grounded
in deterministic pose evidence; session summaries are written by Gemma 4 from a
compact evidence packet; every refusal is visible to the user.

The core claim is **honesty under uncertainty**: when the camera angle can't
support a judgment, the subject is partially out of frame, the activity is
ambiguous, or the user asks something outside our evidence boundary (fall
risk, diagnosis, joint force, muscle activation), the app refuses *visibly*
instead of hallucinating.

## Problem

Single-camera fitness apps routinely overclaim — knee valgus from side views,
"muscle activation %" without EMG, phone video packaged as clinical risk
scores. In senior care both failure modes are dangerous: false warnings stop
useful activity; false reassurance hides real uncertainty. On-device LLM
demos compound this by treating the model as the safety engine. **GemmaFit
inverts that: the model writes summaries; the deterministic stack guards
safety.**

## Technical Innovations

GemmaFit's contribution is six production-grade patterns for bounded local LLM
products, each backed by a runnable benchmark:

**1. MotionZip — task-preserving evidence compression.** Dense pose streams
compress into a compact JSON packet preserving event boundaries, angle
extrema, velocity peaks, confidence floors, low-confidence spans, and
unsupported-claim boundaries; raw frames, full landmark streams, ReID
embeddings, and clinical labels are dropped. The model reproduces **8 / 8
task-critical facts** vs. dense input with only **1.89 % peak-velocity
difference**. Product runs the compressed path; the dense comparison is a
regression gate.
*Validation*: [`motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/`](benchmark/motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json)

**2. ActivityContextTracker — pose-only temporal disambiguation.** A 5-state
machine (`UNKNOWN / CALIBRATING / LOCKED / SUSPECT_SWITCH / AMBIGUOUS`) scores
visually similar templates (chair sit-to-stand vs. supported squat) over a
temporal window using hip dwell, trunk lean, hand-vs-hip position, and phase
profile shape. Locks only on consecutive consistent reps; emits `AMBIGUOUS`
when scores are too close. **Replaces the need for a vision sidecar to "see
the chair" with ~zero inference cost** while staying honest.
*Validation*: [`senior_layer2_video_replay_report_2026-05-16/`](benchmark/senior_layer2_video_replay_report_2026-05-16/report.md), [`layer2_senior_activity_ab_2026-05-16/`](benchmark/layer2_senior_activity_ab_2026-05-16/)

**3. Multi-layer ModelInvocationScheduler — gate-first LLM dispatch.** Five
filtering layers ensure ≥99 % of pose frames never reach the LLM (rewrite
class → device/tracking/capability → trigger policy → in-flight dedup →
realtime fast-path that returns deterministic JSON without calling the
model). **A typical 5-minute session triggers exactly 1 real LLM call — the
session-end summary.** The model is a bounded writer, never the safety
engine.
*Validation*: [`live_safety_contract_report_2026-05-16/`](benchmark/live_safety_contract_report_2026-05-16/report.md)

**4. Visible capability contract + refusal-as-feature.** Every Evidence Card
surfaces explicit `can_judge` / `cannot_judge` chips. When asked fall risk,
diagnosis, joint force, EMG, or muscle activation %, the system fires
`refuse_unsupported_question` with a non-clinical alternative. A shared
`RefusalValidator` cross-checks forbidden terms in both English and zh-TW
defensively. **Refusal is a product feature, not a fallback.**
*Validation*: [`architecture_validation_gap_report_2026-05-16/`](benchmark/architecture_validation_gap_report_2026-05-16/report.md), [`memory_export_boundary_report_2026-05-16/`](benchmark/memory_export_boundary_report_2026-05-16/)

**5. Coach Narrative Packet — deterministic narrative substrate.** `RepRecord`
+ `RepTraceSummary` + `PersonalTraceEnvelope` compile into a structured
`rep_summaries` + `session_trend` + `baseline_comparison` packet with compact
enum + numeric encoding. The model gets rich per-rep substrate **without
expanding prompt tokens**, shifting output from "filled-form summary" to
"specific coach-like observation" with cited rep numbers.

**6. Mali-G715 hardened LiteRT-LM pipeline.** Chat template aligned to
official Gemma 4 (`<turn|>` closing markers, `<|think|>` thinking mode for
summaries), GPU → CPU auto-retry on JSON parse failure, tool-call args
capped at 4. **Directly addresses Mali-G715 field report (LiteRT-LM Issue
#2202)** where tool-call success degrades to 0 % at 4+ args without these
alignments. Achieved **100 / 100 JSON parse success across 100 runs**.
*Validation*: [`litert_prompt_smoke_constrained_100_official_2026-05-16/`](benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json), [`litert_runtime_stability_report_2026-05-16/`](benchmark/litert_runtime_stability_report_2026-05-16/)

## Architecture

```text
CameraX / Video
  → MediaPipe Pose + presence / subject / judgeability gates
  → derived motion features
  → Senior Layer 2 FSM + ActivityContextTracker
  → MotionFeatureWindow + MotionZip packet
  → SeniorInteractionPolicy
  → ModelInvocationScheduler (multi-layer gate)
  → official Gemma-4-E2B-it LiteRT streaming session
  → Android parser, validator, deterministic fill, fallback
  → UI, TTS, care log, caregiver export, debug receipt
```

Deterministic live loop computes pose evidence; MotionZip compresses; the
scheduler decides when (and whether) to invoke Gemma; Android validates +
fills any model output. Full design:
[`official_e2b_motionzip_runtime_architecture.md`](design/official_e2b_motionzip_runtime_architecture.md).

## Performance — Pixel 8 Pro, official Gemma-4-E2B-it LiteRT

| Metric | Result |
|---|---:|
| Model on-disk | 2.5 GB |
| GPU + CPU footprint | ~2.86 GB |
| Generation latency | avg 24.9s · p50 24.8s · p95 26.5s |
| JSON parse success (100 runs) | 100 / 100 |
| Streaming first-token time | 0.96s – 3.14s after start |
| MotionZip vs. dense equivalence | 8 / 8 key facts |
| Peak velocity preservation | 1.89 % difference |
| Camera image-path p95 | 10.2 ms / frame |

Four layered optimizations bring perceived end-to-end latency from **~88s to
~25s**: (a) app-launch background prewarm with thermal-status gating hides
the 9.7s engine init; (b) async streaming UI shows first token at ~3s instead
of 23s silence; (c) constrained-decoding spike enforces JSON schema when the
conversation API path passes smoke; (d) Compose `pose-overlay` violation
pulse is isolated to `Modifier.graphicsLayer { alpha = pulse }` so the static
skeleton recomposes at pose-update frequency (~15 fps) instead of 60 fps
during violations.
*Validation*: [`litert_prompt_stream_dev_2_warm_official_2026-05-16/`](benchmark/litert_prompt_stream_dev_2_warm_official_2026-05-16/summary.json), [`rgba_pipeline_audit/2026-05-16_pixel8pro/`](benchmark/rgba_pipeline_audit/2026-05-16_pixel8pro/summary.md), [`litert_model_perf_2026-05-16/`](benchmark/litert_model_perf_2026-05-16/)

## Why Official Gemma 4 E2B (over fine-tunes)

The product path uses Google's official `Gemma-4-E2B-it` LiteRT artifact. A
separately trained `gemmafit-v5-e2b-evidence-router` LoRA exists (debug
override) but ships as P1 — the official baseline already produces care-log
quality output from MotionZip, and v5 promotes only when eval gates (schema
fidelity, evidence-ref precision, latency, senior wording) demonstrably
exceed it. A `llama.cpp` vision sidecar (mmproj Q8 vs F16) was prototyped
but kept at P3 — Pixel memory pressure outweighed the marginal benefit, and
ActivityContextTracker covers the chair-vs-squat disambiguation that
motivated it.
*Validation*: [`gemma4_vision_mmproj_q8_vs_f16/`](benchmark/gemma4_vision_mmproj_q8_vs_f16/README.md)

## Bilingual Care Log

The same MotionZip packet feeds Gemma 4 with a locale-pinned instruction and
one-shot example, producing care-log wording in English or 繁體中文. UI
strings, TTS voice, cue catalog, and model output all align to the user's
choice. `RefusalValidator` enforces forbidden-claim terms in both languages
defensively, because the multilingual base leaks English under uncertainty.

## Safety & Trust

The trust layer is visible. Source badges (`Pose rules`, `Local Gemma`,
`Template fallback`, `Abstained`) sit next to every cue. Evidence Cards
split into *what I saw* / *judged* / *did NOT judge*. Debug receipts capture
backend, model hash, scheduler decision, fallback reason, evidence refs,
stream phase, first-token time, thermal status, and per-stage timings —
**every generated word is auditable**. Memory is structured and app-owned;
raw video, full landmark streams, free-form model memory, biometric
identity, medical labels, fall-risk / sarcopenia scores, force, GRF, and
EMG claims are hard-blocked with a forbidden-claim grep gate.

## Limitations

GemmaFit is not a medical device. It does not estimate joint force, GRF, EMG,
true muscle activation, injury / fall risk, dementia, sarcopenia,
rehabilitation progress, or clinical improvement. MotionZip is task-
preserving, not visually lossless. When evidence is insufficient the correct
behavior is to abstain, pause, request a setup change, or render a
non-clinical activity summary.

## Track Alignment

- **Main** — End-to-end working Android app: on-device Gemma 4 E2B writes
  care logs from app-owned evidence, with no cloud dependency.
- **Health & Sciences** — Movement coaching for older adults; explicit
  non-clinical boundary; `capability_contract` surfaces precisely what the
  system can and cannot judge.
- **Safety & Trust** — Refusal-as-feature; MotionZip 8 / 8 evidence audit
  gate; visible source badges; 100 / 100 JSON parse gate; deterministic
  fallback always available; structured app-owned memory with hard-blocked
  categories.
- **LiteRT / Google AI Edge** — Uses official `Gemma-4-E2B-it` LiteRT
  artifact with the documented Engine / Session lifecycle (prewarmed
  persistent session, streaming API, app-launch background warmup);
  Mali-G715 field-report compliance.

Secondary technology evidence: a `llama.cpp` vision sidecar was evaluated
against MotionZip with mmproj Q8 vs F16, then archived as research because
Pixel memory pressure made it unsuitable for the P0 phone runtime.

## Closing

GemmaFit's contribution is a practical pattern for trustworthy on-device AI:
**deterministic evidence first, compact auditable model context, local Gemma
only when useful, visible refusal when the camera can't support a claim**. The
result is an offline coach older adults can use without being misled — and a
reference architecture for bounded local LLM products.

---

### Validation Index

Full design and benchmark evidence is indexed in
[`docs/benchmark/README.md`](benchmark/README.md),
[`docs/benchmark/local_validation_run_2026-05-17/`](benchmark/local_validation_run_2026-05-17/README.md),
and [`docs/design/official_e2b_motionzip_runtime_architecture.md`](design/official_e2b_motionzip_runtime_architecture.md).
