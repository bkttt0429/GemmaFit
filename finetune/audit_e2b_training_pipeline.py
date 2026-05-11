"""Audit GemmaFit E2B SFT data and notebook training guards.

This is a fail-fast preflight for the long-evidence router dataset. It does not
train or load model weights. It checks that assistant targets are compact tool
calls, that the training notebook is not accidentally optimizing the long
system/user evidence prompt, and optionally estimates token truncation risk with
the same tokenizer used for training.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import sys
from collections import Counter
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = REPO_ROOT / "finetune" / "data" / "gemmafit_v5_2_e2b_evidence_router.json"
DEFAULT_NOTEBOOK = REPO_ROOT / "finetune" / "train_e2b_v5_evidence_router.ipynb"
INPUT_SECTION_NAMES = {
    "activity_context",
    "capability_contract",
    "evidence_ledger",
    "motion_context",
    "motion_feature_window",
    "output_contract",
    "person_tracking_state",
    "phase_context",
    "router_contract",
    "visual_summary",
}
FORBIDDEN_OUTPUT_FRAGMENTS = {
    "```",
    "activity_context",
    "capability_contract",
    "evidence_ledger",
    "motion_feature_window",
    "output_contract",
    "person_tracking_state",
    "visual_summary",
}


def percentile(values: list[float], q: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, int(len(ordered) * q)))
    return ordered[idx]


def stats(values: list[float]) -> dict[str, float]:
    if not values:
        return {"min": 0.0, "p50": 0.0, "p95": 0.0, "max": 0.0}
    return {
        "min": min(values),
        "p50": statistics.median(values),
        "p95": percentile(values, 0.95),
        "max": max(values),
    }


def flatten_dataset(payload: dict[str, Any]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    train = payload.get("train", [])
    if isinstance(train, list):
        rows.extend(train)
    validation = payload.get("validation", {})
    if isinstance(validation, dict):
        for split_rows in validation.values():
            if isinstance(split_rows, list):
                rows.extend(split_rows)
    elif isinstance(validation, list):
        rows.extend(validation)
    return rows


def build_sample_dataset(train_count: int, validation_count: int) -> dict[str, Any]:
    sys.path.insert(0, str(REPO_ROOT))
    from finetune.data.generate_v5_e2b_evidence_router import build_dataset

    return build_dataset(
        train_count,
        validation_count,
        45,
        hard_cases=True,
        tool_contract_v2=True,
        zh_tw_ratio=0.45,
        schema_fuzz_ratio=0.18,
        tool_schema_hardening_ratio=0.25,
    )


def load_dataset(path: Path, generate_sample: bool, sample_train: int, sample_validation: int) -> dict[str, Any]:
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    if generate_sample:
        return build_sample_dataset(sample_train, sample_validation)
    raise FileNotFoundError(f"Dataset not found: {path}")


def audit_targets(rows: list[dict[str, Any]], allowed_tools: set[str] | None) -> dict[str, Any]:
    errors: list[dict[str, Any]] = []
    row_types = Counter(str(row.get("row_type", "<missing>")) for row in rows)
    expected_functions = Counter(str(row.get("expected_function", "<missing>")) for row in rows)
    prompt_chars: list[float] = []
    target_chars: list[float] = []
    total_chars: list[float] = []
    target_ratio: list[float] = []

    for index, row in enumerate(rows):
        messages = row.get("messages")
        if not isinstance(messages, list) or len(messages) < 3:
            errors.append({"index": index, "issue": "bad_messages_shape", "row_type": row.get("row_type")})
            continue
        if messages[-1].get("role") != "assistant":
            errors.append({"index": index, "issue": "last_message_not_assistant", "row_type": row.get("row_type")})
            continue

        prompt = "\n".join(str(msg.get("content") or "") for msg in messages[:-1])
        target = str(messages[-1].get("content") or "")
        prompt_chars.append(float(len(prompt)))
        target_chars.append(float(len(target)))
        total = len(prompt) + len(target)
        total_chars.append(float(total))
        target_ratio.append(len(target) / max(1.0, float(total)))

        for fragment in FORBIDDEN_OUTPUT_FRAGMENTS:
            if fragment in target:
                errors.append(
                    {
                        "index": index,
                        "issue": "assistant_target_contains_input_or_fence",
                        "fragment": fragment,
                        "row_type": row.get("row_type"),
                    }
                )
                break

        try:
            parsed = json.loads(target)
        except json.JSONDecodeError as exc:
            errors.append(
                {
                    "index": index,
                    "issue": "assistant_target_invalid_json",
                    "error": str(exc),
                    "target_prefix": target[:160],
                }
            )
            continue

        function = parsed.get("function")
        if allowed_tools is not None and function not in allowed_tools:
            errors.append({"index": index, "issue": "function_not_allowed", "function": function})
        if function in INPUT_SECTION_NAMES:
            errors.append({"index": index, "issue": "function_is_input_section", "function": function})
        if not isinstance(parsed.get("args"), dict):
            errors.append({"index": index, "issue": "args_not_object", "function": function})

    return {
        "rows": len(rows),
        "row_types": dict(row_types.most_common()),
        "expected_functions": dict(expected_functions.most_common()),
        "target_error_count": len(errors),
        "target_errors_sample": errors[:25],
        "prompt_chars": stats(prompt_chars),
        "target_chars": stats(target_chars),
        "total_chars": stats(total_chars),
        "target_ratio": stats(target_ratio),
        "low_target_ratio_lt_8_percent": sum(1 for value in target_ratio if value < 0.08),
    }


def audit_notebook(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    sources = ["".join(cell.get("source", [])) for cell in payload.get("cells", [])]
    all_src = "\n".join(sources)
    training_cells = [
        {"index": index, "source": source}
        for index, source in enumerate(sources)
        if "SFTTrainer" in source or "trainer.train" in source or "to_text_dataset" in source
    ]
    training_src = "\n".join(cell["source"] for cell in training_cells)

    has_assistant_only_config = bool(
        re.search(
            r"\b(completion_only_loss|assistant_only_loss|DataCollatorForCompletionOnlyLM|loss_mask|assistant_mask)\b",
            training_src,
            re.IGNORECASE,
        )
    )
    has_explicit_ignore_labels = "-100" in training_src and "labels" in training_src
    uses_full_text_field = "to_text_dataset" in training_src and "dataset_text_field" in training_src
    uses_sft_text_field = "dataset_text_field" in training_src
    max_new_256 = "max_new_tokens=256" in all_src or "MAX_NEW_TOKENS', '256'" in all_src
    max_seq_3072 = "MAX_SEQ_LENGTH" in training_src and "3072" in training_src

    risks: list[str] = []
    if uses_full_text_field and not (has_assistant_only_config or has_explicit_ignore_labels):
        risks.append("full_text_sft_without_assistant_only_loss")
    if max_new_256:
        risks.append("eval_max_new_tokens_default_256_may_hide_or_create_truncation_failures")
    if not max_seq_3072:
        risks.append("max_seq_length_not_detected")

    return {
        "path": str(path),
        "training_cell_indices": [cell["index"] for cell in training_cells],
        "uses_full_text_field": uses_full_text_field,
        "uses_sft_text_field": uses_sft_text_field,
        "has_assistant_only_or_completion_only_config": has_assistant_only_config,
        "has_explicit_ignore_index_labels": has_explicit_ignore_labels,
        "max_seq_3072_detected": max_seq_3072,
        "eval_max_new_tokens_default_256_detected": max_new_256,
        "risks": risks,
    }


def render_messages(tokenizer: Any, messages: list[dict[str, Any]]) -> str:
    if hasattr(tokenizer, "apply_chat_template") and tokenizer.chat_template:
        return tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=False)
    return "\n".join(f"{msg.get('role')}: {msg.get('content')}" for msg in messages)


def audit_token_lengths(
    rows: list[dict[str, Any]],
    model_id: str,
    max_seq_length: int,
    limit: int,
) -> dict[str, Any]:
    try:
        from transformers import AutoTokenizer
    except Exception as exc:  # pragma: no cover - environment dependent
        return {"enabled": False, "error": f"transformers import failed: {type(exc).__name__}: {exc}"}

    token = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN")
    try:
        tokenizer = AutoTokenizer.from_pretrained(model_id, token=token, trust_remote_code=False)
    except Exception as exc:  # pragma: no cover - environment dependent
        return {"enabled": False, "error": f"tokenizer load failed: {type(exc).__name__}: {exc}"}

    selected = rows[:limit] if limit > 0 else rows
    full_lengths: list[float] = []
    prompt_lengths: list[float] = []
    target_lengths: list[float] = []
    overflow = 0
    likely_target_truncation = 0

    for row in selected:
        messages = row["messages"]
        target = str(messages[-1].get("content") or "")
        full_ids = tokenizer(render_messages(tokenizer, messages), add_special_tokens=False).input_ids
        prompt_ids = tokenizer(render_messages(tokenizer, messages[:-1]), add_special_tokens=False).input_ids
        target_ids = tokenizer(target, add_special_tokens=False).input_ids
        full_lengths.append(float(len(full_ids)))
        prompt_lengths.append(float(len(prompt_ids)))
        target_lengths.append(float(len(target_ids)))
        if len(full_ids) > max_seq_length:
            overflow += 1
        if len(prompt_ids) >= max_seq_length or len(prompt_ids) + len(target_ids) > max_seq_length:
            likely_target_truncation += 1

    return {
        "enabled": True,
        "model_id": model_id,
        "rows_checked": len(selected),
        "max_seq_length": max_seq_length,
        "full_tokens": stats(full_lengths),
        "prompt_tokens": stats(prompt_lengths),
        "target_tokens": stats(target_lengths),
        "rows_over_max_seq_length": overflow,
        "rows_with_possible_target_truncation": likely_target_truncation,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--notebook", type=Path, default=DEFAULT_NOTEBOOK)
    parser.add_argument("--generate-sample-if-missing", action="store_true")
    parser.add_argument("--sample-train-count", type=int, default=2000)
    parser.add_argument("--sample-validation-count", type=int, default=300)
    parser.add_argument("--base-model", default="", help="Optional tokenizer id/path for token truncation audit.")
    parser.add_argument("--max-seq-length", type=int, default=3072)
    parser.add_argument("--token-limit", type=int, default=1000)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()

    sys.path.insert(0, str(REPO_ROOT))
    from finetune.data.generate_v5_e2b_evidence_router import TOOLS

    dataset = load_dataset(
        args.dataset,
        args.generate_sample_if_missing,
        args.sample_train_count,
        args.sample_validation_count,
    )
    rows = flatten_dataset(dataset)
    report: dict[str, Any] = {
        "dataset": str(args.dataset),
        "dataset_version": dataset.get("version"),
        "dataset_metadata": dataset.get("metadata", {}),
        "target_audit": audit_targets(rows, set(TOOLS)),
        "notebook_audit": audit_notebook(args.notebook),
    }
    if args.base_model:
        report["token_audit"] = audit_token_lengths(rows, args.base_model, args.max_seq_length, args.token_limit)

    failures: list[str] = []
    if report["target_audit"]["target_error_count"]:
        failures.append("assistant_targets_invalid")
    if report["notebook_audit"]["risks"]:
        failures.extend(report["notebook_audit"]["risks"])
    token_audit = report.get("token_audit")
    if isinstance(token_audit, dict) and token_audit.get("enabled"):
        if token_audit.get("rows_with_possible_target_truncation", 0):
            failures.append("token_audit_possible_target_truncation")
    report["failures"] = failures
    report["passed"] = not failures

    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 1 if args.strict and failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
