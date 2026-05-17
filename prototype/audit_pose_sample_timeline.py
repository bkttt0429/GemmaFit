#!/usr/bin/env python3
"""Audit MediaPipe pose detection on app-style sampled video frames.

This is a desktop diagnostic for the Android review pipeline. It samples a
video at fixed timestamps, runs the same MediaPipe task model, and reports
whether each sample has a pose that is drawable versus merely judgeable.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks import python
from mediapipe.tasks.python import vision


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODEL = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "pose_landmarker_lite.task"
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "pose_sample_timeline"


@dataclass
class PoseStats:
    avg_visibility: float
    high_visibility_count: int
    bbox_area: float
    bbox: list[float]
    renderable: bool


def _parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=DEFAULT_MODEL)
    parser.add_argument("--label", default="")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--every-ms", type=int, default=200)
    parser.add_argument("--max-samples", type=int, default=60)
    parser.add_argument("--long-side", type=int, default=512)
    parser.add_argument("--num-poses", type=int, default=1)
    parser.add_argument("--mode", choices=["video", "image", "both"], default="video")
    parser.add_argument("--visibility-threshold", type=float, default=0.30)
    parser.add_argument("--avg-visibility-threshold", type=float, default=0.20)
    parser.add_argument("--min-high-visibility", type=int, default=8)
    parser.add_argument("--min-bbox-area", type=float, default=0.012)
    return parser.parse_args()


def _resize_long_side(frame: np.ndarray, long_side: int) -> np.ndarray:
    h, w = frame.shape[:2]
    scale = long_side / max(h, w)
    if scale >= 1.0:
        return frame
    return cv2.resize(frame, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)


def _read_frame_at_ms(cap: cv2.VideoCapture, timestamp_ms: int, long_side: int) -> np.ndarray | None:
    cap.set(cv2.CAP_PROP_POS_MSEC, float(timestamp_ms))
    ok, frame_bgr = cap.read()
    if not ok:
        return None
    return _resize_long_side(frame_bgr, long_side)


def _landmarker(model_path: Path, mode: str, num_poses: int) -> vision.PoseLandmarker:
    running_mode = vision.RunningMode.VIDEO if mode == "video" else vision.RunningMode.IMAGE
    options = vision.PoseLandmarkerOptions(
        base_options=python.BaseOptions(model_asset_path=str(model_path)),
        running_mode=running_mode,
        num_poses=num_poses,
        min_pose_detection_confidence=0.2,
        min_pose_presence_confidence=0.2,
        min_tracking_confidence=0.2,
    )
    return vision.PoseLandmarker.create_from_options(options)


def _pose_stats(
    landmarks: Iterable,
    avg_visibility_threshold: float,
    visibility_threshold: float,
    min_high_visibility: int,
    min_bbox_area: float,
) -> PoseStats:
    xs: list[float] = []
    ys: list[float] = []
    visibilities: list[float] = []
    for point in landmarks:
        visibility = float(getattr(point, "visibility", 1.0) or 0.0)
        presence = float(getattr(point, "presence", 1.0) or 0.0)
        if math.isfinite(point.x) and math.isfinite(point.y) and presence >= 0.05:
            xs.append(float(point.x))
            ys.append(float(point.y))
            visibilities.append(visibility)
    if not xs or not ys:
        return PoseStats(0.0, 0, 0.0, [0.0, 0.0, 0.0, 0.0], False)
    min_x = max(0.0, min(xs))
    max_x = min(1.0, max(xs))
    min_y = max(0.0, min(ys))
    max_y = min(1.0, max(ys))
    bbox_area = max(0.0, max_x - min_x) * max(0.0, max_y - min_y)
    avg_visibility = float(sum(visibilities) / max(1, len(visibilities)))
    high_count = sum(1 for visibility in visibilities if visibility >= visibility_threshold)
    renderable = (
        avg_visibility >= avg_visibility_threshold
        and high_count >= min_high_visibility
        and bbox_area >= min_bbox_area
    )
    return PoseStats(
        avg_visibility=avg_visibility,
        high_visibility_count=high_count,
        bbox_area=bbox_area,
        bbox=[min_x, min_y, max_x, max_y],
        renderable=renderable,
    )


def _draw_pose(frame_bgr: np.ndarray, landmarks: Iterable, stats: PoseStats, title: str) -> np.ndarray:
    out = frame_bgr.copy()
    h, w = out.shape[:2]
    points: list[tuple[int, int]] = []
    for point in landmarks:
        points.append((int(float(point.x) * w), int(float(point.y) * h)))
    for start, end in mp.solutions.pose.POSE_CONNECTIONS:
        if start < len(points) and end < len(points):
            cv2.line(out, points[start], points[end], (64, 220, 64), 1, cv2.LINE_AA)
    for x, y in points:
        cv2.circle(out, (x, y), 2, (0, 255, 255), -1, cv2.LINE_AA)
    x1, y1, x2, y2 = stats.bbox
    color = (40, 220, 40) if stats.renderable else (0, 180, 255)
    cv2.rectangle(out, (int(x1 * w), int(y1 * h)), (int(x2 * w), int(y2 * h)), color, 1)
    cv2.putText(out, title, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 2, cv2.LINE_AA)
    cv2.putText(out, title, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, color, 1, cv2.LINE_AA)
    return out


def _draw_empty(frame_bgr: np.ndarray, title: str) -> np.ndarray:
    out = frame_bgr.copy()
    cv2.putText(out, title, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 2, cv2.LINE_AA)
    cv2.putText(out, title, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 255), 1, cv2.LINE_AA)
    return out


def _contact_sheet(images: list[np.ndarray], output_path: Path, columns: int = 6) -> None:
    if not images:
        return
    thumb_h = 150
    thumbs: list[np.ndarray] = []
    for image in images:
        h, w = image.shape[:2]
        scale = thumb_h / h
        thumbs.append(cv2.resize(image, (int(w * scale), thumb_h), interpolation=cv2.INTER_AREA))
    max_w = max(image.shape[1] for image in thumbs)
    padded = []
    for image in thumbs:
        if image.shape[1] < max_w:
            image = cv2.copyMakeBorder(image, 0, 0, 0, max_w - image.shape[1], cv2.BORDER_CONSTANT, value=(24, 24, 24))
        padded.append(image)
    rows = []
    for index in range(0, len(padded), columns):
        row = padded[index : index + columns]
        if len(row) < columns:
            row += [np.zeros_like(padded[0])] * (columns - len(row))
        rows.append(np.hstack(row))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(output_path), np.vstack(rows))


def _run_mode(args: argparse.Namespace, mode: str) -> tuple[dict, list[np.ndarray]]:
    cap = cv2.VideoCapture(str(args.video))
    if not cap.isOpened():
        raise FileNotFoundError(f"Unable to open video: {args.video}")
    duration_ms = int((cap.get(cv2.CAP_PROP_FRAME_COUNT) / max(1.0, cap.get(cv2.CAP_PROP_FPS))) * 1000)
    sample_count = min(args.max_samples, max(1, duration_ms // max(1, args.every_ms) + 1))
    rows: list[dict] = []
    images: list[np.ndarray] = []
    with _landmarker(args.model, mode, args.num_poses) as landmarker:
        for sample_index in range(sample_count):
            timestamp_ms = sample_index * args.every_ms
            frame = _read_frame_at_ms(cap, timestamp_ms, args.long_side)
            if frame is None:
                break
            frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
            mp_image = mp.Image(image_format=mp.ImageFormat.SRGB, data=frame_rgb)
            if mode == "video":
                result = landmarker.detect_for_video(mp_image, timestamp_ms)
            else:
                result = landmarker.detect(mp_image)
            pose_landmarks = list(result.pose_landmarks or [])
            pose_stats = [
                _pose_stats(
                    pose,
                    args.avg_visibility_threshold,
                    args.visibility_threshold,
                    args.min_high_visibility,
                    args.min_bbox_area,
                )
                for pose in pose_landmarks
            ]
            best_index = -1
            if pose_stats:
                best_index = max(range(len(pose_stats)), key=lambda idx: pose_stats[idx].bbox_area)
            best = pose_stats[best_index] if best_index >= 0 else None
            renderable_count = sum(1 for stats in pose_stats if stats.renderable)
            row = {
                "sample_index": sample_index,
                "timestamp_ms": timestamp_ms,
                "pose_count": len(pose_landmarks),
                "renderable_count": renderable_count,
                "best_pose_index": best_index,
                "best_pose": asdict(best) if best else None,
            }
            rows.append(row)
            if best_index >= 0 and best:
                title = (
                    f"{mode} s{sample_index} {timestamp_ms}ms "
                    f"p{len(pose_landmarks)} r{renderable_count} v{best.avg_visibility:.2f}"
                )
                images.append(_draw_pose(frame, pose_landmarks[best_index], best, title))
            else:
                images.append(_draw_empty(frame, f"{mode} s{sample_index} {timestamp_ms}ms no pose"))
    cap.release()
    missing = [row["sample_index"] for row in rows if row["pose_count"] == 0]
    non_renderable = [row["sample_index"] for row in rows if row["pose_count"] > 0 and row["renderable_count"] == 0]
    first_pose = next((row["sample_index"] for row in rows if row["pose_count"] > 0), None)
    first_renderable = next((row["sample_index"] for row in rows if row["renderable_count"] > 0), None)
    return (
        {
            "mode": mode,
            "samples": len(rows),
            "pose_hits": sum(1 for row in rows if row["pose_count"] > 0),
            "renderable_hits": sum(1 for row in rows if row["renderable_count"] > 0),
            "first_pose_sample": first_pose,
            "first_renderable_sample": first_renderable,
            "missing_samples": missing,
            "non_renderable_samples": non_renderable,
            "rows": rows,
        },
        images,
    )


def main() -> int:
    args = _parse_args()
    if not args.video.exists():
        raise FileNotFoundError(args.video)
    if not args.model.exists():
        raise FileNotFoundError(args.model)
    label = args.label or args.video.stem
    modes = ["video", "image"] if args.mode == "both" else [args.mode]
    args.out_dir.mkdir(parents=True, exist_ok=True)
    all_results: dict[str, dict] = {}
    for mode in modes:
        result, images = _run_mode(args, mode)
        all_results[mode] = result
        _contact_sheet(images, args.out_dir / f"{label}_{mode}_contact_sheet.jpg")
    report = {
        "label": label,
        "video": str(args.video),
        "model": str(args.model),
        "config": {
            "every_ms": args.every_ms,
            "max_samples": args.max_samples,
            "long_side": args.long_side,
            "num_poses": args.num_poses,
            "avg_visibility_threshold": args.avg_visibility_threshold,
            "visibility_threshold": args.visibility_threshold,
            "min_high_visibility": args.min_high_visibility,
            "min_bbox_area": args.min_bbox_area,
        },
        "results": all_results,
    }
    report_path = args.out_dir / f"{label}_pose_timeline.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote {report_path}")
    for mode, result in all_results.items():
        print(
            f"{mode}: samples={result['samples']} pose_hits={result['pose_hits']} "
            f"renderable_hits={result['renderable_hits']} first_pose={result['first_pose_sample']} "
            f"first_renderable={result['first_renderable_sample']}"
        )
        if result["missing_samples"]:
            print(f"{mode}: missing samples {result['missing_samples'][:30]}")
        if result["non_renderable_samples"]:
            print(f"{mode}: non-renderable samples {result['non_renderable_samples'][:30]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
