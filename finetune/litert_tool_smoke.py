"""Smoke-test a GemmaFit `.litertlm` model with eight FC examples.

This runner shells out to the `litert-lm` CLI so the same artifact can be
validated before pushing it to Pixel internal storage.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path


ALLOWED = {
    "correct_knee_alignment",
    "correct_spinal_alignment",
    "correct_joint_angle",
    "correct_asymmetry",
    "warn_com_offset",
    "warn_rapid_movement",
    "increase_range_of_motion",
    "positive_reinforcement",
}


SMOKE_CASES = [
    ("correct_knee_alignment", {"rule": 1, "status": "WARNING", "knee_valgus_ratio": 0.72}),
    ("correct_spinal_alignment", {"rule": 2, "status": "WARNING", "trunk_lean": 22}),
    ("correct_joint_angle", {"rule": 3, "status": "WARNING", "joint": "elbow", "angle": 178}),
    ("correct_asymmetry", {"rule": 4, "status": "WARNING", "left_knee": 92, "right_knee": 112}),
    ("warn_com_offset", {"rule": 5, "status": "MONITOR", "com_offset_ratio": 1.12}),
    ("warn_rapid_movement", {"rule": 6, "status": "WARNING", "tempo_deg_s": 680}),
    ("increase_range_of_motion", {"rule": 7, "status": "WARNING", "knee_rom": 38}),
    ("positive_reinforcement", {"status": "OK", "exercise": "squat", "phase": "ascent", "clean_streak": 30}),
]


def build_prompt(payload: dict) -> str:
    return json.dumps(
        {
            "task": "select_one_gemmafit_function_call",
            "movement": payload,
            "constraints": [
                "Return one tool call only.",
                "Do not provide medical or injury claims.",
                "Use evidence fields in the arguments when helpful.",
            ],
        },
        separators=(",", ":"),
    )


def extract_tool_name(output: str) -> str | None:
    for line in output.splitlines():
        if "[tool_call]" in line:
            _, raw = line.split("[tool_call]", 1)
            try:
                return json.loads(raw.strip()).get("name")
            except json.JSONDecodeError:
                pass
    match = re.search(r'"name"\s*:\s*"([^"]+)"', output)
    if match:
        return match.group(1)
    match = re.search(r'"function"\s*:\s*"([^"]+)"', output)
    if match:
        return match.group(1)
    return None


def run_case(model: Path, preset: Path, expected: str, payload: dict, timeout: int) -> dict:
    prompt = build_prompt(payload)
    cmd = [
        "litert-lm",
        "run",
        str(model),
        "--preset",
        str(preset),
        "--prompt",
        prompt,
    ]
    proc = subprocess.run(
        cmd,
        text=True,
        capture_output=True,
        timeout=timeout,
        check=False,
    )
    raw = proc.stdout + proc.stderr
    actual = extract_tool_name(raw)
    return {
        "expected": expected,
        "actual": actual,
        "pass": proc.returncode == 0 and actual == expected and actual in ALLOWED,
        "returncode": proc.returncode,
        "raw": raw[-4000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=Path("models/gemmafit-v2-fc.litertlm"))
    parser.add_argument("--preset", type=Path, default=Path("finetune/litert_gemmafit_tools.py"))
    parser.add_argument("--output", type=Path, default=Path("finetune/metrics/tool_call_eval.json"))
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()

    if not args.model.exists():
        raise SystemExit(f"LiteRT-LM model not found: {args.model}")
    if not args.preset.exists():
        raise SystemExit(f"Preset not found: {args.preset}")

    results = [
        run_case(args.model, args.preset, expected, payload, args.timeout)
        for expected, payload in SMOKE_CASES
    ]
    summary = {
        "model": str(args.model),
        "passed": sum(1 for item in results if item["pass"]),
        "total": len(results),
        "results": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({k: summary[k] for k in ("model", "passed", "total")}, indent=2))
    return 0 if summary["passed"] == summary["total"] else 1


if __name__ == "__main__":
    sys.exit(main())
