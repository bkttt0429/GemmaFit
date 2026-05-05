"""
test_smoothed_classifier_video.py — verify Algorithm #3.

Run squat_wikimedia + pushup_cdc through the raw `detect_exercise()` and
the new `SmoothedExerciseDetector`, then count single-frame label flips.
Expectation: smoothed flips << raw flips, and the steady-state label
matches the video's actual exercise.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import List

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exercises.core import (
    SmoothedExerciseDetector,
    detect_exercise,
)
from multi_person_pose import MultiPersonPose
from subject_selector import SubjectSelector, build_candidate

PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "smoothed_classifier_smoke"

SCENARIOS = [
    {
        "label": "squat_wikimedia",
        "video": PROJECT_ROOT / "test_assets" / "videos" / "squat_wikimedia_01.webm",
        "expected_label": "squat",
    },
    {
        "label": "pushup_cdc",
        "video": PROJECT_ROOT / "test_assets" / "videos" / "pushup_cdc_01.webm",
        "expected_label": "push_up",
    },
]


def _count_flips(labels: List[str]) -> int:
    return sum(1 for i in range(1, len(labels)) if labels[i] != labels[i - 1])


def run_scenario(label: str, video: Path, expected_label: str,
                 max_frames: int, every_ms: int) -> dict:
    if not video.exists():
        return {"label": label, "skipped": "missing_video"}

    selector = SubjectSelector()
    smoothed = SmoothedExerciseDetector(window_frames=9, switch_min_streak=4)

    raw_labels: List[str] = []
    smoothed_labels: List[str] = []

    with MultiPersonPose(num_poses=1) as pose:
        for fc in pose.iter_video(video, sample_every_ms=every_ms):
            if len(raw_labels) >= max_frames:
                break
            cands = [c for c in (build_candidate(a) for a in fc.candidates) if c is not None]
            sel = selector.update(cands)
            if not sel.has_candidate:
                continue
            lm = sel.candidate.landmarks
            raw = detect_exercise(lm)
            sm  = smoothed.update(lm)
            raw_labels.append(raw.exercise)
            smoothed_labels.append(sm.exercise)

    summary = {
        "frames": len(raw_labels),
        "raw_distribution": dict(Counter(raw_labels)),
        "smoothed_distribution": dict(Counter(smoothed_labels)),
        "raw_flips": _count_flips(raw_labels),
        "smoothed_flips": _count_flips(smoothed_labels),
        "steady_state_smoothed": Counter(smoothed_labels).most_common(1)[0][0] if smoothed_labels else None,
        "expected_label": expected_label,
    }
    return {"label": label, "video": str(video), "summary": summary,
            "raw_labels": raw_labels, "smoothed_labels": smoothed_labels}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-frames", type=int, default=80)
    ap.add_argument("--every-ms", type=int, default=125)
    ap.add_argument("--out", default=str(OUT_DIR))
    args = ap.parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"max-frames={args.max_frames}  every-ms={args.every_ms}")
    print()

    results = []
    failures: List[str] = []
    for s in SCENARIOS:
        print(f">>> {s['label']}  expected={s['expected_label']}")
        result = run_scenario(s["label"], Path(s["video"]),
                              s["expected_label"], args.max_frames, args.every_ms)
        if result.get("skipped"):
            print(f"    SKIPPED: {result['skipped']}")
            print()
            failures.append(f"{s['label']}: missing video {s['video']}")
            continue
        sm = result["summary"]
        steady_ok = sm["steady_state_smoothed"] == sm["expected_label"]
        flips_ok = sm["smoothed_flips"] <= sm["raw_flips"]
        delta_pct = (
            (sm["raw_flips"] - sm["smoothed_flips"]) / sm["raw_flips"] * 100.0
            if sm["raw_flips"] else 0.0
        )
        print(f"    frames={sm['frames']}")
        print(f"    raw      label distribution: {sm['raw_distribution']}")
        print(f"    smoothed label distribution: {sm['smoothed_distribution']}")
        print(f"    flips raw={sm['raw_flips']}  smoothed={sm['smoothed_flips']}  "
              f"reduction={delta_pct:.0f}%")
        print(f"    steady state = {sm['steady_state_smoothed']}  "
              f"{'OK' if steady_ok else 'MISMATCH'}")
        print()
        if not sm["frames"]:
            failures.append(f"{s['label']}: no detected frames")
        if not steady_ok:
            failures.append(
                f"{s['label']}: steady={sm['steady_state_smoothed']} "
                f"expected={sm['expected_label']}"
            )
        if not flips_ok:
            failures.append(
                f"{s['label']}: smoothed_flips={sm['smoothed_flips']} "
                f"> raw_flips={sm['raw_flips']}"
            )
        results.append(result)
        log_path = out_dir / f"{s['label']}_log.json"
        log_path.write_text(json.dumps(result, indent=2, ensure_ascii=False),
                            encoding="utf-8")

    summary_path = out_dir / "smoke_summary.json"
    summary_path.write_text(
        json.dumps([{"label": r["label"], "summary": r["summary"]} for r in results],
                   indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"Wrote {summary_path}")

    if failures:
        raise AssertionError("Algorithm #3 video smoke failed:\n" + "\n".join(failures))


if __name__ == "__main__":
    main()
