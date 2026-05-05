# Fine-tune Training Data

## Files

| File | Purpose | Generator |
| --- | --- | --- |
| `fc_training_data.json` | v1 raw dataset with `input` and `output` pairs | `generate_synthetic.py` |
| `fc_training_data_chat.json` | v2 chat-formatted dataset with prompt-format expansion | `format_expand.py` |
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

Do not retrain until the deep literature report and native Evidence DAG contract
are stable. The next dataset should be named separately from v2, for example
`gemmafit_v3_evidence_router`, and should add these fields to the domain input:

- `capability_contract.can_judge`
- `capability_contract.cannot_judge`
- compact `evidence_dag.nodes`
- compact `evidence_dag.edges`
- metric-level `confidence_ceiling`

The target output should still be a single function call, but every output must
include `evidence_refs`, `selection_basis`, and `refusal_level`. Old
`warn_poor_visibility` examples should be mapped to
`refuse_unsupported_question(reason="insufficient_evidence")` once the v3
benchmark is created; keep v2 unchanged so existing benchmark artifacts remain
reproducible.

## Reproduce

```bash
cd finetune/data
python generate_synthetic.py
python format_expand.py
```

Then run the notebook from Section 1 through Section 9 in Colab. After the
`.litertlm` artifact is produced, copy it back and finalize it with:

```bash
python finetune/prepare_litert_artifact.py --source-litertlm path/to/gemmafit-v2-fc.litertlm --run-smoke
```
