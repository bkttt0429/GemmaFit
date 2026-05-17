"""
Validate the person-proposal layer before subject identity tracking.

This script intentionally does not select or track a subject. It answers one
lower-level question: for each sampled frame, do MediaPipe pose results produce
presence-gated human candidates with boxes that cover the visible person?

The output overlay draws every accepted proposal so identity failures can be
separated from detector/proposal failures.

Example:
  python prototype/validate_person_detection_video.py ^
      --video test_assets/videos/pixel_line_error_1774202137014.mp4 ^
      --label pixel_line_error_person_detection --every-ms 125 --num-poses 4
"""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

import cv2
import numpy as np

from multi_person_pose import MultiPersonPose
from person_proposals import build_person_proposals
from subject_selector import PoseBBox, PoseCandidate, build_candidate
from validate_kalman_tracking_video import _put_header


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VIDEO = PROJECT_ROOT / "test_assets" / "videos" / "pixel_line_error_1774202137014.mp4"
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "person_detection_video"

VISIBILITY_FLOOR = 0.25
TORSO_POINTS: Tuple[int, ...] = (11, 12, 23, 24)
UPPER_BODY_POINTS: Tuple[int, ...] = (11, 12, 13, 14, 15, 16, 23, 24)
HEAD_POINTS: Tuple[int, ...] = tuple(range(0, 11))
FEET_POINTS: Tuple[int, ...] = (27, 28, 29, 30, 31, 32)
PERSON_COLORS: Tuple[Tuple[int, int, int], ...] = (
    (40, 220, 255),
    (80, 255, 80),
    (255, 160, 40),
    (240, 80, 255),
)


@dataclass
class PersonProposal:
    source_index: int
    candidate: PoseCandidate
    raw_bbox: PoseBBox
    person_bbox: PoseBBox
    high_visibility_count: int
    torso_visible: int
    upper_visible: int


def _clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else value)


def _bbox_iou(a: PoseBBox, b: PoseBBox) -> float:
    left = max(a.left, b.left)
    top = max(a.top, b.top)
    right = min(a.right, b.right)
    bottom = min(a.bottom, b.bottom)
    inter = max(0.0, right - left) * max(0.0, bottom - top)
    union = a.area + b.area - inter
    if union <= 0.0:
        return 0.0
    return _clamp01(inter / union)


def _visible_indices(landmarks: np.ndarray, indices: Tuple[int, ...]) -> List[int]:
    return [
        idx for idx in indices
        if idx < landmarks.shape[0]
        and float(landmarks[idx, 2]) >= VISIBILITY_FLOOR
        and math.isfinite(float(landmarks[idx, 0]))
        and math.isfinite(float(landmarks[idx, 1]))
    ]


def _mean_xy(landmarks: np.ndarray, indices: List[int]) -> Optional[Tuple[float, float]]:
    if not indices:
        return None
    return (
        float(np.mean(landmarks[indices, 0])),
        float(np.mean(landmarks[indices, 1])),
    )


def _distance(a: Optional[Tuple[float, float]], b: Optional[Tuple[float, float]]) -> float:
    if a is None or b is None:
        return 0.0
    return math.hypot(a[0] - b[0], a[1] - b[1])


def _expanded_person_bbox(candidate: PoseCandidate) -> PoseBBox:
    """Convert a landmark bbox into a more person-like crop box.

    `PoseCandidate.bbox` is intentionally tight around confident keypoints.
    That is useful for pose math but too small for person proposal overlays,
    tap hit testing, and later detector/ReID crops. This expansion uses only
    visible pose geometry and clamps to image-normalized bounds.
    """
    landmarks = candidate.landmarks
    raw = candidate.bbox
    vis = np.nan_to_num(landmarks[:, 2], nan=0.0, posinf=0.0, neginf=0.0)
    mask = (
        (vis >= VISIBILITY_FLOOR)
        & np.isfinite(landmarks[:, 0])
        & np.isfinite(landmarks[:, 1])
    )
    if not np.any(mask):
        return raw

    xs = np.clip(landmarks[mask, 0], 0.0, 1.0)
    ys = np.clip(landmarks[mask, 1], 0.0, 1.0)
    left = float(xs.min())
    right = float(xs.max())
    top = float(ys.min())
    bottom = float(ys.max())

    shoulders = _visible_indices(landmarks, (11, 12))
    hips = _visible_indices(landmarks, (23, 24))
    head = _visible_indices(landmarks, HEAD_POINTS)
    feet = _visible_indices(landmarks, FEET_POINTS)

    shoulder_center = _mean_xy(landmarks, shoulders)
    hip_center = _mean_xy(landmarks, hips)
    torso_h = abs((hip_center[1] - shoulder_center[1]) if shoulder_center and hip_center else 0.0)
    shoulder_w = 0.0
    if 11 in shoulders and 12 in shoulders:
        shoulder_w = abs(float(landmarks[11, 0] - landmarks[12, 0]))

    raw_w = max(right - left, raw.width, 0.01)
    raw_h = max(bottom - top, raw.height, 0.01)
    body_scale = max(raw_w, raw_h * 0.45, shoulder_w * 1.8, torso_h * 0.9, 0.04)

    side_pad = max(raw_w * 0.20, shoulder_w * 0.55, body_scale * 0.12, 0.018)
    top_pad = max(raw_h * 0.08, torso_h * 0.18, body_scale * 0.06, 0.010)
    bottom_pad = max(raw_h * 0.12, torso_h * 0.30, body_scale * 0.10, 0.018)

    if head:
        top = min(top, float(np.min(landmarks[head, 1])))
        top_pad = max(top_pad, body_scale * 0.04)
    else:
        top_pad = max(top_pad, body_scale * 0.10)

    if feet:
        bottom = max(bottom, float(np.max(landmarks[feet, 1])))
        bottom_pad = max(bottom_pad, body_scale * 0.05)
    else:
        bottom_pad = max(bottom_pad, body_scale * 0.22)

    expanded = PoseBBox(
        left=_clamp01(left - side_pad),
        top=_clamp01(top - top_pad),
        right=_clamp01(right + side_pad),
        bottom=_clamp01(bottom + bottom_pad),
    )

    # Keep a minimum person-like box. Pose boxes can collapse when only a thin
    # side view is confidently visible.
    min_w = min(0.42, max(raw_w * 1.25, shoulder_w * 2.0, 0.055))
    min_h = min(0.85, max(raw_h * 1.18, torso_h * 2.4, 0.14))
    cx = 0.5 * (expanded.left + expanded.right)
    cy = 0.5 * (expanded.top + expanded.bottom)
    if expanded.width < min_w:
        expanded.left = _clamp01(cx - min_w * 0.5)
        expanded.right = _clamp01(cx + min_w * 0.5)
    if expanded.height < min_h:
        expanded.top = _clamp01(cy - min_h * 0.5)
        expanded.bottom = _clamp01(cy + min_h * 0.5)

    return expanded


def _nms_proposals(proposals: List[PersonProposal], iou_threshold: float = 0.72) -> List[PersonProposal]:
    ranked = sorted(
        proposals,
        key=lambda p: (
            p.candidate.avg_visibility,
            p.high_visibility_count,
            p.person_bbox.area,
        ),
        reverse=True,
    )
    kept: List[PersonProposal] = []
    for proposal in ranked:
        if any(_bbox_iou(proposal.person_bbox, kept_one.person_bbox) >= iou_threshold for kept_one in kept):
            continue
        kept.append(proposal)
    kept.sort(key=lambda p: p.source_index)
    return kept


def _build_proposals(raw_candidates: List[np.ndarray]) -> Tuple[List[PersonProposal], int]:
    return build_person_proposals(raw_candidates)


def _to_px(bbox: PoseBBox, width: int, height: int) -> Tuple[int, int, int, int]:
    left = int(round(_clamp01(bbox.left) * width))
    top = int(round(_clamp01(bbox.top) * height))
    right = int(round(_clamp01(bbox.right) * width))
    bottom = int(round(_clamp01(bbox.bottom) * height))
    left = max(0, min(width - 1, left))
    top = max(0, min(height - 1, top))
    right = max(left + 1, min(width, right))
    bottom = max(top + 1, min(height, bottom))
    return left, top, right, bottom


def _draw_proposals(frame: np.ndarray, proposals: List[PersonProposal], tap: Optional[Tuple[float, float]]) -> None:
    h, w = frame.shape[:2]
    for i, proposal in enumerate(proposals):
        color = PERSON_COLORS[i % len(PERSON_COLORS)]
        left, top, right, bottom = _to_px(proposal.person_bbox, w, h)
        raw_left, raw_top, raw_right, raw_bottom = _to_px(proposal.raw_bbox, w, h)
        cv2.rectangle(frame, (left, top), (right, bottom), color, 2)
        cv2.rectangle(frame, (raw_left, raw_top), (raw_right, raw_bottom), color, 1, cv2.LINE_AA)
        label = (
            f"P{i}/src{proposal.source_index} "
            f"vis={proposal.candidate.avg_visibility:.2f} "
            f"kp={proposal.high_visibility_count}"
        )
        cv2.putText(
            frame,
            label,
            (left, max(14, top - 6)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.45,
            color,
            1,
            cv2.LINE_AA,
        )
    if tap is not None:
        tx = int(round(tap[0] * w))
        ty = int(round(tap[1] * h))
        cv2.drawMarker(frame, (tx, ty), (0, 0, 255), cv2.MARKER_CROSS, 22, 2)


def _make_contact_sheet(frames: List[np.ndarray], out_path: Path, cols: int = 4) -> None:
    if not frames:
        return
    thumbs: List[np.ndarray] = []
    for frame in frames:
        thumb = cv2.resize(frame, (360, 203), interpolation=cv2.INTER_AREA)
        thumbs.append(thumb)
    rows = int(math.ceil(len(thumbs) / cols))
    blank = np.zeros_like(thumbs[0])
    while len(thumbs) < rows * cols:
        thumbs.append(blank.copy())
    strips = []
    for row in range(rows):
        strips.append(np.concatenate(thumbs[row * cols:(row + 1) * cols], axis=1))
    sheet = np.concatenate(strips, axis=0)
    cv2.imwrite(str(out_path), sheet)


def _run(
    video_path: Path,
    label: str,
    out_dir: Path,
    every_ms: int,
    num_poses: int,
    max_frames: int,
    start_ms: int,
    tap_x: Optional[float],
    tap_y: Optional[float],
    expect_person: bool,
    min_candidate_frames_ratio: float,
) -> dict:
    if not video_path.exists():
        raise FileNotFoundError(video_path)
    out_dir.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise FileNotFoundError(video_path)
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    cap.release()

    overlay_path = out_dir / f"{label}_person_detection_overlay.mp4"
    trace_path = out_dir / f"{label}_person_detection_trace.json"
    summary_path = out_dir / f"{label}_person_detection_summary.json"
    sheet_path = out_dir / f"{label}_person_detection_contact_sheet.jpg"

    writer = cv2.VideoWriter(
        str(overlay_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        max(1.0, 1000.0 / max(1, every_ms)),
        (width, height),
    )
    if not writer.isOpened():
        raise RuntimeError(f"Cannot open video writer: {overlay_path}")

    trace: List[dict] = []
    contact_frames: List[np.ndarray] = []
    started = time.time()
    last_emit_ms = -1

    with MultiPersonPose(num_poses=num_poses) as detector:
        cap = cv2.VideoCapture(str(video_path))
        try:
            frame_idx = 0
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                ts_ms = int(round(frame_idx / fps * 1000))
                frame_idx += 1
                if ts_ms < start_ms:
                    continue
                if (ts_ms - last_emit_ms) < every_ms:
                    continue
                if max_frames > 0 and len(trace) >= max_frames:
                    break
                last_emit_ms = ts_ms

                raw = detector.detect_for_video(frame, ts_ms)
                proposals, gated_out = _build_proposals(raw)
                tap = (tap_x, tap_y) if tap_x is not None and tap_y is not None else None
                tap_hits = 0
                if tap is not None:
                    tap_hits = sum(1 for p in proposals if p.person_bbox.contains(tap[0], tap[1]))

                header = [
                    f"{label}",
                    f"frame={frame_idx - 1} ts={ts_ms}ms raw={len(raw)} proposals={len(proposals)} gated_out={gated_out}",
                    f"tap_hits={tap_hits}" if tap is not None else "tap=none",
                ]
                _put_header(frame, header)
                _draw_proposals(frame, proposals, tap)
                writer.write(frame)

                if len(contact_frames) < 12 and (len(trace) % max(1, math.ceil(max_frames / 12) if max_frames > 0 else 6) == 0):
                    contact_frames.append(frame.copy())

                trace.append({
                    "frame_index": frame_idx - 1,
                    "timestamp_ms": ts_ms,
                    "raw_candidates": len(raw),
                    "proposals": len(proposals),
                    "gated_out": gated_out,
                    "tap_hits": tap_hits,
                    "boxes": [
                        {
                            "source_index": p.source_index,
                            "avg_visibility": round(p.candidate.avg_visibility, 4),
                            "high_visibility_count": p.high_visibility_count,
                            "torso_visible": p.torso_visible,
                            "upper_visible": p.upper_visible,
                            "raw_bbox": {
                                "left": round(p.raw_bbox.left, 4),
                                "top": round(p.raw_bbox.top, 4),
                                "right": round(p.raw_bbox.right, 4),
                                "bottom": round(p.raw_bbox.bottom, 4),
                                "area": round(p.raw_bbox.area, 4),
                            },
                            "person_bbox": {
                                "left": round(p.person_bbox.left, 4),
                                "top": round(p.person_bbox.top, 4),
                                "right": round(p.person_bbox.right, 4),
                                "bottom": round(p.person_bbox.bottom, 4),
                                "area": round(p.person_bbox.area, 4),
                            },
                        }
                        for p in proposals
                    ],
                })
        finally:
            cap.release()
            writer.release()

    candidate_frames = sum(1 for row in trace if row["proposals"] > 0)
    raw_candidate_frames = sum(1 for row in trace if row["raw_candidates"] > 0)
    tap_hit_frames = sum(1 for row in trace if row["tap_hits"] > 0)
    avg_proposals = float(np.mean([row["proposals"] for row in trace])) if trace else 0.0
    max_proposals = max([row["proposals"] for row in trace], default=0)
    ratio = candidate_frames / max(1, len(trace))

    assertions = [
        {
            "name": "pose model raw candidate expectation",
            "passed": raw_candidate_frames > 0 if expect_person else raw_candidate_frames == 0,
            "detail": f"raw_candidate_frames={raw_candidate_frames}/{len(trace)}",
        },
        {
            "name": "presence-gated person proposal expectation",
            "passed": ratio >= min_candidate_frames_ratio if expect_person else candidate_frames == 0,
            "detail": f"proposal_frames={candidate_frames}/{len(trace)} ratio={ratio:.3f} required={min_candidate_frames_ratio:.3f}",
        },
        {
            "name": "tap point is inside at least one proposal at initialization",
            "passed": (not expect_person) or tap_x is None or tap_hit_frames > 0,
            "detail": f"tap_hit_frames={tap_hit_frames}/{len(trace)}",
        },
    ]

    summary = {
        "label": label,
        "video": str(video_path),
        "outputs": {
            "overlay_video": str(overlay_path.resolve()),
            "trace_json": str(trace_path.resolve()),
            "summary_json": str(summary_path.resolve()),
            "contact_sheet": str(sheet_path.resolve()),
        },
        "config": {
            "every_ms": every_ms,
            "num_poses": num_poses,
            "max_frames": max_frames,
            "start_ms": start_ms,
            "tap": [tap_x, tap_y] if tap_x is not None and tap_y is not None else None,
            "expect_person": expect_person,
            "min_candidate_frames_ratio": min_candidate_frames_ratio,
            "presence_gate": {
                "avg_visibility": 0.18,
                "visibility_floor": VISIBILITY_FLOOR,
                "min_visible_keypoints": 8,
                "min_bbox_area": 0.01,
                "torso_or_upper_body": "torso >= 2 or upper_body >= 4",
            },
        },
        "summary": {
            "frames_analyzed": len(trace),
            "raw_candidate_frames": raw_candidate_frames,
            "proposal_frames": candidate_frames,
            "proposal_frame_ratio": round(ratio, 4),
            "avg_proposals_per_frame": round(avg_proposals, 4),
            "max_proposals_per_frame": max_proposals,
            "tap_hit_frames": tap_hit_frames,
            "elapsed_s": round(time.time() - started, 3),
        },
        "assertions": assertions,
    }

    trace_path.write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    _make_contact_sheet(contact_frames, sheet_path)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", type=Path, default=DEFAULT_VIDEO)
    parser.add_argument("--label", default="person_detection_video")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--every-ms", type=int, default=125)
    parser.add_argument("--num-poses", type=int, default=4)
    parser.add_argument("--max-frames", type=int, default=0)
    parser.add_argument("--start-ms", type=int, default=0)
    parser.add_argument("--tap-x", type=float, default=None)
    parser.add_argument("--tap-y", type=float, default=None)
    parser.add_argument("--expect-no-person", action="store_true")
    parser.add_argument("--min-candidate-frames-ratio", type=float, default=0.95)
    args = parser.parse_args()

    summary = _run(
        video_path=args.video,
        label=args.label,
        out_dir=args.out_dir,
        every_ms=args.every_ms,
        num_poses=args.num_poses,
        max_frames=args.max_frames,
        start_ms=args.start_ms,
        tap_x=args.tap_x,
        tap_y=args.tap_y,
        expect_person=not args.expect_no_person,
        min_candidate_frames_ratio=args.min_candidate_frames_ratio,
    )
    sm = summary["summary"]
    print(
        "frames={frames_analyzed} raw_frames={raw_candidate_frames} "
        "proposal_frames={proposal_frames} ratio={proposal_frame_ratio} "
        "avg_proposals={avg_proposals_per_frame} max_proposals={max_proposals_per_frame}".format(**sm)
    )
    failures = 0
    for assertion in summary["assertions"]:
        status = "OK" if assertion["passed"] else "FAIL"
        print(f"[{status}] {assertion['name']}: {assertion['detail']}")
        if not assertion["passed"]:
            failures += 1
    print(f"overlay={summary['outputs']['overlay_video']}")
    print(f"summary={summary['outputs']['summary_json']}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
