"""Evaluate whether sparse MotionZip evidence can summarize video content.

This benchmark answers a narrower question than packet integrity:

  Can GemmaFit understand the important content of a video segment without
  tracking every frame?

It uses an existing derived timeline as the dense reference, then simulates
lower tracking budgets by taking every Nth pose sample before building
MotionZip sparse event blocks. The output compares content tags, state
coverage, peak-motion preservation, confidence-floor behavior, and event
location coverage.

No model is called and no raw video is inspected.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import math
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
PROTOTYPE_DIR = Path(__file__).resolve().parent
DEFAULT_ANGLE_CSV = PROTOTYPE_DIR / "data" / "processed" / "angles" / "lunge_forward_army_motionzip_angles.csv"
DEFAULT_LANDMARK_CSV = PROTOTYPE_DIR / "data" / "processed" / "landmarks" / "lunge_forward_army_motionzip.csv"
DEFAULT_OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding"

import sys

sys.path.insert(0, str(PROTOTYPE_DIR))
from build_motionzip_packet_from_video import build_packet, build_sparse_blocks, select_sparse_events  # noqa: E402


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def safe_float(value: Any, default: float = math.nan) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default


def safe_int(value: Any, default: int = 0) -> int:
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def thin_rows(rows: list[dict[str, str]], stride: int) -> list[dict[str, str]]:
    if stride <= 1:
        return list(rows)
    return rows[::stride]


def filter_landmarks_for_frames(rows: list[dict[str, str]], frames: set[int]) -> list[dict[str, str]]:
    return [row for row in rows if safe_int(row.get("frame")) in frames]


def block_tags(packet: dict[str, Any]) -> set[str]:
    tags: set[str] = set()
    for block in packet.get("compressed_sparse_blocks", []):
        tags.update(str(token) for token in block.get("tokens", []))
        state = block.get("rule_policy_state")
        if state:
            tags.add(str(state))
        extrema = block.get("preserved_extrema", {})
        for flag in extrema.get("geometry_quality_flags", []) or []:
            tags.add(str(flag))
    return tags


def block_states(packet: dict[str, Any]) -> set[str]:
    return {
        str(block.get("rule_policy_state"))
        for block in packet.get("compressed_sparse_blocks", [])
        if block.get("rule_policy_state")
    }


def event_end_frames(packet: dict[str, Any]) -> list[int]:
    frames: list[int] = []
    for block in packet.get("compressed_sparse_blocks", []):
        source_frames = block.get("source_frames") or []
        if len(source_frames) >= 2:
            frames.append(safe_int(source_frames[1]))
    return frames


def peak_velocity(packet: dict[str, Any]) -> float:
    values = []
    for block in packet.get("compressed_sparse_blocks", []):
        value = safe_float(block.get("preserved_extrema", {}).get("peak_velocity_deg_s"))
        if math.isfinite(value):
            values.append(value)
    return max(values) if values else 0.0


def min_confidence(packet: dict[str, Any]) -> float | None:
    values = []
    for block in packet.get("compressed_sparse_blocks", []):
        value = safe_float(block.get("preserved_extrema", {}).get("confidence_floor"))
        if math.isfinite(value):
            values.append(value)
    return min(values) if values else None


def coverage(reference_frames: list[int], candidate_frames: list[int], tolerance_frames: int) -> float:
    if not reference_frames:
        return 1.0
    hits = 0
    for ref in reference_frames:
        if any(abs(ref - cand) <= tolerance_frames for cand in candidate_frames):
            hits += 1
    return hits / len(reference_frames)


def jaccard_recall(reference: set[str], candidate: set[str]) -> float:
    if not reference:
        return 1.0
    return len(reference & candidate) / len(reference)


def make_packet(
    video_path: Path,
    angle_rows: list[dict[str, str]],
    landmark_rows: list[dict[str, str]],
    activity_hint: str,
    sample_every: int,
    fps: float,
    window_ms: int,
    max_blocks: int,
    min_event_velocity: float,
    min_frame_gap: int,
    min_confidence_floor: float,
    label: str,
) -> dict[str, Any]:
    events = select_sparse_events(
        angle_rows=angle_rows,
        max_blocks=max_blocks,
        min_velocity=min_event_velocity,
        min_frame_gap=min_frame_gap,
    )
    blocks = build_sparse_blocks(
        angle_rows=angle_rows,
        landmark_rows=landmark_rows,
        events=events,
        fps=fps,
        window_ms=window_ms,
        activity_hint=activity_hint,
        min_confidence_floor=min_confidence_floor,
    )
    report = {
        "video_frames_total": 0,
        "com": {},
        "summaries": {
            "max_angular_velocity_dps": {
                "max": max([safe_float(row.get("max_angular_velocity_dps"), 0.0) for row in angle_rows] or [0.0])
            }
        },
    }
    packet = build_packet(
        video_path=video_path,
        angle_rows=angle_rows,
        landmark_rows=landmark_rows,
        report=report,
        fps=fps,
        sample_every=sample_every,
        blocks=blocks,
        activity_hint=activity_hint,
        window_ms=window_ms,
    )
    packet["window_id"] = f"video.{video_path.stem}.{label}"
    return packet


def make_bar_svg(path: Path, title: str, rows: list[tuple[str, float, str]], max_value: float = 1.0) -> None:
    width = 980
    top = 70
    row_h = 44
    left = 290
    chart_w = 560
    height = top + len(rows) * row_h + 40
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        f'<text x="30" y="38" font-family="Arial, sans-serif" font-size="24" font-weight="700" fill="#0f172a">{html.escape(title)}</text>',
    ]
    for index, (label, value, display) in enumerate(rows):
        y = top + index * row_h
        bar_w = int(chart_w * min(max(value, 0.0) / max_value, 1.0))
        if value > 0 and bar_w < 3:
            bar_w = 3
        color = "#2563eb" if value >= 0.9 * max_value else "#0f766e"
        if value < 0.5 * max_value:
            color = "#b45309"
        lines.extend(
            [
                f'<text x="30" y="{y + 24}" font-family="Arial, sans-serif" font-size="15" fill="#334155">{html.escape(label)}</text>',
                f'<rect x="{left}" y="{y + 7}" width="{chart_w}" height="24" rx="4" fill="#e2e8f0"/>',
                f'<rect x="{left}" y="{y + 7}" width="{bar_w}" height="24" rx="4" fill="{color}"/>',
                f'<text x="{left + chart_w + 16}" y="{y + 25}" font-family="Arial, sans-serif" font-size="15" font-weight="700" fill="#0f172a">{html.escape(display)}</text>',
            ]
        )
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def make_understanding_svg(path: Path, variants: list[dict[str, Any]]) -> None:
    rows = [
        (
            f"track every {item['relative_stride']} pose sample(s)",
            float(item["understanding_score"]),
            f"{item['understanding_score'] * 100:.1f}%",
        )
        for item in variants
    ]
    make_bar_svg(path, "Sparse Tracking Understanding Score", rows, max_value=1.0)


def make_tradeoff_svg(path: Path, variants: list[dict[str, Any]]) -> None:
    width = 1000
    height = 460
    left = 90
    top = 60
    chart_w = 820
    chart_h = 300
    max_samples = max(item["tracked_pose_samples"] for item in variants) or 1
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        '<text x="30" y="35" font-family="Arial, sans-serif" font-size="24" font-weight="700" fill="#0f172a">Tracking Budget vs Video Understanding</text>',
        f'<line x1="{left}" y1="{top + chart_h}" x2="{left + chart_w}" y2="{top + chart_h}" stroke="#475569" stroke-width="2"/>',
        f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top + chart_h}" stroke="#475569" stroke-width="2"/>',
        '<text x="30" y="210" transform="rotate(-90 30 210)" font-family="Arial, sans-serif" font-size="14" fill="#334155">Understanding score</text>',
        f'<text x="{left + chart_w / 2}" y="{height - 35}" text-anchor="middle" font-family="Arial, sans-serif" font-size="14" fill="#334155">Tracked pose samples</text>',
    ]
    points: list[tuple[float, float]] = []
    for item in sorted(variants, key=lambda row: row["tracked_pose_samples"]):
        x = left + chart_w * (item["tracked_pose_samples"] / max_samples)
        y = top + chart_h * (1.0 - item["understanding_score"])
        points.append((x, y))
        lines.extend(
            [
                f'<circle cx="{x:.1f}" cy="{y:.1f}" r="6" fill="#2563eb"/>',
                f'<text x="{x:.1f}" y="{y - 12:.1f}" text-anchor="middle" font-family="Arial, sans-serif" font-size="12" fill="#0f172a">{item["relative_stride"]}x</text>',
            ]
        )
    if len(points) > 1:
        path_d = " ".join(f"{x:.1f},{y:.1f}" for x, y in points)
        lines.insert(-len(points) * 2, f'<polyline points="{path_d}" fill="none" stroke="#2563eb" stroke-width="3"/>')
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate sparse MotionZip video understanding.")
    parser.add_argument("--angle-csv", default=str(DEFAULT_ANGLE_CSV))
    parser.add_argument("--landmark-csv", default=str(DEFAULT_LANDMARK_CSV))
    parser.add_argument("--video", default="test_assets/videos/internet_public_mp4/lunge_forward_army.mp4")
    parser.add_argument("--activity-hint", default="lunge_like_unilateral_motion")
    parser.add_argument("--fps", type=float, default=29.97)
    parser.add_argument("--base-sample-every", type=int, default=6)
    parser.add_argument("--strides", default="1,2,4,8,16")
    parser.add_argument("--window-ms", type=int, default=1600)
    parser.add_argument("--max-blocks", type=int, default=2)
    parser.add_argument("--min-event-velocity", type=float, default=300.0)
    parser.add_argument("--min-frame-gap", type=int, default=240)
    parser.add_argument("--min-confidence-floor", type=float, default=0.55)
    parser.add_argument("--event-tolerance-frames", type=int, default=240)
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR))
    args = parser.parse_args()

    angle_csv = Path(args.angle_csv)
    landmark_csv = Path(args.landmark_csv)
    video_path = Path(args.video)
    if not video_path.is_absolute():
        video_path = (REPO_ROOT / video_path).resolve()
    out_dir = Path(args.out_dir)
    if not out_dir.is_absolute():
        out_dir = (REPO_ROOT / out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    angle_rows = read_csv(angle_csv)
    landmark_rows = read_csv(landmark_csv)
    frame_numbers = [safe_int(row.get("frame")) for row in angle_rows]
    source_frame_span = max(frame_numbers) if frame_numbers else 0
    strides = [int(part.strip()) for part in args.strides.split(",") if part.strip()]
    dense_packet = make_packet(
        video_path=video_path,
        angle_rows=angle_rows,
        landmark_rows=landmark_rows,
        activity_hint=args.activity_hint,
        sample_every=args.base_sample_every,
        fps=args.fps,
        window_ms=args.window_ms,
        max_blocks=args.max_blocks,
        min_event_velocity=args.min_event_velocity,
        min_frame_gap=args.min_frame_gap,
        min_confidence_floor=args.min_confidence_floor,
        label="dense_reference",
    )
    reference_tags = block_tags(dense_packet)
    reference_states = block_states(dense_packet)
    reference_frames = event_end_frames(dense_packet)
    reference_peak = peak_velocity(dense_packet)
    reference_conf = min_confidence(dense_packet)

    variants: list[dict[str, Any]] = []
    packet_dir = out_dir / "packets"
    packet_dir.mkdir(parents=True, exist_ok=True)
    (packet_dir / "dense_reference_packet.json").write_text(
        json.dumps(dense_packet, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    for stride in strides:
        sparse_angles = thin_rows(angle_rows, stride)
        frames = {safe_int(row.get("frame")) for row in sparse_angles}
        sparse_landmarks = filter_landmarks_for_frames(landmark_rows, frames)
        packet = make_packet(
            video_path=video_path,
            angle_rows=sparse_angles,
            landmark_rows=sparse_landmarks,
            activity_hint=args.activity_hint,
            sample_every=args.base_sample_every * stride,
            fps=args.fps,
            window_ms=args.window_ms,
            max_blocks=args.max_blocks,
            min_event_velocity=args.min_event_velocity,
            min_frame_gap=args.min_frame_gap,
            min_confidence_floor=args.min_confidence_floor,
            label=f"sparse_stride_{stride}",
        )
        tags = block_tags(packet)
        states = block_states(packet)
        frames_out = event_end_frames(packet)
        tag_recall = jaccard_recall(reference_tags, tags)
        state_recall = jaccard_recall(reference_states, states)
        event_coverage = coverage(reference_frames, frames_out, args.event_tolerance_frames)
        sparse_peak = peak_velocity(packet)
        peak_recall = min(sparse_peak / reference_peak, 1.0) if reference_peak else 1.0
        sparse_conf = min_confidence(packet)
        confidence_error = abs((sparse_conf if sparse_conf is not None else 0.0) - (reference_conf if reference_conf is not None else 0.0))
        confidence_score = max(0.0, 1.0 - min(confidence_error / 0.25, 1.0))
        understanding_score = (
            0.30 * tag_recall
            + 0.20 * state_recall
            + 0.25 * event_coverage
            + 0.15 * peak_recall
            + 0.10 * confidence_score
        )
        variant = {
            "relative_stride": stride,
            "effective_video_frame_interval": args.base_sample_every * stride,
            "tracked_pose_samples": len(sparse_angles),
            "sparse_blocks": len(packet.get("compressed_sparse_blocks", [])),
            "tags": sorted(tags),
            "states": sorted(states),
            "event_end_frames": frames_out,
            "tag_recall": tag_recall,
            "state_recall": state_recall,
            "event_coverage": event_coverage,
            "peak_velocity_recall": peak_recall,
            "confidence_floor_error": confidence_error,
            "confidence_score": confidence_score,
            "understanding_score": understanding_score,
            "packet_path": str((packet_dir / f"sparse_stride_{stride}_packet.json").relative_to(REPO_ROOT)),
        }
        variants.append(variant)
        (packet_dir / f"sparse_stride_{stride}_packet.json").write_text(
            json.dumps(packet, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )

    results = {
        "summary": {
            "video": str(video_path.relative_to(REPO_ROOT)) if video_path.exists() else str(video_path),
            "activity_hint": args.activity_hint,
            "source_frame_span": source_frame_span,
            "dense_reference_pose_samples": len(angle_rows),
            "dense_reference_blocks": len(dense_packet.get("compressed_sparse_blocks", [])),
            "dense_reference_tags": sorted(reference_tags),
            "dense_reference_states": sorted(reference_states),
            "best_sparse_understanding_score": max((item["understanding_score"] for item in variants), default=0.0),
            "lowest_budget_passing_tracked_samples": next(
                (
                    item["tracked_pose_samples"]
                    for item in sorted(variants, key=lambda row: row["tracked_pose_samples"])
                    if item["understanding_score"] >= 0.80
                ),
                None,
            ),
            "lowest_budget_passing_tracked_frame_ratio": next(
                (
                    item["tracked_pose_samples"] / source_frame_span
                    for item in sorted(variants, key=lambda row: row["tracked_pose_samples"])
                    if item["understanding_score"] >= 0.80 and source_frame_span
                ),
                None,
            ),
            "lowest_budget_passing_stride": next(
                (
                    item["relative_stride"]
                    for item in sorted(variants, key=lambda row: row["tracked_pose_samples"])
                    if item["understanding_score"] >= 0.80
                ),
                None,
            ),
            "lowest_budget_passing_effective_frame_interval": next(
                (
                    item["effective_video_frame_interval"]
                    for item in sorted(variants, key=lambda row: row["tracked_pose_samples"])
                    if item["understanding_score"] >= 0.80
                ),
                None,
            ),
        },
        "dense_reference": {
            "tags": sorted(reference_tags),
            "states": sorted(reference_states),
            "event_end_frames": reference_frames,
            "peak_velocity_deg_s": reference_peak,
            "confidence_floor": reference_conf,
            "packet_path": str((packet_dir / "dense_reference_packet.json").relative_to(REPO_ROOT)),
        },
        "variants": variants,
    }
    (out_dir / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    make_understanding_svg(out_dir / "sparse_understanding_score.svg", variants)
    make_tradeoff_svg(out_dir / "tracking_budget_tradeoff.svg", variants)

    lines = [
        "# MotionZip Sparse Understanding Report",
        "",
        "This benchmark tests whether MotionZip can preserve the meaningful content of a video segment without tracking every frame.",
        "The dense reference is the existing derived pose timeline; sparse variants simulate lower tracking budgets by taking every Nth pose sample before building MotionZip blocks.",
        "",
        "## Summary",
        "",
        "| Metric | Value |",
        "| --- | ---: |",
        f"| Source video frame span | {source_frame_span} |",
        f"| Dense reference pose samples | {len(angle_rows)} |",
        f"| Dense reference sparse blocks | {len(dense_packet.get('compressed_sparse_blocks', []))} |",
        f"| Best sparse understanding score | {max((item['understanding_score'] for item in variants), default=0.0) * 100:.1f}% |",
        f"| Lowest passing tracked samples | {results['summary']['lowest_budget_passing_tracked_samples']} |",
        f"| Lowest passing tracked-frame ratio | {(results['summary']['lowest_budget_passing_tracked_frame_ratio'] or 0.0) * 100:.1f}% |",
        f"| Lowest passing stride (score >= 80%) | {results['summary']['lowest_budget_passing_stride']} |",
        f"| Lowest passing effective frame interval | {results['summary']['lowest_budget_passing_effective_frame_interval']} video frames |",
        "",
        "## Visuals",
        "",
        "![Sparse understanding score](sparse_understanding_score.svg)",
        "",
        "![Tracking budget tradeoff](tracking_budget_tradeoff.svg)",
        "",
        "## Dense Reference Content",
        "",
        f"- Activity hint: `{args.activity_hint}`",
        f"- States: `{', '.join(sorted(reference_states))}`",
        f"- Tags: `{', '.join(sorted(reference_tags))}`",
        f"- Event frames: `{', '.join(str(frame) for frame in reference_frames)}`",
        f"- Peak velocity: `{reference_peak:.3f} deg/s`",
        f"- Confidence floor: `{reference_conf:.4f}`" if reference_conf is not None else "- Confidence floor: `n/a`",
        "",
        "## Sparse Variants",
        "",
        "| Relative stride | Effective frame interval | Tracked samples | Blocks | Tag recall | State recall | Event coverage | Peak recall | Understanding |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for item in variants:
        lines.append(
            f"| {item['relative_stride']} | {item['effective_video_frame_interval']} | "
            f"{item['tracked_pose_samples']} | {item['sparse_blocks']} | "
            f"{item['tag_recall'] * 100:.1f}% | {item['state_recall'] * 100:.1f}% | "
            f"{item['event_coverage'] * 100:.1f}% | {item['peak_velocity_recall'] * 100:.1f}% | "
            f"{item['understanding_score'] * 100:.1f}% |"
        )
    lines.extend(
        [
            "",
            "## Interpretation",
            "",
            "A passing sparse variant means the compressed evidence still identifies the segment as the same activity context, keeps the same conservative state labels, covers the key event windows, and preserves peak motion evidence closely enough for bounded Gemma routing.",
            "This does not mean raw video understanding or clinical correctness. It means the app can avoid per-frame model calls and avoid sending raw video while still giving Gemma the evidence needed for reports, refusal, and summaries.",
        ]
    )
    (out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")

    print(f"dense_pose_samples={len(angle_rows)}")
    print(f"variants={len(variants)}")
    print(f"best_understanding_score={results['summary']['best_sparse_understanding_score']:.3f}")
    print(f"lowest_budget_passing_stride={results['summary']['lowest_budget_passing_stride']}")
    print(f"report={out_dir / 'report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
