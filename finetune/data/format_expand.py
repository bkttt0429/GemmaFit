"""
format_expand.py — Expand each (input, output) pair into multiple chat-format variants.

Why: v1 fine-tune used a single bare prompt format ("Motion report:\n{compact json}").
The Android app sends a richer format (system prompt + ```json``` fences + reply
instruction). Result: FT model never saw production-style prompts during training
and fails on them at inference.

This script reads fc_training_data.json and produces fc_training_data_chat.json
where each train example is expanded into N variants. The 'validation' split is
expanded too, but each val example produces only ONE variant per format so the
eval set stays at the same logical size per format.

Output schema (chat-ready, matches the notebook's interleave_datasets stage):
  {
    "messages": [
      {"role": "system", "content": "..."},   // optional, only for production
      {"role": "user", "content": "..."},
      {"role": "assistant", "content": "<json output>"}
    ],
    "format": "production|bare|terse|chinese",
    "src_idx": 17                              // index in original train list
  }

Usage:
  python finetune/data/format_expand.py
  python finetune/data/format_expand.py --in fc_training_data.json --out fc_training_data_chat.json
"""
from __future__ import annotations

import argparse
import json
import random
from pathlib import Path
from typing import List, Dict

# Must stay in sync with prototype/eval_compare.py SYSTEM_PROMPT
SYSTEM_PROMPT = (
    "You are GemmaFit's structured coaching assistant. Given a motion-analysis "
    "input, respond with ONE valid JSON object describing the function call to "
    "invoke for the coach. Required schema:\n"
    '  {"function": "<function_name>", "args": {<args object>}}\n'
    "Output ONLY the JSON object — no prose, no markdown fences."
)

# Variant weights. Production is weighted highest because it matches the app.
# Sum doesn't have to be 1.0 — each train example produces variants weighted
# by these probabilities, sampled with replacement.
VARIANT_WEIGHTS = {
    "production": 0.50,
    "bare":       0.25,
    "terse":      0.15,
    "chinese":    0.10,
}


def _user_msg(inp: dict, fmt: str) -> str:
    if fmt == "production":
        return (
            f"Motion-analysis input:\n```json\n"
            f"{json.dumps(inp, ensure_ascii=False, indent=2)}\n```\n"
            f"Reply with a single JSON object: "
            f'{{"function": "...", "args": {{...}}}}'
        )
    if fmt == "bare":
        return f"Motion report:\n{json.dumps(inp, ensure_ascii=False)}"
    if fmt == "terse":
        return json.dumps(inp, ensure_ascii=False)
    if fmt == "chinese":
        return f"動作分析輸入：\n{json.dumps(inp, ensure_ascii=False)}"
    raise ValueError(f"Unknown variant: {fmt}")


def _make_messages(inp: dict, out: dict, fmt: str) -> List[Dict]:
    msgs: List[Dict] = []
    if fmt == "production":
        msgs.append({"role": "system", "content": SYSTEM_PROMPT})
    msgs.append({"role": "user",   "content": _user_msg(inp, fmt)})
    # Compact JSON in the assistant turn — production prompt explicitly forbids
    # markdown fences, and consistency across formats helps the model commit to
    # one output style.
    msgs.append({"role": "assistant",
                 "content": json.dumps(out, ensure_ascii=False)})
    return msgs


def _expand_train(rows: List[dict], variants_per_example: int,
                  seed: int = 42) -> List[dict]:
    rng = random.Random(seed)
    formats = list(VARIANT_WEIGHTS.keys())
    weights = list(VARIANT_WEIGHTS.values())
    out: List[dict] = []
    for src_idx, ex in enumerate(rows):
        # Sample N distinct variants per example. If N > len(formats), fall back
        # to with-replacement; otherwise weighted-sample without replacement.
        if variants_per_example >= len(formats):
            chosen = formats[:]
            extras = rng.choices(formats, weights=weights,
                                 k=variants_per_example - len(formats))
            chosen.extend(extras)
        else:
            chosen = rng.choices(formats, weights=weights,
                                 k=variants_per_example)
            # Force at least one production sample per example for coverage
            if "production" not in chosen and variants_per_example >= 1:
                chosen[0] = "production"
        for fmt in chosen:
            out.append({
                "messages": _make_messages(ex["input"], ex["output"], fmt),
                "format":   fmt,
                "src_idx":  src_idx,
            })
    return out


def _expand_val(rows: List[dict]) -> Dict[str, List[dict]]:
    """Return per-format val sets so we can report metric per format."""
    per_fmt: Dict[str, List[dict]] = {f: [] for f in VARIANT_WEIGHTS}
    for src_idx, ex in enumerate(rows):
        for fmt in VARIANT_WEIGHTS:
            per_fmt[fmt].append({
                "messages": _make_messages(ex["input"], ex["output"], fmt),
                "format":   fmt,
                "src_idx":  src_idx,
            })
    return per_fmt


def main() -> None:
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--in",  dest="inp",  default=str(here / "fc_training_data.json"))
    ap.add_argument("--out", dest="outp", default=str(here / "fc_training_data_chat.json"))
    ap.add_argument("--variants-per-train", type=int, default=4,
                    help="Number of prompt-format variants per train example (default 4)")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    data = json.loads(Path(args.inp).read_text(encoding="utf-8"))
    train_rows = data["train"]
    val_rows   = data["validation"]

    train_chat = _expand_train(train_rows, args.variants_per_train, args.seed)
    val_chat_per_fmt = _expand_val(val_rows)

    payload = {
        "meta": {
            **data.get("meta", {}),
            "expansion": {
                "variants_per_train": args.variants_per_train,
                "variant_weights":    VARIANT_WEIGHTS,
                "system_prompt":      SYSTEM_PROMPT,
                "train_rows_in":      len(train_rows),
                "train_rows_out":     len(train_chat),
                "val_rows_in":        len(val_rows),
                "val_per_format_out": {f: len(v) for f, v in val_chat_per_fmt.items()},
                "seed":               args.seed,
            },
        },
        "train":      train_chat,
        "validation": val_chat_per_fmt,
    }
    Path(args.outp).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    # Console summary
    from collections import Counter
    fmts = Counter(r["format"] for r in train_chat)
    print(f"Wrote {args.outp}")
    print(f"  train: {len(train_rows)} → {len(train_chat)} ({args.variants_per_train}x)")
    for f, c in fmts.most_common():
        print(f"    {f:12s} {c:5d}  ({c/len(train_chat)*100:.1f}%)")
    print(f"  validation: {len(val_rows)} per format × {len(VARIANT_WEIGHTS)} formats "
          f"= {len(val_rows) * len(VARIANT_WEIGHTS)} eval rows")


if __name__ == "__main__":
    main()
