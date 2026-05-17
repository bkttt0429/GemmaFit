#!/usr/bin/env python3
"""Host-side video benchmark for the live camera Bitmap rotate decision.

This does not emulate CameraX internals. It measures the app-side work that the
old analyzer path performed after receiving an upright-or-rotated frame:

current_yuv_bitmap_rotate:
  BGR frame -> RGBA bitmap-like buffer -> 90 degree rotate -> copy

camerax_rotated_yuv_bitmap:
  BGR frame -> RGBA bitmap-like buffer -> copy

Use the Pixel RGBA pipeline audit for device truth. This script is a fast,
repeatable local-video sanity check that app-side rotation is the part we want
to remove.
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import time
from pathlib import Path
from typing import Any

import cv2


def percentile(values: list[float], pct: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = round((pct / 100.0) * (len(ordered) - 1))
    return ordered[index]


def summarize(values: list[float]) -> dict[str, float]:
    if not values:
        return {
            "count": 0,
            "avg_us": 0.0,
            "median_us": 0.0,
            "p95_us": 0.0,
            "p99_us": 0.0,
            "min_us": 0.0,
            "max_us": 0.0,
        }
    return {
        "count": len(values),
        "avg_us": statistics.fmean(values),
        "median_us": statistics.median(values),
        "p95_us": percentile(values, 95),
        "p99_us": percentile(values, 99),
        "min_us": min(values),
        "max_us": max(values),
    }


def safe_name(path: Path) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", path.stem).strip("_")


def measure_once(frame, width: int, height: int) -> tuple[float, float, int]:
    resized = cv2.resize(frame, (width, height), interpolation=cv2.INTER_AREA)
    checksum = 0

    start = time.perf_counter_ns()
    rgba_current = cv2.cvtColor(resized, cv2.COLOR_BGR2RGBA)
    rotated = cv2.rotate(rgba_current, cv2.ROTATE_90_CLOCKWISE)
    current_sink = rotated.copy()
    current_us = (time.perf_counter_ns() - start) / 1000.0
    checksum += int(current_sink[0, 0, 0])

    start = time.perf_counter_ns()
    rgba_optimized = cv2.cvtColor(resized, cv2.COLOR_BGR2RGBA)
    optimized_sink = rgba_optimized.copy()
    optimized_us = (time.perf_counter_ns() - start) / 1000.0
    checksum += int(optimized_sink[0, 0, 0])

    return current_us, optimized_us, checksum


def benchmark_video(
    video_path: Path,
    max_frames: int,
    warmup_frames: int,
    width: int,
    height: int,
) -> dict[str, Any]:
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise RuntimeError(f"Could not open video: {video_path}")

    current_samples: list[float] = []
    optimized_samples: list[float] = []
    checksum = 0
    decoded = 0
    used = 0
    source_width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
    source_height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
    fps = float(capture.get(cv2.CAP_PROP_FPS) or 0.0)

    try:
        while used < max_frames:
            ok, frame = capture.read()
            if not ok:
                break
            decoded += 1
            if decoded <= warmup_frames:
                continue
            current_us, optimized_us, frame_checksum = measure_once(frame, width, height)
            current_samples.append(current_us)
            optimized_samples.append(optimized_us)
            checksum += frame_checksum
            used += 1
    finally:
        capture.release()

    current = summarize(current_samples)
    optimized = summarize(optimized_samples)
    avg_saved = current["avg_us"] - optimized["avg_us"]
    p95_saved = current["p95_us"] - optimized["p95_us"]
    avg_saved_pct = (avg_saved / current["avg_us"] * 100.0) if current["avg_us"] else 0.0

    return {
        "video": str(video_path),
        "source": {
            "width": source_width,
            "height": source_height,
            "fps": fps,
        },
        "simulated_input": {
            "width": width,
            "height": height,
            "warmup_frames": warmup_frames,
            "measured_frames": used,
        },
        "pipelines": {
            "current_yuv_bitmap_rotate": current,
            "camerax_rotated_yuv_bitmap": optimized,
        },
        "delta": {
            "avg_saved_us": avg_saved,
            "p95_saved_us": p95_saved,
            "avg_saved_pct": avg_saved_pct,
        },
        "checksum": checksum,
    }


def fmt_us(value: float) -> str:
    return f"{value:,.0f}"


def write_report(out_dir: Path, results: list[dict[str, Any]]) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    summary_path = out_dir / "summary.json"
    summary_path.write_text(json.dumps({"results": results}, indent=2), encoding="utf-8")

    lines = [
        "# Bitmap Rotate Local Video Simulation",
        "",
        "Date: 2026-05-16",
        "",
        "Purpose: repeat the Bitmap rotate optimization against local mp4 input without requiring the live camera.",
        "",
        "This is a host-side OpenCV simulation. It validates the direction and size of app-side rotate cost on decoded video frames, but it does not measure CameraX internal thread cost or MediaPipe pose correctness. The Pixel live-camera audit remains the device source of truth.",
        "",
        "## Method",
        "",
        "- `current_yuv_bitmap_rotate`: decoded frame -> RGBA bitmap-like buffer -> 90 degree rotate -> copy.",
        "- `camerax_rotated_yuv_bitmap`: decoded frame -> RGBA bitmap-like buffer -> copy.",
        "- Input frames are resized to the configured analyzer size before measuring both paths.",
        "",
        "## Results",
        "",
        "| Video | Frames | Input | Current avg / p95 us | Optimized avg / p95 us | Avg saved | P95 saved | Avg saved % |",
        "| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |",
    ]

    for result in results:
        current = result["pipelines"]["current_yuv_bitmap_rotate"]
        optimized = result["pipelines"]["camerax_rotated_yuv_bitmap"]
        delta = result["delta"]
        input_size = result["simulated_input"]
        lines.append(
            "| {video} | {frames} | {width}x{height} | {cur_avg} / {cur_p95} | {opt_avg} / {opt_p95} | {avg_saved} | {p95_saved} | {pct:.1f}% |".format(
                video=Path(result["video"]).name,
                frames=input_size["measured_frames"],
                width=input_size["width"],
                height=input_size["height"],
                cur_avg=fmt_us(current["avg_us"]),
                cur_p95=fmt_us(current["p95_us"]),
                opt_avg=fmt_us(optimized["avg_us"]),
                opt_p95=fmt_us(optimized["p95_us"]),
                avg_saved=fmt_us(delta["avg_saved_us"]),
                p95_saved=fmt_us(delta["p95_saved_us"]),
                pct=delta["avg_saved_pct"],
            )
        )

    lines.extend(
        [
            "",
            "## Decision",
            "",
            "`camerax_rotated_yuv_bitmap` should be the live-camera default. It keeps the existing YUV-to-Bitmap conversion behavior but removes app-layer `Bitmap` rotation from the analyzer hot path.",
            "",
            "`camerax_rotated_rgba_bitmap` remains a debug variant until landmark correctness and channel behavior are checked with a person visible.",
            "",
            "## Artifacts",
            "",
            "- `summary.json`",
        ]
    )

    (out_dir / "README.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", action="append", required=True, help="Path to a local mp4 or other OpenCV-readable video.")
    parser.add_argument("--out-dir", required=True, help="Directory for summary.json and README.md.")
    parser.add_argument("--max-frames", type=int, default=300)
    parser.add_argument("--warmup-frames", type=int, default=10)
    parser.add_argument("--resize-width", type=int, default=640)
    parser.add_argument("--resize-height", type=int, default=480)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    results = []
    for video in args.video:
        video_path = Path(video).resolve()
        results.append(
            benchmark_video(
                video_path=video_path,
                max_frames=args.max_frames,
                warmup_frames=args.warmup_frames,
                width=args.resize_width,
                height=args.resize_height,
            )
        )
    write_report(Path(args.out_dir).resolve(), results)
    print(json.dumps({"out_dir": str(Path(args.out_dir).resolve()), "videos": len(results)}, indent=2))


if __name__ == "__main__":
    main()
