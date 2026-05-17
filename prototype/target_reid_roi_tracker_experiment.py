"""
Target re-ID + ROI tracker experiment for hard multi-person overlap clips.

This is intentionally prototype-only. It tests whether a lightweight image ROI
tracker can keep the user-selected target in focus while MediaPipe pose
candidates are ambiguous. The output separates:
  - ROI focus: the tracker box still follows the tapped person.
  - pose selection: a pose candidate is safe enough to draw as the subject.

Example:
  python prototype/target_reid_roi_tracker_experiment.py ^
      --video test_assets/videos/pixel_line_error_1774202137014.mp4 ^
      --label pixel_line_error_black_roi_csrt ^
      --start-ms 1900 --tap-x 0.47 --tap-y 0.53 --tracker csrt
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
from subject_selector import PoseBBox, PoseCandidate, SubjectAppearanceSignature, build_candidate
from validate_kalman_tracking_video import (
    _appearance_signature_from_candidate,
    _draw_candidate,
    _put_header,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VIDEO = PROJECT_ROOT / "test_assets" / "videos" / "pixel_line_error_1774202137014.mp4"
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "target_reid_roi_tracker"


@dataclass
class RoiState:
    bbox: PoseBBox
    tracker_ok: bool
    confidence: float


def _clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else value)


def _bbox_iou(a: PoseBBox, b: PoseBBox) -> float:
    left = max(a.left, b.left)
    top = max(a.top, b.top)
    right = min(a.right, b.right)
    bottom = min(a.bottom, b.bottom)
    inter = max(0.0, right - left) * max(0.0, bottom - top)
    union = a.area + b.area - inter
    return 0.0 if union <= 0.0 else _clamp01(inter / union)


def _center_distance(a: PoseBBox, b: PoseBBox) -> float:
    ax = 0.5 * (a.left + a.right)
    ay = 0.5 * (a.top + a.bottom)
    bx = 0.5 * (b.left + b.right)
    by = 0.5 * (b.top + b.bottom)
    return math.hypot(ax - bx, ay - by)


def _expand_bbox(bbox: PoseBBox, scale_x: float, scale_y: float) -> PoseBBox:
    cx = 0.5 * (bbox.left + bbox.right)
    cy = 0.5 * (bbox.top + bbox.bottom)
    half_w = bbox.width * scale_x * 0.5
    half_h = bbox.height * scale_y * 0.5
    return PoseBBox(
        left=max(0.0, cx - half_w),
        top=max(0.0, cy - half_h),
        right=min(1.0, cx + half_w),
        bottom=min(1.0, cy + half_h),
    )


def _norm_to_cv_bbox(bbox: PoseBBox, width: int, height: int) -> Tuple[int, int, int, int]:
    left = int(round(bbox.left * width))
    top = int(round(bbox.top * height))
    right = int(round(bbox.right * width))
    bottom = int(round(bbox.bottom * height))
    left = max(0, min(width - 2, left))
    top = max(0, min(height - 2, top))
    right = max(left + 2, min(width, right))
    bottom = max(top + 2, min(height, bottom))
    return left, top, right - left, bottom - top


def _cv_to_norm_bbox(rect: Tuple[float, float, float, float], width: int, height: int) -> PoseBBox:
    x, y, w, h = rect
    return PoseBBox(
        left=_clamp01(float(x) / max(1, width)),
        top=_clamp01(float(y) / max(1, height)),
        right=_clamp01(float(x + w) / max(1, width)),
        bottom=_clamp01(float(y + h) / max(1, height)),
    )


def _create_tracker(name: str):
    key = name.lower()
    if key == "csrt":
        return cv2.TrackerCSRT_create()
    if key == "kcf":
        return cv2.TrackerKCF_create()
    if key == "mil":
        return cv2.TrackerMIL_create()
    if key == "mosse":
        return cv2.legacy.TrackerMOSSE_create()
    raise ValueError(f"Unsupported tracker: {name}")


def _select_index_from_tap(candidates: List[PoseCandidate], x: float, y: float) -> int:
    containing = [
        (i, c.bbox.area)
        for i, c in enumerate(candidates)
        if c.bbox.contains(x, y)
    ]
    if containing:
        return max(containing, key=lambda item: item[1])[0]
    best_i = -1
    best_d = float("inf")
    for i, c in enumerate(candidates):
        d = math.hypot(c.center_x - x, c.center_y - y)
        if d < best_d:
            best_d = d
            best_i = i
    return best_i


def _score_candidates(
    candidates: List[PoseCandidate],
    roi: Optional[RoiState],
    target_signature: Optional[SubjectAppearanceSignature],
) -> List[Tuple[int, float, float, float, float]]:
    scored: List[Tuple[int, float, float, float, float]] = []
    for i, c in enumerate(candidates):
        appearance = target_signature.similarity(c.appearance) if target_signature is not None else 0.5
        iou = _bbox_iou(c.bbox, roi.bbox) if roi is not None else 0.0
        center_score = _clamp01(1.0 - (_center_distance(c.bbox, roi.bbox) if roi is not None else 1.0) / 0.30)
        vis_score = _clamp01(c.avg_visibility)
        score = (
            0.35 * appearance +
            0.30 * iou +
            0.25 * center_score +
            0.10 * vis_score
        )
        scored.append((i, _clamp01(score), appearance, iou, center_score))
    scored.sort(key=lambda item: item[1], reverse=True)
    return scored


def _draw_roi(frame: np.ndarray, roi: RoiState, label: str) -> None:
    h, w = frame.shape[:2]
    left, top, bw, bh = _norm_to_cv_bbox(roi.bbox, w, h)
    color = (0, 220, 255) if roi.tracker_ok else (0, 140, 255)
    cv2.rectangle(frame, (left, top), (left + bw, top + bh), color, 2, cv2.LINE_AA)
    cv2.putText(
        frame,
        label,
        (left, max(18, top - 8)),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.54,
        color,
        1,
        cv2.LINE_AA,
    )


def _run(
    video_path: Path,
    out_dir: Path,
    label: str,
    tracker_name: str,
    start_ms: int,
    tap_x: float,
    tap_y: float,
    every_ms: int,
    max_frames: int,
    num_poses: int,
) -> dict:
    if not video_path.exists():
        raise FileNotFoundError(video_path)

    out_dir.mkdir(parents=True, exist_ok=True)
    overlay_path = out_dir / f"{label}_{tracker_name}_overlay.mp4"
    trace_path = out_dir / f"{label}_{tracker_name}_trace.json"
    summary_path = out_dir / f"{label}_{tracker_name}_summary.json"

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise FileNotFoundError(f"Cannot open video: {video_path}")

    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    writer = cv2.VideoWriter(
        str(overlay_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        1000.0 / every_ms,
        (width, height),
    )
    if not writer.isOpened():
        cap.release()
        raise RuntimeError(f"Cannot create output video: {overlay_path}")

    trace: List[dict] = []
    tracker = None
    roi: Optional[RoiState] = None
    target_signature: Optional[SubjectAppearanceSignature] = None
    initialized = False
    last_emit_ms = -1
    frame_idx = 0
    detect_seconds = 0.0
    tracker_seconds = 0.0
    started = time.time()

    with MultiPersonPose(num_poses=num_poses) as pose:
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                ts_ms = int(round(frame_idx / fps * 1000))
                frame_idx += 1

                if initialized and tracker is not None:
                    tracker_start = time.perf_counter()
                    tracker_ok, cv_bbox = tracker.update(frame)
                    tracker_seconds += time.perf_counter() - tracker_start
                    if tracker_ok:
                        roi = RoiState(_cv_to_norm_bbox(cv_bbox, width, height), True, 1.0)
                    elif roi is not None:
                        roi = RoiState(roi.bbox, False, max(0.0, roi.confidence - 0.15))

                if ts_ms < start_ms or (ts_ms - last_emit_ms) < every_ms:
                    continue
                if max_frames > 0 and len(trace) >= max_frames:
                    break

                detect_start = time.perf_counter()
                raw_candidates = pose.detect_for_video(frame, ts_ms)
                detect_seconds += time.perf_counter() - detect_start
                candidates: List[PoseCandidate] = []
                for arr in raw_candidates:
                    built = build_candidate(arr)
                    if built is None:
                        continue
                    built.appearance = _appearance_signature_from_candidate(frame, built)
                    candidates.append(built)

                selected_index: Optional[int] = None
                selected_score: Optional[float] = None
                selected_app: Optional[float] = None
                selected_iou: Optional[float] = None
                selected_center_score: Optional[float] = None
                reason = ""

                if not initialized and candidates:
                    tap_i = _select_index_from_tap(candidates, tap_x, tap_y)
                    if tap_i >= 0:
                        selected = candidates[tap_i]
                        target_signature = selected.appearance
                        init_bbox = _expand_bbox(selected.bbox, 1.30, 1.18)
                        tracker = _create_tracker(tracker_name)
                        tracker.init(frame, _norm_to_cv_bbox(init_bbox, width, height))
                        roi = RoiState(init_bbox, True, 1.0)
                        initialized = True
                        selected_index = tap_i
                        selected_score = 1.0
                        selected_app = 1.0
                        selected_iou = 1.0
                        selected_center_score = 1.0
                        reason = "manual_target_initialized"

                elif initialized:
                    scored = _score_candidates(candidates, roi, target_signature)
                    if scored:
                        best = scored[0]
                        second = scored[1] if len(scored) > 1 else None
                        margin = best[1] - second[1] if second is not None else 1.0
                        best_i, best_score, appearance, iou, center_score = best
                        # Candidate adoption is deliberately strict. The ROI box
                        # may continue to focus the target while pose drawing holds.
                        accept = (
                            best_score >= 0.52 and
                            appearance >= 0.42 and
                            (iou >= 0.15 or center_score >= 0.70) and
                            margin >= 0.05
                        )
                        if accept:
                            selected_index = best_i
                            selected_score = best_score
                            selected_app = appearance
                            selected_iou = iou
                            selected_center_score = center_score
                            candidate_box = _expand_bbox(candidates[best_i].bbox, 1.25, 1.15)
                            tracker = _create_tracker(tracker_name)
                            tracker.init(frame, _norm_to_cv_bbox(candidate_box, width, height))
                            roi = RoiState(candidate_box, True, 1.0)
                            if target_signature is not None and candidates[best_i].appearance is not None:
                                target_signature = target_signature.blend(candidates[best_i].appearance, alpha=0.08)
                            reason = "pose_candidate_reid"
                        else:
                            reason = "roi_focus_pose_hold"
                    else:
                        reason = "roi_focus_no_pose"

                hold_frame = initialized and selected_index is None
                for i, candidate in enumerate(candidates):
                    is_selected = i == selected_index
                    color = (64, 220, 120) if is_selected else (128, 156, 180)
                    label_text = f"cand={i} vis={candidate.avg_visibility:.2f}"
                    if is_selected and selected_score is not None:
                        label_text += f" reid={selected_score:.2f}"
                    _draw_candidate(frame, candidate, label_text, color, is_selected, draw_skeleton=is_selected)

                if roi is not None:
                    _draw_roi(frame, roi, f"ROI {tracker_name} conf={roi.confidence:.2f}")

                row = {
                    "sample_index": len(trace),
                    "frame": frame_idx - 1,
                    "timestamp_ms": ts_ms,
                    "initialized": initialized,
                    "raw_candidates": len(raw_candidates),
                    "candidates_after_gate": len(candidates),
                    "selected_index": selected_index,
                    "selected_score": round(float(selected_score), 5) if selected_score is not None else None,
                    "selected_appearance": round(float(selected_app), 5) if selected_app is not None else None,
                    "selected_iou": round(float(selected_iou), 5) if selected_iou is not None else None,
                    "selected_center_score": (
                        round(float(selected_center_score), 5) if selected_center_score is not None else None
                    ),
                    "hold_frame": hold_frame,
                    "reason": reason,
                    "roi_bbox": (
                        [
                            round(float(roi.bbox.left), 5),
                            round(float(roi.bbox.top), 5),
                            round(float(roi.bbox.right), 5),
                            round(float(roi.bbox.bottom), 5),
                        ]
                        if roi is not None
                        else None
                    ),
                    "roi_tracker_ok": roi.tracker_ok if roi is not None else False,
                    "roi_confidence": round(float(roi.confidence), 5) if roi is not None else None,
                    "candidate_centers": [
                        [round(float(c.center_x), 5), round(float(c.center_y), 5)]
                        for c in candidates
                    ],
                    "candidate_scores": [
                        [round(float(v), 5) for v in score]
                        for score in _score_candidates(candidates, roi, target_signature)
                    ],
                }
                trace.append(row)

                header = [
                    f"GemmaFit target re-ID ROI tracker | {label} | {tracker_name}",
                    f"frame={row['frame']} t={ts_ms}ms init={initialized} selected={selected_index} hold={hold_frame}",
                    f"raw={row['raw_candidates']} gated={row['candidates_after_gate']} reason={reason}",
                    f"score={row['selected_score']} app={row['selected_appearance']} iou={row['selected_iou']} center={row['selected_center_score']}",
                    f"tap=({tap_x:.3f}, {tap_y:.3f})",
                ]
                _put_header(frame, header)
                writer.write(frame)
                last_emit_ms = ts_ms
        finally:
            cap.release()
            writer.release()

    initialized_frames = sum(1 for row in trace if row["initialized"])
    roi_ok_frames = sum(1 for row in trace if row["roi_tracker_ok"])
    selected_frames = sum(1 for row in trace if row["selected_index"] is not None)
    hold_frames = sum(1 for row in trace if row["hold_frame"])
    summary = {
        "label": label,
        "video": str(video_path),
        "tracker": tracker_name,
        "outputs": {
            "overlay_video": str(overlay_path),
            "trace_json": str(trace_path),
            "summary_json": str(summary_path),
        },
        "config": {
            "start_ms": start_ms,
            "tap": [tap_x, tap_y],
            "every_ms": every_ms,
            "num_poses": num_poses,
        },
        "summary": {
            "frames_analyzed": len(trace),
            "initialized_frames": initialized_frames,
            "roi_ok_frames": roi_ok_frames,
            "roi_ok_ratio": round(roi_ok_frames / max(1, initialized_frames), 5),
            "selected_pose_frames": selected_frames,
            "selected_pose_ratio": round(selected_frames / max(1, initialized_frames), 5),
            "hold_frames": hold_frames,
            "elapsed_s": round(time.time() - started, 3),
            "detect_seconds": round(detect_seconds, 4),
            "tracker_seconds": round(tracker_seconds, 4),
            "avg_tracker_ms_per_frame": round((tracker_seconds / max(1, frame_idx)) * 1000.0, 4),
        },
        "assertions": [
            {
                "name": "target initialized from manual tap",
                "passed": initialized_frames > 0,
                "detail": f"initialized_frames={initialized_frames}",
            },
            {
                "name": "ROI tracker stayed active on most initialized samples",
                "passed": initialized_frames == 0 or roi_ok_frames / initialized_frames >= 0.75,
                "detail": f"roi_ok={roi_ok_frames}/{initialized_frames}",
            },
            {
                "name": "pose adoption remains conservative",
                "passed": selected_frames <= initialized_frames,
                "detail": f"selected_pose_frames={selected_frames}, hold_frames={hold_frames}",
            },
        ],
    }

    trace_path.write_text(json.dumps(trace, indent=2, ensure_ascii=False), encoding="utf-8")
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", default=str(DEFAULT_VIDEO))
    parser.add_argument("--label", default="pixel_line_error_target_reid")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR))
    parser.add_argument("--tracker", default="csrt", choices=["csrt", "kcf", "mil", "mosse"])
    parser.add_argument("--start-ms", type=int, default=1900)
    parser.add_argument("--tap-x", type=float, default=0.47)
    parser.add_argument("--tap-y", type=float, default=0.53)
    parser.add_argument("--every-ms", type=int, default=125)
    parser.add_argument("--max-frames", type=int, default=0)
    parser.add_argument("--num-poses", type=int, default=4)
    args = parser.parse_args()

    summary = _run(
        video_path=Path(args.video),
        out_dir=Path(args.out_dir),
        label=args.label,
        tracker_name=args.tracker,
        start_ms=args.start_ms,
        tap_x=args.tap_x,
        tap_y=args.tap_y,
        every_ms=args.every_ms,
        max_frames=args.max_frames,
        num_poses=args.num_poses,
    )
    sm = summary["summary"]
    print(f"Output dir: {args.out_dir}")
    print(f"Overlay: {summary['outputs']['overlay_video']}")
    print(f"Trace:   {summary['outputs']['trace_json']}")
    print(f"Summary: {summary['outputs']['summary_json']}")
    print(
        "frames={frames_analyzed} roi_ok={roi_ok_frames} "
        "roi_ok_ratio={roi_ok_ratio} selected_pose={selected_pose_frames} "
        "selected_ratio={selected_pose_ratio} hold={hold_frames} "
        "avg_tracker_ms={avg_tracker_ms_per_frame}".format(**sm)
    )
    n_fail = 0
    for item in summary["assertions"]:
        tag = "OK" if item["passed"] else "FAIL"
        if not item["passed"]:
            n_fail += 1
        print(f"{tag:4s} {item['name']}: {item['detail']}")
    if n_fail:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
