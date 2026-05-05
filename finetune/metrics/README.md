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

## How To Update After A Colab Run

1. In Colab, after Section 6 completes, copy the done marker and trainer state
   from Drive to this directory.
2. Rename them to the stable repo names in the table above.
3. For v2, copy exported GGUF files to `models/` using explicit v2 names:
   `gemmafit-v2-q4_k_m.gguf` and optionally `gemmafit-v2-q5_k_m.gguf`.
4. Copy the converted LiteRT-LM artifact to `models/gemmafit-v2-fc.litertlm`
   and run `python finetune/litert_tool_smoke.py --model models/gemmafit-v2-fc.litertlm`.
5. Commit metrics after benchmark numbers are written, not before.

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
  "conversion_status": "not_started|ready_for_android|smoke_failed",
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

## Current Status

- v1 Q4 exists as `models/gemmafit-q4_k_m.gguf` and should remain labeled v1.
- The v1 benchmark showed production-prompt function match of 2.2%, so it is
  not demo-ready.
- v2 training should use `finetune/data/fc_training_data_chat.json` and notebook
  run name `gemmafit_v2_format_expand`.
- v2 LiteRT-LM app testing expects `models/gemmafit-v2-fc.litertlm`; GGUF is
  retained only as llama.cpp fallback evidence.
- Any `.crdownload` model file is incomplete and must not be used as benchmark
  evidence.
