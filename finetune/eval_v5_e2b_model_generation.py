"""Run real generation eval for the GemmaFit v5 E2B evidence router.

This evaluates the model output, not just the expected dataset labels:

dataset row input -> model generation -> JSON function call parser -> v5 gates

The model is still only judged as an evidence router. This script does not
evaluate raw video understanding, force/GRF/EMG estimation, or clinical claims.
"""

from __future__ import annotations

import argparse
import hashlib
import copy
import json
import os
import random
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from typing import Any, Iterable

from eval_v5_e2b_evidence_router import (
    check_gates,
    extract_json_object,
    evaluate_rows,
    subset_report,
)


DEFAULT_DATASET = Path("finetune/data/gemmafit_v5_2_e2b_evidence_router.json")
DEFAULT_OUTPUT = Path("finetune/metrics/model_generation_eval_v5_e2b.json")


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def expected_dataset_sha256(dataset_path: Path) -> str:
    metrics_path = repo_root() / "finetune" / "metrics" / "training_done_v5_e2b.json"
    if not metrics_path.exists():
        return ""
    try:
        payload = json.loads(metrics_path.read_text(encoding="utf-8"))
    except Exception:
        return ""
    done_dataset = payload.get("dataset_path", "")
    if done_dataset and Path(done_dataset).name != dataset_path.name:
        return ""
    return payload.get("dataset_sha256", "")


def restore_dataset_from_drive_package(dataset_path: Path, package_dir: Path) -> bool:
    if not package_dir.exists():
        return False
    member = dataset_path.as_posix()
    if not member.startswith("finetune/data/"):
        member = f"finetune/data/{dataset_path.name}"
    packages = sorted(
        [
            *package_dir.glob("gemmafit-v5-2-e2b-training-metadata-*.zip"),
            *package_dir.glob("gemmafit-v5-1-e2b-training-metadata-*.zip"),
        ],
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    for zip_path in packages:
        print(f"Checking dataset package: {zip_path}")
        with zipfile.ZipFile(zip_path) as zf:
            if member not in zf.namelist():
                continue
            dataset_path.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(member) as source, dataset_path.open("wb") as target:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    target.write(chunk)
            print(f"Restored dataset from package: {zip_path}")
            return True
    return False


def regenerate_dataset(dataset_path: Path) -> None:
    generator = repo_root() / "finetune" / "data" / "generate_v5_e2b_evidence_router.py"
    if not generator.exists():
        raise FileNotFoundError(f"Dataset generator not found: {generator}")
    is_v5_2 = "v5_2" in dataset_path.name
    print(f"No Drive dataset package found; regenerating deterministic {'v5.2' if is_v5_2 else 'v5.1'} hard-case dataset.")
    cmd = [
        sys.executable,
        str(generator),
        "--train-count",
        "50000",
        "--validation-count",
        "6000",
        "--out",
        str(dataset_path),
        "--validate",
        "--hard-cases",
        "--zh-tw-ratio",
        "0.45",
        "--schema-fuzz-ratio",
        "0.25",
    ]
    if is_v5_2:
        cmd.append("--tool-contract-v2")
    print("Running:", " ".join(cmd))
    subprocess.run(cmd, check=True, cwd=str(repo_root()))


def ensure_dataset_available(dataset_path: Path, package_dir: Path, allow_regenerate: bool) -> None:
    if dataset_path.exists():
        return
    print(f"Dataset missing: {dataset_path}")
    restored = restore_dataset_from_drive_package(dataset_path, package_dir)
    if not restored:
        if not allow_regenerate:
            raise FileNotFoundError(
                f"Dataset not found and restore package unavailable: {dataset_path}. "
                "Pass --allow-regenerate-dataset to rebuild it."
            )
        regenerate_dataset(dataset_path)
    if not dataset_path.exists():
        raise FileNotFoundError(f"Dataset still missing after restore/generate: {dataset_path}")

    expected_sha = expected_dataset_sha256(dataset_path)
    if expected_sha:
        actual_sha = file_sha256(dataset_path)
        print(f"Dataset SHA256: {actual_sha}")
        print(f"Expected SHA256: {expected_sha}")
        if actual_sha != expected_sha:
            raise RuntimeError("Dataset SHA256 mismatch. Refusing to evaluate against a different dataset.")


def parse_csv(value: str) -> set[str]:
    return {item.strip() for item in value.split(",") if item.strip()}


def iter_selected_rows(
    dataset: dict[str, Any],
    *,
    splits: set[str],
    row_types: set[str],
    include_train: bool,
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    validation = dataset.get("validation", {})
    selected_splits = set(validation) if not splits or splits == {"all"} else splits

    for split_name, split_rows in validation.items():
        if split_name in selected_splits:
            rows.extend(split_rows)

    if include_train:
        rows.extend(dataset.get("train", []))

    if row_types and row_types != {"all"}:
        rows = [row for row in rows if row.get("row_type", "") in row_types]
    return rows


def balanced_sample(rows: list[dict[str, Any]], limit: int, seed: int) -> list[dict[str, Any]]:
    if limit <= 0 or len(rows) <= limit:
        return rows

    rng = random.Random(seed)
    by_type: dict[str, list[dict[str, Any]]] = {}
    for row in rows:
        by_type.setdefault(row.get("row_type", "unknown"), []).append(row)
    for type_rows in by_type.values():
        rng.shuffle(type_rows)

    selected: list[dict[str, Any]] = []
    keys = sorted(by_type)
    while len(selected) < limit and keys:
        next_keys: list[str] = []
        for key in keys:
            bucket = by_type[key]
            if bucket and len(selected) < limit:
                selected.append(bucket.pop())
            if bucket:
                next_keys.append(key)
        keys = next_keys
    rng.shuffle(selected)
    return selected


def prompt_messages(row: dict[str, Any]) -> list[dict[str, str]]:
    messages = row.get("messages", [])
    if not messages:
        raise ValueError("row has no messages")
    if messages[-1].get("role") == "assistant":
        return messages[:-1]
    return messages


def render_prompt(row: dict[str, Any], tokenizer: Any) -> str:
    messages = prompt_messages(row)
    if hasattr(tokenizer, "apply_chat_template") and tokenizer.chat_template:
        return tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    return "\n".join(f"{msg['role']}: {msg['content']}" for msg in messages) + "\nassistant:"


def expected_output(row: dict[str, Any]) -> dict[str, Any] | None:
    for msg in reversed(row.get("messages", [])):
        if msg.get("role") == "assistant":
            try:
                return json.loads(msg.get("content", "{}"))
            except json.JSONDecodeError:
                return None
    return None


def generated_eval_row(row: dict[str, Any], parsed: dict[str, Any] | None, raw: str) -> dict[str, Any]:
    output_row = copy.deepcopy(row)
    messages = prompt_messages(output_row)
    if parsed is None:
        assistant_content = raw
    else:
        assistant_content = json.dumps(parsed, ensure_ascii=False)
    output_row["messages"] = messages + [{"role": "assistant", "content": assistant_content}]
    return output_row


def load_token() -> str | None:
    for name in ("HF_TOKEN", "HUGGING_FACE_HUB_TOKEN", "HUGGINGFACE_TOKEN"):
        value = os.environ.get(name)
        if value:
            return value
    return None


def load_generation_pipeline(args: argparse.Namespace) -> tuple[Any, Any]:
    try:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer, pipeline
    except Exception as exc:  # pragma: no cover - only hit in incomplete Colab envs.
        raise SystemExit(
            "Missing generation dependencies. Install transformers/torch first, "
            f"then rerun. Original error: {exc}"
        ) from exc

    dtype_map = {
        "auto": "auto",
        "float16": torch.float16,
        "bfloat16": torch.bfloat16,
        "float32": torch.float32,
    }
    dtype = dtype_map[args.dtype]
    token = load_token()

    tokenizer = AutoTokenizer.from_pretrained(
        args.model,
        token=token,
        trust_remote_code=args.trust_remote_code,
    )

    model_kwargs: dict[str, Any] = {
        "token": token,
        "device_map": args.device_map,
        "trust_remote_code": args.trust_remote_code,
    }
    if dtype != "auto":
        model_kwargs["torch_dtype"] = dtype
    if args.load_in_4bit:
        try:
            from transformers import BitsAndBytesConfig
        except Exception as exc:  # pragma: no cover
            raise SystemExit("load_in_4bit requires bitsandbytes-compatible transformers.") from exc
        compute_dtype = torch.float16 if dtype == "auto" else dtype
        model_kwargs["quantization_config"] = BitsAndBytesConfig(
            load_in_4bit=True,
            bnb_4bit_compute_dtype=compute_dtype,
        )

    model = AutoModelForCausalLM.from_pretrained(args.model, **model_kwargs)
    text_gen = pipeline("text-generation", model=model, tokenizer=tokenizer)
    return tokenizer, text_gen


def generate_one(prompt: str, text_gen: Any, args: argparse.Namespace) -> str:
    outputs = text_gen(
        prompt,
        max_new_tokens=args.max_new_tokens,
        do_sample=False,
        return_full_text=False,
    )
    return outputs[0]["generated_text"]


def row_type_summary(rows: Iterable[dict[str, Any]], validations: list[dict[str, Any]]) -> dict[str, Any]:
    total_by_type: dict[str, int] = {}
    failed_by_type: dict[str, int] = {}
    for row, validation in zip(rows, validations):
        row_type = row.get("row_type", "unknown")
        total_by_type[row_type] = total_by_type.get(row_type, 0) + 1
        if validation.get("issues"):
            failed_by_type[row_type] = failed_by_type.get(row_type, 0) + 1
    return {
        row_type: {
            "total": total,
            "failures": failed_by_type.get(row_type, 0),
            "pass_rate": (total - failed_by_type.get(row_type, 0)) / total if total else 0.0,
        }
        for row_type, total in sorted(total_by_type.items())
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="Merged HF model path or Hub model id.")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument(
        "--dataset-package-dir",
        type=Path,
        default=Path(os.environ.get("GEMMAFIT_DATASET_PACKAGE_DIR", "/content/drive/MyDrive/GemmaFit_train/artifact_packages")),
        help="Directory containing gemmafit-v5-1-e2b-training-metadata-*.zip packages.",
    )
    parser.add_argument(
        "--allow-regenerate-dataset",
        action="store_true",
        default=os.environ.get("ALLOW_REGENERATE_DATASET", "1") == "1",
        help="Regenerate the deterministic v5.1 dataset if no Drive package is found.",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--predictions-output", type=Path)
    parser.add_argument("--limit", type=int, default=200, help="Number of held-out rows to generate. Use <=0 for all.")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--splits", default="all", help="Validation split names, comma-separated, or all.")
    parser.add_argument("--row-types", default="all", help="Row types, comma-separated, or all.")
    parser.add_argument("--include-train", action="store_true", help="Also sample train rows. Off by default.")
    parser.add_argument("--max-new-tokens", type=int, default=768)
    parser.add_argument("--device-map", default="auto")
    parser.add_argument("--dtype", choices=["auto", "float16", "bfloat16", "float32"], default="float16")
    parser.add_argument("--load-in-4bit", action="store_true")
    parser.add_argument("--trust-remote-code", action="store_true")
    parser.add_argument("--sample-output-count", type=int, default=12)
    parser.add_argument(
        "--progress-every",
        type=int,
        default=10,
        help="Print generation progress every N rows. Use <=0 to disable.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Select rows and render prompt previews without loading model.")
    parser.add_argument("--strict", action="store_true", help="Return non-zero when generation eval gates fail.")
    return parser


def main() -> int:
    args = build_arg_parser().parse_args()

    ensure_dataset_available(args.dataset, args.dataset_package_dir, args.allow_regenerate_dataset)
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    rows = iter_selected_rows(
        dataset,
        splits=parse_csv(args.splits),
        row_types=parse_csv(args.row_types),
        include_train=args.include_train,
    )
    rows = balanced_sample(rows, args.limit, args.seed)
    if not rows:
        raise SystemExit("No rows selected for generation eval.")

    if args.dry_run:
        preview = {
            "dataset": str(args.dataset),
            "selected_rows": len(rows),
            "row_types": sorted({row.get("row_type", "unknown") for row in rows}),
            "first_expected": expected_output(rows[0]),
            "first_prompt_messages": prompt_messages(rows[0]),
        }
        write_json(args.output, preview)
        print(json.dumps(preview, indent=2, ensure_ascii=False))
        return 0

    tokenizer, text_gen = load_generation_pipeline(args)
    generated_rows: list[dict[str, Any]] = []
    predictions: list[dict[str, Any]] = []
    parse_success = 0
    started = time.time()
    total_rows = len(rows)
    progress_every = max(args.progress_every, 0)

    for index, row in enumerate(rows, start=1):
        prompt = render_prompt(row, tokenizer)
        raw = generate_one(prompt, text_gen, args)
        parsed = None
        parse_error = ""
        try:
            parsed = extract_json_object(raw)
            parse_success += 1
        except Exception as exc:
            parse_error = str(exc)

        generated_rows.append(generated_eval_row(row, parsed, raw))
        predictions.append(
            {
                "index": index - 1,
                "row_type": row.get("row_type", ""),
                "expected_function": row.get("expected_function", ""),
                "expected": expected_output(row),
                "parsed": parsed,
                "parse_error": parse_error,
                "raw_tail": raw[-1200:],
            }
        )
        if progress_every and (index == 1 or index % progress_every == 0 or index == total_rows):
            elapsed = time.time() - started
            avg_sec = elapsed / max(index, 1)
            eta_sec = avg_sec * max(total_rows - index, 0)
            pct = (index / total_rows * 100.0) if total_rows else 100.0
            print(
                "GEN_EVAL_PROGRESS "
                f"{index}/{total_rows} ({pct:.1f}%) "
                f"parse_ok={parse_success}/{index} "
                f"elapsed_min={elapsed / 60.0:.1f} "
                f"avg_sec_per_row={avg_sec:.2f} "
                f"eta_min={eta_sec / 60.0:.1f}"
            )
            sys.stdout.flush()

    report = evaluate_rows(generated_rows)
    validations = report.pop("validations")
    report["gate_failures"] = check_gates(report["summary"])
    generation_summary = {
        "model": args.model,
        "dataset": str(args.dataset),
        "selected_rows": len(rows),
        "limit": args.limit,
        "splits": args.splits,
        "row_types": args.row_types,
        "json_parse_rate": parse_success / len(rows),
        "elapsed_sec": round(time.time() - started, 3),
        "max_new_tokens": args.max_new_tokens,
        "device_map": args.device_map,
        "dtype": args.dtype,
        "load_in_4bit": args.load_in_4bit,
        "progress_every": args.progress_every,
    }
    report["generation"] = generation_summary
    if generation_summary["json_parse_rate"] < 0.98:
        report["gate_failures"].append("json_parse_rate<0.98")
    report["row_type_summary"] = row_type_summary(rows, validations)
    report["samples"] = predictions[: max(args.sample_output_count, 0)]
    report["failure_samples"] = [
        {**predictions[item["index"]], "issues": item["issues"]}
        for item in validations
        if item.get("issues")
    ][:100]
    report["refusal_subset"] = subset_report(validations, {"unsupported", "unsupported_zh_tw"})
    report["adversarial_subset"] = subset_report(validations, {"adversarial"})

    write_json(args.output, report)
    if args.predictions_output:
        write_jsonl(args.predictions_output, predictions)

    print(json.dumps({"summary": report["summary"], "generation": generation_summary, "gate_failures": report["gate_failures"]}, indent=2))
    return 1 if args.strict and report["gate_failures"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
