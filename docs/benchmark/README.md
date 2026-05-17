# Benchmark Evidence Registry

This folder stores reproducible benchmark outputs and Pixel/device evidence.
The newest canonical artifacts should be cited from this registry instead of
older exploratory runs.

## Claim Scope

These artifacts can support:

- local model readiness and latency,
- JSON/schema reliability,
- evidence-ref validation behavior,
- MotionZip key-fact preservation,
- Layer 2 event/judgeability behavior,
- camera/image-pipeline timing,
- debug/demo readiness.

They do **not** validate clinical biomechanics thresholds, injury prediction,
rehabilitation efficacy, muscle activation, joint forces, GRF, fall-risk
scoring, sarcopenia detection, dementia screening, or clinical improvement.

## Canonical Current Evidence

| Topic | Current artifact | What it supports |
| --- | --- | --- |
| Official E2B baseline | `edge_gallery_official_e2b_litert_smoke_2026-05-15.md` | Official Edge Gallery `Gemma-4-E2B-it` LiteRT artifact presence, model size, initial GPU smoke, warm prompt feasibility. |
| Official E2B JSON gate | `litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json` | `100 / 100` endpoint success, generation success, and parseable model JSON under the official constrained smoke harness. |
| Official E2B streaming | `litert_prompt_stream_dev_2_warm_official_2026-05-16/summary.json` | Warm first-token timing on reused official E2B engine. |
| MotionZip model equivalence | `motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16/summary.json` | Dense-vs-compressed prompt equivalence for activity/state/event/velocity/confidence key checks. |
| MotionZip sparse understanding demo | `motionzip_sparse_understanding/report.md` | Visual/packet demonstration of sampled tracking and compact evidence delivery. |
| Layer 2 senior activity | `layer2_senior_activity_ab_2026-05-16/README.md` | Senior Layer 2 A/B outputs and Pixel debug smoke. |
| RGBA/YUV camera path | `rgba_pipeline_mobile_default_optimized_2026-05-16/summary.json` | Current live-camera image-pipeline timing and format audit. |
| LiteRT model performance | `litert_model_perf_2026-05-16/report.md` | Official vs v5 runtime observations and foreground prompt performance notes. |
| Pixel demo flow | `pixel_demo_flow_smoke_2026-05-16/` | UI screenshots, debug state, and demo-flow smoke artifacts. |
| Vision sidecar cost | `gemma4_vision_mmproj_q8_vs_f16/README.md` | F16/Q8 projector comparison and why vision sidecar remains P3. |

## Historical / Exploratory Runs

Keep these for traceability, but do not cite them as the current headline unless
the current canonical artifact is unavailable:

| Family | Notes |
| --- | --- |
| `ab_compare/`, `ab_compare_training_fmt/`, `baseline_e4b/`, `baseline_smoke/` | Early base-vs-fine-tuned GGUF function-calling tests. Useful as history, not current P0 evidence. |
| `litert_prompt_smoke_dev_*`, `litert_prompt_smoke_retry_*`, `litert_prompt_smoke_100_official_a_*` | Development runs before the current 100-run constrained gate. |
| `motionzip_equivalence_prompt_endpoint_dev_official/`, `hardened*` before `hardened4` | Iterations of the equivalence prompt endpoint; use `hardened4` as current. |
| `rgba_pipeline_*` older variants | Camera-pipeline A/B history; use `rgba_pipeline_mobile_default_optimized_2026-05-16` as current. |

## Benchmark Folder Convention

Preferred layout:

```text
topic_YYYY-MM-DD/
  summary.json      machine-readable headline numbers
  report.md         human-readable interpretation
  records.json      optional per-run records
  model_readiness.json / state.json / events.jsonl as needed
```

For small one-off benchmarks, a single dated `.md` file is acceptable.

## Current P0 Interpretation

- Official `Gemma-4-E2B-it` is sufficient for P0 summaries when the app owns
  schema prompting, parsing, evidence-ref validation, forbidden-claim rejection,
  deterministic fill, and fallback.
- Native LiteRT tool-call objects were not observed in the 100-run constrained
  official smoke, so constrained decoding should be described as smoke-safe but
  not native-tool proven.
- Streaming improves perceived latency only after prewarm. Full generation for
  the tested prompt still takes tens of seconds.
- MotionZip equivalence supports key-fact preservation for the tested task; it
  does not prove lossless video understanding.
