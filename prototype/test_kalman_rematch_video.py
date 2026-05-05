"""
test_kalman_rematch_video.py - verify Algorithm #4.

This is a video-style deterministic smoke for the Python subject selector:
8 fps timestamps, two subjects crossing, candidate order reversal, and a
short no-detection gap. The locked subject must be re-matched by the Kalman
prediction instead of switching to the distractor.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from subject_selector import (
    PoseCandidate,
    SubjectLockStatus,
    SubjectSelector,
    SubjectSelectorConfig,
    build_candidate,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "kalman_rematch_smoke"


def make_candidate(cx: float, cy: float = 0.55, visibility: float = 0.82) -> PoseCandidate:
    """Build a plausible MediaPipe-like candidate centered at `cx`, `cy`."""
    lm = np.zeros((33, 3), dtype=np.float32)
    points = {
        0:  (cx,        cy - 0.22),
        11: (cx - 0.05, cy - 0.11),
        12: (cx + 0.05, cy - 0.11),
        13: (cx - 0.07, cy - 0.02),
        14: (cx + 0.07, cy - 0.02),
        15: (cx - 0.08, cy + 0.06),
        16: (cx + 0.08, cy + 0.06),
        23: (cx - 0.04, cy + 0.03),
        24: (cx + 0.04, cy + 0.03),
        25: (cx - 0.04, cy + 0.18),
        26: (cx + 0.04, cy + 0.18),
        27: (cx - 0.04, cy + 0.32),
        28: (cx + 0.04, cy + 0.32),
    }
    for i in range(33):
        x, y = points.get(i, (cx, cy))
        lm[i] = [x, y, visibility]
    candidate = build_candidate(lm, keypoint_visibility_floor=0.15)
    if candidate is None:
        raise RuntimeError("Synthetic candidate did not build")
    return candidate


def run_trace() -> dict:
    cfg = SubjectSelectorConfig(
        kalman_enabled=True,
        subject_min_visibility=0.10,
        subject_lost_frames=5,
    )
    selector = SubjectSelector(cfg)
    selector.request_tap(0.30, 0.55)

    # (timestamp_ms, target_cx, distractor_cx). None means no detected poses.
    frames: List[Tuple[int, Optional[float], Optional[float]]] = [
        (0,   0.30, 0.70),
        (125, 0.36, 0.64),
        (250, None, None),
        (375, 0.48, 0.52),
        (500, 0.54, 0.46),
        (625, 0.60, 0.40),
    ]

    trace = []
    target_hits = 0
    visible_frames = 0
    hold_frames = 0
    lost_frames = 0

    for frame_idx, (ts_ms, target_cx, distractor_cx) in enumerate(frames):
        if target_cx is None or distractor_cx is None:
            candidates: List[PoseCandidate] = []
        elif frame_idx == 0:
            # Initial manual tap locks the target at active_index=0.
            candidates = [make_candidate(target_cx), make_candidate(distractor_cx)]
        else:
            # Reverse candidate order after lock to exercise re-match.
            candidates = [make_candidate(distractor_cx), make_candidate(target_cx)]

        selected = selector.update(candidates, timestamp_ms=ts_ms)
        selected_cx = selected.candidate.center_x if selected.candidate is not None else None
        target_active = (
            target_cx is not None
            and selected.has_candidate
            and abs(float(selected_cx) - target_cx) < 0.025
        )
        if target_cx is not None:
            visible_frames += 1
            if target_active:
                target_hits += 1
        if selected.reason == "subject_temporarily_unmatched":
            hold_frames += 1
        if selected.status == SubjectLockStatus.SUBJECT_LOST:
            lost_frames += 1

        trace.append({
            "frame": frame_idx,
            "timestamp_ms": ts_ms,
            "target_cx": target_cx,
            "distractor_cx": distractor_cx,
            "active_index": selected.active_index,
            "selected_cx": round(float(selected_cx), 4) if selected_cx is not None else None,
            "track_id": selected.track_id,
            "status": selected.status.value,
            "reason": selected.reason,
            "track_score": (
                round(float(selected.candidate.track_score), 4)
                if selected.candidate is not None else None
            ),
            "target_active": target_active,
        })

    track_ids = sorted({row["track_id"] for row in trace if row["track_id"] >= 0})
    assertions = [
        {
            "name": "all visible frames select target",
            "passed": target_hits == visible_frames,
            "detail": f"{target_hits}/{visible_frames}",
        },
        {
            "name": "candidate order reversal stays locked",
            "passed": trace[1]["active_index"] == 1 and trace[3]["active_index"] == 1,
            "detail": f"indices={[trace[1]['active_index'], trace[3]['active_index']]}",
        },
        {
            "name": "short no-detection gap holds lock",
            "passed": hold_frames == 1,
            "detail": f"hold_frames={hold_frames}",
        },
        {
            "name": "no subject lost",
            "passed": lost_frames == 0,
            "detail": f"lost_frames={lost_frames}",
        },
        {
            "name": "track id stable",
            "passed": track_ids == [1],
            "detail": f"track_ids={track_ids}",
        },
        {
            "name": "reacquires target after gap at crossing",
            "passed": trace[3]["target_active"] is True,
            "detail": f"frame3={trace[3]}",
        },
        {
            "name": "kalman score remains gated",
            "passed": all(
                row["track_score"] is None or row["track_score"] >= 0.0
                for row in trace
            ),
            "detail": "scores=" + str([row["track_score"] for row in trace]),
        },
    ]

    return {
        "config": {
            "kalman_enabled": cfg.kalman_enabled,
            "kalman_chi2_gate_3df": cfg.kalman_chi2_gate_3df,
            "kalman_default_dt_s": cfg.kalman_default_dt_s,
        },
        "trace": trace,
        "assertions": assertions,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(OUT_DIR))
    args = ap.parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    result = run_trace()
    log_path = out_dir / "kalman_rematch_log.json"
    log_path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")

    n_pass = sum(1 for a in result["assertions"] if a["passed"])
    n_fail = sum(1 for a in result["assertions"] if not a["passed"])
    print(f"Output: {out_dir}")
    for assertion in result["assertions"]:
        tag = "OK" if assertion["passed"] else "FAIL"
        print(f"{tag:4s} {assertion['name']}: {assertion['detail']}")
    print(f"Total assertions: pass={n_pass} fail={n_fail}")
    print(f"Log: {log_path}")
    if n_fail:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
