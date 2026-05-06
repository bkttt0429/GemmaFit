# Fine-tune Training Data

## Files

| File | Purpose | Generator |
| --- | --- | --- |
| `fc_training_data.json` | v1 raw dataset with `input` and `output` pairs | `generate_synthetic.py` |
| `fc_training_data_chat.json` | v2 chat-formatted dataset with prompt-format expansion | `format_expand.py` |
| `gemmafit_v3_evidence_router.json` | v3 chat-formatted Capability-Bounded Evidence Router dataset | `generate_v3_evidence_router.py` |
| `data/fc_training_data.json` | older snapshot kept for diff reference | legacy |

## Pipeline

```text
generate_synthetic.py
  -> fc_training_data.json
  -> format_expand.py
  -> fc_training_data_chat.json
```

`fc_training_data_chat.json` is the v2 training file. It is already in
`{"messages": [...]}` shape, so the notebook should not apply the old
`fmt_domain` function to GemmaFit domain rows.

## v2 Format Expansion

`format_expand.py` produces four prompt wrappings per train example:

| Format | Purpose |
| --- | --- |
| `production` | Matches Android inference: system prompt, fenced JSON, reply instruction |
| `bare` | Preserves v1 `Motion report:\n{json}` distribution |
| `terse` | Raw compact JSON only |
| `chinese` | Future-proofing for Chinese app-facing inputs |

Current output:

- train: 510 raw examples -> 2040 chat examples
- validation: 90 examples per format x 4 formats

## Notebook v2 Recipe

`train_gemma4_pipeline.ipynb` has been switched to the P0/P1 v2 recipe:

1. Load `/content/fc_training_data_chat.json`.
2. Use `domain_ds` directly as `domain_fmt`.
3. Keep Glaive and HH formatting functions for external data only.
4. Set mixture probabilities to `[0.60, 0.30, 0.10]` for
   `[domain, Glaive, HH]`.
5. Use production-format validation from `fc["validation"]["production"]`.
6. Use run suffix `gemmafit_v2_format_expand`.
7. Preserve the merged HF/safetensors export for LiteRT-LM conversion before
   deleting Colab artifacts.

Implementation note: the notebook materializes a finite mixed dataset instead
of passing a HuggingFace streaming `interleave_datasets` object to Unsloth.
Current Unsloth/TRL code expects iterable internals to expose `batch_size`, but
`RandomlyCyclingMultiSourcesExamplesIterable` does not. The finite mix keeps the
same 60/30/10 ratio by building 2040 domain rows, 1020 Glaive rows, and 340
HH-RLHF rows.

The v2 run intentionally keeps `metric_for_best_model="eval_loss"` for now and
relies on post-hoc `prototype/eval_compare.py` for function-match benchmarking.

## v3 Evidence Router Prep

`generate_v3_evidence_router.py` creates the v3 training set for the
Capability-Bounded Evidence Router. This is separate from v2 and must not
overwrite v2 metrics or model artifacts.

The v3 domain input contains compact structured evidence only:

- `capability_contract.can_judge`
- `capability_contract.cannot_judge`
- compact `evidence_dag_compact`
- metric-level `confidence_ceiling`
- session-level summary statistics, not raw video or raw landmarks

The target output should still be a single function call, but every output must
include `evidence_refs`, `selection_basis`, and `refusal_level`. Movement
coaching tools also carry `coach_cue` and `next_focus`. Unsupported medical,
force, EMG, fall-risk, sarcopenia, injury, and insufficient-evidence prompts
route to `refuse_unsupported_question`.

The default row mix is:

| Row Type | Ratio |
| --- | ---: |
| Clean positive | 20% |
| Warning correction | 25% |
| Partial judgment | 20% |
| Low confidence / view limited | 15% |
| Unsupported medical / force / EMG | 10% |
| Memory / trend tools | 10% |

The Colab notebook run suffix is `gemmafit_v3_evidence_router`, with a
domain/Glaive/HH mixture of `70/20/10`.

## Reproduce

```bash
cd finetune/data
python generate_synthetic.py
python format_expand.py
python generate_v3_evidence_router.py --validate
```

Then run the notebook from Section 1 through Section 9 in Colab. For LiteRT
conversion, restart into a conversion-only runtime after installing
`litert-torch-nightly` / `litert-lm`; do not import Unsloth in that runtime.
The notebook packages the converted model and small metadata files into
`GemmaFit_train/gemmafit-v3-evidence-router-local-artifacts.zip`.

After downloading the bundle, finalize it locally with:

```bash
python finetune/prepare_litert_artifact.py --source-bundle path/to/gemmafit-v3-evidence-router-local-artifacts.zip --run-smoke
```

If you download only the `.litertlm` artifact instead of the bundle, use:

```bash
python finetune/prepare_litert_artifact.py --source-litertlm path/to/gemmafit-v3-evidence-router.litertlm --run-smoke
```
