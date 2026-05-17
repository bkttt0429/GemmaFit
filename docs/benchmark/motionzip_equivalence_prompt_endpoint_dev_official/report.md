# MotionZip Equivalence via LiteRT Prompt Endpoint

Model: official

Prompt pair: docs\benchmark\motionzip_model_equivalence\model_prompt_pair_compact.jsonl

Overall pass: False

Checks: 3 / 8

This harness runs dense and MotionZip prompts as separate litert_prompt_infer
requests, then compares the two model outputs on the host. It avoids the raw
single-endpoint OOM path while preserving the official E2B model comparison.
