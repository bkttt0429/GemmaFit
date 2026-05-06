"""Smoke-test a GemmaFit v3 `.litertlm` model with 12 FC examples.

This runner shells out to the `litert-lm` CLI so the same artifact can be
validated before pushing it to Pixel internal storage. The smoke prompts use
the v3 contract shape: compact evidence DAG plus capability contract.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Any


ALLOWED = {
    "correct_knee_alignment",
    "correct_spinal_alignment",
    "correct_joint_angle",
    "correct_asymmetry",
    "warn_com_offset",
    "warn_rapid_movement",
    "increase_range_of_motion",
    "positive_reinforcement",
    "read_memory",
    "request_memory_update",
    "summarize_trend",
    "refuse_unsupported_question",
}


def evidence_case(
    expected: str,
    can_metric: str,
    evidence_id: str,
    value: float,
    status: str,
    question: str = "",
    cannot_metric: str | None = None,
    reason: str = "side_view",
) -> tuple[str, dict[str, Any]]:
    cannot_judge = []
    if cannot_metric:
        cannot_judge.append(
            {
                "metric": cannot_metric,
                "reason": reason,
                "required_evidence": ["better_view"],
                "evidence_refs": [f"capability.{cannot_metric}.blocked"],
            }
        )
    return expected, {
        "trigger": "SESSION_SUMMARY",
        "exercise": "squat",
        "session_summary": {
            "total_reps": 4,
            "avg_form_score": 86,
            "top_warnings": [{"function": expected, "metric": can_metric, "evidence_refs": [evidence_id]}]
            if status in {"WARNING", "MONITOR"}
            else [],
            "view_limited_count": 2 if cannot_metric else 0,
            "low_confidence_count": 0,
        },
        "capability_contract": {
            "can_judge": [{"metric": can_metric, "confidence_ceiling": 0.86, "evidence_refs": [evidence_id]}],
            "cannot_judge": cannot_judge,
        },
        "evidence_dag_compact": [
            {
                "id": evidence_id,
                "type": "template_metric",
                "metric": can_metric,
                "value": value,
                "confidence": 0.84,
                "status": status,
            }
        ],
        "user_question": question,
    }


SMOKE_CASES = [
    evidence_case("correct_knee_alignment", "frontal_knee_valgus", "metric.squat.knee_valgus_ratio", 0.72, "WARNING"),
    evidence_case("correct_spinal_alignment", "trunk_lean", "metric.squat.trunk_lean", 22.0, "WARNING"),
    evidence_case("correct_joint_angle", "knee_angle", "metric.squat.knee_angle", 178.0, "WARNING"),
    evidence_case("correct_asymmetry", "bilateral_asymmetry", "metric.squat.bilateral_asymmetry", 20.0, "WARNING"),
    evidence_case("warn_com_offset", "com_offset", "metric.squat.com_offset", 0.18, "MONITOR"),
    evidence_case("warn_rapid_movement", "tempo", "metric.squat.tempo", 680.0, "WARNING"),
    evidence_case("increase_range_of_motion", "squat_depth", "metric.squat.depth", 0.42, "WARNING"),
    evidence_case(
        "positive_reinforcement",
        "squat_depth",
        "metric.squat.depth",
        0.84,
        "OK",
        cannot_metric="frontal_knee_valgus",
    ),
    evidence_case("read_memory", "tempo_trend", "memory.trend.tempo_7d", 0.08, "MONITOR", "Read my local squat trend."),
    evidence_case(
        "request_memory_update",
        "tempo_trend",
        "memory.trend.tempo_7d",
        0.05,
        "MONITOR",
        "Store this non-clinical trend note.",
    ),
    evidence_case("summarize_trend", "tempo_trend", "memory.trend.tempo_7d", 0.11, "MONITOR", "Summarize my squat trend."),
    (
        "refuse_unsupported_question",
        {
            "trigger": "SESSION_SUMMARY",
            "exercise": "squat",
            "session_summary": {"total_reps": 3, "avg_form_score": 82, "top_warnings": [], "view_limited_count": 0, "low_confidence_count": 0},
            "capability_contract": {
                "can_judge": [{"metric": "squat_depth", "confidence_ceiling": 0.90, "evidence_refs": ["metric.squat.depth"]}],
                "cannot_judge": [
                    {
                        "metric": "force_or_emg_claim",
                        "reason": "product_boundary",
                        "required_evidence": ["force_plate_or_emg_sensor"],
                        "evidence_refs": ["capability.force_or_emg_claim.blocked"],
                    }
                ],
            },
            "evidence_dag_compact": [
                {"id": "metric.squat.depth", "type": "template_metric", "metric": "squat_depth", "value": 0.81, "confidence": 0.88, "status": "OK"},
                {"id": "capability.force_or_emg_claim.blocked", "type": "capability", "metric": "force_or_emg_claim", "value": 0.0, "confidence": 0.0, "status": "NOT_APPLICABLE"},
            ],
            "user_question": "What percentage is my glute activation?",
        },
    ),
]


def build_prompt(payload: dict[str, Any]) -> str:
    return json.dumps(
        {
            "task": "select_one_gemmafit_function_call",
            "input_contract": payload,
            "constraints": [
                "Return one tool call only.",
                "Use only metrics in capability_contract.can_judge.",
                "If the requested metric is in cannot_judge or outside product scope, call refuse_unsupported_question.",
                "Cite only evidence_refs that exist in input_contract.",
                "Do not provide medical, injury, force, fall-risk, sarcopenia, or muscle-activation claims.",
            ],
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )


def extract_tool_call(output: str) -> dict[str, Any] | None:
    for line in output.splitlines():
        if "[tool_call]" in line:
            _, raw = line.split("[tool_call]", 1)
            try:
                return json.loads(raw.strip())
            except json.JSONDecodeError:
                pass
    start = output.find("{")
    end = output.rfind("}")
    if 0 <= start < end:
        try:
            parsed = json.loads(output[start:end + 1])
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass
    name_match = re.search(r'"name"\s*:\s*"([^"]+)"', output) or re.search(r'"function"\s*:\s*"([^"]+)"', output)
    if name_match:
        return {"name": name_match.group(1)}
    return None


def run_case(model: Path, preset: Path, expected: str, payload: dict[str, Any], timeout: int) -> dict[str, Any]:
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
    call = extract_tool_call(raw) or {}
    actual = call.get("name") or call.get("function")
    args = call.get("args") or call.get("arguments") or call.get("parameters")
    args_parseable = isinstance(args, dict)
    return {
        "expected": expected,
        "actual": actual,
        "args_parseable": args_parseable,
        "pass": proc.returncode == 0 and actual == expected and actual in ALLOWED and args_parseable,
        "returncode": proc.returncode,
        "raw": raw[-4000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=Path, default=Path("models/gemmafit-v3-evidence-router.litertlm"))
    parser.add_argument("--preset", type=Path, default=Path("finetune/litert_gemmafit_tools.py"))
    parser.add_argument("--output", type=Path, default=Path("finetune/metrics/tool_call_eval_v3.json"))
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
        "allowed_tools": sorted(ALLOWED),
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
