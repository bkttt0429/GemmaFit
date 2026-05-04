# GemmaFit Benchmark - base vs fine-tuned v1

**Date:** 2026-05-04  
**Base model:** `unsloth/gemma-4-E4B-it` via `models/gemma4-e4b-Q4_K_M.gguf`  
**Fine-tuned model:** `models/gemmafit-q4_k_m.gguf`  
**Model status:** this GGUF is v1 and must not be treated as v2.  
**Validation set:** `finetune/data/fc_training_data.json::validation` with 90 synthetic examples across 9 functions.

## Headline Numbers

| Setup | Base JSON parse | Base fn-match | FT JSON parse | FT fn-match | FT args Jaccard |
| --- | ---: | ---: | ---: | ---: | ---: |
| Production prompt | 95.6% | 0.0% | 93.3% | 2.2% | 0.081 |
| Training prompt | 0.0% | 0.0% | 0.0% | 0.0% | 0.000 |

Production prompt means system prompt plus fenced JSON plus reply instruction,
matching the Android app. Training prompt means bare
`Motion report:\n{compact json}`, matching the v1 notebook `fmt_domain` wrapper.

## Artifacts

| Path | Purpose |
| --- | --- |
| `ab_compare/results.json` | production-prompt A/B results |
| `ab_compare/report.html` | production-prompt side-by-side HTML |
| `ab_compare_training_fmt/results.json` | training-prompt A/B results |
| `ab_compare_training_fmt/report.html` | training-prompt side-by-side HTML |
| `baseline_e4b/results.json` | original base-model baseline |
| `baseline_smoke/` | early 5-example smoke test |

## Diagnosis

v1 is not usable as the local function-calling model yet.

- The FT model usually emits valid JSON under production prompt, but mostly
  chooses generic or wrong function names.
- Under the v1 training prompt, both base and FT fail function match. The FT
  often echoes the input instead of emitting `{"function": "...", "args": ...}`.
- The likely root causes are training distribution, not just quantization:
  domain examples were only 30% of the mixture, production prompt format was not
  represented, and checkpoint selection used `eval_loss` rather than a
  function-calling metric.

## v2 Remediation Status

P0 is implemented:

- `finetune/data/format_expand.py`
- `finetune/data/fc_training_data_chat.json`
- train rows: 510 raw examples expanded to 2040 chat-format examples
- validation rows: 90 examples per format for `production`, `bare`, `terse`, and
  `chinese`

The notebook has been switched to the P0/P1 v2 recipe:

- load `fc_training_data_chat.json`
- use rows directly as `messages`
- remove `fmt_domain` for domain rows
- rebalance domain/Glaive/HH mixture to `60/30/10`
- write `TRAINING_DONE_<model>_gemmafit_v2_format_expand.json`
- export `gemmafit-v2-q4_k_m.gguf` and optionally `gemmafit-v2-q5_k_m.gguf`

## Q5 Note

`models/gemmafit-q5_k_m.gguf.crdownload` is incomplete and is not benchmark
evidence. If a complete Q5 v1 file becomes available, run it only as a diagnostic
to test whether quantization suppressed the v1 signal. Do not block v2 retraining
on that diagnostic.

## Next Benchmark After v2 Training

Run post-hoc A/B with:

```bash
python prototype/eval_compare.py \
  --base models/gemma4-e4b-Q4_K_M.gguf \
  --ft models/gemmafit-v2-q4_k_m.gguf \
  --n 90 \
  --prompt-format production \
  --out docs/benchmark/ab_compare_v2
```

Then repeat with `--prompt-format training` to verify the model no longer echoes
the input. If Q5 exists, repeat with `models/gemmafit-v2-q5_k_m.gguf`.

## Success Criteria

- Minimum improvement: production function match clearly above v1's 2.2%.
- Demo-ready target: production function match at least 50%.
- If v2 remains below 50%, move to P2: expand synthetic domain data to roughly
  1800 examples with stratified train/validation splits.
