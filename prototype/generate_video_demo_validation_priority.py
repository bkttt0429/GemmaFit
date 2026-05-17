"""Generate the video-demo validation priority report.

The report maps each planned 3-minute video segment to the technical claim that
must be proven behind it. The goal is to avoid a feature-only demo: each visible
moment either has an existing comparison artifact or a named validation report
that must be produced before the claim is used in the submission video.
"""

from __future__ import annotations

import html
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "video_demo_validation_priority_2026-05-16"
ARCH_MATRIX = REPO_ROOT / "docs" / "benchmark" / "architecture_validation_gap_report_2026-05-16" / "validation_matrix.json"


@dataclass(frozen=True)
class Segment:
    order: int
    time_range: str
    video_ratio_bucket: str
    video_content: str
    visible_demo: str
    technical_claim: str
    proof_status: str
    validation_priority: str
    comparison_report: str
    comparison_design: str
    ready_artifacts: list[str]
    acceptance_gate: list[str]
    use_in_video_now: str


@dataclass(frozen=True)
class ReportQueueItem:
    rank: int
    report_id: str
    why_video_needs_it: str
    comparison_groups: list[str]
    minimum_artifacts: list[str]
    done_when: list[str]
    related_segments: list[int]


def read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8-sig"))


def exists(path: str) -> bool:
    return (REPO_ROOT / path).exists()


def artifact(path: str) -> str:
    return f"`{path}`" if exists(path) else f"`{path}` (missing)"


def build_segments() -> list[Segment]:
    return [
        Segment(
            order=1,
            time_range="0-20s",
            video_ratio_bucket="10% impact",
            video_content="Problem setup: home exercise needs safe feedback without cloud dependence.",
            visible_demo="Older adult / beginner starts a simple home movement session.",
            technical_claim="Non-clinical, privacy-preserving movement-quality support; not diagnosis.",
            proof_status="script-ready",
            validation_priority="P2 wording guard",
            comparison_report="writeup_claim_boundary_check",
            comparison_design="Compare final narration against forbidden clinical/raw-data claims.",
            ready_artifacts=[
                artifact("implementation_plan.md"),
                artifact("docs/benchmark/architecture_validation_gap_report_2026-05-16/report.md"),
            ],
            acceptance_gate=[
                "No fall-risk, sarcopenia, rehab, force, EMG, heart-rate, or clinical-progress claim.",
                "Problem statement mentions privacy/offline without implying medical monitoring.",
            ],
            use_in_video_now="yes, with conservative wording",
        ),
        Segment(
            order=2,
            time_range="20-38s",
            video_ratio_bucket="70% product demo",
            video_content="Normal rep: skeleton / trace appears while a movement is analyzed.",
            visible_demo="Pixel screen: camera/video preview, skeleton or trace, progress, stable evidence.",
            technical_claim="The app runs real deterministic pose and motion analysis before any model explanation.",
            proof_status="needs P0 comparison",
            validation_priority="P0",
            comparison_report="live_safety_contract_report_2026-05-16",
            comparison_design="Clean rep vs generated Evidence Card: show motion trace, verdict source, evidence_refs, and zero live model calls.",
            ready_artifacts=[
                artifact("docs/benchmark/live_safety_contract_preflight_2026-05-16/report.md"),
                artifact("docs/benchmark/pixel_demo_flow_smoke_2026-05-16/README.md"),
                artifact("docs/benchmark/pixel_demo_flow_smoke_2026-05-16/ui_after_ui_fix_analysis.png"),
                artifact("docs/benchmark/rgba_pipeline_mobile_default_optimized_2026-05-16/report.md"),
            ],
            acceptance_gate=[
                "`LIVE_FRAME` model call count is 0.",
                "Trace/evidence state is produced before summary or sidecar output.",
                "Frame timing and analysis completion are included.",
            ],
            use_in_video_now="yes for UI run; do not claim full live contract until report exists",
        ),
        Segment(
            order=3,
            time_range="38-55s",
            video_ratio_bucket="70% product demo",
            video_content="Monitor / warning rep: unsafe or questionable movement produces a cue.",
            visible_demo="Pixel screen: warning/monitor badge, cue, evidence explanation or Why flagged.",
            technical_claim="Safety cues are evidence-bounded, not free-form LLM judgments.",
            proof_status="needs P0 comparison",
            validation_priority="P0",
            comparison_report="live_safety_contract_report_2026-05-16",
            comparison_design="Warning frame vs Evidence Card vs Gemma explanation: model may explain refs but cannot create or mutate verdict.",
            ready_artifacts=[
                artifact("docs/benchmark/live_safety_contract_preflight_2026-05-16/report.md"),
                artifact("docs/benchmark/pixel_demo_flow_smoke_2026-05-16/ui_why_flagged_full_width.png"),
                artifact("docs/benchmark/pixel_demo_flow_smoke_2026-05-16/README.md"),
                artifact("app/src/test/kotlin/com/gemmafit/video/TrustUiStateTest.kt"),
            ],
            acceptance_gate=[
                "Every warning/monitor row has evidence_refs and reason text.",
                "Gemma output has same evidence_refs or is rejected.",
                "No new warning generated by model text.",
            ],
            use_in_video_now="yes for UI proof; add comparison report before technical voiceover",
        ),
        Segment(
            order=4,
            time_range="55-70s",
            video_ratio_bucket="70% product demo",
            video_content="Low confidence / abstention: bad view, missing person, occlusion, or multi-person ambiguity.",
            visible_demo="Pixel screen: low-confidence or abstain message; no stale confident warning.",
            technical_claim="The app refuses to judge when pose/person evidence is not reliable.",
            proof_status="needs P0 comparison",
            validation_priority="P0",
            comparison_report="person_recovery_yolo_fallback_report_2026-05-16",
            comparison_design="Clean single subject vs no-person/occluded/multi-person clip: compare verdict, skeleton rendering, relocalization, and YOLO burst budget.",
            ready_artifacts=[
                artifact("app/src/test/kotlin/com/gemmafit/video/PoseOwnershipGateTest.kt"),
                artifact("app/src/test/kotlin/com/gemmafit/video/SubjectIdentityMatcherTest.kt"),
                artifact("test_assets/videos/no_person_blank_3s.mp4"),
            ],
            acceptance_gate=[
                "No stale skeleton treated as current user.",
                "Ambiguous subject state emits LOW_CONFIDENCE/abstain, not a hard safety cue.",
                "YOLO/person fallback remains burst/recovery path, not always-on.",
            ],
            use_in_video_now="no, needs captured comparison clip",
        ),
        Segment(
            order=5,
            time_range="70-90s",
            video_ratio_bucket="20% technical highlight",
            video_content="Architecture in one line: Pose -> Motion Trace -> Evidence Card -> Local Gemma.",
            visible_demo="Fast diagram plus a real Evidence Card / summary screen.",
            technical_claim="Gemma explains structured evidence; it does not directly watch every frame and guess safety.",
            proof_status="ready",
            validation_priority="P0 evidence ready",
            comparison_report="multimodal_image_compression_reproof_2026-05-16",
            comparison_design="Raw/dense visual evidence vs compressed Evidence Panel/MotionZip sidecar field-pass and byte comparison.",
            ready_artifacts=[
                artifact("docs/benchmark/multimodal_image_compression_reproof_2026-05-16/report.md"),
                artifact("docs/benchmark/multimodal_image_compression_reproof_2026-05-16/report_video_comparison_demo.mp4"),
                artifact("docs/benchmark/motionzip_model_equivalence/report.md"),
            ],
            acceptance_gate=[
                "State that MotionZip is task-preserving evidence compression, not lossless video compression.",
                "Mention live safety remains deterministic.",
                "Use sidecar demo as technical highlight, not as live verdict proof.",
            ],
            use_in_video_now="yes",
        ),
        Segment(
            order=6,
            time_range="90-110s",
            video_ratio_bucket="20% technical highlight",
            video_content="Local Gemma / LiteRT summary and explanation.",
            visible_demo="Pixel summary screen streams or displays a local care/activity summary.",
            technical_claim="Summary/explanation runs locally and is constrained by app-owned JSON parsing and validation.",
            proof_status="needs P0 stability",
            validation_priority="P0",
            comparison_report="litert_runtime_stability_report_2026-05-16",
            comparison_design="Official LiteRT summary path vs deterministic fallback: parse success, first-token latency, memory, and failure behavior.",
            ready_artifacts=[
                artifact("docs/benchmark/pixel_demo_flow_smoke_2026-05-16/README.md"),
                artifact("docs/benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/report.md"),
                artifact("docs/benchmark/litert_model_perf_2026-05-16/report.md"),
            ],
            acceptance_gate=[
                "Local backend label visible or cited.",
                "100/100 constrained JSON parse remains a parser claim, not native tool-call claim.",
                "Fallback path is documented for unavailable/failed LiteRT.",
            ],
            use_in_video_now="yes for one controlled run; avoid claiming long-run stability until report exists",
        ),
        Segment(
            order=7,
            time_range="110-128s",
            video_ratio_bucket="20% technical highlight",
            video_content="Privacy boundary: no raw video memory; caregiver-safe export uses structured evidence.",
            visible_demo="Memory/trends or caregiver summary screen with non-clinical wording.",
            technical_claim="The app stores structured evidence and summaries, not raw video or full landmark history.",
            proof_status="needs P0 privacy audit",
            validation_priority="P0",
            comparison_report="memory_export_boundary_report_2026-05-16",
            comparison_design="Allowed structured export vs adversarial/raw/clinical payload scan.",
            ready_artifacts=[
                artifact("app/src/test/kotlin/com/gemmafit/memory/MemoryWritePolicyTest.kt"),
                artifact("app/src/test/kotlin/com/gemmafit/export/CaregiverExportBuilderTest.kt"),
                artifact("implementation_plan.md"),
            ],
            acceptance_gate=[
                "0 raw video/frame-history/full-landmark fields in export samples.",
                "Unsupported-judgment disclaimer is present.",
                "Forbidden clinical terms are rejected or absent.",
            ],
            use_in_video_now="only if report is generated or wording stays high-level",
        ),
        Segment(
            order=8,
            time_range="128-145s",
            video_ratio_bucket="10% impact",
            video_content="Senior Mode: large text, conservative cue, voice-friendly flow.",
            visible_demo="Senior home/live coach/evidence card/memory screen montage.",
            technical_claim="Senior Mode changes interaction policy and safety wording without becoming clinical.",
            proof_status="needs P1 UX capture",
            validation_priority="P1",
            comparison_report="senior_voice_accessibility_report_2026-05-16",
            comparison_design="Default UI vs Senior Mode: font scale, cue length, TTS cooldown, source labels, no text overlap.",
            ready_artifacts=[
                artifact("app/src/test/kotlin/com/gemmafit/voice/VoiceCuePolicyTest.kt"),
                artifact("app/src/test/kotlin/com/gemmafit/senior/SeniorVoiceAnswerParserTest.kt"),
                artifact("docs/benchmark/pixel_demo_flow_smoke_2026-05-16/ui_summary_screen.png"),
            ],
            acceptance_gate=[
                "TTS cooldown >= 3 seconds.",
                "No overlapping text at senior font scale.",
                "Every feedback screen shows source/limits or unsupported judgments.",
            ],
            use_in_video_now="yes as product UI, but keep technical claim light until UX report",
        ),
        Segment(
            order=9,
            time_range="145-180s",
            video_ratio_bucket="10% impact",
            video_content="Close: trustworthy home movement feedback that knows its limits.",
            visible_demo="Short recap: app running, evidence card, local summary, senior-friendly impact.",
            technical_claim="Combined impact comes from local deterministic safety + bounded Gemma explanations.",
            proof_status="script-ready",
            validation_priority="P2 wording guard",
            comparison_report="final_video_claim_check",
            comparison_design="Check every narrated claim against the validation matrix before export.",
            ready_artifacts=[
                artifact("docs/benchmark/video_demo_validation_priority_2026-05-16/report.md"),
                artifact("docs/benchmark/architecture_validation_gap_report_2026-05-16/report.md"),
            ],
            acceptance_gate=[
                "No unsupported technical claim appears in the final narration.",
                "Every wow-factor claim maps to an artifact or is phrased as future/optional.",
            ],
            use_in_video_now="yes after claim check",
        ),
    ]


def build_report_queue(segments: list[Segment]) -> list[ReportQueueItem]:
    return [
        ReportQueueItem(
            rank=1,
            report_id="live_safety_contract_report_2026-05-16",
            why_video_needs_it="This supports the 70% real demo section: skeleton/trace, warning/monitor, Evidence Card, and the claim that Gemma does not make live safety verdicts.",
            comparison_groups=[
                "LIVE_FRAME vs SESSION_ENDED backend calls",
                "Deterministic warning/monitor vs Gemma explanation",
                "Evidence Card refs vs model output refs",
            ],
            minimum_artifacts=[
                "event log with trigger, verdict, evidence_refs, backend_call_count",
                "screenshot or screen recording of warning/monitor + Evidence Card",
                "validator result showing no verdict mutation",
            ],
            done_when=[
                "LIVE_FRAME backend_call_count == 0",
                "SESSION_ENDED or USER_QUESTION may call Local Gemma after deterministic evidence exists",
                "No model output changes WARNING/MONITOR/LOW_CONFIDENCE",
            ],
            related_segments=[2, 3, 5],
        ),
        ReportQueueItem(
            rank=2,
            report_id="person_recovery_yolo_fallback_report_2026-05-16",
            why_video_needs_it="This gives the demo an abstention moment: the app looks safer because it refuses judgment when tracking is unreliable.",
            comparison_groups=[
                "clean single subject",
                "no person / blank scene",
                "occluded or multi-person ambiguity",
            ],
            minimum_artifacts=[
                "timeline of subjectObserved, subjectStable, poseConfidence, verdict",
                "screenshot/contact sheet for clean vs ambiguous cases",
                "YOLO burst count and budget if fallback is triggered",
            ],
            done_when=[
                "Ambiguous/no-person states do not emit hard warnings",
                "Stale skeleton is not shown as current evidence",
                "Recovery path is bounded and auditable",
            ],
            related_segments=[4],
        ),
        ReportQueueItem(
            rank=3,
            report_id="litert_runtime_stability_report_2026-05-16",
            why_video_needs_it="This backs the local Gemma/LiteRT part of the video without overclaiming from one successful summary screen.",
            comparison_groups=[
                "official LiteRT constrained summary",
                "deterministic fallback path",
                "warm vs reinitialized engine",
            ],
            minimum_artifacts=[
                "parse success and fallback rate",
                "first token / generate time / p95 latency",
                "meminfo and thermal/device state",
            ],
            done_when=[
                "Every output is parseable, refused, or fallbacked",
                "No crash in a controlled long-ish run",
                "Report clearly says app-side parsing/validation remains mandatory",
            ],
            related_segments=[6],
        ),
        ReportQueueItem(
            rank=4,
            report_id="memory_export_boundary_report_2026-05-16",
            why_video_needs_it="This supports privacy and caregiver-safe impact claims, which are easy to overstate in a 3-minute pitch.",
            comparison_groups=[
                "allowed structured caregiver export",
                "adversarial memory update request",
                "forbidden raw/clinical payload scan",
            ],
            minimum_artifacts=[
                "sample export JSON/text",
                "scan report for raw media/full landmarks/clinical terms",
                "MemoryWritePolicy accept/reject summary",
            ],
            done_when=[
                "0 raw video/frame-history/full-landmark fields",
                "Every trend note has evidence provenance",
                "Caregiver export includes non-clinical disclaimer",
            ],
            related_segments=[7, 9],
        ),
        ReportQueueItem(
            rank=5,
            report_id="senior_layer2_video_replay_report_2026-05-16",
            why_video_needs_it="This strengthens the senior-specific demo if the final video uses chair sit-to-stand, supported squat, or balance hold.",
            comparison_groups=[
                "clean senior activity",
                "setup transition",
                "non-senior lunge/basketball demotion",
            ],
            minimum_artifacts=[
                "event timeline with phase/activity/judgeability",
                "real-video contact sheet",
                "non-senior demotion and abstain counts",
            ],
            done_when=[
                "No hard senior judgment on non-senior clips",
                "Every senior event has evidence_refs",
                "Ambiguous context is abstain/AMBIGUOUS",
            ],
            related_segments=[2, 4, 8],
        ),
        ReportQueueItem(
            rank=6,
            report_id="camera_pipeline_long_run_report_2026-05-16",
            why_video_needs_it="This is less visible than the safety contract but prevents the demo from resting on a short happy-path camera run.",
            comparison_groups=[
                "short smoke vs long foreground run",
                "front/back and rotation variants",
                "frame timing and memory over time",
            ],
            minimum_artifacts=[
                "30s/10m timing summary",
                "meminfo/gfxinfo snapshots",
                "camera rotation screenshots or audit events",
            ],
            done_when=[
                "No sustained frame starvation",
                "No crash/memory creep beyond documented threshold",
                "Orientation and frame dimensions match the active pipeline",
            ],
            related_segments=[2, 3, 4],
        ),
    ]


def status_score(status: str) -> int:
    if status == "ready":
        return 3
    if status in {"script-ready", "P0 evidence ready"}:
        return 2
    if status.startswith("needs P1"):
        return 1
    if status.startswith("needs P0"):
        return 0
    return 1


def svg_text(x: int, y: int, value: str, size: int = 15, weight: int = 400, fill: str = "#0f172a") -> str:
    return (
        f'<text x="{x}" y="{y}" font-family="Arial, sans-serif" '
        f'font-size="{size}" font-weight="{weight}" fill="{fill}">{html.escape(value)}</text>'
    )


def write_video_timeline_svg(path: Path, segments: list[Segment]) -> None:
    colors = {
        "ready": "#16a34a",
        "script-ready": "#0f766e",
        "needs P0 comparison": "#dc2626",
        "needs P0 stability": "#dc2626",
        "needs P0 privacy audit": "#dc2626",
        "needs P1 UX capture": "#f59e0b",
    }
    body = [
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        svg_text(34, 46, "3-Minute Demo Proof Map", 26, 700),
        svg_text(34, 74, "Each visible segment must map to a comparison report or conservative wording.", 15, 400, "#475569"),
    ]
    x0 = 52
    y = 120
    total_w = 1040
    widths = [116, 104, 104, 104, 132, 132, 132, 92, 224]
    x = x0
    for segment, w in zip(segments, widths):
        color = colors.get(segment.proof_status, "#64748b")
        body.append(f'<rect x="{x}" y="{y}" width="{w}" height="82" rx="8" fill="{color}" opacity="0.95"/>')
        body.append(svg_text(x + 10, y + 24, segment.time_range, 13, 700, "#ffffff"))
        body.append(svg_text(x + 10, y + 46, f"S{segment.order}", 18, 700, "#ffffff"))
        label = segment.video_ratio_bucket.replace("% ", "%\u00a0")
        body.append(svg_text(x + 10, y + 68, label[:16], 11, 400, "#ffffff"))
        x += w + 8
    body.extend(
        [
            svg_text(52, 250, "Priority interpretation", 20, 700),
            '<rect x="52" y="278" width="24" height="24" rx="4" fill="#dc2626"/>',
            svg_text(88, 297, "P0 comparison required before using the technical claim in narration.", 15, 400, "#334155"),
            '<rect x="52" y="318" width="24" height="24" rx="4" fill="#16a34a"/>',
            svg_text(88, 337, "Ready proof exists; still phrase boundaries accurately.", 15, 400, "#334155"),
            '<rect x="52" y="358" width="24" height="24" rx="4" fill="#f59e0b"/>',
            svg_text(88, 377, "P1 UX/accessibility validation; useful but not the first safety proof blocker.", 15, 400, "#334155"),
        ]
    )
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="1160" height="420" viewBox="0 0 1160 420">\n' + "\n".join(body) + "\n</svg>\n"
    path.write_text(svg, encoding="utf-8")


def write_report_queue_svg(path: Path, queue: list[ReportQueueItem]) -> None:
    body = [
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        svg_text(34, 46, "Validation Reports To Produce First", 26, 700),
        svg_text(34, 74, "Ordered for a product-first submission video: demo proof first, architecture proof second.", 15, 400, "#475569"),
    ]
    for idx, item in enumerate(queue):
        y = 116 + idx * 72
        color = "#dc2626" if idx < 4 else "#f59e0b"
        body.append(f'<rect x="44" y="{y}" width="54" height="44" rx="8" fill="{color}"/>')
        body.append(svg_text(62, y + 29, str(item.rank), 20, 700, "#ffffff"))
        body.append(svg_text(120, y + 19, item.report_id, 15, 700, "#0f172a"))
        body.append(svg_text(120, y + 43, item.why_video_needs_it[:120], 12, 400, "#475569"))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="1180" height="585" viewBox="0 0 1180 585">\n' + "\n".join(body) + "\n</svg>\n"
    path.write_text(svg, encoding="utf-8")


def write_report(path: Path, segments: list[Segment], queue: list[ReportQueueItem]) -> None:
    lines = [
        "# Video Demo Validation Priority Report",
        "",
        "Date: 2026-05-16",
        "",
        "## Decision",
        "",
        "For the submission video, prioritize validation by what appears on screen. The video can be product-first, but every technical claim needs a comparison proof behind it. The highest-risk missing proof is not MotionZip anymore; it is the live safety contract: `LIVE_FRAME` must stay deterministic, while Gemma/LiteRT only explains or summarizes after evidence exists.",
        "",
        "## Visual Summary",
        "",
        "![3-minute proof map](video_demo_proof_map.svg)",
        "",
        "![Validation queue](validation_report_queue.svg)",
        "",
        "## Shot-To-Proof Matrix",
        "",
        "| Time | Video content | Technical claim | Proof status | Required comparison report | Use now? |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for segment in segments:
        lines.append(
            f"| {segment.time_range} | {segment.video_content} | {segment.technical_claim} | `{segment.proof_status}` | `{segment.comparison_report}` | {segment.use_in_video_now} |"
        )
    lines.extend(
        [
            "",
            "## Priority Report Queue",
            "",
            "Generate these reports in this order before locking the final narration.",
            "",
        ]
    )
    for item in queue:
        lines.extend(
            [
                f"### {item.rank}. {item.report_id}",
                "",
                item.why_video_needs_it,
                "",
                "Comparison groups:",
                "",
            ]
        )
        lines.extend(f"- {entry}" for entry in item.comparison_groups)
        lines.extend(["", "Minimum artifacts:", ""])
        lines.extend(f"- {entry}" for entry in item.minimum_artifacts)
        lines.extend(["", "Done when:", ""])
        lines.extend(f"- {entry}" for entry in item.done_when)
        lines.append("")
    lines.extend(
        [
            "## Ready-To-Use Proofs",
            "",
            "- MotionZip / sidecar difference: `docs/benchmark/multimodal_image_compression_reproof_2026-05-16/report.md` and `report_video_comparison_demo.mp4`.",
            "- Live safety preflight: `docs/benchmark/live_safety_contract_preflight_2026-05-16/report.md`.",
            "- Pixel summary flow: `docs/benchmark/pixel_demo_flow_smoke_2026-05-16/README.md`.",
            "- Constrained LiteRT JSON parse: `docs/benchmark/litert_prompt_smoke_constrained_100_official_2026-05-16/report.md`.",
            "- Layer 2 unit/device smoke: `docs/benchmark/layer2_senior_activity_ab_2026-05-16/README.md`.",
            "",
            "## Narration Rules",
            "",
            "- Say: \"Gemma explains structured evidence after the app has already decided what is judgeable.\"",
            "- Say: \"When confidence is low, GemmaFit abstains instead of guessing.\"",
            "- Say: \"Local Gemma runs on device for summaries/explanations, with app-side validation.\"",
            "- Do not say: \"Gemma watches live video and detects safety risks.\"",
            "- Do not say: \"This predicts fall risk, sarcopenia, force, load, EMG, heart rate, or clinical progress.\"",
            "",
            "## Validation Acceptance Rule",
            "",
            "A final video claim is allowed only if one of these is true:",
            "",
            "1. It maps to a generated comparison report in this folder or `docs/benchmark`.",
            "2. It is shown purely as UI behavior without a technical claim.",
            "3. It is phrased as an implementation boundary or future optional path, not a proven result.",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    segments = build_segments()
    queue = build_report_queue(segments)
    matrix = read_json(ARCH_MATRIX, [])
    payload = {
        "schema_version": "video_demo_validation_priority_v1",
        "date": "2026-05-16",
        "video_ratio": {
            "real_usage_and_function_demo": "70%",
            "technical_highlight": "20%",
            "impact": "10%",
        },
        "segments": [asdict(segment) for segment in segments],
        "report_queue": [asdict(item) for item in queue],
        "source_architecture_validation_items": len(matrix),
    }
    (OUT_DIR / "video_demo_validation_matrix.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    write_video_timeline_svg(OUT_DIR / "video_demo_proof_map.svg", segments)
    write_report_queue_svg(OUT_DIR / "validation_report_queue.svg", queue)
    write_report(OUT_DIR / "report.md", segments, queue)
    print(json.dumps({"out_dir": str(OUT_DIR), "report": str(OUT_DIR / "report.md")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
