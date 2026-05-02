"""
Validate GemmaFit prototype formulas on a real test video.

Pipeline:
  video -> MediaPipe landmarks CSV -> angle/FPPA/velocity CSV -> formula report

Run:
  cd prototype
  python validate_formula_video.py --video path/to/test_squat.mp4 --label good

This script does not use a camera and does not require a dataset. It only needs
one local video file.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
import sys
from types import SimpleNamespace
from typing import Any

import cv2

from compute_angles import (
    OUTPUT_HEADER,
    RAPID_MOVEMENT_THRESHOLD_DPS,
    make_output_path as make_angles_output_path,
    process_csv,
)
from com_tracker_prototype import track_com


RESULT_DIR = os.path.join("data", "validation", "results")
os.makedirs(RESULT_DIR, exist_ok=True)

LANDMARK_NAMES = [
    "nose", "left_eye_inner", "left_eye", "left_eye_outer",
    "right_eye_inner", "right_eye", "right_eye_outer",
    "left_ear", "right_ear", "mouth_left", "mouth_right",
    "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
    "left_wrist", "right_wrist", "left_pinky", "right_pinky",
    "left_index", "right_index", "left_thumb", "right_thumb",
    "left_hip", "right_hip", "left_knee", "right_knee",
    "left_ankle", "right_ankle", "left_heel", "right_heel",
    "left_foot_index", "right_foot_index",
]


def safe_float(value: Any, default: float = math.nan) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def finite_values(rows: list[dict[str, str]], key: str) -> list[float]:
    values = [safe_float(row.get(key)) for row in rows]
    return [value for value in values if math.isfinite(value)]


def summarize(values: list[float]) -> dict[str, float | int | None]:
    if not values:
        return {"count": 0, "min": None, "median": None, "max": None}
    return {
        "count": len(values),
        "min": round(min(values), 4),
        "median": round(statistics.median(values), 4),
        "max": round(max(values), 4),
    }


def read_csv(path: str) -> list[dict[str, str]]:
    with open(path, newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def video_fps(path: str) -> float:
    cap = cv2.VideoCapture(path)
    if not cap.isOpened():
        return 30.0
    fps = cap.get(cv2.CAP_PROP_FPS)
    cap.release()
    return float(fps) if fps and fps > 0 else 30.0


def make_landmark_output_path(input_path: str, label: str) -> str:
    base = os.path.splitext(os.path.basename(input_path))[0]
    name = f"{base}_{label}.csv" if label else f"{base}.csv"
    return os.path.join("data", "processed", "landmarks", name)


def row_to_landmarks(row: dict[str, str]) -> list[SimpleNamespace]:
    landmarks: list[SimpleNamespace] = []
    for name in LANDMARK_NAMES:
        landmarks.append(SimpleNamespace(
            x=safe_float(row.get(f"{name}_x"), 0.0),
            y=safe_float(row.get(f"{name}_y"), 0.0),
            z=safe_float(row.get(f"{name}_z"), 0.0),
            visibility=safe_float(row.get(f"{name}_vis"), 0.0),
        ))
    return landmarks


def summarize_com(landmark_rows: list[dict[str, str]]) -> dict[str, Any]:
    results = []
    for row in landmark_rows:
        try:
            results.append(track_com(row_to_landmarks(row), contact="bipedal"))
        except Exception as exc:  # Keep validation report complete for bad frames.
            results.append(SimpleNamespace(inside=False, offset_ratio=math.nan, error=str(exc)))

    offsets = [
        float(result.offset_ratio)
        for result in results
        if hasattr(result, "offset_ratio") and math.isfinite(float(result.offset_ratio))
    ]
    inside_count = sum(1 for result in results if getattr(result, "inside", False))
    total = len(results)
    return {
        "frames": total,
        "inside_count": inside_count,
        "inside_rate": round(inside_count / total, 4) if total else 0.0,
        "offset_ratio": summarize(offsets),
    }


def check_range(
    name: str,
    values: list[float],
    min_allowed: float,
    max_allowed: float,
    failures: list[str],
) -> None:
    for idx, value in enumerate(values):
        if not (min_allowed <= value <= max_allowed):
            failures.append(f"{name}[{idx}]={value:.4f} outside [{min_allowed}, {max_allowed}]")
            return


def build_formula_checks(angle_rows: list[dict[str, str]]) -> dict[str, Any]:
    failures: list[str] = []
    warnings: list[str] = []

    angle_columns = ["knee_angle", "hip_angle", "back_angle"]
    for column in angle_columns:
        check_range(column, finite_values(angle_rows, column), 0.0, 180.0, failures)

    for column in ["left_fppa", "right_fppa", "max_fppa"]:
        if column in OUTPUT_HEADER:
            check_range(column, finite_values(angle_rows, column), 0.0, 180.0, failures)

    velocity_columns = [
        "knee_velocity_dps",
        "hip_velocity_dps",
        "back_velocity_dps",
        "max_angular_velocity_dps",
    ]
    for column in velocity_columns:
        values = finite_values(angle_rows, column)
        if any(value < 0 for value in values):
            failures.append(f"{column} contains negative velocity")

    allowed_phases = {"top", "descending", "bottom", "ascending"}
    observed_phases = {row.get("squat_phase", "") for row in angle_rows}
    bad_phases = sorted(observed_phases - allowed_phases)
    if bad_phases:
        failures.append(f"unexpected squat_phase values: {bad_phases}")

    rapid_values = finite_values(angle_rows, "max_angular_velocity_dps")
    if rapid_values and max(rapid_values) > RAPID_MOVEMENT_THRESHOLD_DPS:
        warnings.append(
            f"rapid movement detected: max={max(rapid_values):.2f} deg/s "
            f"> threshold={RAPID_MOVEMENT_THRESHOLD_DPS:.2f} deg/s"
        )

    ratio_values = finite_values(angle_rows, "valgus_ratio")
    if any(value < 0 for value in ratio_values):
        failures.append("valgus_ratio contains negative values")

    return {
        "pass": not failures,
        "failures": failures,
        "warnings": warnings,
        "observed_phases": sorted(observed_phases),
    }


def build_report(
    video_path: str,
    landmark_csv: str,
    angle_csv: str,
    fps: float,
    sample_every: int,
) -> dict[str, Any]:
    landmark_rows = read_csv(landmark_csv)
    angle_rows = read_csv(angle_csv)

    summaries = {
        "knee_angle": summarize(finite_values(angle_rows, "knee_angle")),
        "hip_angle": summarize(finite_values(angle_rows, "hip_angle")),
        "back_angle": summarize(finite_values(angle_rows, "back_angle")),
        "valgus_ratio": summarize(finite_values(angle_rows, "valgus_ratio")),
        "max_fppa": summarize(finite_values(angle_rows, "max_fppa")),
        "max_angular_velocity_dps": summarize(finite_values(angle_rows, "max_angular_velocity_dps")),
    }

    return {
        "video": os.path.abspath(video_path),
        "fps": round(fps, 4),
        "sample_every": sample_every,
        "landmark_csv": os.path.abspath(landmark_csv),
        "angle_csv": os.path.abspath(angle_csv),
        "landmark_frames": len(landmark_rows),
        "angle_frames": len(angle_rows),
        "summaries": summaries,
        "com": summarize_com(landmark_rows),
        "checks": build_formula_checks(angle_rows),
    }


def write_report(report: dict[str, Any], output_path: str) -> None:
    with open(output_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)


def print_report(report: dict[str, Any], output_path: str) -> None:
    checks = report["checks"]
    print("\n=== GemmaFit Formula Video Validation ===")
    print(f"video: {report['video']}")
    print(f"fps: {report['fps']}  sample_every: {report['sample_every']}")
    print(f"landmark frames: {report['landmark_frames']}")
    print(f"angle frames: {report['angle_frames']}")
    print("\nMetric summaries:")
    for name, summary in report["summaries"].items():
        print(f"  {name:<28} count={summary['count']} min={summary['min']} median={summary['median']} max={summary['max']}")

    com = report["com"]
    print("\nCOM / BoS:")
    print(f"  inside_rate={com['inside_rate']} ({com['inside_count']}/{com['frames']})")
    print(f"  offset_ratio={com['offset_ratio']}")

    if checks["warnings"]:
        print("\nWarnings:")
        for warning in checks["warnings"]:
            print(f"  - {warning}")

    if checks["failures"]:
        print("\nFailures:")
        for failure in checks["failures"]:
            print(f"  - {failure}")

    print(f"\nreport: {os.path.abspath(output_path)}")
    print("status:", "PASS" if checks["pass"] else "FAIL")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate GemmaFit formulas on one local video.")
    parser.add_argument("--video", required=True, help="Local test video path.")
    parser.add_argument("--label", default="test", help="Label written to the landmark CSV.")
    parser.add_argument("--sample-every", type=int, default=3, help="Extract one frame every N frames.")
    parser.add_argument("--fps", type=float, default=0.0, help="Override video FPS for deg/s calculation.")
    parser.add_argument("--landmark-csv", default="", help="Optional output landmark CSV path.")
    parser.add_argument("--angle-csv", default="", help="Optional output angle CSV path.")
    parser.add_argument("--report", default="", help="Optional output JSON report path.")
    args = parser.parse_args()

    if not os.path.isfile(args.video):
        print(f"[error] video not found: {args.video}")
        return 1

    fps = args.fps if args.fps > 0 else video_fps(args.video)
    landmark_csv = args.landmark_csv or make_landmark_output_path(args.video, args.label)
    angle_csv = args.angle_csv or make_angles_output_path(landmark_csv)
    report_path = args.report or os.path.join(
        RESULT_DIR,
        f"{os.path.splitext(os.path.basename(args.video))[0]}_{args.label}_formula_report.json",
    )

    print("[1/3] Extracting MediaPipe landmarks...")
    from extract_landmarks import extract_from_video

    extract_from_video(args.video, args.label, landmark_csv, args.sample_every)

    print("[2/3] Computing angles, FPPA, and deg/s velocities...")
    process_csv(landmark_csv, angle_csv, fps=fps)

    print("[3/3] Building formula sanity report...")
    report = build_report(args.video, landmark_csv, angle_csv, fps, args.sample_every)
    write_report(report, report_path)
    print_report(report, report_path)

    if report["landmark_frames"] == 0 or report["angle_frames"] == 0:
        print("[error] no pose frames were detected; use a clearer full-body test video.")
        return 1
    return 0 if report["checks"]["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
