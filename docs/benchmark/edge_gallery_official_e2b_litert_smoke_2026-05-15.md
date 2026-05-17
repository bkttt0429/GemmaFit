# Edge Gallery Official Gemma 4 E2B LiteRT Smoke - 2026-05-15

本次測試目標是驗證手機 Google AI Edge Gallery 已下載的官方
`Gemma-4-E2B-it` LiteRT-LM artifact，能否在 GemmaFit 現有 debug
pipeline 內作為官方 baseline 使用。

## Device Model Source

Edge Gallery package:

```text
com.google.ai.edge.gallery
versionName: 1.0.12
```

Edge Gallery 下載檔：

```text
/sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_4_E2B_it/20260325/gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm
size: 2,538,766,336 bytes
```

Edge Gallery allowlist metadata：

```text
modelId: litert-community/gemma-4-E2B-it-litert-lm
modelFile: gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm
maxContextLength: 32000
maxTokens: 4000
accelerators: gpu,cpu
visionAccelerator: gpu
llmSupportImage: true
llmSupportAudio: true
capabilities: llm_thinking
minDeviceMemoryInGb: 8
```

Vision-related side files observed after Edge Gallery ran the model:

```text
vision_encoder_11057871376774296493.bin: 152,308,544 bytes
vision_encoder_mldrift_program_cache.bin: 2,893,600 bytes
vision_adapter.xnnpack_cache: 4,724,984 bytes
```

## GemmaFit Test Setup

Copied the official Edge Gallery E2B artifact into GemmaFit app external files
under the filename already supported by `CoachModelResolver`:

```text
/sdcard/Android/data/com.gemmafit/files/gemma-4-E2B-it.litertlm
```

During this test only, the GemmaFit v5 router was temporarily moved aside so the
resolver selected the official E2B fallback:

```text
gemmafit-v5-e2b-evidence-router.litertlm -> gemmafit-v5-e2b-evidence-router.litertlm.hold
```

After the test, v5 was restored as the default selected model.

## Readiness Result

Command:

```text
adb shell content read --uri content://com.gemmafit.debug/model_readiness
```

Result during official E2B test:

```json
{
  "status": "local_gemma_ready",
  "model_path": "/storage/emulated/0/Android/data/com.gemmafit/files/gemma-4-E2B-it.litertlm",
  "model_file_name": "gemma-4-E2B-it.litertlm",
  "model_size_bytes": 2538766336,
  "backend": "litert-lm:available"
}
```

After restoring GemmaFit v5, readiness returned to:

```json
{
  "status": "local_gemma_ready",
  "model_path": "/storage/emulated/0/Android/data/com.gemmafit/files/gemmafit-v5-e2b-evidence-router.litertlm",
  "model_file_name": "gemmafit-v5-e2b-evidence-router.litertlm",
  "model_size_bytes": 5071689680,
  "backend": "litert-lm:available"
}
```

## Prewarm Result

Command:

```text
adb shell content read --uri content://com.gemmafit.debug/litert_prewarm
```

Result:

```json
{
  "success": true,
  "backend": "litert-lm:isolated:gpu",
  "engine_create_ms": 26,
  "engine_initialize_ms": 9729,
  "reused_engine": false,
  "model_size_bytes": 2538766336,
  "elapsed_ms": 9775
}
```

Interpretation: the official E2B LiteRT-LM artifact initializes successfully in
GemmaFit's isolated debug backend on Pixel.

## Prompt Inference Result

Command:

```text
adb shell content read --uri content://com.gemmafit.debug/litert_prompt_infer
```

Result:

```json
{
  "success": true,
  "backend": "litert-lm:isolated:gpu",
  "engine_create_ms": 0,
  "engine_initialize_ms": 0,
  "session_create_ms": 8,
  "generate_content_ms": 23286,
  "total_elapsed_ms": 23299,
  "reused_engine": true,
  "prompt_chars": 1731,
  "model_size_bytes": 2538766336
}
```

Raw response was a valid fenced JSON object for `create_care_activity_log` with
the expected high-level fields:

```json
{
  "function": "create_care_activity_log",
  "arguments": {
    "headline": "Lunge Session Summary",
    "observations": "...",
    "next_session_focus": "...",
    "evidence_refs": [
      "metric.session.duration_seconds",
      "metric.session.view_limited_count",
      "metric.session.safety_events"
    ]
  }
}
```

Interpretation: the official E2B model can produce schema-shaped care-log output
from GemmaFit's existing prompt path after prewarm. It is not GemmaFit-v5 tuned,
so app-side schema validation and deterministic fill remain necessary.

## MotionZip Equivalence Result

Command:

```text
adb shell content read --uri 'content://com.gemmafit.debug/motionzip_model_equivalence?file=model_prompt_pair_compact.jsonl'
```

Result:

```text
success: true
overall_pass: true
backend: litert-lm:raw:cpu
elapsed_ms: 118,179
prompt_file_bytes: 8,768
case_count: 2
pass_count: 8 / 8
```

Passed checks:

```text
activity: lunge_like_unilateral_motion == lunge_like_unilateral_motion
states: abstain, monitor_only == abstain, monitor_only
event_count: 1 == 1
event_frames: 258 == 258
velocity_band: high == high
velocity_peak: 776.422 == 776.422
confidence_floor: 0.5145 == 0.5145
low_confidence_reason: low_keypoint_visibility == low_keypoint_visibility
```

Interpretation: with the official E2B LiteRT model, the dense frame-by-frame
prompt and MotionZip compressed prompt produced equivalent key understanding for
the measured activity/state/event/velocity/confidence fields.

## Image Prompt Result

Command:

```text
adb shell content read --uri 'content://com.gemmafit.debug/litert_image_prompt_infer?image=debug_phone_current.png'
```

Result:

```text
success: false
backend: litert-lm:isolated
error: Failed to generate content: INTERNAL: Image must be preprocessed before being used in SessionAdvanced.
```

Both GPU and CPU attempts reached `generate_content` but failed with the same
preprocessing error. This means the model artifact supports vision, but
GemmaFit's current debug image endpoint is not yet using the LiteRT-LM image
preprocess path required by `SessionAdvanced`.

## Current Decision

- Official Edge Gallery `Gemma-4-E2B-it` is a viable official baseline for
  text/schema and MotionZip compressed-evidence tests.
- It is much smaller than GemmaFit v5 (`2.54GB` vs `5.07GB`) and initializes
  successfully on Pixel.
- It is not tuned to GemmaFit's full output contract, so it should be used as an
  official comparison baseline, not as the primary demo model unless the app
  keeps strict schema validation and deterministic field fill.
- Vision is not usable through the current debug endpoint until image
  preprocessing is wired correctly.
