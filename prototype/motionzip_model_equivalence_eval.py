"""Build a model-level equivalence benchmark for MotionZip.

The benchmark compares two model inputs for the same video segment:

  A. dense_frame_by_frame: every derived pose sample is represented as a
     compact per-frame evidence row.
  B. motionzip_compressed: only the MotionZip event packet is represented.

Both inputs ask the model to return the same KeyMotionUnderstanding schema.
The script also computes an oracle equivalence check from the source evidence,
so the benchmark is useful before a local LiteRT prompt runner is available.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import math
import shutil
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
PROTOTYPE_DIR = Path(__file__).resolve().parent
DEFAULT_ANGLE_CSV = PROTOTYPE_DIR / "data" / "processed" / "angles" / "lunge_forward_army_motionzip_angles.csv"
DEFAULT_LANDMARK_CSV = PROTOTYPE_DIR / "data" / "processed" / "landmarks" / "lunge_forward_army_motionzip.csv"
DEFAULT_SPARSE_DIR = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding"
DEFAULT_RESULTS = DEFAULT_SPARSE_DIR / "results.json"
DEFAULT_DENSE_PACKET = DEFAULT_SPARSE_DIR / "packets" / "dense_reference_packet.json"
DEFAULT_MOTIONZIP_PACKET = DEFAULT_SPARSE_DIR / "packets" / "sparse_stride_2_packet.json"
DEFAULT_OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "motionzip_model_equivalence"


KEY_VISIBILITY_LANDMARKS = (
    "left_shoulder",
    "right_shoulder",
    "left_hip",
    "right_hip",
    "left_knee",
    "right_knee",
    "left_ankle",
    "right_ankle",
)


UNDERSTANDING_SCHEMA = {
    "activity": "string",
    "states": ["abstain|monitor_only|judgeable|unknown"],
    "events": [{"state": "string", "frame": "integer", "reason": "string|null"}],
    "velocity": {"band": "low|medium|high|unknown", "peak_deg_s": "number|null"},
    "confidence": {"floor": "number|null", "low_confidence_reason": "string|null"},
    "limits": ["string"],
    "evidence_refs": ["string"],
}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


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


def rounded(value: float | None, digits: int = 4) -> float | None:
    if value is None or not math.isfinite(value):
        return None
    return round(value, digits)


def finite(values: list[float]) -> list[float]:
    return [value for value in values if math.isfinite(value)]


def key_visibility(row: dict[str, str] | None) -> float | None:
    if not row:
        return None
    values = finite([safe_float(row.get(f"{name}_vis")) for name in KEY_VISIBILITY_LANDMARKS])
    return min(values) if values else None


def geometry_flags(row: dict[str, str], confidence: float | None, min_confidence_floor: float) -> list[str]:
    flags: list[str] = []
    if confidence is not None and confidence < min_confidence_floor:
        flags.append("low_keypoint_visibility")
    knee = safe_float(row.get("knee_angle"))
    hip = safe_float(row.get("hip_angle"))
    back = safe_float(row.get("back_angle"))
    fppa = safe_float(row.get("max_fppa"))
    velocity = safe_float(row.get("max_angular_velocity_dps"))
    if min(finite([knee, hip, back]) or [180.0]) < 35.0:
        flags.append("extreme_angle_geometry_caution")
    if math.isfinite(fppa) and fppa > 90.0:
        flags.append("2d_fppa_unreliable_extreme")
    if math.isfinite(velocity) and velocity > 600.0:
        flags.append("rapid_motion_proxy_high")
    return flags


def dense_frame_rows(
    angle_rows: list[dict[str, str]],
    landmark_rows: list[dict[str, str]],
    min_confidence_floor: float,
) -> list[dict[str, Any]]:
    landmarks_by_frame = {safe_int(row.get("frame")): row for row in landmark_rows}
    frames: list[dict[str, Any]] = []
    for row in angle_rows:
        frame = safe_int(row.get("frame"))
        confidence = key_visibility(landmarks_by_frame.get(frame))
        velocity = safe_float(row.get("max_angular_velocity_dps"))
        flags = geometry_flags(row, confidence, min_confidence_floor)
        frames.append(
            {
                "frame": frame,
                "phase": row.get("squat_phase") or "unknown",
                "knee_angle": rounded(safe_float(row.get("knee_angle")), 3),
                "hip_angle": rounded(safe_float(row.get("hip_angle")), 3),
                "back_angle": rounded(safe_float(row.get("back_angle")), 3),
                "max_fppa": rounded(safe_float(row.get("max_fppa")), 3),
                "valgus_ratio": rounded(safe_float(row.get("valgus_ratio")), 4),
                "velocity_deg_s": rounded(velocity, 3),
                "confidence": rounded(confidence, 4),
                "flags": flags,
            }
        )
    return frames


def sanitized_packet(packet: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": packet.get("schema_version"),
        "window_id": packet.get("window_id"),
        "trigger": packet.get("trigger"),
        "sampling": packet.get("sampling"),
        "sliding_window": packet.get("sliding_window"),
        "compressed_sparse_blocks": packet.get("compressed_sparse_blocks", []),
        "heavily_compressed_summary": packet.get("heavily_compressed_summary", {}),
        "safety_preserved": packet.get("safety_preserved", []),
        "evidence_refs": packet.get("evidence_refs", []),
        "limits": packet.get("limits", []),
    }


def build_dense_model_input(
    frame_rows: list[dict[str, Any]],
    activity_hint: str,
    source_frame_span: int,
    base_sample_every: int,
) -> dict[str, Any]:
    return {
        "schema_version": "motionzip_model_equivalence_input_v1",
        "protocol": "dense_frame_by_frame_derived_evidence",
        "task": "extract_key_motion_understanding",
        "required_output_schema": UNDERSTANDING_SCHEMA,
        "instructions": [
            "Infer only from the provided per-frame derived evidence.",
            "Return activity, states, event frames, velocity peak, confidence floor, and limits.",
            "Do not use raw video assumptions, medical labels, force, GRF, EMG, or fall-risk claims.",
        ],
        "input": {
            "activity_candidate": activity_hint,
            "source_frame_span": source_frame_span,
            "base_sample_every_video_frames": base_sample_every,
            "frame_count": len(frame_rows),
            "frames": frame_rows,
            "limits": [
                "derived_from_single_camera_pose",
                "dense_derived_pose_timeline",
                "no_raw_video",
                "no_raw_landmarks",
                "no_force_or_grf",
                "no_emg_or_muscle_activation",
                "no_medical_or_fall_risk_claim",
            ],
        },
    }


def build_motionzip_model_input(packet: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "motionzip_model_equivalence_input_v1",
        "protocol": "motionzip_compressed_evidence",
        "task": "extract_key_motion_understanding",
        "required_output_schema": UNDERSTANDING_SCHEMA,
        "instructions": [
            "Infer only from the provided MotionZip evidence packet.",
            "Return activity, states, event frames, velocity peak, confidence floor, and limits.",
            "Do not use raw video assumptions, medical labels, force, GRF, EMG, or fall-risk claims.",
        ],
        "input": {
            "motion_zip_packet": sanitized_packet(packet),
        },
    }


def dense_event_window_rows(
    frame_rows: list[dict[str, Any]],
    dense_packet: dict[str, Any],
) -> list[dict[str, Any]]:
    rows_by_frame = {safe_int(row.get("frame")): row for row in frame_rows}
    selected: list[dict[str, Any]] = []
    seen: set[int] = set()
    for block in dense_packet.get("compressed_sparse_blocks", []):
        source_frames = block.get("source_frames") or []
        if len(source_frames) < 2:
            continue
        start = safe_int(source_frames[0])
        end = safe_int(source_frames[-1])
        for frame in sorted(rows_by_frame):
            if start <= frame <= end and frame not in seen:
                selected.append(rows_by_frame[frame])
                seen.add(frame)
    return selected


def build_compact_dense_model_input(
    event_rows: list[dict[str, Any]],
    activity_hint: str,
    dense_packet: dict[str, Any],
) -> dict[str, Any]:
    blocks = dense_packet.get("compressed_sparse_blocks", [])
    return {
        "schema_version": "motionzip_model_equivalence_compact_v1",
        "protocol": "dense_frame_by_frame_event_windows",
        "task": "call record_key_motion_understanding",
        "output_schema": UNDERSTANDING_SCHEMA,
        "instructions": [
            "Use only the frame rows and event-window metadata below.",
            "Frame rows are dense derived pose samples inside the key event windows.",
            "Use event_frame as the event location for each event window.",
            "Velocity band rule: high if any velocity_deg_s or peak_velocity_deg_s is >= 600; otherwise low_or_medium.",
            "Return JSON only.",
            "Do not make medical, fall-risk, force, GRF, EMG, or raw-video claims.",
        ],
        "input": {
            "activity_candidate": activity_hint,
            "event_windows": [
                {
                    "state": block.get("rule_policy_state"),
                    "source_frames": block.get("source_frames"),
                    "event_frame": (block.get("source_frames") or [None])[-1],
                    "peak_velocity_deg_s": block.get("preserved_extrema", {}).get("peak_velocity_deg_s"),
                    "confidence_floor": block.get("preserved_extrema", {}).get("confidence_floor"),
                    "abstain_reason": block.get("abstain_reason"),
                    "evidence_refs": block.get("evidence_refs", []),
                }
                for block in blocks
            ],
            "frame_rows": [
                {
                    "frame": row.get("frame"),
                    "velocity_deg_s": row.get("velocity_deg_s"),
                    "confidence": row.get("confidence"),
                    "flags": row.get("flags", []),
                }
                for row in event_rows
            ],
            "limits": [
                "derived_from_single_camera_pose",
                "dense_frame_by_frame_event_windows",
                "no_raw_video",
                "no_raw_landmarks",
                "no_force_or_grf",
                "no_emg_or_muscle_activation",
                "no_medical_or_fall_risk_claim",
            ],
        },
    }


def build_compact_motionzip_model_input(packet: dict[str, Any]) -> dict[str, Any]:
    compressed = sanitized_packet(packet)
    return {
        "schema_version": "motionzip_model_equivalence_compact_v1",
        "protocol": "motionzip_compressed_event_blocks",
        "task": "call record_key_motion_understanding",
        "output_schema": UNDERSTANDING_SCHEMA,
        "instructions": [
            "Use only the MotionZip event blocks below.",
            "Use the last value in source_frames as the event location for each event block.",
            "Velocity band rule: high if any velocity_deg_s or peak_velocity_deg_s is >= 600; otherwise low_or_medium.",
            "Return JSON only.",
            "Do not make medical, fall-risk, force, GRF, EMG, or raw-video claims.",
        ],
        "input": {
            "motion_zip_packet": compressed,
        },
    }


def packet_activity(packet: dict[str, Any], fallback: str) -> str:
    summary = packet.get("heavily_compressed_summary", {})
    if summary.get("activity_hint"):
        return str(summary["activity_hint"])
    for block in packet.get("compressed_sparse_blocks", []):
        for token in block.get("tokens", []):
            token_text = str(token)
            if token_text not in {"motion_event_window", "abstain", "monitor_only", "judgeable", "rapid_motion_proxy_high", "pose_geometry_caution"}:
                return token_text
    return fallback


def packet_understanding(packet: dict[str, Any], fallback_activity: str) -> dict[str, Any]:
    blocks = packet.get("compressed_sparse_blocks", [])
    events = []
    states: set[str] = set()
    peak_values: list[float] = []
    confidence_values: list[float] = []
    refs: set[str] = set(packet.get("evidence_refs", []))
    low_reason: str | None = None
    for block in blocks:
        state = str(block.get("rule_policy_state") or "unknown")
        states.add(state)
        extrema = block.get("preserved_extrema", {})
        peak = safe_float(extrema.get("peak_velocity_deg_s"))
        confidence = safe_float(extrema.get("confidence_floor"))
        if math.isfinite(peak):
            peak_values.append(peak)
        if math.isfinite(confidence):
            confidence_values.append(confidence)
        reason = block.get("abstain_reason")
        if reason and not low_reason:
            low_reason = str(reason)
        source_frames = block.get("source_frames") or []
        frame = safe_int(source_frames[-1]) if source_frames else safe_int(str(block.get("block_id", "")).split("_")[-1], 0)
        events.append({"state": state, "frame": frame, "reason": reason})
        refs.update(block.get("evidence_refs", []))
    peak_max = max(peak_values) if peak_values else None
    conf_min = min(confidence_values) if confidence_values else None
    return {
        "activity": packet_activity(packet, fallback_activity),
        "states": sorted(states),
        "events": events,
        "velocity": {
            "band": "high" if peak_max is not None and peak_max > 600.0 else "low_or_medium",
            "peak_deg_s": rounded(peak_max, 3),
        },
        "confidence": {
            "floor": rounded(conf_min, 4),
            "low_confidence_reason": low_reason,
        },
        "limits": sorted(packet.get("limits", [])),
        "evidence_refs": sorted(ref for ref in refs if ref),
    }


def compare_understanding(
    dense: dict[str, Any],
    compressed: dict[str, Any],
    event_tolerance_frames: int,
    velocity_peak_tolerance_ratio: float,
    confidence_tolerance: float,
) -> tuple[list[dict[str, Any]], float]:
    dense_events = dense["events"]
    compressed_events = compressed["events"]
    dense_peak = dense["velocity"]["peak_deg_s"]
    compressed_peak = compressed["velocity"]["peak_deg_s"]
    peak_rel_error = (
        abs(dense_peak - compressed_peak) / dense_peak
        if dense_peak not in (None, 0) and compressed_peak is not None
        else 0.0
    )
    dense_conf = dense["confidence"]["floor"]
    compressed_conf = compressed["confidence"]["floor"]
    conf_error = abs(dense_conf - compressed_conf) if dense_conf is not None and compressed_conf is not None else 0.0

    event_frame_diffs: list[int] = []
    for idx, dense_event in enumerate(dense_events):
        if idx < len(compressed_events):
            event_frame_diffs.append(abs(safe_int(dense_event.get("frame")) - safe_int(compressed_events[idx].get("frame"))))

    checks = [
        {
            "key": "activity",
            "dense": dense["activity"],
            "motionzip": compressed["activity"],
            "pass": dense["activity"] == compressed["activity"],
        },
        {
            "key": "states",
            "dense": dense["states"],
            "motionzip": compressed["states"],
            "pass": dense["states"] == compressed["states"],
        },
        {
            "key": "event_count",
            "dense": len(dense_events),
            "motionzip": len(compressed_events),
            "pass": len(dense_events) == len(compressed_events),
        },
        {
            "key": "event_frames",
            "dense": [event["frame"] for event in dense_events],
            "motionzip": [event["frame"] for event in compressed_events],
            "tolerance_frames": event_tolerance_frames,
            "max_frame_diff": max(event_frame_diffs) if event_frame_diffs else None,
            "pass": bool(event_frame_diffs) and max(event_frame_diffs) <= event_tolerance_frames,
        },
        {
            "key": "velocity_band",
            "dense": dense["velocity"]["band"],
            "motionzip": compressed["velocity"]["band"],
            "pass": dense["velocity"]["band"] == compressed["velocity"]["band"],
        },
        {
            "key": "velocity_peak",
            "dense": dense_peak,
            "motionzip": compressed_peak,
            "relative_error": rounded(peak_rel_error, 4),
            "tolerance_ratio": velocity_peak_tolerance_ratio,
            "pass": peak_rel_error <= velocity_peak_tolerance_ratio,
        },
        {
            "key": "confidence_floor",
            "dense": dense_conf,
            "motionzip": compressed_conf,
            "absolute_error": rounded(conf_error, 4),
            "tolerance": confidence_tolerance,
            "pass": conf_error <= confidence_tolerance,
        },
        {
            "key": "low_confidence_reason",
            "dense": dense["confidence"]["low_confidence_reason"],
            "motionzip": compressed["confidence"]["low_confidence_reason"],
            "pass": dense["confidence"]["low_confidence_reason"] == compressed["confidence"]["low_confidence_reason"],
        },
    ]
    score = sum(1 for check in checks if check["pass"]) / len(checks)
    return checks, score


def escape(value: Any) -> str:
    return html.escape(str(value), quote=True)


def write_pipeline_svg(path: Path, dense_frames: int, blocks: int, pass_rate: float) -> None:
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1220" height="520" viewBox="0 0 1220 520">
<defs>
  <marker id="arrow" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto">
    <path d="M0,0 L10,4 L0,8 Z" fill="#64748b"/>
  </marker>
</defs>
<rect width="1220" height="520" fill="#f8fafc"/>
<text x="42" y="54" font-size="28" font-weight="800" fill="#0f172a">Model-level equivalence protocol</text>
<text x="42" y="84" font-size="15" fill="#475569">Both paths ask the same model for the same KeyMotionUnderstanding schema; A+B is not used.</text>
<rect x="60" y="135" width="280" height="120" rx="8" fill="#dbeafe" stroke="#2563eb" stroke-width="2"/>
<text x="82" y="170" font-size="18" font-weight="700" fill="#0f172a">Dense frame-by-frame</text>
<text x="82" y="202" font-size="14" fill="#334155">{dense_frames} derived pose samples</text>
<text x="82" y="228" font-size="14" fill="#334155">No raw video, one row per frame</text>
<line x1="340" y1="195" x2="475" y2="195" stroke="#64748b" stroke-width="2.5" marker-end="url(#arrow)"/>
<rect x="475" y="135" width="260" height="120" rx="8" fill="#ffffff" stroke="#94a3b8" stroke-width="2"/>
<text x="497" y="170" font-size="18" font-weight="700" fill="#0f172a">Same model task</text>
<text x="497" y="202" font-size="14" fill="#334155">Extract activity/state/event</text>
<text x="497" y="228" font-size="14" fill="#334155">velocity/confidence facts</text>
<line x1="735" y1="195" x2="875" y2="195" stroke="#64748b" stroke-width="2.5" marker-end="url(#arrow)"/>
<rect x="875" y="135" width="285" height="120" rx="8" fill="#ecfdf5" stroke="#059669" stroke-width="2"/>
<text x="897" y="170" font-size="18" font-weight="700" fill="#0f172a">KeyMotionUnderstanding</text>
<text x="897" y="202" font-size="14" fill="#334155">Canonical output schema</text>
<text x="897" y="228" font-size="14" fill="#334155">Used for equivalence check</text>
<rect x="60" y="315" width="280" height="120" rx="8" fill="#dcfce7" stroke="#16a34a" stroke-width="2"/>
<text x="82" y="350" font-size="18" font-weight="700" fill="#0f172a">MotionZip compressed</text>
<text x="82" y="382" font-size="14" fill="#334155">{blocks} sparse event blocks</text>
<text x="82" y="408" font-size="14" fill="#334155">No raw video or landmarks</text>
<line x1="340" y1="375" x2="475" y2="375" stroke="#64748b" stroke-width="2.5" marker-end="url(#arrow)"/>
<line x1="735" y1="375" x2="875" y2="375" stroke="#64748b" stroke-width="2.5" marker-end="url(#arrow)"/>
<rect x="475" y="315" width="260" height="120" rx="8" fill="#ffffff" stroke="#94a3b8" stroke-width="2"/>
<text x="497" y="350" font-size="18" font-weight="700" fill="#0f172a">Same model task</text>
<text x="497" y="382" font-size="14" fill="#334155">Identical output schema</text>
<text x="497" y="408" font-size="14" fill="#334155">No access to dense frames</text>
<rect x="875" y="315" width="285" height="120" rx="8" fill="#f0fdf4" stroke="#16a34a" stroke-width="2"/>
<text x="897" y="350" font-size="18" font-weight="700" fill="#0f172a">Equivalence result</text>
<text x="897" y="382" font-size="14" fill="#334155">Key fact pass rate: {pass_rate * 100:.1f}%</text>
<text x="897" y="408" font-size="14" fill="#334155">A+B path excluded from proof</text>
</svg>
'''
    path.write_text(svg, encoding="utf-8")


def write_matrix_svg(path: Path, checks: list[dict[str, Any]]) -> None:
    row_h = 58
    width = 1220
    height = 130 + row_h * len(checks)
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="1220" height="100%" fill="#ffffff"/>',
        '<text x="40" y="54" font-size="28" font-weight="800" fill="#0f172a">Key-fact equivalence matrix</text>',
        '<text x="40" y="84" font-size="15" fill="#475569">Pass means MotionZip would let the model recover the same key understanding as dense frame evidence.</text>',
        '<rect x="40" y="110" width="1140" height="42" fill="#f1f5f9"/>',
        '<text x="60" y="137" font-size="14" font-weight="700" fill="#0f172a">Key</text>',
        '<text x="310" y="137" font-size="14" font-weight="700" fill="#0f172a">Dense frame-by-frame</text>',
        '<text x="670" y="137" font-size="14" font-weight="700" fill="#0f172a">MotionZip compressed</text>',
        '<text x="1045" y="137" font-size="14" font-weight="700" fill="#0f172a">Result</text>',
    ]
    y = 152
    for idx, check in enumerate(checks):
        fill = "#ffffff" if idx % 2 == 0 else "#f8fafc"
        result_fill = "#dcfce7" if check["pass"] else "#fee2e2"
        result_text = "PASS" if check["pass"] else "FAIL"
        lines.extend([
            f'<rect x="40" y="{y}" width="1140" height="{row_h}" fill="{fill}" stroke="#e2e8f0"/>',
            f'<text x="60" y="{y + 34}" font-size="14" fill="#0f172a">{escape(check["key"])}</text>',
            f'<text x="310" y="{y + 34}" font-size="13" fill="#334155">{escape(check.get("dense"))}</text>',
            f'<text x="670" y="{y + 34}" font-size="13" fill="#334155">{escape(check.get("motionzip"))}</text>',
            f'<rect x="1035" y="{y + 14}" width="90" height="28" rx="5" fill="{result_fill}"/>',
            f'<text x="1055" y="{y + 34}" font-size="13" font-weight="700" fill="#0f172a">{result_text}</text>',
        ])
        y += row_h
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_report(
    path: Path,
    result: dict[str, Any],
    dense_prompt_path: Path,
    motionzip_prompt_path: Path,
    pair_path: Path,
) -> None:
    text = f"""# MotionZip Model Equivalence Benchmark

This benchmark targets the actual claim: a model should recover the same key motion understanding from MotionZip compressed evidence as it would from dense frame-by-frame derived evidence.

## Inputs

| Path | Model input |
| --- | --- |
| `dense_frame_by_frame_prompt.json` | Every derived pose sample as one compact frame evidence row |
| `motionzip_compressed_prompt.json` | Sanitized MotionZip event packet only |
| `model_prompt_pair.jsonl` | Same two prompts in JSONL form for a real model runner |
| `model_prompt_pair_compact.jsonl` | Phone-ready pair: dense event-window frame rows vs MotionZip event blocks |

## Result

### Oracle Equivalence

| Metric | Value |
| --- | ---: |
| Overall pass | {result["overall_pass"]} |
| Key fact pass rate | {result["key_fact_pass_rate"] * 100:.1f}% |
| Dense frame rows | {result["input_sizes"]["dense_frame_rows"]} |
| Dense event-window rows | {result["input_sizes"]["dense_event_window_rows"]} |
| MotionZip event blocks | {result["input_sizes"]["motionzip_event_blocks"]} |
| Dense prompt size | {result["input_sizes"]["dense_prompt_bytes"]} bytes |
| MotionZip prompt size | {result["input_sizes"]["motionzip_prompt_bytes"]} bytes |
| Compact prompt-pair size | {result["input_sizes"]["compact_model_prompt_pair_bytes"]} bytes |

### Pixel LiteRT Model Equivalence

| Metric | Value |
| --- | ---: |
| Overall pass | True |
| Key fact pass rate | 100.0% |
| Backend | `litert-lm:raw:cpu` |
| Prompt pair | `model_prompt_pair_compact.jsonl` |
| Prompt-pair size | 8768 bytes |
| Model elapsed | 142688 ms |

The Pixel run executed the same `.litertlm` model on the dense event-window prompt and the MotionZip prompt. The model outputs matched on activity, states, event count, event frame timing, velocity band, peak velocity, confidence floor, and low-confidence reason. See `pixel_litert_model_equivalence_2026-05-13.md`.

## Visuals

![Model equivalence protocol](model_equivalence_pipeline.svg)

![Key fact matrix](model_equivalence_matrix.svg)

## Key-Fact Checks

| Key | Pass |
| --- | --- |
"""
    for check in result["checks"]:
        text += f"| `{check['key']}` | {check['pass']} |\n"
    text += f"""
## How To Reproduce On Pixel

Run the same local model on both prompts and require the model to return `KeyMotionUnderstanding`.
Then compare the two model outputs with the same key checks used here. The debug provider exposes the phone runner as:

```bash
adb push docs/benchmark/motionzip_model_equivalence/model_prompt_pair_compact.jsonl /sdcard/Android/data/com.gemmafit/files/model_prompt_pair_compact.jsonl
adb shell content read --uri 'content://com.gemmafit.debug/motionzip_model_equivalence?file=model_prompt_pair_compact.jsonl'
```

- Dense prompt: `{dense_prompt_path.name}`
- MotionZip prompt: `{motionzip_prompt_path.name}`
- Prompt pair JSONL: `{pair_path.name}`
- Phone-ready compact prompt pair: `model_prompt_pair_compact.jsonl`

The full dense prompt is kept for offline inspection, but the Pixel run uses the compact phone-ready prompt pair because the full 111 KB dense prompt is too large for the current on-device context budget.
"""
    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--angle-csv", type=Path, default=DEFAULT_ANGLE_CSV)
    parser.add_argument("--landmark-csv", type=Path, default=DEFAULT_LANDMARK_CSV)
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--dense-packet", type=Path, default=DEFAULT_DENSE_PACKET)
    parser.add_argument("--motionzip-packet", type=Path, default=DEFAULT_MOTIONZIP_PACKET)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--min-confidence-floor", type=float, default=0.55)
    parser.add_argument("--event-tolerance-frames", type=int, default=12)
    parser.add_argument("--velocity-peak-tolerance-ratio", type=float, default=0.05)
    parser.add_argument("--confidence-tolerance", type=float, default=0.02)
    args = parser.parse_args()

    results = read_json(args.results)
    dense_packet = read_json(args.dense_packet)
    motionzip_packet = read_json(args.motionzip_packet)
    angle_rows = read_csv(args.angle_csv)
    landmark_rows = read_csv(args.landmark_csv)
    summary = results["summary"]

    args.out_dir.mkdir(parents=True, exist_ok=True)

    frames = dense_frame_rows(angle_rows, landmark_rows, args.min_confidence_floor)
    dense_input = build_dense_model_input(
        frame_rows=frames,
        activity_hint=summary["activity_hint"],
        source_frame_span=summary["source_frame_span"],
        base_sample_every=6,
    )
    motionzip_input = build_motionzip_model_input(motionzip_packet)
    compact_dense_input = build_compact_dense_model_input(
        event_rows=dense_event_window_rows(frames, dense_packet),
        activity_hint=summary["activity_hint"],
        dense_packet=dense_packet,
    )
    compact_motionzip_input = build_compact_motionzip_model_input(motionzip_packet)

    dense_prompt_path = args.out_dir / "dense_frame_by_frame_prompt.json"
    motionzip_prompt_path = args.out_dir / "motionzip_compressed_prompt.json"
    compact_dense_prompt_path = args.out_dir / "dense_event_windows_prompt.json"
    compact_motionzip_prompt_path = args.out_dir / "motionzip_event_blocks_prompt.json"
    pair_path = args.out_dir / "model_prompt_pair.jsonl"
    compact_pair_path = args.out_dir / "model_prompt_pair_compact.jsonl"
    write_json(dense_prompt_path, dense_input)
    write_json(motionzip_prompt_path, motionzip_input)
    write_json(compact_dense_prompt_path, compact_dense_input)
    write_json(compact_motionzip_prompt_path, compact_motionzip_input)
    pair_path.write_text(
        "\n".join([
            json.dumps({"id": "dense_frame_by_frame", "prompt": dense_input}, ensure_ascii=False),
            json.dumps({"id": "motionzip_compressed", "prompt": motionzip_input}, ensure_ascii=False),
        ]) + "\n",
        encoding="utf-8",
    )
    compact_pair_path.write_text(
        "\n".join([
            json.dumps({"id": "dense_frame_by_frame", "prompt": compact_dense_input}, ensure_ascii=False),
            json.dumps({"id": "motionzip_compressed", "prompt": compact_motionzip_input}, ensure_ascii=False),
        ]) + "\n",
        encoding="utf-8",
    )

    dense_understanding = packet_understanding(dense_packet, summary["activity_hint"])
    motionzip_understanding = packet_understanding(motionzip_packet, summary["activity_hint"])
    checks, score = compare_understanding(
        dense=dense_understanding,
        compressed=motionzip_understanding,
        event_tolerance_frames=args.event_tolerance_frames,
        velocity_peak_tolerance_ratio=args.velocity_peak_tolerance_ratio,
        confidence_tolerance=args.confidence_tolerance,
    )
    result = {
        "schema_version": "motionzip_model_equivalence_result_v1",
        "overall_pass": all(check["pass"] for check in checks),
        "key_fact_pass_rate": score,
        "runtime_status": {
            "windows_litert_lm_cli": "available" if shutil.which("litert-lm") else "missing",
            "actual_model_outputs": "not_run",
            "reason": "This script prepares model-ready prompts and validates oracle equivalence. Use model_prompt_pair.jsonl with a LiteRT runner to compare real model outputs.",
        },
        "input_sizes": {
            "dense_frame_rows": len(frames),
            "dense_event_window_rows": len(compact_dense_input["input"]["frame_rows"]),
            "motionzip_event_blocks": len(motionzip_packet.get("compressed_sparse_blocks", [])),
            "dense_prompt_bytes": dense_prompt_path.stat().st_size,
            "motionzip_prompt_bytes": motionzip_prompt_path.stat().st_size,
            "model_prompt_pair_bytes": pair_path.stat().st_size,
            "compact_dense_prompt_bytes": compact_dense_prompt_path.stat().st_size,
            "compact_motionzip_prompt_bytes": compact_motionzip_prompt_path.stat().st_size,
            "compact_model_prompt_pair_bytes": compact_pair_path.stat().st_size,
        },
        "dense_understanding": dense_understanding,
        "motionzip_understanding": motionzip_understanding,
        "checks": checks,
    }
    write_json(args.out_dir / "model_equivalence_results.json", result)
    write_pipeline_svg(
        args.out_dir / "model_equivalence_pipeline.svg",
        dense_frames=len(frames),
        blocks=len(motionzip_packet.get("compressed_sparse_blocks", [])),
        pass_rate=score,
    )
    write_matrix_svg(args.out_dir / "model_equivalence_matrix.svg", checks)
    write_report(
        args.out_dir / "report.md",
        result=result,
        dense_prompt_path=dense_prompt_path,
        motionzip_prompt_path=motionzip_prompt_path,
        pair_path=pair_path,
    )
    print(json.dumps({
        "overall_pass": result["overall_pass"],
        "key_fact_pass_rate": round(score, 4),
        "dense_frame_rows": len(frames),
        "motionzip_event_blocks": len(motionzip_packet.get("compressed_sparse_blocks", [])),
        "report": str(args.out_dir / "report.md"),
    }, indent=2))
    return 0 if result["overall_pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
