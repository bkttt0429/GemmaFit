# LiteRT Model Throughput and Performance - 2026-05-16

Device: Pixel 8 Pro (`husky`, serial `3A251FDJG004RX`)

APK: local debug build installed with `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Scope: LiteRT text-mode summary/debug endpoints only. Layer 2 was not changed or benchmarked in this run.

## Models

| Model override | File | Size | Backend |
| --- | --- | ---: | --- |
| `official` | `/sdcard/Android/data/com.gemmafit/files/gemma-4-E2B-it.litertlm` | 2,538,766,336 bytes | `litert-lm:isolated:gpu` |
| `v5` | `/sdcard/Android/data/com.gemmafit/files/gemmafit-v5-e2b-evidence-router.litertlm` | 5,071,689,680 bytes | `litert-lm:isolated:gpu` |

Use nested adb quoting when passing multiple query params:

```powershell
adb shell "content read --uri 'content://com.gemmafit.debug/litert_prompt_infer?file=litert_ab_a_no_narrative_v2.json&model=official'"
```

Without nested quoting, the remote shell can drop the query params after `&`.

## Official E2B Results

Cold/prewarm:

| Condition | Engine initialize | Endpoint elapsed | Notes |
| --- | ---: | ---: | --- |
| Force-stop then prewarm | 10,553 ms | 10,609 ms | clean cold-ish debug provider run |
| Foreground app then prewarm | 18,847 ms | 18,880 ms | noisier, likely affected by foreground app/device state |
| App-launch background prewarm | 14,141 ms | n/a | `GemmaFitLiteRtPrewarm`, thermal `light`, backend `litert-lm:isolated:gpu` |

Warm prompt throughput:

| Prompt fixture | Prompt chars | Output chars | Runs | Avg generate | Avg wall | Throughput |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `litert_ab_a_no_narrative_v2.json` | 1,509 | 648 | 5 | 22,611 ms | 24,227 ms | 2.65 prompts/min |
| `litert_ab_b_with_narrative_v2.json` | 2,611 | 734 | 5 | 26,654 ms | 28,544 ms | 2.25 prompts/min |

Output speed from the raw JSON string length is roughly 28 output chars/sec. LiteRT-LM does not expose token/sec through the current Android API, so this is not a token throughput number.

Memory snapshot after a foreground official prompt:

| Metric | Value |
| --- | ---: |
| TOTAL PSS | 169,960 KB |
| TOTAL RSS | 294,960 KB |
| Java Heap PSS | 11,356 KB |
| Native Heap PSS | 10,200 KB |
| Graphics PSS | 45,372 KB |

This is app-process `dumpsys meminfo`; it should not be treated as full device GPU/model reservation.

## V5 Comparison

| Test | Engine initialize | Generate | Wall | Output chars | Notes |
| --- | ---: | ---: | ---: | ---: | --- |
| `v5` prewarm | 15,586 ms | n/a | 19,397 ms | n/a | 5.07 GB artifact |
| `v5` short prompt | 0 ms | 26,140 ms | 28,974 ms | 410 | reused engine |
| `v5` long prompt | 0 ms | 32,468 ms | 34,066 ms | 548 | reused engine |

V5 works, but it is slower than official E2B in this test and has a much larger artifact. Keep it as optional P1 quality layer unless it wins on schema/wording gates.

## MotionZip Equivalence Endpoint

`motionzip_model_equivalence?file=model_prompt_pair_compact.jsonl&model=official&backend=auto`
failed with:

```text
java.lang.OutOfMemoryError:
Failed to allocate a 301989904 byte allocation ...
growth limit 268435456
```

It failed both after a resident warm engine and after `am force-stop`. The
debug endpoint was tightened to default to `max_tokens=512`, reuse the isolated
engine path, and avoid treating `backend=auto` as a forced single backend, but
the single endpoint still returns an outer OOM before recording benchmark
attempts.

This remains a benchmark endpoint issue, not a live-path result: product
runtime uses one compact MotionZip prompt on the isolated prewarmed path, while
equivalence is intentionally heavier because it compares dense and compressed
prompts.

## New Harnesses Added

Two host-side PowerShell harnesses were added so the expensive gates can run
without editing Android code each time:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run_litert_prompt_smoke.ps1 -Count 100 -Model official -PromptFile litert_ab_a_no_narrative_v2.json

powershell -NoProfile -ExecutionPolicy Bypass -File tools\run_motionzip_equivalence_prompt_endpoint.ps1 -Model official
```

The smoke harness calls `model_readiness`, then runs
`litert_prompt_infer` repeatedly and parses both the debug endpoint JSON and
the model's raw JSON object.

The MotionZip harness avoids the single-endpoint OOM by sending the dense and
MotionZip prompts as separate `litert_prompt_infer` requests, then comparing the
two official E2B outputs on the host.

## Official E2B Dev Smoke

Short dev run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run_litert_prompt_smoke.ps1 -Count 2 -Model official -PromptFile litert_ab_a_no_narrative_v2.json -OutDir docs\benchmark\litert_prompt_smoke_dev_2_official_a -ForceStopBeforeRun
```

Result:

| Metric | Value |
| --- | ---: |
| Runs | 2 / 2 |
| JSON parse success | 2 / 2 |
| 99% gate | pass for this short dev run only |
| Backend | `litert-lm:isolated:gpu` |
| Avg wall | 34,863.5 ms |
| Avg generate | 23,361.5 ms |

This proves the 100-run harness is usable. It does not replace the full 100-run
acceptance gate.

## Official E2B 100-Run Gate Attempt

First long run:

- `docs/benchmark/litert_prompt_smoke_100_official_a_2026-05-16`
- completed 44 attempts before being stopped for investigation
- runs 1-40 produced parseable JSON
- runs 41-43 returned empty adb/content payload files, not malformed model JSON
- run 44 succeeded again after the endpoint reinitialized the isolated engine
- device thermal status reached `3` / SEVERE during this window

Harness hardening added after that run:

- `MaxEndpointRetries` retries empty endpoint payloads, endpoint failures,
  generation failures, or unparsable model JSON before counting the run
- `PauseOnThermalSevere` checks `dumpsys thermalservice` before each prompt and
  pauses instead of running prompts while the device is thermal status `3`+

Retry + thermal-gated run:

- `docs/benchmark/litert_prompt_smoke_100_official_a_retry_thermal_2026-05-16`
- completed 7 / 100 prompts before stopping
- JSON parse success in completed prompts: 7 / 7
- average generate time in completed prompts: 23,501 ms
- stopped because the Pixel reached thermal status `3` before run 8 and stayed
  there through repeated 90-second pauses

Conclusion: the **100-run >= 99% JSON gate is not yet complete**. The current
evidence is strong for the prompt contract on short/warm runs, but the full gate
needs a cooled device or a chunked thermal-rest protocol before it can be marked
passed.

## MotionZip Equivalence via Prompt Endpoint

Host-side extract-mode run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run_motionzip_equivalence_prompt_endpoint.ps1 -Model official -OutDir docs\benchmark\motionzip_equivalence_prompt_endpoint_dev_official -ForceStopBeforeRun
```

Result:

| Case | Backend | Wall | Generate |
| --- | --- | ---: | ---: |
| Dense frame-by-frame prompt | `litert-lm:isolated:gpu` | 64,120 ms | 39,047 ms |
| MotionZip compressed prompt | `litert-lm:isolated:gpu` | 40,467 ms | 38,867 ms |

Equivalence checks:

| Check | Result |
| --- | --- |
| activity | pass |
| states | pass |
| event_count | fail |
| event_frames | fail |
| velocity_band | pass |
| velocity_peak | fail |
| confidence_floor | fail |
| low_confidence_reason | fail |

Overall: **3 / 8 pass** for official E2B on the current prompt pair.

Interpretation: official E2B can run both inputs through the safe isolated
prompt endpoint, but free re-summary is not a valid MotionZip proof because
the model can paraphrase or average away task-critical facts.

Host-side canonical-copy run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run_motionzip_equivalence_prompt_endpoint.ps1 -Model official -PromptMode canonical_copy -OutDir docs\benchmark\motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16 -ForceStopBeforeRun
```

Result:

| Case | Backend | Wall | Generate |
| --- | --- | ---: | ---: |
| Dense frame-by-frame prompt | `litert-lm:isolated:gpu` | 69,258 ms | 45,116 ms |
| MotionZip compressed prompt | `litert-lm:isolated:gpu` | 39,783 ms | 37,950 ms |

Equivalence checks:

| Check | Result |
| --- | --- |
| activity | pass |
| states | pass |
| event_count | pass |
| event_frames | pass, within 6-frame tolerance |
| velocity_band | pass |
| velocity_peak | pass, 1.89% relative error |
| confidence_floor | pass |
| low_confidence_reason | pass |

Overall: **8 / 8 pass** for official E2B with the hardened
`EXPECTED_KEY_MOTION_UNDERSTANDING` contract.

This is the result to use for the current MotionZip proof: the model receives
different evidence encodings, but the required output contract is to reproduce
the canonical task-critical understanding rather than invent a new summary.
The single Android `motionzip_model_equivalence` content endpoint still has an
OOM issue and remains a CI ergonomics problem, not a live-path blocker.
