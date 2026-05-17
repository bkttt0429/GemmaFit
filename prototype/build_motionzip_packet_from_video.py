"""
Build a MotionZip-V4 evidence packet from one local video.

This is a prototype/productization bridge for GemmaFit's video path:

  video -> MediaPipe pose samples -> derived kinematic CSV -> sparse MotionZip packet

The packet intentionally stores derived evidence only. It does not store raw
video frames, full skeleton streams, force, GRF, EMG, joint moments, ligament
load, clinical labels, or medical risk predictions.

Example:
  cd D:\\GemmaFit
  python prototype/build_motionzip_packet_from_video.py ^
    --video test_assets/videos/internet_public_mp4/lunge_forward_army.mp4 ^
    --activity-hint lunge_like_unilateral_motion ^
    --sample-every 6
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
import sys
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
PROTOTYPE_DIR = Path(__file__).resolve().parent
RESULT_DIR = PROTOTYPE_DIR / "data" / "validation" / "results"
LANDMARK_DIR = PROTOTYPE_DIR / "data" / "processed" / "landmarks"
ANGLE_DIR = PROTOTYPE_DIR / "data" / "processed" / "angles"


UNSUPPORTED_LIMITS = [
    "derived_from_single_camera_pose",
    "sampled_video_pose_not_every_frame",
    "no_force_or_grf",
    "no_joint_force_or_grf",
    "no_joint_moment_or_ligament_load",
    "no_emg_or_muscle_activation",
    "no_medical_or_fall_risk_claim",
    "2d_geometry_can_be_unreliable_in_oblique_or_occluded_views",
]


KEY_VISIBILITY_LANDMARKS = [
    "left_shoulder",
    "right_shoulder",
    "left_hip",
    "right_hip",
    "left_knee",
    "right_knee",
    "left_ankle",
    "right_ankle",
]


def safe_float(value: Any, default: float = math.nan) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def finite(values: list[float]) -> list[float]:
    return [value for value in values if math.isfinite(value)]


def summarize(values: list[float]) -> dict[str, Any]:
    values = finite(values)
    if not values:
        return {"count": 0, "min": None, "median": None, "max": None}
    return {
        "count": len(values),
        "min": round(min(values), 4),
        "median": round(statistics.median(values), 4),
        "max": round(max(values), 4),
    }


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def load_prototype_modules() -> Any:
    sys.path.insert(0, str(PROTOTYPE_DIR))
    from compute_angles import make_output_path as make_angles_output_path
    from compute_angles import process_csv
    from extract_landmarks import extract_from_video
    from validate_formula_video import build_report, video_fps

    return {
        "extract_from_video": extract_from_video,
        "process_csv": process_csv,
        "make_angles_output_path": make_angles_output_path,
        "build_report": build_report,
        "video_fps": video_fps,
    }


def output_paths(video_path: Path, label: str) -> tuple[Path, Path, Path]:
    base = video_path.stem
    landmark_csv = LANDMARK_DIR / f"{base}_{label}.csv"
    angle_csv = ANGLE_DIR / f"{base}_{label}_angles.csv"
    report_json = RESULT_DIR / f"{base}_{label}_formula_report.json"
    return landmark_csv, angle_csv, report_json


def frame_ms(frame: int, fps: float) -> int:
    if fps <= 0:
        return 0
    return round(frame / fps * 1000)


def key_visibility(frame: int, landmark_by_frame: dict[int, dict[str, str]]) -> float | None:
    row = landmark_by_frame.get(frame)
    if not row:
        return None
    values = []
    for name in KEY_VISIBILITY_LANDMARKS:
        value = safe_float(row.get(f"{name}_vis"))
        if math.isfinite(value):
            values.append(value)
    return min(values) if values else None


def values_in_window(rows: list[dict[str, str]], key: str) -> list[float]:
    return finite([safe_float(row.get(key)) for row in rows])


def geometry_flags(
    confidence_floor: float,
    knee_values: list[float],
    hip_values: list[float],
    back_values: list[float],
    fppa_values: list[float],
    velocity_values: list[float],
    min_confidence_floor: float,
) -> list[str]:
    flags: list[str] = []
    if confidence_floor < min_confidence_floor:
        flags.append("low_keypoint_visibility")
    if min(knee_values or [180.0]) < 35.0 or min(hip_values or [180.0]) < 20.0 or min(back_values or [180.0]) < 20.0:
        flags.append("extreme_angle_geometry_caution")
    if max(fppa_values or [0.0]) > 90.0:
        flags.append("2d_fppa_unreliable_extreme")
    if max(velocity_values or [0.0]) > 600.0:
        flags.append("rapid_motion_proxy_high")
    return flags


def select_sparse_events(
    angle_rows: list[dict[str, str]],
    max_blocks: int,
    min_velocity: float,
    min_frame_gap: int,
) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for index, row in enumerate(angle_rows):
        frame = int(safe_float(row.get("frame"), 0.0))
        candidates.append(
            {
                "index": index,
                "frame": frame,
                "velocity": safe_float(row.get("max_angular_velocity_dps"), 0.0),
                "knee": safe_float(row.get("knee_angle")),
                "hip": safe_float(row.get("hip_angle")),
                "back": safe_float(row.get("back_angle")),
                "fppa": safe_float(row.get("max_fppa")),
                "valgus_ratio": safe_float(row.get("valgus_ratio")),
            }
        )

    selected: list[dict[str, Any]] = []
    for candidate in sorted(candidates, key=lambda item: item["velocity"], reverse=True):
        if candidate["velocity"] < min_velocity:
            break
        if all(abs(candidate["frame"] - prev["frame"]) > min_frame_gap for prev in selected):
            selected.append(candidate)
        if len(selected) >= max_blocks:
            break
    return sorted(selected, key=lambda item: item["frame"])


def build_sparse_blocks(
    angle_rows: list[dict[str, str]],
    landmark_rows: list[dict[str, str]],
    events: list[dict[str, Any]],
    fps: float,
    window_ms: int,
    activity_hint: str,
    min_confidence_floor: float,
) -> list[dict[str, Any]]:
    landmark_by_frame: dict[int, dict[str, str]] = {
        int(safe_float(row.get("frame"), 0.0)): row
        for row in landmark_rows
    }
    window_frames = max(1, round(fps * window_ms / 1000.0))
    blocks: list[dict[str, Any]] = []
    for offset, event in enumerate(events, 1):
        end_frame = int(event["frame"])
        start_frame = max(0, end_frame - window_frames)
        window = [
            row
            for row in angle_rows
            if start_frame <= int(safe_float(row.get("frame"), 0.0)) <= end_frame
        ]
        frames = [int(safe_float(row.get("frame"), 0.0)) for row in window]
        knee_values = values_in_window(window, "knee_angle")
        hip_values = values_in_window(window, "hip_angle")
        back_values = values_in_window(window, "back_angle")
        velocity_values = values_in_window(window, "max_angular_velocity_dps")
        fppa_values = values_in_window(window, "max_fppa")
        valgus_values = values_in_window(window, "valgus_ratio")
        visibility_values = finite([
            key_visibility(frame, landmark_by_frame) or math.nan
            for frame in frames
        ])
        confidence_floor = round(min(visibility_values), 4) if visibility_values else 0.0
        flags = geometry_flags(
            confidence_floor=confidence_floor,
            knee_values=knee_values,
            hip_values=hip_values,
            back_values=back_values,
            fppa_values=fppa_values,
            velocity_values=velocity_values,
            min_confidence_floor=min_confidence_floor,
        )
        rule_state = "abstain" if confidence_floor < min_confidence_floor else "monitor_only"
        abstain_reason = "low_keypoint_visibility" if rule_state == "abstain" else None
        refs = [
            "metric.motion.peak_velocity",
            "metric.motion.angle_extrema",
            "metric.motion.confidence_floor",
            f"layer2.activity.{activity_hint}",
            f"layer2.event.{rule_state}",
            f"video.frame.{end_frame}",
        ]
        tokens = [activity_hint, "motion_event_window", rule_state]
        if "rapid_motion_proxy_high" in flags:
            tokens.append("rapid_motion_proxy_high")
        if flags:
            tokens.append("pose_geometry_caution")

        blocks.append(
            {
                "block_id": f"video.{activity_hint}.block.{offset}.frame_{end_frame}",
                "compression_mode": "sparse_event_block_from_video_pose_samples",
                "time_range_ms": [frame_ms(start_frame, fps), frame_ms(end_frame, fps)],
                "source_frames": [
                    min(frames) if frames else start_frame,
                    max(frames) if frames else end_frame,
                ],
                "tokens": tokens,
                "preserved_extrema": {
                    "knee_angle_min": round(min(knee_values), 3) if knee_values else None,
                    "knee_angle_max": round(max(knee_values), 3) if knee_values else None,
                    "hip_angle_min": round(min(hip_values), 3) if hip_values else None,
                    "hip_angle_max": round(max(hip_values), 3) if hip_values else None,
                    "back_angle_min": round(min(back_values), 3) if back_values else None,
                    "back_angle_max": round(max(back_values), 3) if back_values else None,
                    "max_fppa": round(max(fppa_values), 3) if fppa_values else None,
                    "valgus_ratio_min": round(min(valgus_values), 4) if valgus_values else None,
                    "peak_velocity_deg_s": round(max(velocity_values), 3) if velocity_values else None,
                    "velocity_peak": "high" if max(velocity_values or [0.0]) > 600.0 else "moderate",
                    "confidence_floor": confidence_floor,
                    "geometry_quality_flags": flags,
                },
                "event_score": round(max(0.0, min(0.85, confidence_floor)), 3),
                "rule_policy_state": rule_state,
                "abstain_reason": abstain_reason,
                "evidence_refs": refs,
            }
        )
    return blocks


def build_packet(
    video_path: Path,
    angle_rows: list[dict[str, str]],
    landmark_rows: list[dict[str, str]],
    report: dict[str, Any],
    fps: float,
    sample_every: int,
    blocks: list[dict[str, Any]],
    activity_hint: str,
    window_ms: int,
) -> dict[str, Any]:
    evidence_refs = sorted({
        ref
        for block in blocks
        for ref in block.get("evidence_refs", [])
    })
    output_state = "abstain" if blocks and all(block.get("rule_policy_state") == "abstain" for block in blocks) else "monitor_only"
    low_visibility_blocks = sum(1 for block in blocks if block.get("abstain_reason") == "low_keypoint_visibility")
    return {
        "schema_version": "motion_zip_v4_v1",
        "window_id": f"video.{video_path.stem}.motionzip",
        "trigger": "VIDEO_ANALYSIS_SUMMARY",
        "source_video": str(video_path.resolve()),
        "sampling": {
            "video_frames_total": int(report.get("video_frames_total", 0) or 0),
            "pose_samples": len(angle_rows),
            "sample_every_frames": sample_every,
            "fps": round(fps, 4),
            "compression": f"{len(angle_rows)} pose samples -> {len(blocks)} sparse event blocks",
        },
        "sliding_window": {
            "last_ms": window_ms,
            "frames_kept": len(blocks),
            "reason": "top_motion_information_windows",
        },
        "compressed_sparse_blocks": blocks,
        "heavily_compressed_summary": {
            "activity_hint": activity_hint,
            "completed_reps": None,
            "tempo_band": "mixed_with_high_velocity_events" if blocks else "no_motion_event_blocks",
            "event": output_state,
            "pose_samples": len(angle_rows),
            "landmark_frames": len(landmark_rows),
            "inside_bos_rate": report.get("com", {}).get("inside_rate"),
            "max_angular_velocity_dps": report.get("summaries", {}).get("max_angular_velocity_dps", {}).get("max"),
            "low_visibility_abstain_blocks": low_visibility_blocks,
            "output_state": output_state,
            "model_routing_recommendation": (
                "E2B may summarize this packet; deterministic rules should avoid hard safety verdicts "
                "from sparse single-camera evidence alone."
            ),
        },
        "safety_preserved": [
            "confidence_floor",
            "angle_extrema",
            "velocity_peak",
            "event_boundary",
            "phase_or_primitive_tokens",
            "rule_policy_state",
            "evidence_refs",
            "unsupported_claim_boundaries",
        ],
        "evidence_refs": evidence_refs,
        "limits": UNSUPPORTED_LIMITS,
    }


def build_e2b_prompt(packet: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "motion_zip_e2b_prompt_v1",
        "system": (
            "You are GemmaFit's bounded local evidence reporter. Return exactly one JSON function call. "
            "Use only the provided motion_zip_packet evidence_refs and limits. Do not diagnose, predict fall risk, "
            "estimate force, GRF, joint moments, EMG, muscle activation, heart-rate status, ligament load, or clinical status."
        ),
        "allowed_functions": [
            "create_persona_activity_report",
            "refuse_unsupported_question",
        ],
        "recommended_function": "create_persona_activity_report",
        "input": {
            "trigger": packet.get("trigger"),
            "motion_zip_packet": packet,
            "capability_contract": {
                "can_judge": [
                    "pose_visibility_proxy",
                    "motion_velocity_proxy",
                    "angle_extrema_proxy",
                    "monitor_or_abstain_state",
                ],
                "cannot_judge": [
                    "fall_risk_prediction",
                    "force",
                    "grf",
                    "joint_moment",
                    "emg",
                    "muscle_activation",
                    "heart_rate_status",
                    "medical_diagnosis",
                ],
            },
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Build a MotionZip-V4 packet from a local video.")
    parser.add_argument("--video", required=True, help="Input local video path.")
    parser.add_argument("--activity-hint", default="unknown_motion", help="Bounded activity or primitive hint.")
    parser.add_argument("--label", default="motionzip", help="Intermediate CSV label.")
    parser.add_argument("--sample-every", type=int, default=6, help="MediaPipe sampling interval in frames.")
    parser.add_argument("--window-ms", type=int, default=1600, help="Sparse event window length.")
    parser.add_argument("--max-blocks", type=int, default=3, help="Maximum sparse event blocks.")
    parser.add_argument("--min-event-velocity", type=float, default=300.0, help="Minimum peak velocity to keep an event.")
    parser.add_argument("--min-frame-gap", type=int, default=240, help="Minimum frame gap between selected sparse events.")
    parser.add_argument("--min-confidence-floor", type=float, default=0.55, help="Below this, packet block abstains.")
    parser.add_argument("--out", default="", help="Output packet JSON path.")
    parser.add_argument("--prompt-out", default="", help="Output bounded E2B prompt JSON path.")
    parser.add_argument("--reuse-csv", action="store_true", help="Reuse existing intermediate CSV files when present.")
    args = parser.parse_args()

    video_path = Path(args.video)
    if not video_path.is_absolute():
        video_path = (REPO_ROOT / video_path).resolve()
    if not video_path.exists():
        print(f"[error] video not found: {video_path}", file=sys.stderr)
        return 1

    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    LANDMARK_DIR.mkdir(parents=True, exist_ok=True)
    ANGLE_DIR.mkdir(parents=True, exist_ok=True)

    modules = load_prototype_modules()
    fps = modules["video_fps"](str(video_path))
    landmark_csv, angle_csv, report_json = output_paths(video_path, args.label)

    # Keep legacy prototype helpers writing under prototype/data regardless of caller cwd.
    old_cwd = Path.cwd()
    os.chdir(PROTOTYPE_DIR)
    try:
        if not args.reuse_csv or not landmark_csv.exists():
            print("[1/4] Extracting MediaPipe landmarks...")
            modules["extract_from_video"](str(video_path), args.label, str(landmark_csv.relative_to(PROTOTYPE_DIR)), args.sample_every)
        else:
            print(f"[1/4] Reusing landmarks: {landmark_csv}")

        if not args.reuse_csv or not angle_csv.exists():
            print("[2/4] Computing angles and velocity proxies...")
            modules["process_csv"](str(landmark_csv.relative_to(PROTOTYPE_DIR)), str(angle_csv.relative_to(PROTOTYPE_DIR)), fps=fps)
        else:
            print(f"[2/4] Reusing angles: {angle_csv}")

        print("[3/4] Building formula report...")
        report = modules["build_report"](
            str(video_path),
            str(landmark_csv.relative_to(PROTOTYPE_DIR)),
            str(angle_csv.relative_to(PROTOTYPE_DIR)),
            fps,
            args.sample_every,
        )
        # Add this because validate_formula_video does not currently include it.
        try:
            import cv2

            cap = cv2.VideoCapture(str(video_path))
            report["video_frames_total"] = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) if cap.isOpened() else 0
            cap.release()
        except Exception:
            report["video_frames_total"] = 0
        report_json.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    finally:
        os.chdir(old_cwd)

    print("[4/4] Building MotionZip packet...")
    angle_rows = read_csv(angle_csv)
    landmark_rows = read_csv(landmark_csv)
    events = select_sparse_events(
        angle_rows=angle_rows,
        max_blocks=args.max_blocks,
        min_velocity=args.min_event_velocity,
        min_frame_gap=args.min_frame_gap,
    )
    blocks = build_sparse_blocks(
        angle_rows=angle_rows,
        landmark_rows=landmark_rows,
        events=events,
        fps=fps,
        window_ms=args.window_ms,
        activity_hint=args.activity_hint,
        min_confidence_floor=args.min_confidence_floor,
    )
    packet = build_packet(
        video_path=video_path,
        angle_rows=angle_rows,
        landmark_rows=landmark_rows,
        report=report,
        fps=fps,
        sample_every=args.sample_every,
        blocks=blocks,
        activity_hint=args.activity_hint,
        window_ms=args.window_ms,
    )

    output_path = Path(args.out) if args.out else RESULT_DIR / f"{video_path.stem}_{args.label}_motionzip_packet.json"
    if not output_path.is_absolute():
        output_path = (REPO_ROOT / output_path).resolve()
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(packet, ensure_ascii=False, indent=2), encoding="utf-8")

    prompt_path = Path(args.prompt_out) if args.prompt_out else output_path.with_name(f"{output_path.stem}_e2b_prompt.json")
    if not prompt_path.is_absolute():
        prompt_path = (REPO_ROOT / prompt_path).resolve()
    prompt_path.write_text(json.dumps(build_e2b_prompt(packet), ensure_ascii=False, indent=2), encoding="utf-8")

    summary = packet["heavily_compressed_summary"]
    print(f"packet: {output_path}")
    print(f"prompt: {prompt_path}")
    print(
        "summary:",
        json.dumps(
            {
                "pose_samples": summary["pose_samples"],
                "blocks": len(blocks),
                "output_state": summary["output_state"],
                "low_visibility_abstain_blocks": summary["low_visibility_abstain_blocks"],
                "max_angular_velocity_dps": summary["max_angular_velocity_dps"],
            },
            ensure_ascii=False,
        ),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
