"""
Mobile-budget YOLO person-burst + target ReID audit.

This is a prototype-only evaluator. It checks whether a low-frequency detector
burst can keep a selected target through crowded frames without treating the
YOLO per-frame label as a stable person id.

Example:
  python prototype/mobile_yolo_reid_burst_audit.py ^
      --video tmp_gemmafit_pixel_line_error.mp4 ^
      --model yolov8n-seg.pt ^
      --label pixel_line_error_mobile_burst ^
      --start-frame 39 --end-frame 41 --target-rank 2
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import cv2
import numpy as np
from ultralytics import YOLO

from target_active_focus_yolo_reid import (
    PersonDetection,
    STAT_BLACK,
    STAT_GREEN,
    YoloTrackState,
    _accept,
    _bbox_center,
    _detect_people,
    _draw_detection,
    _rect_from_norm,
    _score_detections,
    _select_detection_from_tap,
    _stat_at,
    _update_state,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "mobile_yolo_reid_burst_audit"


def _read_app_sample(cap: cv2.VideoCapture, sample_index: int, every_ms: int, long_side: int) -> Tuple[np.ndarray, int]:
    timestamp_ms = sample_index * every_ms
    cap.set(cv2.CAP_PROP_POS_MSEC, timestamp_ms)
    ok, frame = cap.read()
    if not ok:
        raise RuntimeError(f"Cannot read app sample {sample_index} at {timestamp_ms} ms")
    height, width = frame.shape[:2]
    scale = long_side / max(width, height)
    resized = cv2.resize(frame, (round(width * scale), round(height * scale)), interpolation=cv2.INTER_AREA)
    return resized, timestamp_ms


def _sort_detections_left_to_right(detections: Sequence[PersonDetection]) -> List[PersonDetection]:
    return sorted(detections, key=lambda det: (det.bbox.left, det.bbox.top, -det.confidence))


def _detection_map(det: PersonDetection, rank: int) -> Dict[str, Any]:
    cx, cy = _bbox_center(det.bbox)
    upper_stats = det.signature.stats.get("upper")
    lower_stats = det.signature.stats.get("lower")
    return {
        "rank": rank,
        "source_index": det.index,
        "confidence": round(float(det.confidence), 5),
        "bbox": [
            round(float(det.bbox.left), 5),
            round(float(det.bbox.top), 5),
            round(float(det.bbox.right), 5),
            round(float(det.bbox.bottom), 5),
        ],
        "center": [round(float(cx), 5), round(float(cy), 5)],
        "area": round(float(det.bbox.area), 5),
        "upper_black": round(float(_stat_at(upper_stats, STAT_BLACK)), 5),
        "upper_green": round(float(_stat_at(upper_stats, STAT_GREEN)), 5),
        "lower_green": round(float(_stat_at(lower_stats, STAT_GREEN)), 5),
    }


def _profile_gate(
    det: PersonDetection,
    target_profile: str,
    min_upper_black: float,
    max_upper_green: float,
    max_lower_green: float,
) -> Tuple[bool, str]:
    if target_profile == "any":
        return True, "profile_any"
    upper_stats = det.signature.stats.get("upper")
    lower_stats = det.signature.stats.get("lower")
    upper_black = float(_stat_at(upper_stats, STAT_BLACK))
    upper_green = float(_stat_at(upper_stats, STAT_GREEN))
    lower_green = float(_stat_at(lower_stats, STAT_GREEN))
    if target_profile == "black":
        if upper_black < min_upper_black:
            return False, f"black_profile_upper_black_low:{upper_black:.3f}"
        if upper_green > max_upper_green:
            return False, f"black_profile_upper_green_high:{upper_green:.3f}"
        if lower_green > max_lower_green:
            return False, f"black_profile_lower_green_high:{lower_green:.3f}"
        return True, "black_profile_ok"
    return True, "profile_unknown"


def _select_detection_from_tap_with_profile(
    detections: Sequence[PersonDetection],
    tap: Tuple[float, float],
    target_profile: str,
    min_upper_black: float,
    max_upper_green: float,
    max_lower_green: float,
) -> Tuple[Optional[int], str]:
    passing = []
    rejected = []
    for i, det in enumerate(detections):
        ok, reason = _profile_gate(det, target_profile, min_upper_black, max_upper_green, max_lower_green)
        if ok:
            passing.append((i, det))
        else:
            rejected.append((i, reason))
    containing = [(i, det.bbox.area) for i, det in passing if det.bbox.contains(tap[0], tap[1])]
    if containing:
        return max(containing, key=lambda item: item[1])[0], "manual_tap_profile_initialized"
    if target_profile != "any":
        return None, "tap_no_profile_clean_detection"
    selected = _select_detection_from_tap(list(detections), tap[0], tap[1])
    return selected if selected >= 0 else None, "manual_tap_initialized"


def _draw_frame(
    frame: np.ndarray,
    detections: Sequence[PersonDetection],
    selected_rank: Optional[int],
    header: Sequence[str],
) -> np.ndarray:
    out = frame.copy()
    for rank, det in enumerate(detections):
        selected = selected_rank == rank
        color = (64, 220, 120) if selected else (128, 156, 180)
        _draw_detection(
            out,
            det,
            f"rank {rank} conf={det.confidence:.2f}",
            color,
            selected,
        )
    y = 22
    for line in header:
        cv2.putText(out, line, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (20, 20, 20), 3, cv2.LINE_AA)
        cv2.putText(out, line, (8, y), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (245, 245, 245), 1, cv2.LINE_AA)
        y += 20
    return out


def _contact_sheet(images: Sequence[np.ndarray], path: Path, columns: int = 2) -> None:
    if not images:
        return
    thumb_w = 480
    thumbs = []
    for image in images:
        h, w = image.shape[:2]
        scale = thumb_w / w
        thumbs.append(cv2.resize(image, (thumb_w, round(h * scale)), interpolation=cv2.INTER_AREA))
    max_h = max(img.shape[0] for img in thumbs)
    rows = []
    for i in range(0, len(thumbs), columns):
        row_imgs = []
        for img in thumbs[i : i + columns]:
            if img.shape[0] < max_h:
                pad = np.zeros((max_h - img.shape[0], img.shape[1], 3), dtype=np.uint8)
                img = np.vstack([img, pad])
            row_imgs.append(img)
        while len(row_imgs) < columns:
            row_imgs.append(np.zeros_like(row_imgs[0]))
        rows.append(np.hstack(row_imgs))
    path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(path), np.vstack(rows))


def _percentile(values: Sequence[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * p)))
    return float(ordered[idx])


def run_audit(
    video_path: Path,
    model_name: str,
    out_dir: Path,
    label: str,
    start_frame: int,
    end_frame: int,
    every_ms: int,
    target_rank: Optional[int],
    tap: Optional[Tuple[float, float]],
    conf: float,
    imgsz: int,
    long_side: int,
    cpu_budget_ms: float,
    target_profile: str = "any",
    min_upper_black: float = 0.28,
    max_upper_green: float = 0.35,
    max_lower_green: float = 0.35,
) -> Dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    model = YOLO(model_name)
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise FileNotFoundError(f"Cannot open video: {video_path}")

    state = YoloTrackState()
    trace: List[Dict[str, Any]] = []
    images: List[np.ndarray] = []
    yolo_ms_values: List[float] = []

    try:
        for sample_index in range(start_frame, end_frame + 1):
            frame, timestamp_ms = _read_app_sample(cap, sample_index, every_ms, long_side)
            started = time.perf_counter()
            detections = _sort_detections_left_to_right(_detect_people(model, frame, conf=conf, imgsz=imgsz))
            yolo_ms = (time.perf_counter() - started) * 1000.0
            yolo_ms_values.append(yolo_ms)

            selected_rank: Optional[int] = None
            selected_detection: Optional[PersonDetection] = None
            reason = "no_detection"
            scored: List[Dict[str, Any]] = []
            margin: Optional[float] = None
            profile_rejections: Dict[str, str] = {}

            if detections and not state.initialized:
                if tap is not None:
                    selected_rank, reason = _select_detection_from_tap_with_profile(
                        detections,
                        tap,
                        target_profile,
                        min_upper_black,
                        max_upper_green,
                        max_lower_green,
                    )
                elif target_rank is not None and 0 <= target_rank < len(detections):
                    selected_rank = target_rank
                    reason = "target_rank_initialized"
                if selected_rank is not None and 0 <= selected_rank < len(detections):
                    selected_detection = detections[selected_rank]
                    profile_ok, profile_reason = _profile_gate(
                        selected_detection,
                        target_profile,
                        min_upper_black,
                        max_upper_green,
                        max_lower_green,
                    )
                    if profile_ok:
                        _update_state(state, selected_detection, timestamp_ms)
                    else:
                        selected_rank = None
                        selected_detection = None
                        reason = f"target_profile_rejected:{profile_reason}"
                else:
                    selected_rank = None
                    reason = "target_not_available"
            elif detections and state.initialized:
                scored = _score_detections(detections, state, timestamp_ms)
                if target_profile != "any":
                    filtered_scored = []
                    for item in scored:
                        candidate_index = int(item["index"])
                        ok, profile_reason = _profile_gate(
                            detections[candidate_index],
                            target_profile,
                            min_upper_black,
                            max_upper_green,
                            max_lower_green,
                        )
                        if ok:
                            filtered_scored.append(item)
                        else:
                            profile_rejections[str(candidate_index)] = profile_reason
                    scored = filtered_scored
                else:
                    profile_rejections = {}
                if scored:
                    best = scored[0]
                    second = scored[1] if len(scored) > 1 else None
                    margin = float(best["identity"] - second["identity"]) if second is not None else 1.0
                    accepted, reason = _accept(best, margin, state)
                    if accepted:
                        selected_rank = int(best["index"])
                        selected_detection = detections[selected_rank]
                        _update_state(state, selected_detection, timestamp_ms)
                    else:
                        state.hold_frames += 1
                        if state.lost_since_ms is None:
                            state.lost_since_ms = timestamp_ms
                else:
                    state.hold_frames += 1
                    reason = "no_profile_clean_reid_score" if profile_rejections else "no_reid_score"
            else:
                profile_rejections = {}

            row = {
                "sample_index": sample_index,
                "timestamp_ms": timestamp_ms,
                "detections": [_detection_map(det, rank) for rank, det in enumerate(detections)],
                "selected_rank": selected_rank,
                "selected_bbox": _detection_map(selected_detection, selected_rank)["bbox"]
                if selected_detection is not None and selected_rank is not None
                else None,
                "initialized": state.initialized,
                "hold_frames": state.hold_frames,
                "reason": reason,
                "top2_margin": round(float(margin), 5) if margin is not None else None,
                "scores": [
                    {k: (round(float(v), 5) if isinstance(v, (float, np.floating)) else v) for k, v in item.items()}
                    for item in scored
                ],
                "profile_rejections": profile_rejections,
                "yolo_ms": round(yolo_ms, 3),
                "budget_ok": yolo_ms <= cpu_budget_ms,
            }
            trace.append(row)
            header = [
                f"{label} frame={sample_index} ts={timestamp_ms}ms",
                f"dets={len(detections)} selected_rank={selected_rank} reason={reason}",
                f"yolo_ms={yolo_ms:.1f} budget<={cpu_budget_ms:.1f}ms",
            ]
            if scored:
                header.append(f"best_id={scored[0]['identity']:.2f} app={scored[0]['appearance']:.2f} margin={margin:.2f}")
            images.append(_draw_frame(frame, detections, selected_rank, header))
    finally:
        cap.release()

    selected_frames = sum(1 for row in trace if row["selected_rank"] is not None)
    hold_frames = sum(1 for row in trace if row["selected_rank"] is None and row["initialized"])
    warm_yolo_ms_values = yolo_ms_values[1:] if len(yolo_ms_values) > 1 else yolo_ms_values
    summary = {
        "label": label,
        "video": str(video_path),
        "model": model_name,
        "config": {
            "start_frame": start_frame,
            "end_frame": end_frame,
            "every_ms": every_ms,
            "target_rank": target_rank,
            "tap": list(tap) if tap is not None else None,
            "conf": conf,
            "imgsz": imgsz,
            "long_side": long_side,
            "cpu_budget_ms": cpu_budget_ms,
            "target_profile": target_profile,
            "min_upper_black": min_upper_black,
            "max_upper_green": max_upper_green,
            "max_lower_green": max_lower_green,
        },
        "summary": {
            "frames_analyzed": len(trace),
            "selected_frames": selected_frames,
            "selected_ratio": round(selected_frames / max(1, len(trace)), 5),
            "hold_frames": hold_frames,
            "avg_yolo_ms": round(statistics.mean(yolo_ms_values), 3) if yolo_ms_values else 0.0,
            "p95_yolo_ms": round(_percentile(yolo_ms_values, 0.95), 3),
            "max_yolo_ms": round(max(yolo_ms_values), 3) if yolo_ms_values else 0.0,
            "budget_ok_ratio": round(
                sum(1 for value in yolo_ms_values if value <= cpu_budget_ms) / max(1, len(yolo_ms_values)),
                5,
            ),
            "warm_avg_yolo_ms": round(statistics.mean(warm_yolo_ms_values), 3) if warm_yolo_ms_values else 0.0,
            "warm_p95_yolo_ms": round(_percentile(warm_yolo_ms_values, 0.95), 3),
            "warm_budget_ok_ratio": round(
                sum(1 for value in warm_yolo_ms_values if value <= cpu_budget_ms) / max(1, len(warm_yolo_ms_values)),
                5,
            ),
            "cold_start_note": "First sample includes model/runtime warm-up and is not representative of steady-state burst cost.",
        },
        "trace": trace,
    }

    summary_path = out_dir / f"{label}_summary.json"
    trace_path = out_dir / f"{label}_trace.json"
    sheet_path = out_dir / f"{label}_contact_sheet.jpg"
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    trace_path.write_text(json.dumps(trace, indent=2, ensure_ascii=False), encoding="utf-8")
    _contact_sheet(images, sheet_path)
    summary["outputs"] = {
        "summary_json": str(summary_path),
        "trace_json": str(trace_path),
        "contact_sheet": str(sheet_path),
    }
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--model", default="yolov8n-seg.pt")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--label", default="mobile_yolo_reid_burst")
    parser.add_argument("--start-frame", type=int, default=39)
    parser.add_argument("--end-frame", type=int, default=41)
    parser.add_argument("--every-ms", type=int, default=200)
    parser.add_argument("--target-rank", type=int, default=None)
    parser.add_argument("--tap-x", type=float, default=None)
    parser.add_argument("--tap-y", type=float, default=None)
    parser.add_argument("--conf", type=float, default=0.2)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--long-side", type=int, default=640)
    parser.add_argument("--cpu-budget-ms", type=float, default=35.0)
    parser.add_argument("--target-profile", choices=["any", "black"], default="any")
    parser.add_argument("--min-upper-black", type=float, default=0.28)
    parser.add_argument("--max-upper-green", type=float, default=0.35)
    parser.add_argument("--max-lower-green", type=float, default=0.35)
    args = parser.parse_args()

    tap = None
    if args.tap_x is not None and args.tap_y is not None:
        tap = (args.tap_x, args.tap_y)

    summary = run_audit(
        video_path=args.video,
        model_name=args.model,
        out_dir=args.out_dir,
        label=args.label,
        start_frame=args.start_frame,
        end_frame=args.end_frame,
        every_ms=args.every_ms,
        target_rank=args.target_rank,
        tap=tap,
        conf=args.conf,
        imgsz=args.imgsz,
        long_side=args.long_side,
        cpu_budget_ms=args.cpu_budget_ms,
        target_profile=args.target_profile,
        min_upper_black=args.min_upper_black,
        max_upper_green=args.max_upper_green,
        max_lower_green=args.max_lower_green,
    )
    print(json.dumps(
        {
            "outputs": summary["outputs"],
            "summary": summary["summary"],
            "config": summary["config"],
        },
        indent=2,
        ensure_ascii=False,
    ))


if __name__ == "__main__":
    main()
