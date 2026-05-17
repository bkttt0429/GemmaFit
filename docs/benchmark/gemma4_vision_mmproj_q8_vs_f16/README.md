# Gemma 4 E2B Vision mmproj F16 vs Q8_0 smoke benchmark

Date: 2026-05-15

## Files

| File | Size | Role |
|---|---:|---|
| `models/gemma4_e2b_vision_gguf/gemma-4-E2B-it-UD-IQ2_M.gguf` | 2,290,858,112 bytes | main Gemma 4 E2B Vision GGUF |
| `models/gemma4_e2b_vision_gguf/mmproj-F16.gguf` | 985,654,080 bytes | existing F16 multimodal projector |
| `models/gemma4_e2b_vision_gguf/mmproj-gemma-4-E2B-it-Q8_0.gguf` | 557,367,776 bytes | downloaded Q8_0 projector from `ggml-org/gemma-4-E2B-it-GGUF` |

Q8_0 saves 428,286,304 bytes, about 43.5% of the projector file size.

## Test Setup

- Tool: `tools/llama.cpp-b9159-vulkan/llama-mtmd-cli.exe`
- Backend: Vulkan
- Device: `Vulkan1: NVIDIA GeForce RTX 3050 Ti Laptop GPU`
- Main model: `gemma-4-E2B-it-UD-IQ2_M.gguf`
- Prompt image: `docs/benchmark/gemma4_vision_mmproj_q8_vs_f16/person_equipment_crop_2s.jpg`
- Prompt: `Describe the image in one sentence.`
- Generation: `-n 1`
- Context: `--ctx-size 1024`
- GPU offload: `--device Vulkan1 -ngl 99`
- Chat formatting: `--jinja`
- Warmup: `--no-warmup`

Important: `--image-min-tokens` must be set together with `--image-max-tokens`. Setting only `--image-max-tokens` lower than the model default can fail with:

```text
image_max_pixels (...) is less than image_min_pixels (...)
```

## Sequential Results

These four runs were executed sequentially to avoid GPU contention.

| Projector | image tokens | elapsed |
|---|---:|---:|
| F16 | 128 | 21.065s |
| Q8_0 | 128 | 14.847s |
| F16 | 256 | 20.996s |
| Q8_0 | 256 | 21.753s |

## Quality Smoke

The initial quality prompts used `--jinja` without grammar/schema constraints. Gemma 4 emitted `<|channel>thought` before the final answer, so those runs are not suitable for comparing projector quality.

Using `--json-schema '{}'` constrained output to a JSON object and suppressed the thought channel in the observed runs.

### Person crop

Image: `person_equipment_crop_2s.jpg`

Prompt:

```text
Return JSON about the visible person and equipment. Do not include reasoning.
```

Both F16 and Q8_0 returned the same output:

```json
{"person": "A person standing, wearing a black t-shirt and black shorts.", "equipment": "Black t-shirt, black shorts"}
```

### Full overlay frame

Image: `clean_frame_2s.jpg`

Prompt:

```text
Return JSON with person_visible, equipment, scene, text_overlay_present. Do not include reasoning.
```

Both F16 and Q8_0 returned the same output:

```json
{"person_visible": true, "equipment": ["shorts"], "scene": "Outdoor setting with a background of trees/nature, possibly a park or wooded area.", "text_overlay_present": true}
```

### Thumbnail overlay

Image: `motionzip_sparse_stride2_demo_thumb.png`

Prompt:

```text
Return JSON with person_visible, equipment, visible_body_region, text_overlay_present. Do not include reasoning.
```

Both F16 and Q8_0 returned the same output:

```json
{"person_visible": false, "equipment": [{"visible_body_region": "n/a"}, {"text_overlay_present": false}]}
```

## Interpretation

Q8_0 is useful for reducing storage and memory pressure. In this one-shot CLI test it is not guaranteed to be faster in every setting:

- at 128 image tokens, Q8_0 was faster;
- at 256 image tokens, Q8_0 and F16 were roughly comparable;
- earlier parallel GPU runs showed extreme timing distortion and should be ignored.

Quality-wise, the smoke test did not show a difference between F16 and Q8_0: outputs were identical on the tested images. However, the absolute quality on the current MotionZip demo overlay images was not good enough for safety reasoning. Both projectors missed or misclassified visible exercise context in at least one case. This supports keeping Gemma Vision as a low-frequency scene-summary sidecar only.

For GemmaFit, Q8_0 should be treated as a candidate for a low-frequency Vision sidecar, not as a live coaching dependency. The live product path should remain MediaPipe / Layer 2 / MotionZip with LiteRT evidence routing.
