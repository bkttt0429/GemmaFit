"""Visualize the MotionZip A/B proof protocol.

This script turns the sparse-understanding benchmark into presentation-ready
artifacts that separate three evaluation modes:

  A-only: Gemma sees raw video/frames. Useful as a direct-video baseline.
  B-only: Gemma sees only a sanitized MotionZip evidence packet. This is the
          protocol that can support the compression claim.
  A+B: Gemma sees raw video/frames plus MotionZip. Useful for debug only, but
       not eligible as proof that MotionZip alone preserved the key evidence.

No model is called and no raw video pixels are read here.
"""

from __future__ import annotations

import argparse
import html
import json
import math
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding"
DEFAULT_RESULTS = DEFAULT_OUT_DIR / "results.json"
DEFAULT_PACKET = DEFAULT_OUT_DIR / "packets" / "sparse_stride_2_packet.json"


FORBIDDEN_PAYLOAD_KEYS = {
    "raw_video",
    "video_bytes",
    "frame_pixels",
    "frames",
    "image",
    "images",
    "raw_landmarks",
    "landmarks",
    "landmark_rows",
    "source_video",
}


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")


def fmt_pct(value: float) -> str:
    return f"{value * 100:.1f}%"


def fmt_bytes(size: int) -> str:
    if size <= 0:
        return "0 B"
    units = ["B", "KB", "MB", "GB"]
    idx = min(int(math.log(size, 1024)), len(units) - 1)
    value = size / (1024 ** idx)
    return f"{value:.1f} {units[idx]}"


def escape(value: Any) -> str:
    return html.escape(str(value), quote=True)


def recursive_forbidden_hits(value: Any, path: str = "$") -> list[str]:
    hits: list[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            key_text = str(key)
            next_path = f"{path}.{key_text}"
            if key_text in FORBIDDEN_PAYLOAD_KEYS:
                hits.append(next_path)
            hits.extend(recursive_forbidden_hits(item, next_path))
    elif isinstance(value, list):
        for idx, item in enumerate(value):
            hits.extend(recursive_forbidden_hits(item, f"{path}[{idx}]"))
    elif isinstance(value, str):
        lower = value.lower()
        if lower.endswith((".mp4", ".mov", ".avi", ".mkv")):
            hits.append(f"{path}=video_path")
    return hits


def build_bonly_prompt(packet: dict[str, Any]) -> dict[str, Any]:
    motion_zip_packet = {
        "schema_version": packet.get("schema_version"),
        "window_id": packet.get("window_id"),
        "trigger": packet.get("trigger"),
        "sliding_window": packet.get("sliding_window"),
        "compressed_sparse_blocks": packet.get("compressed_sparse_blocks", []),
        "heavily_compressed_summary": packet.get("heavily_compressed_summary", {}),
        "safety_preserved": packet.get("safety_preserved", []),
        "evidence_refs": packet.get("evidence_refs", []),
        "limits": packet.get("limits", []),
    }
    return {
        "schema_version": "motion_zip_e2b_prompt_v1",
        "protocol": "B_ONLY_NO_RAW_VIDEO",
        "system": (
            "Return exactly one JSON function call. Use only the provided "
            "motion_zip_packet evidence_refs and limits."
        ),
        "allowed_functions": [
            "create_persona_activity_report",
            "refuse_unsupported_question",
        ],
        "recommended_function": "create_persona_activity_report",
        "input": {
            "trigger": packet.get("trigger"),
            "motion_zip_packet": motion_zip_packet,
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


def rect(text: str, x: int, y: int, w: int, h: int, fill: str, stroke: str, lines: list[str] | None = None) -> str:
    body = [
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" fill="{fill}" stroke="{stroke}" stroke-width="2"/>',
        f'<text x="{x + 18}" y="{y + 30}" font-size="18" font-weight="700" fill="#0f172a">{escape(text)}</text>',
    ]
    if lines:
        for idx, line in enumerate(lines):
            body.append(
                f'<text x="{x + 18}" y="{y + 60 + idx * 24}" font-size="14" fill="#334155">{escape(line)}</text>'
            )
    return "\n".join(body)


def arrow(x1: int, y1: int, x2: int, y2: int, color: str = "#64748b") -> str:
    return (
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
        'stroke-width="2.5" marker-end="url(#arrow)"/>'
    )


def write_protocol_svg(path: Path, stats: dict[str, Any]) -> None:
    width = 1260
    height = 760
    score = fmt_pct(stats["understanding_score"])
    tracked = fmt_pct(stats["tracked_frame_ratio"])
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
<defs>
  <marker id="arrow" markerWidth="10" markerHeight="8" refX="9" refY="4" orient="auto">
    <path d="M0,0 L10,4 L0,8 Z" fill="#64748b"/>
  </marker>
</defs>
<rect width="{width}" height="{height}" fill="#f8fafc"/>
<text x="40" y="54" font-size="28" font-weight="800" fill="#0f172a">MotionZip proof protocol: keep B-only separate from A+B</text>
<text x="40" y="84" font-size="15" fill="#475569">A+B is useful for consistency checks, but it cannot prove the compressed packet alone is sufficient.</text>

{rect("A-only baseline", 40, 130, 360, 210, "#eff6ff", "#2563eb", [
    "Input: raw video or sampled frames",
    "Model: Gemma video / vision path",
    "Use: direct-video baseline",
    "Compression claim: not eligible"
])}
{rect("B-only proof path", 450, 130, 360, 210, "#ecfdf5", "#059669", [
    "Input: sanitized MotionZip JSON only",
    f"Tracked-frame ratio: {tracked}",
    f"Understanding score: {score}",
    "Compression claim: eligible"
])}
{rect("A+B debug path", 860, 130, 360, 210, "#fff7ed", "#ea580c", [
    "Input: raw video/frames + MotionZip",
    "Use: mismatch analysis and demo QA",
    "Risk: model can rely on A",
    "Compression claim: not eligible"
])}

{rect("Raw video / frames", 65, 410, 300, 88, "#dbeafe", "#2563eb", [stats["raw_video_size"]])}
{rect("Sanitized evidence packet", 480, 410, 300, 88, "#dcfce7", "#16a34a", [stats["bonly_payload_size"], "No raw video or raw landmarks"])}
{rect("Mixed evidence", 890, 410, 300, 88, "#ffedd5", "#f97316", ["Raw visual evidence is present", "Debug only"])}

{arrow(215, 500, 215, 575, "#2563eb")}
{arrow(630, 500, 630, 575, "#16a34a")}
{arrow(1040, 500, 1040, 575, "#f97316")}

{rect("Result interpretation", 65, 575, 300, 100, "#ffffff", "#94a3b8", [
    "Answers: what Gemma can infer",
    "when it sees video directly."
])}
{rect("Result interpretation", 480, 575, 300, 100, "#ffffff", "#16a34a", [
    "Answers: what MotionZip preserves",
    "without raw visual access."
])}
{rect("Result interpretation", 890, 575, 300, 100, "#ffffff", "#f97316", [
    "Answers: whether A and B agree,",
    "not whether B alone is sufficient."
])}
</svg>
'''
    path.write_text(svg, encoding="utf-8")


def write_gate_svg(path: Path, stats: dict[str, Any]) -> None:
    width = 1180
    height = 640
    packet_bar = max(8, int(stats["bonly_payload_bytes"] / stats["raw_video_bytes"] * 760))
    video_bar = 760
    score_bar = int(stats["understanding_score"] * 760)
    tracked_bar = int(stats["tracked_frame_ratio"] * 760)
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
<rect width="{width}" height="{height}" fill="#ffffff"/>
<text x="40" y="54" font-size="28" font-weight="800" fill="#0f172a">B-only no-peek gate</text>
<text x="40" y="84" font-size="15" fill="#475569">The proof packet removes raw video paths and landmark streams before it is treated as Gemma input.</text>

<text x="60" y="145" font-size="16" font-weight="700" fill="#0f172a">Raw video file</text>
<rect x="300" y="125" width="{video_bar}" height="32" rx="4" fill="#bfdbfe"/>
<text x="300" y="182" font-size="14" fill="#334155">{escape(stats["raw_video_size"])}</text>

<text x="60" y="235" font-size="16" font-weight="700" fill="#0f172a">B-only Gemma payload</text>
<rect x="300" y="215" width="{packet_bar}" height="32" rx="4" fill="#86efac"/>
<text x="300" y="272" font-size="14" fill="#334155">{escape(stats["bonly_payload_size"])} ({escape(stats["compression_ratio_label"])})</text>

<text x="60" y="335" font-size="16" font-weight="700" fill="#0f172a">Tracked-frame ratio</text>
<rect x="300" y="315" width="{tracked_bar}" height="32" rx="4" fill="#fde68a"/>
<text x="300" y="372" font-size="14" fill="#334155">{escape(fmt_pct(stats["tracked_frame_ratio"]))}; every {stats["effective_frame_interval"]} video frames</text>

<text x="60" y="425" font-size="16" font-weight="700" fill="#0f172a">Understanding score</text>
<rect x="300" y="405" width="{score_bar}" height="32" rx="4" fill="#c4b5fd"/>
<text x="300" y="462" font-size="14" fill="#334155">{escape(fmt_pct(stats["understanding_score"]))}; tags, states, events, velocity, confidence floor</text>

<rect x="60" y="505" width="1060" height="82" rx="8" fill="#f8fafc" stroke="#cbd5e1"/>
<text x="82" y="537" font-size="17" font-weight="700" fill="#0f172a">Gate result: {escape(stats["gate_result"])}</text>
<text x="82" y="567" font-size="14" fill="#475569">Forbidden payload hits: {escape(str(stats["forbidden_hit_count"]))}; event blocks: {stats["blocks"]}; states: {escape(stats["states"])}</text>
</svg>
'''
    path.write_text(svg, encoding="utf-8")


def write_report(path: Path, stats: dict[str, Any]) -> None:
    text = f"""# MotionZip B-only Proof Protocol

This report separates the proof path from the debug path.

## Protocol Matrix

| Protocol | Gemma input | Valid use | Eligible for MotionZip compression claim |
| --- | --- | --- | --- |
| A-only | Raw video or sampled frames | Direct-video baseline | No |
| B-only | Sanitized MotionZip JSON evidence packet only | Prove compressed evidence is sufficient | Yes |
| A+B | Raw video/frames plus MotionZip packet | Consistency check and debug | No |

## B-only Result

| Metric | Value |
| --- | ---: |
| Source video size | {stats["raw_video_size"]} |
| B-only Gemma payload size | {stats["bonly_payload_size"]} |
| Approx payload reduction | {stats["compression_ratio_label"]} |
| Tracked samples | {stats["tracked_samples"]} |
| Tracked-frame ratio | {fmt_pct(stats["tracked_frame_ratio"])} |
| Effective tracking interval | {stats["effective_frame_interval"]} video frames |
| MotionZip event blocks | {stats["blocks"]} |
| Understanding score | {fmt_pct(stats["understanding_score"])} |
| Forbidden raw-payload hits | {stats["forbidden_hit_count"]} |

## Visuals

![Proof protocol](motionzip_proof_protocol.svg)

![B-only no-peek gate](motionzip_bonly_no_peek_gate.svg)

## Interpretation

The B-only path is the only path that can support the claim: MotionZip preserved the key motion evidence without giving Gemma raw visual evidence.
The A+B path is still useful, but only for QA: if direct video and MotionZip disagree, inspect the tracking/compression stage. It should not be used as the headline evidence that MotionZip works.

## Generated Payload

- B-only Gemma payload: `gemma_bonly_payload_stride_2.json`
- Source sparse packet: `packets/sparse_stride_2_packet.json`
"""
    path.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--packet", type=Path, default=DEFAULT_PACKET)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR)
    args = parser.parse_args()

    results = load_json(args.results)
    packet = load_json(args.packet)
    summary = results["summary"]
    variant = next(
        item for item in results["variants"]
        if item["relative_stride"] == summary["lowest_budget_passing_stride"]
    )

    bonly_prompt = build_bonly_prompt(packet)
    payload_path = args.out_dir / "gemma_bonly_payload_stride_2.json"
    write_json(payload_path, bonly_prompt)

    raw_video_path = Path(summary["video"])
    if not raw_video_path.is_absolute():
        raw_video_path = REPO_ROOT / raw_video_path
    raw_video_bytes = raw_video_path.stat().st_size if raw_video_path.exists() else 0
    bonly_payload_bytes = payload_path.stat().st_size
    forbidden_hits = recursive_forbidden_hits(bonly_prompt)
    ratio = raw_video_bytes / bonly_payload_bytes if bonly_payload_bytes else 0.0

    states = ", ".join(variant.get("states", []))
    stats = {
        "raw_video_bytes": raw_video_bytes,
        "raw_video_size": fmt_bytes(raw_video_bytes),
        "bonly_payload_bytes": bonly_payload_bytes,
        "bonly_payload_size": fmt_bytes(bonly_payload_bytes),
        "compression_ratio_label": f"{ratio:.0f}x smaller than raw source video" if ratio else "n/a",
        "tracked_samples": variant["tracked_pose_samples"],
        "tracked_frame_ratio": variant["tracked_pose_samples"] / max(1, summary["source_frame_span"]),
        "effective_frame_interval": variant["effective_video_frame_interval"],
        "blocks": variant["sparse_blocks"],
        "understanding_score": variant["understanding_score"],
        "forbidden_hits": forbidden_hits,
        "forbidden_hit_count": len(forbidden_hits),
        "gate_result": "PASS" if not forbidden_hits else "FAIL",
        "states": states,
    }

    write_json(args.out_dir / "ab_protocol_summary.json", stats)
    write_protocol_svg(args.out_dir / "motionzip_proof_protocol.svg", stats)
    write_gate_svg(args.out_dir / "motionzip_bonly_no_peek_gate.svg", stats)
    write_report(args.out_dir / "ab_test_protocol_report.md", stats)

    print(json.dumps({
        "gate_result": stats["gate_result"],
        "understanding_score": round(stats["understanding_score"], 4),
        "tracked_frame_ratio": round(stats["tracked_frame_ratio"], 4),
        "bonly_payload": str(payload_path),
        "report": str(args.out_dir / "ab_test_protocol_report.md"),
    }, indent=2))
    return 0 if not forbidden_hits else 1


if __name__ == "__main__":
    raise SystemExit(main())
