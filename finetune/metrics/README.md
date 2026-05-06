# Training metrics - version-controlled training artifacts

This directory holds small, text-based artifacts from each fine-tune run so
that the loss curve, hyperparameters, and final eval metrics are reproducible
from the repo without needing access to Google Drive.

`finetune/output/` is gitignored because it may contain LoRA adapters, merged
fp16 exports, and checkpoints. This directory is tracked because the JSON,
CSV, and PNG summary files are small.

## Files

| File | Source in Drive | Purpose |
| --- | --- | --- |
| `training_done_v1.json` | `trained_outputs/TRAINING_DONE_unsloth_gemma-4-E4B-it_gemmafit_v1.json` | v1 run summary written by Section 6 of the notebook |
| `trainer_state_v1.json` | `trained_outputs/checkpoints/.../trainer_state.json` | v1 HF Trainer state and loss curve source |
| `loss_curve_v1.png` | optional, plotted from `trainer_state_v1.json` | v1 loss curve image for the writeup |
| `training_done_v2.json` | `trained_outputs/TRAINING_DONE_<model>_gemmafit_v2_format_expand.json` | v2 P0/P1 run summary after prompt-format expansion, 60/30/10 mixture, and LiteRT conversion metadata |
| `trainer_state_v2.json` | `trained_outputs/checkpoints/<model>_gemmafit_v2_format_expand/trainer_state.json` | v2 HF Trainer state and loss curve source |
| `loss_curve_v2.png` | optional, plotted from `trainer_state_v2.json` | v2 loss curve image for the writeup |
| `tool_call_eval.json` | `finetune/litert_tool_smoke.py` | 8-tool LiteRT-LM smoke-test results for `models/gemmafit-v2-fc.litertlm` |
| `training_done_v3.json` | `trained_outputs/TRAINING_DONE_<model>_gemmafit_v3_evidence_router.json` | v3 Evidence Router run summary plus resume/conversion metadata |
| `trainer_state_v3.json` | `trained_outputs/checkpoints/<model>_gemmafit_v3_evidence_router/trainer_state.json` | v3 HF Trainer state and loss curve source |
| `tool_call_eval_v3.json` | `finetune/eval_v3_evidence_router.py` / `finetune/litert_tool_smoke.py` | 12-tool schema/evidence-ref compliance and LiteRT smoke output |
| `refusal_eval_v3.json` | v3 post-hoc eval | Refusal reason / refusal level eval for unsupported and insufficient-evidence prompts |
| `adversarial_eval_v3.json` | v3 post-hoc eval | Prompt-injection and boundary-probing eval summary |

## How To Update After A Colab Run

1. In Colab, after Section 6 completes, copy the done marker and trainer state
   from Drive to this directory.
2. Rename them to the stable repo names in the table above.
3. For v2, copy exported GGUF files to `models/` using explicit v2 names:
   `gemmafit-v2-q4_k_m.gguf` and optionally `gemmafit-v2-q5_k_m.gguf`.
4. Copy the converted LiteRT-LM artifact to `models/gemmafit-v2-fc.litertlm`
   and run `python finetune/litert_tool_smoke.py --model models/gemmafit-v2-fc.litertlm`.
5. For v3, copy the Drive files:
   `TRAINING_DONE_<model>_gemmafit_v3_evidence_router.json`,
   `RUN_STATE_<model>_gemmafit_v3_evidence_router.json`,
   `RUN_EVENTS_<model>_gemmafit_v3_evidence_router.jsonl`, and
   `DISCONNECT_POINTS_<model>_gemmafit_v3_evidence_router.jsonl`.
6. For v3, prefer the dedicated conversion-only Colab notebook
   `finetune/convert_gemmafit_v3_litert_colab.ipynb` after the merged
   HF/safetensors export exists. It avoids importing Unsloth and uses
   `--prefill_lengths=[256]` so Fire does not parse prefill as a bare integer.
   Keep the explicit `--quantization_recipe=dynamic_wi4_afp32` exporter flag.
7. Download the generated bundle
   `GemmaFit_train/gemmafit-v3-evidence-router-local-artifacts.zip`, then run:
   `python finetune/prepare_litert_artifact.py --source-bundle path/to/gemmafit-v3-evidence-router-local-artifacts.zip --run-smoke`.
8. If you download only the model instead of the bundle, copy the converted
   LiteRT-LM artifact to `models/gemmafit-v3-evidence-router.litertlm` and run:
   `python finetune/prepare_litert_artifact.py --source-litertlm path/to/gemmafit-v3-evidence-router.litertlm --run-smoke`.
9. Commit metrics after benchmark numbers are written, not before.

## Schema

```json
{
  "version": "v2_format_expand",
  "finished_at": "YYYY-MM-DD HH:MM:SS",
  "elapsed_seconds": 0,
  "final_train_loss": 0.0,
  "best_metric": 0.0,
  "global_step": 0,
  "adapter_path": "...",
  "checkpoint_dir": "...",
  "log_dir": "...",
  "effective_batch": 0,
  "seq_len": 0,
  "model": "unsloth/gemma-4-E4B-it",
  "model_source": "unsloth/gemma-4-E4B-it",
  "trained_output_dir": "...",
  "merged_hf_path": "...",
  "litertlm_path": "models/gemmafit-v2-fc.litertlm",
  "conversion_status": "not_started|converted_unverified|ready_for_android|smoke_failed",
  "conversion_log": {},
  "tool_call_eval": "finetune/metrics/tool_call_eval.json",
  "domain_data": "fc_training_data_chat.json",
  "mixture_probabilities": {
    "domain": 0.6,
    "glaive": 0.3,
    "hh_rlhf": 0.1
  },
  "eval_format": "production"
}
```

v3 adds resume and evidence-router fields:

```json
{
  "version": "v3_evidence_router",
  "run_name": "<model>_gemmafit_v3_evidence_router",
  "run_suffix": "gemmafit_v3_evidence_router",
  "status": "training_complete|complete",
  "last_completed_phase": "6_training|8_5_litert_metadata|11_eval_done",
  "dataset_path": "finetune/data/gemmafit_v3_evidence_router.json",
  "dataset_sha256": "...",
  "train_rows": 10000,
  "validation_rows": 1200,
  "best_checkpoint": "...",
  "adapter_path": "...",
  "merged_hf_path": "...",
  "gguf_q4_path": "models/gemmafit-v3-evidence-router-q4_k_m.gguf",
  "litertlm_path": "models/gemmafit-v3-evidence-router.litertlm",
  "conversion_status": "not_started|ready_for_android|smoke_failed",
  "tool_call_eval": "finetune/metrics/tool_call_eval_v3.json",
  "resume_log": "RUN_EVENTS_<run>.jsonl",
  "disconnect_points": "DISCONNECT_POINTS_<run>.jsonl"
}
```

## Current Status

- v1 Q4 exists as `models/gemmafit-q4_k_m.gguf` and should remain labeled v1.
- The v1 benchmark showed production-prompt function match of 2.2%, so it is
  not demo-ready.
- v2 training should use `finetune/data/fc_training_data_chat.json` and notebook
  run name `gemmafit_v2_format_expand`.
- v2 LiteRT-LM app testing expects `models/gemmafit-v2-fc.litertlm`; GGUF is
  retained only as llama.cpp fallback evidence.
- v3 training uses `finetune/data/gemmafit_v3_evidence_router.json`, run suffix
  `gemmafit_v3_evidence_router`, and 12 tools with evidence-ref validation.
- v3 LiteRT-LM app testing expects
  `models/gemmafit-v3-evidence-router.litertlm`; GGUF remains fallback-only.
- `converted_unverified` means the `.litertlm` file exists but the 12-tool
  smoke has not passed yet. Only `ready_for_android` is demo-ready.
- Any `.crdownload` model file is incomplete and must not be used as benchmark
  evidence.
