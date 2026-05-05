"""
test_subject_selector_video.py — exercise the subject selector against
real video files and dump a per-frame trace + summary stats.

Two scenarios:
  - opencv_vtest.avi  → multi-person walking; checks track-id stability
  - squat_wikimedia   → single-person; checks SINGLE_AUTO path

Usage:
    python prototype/test_subject_selector_video.py
    python prototype/test_subject_selector_video.py --max-frames 60
    python prototype/test_subject_selector_video.py --video <path> --label custom
"""
from __future__ import annotations

import argparse
import json
import time
from collections import Counter
from dataclasses import asdict
from pathlib import Path
from typing import Dict, List, Optional

import numpy as np

from multi_person_pose import MultiPersonPose
from subject_selector import (
    PoseCandidate,
    SubjectLockStatus,
    SubjectSelector,
    build_candidate,
)

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "subject_selector_smoke"

# After the first frame's SINGLE_AUTO, subsequent frames go through the
# "already locked" branch in update() and report AUTO_LOCKED. So the
# expected dominant status for a clean single-person clip is AUTO_LOCKED.
DEFAULT_SCENARIOS = [
    {
        "label": "squat_wikimedia_single",
        "video": PROJECT_ROOT / "test_assets" / "videos" / "squat_wikimedia_01.webm",
        "expected_status_dominant": SubjectLockStatus.AUTO_LOCKED.value,
        "expected_distinct_track_ids": 1,
        "note": "single-person squat; locks track_id=1 across all frames",
    },
    {
        "label": "pushup_cdc_single",
        "video": PROJECT_ROOT / "test_assets" / "videos" / "pushup_cdc_01.webm",
        "expected_status_dominant": SubjectLockStatus.AUTO_LOCKED.value,
        "expected_distinct_track_ids": 1,
        "note": "single-person push-up; locks track_id=1 across all frames",
    },
    {
        "label": "synthetic_two_person",
        "video": PROJECT_ROOT / "test_assets" / "videos" / "synthetic_two_person_squat.mp4",
        "expected_status_dominant": SubjectLockStatus.AUTO_LOCKED.value,
        "expected_distinct_track_ids": 1,
        "note": "two-person side-by-side; selector should auto-lock to one and never switch",
    },
]


def _distributed_landmarks(visibility: float = 0.0) -> np.ndarray:
    arr = np.zeros((33, 3), dtype=np.float32)
    for i in range(33):
        arr[i, 0] = (i % 6) / 5.0
        arr[i, 1] = (i // 6) / 5.0
        arr[i, 2] = visibility
    return arr


def run_presence_gate_smoke() -> int:
    blank = _distributed_landmarks(0.0)
    assert build_candidate(blank) is None, "blank distributed landmarks must not become a candidate"

    face_only = _distributed_landmarks(0.0)
    face_only[0:11, 2] = 0.9
    assert build_candidate(face_only) is None, "face-only visibility must not render as a body"

    bbox_only = _distributed_landmarks(0.0)
    bbox_only[[11, 24], 2] = 0.9
    assert build_candidate(bbox_only) is None, "bbox area alone must not pass presence gate"

    upper_body = _distributed_landmarks(0.0)
    upper_body[[11, 12, 13, 14, 15, 16, 23, 24], 2] = 0.9
    assert build_candidate(upper_body) is not None, "torso/upper-body crop should pass presence gate"
    return 4


def _summary_for_trace(trace: List[dict]) -> dict:
    if not trace:
        return {"frames": 0}
    statuses = Counter(t["status"] for t in trace)
    track_id_runs: List[List[int]] = []
    cur: List[int] = []
    last: Optional[int] = None
    for t in trace:
        tid = t["track_id"]
        if tid >= 0:
            if tid != last and cur:
                track_id_runs.append(cur)
                cur = []
            cur.append(tid)
            last = tid
        else:
            if cur:
                track_id_runs.append(cur)
                cur = []
            last = None
    if cur:
        track_id_runs.append(cur)

    distinct_tracks = sorted({tid for run in track_id_runs for tid in run})
    candidate_counts = Counter(t["n_candidates"] for t in trace)

    return {
        "frames": len(trace),
        "status_distribution": dict(statuses),
        "candidate_count_distribution": {
            int(k): v for k, v in candidate_counts.items()
        },
        "distinct_track_ids": distinct_tracks,
        "track_id_run_lengths": [len(run) for run in track_id_runs],
        "longest_lock_run": max((len(r) for r in track_id_runs), default=0),
        "lost_frames": int(statuses.get("SUBJECT_LOST", 0)),
        "multi_person_frames": sum(1 for t in trace if t["n_candidates"] >= 2),
    }


def run_scenario(
    label: str,
    video_path: Path,
    out_dir: Path,
    max_frames: int,
    sample_every_ms: int,
    num_poses: int,
) -> dict:
    if not video_path.exists():
        return {"label": label, "video": str(video_path), "skipped": "missing_file"}

    out_dir.mkdir(parents=True, exist_ok=True)
    selector = SubjectSelector()
    trace: List[dict] = []
    t0 = time.time()
    detect_seconds = 0.0
    select_seconds = 0.0

    with MultiPersonPose(num_poses=num_poses) as pose:
        for fc in pose.iter_video(video_path, sample_every_ms=sample_every_ms):
            if len(trace) >= max_frames:
                break
            t_a = time.time()
            cand_objs: List[PoseCandidate] = []
            for arr in fc.candidates:
                built = build_candidate(arr)
                if built is not None:
                    cand_objs.append(built)
            t_b = time.time()
            detect_seconds += 0.0  # detection time is inside iter_video; left as 0 placeholder
            sel = selector.update(cand_objs)
            t_c = time.time()
            select_seconds += (t_c - t_b)

            trace.append({
                "frame": fc.frame_index,
                "ts_ms": fc.timestamp_ms,
                "n_candidates": len(cand_objs),
                "active_index": sel.active_index,
                "track_id": sel.track_id,
                "status": sel.status.value,
                "trust_flags": sel.trust_flags,
                "candidate_visibilities": [round(c.avg_visibility, 3) for c in cand_objs],
                "candidate_areas": [round(c.bbox.area, 4) for c in cand_objs],
            })

    elapsed = time.time() - t0
    summary = _summary_for_trace(trace)

    log_path = out_dir / f"{label}_log.json"
    log_path.write_text(json.dumps({
        "label": label,
        "video": str(video_path),
        "max_frames": max_frames,
        "sample_every_ms": sample_every_ms,
        "num_poses": num_poses,
        "elapsed_s": round(elapsed, 2),
        "select_seconds": round(select_seconds, 4),
        "summary": summary,
        "trace": trace,
    }, indent=2, ensure_ascii=False), encoding="utf-8")

    return {
        "label": label,
        "video": str(video_path),
        "frames_run": len(trace),
        "elapsed_s": round(elapsed, 2),
        "summary": summary,
        "log": str(log_path),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-frames", type=int, default=80)
    ap.add_argument("--every-ms", type=int, default=125,
                    help="Sample cadence (ms). 125 = 8 fps, matches Android.")
    ap.add_argument("--num-poses", type=int, default=4)
    ap.add_argument("--out", default=str(DEFAULT_OUT_DIR))
    ap.add_argument("--video", default=None,
                    help="Custom video path (overrides scenarios)")
    ap.add_argument("--label", default="custom",
                    help="Label for custom video runs")
    args = ap.parse_args()

    out_dir = Path(args.out)
    if args.video is not None:
        scenarios = [{
            "label": args.label,
            "video": Path(args.video),
            "expected_status_dominant": None,
            "note": "custom",
        }]
    else:
        scenarios = DEFAULT_SCENARIOS

    print(f"Output dir: {out_dir}")
    print(f"Sample every: {args.every_ms} ms   max-frames: {args.max_frames}   num_poses: {args.num_poses}")
    print(f"Presence gate assertions: {run_presence_gate_smoke()} OK")
    print()

    results: List[dict] = []
    failures: List[str] = []
    for s in scenarios:
        print(f">>> {s['label']}  ({s['note']})")
        result = run_scenario(
            label=s["label"],
            video_path=Path(s["video"]),
            out_dir=out_dir,
            max_frames=args.max_frames,
            sample_every_ms=args.every_ms,
            num_poses=args.num_poses,
        )
        results.append(result)
        if result.get("skipped"):
            print(f"    SKIPPED: {result['skipped']} ({result['video']})")
            print()
            continue
        sm = result["summary"]
        print(f"    frames={sm['frames']}  multi_person_frames={sm['multi_person_frames']}  "
              f"longest_lock_run={sm['longest_lock_run']}  lost={sm['lost_frames']}")
        print(f"    status: {sm['status_distribution']}")
        print(f"    distinct_track_ids: {sm['distinct_track_ids']}")
        if s.get("expected_status_dominant") is not None:
            top = max(sm["status_distribution"], key=sm["status_distribution"].get)
            ok = top == s["expected_status_dominant"]
            print(f"    expected_dominant={s['expected_status_dominant']} got={top} {'OK' if ok else 'MISMATCH'}")
            if not ok:
                failures.append(f"{s['label']}: dominant status {top} != {s['expected_status_dominant']}")
        if s.get("expected_distinct_track_ids") is not None:
            n_tracks = len(sm["distinct_track_ids"])
            ok = n_tracks == s["expected_distinct_track_ids"]
            print(f"    expected_distinct_tracks={s['expected_distinct_track_ids']} got={n_tracks} {'OK' if ok else 'MISMATCH'}")
            if not ok:
                failures.append(f"{s['label']}: distinct tracks {n_tracks} != {s['expected_distinct_track_ids']}")
        print(f"    log: {result['log']}")
        print()

    summary_path = out_dir / "smoke_summary.json"
    summary_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote {summary_path}")
    if failures:
        print("Failures:")
        for failure in failures:
            print(f"  - {failure}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
