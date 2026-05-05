"""
test_regional_confidence_video.py — verify Algorithm #2.

For each test video:
  1. Pull the 5th captured frame's landmarks (skip warm-up frames).
  2. Compute baseline RegionalConfidence — should pass for the matching template.
  3. Synthesize an occluded frame by zeroing visibility on `lower_body`
     (or another region under test).
  4. Re-run gate — required-region failures should now fire.

Expected results:
  - squat baseline → passes lower_body+torso gate
  - squat lower-occluded → fails lower_body
  - push_up baseline → passes upper_body+torso gate
  - push_up lower-occluded → still passes (push_up doesn't require lower_body)
  - push_up upper-occluded → fails upper_body
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from multi_person_pose import MultiPersonPose
from regional_confidence import (
    REGIONS,
    apply_regional_gate,
    occlude_region,
)
from subject_selector import SubjectSelector, build_candidate

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "regional_confidence_smoke"

CASES = [
    {
        "label": "squat_wikimedia",
        "video": PROJECT_ROOT / "test_assets" / "videos" / "squat_wikimedia_01.webm",
        "exercise": "squat",
        "expect_baseline_pass": True,
        "occlusion_targets": [
            {"region": "lower_body", "expect_pass": False},
            {"region": "head",       "expect_pass": True},
        ],
    },
    {
        "label": "pushup_cdc",
        "video": PROJECT_ROOT / "test_assets" / "videos" / "pushup_cdc_01.webm",
        "exercise": "push_up",
        "expect_baseline_pass": True,
        "occlusion_targets": [
            {"region": "lower_body", "expect_pass": True},   # push_up doesn't need lower body
            {"region": "upper_body", "expect_pass": False},
            {"region": "torso",      "expect_pass": False},
        ],
    },
]


def grab_landmarks(video: Path, frame_skip: int = 5) -> Optional[np.ndarray]:
    """Return the first reasonably-confident pose landmarks in the video."""
    if not video.exists():
        return None
    selector = SubjectSelector()
    with MultiPersonPose(num_poses=1) as pose:
        for i, fc in enumerate(pose.iter_video(video, sample_every_ms=125)):
            if i < frame_skip:
                continue
            cands = [c for c in (build_candidate(a) for a in fc.candidates) if c is not None]
            sel = selector.update(cands)
            if sel.has_candidate:
                return sel.candidate.landmarks.copy()
    return None


def run_case(case: dict, out_dir: Path) -> dict:
    video = Path(case["video"])
    if not video.exists():
        return {"label": case["label"], "skipped": "missing_video"}

    lm = grab_landmarks(video)
    if lm is None:
        return {"label": case["label"], "skipped": "no_pose_detected"}

    baseline = apply_regional_gate(lm, case["exercise"])
    record = {
        "label":    case["label"],
        "exercise": case["exercise"],
        "baseline": {
            "passed":         baseline.passed_template,
            "failed_regions": baseline.failed_regions,
            "means":          {k: round(v, 3) for k, v in baseline.means.items()},
            "visible_counts": baseline.visible_counts,
            "reason":         baseline.reason,
            "expected_pass":  case["expect_baseline_pass"],
            "match":          baseline.passed_template == case["expect_baseline_pass"],
        },
        "occlusions": [],
    }

    for tgt in case["occlusion_targets"]:
        occluded_lm = occlude_region(lm, tgt["region"])
        occluded   = apply_regional_gate(occluded_lm, case["exercise"])
        record["occlusions"].append({
            "occluded_region": tgt["region"],
            "expected_pass":   tgt["expect_pass"],
            "passed":          occluded.passed_template,
            "failed_regions":  occluded.failed_regions,
            "means":           {k: round(v, 3) for k, v in occluded.means.items()},
            "reason":          occluded.reason,
            "match":           occluded.passed_template == tgt["expect_pass"],
        })

    log_path = out_dir / f"{case['label']}_log.json"
    log_path.write_text(json.dumps(record, indent=2, ensure_ascii=False), encoding="utf-8")
    return record


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(OUT_DIR))
    args = ap.parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"Output: {out_dir}\n")
    n_pass = n_fail = 0
    summary: List[dict] = []
    for case in CASES:
        rec = run_case(case, out_dir)
        if rec.get("skipped"):
            print(f">>> {rec['label']}  SKIPPED: {rec['skipped']}")
            print()
            continue
        print(f">>> {rec['label']}  exercise={rec['exercise']}")
        b = rec["baseline"]
        b_status = "OK" if b["match"] else "MISMATCH"
        print(f"    baseline: passed={b['passed']}  expected={b['expected_pass']}  {b_status}")
        print(f"      means={b['means']}")
        if b["match"]: n_pass += 1
        else:          n_fail += 1
        for o in rec["occlusions"]:
            tag = "OK" if o["match"] else "MISMATCH"
            print(f"    occlude {o['occluded_region']:12s}: passed={o['passed']}  "
                  f"expected={o['expected_pass']}  {tag}  failed_regions={o['failed_regions']}")
            if o["match"]: n_pass += 1
            else:          n_fail += 1
        print()
        summary.append({
            "label": rec["label"],
            "exercise": rec["exercise"],
            "baseline_match": b["match"],
            "occlusion_matches": [o["match"] for o in rec["occlusions"]],
        })

    print(f"Total assertions: pass={n_pass} fail={n_fail}")
    (out_dir / "smoke_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
