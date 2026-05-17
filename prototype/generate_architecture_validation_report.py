"""Generate the architecture validation gap report for GemmaFit.

This report is intentionally evidence-bounded: it only marks a subsystem as
validated when there is a local benchmark artifact or test anchor to cite. It
does not run Android, call a model, or infer success from architecture text.
"""

from __future__ import annotations

import html
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "architecture_validation_gap_report_2026-05-16"


@dataclass(frozen=True)
class ValidationItem:
    id: str
    architecture: str
    status: str
    priority: str
    current_evidence: list[str]
    remaining_validation: list[str]
    next_report: str
    acceptance_gate: list[str]
    artifacts: list[str]


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT)).replace("\\", "/")
    except ValueError:
        return str(path).replace("\\", "/")


def read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8-sig"))


def exists_text(path: str) -> str:
    full = REPO_ROOT / path
    return f"`{path}`" if full.exists() else f"`{path}` (missing)"


def fmt_ms(value: Any) -> str:
    try:
        return f"{float(value):.0f} ms"
    except (TypeError, ValueError):
        return "unknown"


def build_items() -> list[ValidationItem]:
    motionzip = read_json(
        REPO_ROOT / "docs/benchmark/motionzip_model_equivalence/model_equivalence_results.json",
        {},
    )
    multimodal = read_json(
        REPO_ROOT / "docs/benchmark/multimodal_image_compression_reproof_2026-05-16/results.json",
        {},
    )
    layer2 = read_json(
        REPO_ROOT / "docs/benchmark/layer2_senior_activity_ab_2026-05-16/results.json",
        {},
    )
    litert = read_json(
        REPO_ROOT / "docs/benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/summary.json",
        {},
    )
    rgba = read_json(
        REPO_ROOT / "docs/benchmark/rgba_pipeline_mobile_default_optimized_2026-05-16/summary.json",
        {},
    )
    pixel_flow = REPO_ROOT / "docs/benchmark/pixel_demo_flow_smoke_2026-05-16/README.md"

    motionzip_input = motionzip.get("input_sizes", {})
    motionzip_pass_rate = motionzip.get("key_fact_pass_rate", 0.0)
    multimodal_byte = multimodal.get("byte_comparison", {})
    multimodal_visual = multimodal.get("visual_output_comparison", {})
    sidecar = multimodal_visual.get("motionzip_sidecar", {})
    dense = multimodal_visual.get("dense_raw_montage", {})
    layer2_totals = layer2.get("totals", {})
    layer2_pixel = layer2.get("pixel_smoke", {})
    rgba_timing = rgba.get("timing_us", {}).get("total_accepted_frame", {})

    items = [
        ValidationItem(
            id="motionzip_compression",
            architecture="MotionZip compact evidence / compression path",
            status="strong",
            priority="P0 evidence ready",
            current_evidence=[
                f"Oracle key-fact pass rate: {motionzip_pass_rate * 100:.1f}%.",
                f"Compact prompt pair: {motionzip_input.get('compact_model_prompt_pair_bytes', 'unknown')} bytes; dense rows: {motionzip_input.get('dense_frame_rows', 'unknown')}; MotionZip blocks: {motionzip_input.get('motionzip_event_blocks', 'unknown')}.",
                f"Visual sidecar field pass: dense {dense.get('pass_count', '?')}/{dense.get('total', '?')} vs sidecar {sidecar.get('pass_count', '?')}/{sidecar.get('total', '?')}.",
                f"Panel q70 reduction vs source video: {multimodal_byte.get('panel_q70_vs_source_video_reduction_pct', 0):.2f}%.",
            ],
            remaining_validation=[
                "Repeat on a small cross-video set, not only the lunge fixture.",
                "Keep B-only proof separate from A+B debug comparisons.",
            ],
            next_report="motionzip_cross_video_reproof_2026-05-16",
            acceptance_gate=[
                ">= 5 representative videos.",
                ">= 95% key-fact agreement on activity/state/event/velocity/confidence.",
                "No raw frame history or full landmark stream in model payload.",
            ],
            artifacts=[
                exists_text("docs/benchmark/motionzip_model_equivalence/report.md"),
                exists_text("docs/benchmark/motionzip_sparse_understanding/report.md"),
                exists_text("docs/benchmark/multimodal_image_compression_reproof_2026-05-16/report.md"),
            ],
        ),
        ValidationItem(
            id="official_litert_e2b",
            architecture="Official Gemma-4-E2B LiteRT constrained runtime",
            status="partial",
            priority="P0 needs stability pass",
            current_evidence=[
                f"Constrained smoke completed {litert.get('count_completed', '?')}/{litert.get('count_requested', '?')} requests.",
                f"JSON parse success rate: {litert.get('model_json_parse_success_rate', 0) * 100:.1f}%.",
                f"Generate p50/p95: {fmt_ms(litert.get('generate_ms_p50'))} / {fmt_ms(litert.get('generate_ms_p95'))}.",
                f"Engine reinitialize count: {litert.get('engine_reinitialize_count', 'unknown')}.",
            ],
            remaining_validation=[
                "Thermal and memory stability over a longer foreground run.",
                "Confirm fallback behavior when LiteRT initialization fails.",
                "Keep app-side parser/validator mandatory because native tool calls were not observed.",
            ],
            next_report="litert_runtime_stability_report_2026-05-16",
            acceptance_gate=[
                "No unhandled crash across 10-30 minutes foreground use.",
                "Every response parseable or deterministically refused/fallbacked.",
                "P95 generation and memory figures reported with device state.",
            ],
            artifacts=[
                exists_text("docs/benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/report.md"),
                exists_text("docs/benchmark/litert_model_perf_2026-05-16/report.md"),
            ],
        ),
        ValidationItem(
            id="live_safety_contract",
            architecture="Deterministic live safety path contract",
            status="gap",
            priority="P0 missing report",
            current_evidence=[
                "Architecture specifies MediaPipe / YOLO fallback / C++ motion evidence -> Trust Matrix -> Evidence Card -> UI/TTS.",
                "Unit and device artifacts cover pieces, but no single report proves the whole live contract end to end.",
                f"Pixel demo flow artifact exists: {'yes' if pixel_flow.exists() else 'no'}.",
            ],
            remaining_validation=[
                "Prove `LIVE_FRAME` never calls Gemma or multimodal backend.",
                "Prove deterministic verdict is emitted before any optional sidecar work.",
                "Measure cue latency, stale-frame handling, and evidence-card completeness on replay/live camera.",
            ],
            next_report="live_safety_contract_report_2026-05-16",
            acceptance_gate=[
                "0 model/backend calls for `LIVE_FRAME` triggers.",
                "Every `WARNING`/`MONITOR`/`LOW_CONFIDENCE` has evidence refs and unsupported judgments where required.",
                "No optional sidecar result mutates deterministic verdict fields.",
            ],
            artifacts=[
                exists_text("app/src/test/kotlin/com/gemmafit/video/ModelInvocationSchedulerTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/video/TrustUiStateTest.kt"),
                exists_text("docs/benchmark/pixel_demo_flow_smoke_2026-05-16/README.md"),
            ],
        ),
        ValidationItem(
            id="layer2_senior",
            architecture="Senior Layer 2 temporal interpreter and activity context",
            status="partial",
            priority="P0 needs real-video replay",
            current_evidence=[
                f"Risky hard-judgment frames reduced by {layer2_totals.get('risky_hard_judgment_frames', {}).get('reduction_percent', 'unknown')}%.",
                f"Setup-transition false events reduced by {layer2_totals.get('setup_transition_non_none_events', {}).get('reduction_percent', 'unknown')}%.",
                f"Pixel Layer 2 smoke success: {layer2_pixel.get('layer2_smoke', {}).get('success', 'unknown')}.",
                f"Pixel ModelInvocation smoke success: {layer2_pixel.get('model_invocation_smoke', {}).get('success', 'unknown')}.",
            ],
            remaining_validation=[
                "Replay real senior chair/squat/balance videos with event timeline output.",
                "Validate ambiguity handling and abstain wording on transitions.",
                "Verify no non-senior activity unlocks hard senior judgments.",
            ],
            next_report="senior_layer2_video_replay_report_2026-05-16",
            acceptance_gate=[
                "No hard senior judgment on non-senior clips.",
                "Every emitted senior event has phase/event evidence refs.",
                "Ambiguous context emits `AMBIGUOUS` or abstain, not a confident label.",
            ],
            artifacts=[
                exists_text("docs/benchmark/layer2_senior_activity_ab_2026-05-16/README.md"),
                exists_text("app/src/test/kotlin/com/gemmafit/video/Layer2TemporalInterpreterTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/video/Layer2SeniorArchitectureABTest.kt"),
            ],
        ),
        ValidationItem(
            id="multimodal_sidecar",
            architecture="Low-frequency multimodal evidence panel sidecar",
            status="partial",
            priority="P1 backend gated",
            current_evidence=[
                "Frame selector, packet, validator, and scheduler test anchors exist.",
                f"Compressed visual sidecar field pass: {sidecar.get('pass_count', '?')}/{sidecar.get('total', '?')}.",
                "Report video demo now shows raw-video vs sidecar difference on one clip.",
            ],
            remaining_validation=[
                "Run trigger-gate report proving `LIVE_FRAME` is blocked on-device.",
                "Validate text-only fallback when image/audio backend is missing or too expensive.",
                "If FP32 vision is used, collect memory budget and failure fallback evidence.",
            ],
            next_report="multimodal_trigger_fallback_report_2026-05-16",
            acceptance_gate=[
                "`USER_QUESTION`, `SESSION_ENDED`, `CAREGIVER_EXPORT`, optional `WARNING_PERSISTED` only.",
                "`LIVE_FRAME` produces no panel and no backend call.",
                "Validator rejects missing evidence refs, new warnings, verdict mutation, and forbidden claims.",
            ],
            artifacts=[
                exists_text("docs/benchmark/multimodal_image_compression_reproof_2026-05-16/report.md"),
                exists_text("app/src/test/kotlin/com/gemmafit/video/MultimodalResultValidatorTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/video/FrameEvidenceSelectorTest.kt"),
            ],
        ),
        ValidationItem(
            id="person_recovery",
            architecture="Pose ownership, subject identity, and YOLO/person fallback",
            status="partial",
            priority="P0 needs adversarial video",
            current_evidence=[
                "Subject identity, relocalization, ownership, and motion tracking test anchors exist.",
                "RGBA/camera pipeline metrics exist for the selected live image path.",
                "No consolidated report yet proves multi-person recovery on real video.",
            ],
            remaining_validation=[
                "Multi-person crossing/occlusion replay.",
                "Subject loss -> YOLO burst -> pose ownership recovery timeline.",
                "False-owner prevention with stale skeleton and ROI drift cases.",
            ],
            next_report="person_recovery_yolo_fallback_report_2026-05-16",
            acceptance_gate=[
                "No stale skeleton rendered as current subject.",
                "No identity switch without explicit low-confidence/relocalization state.",
                "YOLO burst remains fallback, not always-on cost.",
            ],
            artifacts=[
                exists_text("app/src/test/kotlin/com/gemmafit/video/SubjectIdentityMatcherTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/video/PoseOwnershipGateTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/video/SubjectRelocalizationPolicyTest.kt"),
            ],
        ),
        ValidationItem(
            id="camera_rgba_pipeline",
            architecture="CameraX RGBA/YUV image pipeline and frame budget",
            status="partial",
            priority="P0 needs long-run device pass",
            current_evidence=[
                f"Sample count: {rgba.get('sample_count', 'unknown')}.",
                f"Accepted-frame p50/p95: {rgba_timing.get('p50', 'unknown')} us / {rgba_timing.get('p95', 'unknown')} us.",
                f"Estimated sample rate: {rgba.get('estimated_sample_rate_hz', 'unknown')} Hz.",
                f"Pipeline variants observed: {', '.join(rgba.get('pipeline_variants', {}).keys()) or 'unknown'}.",
            ],
            remaining_validation=[
                "10-30 minute thermal/memory run.",
                "Front/back camera and rotation matrix smoke.",
                "Verify no bitmap recycle/missing frame crash in evidence panel path.",
            ],
            next_report="camera_pipeline_long_run_report_2026-05-16",
            acceptance_gate=[
                "No crash, no sustained frame starvation, no memory creep above documented threshold.",
                "Every camera rotation path emits correctly oriented pose/image evidence.",
                "Debug audit can reconstruct active variant and timing.",
            ],
            artifacts=[
                exists_text("docs/benchmark/rgba_pipeline_mobile_default_optimized_2026-05-16/report.md"),
                exists_text("docs/benchmark/rgba_pipeline_mobile_camera_rotate_ab_2026-05-16/README.md"),
                exists_text("docs/benchmark/rgba_pipeline_audit_2026-05-16/README.md"),
            ],
        ),
        ValidationItem(
            id="memory_export",
            architecture="Structured local memory and caregiver export boundary",
            status="partial",
            priority="P0 needs privacy audit report",
            current_evidence=[
                "Memory write policy, refusal validator, prompt builder, and caregiver export tests exist.",
                "Architecture says raw video, raw frame history, and full landmarks are not long-term memory.",
                "No single artifact yet scans generated memory/export payloads for forbidden data and clinical claims.",
            ],
            remaining_validation=[
                "Export JSON/TXT/HTML scan for raw media paths, full landmarks, and forbidden terms.",
                "Adversarial `MemoryUpdateRequest` rejection report.",
                "Evidence-id provenance and idempotency audit.",
            ],
            next_report="memory_export_boundary_report_2026-05-16",
            acceptance_gate=[
                "0 raw video/frame/landmark history fields in memory/export payloads.",
                "Every trend note has >= 1 evidence id.",
                "Caregiver export includes non-clinical unsupported-judgment block.",
            ],
            artifacts=[
                exists_text("app/src/test/kotlin/com/gemmafit/memory/MemoryWritePolicyTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/memory/RefusalValidatorTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/export/CaregiverExportBuilderTest.kt"),
            ],
        ),
        ValidationItem(
            id="voice_accessibility",
            architecture="Senior UI, voice cue policy, and TTS cooldown",
            status="partial",
            priority="P1 needs device UX capture",
            current_evidence=[
                "Voice cue policy and senior voice parsing tests exist.",
                "Pixel demo flow screenshots exist, but not a focused accessibility report.",
            ],
            remaining_validation=[
                "Large-font screen capture across Senior Home, Live Coach, Evidence Card, Memory & Trends.",
                "TTS cooldown and interruption behavior on live cue bursts.",
                "No text overlap at senior font scale and no unsupported in-app wording.",
            ],
            next_report="senior_voice_accessibility_report_2026-05-16",
            acceptance_gate=[
                "TTS cooldown >= 3 seconds for repeated cues.",
                "All senior-mode feedback screens include source and unsupported-judgment context.",
                "No overlapping text at configured large font scale.",
            ],
            artifacts=[
                exists_text("app/src/test/kotlin/com/gemmafit/voice/VoiceCuePolicyTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/voice/UtteranceSequenceGateTest.kt"),
                exists_text("app/src/test/kotlin/com/gemmafit/senior/SeniorVoiceAnswerParserTest.kt"),
            ],
        ),
        ValidationItem(
            id="biomechanics_thresholds",
            architecture="Native biomechanics thresholds and Trust Matrix calibration",
            status="partial",
            priority="P1 needs dataset report",
            current_evidence=[
                "Implementation plan records native motion-quality tests and earlier Zenodo findings.",
                "Current benchmark directory does not contain a consolidated threshold calibration report.",
            ],
            remaining_validation=[
                "Dataset-level confusion matrix by rule and by unsupported/judgeable state.",
                "Senior-mode threshold differences separated from general fitness mode.",
                "Evidence Card examples for false-positive and false-negative cases.",
            ],
            next_report="biomechanics_threshold_calibration_report_2026-05-16",
            acceptance_gate=[
                "Every threshold claim has dataset/source, denominator, and known limitation.",
                "No clinical, force, EMG, load, heart-rate, or fall-risk interpretation.",
                "Trust Matrix downgrades when confidence/applicability gates fail.",
            ],
            artifacts=[
                exists_text("native/tests/test_motion_quality.cpp"),
                exists_text("docs/benchmark/motionzip_effectiveness/report.md"),
                exists_text("implementation_plan.md"),
            ],
        ),
    ]
    return items


def status_rank(status: str) -> int:
    return {"gap": 0, "partial": 1, "strong": 2}.get(status, 3)


def priority_rank(priority: str) -> int:
    if priority.startswith("P0"):
        return 0
    if priority.startswith("P1"):
        return 1
    return 2


def svg_text(x: int, y: int, value: str, size: int = 15, weight: int = 400, fill: str = "#0f172a") -> str:
    return (
        f'<text x="{x}" y="{y}" font-family="Arial, sans-serif" '
        f'font-size="{size}" font-weight="{weight}" fill="{fill}">{html.escape(value)}</text>'
    )


def write_status_svg(path: Path, items: list[ValidationItem]) -> None:
    colors = {"strong": "#16a34a", "partial": "#f59e0b", "gap": "#dc2626"}
    labels = {"strong": "Evidence ready", "partial": "Partial", "gap": "Missing report"}
    counts = {key: sum(1 for item in items if item.status == key) for key in colors}
    max_count = max(counts.values()) if counts else 1
    body = [
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        svg_text(32, 46, "Architecture Validation Status", 26, 700),
        svg_text(32, 74, "Evidence-bounded status from local benchmark artifacts.", 15, 400, "#475569"),
    ]
    for index, key in enumerate(["strong", "partial", "gap"]):
        y = 122 + index * 74
        body.append(svg_text(40, y + 20, labels[key], 16, 700, "#334155"))
        body.append(f'<rect x="240" y="{y}" width="500" height="28" rx="6" fill="#e2e8f0"/>')
        bar_w = int(500 * counts[key] / max_count)
        body.append(f'<rect x="240" y="{y}" width="{bar_w}" height="28" rx="6" fill="{colors[key]}"/>')
        body.append(svg_text(764, y + 21, f"{counts[key]} subsystem(s)", 16, 700))
    body.append(svg_text(32, 344, "Interpretation: partial means useful evidence exists, but the final competition claim still needs a narrower proof report.", 14, 400, "#475569"))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="920" height="380" viewBox="0 0 920 380">\n' + "\n".join(body) + "\n</svg>\n"
    path.write_text(svg, encoding="utf-8")


def write_priority_svg(path: Path, items: list[ValidationItem]) -> None:
    p0 = sorted(
        [item for item in items if item.priority.startswith("P0") and item.status != "strong"],
        key=lambda item: (status_rank(item.status), item.id),
    )
    p1 = sorted(
        [item for item in items if item.priority.startswith("P1") and item.status != "strong"],
        key=lambda item: (status_rank(item.status), item.id),
    )
    body = [
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        svg_text(32, 46, "Next Validation Reports", 26, 700),
        svg_text(32, 74, "Generate P0 reports before broadening writeup claims.", 15, 400, "#475569"),
        '<rect x="32" y="110" width="520" height="390" rx="10" fill="#fff7ed" stroke="#f97316" stroke-width="2"/>',
        '<rect x="588" y="110" width="520" height="390" rx="10" fill="#eef2ff" stroke="#6366f1" stroke-width="2"/>',
        svg_text(56, 146, "P0: proof blockers", 20, 700, "#9a3412"),
        svg_text(612, 146, "P1: polish / breadth", 20, 700, "#3730a3"),
    ]
    for index, item in enumerate(p0[:6]):
        body.append(svg_text(56, 184 + index * 48, f"{index + 1}. {item.next_report}", 14, 700, "#334155"))
        body.append(svg_text(76, 204 + index * 48, item.architecture[:58], 12, 400, "#64748b"))
    for index, item in enumerate(p1[:6]):
        body.append(svg_text(612, 184 + index * 48, f"{index + 1}. {item.next_report}", 14, 700, "#334155"))
        body.append(svg_text(632, 204 + index * 48, item.architecture[:58], 12, 400, "#64748b"))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="1140" height="535" viewBox="0 0 1140 535">\n' + "\n".join(body) + "\n</svg>\n"
    path.write_text(svg, encoding="utf-8")


def write_report(path: Path, items: list[ValidationItem]) -> None:
    sorted_items = sorted(items, key=lambda item: (priority_rank(item.priority), status_rank(item.status), item.id))
    lines = [
        "# Architecture Validation Gap Report",
        "",
        "Date: 2026-05-16",
        "",
        "## Conclusion",
        "",
        "The architecture is strongest where there is already local benchmark evidence: MotionZip compression, constrained LiteRT JSON parsing, Layer 2 unit/device smoke, RGBA camera-path timing, and the multimodal image-compression reproof. The remaining proof gap is not another diagram; it is cross-module validation showing that live safety remains deterministic, sidecars are gated, memory/export stays non-clinical, and device runtime remains stable.",
        "",
        "## Visual Summary",
        "",
        "![Validation status](validation_status.svg)",
        "",
        "![Next validation reports](next_validation_reports.svg)",
        "",
        "## Validation Matrix",
        "",
        "| Architecture | Status | Priority | Current evidence | Next report |",
        "| --- | --- | --- | --- | --- |",
    ]
    for item in sorted_items:
        evidence = "<br>".join(item.current_evidence)
        lines.append(f"| {item.architecture} | `{item.status}` | `{item.priority}` | {evidence} | `{item.next_report}` |")

    lines.extend(
        [
            "",
            "## P0 Reports To Generate Next",
            "",
            "These are the reports that would most improve the credibility of the final architecture story.",
            "",
        ]
    )
    for item in sorted_items:
        if item.priority.startswith("P0") and item.status != "strong":
            lines.extend(
                [
                    f"### {item.next_report}",
                    "",
                    f"Architecture: {item.architecture}",
                    "",
                    "Remaining validation:",
                    "",
                ]
            )
            lines.extend(f"- {entry}" for entry in item.remaining_validation)
            lines.extend(["", "Acceptance gate:", ""])
            lines.extend(f"- {entry}" for entry in item.acceptance_gate)
            lines.extend(["", "Current anchors:", ""])
            lines.extend(f"- {entry}" for entry in item.artifacts)
            lines.append("")

    lines.extend(
        [
            "## P1 Reports",
            "",
            "These broaden the story after the P0 safety contract and privacy/runtime gates are closed.",
            "",
        ]
    )
    for item in sorted_items:
        if item.priority.startswith("P1") and item.status != "strong":
            lines.extend(
                [
                    f"- `{item.next_report}`: {item.architecture}.",
                ]
            )

    lines.extend(
        [
            "",
            "## Claim Rules",
            "",
            "- Do claim task-preserving evidence compression only where MotionZip / sidecar reports provide key-fact agreement.",
            "- Do claim local constrained LiteRT output only with the app-side parser/validator caveat.",
            "- Do not claim live Vision/Gemma safety, clinical progress, fall risk, sarcopenia detection, force/load, EMG, heart-rate status, or raw-video persistence.",
            "- Do not present `partial` items as proven architecture; use them as implementation status plus next validation plan.",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    items = build_items()
    (OUT_DIR / "validation_matrix.json").write_text(
        json.dumps([asdict(item) for item in items], indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    write_status_svg(OUT_DIR / "validation_status.svg", items)
    write_priority_svg(OUT_DIR / "next_validation_reports.svg", items)
    write_report(OUT_DIR / "report.md", items)
    print(json.dumps({"out_dir": str(OUT_DIR), "report": str(OUT_DIR / "report.md")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
