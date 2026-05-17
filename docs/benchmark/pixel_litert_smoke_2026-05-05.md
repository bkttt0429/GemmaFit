# Pixel 8 Pro LiteRT / Local Model Smoke - 2026-05-05

## Device

- `adb devices`: `3A251FDJG004RX	device`
- Installed package: `com.gemmafit`

## Model Files On Device

Checked path:

```text
/sdcard/Android/data/com.gemmafit/files/
```

Present:

| File | Size | Role |
| --- | ---: | --- |
| `gemma4-e2b-Q4_K_M.gguf` | 2.8 GiB | llama.cpp fallback candidate |
| `gemma4-e4b-q4.gguf` | 4.6 GiB | llama.cpp fallback candidate |

Missing:

| File | Expected by app | Impact |
| --- | --- | --- |
| `gemmafit-v2-fc.litertlm` | `CoachModelResolver.resolveLiteRtModelPath()` | LiteRT-LM real model smoke cannot pass yet. App should fall back instead of reporting LiteRT success. |

## Current Result

Status: `BLOCKED_LITERT_MODEL_MISSING`

The Android LiteRT backend code path exists, but this device does not currently
have the `.litertlm` model artifact that the resolver accepts. Do not mark
LiteRT / Google AI Edge as complete until a real `.litertlm` file is pushed and
one summary-coach request returns a valid tool call with evidence refs.

App launch smoke:

| Check | Result |
| --- | --- |
| `adb shell am start -n com.gemmafit/.MainActivity` | started |
| `adb shell pidof com.gemmafit` | `5710` |
| Filtered recent logcat for `FATAL EXCEPTION`, `AndroidRuntime`, `LiteRt`, `CoachModelResolver` | no matching crash output in the sampled tail |

## Next Smoke Steps

1. Push the LiteRT model:

```powershell
adb push models\gemmafit-v2-fc.litertlm /sdcard/Android/data/com.gemmafit/files/gemmafit-v2-fc.litertlm
```

2. Launch the app and run one short video/session that produces a summary coach request.

3. Capture logcat evidence for:

```text
backend starts with litert-lm
success=true
function name is allowed by capability_contract.can_judge
evidence_refs are present and valid
```

4. Record latency, tokens/sec if exposed, and fallback count in the local inference performance table.
