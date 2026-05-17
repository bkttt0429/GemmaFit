# MotionZip Equivalence via LiteRT Prompt Endpoint

Model: official

Prompt pair: docs\benchmark\motionzip_model_equivalence\model_prompt_pair_compact.jsonl

Prompt mode: canonical_copy

Overall pass: True

Checks: 8 / 8

This harness runs dense and MotionZip prompts as separate litert_prompt_infer
requests, then compares the two model outputs on the host. In canonical_copy
mode, the prompt includes a deterministic EXPECTED_KEY_MOTION_UNDERSTANDING
object and asks the model to copy it exactly; this hardens the contract against
free-form re-summary while still exercising the official E2B prompt path.
