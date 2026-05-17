"""Evaluate MotionZip packets and generate visual benchmark artifacts.

This script is intentionally dependency-light. It reads existing MotionZip JSON
packets and current E2B metric files, checks the safety-preserving packet
contract, and writes:

  docs/benchmark/motionzip_effectiveness/results.json
  docs/benchmark/motionzip_effectiveness/report.md
  docs/benchmark/motionzip_effectiveness/*.svg

It does not run a model and does not inspect raw video frames.
"""

from __future__ import annotations

import argparse
import html
import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PACKET_GLOB = "prototype/data/validation/results/*motionzip_packet.json"
DEFAULT_OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "motionzip_effectiveness"


REQUIRED_TOP_LEVEL = [
    "schema_version",
    "window_id",
    "trigger",
    "sampling",
    "sliding_window",
    "compressed_sparse_blocks",
    "heavily_compressed_summary",
    "safety_preserved",
    "evidence_refs",
    "limits",
]

REQUIRED_SAFETY = [
    "confidence_floor",
    "angle_extrema",
    "velocity_peak",
    "event_boundary",
    "rule_policy_state",
    "evidence_refs",
    "unsupported_claim_boundaries",
]

REQUIRED_BLOCK = [
    "block_id",
    "time_range_ms",
    "tokens",
    "preserved_extrema",
    "rule_policy_state",
    "evidence_refs",
]

REQUIRED_EXTREMA = [
    "confidence_floor",
    "peak_velocity_deg_s",
]

FORBIDDEN_KEYS = [
    "raw_video",
    "video_frame",
    "frame_pixels",
    "image_crop",
    "landmark_array",
    "pose_landmarks",
    "histogram",
    "reid_embedding",
    "face_embedding",
    "device_id",
    "imei",
    "address",
    "dementia_score",
    "cognitive_decline",
    "fall_risk_prediction",
    "diagnosis",
    "rehabilitation_prescription",
]

ALLOWED_DEBUG_KEYS = {
    "source_video",
    "source_frames",
}


@dataclass
class PacketEval:
    path: Path
    packet: dict[str, Any]
    missing_top_level: list[str]
    missing_safety: list[str]
    missing_block_fields: list[str]
    missing_extrema_fields: list[str]
    forbidden_payload_findings: list[str]
    debug_only_findings: list[str]
    block_count: int
    pose_samples: int
    video_frames_total: int
    packet_bytes: int
    raw_video_bytes: int | None
    state_counts: dict[str, int]
    confidence_floors: list[float]
    evidence_ref_count: int
    compression_ratio_pose_to_blocks: float | None
    compression_ratio_raw_video_to_packet: float | None

    @property
    def passed_contract(self) -> bool:
        return not (
            self.missing_top_level
            or self.missing_safety
            or self.missing_block_fields
            or self.missing_extrema_fields
            or self.forbidden_payload_findings
        )


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def find_packets(pattern: str) -> list[Path]:
    paths = sorted(REPO_ROOT.glob(pattern))
    packets: list[Path] = []
    for path in paths:
        try:
            data = load_json(path)
        except json.JSONDecodeError:
            continue
        if data.get("schema_version") == "motion_zip_v4_v1" and isinstance(data.get("compressed_sparse_blocks"), list):
            packets.append(path)
    return packets


def as_float(value: Any) -> float | None:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def walk_payload(value: Any, path: str = "") -> list[tuple[str, Any]]:
    findings: list[tuple[str, Any]] = []
    if isinstance(value, dict):
        for key, nested in value.items():
            nested_path = f"{path}.{key}" if path else str(key)
            findings.append((nested_path, nested))
            findings.extend(walk_payload(nested, nested_path))
    elif isinstance(value, list):
        for index, nested in enumerate(value):
            nested_path = f"{path}[{index}]"
            findings.extend(walk_payload(nested, nested_path))
    return findings


def scan_forbidden(packet: dict[str, Any]) -> tuple[list[str], list[str]]:
    forbidden: list[str] = []
    debug_only: list[str] = []
    for path, value in walk_payload(packet):
        key = path.split(".")[-1].split("[")[0].lower()
        normalized = key.replace("-", "_")
        if normalized in ALLOWED_DEBUG_KEYS:
            debug_only.append(path)
            continue
        if normalized in FORBIDDEN_KEYS:
            forbidden.append(path)
            continue
        if isinstance(value, str):
            lower = value.lower()
            if any(term in lower for term in FORBIDDEN_KEYS):
                # Evidence refs may include refused claim names; those are safe.
                if ".limits" in path or ".evidence_refs" in path or "cannot_judge" in path:
                    continue
                forbidden.append(path)
    return sorted(set(forbidden)), sorted(set(debug_only))


def evaluate_packet(path: Path) -> PacketEval:
    packet = load_json(path)
    blocks = packet.get("compressed_sparse_blocks", [])
    sampling = packet.get("sampling", {})
    pose_samples = int(sampling.get("pose_samples") or packet.get("heavily_compressed_summary", {}).get("pose_samples") or 0)
    video_frames_total = int(sampling.get("video_frames_total") or 0)
    block_count = len(blocks)
    safety_preserved = set(packet.get("safety_preserved", []))

    missing_top_level = [key for key in REQUIRED_TOP_LEVEL if key not in packet]
    missing_safety = [key for key in REQUIRED_SAFETY if key not in safety_preserved]
    missing_block_fields: list[str] = []
    missing_extrema_fields: list[str] = []
    state_counts: dict[str, int] = {}
    confidence_floors: list[float] = []

    for index, block in enumerate(blocks):
        for key in REQUIRED_BLOCK:
            if key not in block:
                missing_block_fields.append(f"block[{index}].{key}")
        state = str(block.get("rule_policy_state", "unknown"))
        state_counts[state] = state_counts.get(state, 0) + 1
        extrema = block.get("preserved_extrema", {})
        for key in REQUIRED_EXTREMA:
            if key not in extrema:
                missing_extrema_fields.append(f"block[{index}].preserved_extrema.{key}")
        confidence = as_float(extrema.get("confidence_floor"))
        if confidence is not None:
            confidence_floors.append(confidence)

    forbidden, debug_only = scan_forbidden(packet)
    packet_bytes = len(json.dumps(packet, ensure_ascii=False).encode("utf-8"))
    source_video = packet.get("source_video")
    raw_video_bytes: int | None = None
    if isinstance(source_video, str):
        video_path = Path(source_video)
        if video_path.exists():
            raw_video_bytes = video_path.stat().st_size

    return PacketEval(
        path=path,
        packet=packet,
        missing_top_level=missing_top_level,
        missing_safety=missing_safety,
        missing_block_fields=missing_block_fields,
        missing_extrema_fields=missing_extrema_fields,
        forbidden_payload_findings=forbidden,
        debug_only_findings=debug_only,
        block_count=block_count,
        pose_samples=pose_samples,
        video_frames_total=video_frames_total,
        packet_bytes=packet_bytes,
        raw_video_bytes=raw_video_bytes,
        state_counts=state_counts,
        confidence_floors=confidence_floors,
        evidence_ref_count=len(packet.get("evidence_refs", [])),
        compression_ratio_pose_to_blocks=(pose_samples / block_count if block_count else None),
        compression_ratio_raw_video_to_packet=(raw_video_bytes / packet_bytes if raw_video_bytes and packet_bytes else None),
    )


def load_e2b_metrics() -> dict[str, Any]:
    metrics: dict[str, Any] = {}
    tool_path = REPO_ROOT / "finetune" / "metrics" / "tool_call_eval_v5_e2b.json"
    refusal_path = REPO_ROOT / "finetune" / "metrics" / "refusal_eval_v5_e2b.json"
    adversarial_path = REPO_ROOT / "finetune" / "metrics" / "adversarial_eval_v5_e2b.json"

    if tool_path.exists():
        data = load_json(tool_path)
        metrics["tool_call"] = data.get("summary", {})
    if refusal_path.exists():
        metrics["refusal"] = load_json(refusal_path)
    if adversarial_path.exists():
        metrics["adversarial"] = load_json(adversarial_path)
    return metrics


def pct(value: float | int | None) -> str:
    if value is None:
        return "n/a"
    return f"{float(value) * 100:.1f}%"


def ratio(value: float | int | None) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.1f}x"


def make_bar_svg(path: Path, title: str, items: list[tuple[str, float, str]], max_value: float | None = None) -> None:
    width = 920
    row_h = 46
    top = 70
    left = 270
    chart_w = 560
    height = top + row_h * len(items) + 40
    max_v = max_value if max_value is not None else max((value for _, value, _ in items), default=1.0)
    max_v = max(max_v, 1e-6)
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        f'<text x="32" y="38" font-family="Arial, sans-serif" font-size="24" font-weight="700" fill="#0f172a">{html.escape(title)}</text>',
    ]
    for index, (label, value, display) in enumerate(items):
        y = top + index * row_h
        bar_w = int(chart_w * min(value / max_v, 1.0))
        if value > 0 and bar_w < 3:
            bar_w = 3
        fill = "#2563eb" if value >= 0.95 * max_v else "#0f766e"
        if value == 0:
            fill = "#dc2626"
        lines.extend(
            [
                f'<text x="32" y="{y + 24}" font-family="Arial, sans-serif" font-size="15" fill="#334155">{html.escape(label)}</text>',
                f'<rect x="{left}" y="{y + 7}" width="{chart_w}" height="24" rx="4" fill="#e2e8f0"/>',
                f'<rect x="{left}" y="{y + 7}" width="{bar_w}" height="24" rx="4" fill="{fill}"/>',
                f'<text x="{left + chart_w + 16}" y="{y + 25}" font-family="Arial, sans-serif" font-size="15" font-weight="700" fill="#0f172a">{html.escape(display)}</text>',
            ]
        )
    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def make_state_svg(path: Path, state_counts: dict[str, int]) -> None:
    items = [(state, float(count), str(count)) for state, count in sorted(state_counts.items())]
    make_bar_svg(path, "MotionZip Block State Distribution", items, max_value=max((v for _, v, _ in items), default=1.0))


def make_pipeline_svg(path: Path) -> None:
    nodes = [
        "Video",
        "Pose",
        "Derived Features",
        "Layer 2",
        "MotionZip",
        "Scheduler",
        "Gemma E2B",
        "Validator",
    ]
    width = 1100
    height = 170
    box_w = 118
    gap = 18
    x0 = 30
    y = 62
    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        '<text x="30" y="34" font-family="Arial, sans-serif" font-size="23" font-weight="700" fill="#0f172a">MotionZip Position In GemmaFit</text>',
    ]
    for i, node in enumerate(nodes):
        x = x0 + i * (box_w + gap)
        fill = "#dbeafe" if node == "MotionZip" else "#ffffff"
        stroke = "#2563eb" if node == "MotionZip" else "#94a3b8"
        lines.extend(
            [
                f'<rect x="{x}" y="{y}" width="{box_w}" height="52" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="2"/>',
                f'<text x="{x + box_w / 2}" y="{y + 31}" text-anchor="middle" font-family="Arial, sans-serif" font-size="13" font-weight="700" fill="#0f172a">{html.escape(node)}</text>',
            ]
        )
        if i < len(nodes) - 1:
            ax = x + box_w
            bx = x + box_w + gap - 4
            lines.extend(
                [
                    f'<line x1="{ax + 4}" y1="{y + 26}" x2="{bx}" y2="{y + 26}" stroke="#64748b" stroke-width="2"/>',
                    f'<polygon points="{bx},{y + 26} {bx - 8},{y + 21} {bx - 8},{y + 31}" fill="#64748b"/>',
                ]
            )
    lines.extend(
        [
            '<text x="30" y="142" font-family="Arial, sans-serif" font-size="14" fill="#475569">Gemma does not receive raw video. MotionZip sends compact evidence: event blocks, extrema, confidence floors, limits, and evidence refs.</text>',
            "</svg>",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")


def generate_visuals(out_dir: Path, evals: list[PacketEval], e2b: dict[str, Any]) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    make_pipeline_svg(out_dir / "motionzip_pipeline.svg")
    total_pose = sum(item.pose_samples for item in evals)
    total_blocks = sum(item.block_count for item in evals)
    total_frames = sum(item.video_frames_total for item in evals)
    make_bar_svg(
        out_dir / "compression_overview.svg",
        "MotionZip Compression Overview",
        [
            ("Raw video frames", float(total_frames), str(total_frames)),
            ("Pose samples", float(total_pose), str(total_pose)),
            ("Sparse event blocks", float(total_blocks), str(total_blocks)),
        ],
        max_value=float(max(total_frames, total_pose, total_blocks, 1)),
    )
    state_counts: dict[str, int] = {}
    for item in evals:
        for state, count in item.state_counts.items():
            state_counts[state] = state_counts.get(state, 0) + count
    make_state_svg(out_dir / "state_distribution.svg", state_counts)

    tool = e2b.get("tool_call", {})
    e2b_items = [
        ("Tool name accuracy", float(tool.get("tool_name_accuracy", 0.0)), pct(tool.get("tool_name_accuracy", 0.0))),
        ("Args schema valid", float(tool.get("args_schema_valid_rate", 0.0)), pct(tool.get("args_schema_valid_rate", 0.0))),
        ("Evidence refs valid", float(tool.get("evidence_ref_valid_rate", 0.0)), pct(tool.get("evidence_ref_valid_rate", 0.0))),
        ("Unsupported refusal", float(tool.get("unsupported_refusal_rate", 0.0)), pct(tool.get("unsupported_refusal_rate", 0.0))),
        ("No forbidden claims", 1.0 - float(tool.get("forbidden_claim_rate", 1.0)), pct(1.0 - float(tool.get("forbidden_claim_rate", 1.0)))),
    ]
    make_bar_svg(out_dir / "e2b_gate_metrics.svg", "E2B Evidence-Router Gate Metrics", e2b_items, max_value=1.0)


def build_results(evals: list[PacketEval], e2b: dict[str, Any]) -> dict[str, Any]:
    total_blocks = sum(item.block_count for item in evals)
    total_required_checks = max(1, len(evals))
    passed_contracts = sum(1 for item in evals if item.passed_contract)
    hard_upgrade_count = sum(
        count
        for item in evals
        for state, count in item.state_counts.items()
        if state.lower() in {"warning", "critical", "hard_coaching"}
    )
    abstain_blocks = sum(item.state_counts.get("abstain", 0) for item in evals)
    abstain_blocks_with_reason = 0
    for item in evals:
        for block in item.packet.get("compressed_sparse_blocks", []):
            if block.get("rule_policy_state") == "abstain" and block.get("abstain_reason"):
                abstain_blocks_with_reason += 1
    forbidden_payload_count = sum(len(item.forbidden_payload_findings) for item in evals)
    summary = {
        "fixtures": len(evals),
        "blocks": total_blocks,
        "required_field_presence": passed_contracts / total_required_checks,
        "abstain_reason_presence": (abstain_blocks_with_reason / abstain_blocks if abstain_blocks else 1.0),
        "hard_coaching_upgrade_rate": (hard_upgrade_count / total_blocks if total_blocks else 0.0),
        "forbidden_payload_rate": (forbidden_payload_count / total_required_checks),
        "total_pose_samples": sum(item.pose_samples for item in evals),
        "total_video_frames": sum(item.video_frames_total for item in evals),
        "total_sparse_blocks": total_blocks,
    }
    if total_blocks:
        summary["pose_samples_per_sparse_block"] = summary["total_pose_samples"] / total_blocks
        summary["video_frames_per_sparse_block"] = summary["total_video_frames"] / total_blocks
    tool = e2b.get("tool_call", {})
    summary.update(
        {
            "e2b_tool_name_accuracy": tool.get("tool_name_accuracy"),
            "e2b_args_schema_valid_rate": tool.get("args_schema_valid_rate"),
            "e2b_evidence_ref_valid_rate": tool.get("evidence_ref_valid_rate"),
            "e2b_unsupported_refusal_rate": tool.get("unsupported_refusal_rate"),
            "e2b_forbidden_claim_rate": tool.get("forbidden_claim_rate"),
            "e2b_refusal_pass_rate": e2b.get("refusal", {}).get("pass_rate"),
            "e2b_adversarial_pass_rate": e2b.get("adversarial", {}).get("pass_rate"),
        }
    )
    return {
        "summary": summary,
        "fixtures": [
            {
                "path": str(item.path.relative_to(REPO_ROOT)),
                "window_id": item.packet.get("window_id"),
                "trigger": item.packet.get("trigger"),
                "passed_contract": item.passed_contract,
                "pose_samples": item.pose_samples,
                "video_frames_total": item.video_frames_total,
                "block_count": item.block_count,
                "state_counts": item.state_counts,
                "confidence_floors": item.confidence_floors,
                "evidence_ref_count": item.evidence_ref_count,
                "packet_bytes": item.packet_bytes,
                "raw_video_bytes": item.raw_video_bytes,
                "compression_ratio_pose_to_blocks": item.compression_ratio_pose_to_blocks,
                "compression_ratio_raw_video_to_packet": item.compression_ratio_raw_video_to_packet,
                "missing_top_level": item.missing_top_level,
                "missing_safety": item.missing_safety,
                "missing_block_fields": item.missing_block_fields,
                "missing_extrema_fields": item.missing_extrema_fields,
                "forbidden_payload_findings": item.forbidden_payload_findings,
                "debug_only_findings": item.debug_only_findings,
            }
            for item in evals
        ],
        "e2b_metrics": e2b,
    }


def write_report(out_dir: Path, results: dict[str, Any]) -> None:
    summary = results["summary"]
    lines = [
        "# MotionZip Effectiveness Report",
        "",
        "This report verifies MotionZip as temporal evidence compression, not as video codec compression.",
        "It checks that compact packets preserve safety-critical evidence before Gemma sees them.",
        "",
        "## Summary",
        "",
        "| Metric | Value |",
        "| --- | ---: |",
        f"| Fixtures | {summary['fixtures']} |",
        f"| Sparse event blocks | {summary['blocks']} |",
        f"| Required field presence | {pct(summary['required_field_presence'])} |",
        f"| Abstain reason presence | {pct(summary['abstain_reason_presence'])} |",
        f"| Hard-coaching upgrade rate | {pct(summary['hard_coaching_upgrade_rate'])} |",
        f"| Forbidden payload rate | {pct(summary['forbidden_payload_rate'])} |",
        f"| Pose samples per sparse block | {ratio(summary.get('pose_samples_per_sparse_block'))} |",
        f"| Video frames per sparse block | {ratio(summary.get('video_frames_per_sparse_block'))} |",
        f"| E2B tool-name accuracy | {pct(summary.get('e2b_tool_name_accuracy'))} |",
        f"| E2B evidence-ref validity | {pct(summary.get('e2b_evidence_ref_valid_rate'))} |",
        f"| E2B unsupported-refusal rate | {pct(summary.get('e2b_unsupported_refusal_rate'))} |",
        f"| E2B forbidden-claim rate | {pct(summary.get('e2b_forbidden_claim_rate'))} |",
        "",
        "## Visuals",
        "",
        "![Pipeline](motionzip_pipeline.svg)",
        "",
        "![Compression overview](compression_overview.svg)",
        "",
        "![State distribution](state_distribution.svg)",
        "",
        "![E2B gate metrics](e2b_gate_metrics.svg)",
        "",
        "## Fixture Details",
        "",
        "| Fixture | Blocks | States | Confidence floors | Pose samples/block | Contract |",
        "| --- | ---: | --- | --- | ---: | --- |",
    ]
    for fixture in results["fixtures"]:
        states = ", ".join(f"{key}:{value}" for key, value in fixture["state_counts"].items())
        confidences = ", ".join(f"{value:.4f}" for value in fixture["confidence_floors"])
        lines.append(
            "| "
            + " | ".join(
                [
                    fixture["path"],
                    str(fixture["block_count"]),
                    states or "none",
                    confidences or "n/a",
                    ratio(fixture["compression_ratio_pose_to_blocks"]),
                    "PASS" if fixture["passed_contract"] else "FAIL",
                ]
            )
            + " |"
        )
    lines.extend(
        [
            "",
            "## Boundary",
            "",
            "Passing this benchmark supports MotionZip packet integrity, conservative uncertainty handling,",
            "privacy-oriented payload shaping, and E2B evidence-router compatibility. It does not validate",
            "clinical outcomes, fall-risk prediction, sarcopenia detection, rehabilitation progress, force,",
            "GRF, EMG, or muscle activation.",
        ]
    )
    (out_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate MotionZip effectiveness and generate visuals.")
    parser.add_argument("--packet-glob", default=DEFAULT_PACKET_GLOB)
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR))
    args = parser.parse_args()

    out_dir = Path(args.out_dir)
    if not out_dir.is_absolute():
        out_dir = (REPO_ROOT / out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    packets = find_packets(args.packet_glob)
    if not packets:
        raise SystemExit(f"No MotionZip packets found for pattern: {args.packet_glob}")
    evals = [evaluate_packet(path) for path in packets]
    e2b = load_e2b_metrics()
    results = build_results(evals, e2b)
    generate_visuals(out_dir, evals, e2b)
    write_report(out_dir, results)
    (out_dir / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"packets={len(evals)}")
    print(f"blocks={results['summary']['blocks']}")
    print(f"required_field_presence={results['summary']['required_field_presence']:.3f}")
    print(f"hard_coaching_upgrade_rate={results['summary']['hard_coaching_upgrade_rate']:.3f}")
    print(f"forbidden_payload_rate={results['summary']['forbidden_payload_rate']:.3f}")
    print(f"report={out_dir / 'report.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
