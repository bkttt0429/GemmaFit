# litert_runtime_stability_report_2026-05-16

Status: PASS_WITH_CAVEAT
Run: local_validation_run_2026-05-17
Host time: 2026-05-17T14:19:38.1530502+08:00
Git HEAD: `746c30b8616748cd417b1863bdc762ed26749f2a`
Device: `List of devices attached; 3A251FDJG004RX         device product:husky model:Pixel_8_Pro device:husky transport_id:1`
Raw evidence: `docs/benchmark/local_validation_run_2026-05-17/raw/`


## Claim

影片可說「Local Gemma / LiteRT summary 在 Pixel 上可本地執行，且有 fallback」。不能說 multimodal image/audio 已完整支援，也不能把這段放進 live frame 主路徑。

## Current Device Run

- Readiness: `local_gemma_ready` / backend `litert-lm:available` / model `gemma-4-E2B-it.litertlm` / size `2538766336` bytes.
- Prewarm: success `True`, backend `litert-lm:isolated:gpu`, initialize `10696` ms, reused_engine `False`.
- Prompt inference: success `True`, generation `True`, backend `litert-lm:isolated:gpu`, generate `23570` ms, reused_engine `True`.
- Caveat: this run requested constrained mode, but endpoint returned `constrained_decoding=False`. Treat this as text generation stability evidence, not proof of constrained decoder in this single run.

## 100-Run Stability Anchor

- Path: `docs/benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json`
- Completed: `100/100`
- Endpoint success: `100`
- Generation success: `100`
- JSON parse success rate: `1`
- 99% gate: `True`
- p95 wall time: `45163` ms; p95 generate time: `26508` ms.

## Test Gate

| Test | Result |
|---|---:|
| `LiteRtSessionSummaryPromptTest` | 12 tests, 0 failures, 0 errors |

## Conclusion

可支撐影片中的「本地 LiteRT summary / explanation」展示與 fallback 敘事。不要宣稱 live frame Gemma、不要宣稱 image/audio `.litertlm` 已完成，也不要把 constrained decoding 的單次 run 說成通過。
