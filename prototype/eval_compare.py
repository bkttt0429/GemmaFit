"""eval_compare.py — A/B benchmark for base vs fine-tuned Gemma 4.

Runs the 90 validation FC examples through one or two models and
produces:
  1. Per-example JSON log with raw outputs + scores
  2. Side-by-side HTML report (great for Kaggle judges)
  3. Summary stats: JSON parse rate, function match, arg overlap, latency

Usage:
  # Base model only (run before training to establish a baseline)
  python prototype/eval_compare.py \\
      --base models/gemma4-e4b-Q4_K_M.gguf \\
      --n 20

  # A/B: base vs fine-tuned (run after training completes)
  python prototype/eval_compare.py \\
      --base models/gemma4-e4b-Q4_K_M.gguf \\
      --ft   models/gemmafit-q5_k_m.gguf \\
      --n 90 \\
      --out docs/benchmark/ab_compare
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from dataclasses import dataclass, asdict, field
from pathlib import Path
from typing import List, Optional

PROTO_DIR = Path(__file__).resolve().parent
ROOT_DIR  = PROTO_DIR.parent
DEFAULT_VAL_PATH = ROOT_DIR / "finetune" / "data" / "fc_training_data.json"

try:
    from llama_cpp import Llama
    HAS_LLAMA = True
except Exception as e:
    HAS_LLAMA = False
    Llama = None  # type: ignore


SYSTEM_PROMPT = (
    "You are GemmaFit's structured coaching assistant. Given a motion-analysis "
    "input, respond with ONE valid JSON object describing the function call to "
    "invoke for the coach. Required schema:\n"
    '  {"function": "<function_name>", "args": {<args object>}}\n'
    "Output ONLY the JSON object — no prose, no markdown fences."
)


# ── Result dataclass ────────────────────────────────────────────────────────

@dataclass
class ExampleResult:
    idx: int
    input: dict
    expected: dict
    raw_output: str
    parsed: Optional[dict] = None
    json_ok: bool = False
    function_match: bool = False
    args_jaccard: float = 0.0
    elapsed_s: float = 0.0
    error: Optional[str] = None


# ── Inference helpers ────────────────────────────────────────────────────────

def _try_parse_json(text: str) -> Optional[dict]:
    """Best-effort JSON extraction. Tolerates code fences and surrounding prose."""
    text = text.strip()
    # Strip ```json ... ``` fences
    text = re.sub(r"^```(?:json)?\s*", "", text)
    text = re.sub(r"\s*```\s*$", "", text)
    # Find first {...} block
    m = re.search(r"\{.*\}", text, flags=re.DOTALL)
    if not m:
        return None
    candidate = m.group(0)
    try:
        return json.loads(candidate)
    except json.JSONDecodeError:
        # Try cutting off trailing junk after last }
        depth = 0
        end = -1
        for i, ch in enumerate(candidate):
            if ch == "{": depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    break
        if end > 0:
            try:
                return json.loads(candidate[:end])
            except Exception:
                return None
    return None


def _jaccard(a: dict, b: dict) -> float:
    """Jaccard similarity over (key, str(value)) pairs. 1.0 = identical."""
    if not a and not b: return 1.0
    if not a or not b: return 0.0
    sa = {(k, json.dumps(v, sort_keys=True, ensure_ascii=False)) for k, v in a.items()}
    sb = {(k, json.dumps(v, sort_keys=True, ensure_ascii=False)) for k, v in b.items()}
    inter = sa & sb
    union = sa | sb
    return len(inter) / len(union) if union else 1.0


def _run_one(llm, example: dict, idx: int) -> ExampleResult:
    inp = example["input"]
    expected = example["output"]
    user_msg = (
        f"Motion-analysis input:\n```json\n"
        f"{json.dumps(inp, ensure_ascii=False, indent=2)}\n```\n"
        f"Reply with a single JSON object: "
        f'{{"function": "...", "args": {{...}}}}'
    )
    t0 = time.time()
    err = None
    raw = ""
    try:
        resp = llm.create_chat_completion(
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": user_msg},
            ],
            max_tokens=200,
            temperature=0.1,
            stop=["\n\n\n"],
        )
        raw = resp["choices"][0]["message"]["content"]
    except Exception as e:
        err = f"{type(e).__name__}: {e}"
    elapsed = time.time() - t0

    parsed = _try_parse_json(raw)
    json_ok = parsed is not None and isinstance(parsed.get("function"), str)
    fn_match = bool(json_ok and parsed.get("function") == expected.get("function"))
    args_score = (
        _jaccard(parsed.get("args", {}) or {}, expected.get("args", {}) or {})
        if json_ok else 0.0
    )

    return ExampleResult(
        idx=idx, input=inp, expected=expected,
        raw_output=raw, parsed=parsed,
        json_ok=json_ok, function_match=fn_match,
        args_jaccard=round(args_score, 3),
        elapsed_s=round(elapsed, 2), error=err,
    )


def evaluate_model(model_path: str, val_data: List[dict], n_ctx: int = 2048,
                   max_n: Optional[int] = None) -> List[ExampleResult]:
    if not HAS_LLAMA:
        raise SystemExit("llama-cpp-python not installed. pip install llama-cpp-python "
                         "--only-binary=:all: --extra-index-url "
                         "https://abetlen.github.io/llama-cpp-python/whl/cpu")
    if not os.path.exists(model_path):
        raise SystemExit(f"Model not found: {model_path}")

    print(f"\n>>> Loading {model_path}", flush=True)
    t0 = time.time()
    llm = Llama(model_path=model_path, n_ctx=n_ctx, n_threads=None,
                n_gpu_layers=0, verbose=False)
    print(f"    Load time: {time.time()-t0:.1f}s", flush=True)

    n = len(val_data) if max_n is None else min(max_n, len(val_data))
    results: List[ExampleResult] = []
    for i in range(n):
        r = _run_one(llm, val_data[i], i)
        status = "OK " if r.json_ok else "BAD"
        fn = "[Y]" if r.function_match else "[N]"
        print(f"  [{i+1:3d}/{n}] {status} fn={fn} args={r.args_jaccard:.2f} "
              f"({r.elapsed_s:5.1f}s)  expected={r.expected.get('function','')}",
              flush=True)
        results.append(r)
    return results


# ── Reporting ────────────────────────────────────────────────────────────────

def summarize(results: List[ExampleResult]) -> dict:
    n = len(results)
    if not n: return {"n": 0}
    return {
        "n": n,
        "json_parse_rate": sum(r.json_ok for r in results) / n,
        "function_match_rate": sum(r.function_match for r in results) / n,
        "avg_args_jaccard": sum(r.args_jaccard for r in results) / n,
        "avg_latency_s": sum(r.elapsed_s for r in results) / n,
    }


def _format_dict(d) -> str:
    return json.dumps(d, ensure_ascii=False, indent=2) if d else "—"


def write_html(out_dir: Path, base_results: List[ExampleResult],
               ft_results: Optional[List[ExampleResult]],
               base_path: str, ft_path: Optional[str]) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    html_path = out_dir / "report.html"

    base_summary = summarize(base_results)
    ft_summary   = summarize(ft_results) if ft_results else None

    def _row_html(b: ExampleResult, f: Optional[ExampleResult]) -> str:
        b_fn = b.parsed.get("function", "—") if b.parsed else f"<span class=bad>parse fail</span>"
        b_class = "ok" if b.function_match else "bad"
        cells = [
            f"<td><pre>{_format_dict(b.input)}</pre></td>",
            f"<td><pre>{_format_dict(b.expected)}</pre></td>",
            f"<td class={b_class}><pre>{_format_dict(b.parsed) if b.parsed else b.raw_output[:200]}</pre></td>",
        ]
        if f is not None:
            f_class = "ok" if f.function_match else "bad"
            cells.append(
                f"<td class={f_class}><pre>{_format_dict(f.parsed) if f.parsed else f.raw_output[:200]}</pre></td>"
            )
        return f"<tr><td>{b.idx}</td>" + "".join(cells) + "</tr>"

    rows = []
    pairs = list(zip(base_results, ft_results)) if ft_results else \
            [(b, None) for b in base_results]
    for b, f in pairs[:60]:   # cap rendered rows
        rows.append(_row_html(b, f))

    headers = ["#", "Input", "Expected", f"Base ({Path(base_path).name})"]
    if ft_path: headers.append(f"Fine-tuned ({Path(ft_path).name})")
    header_html = "".join(f"<th>{h}</th>" for h in headers)

    summary_html = f"""
<table class=summary>
  <tr><th></th><th>Base</th>{'<th>Fine-tuned</th>' if ft_summary else ''}<th>Δ</th></tr>
  <tr><td>JSON parse rate</td>
      <td>{base_summary['json_parse_rate']*100:.1f}%</td>
      {f"<td>{ft_summary['json_parse_rate']*100:.1f}%</td>" if ft_summary else ''}
      <td>{(ft_summary['json_parse_rate'] - base_summary['json_parse_rate'])*100:+.1f}%</td>
  </tr>
  <tr><td>Function-name match</td>
      <td>{base_summary['function_match_rate']*100:.1f}%</td>
      {f"<td>{ft_summary['function_match_rate']*100:.1f}%</td>" if ft_summary else ''}
      <td>{(ft_summary['function_match_rate'] - base_summary['function_match_rate'])*100:+.1f}%</td>
  </tr>
  <tr><td>Args Jaccard (avg)</td>
      <td>{base_summary['avg_args_jaccard']:.3f}</td>
      {f"<td>{ft_summary['avg_args_jaccard']:.3f}</td>" if ft_summary else ''}
      <td>{(ft_summary['avg_args_jaccard'] - base_summary['avg_args_jaccard']):+.3f}</td>
  </tr>
  <tr><td>Avg latency (sec)</td>
      <td>{base_summary['avg_latency_s']:.2f}</td>
      {f"<td>{ft_summary['avg_latency_s']:.2f}</td>" if ft_summary else ''}
      <td>—</td>
  </tr>
</table>
""" if ft_summary else f"""
<table class=summary>
  <tr><th></th><th>Base</th></tr>
  <tr><td>JSON parse rate</td><td>{base_summary['json_parse_rate']*100:.1f}%</td></tr>
  <tr><td>Function-name match</td><td>{base_summary['function_match_rate']*100:.1f}%</td></tr>
  <tr><td>Args Jaccard (avg)</td><td>{base_summary['avg_args_jaccard']:.3f}</td></tr>
  <tr><td>Avg latency (sec)</td><td>{base_summary['avg_latency_s']:.2f}</td></tr>
</table>
"""

    html = f"""<!doctype html>
<html><head><meta charset='utf-8'><title>GemmaFit A/B benchmark</title>
<style>
  body {{ font-family: -apple-system, system-ui, Arial; background: #0f1724; color: #e0e0e0; margin: 0; padding: 24px; }}
  h1 {{ color: #00D4AA; }}
  table {{ border-collapse: collapse; width: 100%; margin: 16px 0; font-size: 12px; }}
  th, td {{ border: 1px solid #333; padding: 8px; text-align: left; vertical-align: top; }}
  th {{ background: #15202b; color: #4ECDC4; font-weight: bold; }}
  pre {{ margin: 0; white-space: pre-wrap; word-break: break-word; max-width: 380px; max-height: 220px; overflow: auto; font-family: ui-monospace, monospace; font-size: 11px; }}
  td.ok {{ background: #102a18; }}
  td.bad {{ background: #2a1010; }}
  table.summary {{ width: auto; }}
  table.summary th, table.summary td {{ padding: 10px 16px; }}
  .ok {{ color: #00D4AA; }} .bad {{ color: #FF6B6B; }}
</style></head>
<body>
  <h1>GemmaFit A/B benchmark</h1>
  <p>Base: <code>{base_path}</code></p>
  {f'<p>Fine-tuned: <code>{ft_path}</code></p>' if ft_path else ''}
  <h2>Summary</h2>
  {summary_html}
  <h2>Per-example results (first {len(rows)})</h2>
  <table>
    <thead><tr>{header_html}</tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table>
</body></html>
"""
    html_path.write_text(html, encoding="utf-8")
    return html_path


def write_json(out_dir: Path, base_results: List[ExampleResult],
               ft_results: Optional[List[ExampleResult]]) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    p = out_dir / "results.json"
    payload = {
        "summary_base": summarize(base_results),
        "summary_ft":   summarize(ft_results) if ft_results else None,
        "base":  [asdict(r) for r in base_results],
        "ft":    [asdict(r) for r in ft_results] if ft_results else None,
    }
    p.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                 encoding="utf-8")
    return p


# ── CLI ──────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True, help="Path to base GGUF")
    ap.add_argument("--ft",   default=None,  help="Path to fine-tuned GGUF (optional)")
    ap.add_argument("--n",    type=int, default=20,
                    help="Number of validation examples to run (default 20)")
    ap.add_argument("--val",  default=str(DEFAULT_VAL_PATH),
                    help="Path to fc_training_data.json")
    ap.add_argument("--out",  default=str(ROOT_DIR / "docs" / "benchmark"),
                    help="Output directory for report.html + results.json")
    ap.add_argument("--n-ctx", type=int, default=2048)
    args = ap.parse_args()

    val_path = Path(args.val)
    val_blob = json.loads(val_path.read_text(encoding="utf-8"))
    val_data = val_blob.get("validation") or val_blob.get("val") or val_blob
    print(f"Loaded {len(val_data)} validation examples from {val_path}")

    out_dir = Path(args.out)
    print(f"Output → {out_dir}")

    base_results = evaluate_model(args.base, val_data, args.n_ctx, args.n)

    ft_results = None
    if args.ft:
        ft_results = evaluate_model(args.ft, val_data, args.n_ctx, args.n)

    json_path = write_json(out_dir, base_results, ft_results)
    html_path = write_html(out_dir, base_results, ft_results,
                           args.base, args.ft)

    print()
    print("=" * 50)
    print(f"  JSON:    {json_path}")
    print(f"  HTML:    {html_path}")
    print()
    print("  Base summary:")
    for k, v in summarize(base_results).items():
        if isinstance(v, float):
            print(f"    {k:25s} = {v:.3f}")
        else:
            print(f"    {k:25s} = {v}")
    if ft_results:
        print()
        print("  Fine-tuned summary:")
        for k, v in summarize(ft_results).items():
            if isinstance(v, float):
                print(f"    {k:25s} = {v:.3f}")
            else:
                print(f"    {k:25s} = {v}")
    print("=" * 50)


if __name__ == "__main__":
    main()
