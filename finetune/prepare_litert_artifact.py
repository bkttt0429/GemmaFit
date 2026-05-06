"""Finalize a converted GemmaFit LiteRT-LM artifact for app testing."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-litertlm", type=Path, required=True)
    parser.add_argument("--dest", type=Path, default=Path("models/gemmafit-v3-evidence-router.litertlm"))
    parser.add_argument("--training-done", type=Path, default=Path("finetune/metrics/training_done_v3.json"))
    parser.add_argument("--smoke-output", type=Path, default=Path("finetune/metrics/tool_call_eval_v3.json"))
    parser.add_argument("--run-smoke", action="store_true")
    args = parser.parse_args()

    if not args.source_litertlm.exists():
        raise SystemExit(f"Converted .litertlm not found: {args.source_litertlm}")
    if args.source_litertlm.suffix != ".litertlm":
        raise SystemExit("Expected a .litertlm source artifact")

    args.dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(args.source_litertlm, args.dest)

    smoke_status = "not_run"
    smoke_output = str(args.smoke_output)
    if args.run_smoke:
        proc = subprocess.run(
            [
                sys.executable,
                "finetune/litert_tool_smoke.py",
                "--model",
                str(args.dest),
                "--output",
                smoke_output,
            ],
            text=True,
            check=False,
        )
        smoke_status = "pass" if proc.returncode == 0 else "fail"

    done = {}
    if args.training_done.exists():
        done = json.loads(args.training_done.read_text(encoding="utf-8"))
    done.update(
        {
            "version": done.get("version", "v3_evidence_router"),
            "run_suffix": done.get("run_suffix", "gemmafit_v3_evidence_router"),
            "litertlm_path": str(args.dest),
            "conversion_status": "ready_for_android" if smoke_status in {"pass", "not_run"} else "smoke_failed",
            "conversion_log": {
                "source_litertlm": str(args.source_litertlm),
                "dest": str(args.dest),
                "smoke_status": smoke_status,
            },
            "tool_call_eval": smoke_output if args.run_smoke else done.get("tool_call_eval", smoke_output),
        }
    )
    args.training_done.parent.mkdir(parents=True, exist_ok=True)
    args.training_done.write_text(json.dumps(done, indent=2), encoding="utf-8")
    print(json.dumps(done["conversion_log"], indent=2))
    return 0 if smoke_status != "fail" else 1


if __name__ == "__main__":
    sys.exit(main())
