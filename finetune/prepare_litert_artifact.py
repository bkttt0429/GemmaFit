"""Finalize a converted GemmaFit LiteRT-LM artifact for app testing."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--profile",
        choices=("v3", "v5-e2b"),
        default="v3",
        help="Default artifact naming profile. Explicit path args still win.",
    )
    parser.add_argument("--source-litertlm", type=Path)
    parser.add_argument("--source-bundle", type=Path, help="Zip created by the Colab v3 LiteRT export cell")
    parser.add_argument("--dest", type=Path)
    parser.add_argument("--training-done", type=Path)
    parser.add_argument("--smoke-output", type=Path)
    parser.add_argument("--smoke-preset", type=Path)
    parser.add_argument("--run-smoke", action="store_true")
    args = parser.parse_args()

    defaults = {
        "v3": {
            "dest": Path("models/gemmafit-v3-evidence-router.litertlm"),
            "training_done": Path("finetune/metrics/training_done_v3.json"),
            "smoke_output": Path("finetune/metrics/tool_call_eval_v3.json"),
            "smoke_preset": Path("finetune/litert_gemmafit_tools.py"),
            "version": "v3_evidence_router",
            "run_suffix": "gemmafit_v3_evidence_router",
        },
        "v5-e2b": {
            "dest": Path("models/gemmafit-v5-e2b-evidence-router.litertlm"),
            "training_done": Path("finetune/metrics/training_done_v5_e2b.json"),
            "smoke_output": Path("finetune/metrics/tool_call_eval_v5_e2b.json"),
            "smoke_preset": Path("finetune/litert_gemmafit_tools_v5.py"),
            "version": "v5_e2b_evidence_router",
            "run_suffix": "gemmafit_v5_e2b_evidence_router",
        },
    }[args.profile]

    args.dest = args.dest or defaults["dest"]
    args.training_done = args.training_done or defaults["training_done"]
    args.smoke_output = args.smoke_output or defaults["smoke_output"]
    args.smoke_preset = args.smoke_preset or defaults["smoke_preset"]

    if not args.source_litertlm and not args.source_bundle:
        raise SystemExit("Provide --source-litertlm or --source-bundle")

    if args.source_bundle:
        if not args.source_bundle.exists():
            raise SystemExit(f"Bundle not found: {args.source_bundle}")
        with zipfile.ZipFile(args.source_bundle) as bundle:
            names = bundle.namelist()
            unsafe = [
                name for name in names
                if Path(name).is_absolute() or ".." in Path(name).parts
            ]
            if unsafe:
                raise SystemExit(f"Unsafe path in bundle: {unsafe[0]}")
            litert_members = [name for name in names if name.endswith(".litertlm")]
            if not litert_members:
                raise SystemExit("Bundle does not contain a .litertlm artifact")
            args.dest.parent.mkdir(parents=True, exist_ok=True)
            with bundle.open(litert_members[0]) as src, args.dest.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            for name in names:
                if name.startswith("finetune/metrics/"):
                    out = Path(name)
                    out.parent.mkdir(parents=True, exist_ok=True)
                    with bundle.open(name) as src, out.open("wb") as dst:
                        shutil.copyfileobj(src, dst)
        args.source_litertlm = args.dest

    if not args.source_litertlm.exists():
        raise SystemExit(f"Converted .litertlm not found: {args.source_litertlm}")
    if args.source_litertlm.suffix != ".litertlm":
        raise SystemExit("Expected a .litertlm source artifact")

    args.dest.parent.mkdir(parents=True, exist_ok=True)
    if args.source_litertlm.resolve() != args.dest.resolve():
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
                "--preset",
                str(args.smoke_preset),
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
            "version": done.get("version", defaults["version"]),
            "run_suffix": done.get("run_suffix", defaults["run_suffix"]),
            "litertlm_path": str(args.dest),
            "conversion_status": (
                "ready_for_android"
                if smoke_status == "pass"
                else "converted_unverified"
                if smoke_status == "not_run"
                else "smoke_failed"
            ),
            "conversion_log": {
                "profile": args.profile,
                "source_litertlm": str(args.source_litertlm),
                "dest": str(args.dest),
                "smoke_preset": str(args.smoke_preset),
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
