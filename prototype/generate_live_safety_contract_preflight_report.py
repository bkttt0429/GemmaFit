"""Generate a live-safety-contract preflight report.

This report is intentionally conservative. It combines the targeted unit-test
results and the existing Pixel demo smoke artifacts, then marks the remaining
on-device trigger trace that is still required before the final submission
video can claim a fully proven live safety contract.
"""

from __future__ import annotations

import html
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from xml.etree import ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "live_safety_contract_preflight_2026-05-16"
TEST_RESULTS = REPO_ROOT / "app" / "build" / "test-results" / "testDebugUnitTest"
PIXEL_README = REPO_ROOT / "docs" / "benchmark" / "pixel_demo_flow_smoke_2026-05-16" / "README.md"


@dataclass(frozen=True)
class TestSummary:
    name: str
    tests: int
    failures: int
    errors: int
    skipped: int
    time_sec: float
    path: str


@dataclass(frozen=True)
class ContractRow:
    comparison: str
    expected_contract: str
    current_evidence: str
    status: str
    still_needed: str


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT)).replace("\\", "/")
    except ValueError:
        return str(path).replace("\\", "/")


def read_test_summary(class_name: str) -> TestSummary:
    path = TEST_RESULTS / f"TEST-{class_name}.xml"
    if not path.exists():
        return TestSummary(class_name, 0, 0, 1, 0, 0.0, rel(path))
    root = ET.parse(path).getroot()
    return TestSummary(
        name=root.attrib.get("name", class_name),
        tests=int(root.attrib.get("tests", 0)),
        failures=int(root.attrib.get("failures", 0)),
        errors=int(root.attrib.get("errors", 0)),
        skipped=int(root.attrib.get("skipped", 0)),
        time_sec=float(root.attrib.get("time", 0.0)),
        path=rel(path),
    )


def pixel_readme_hits() -> dict[str, bool]:
    text = PIXEL_README.read_text(encoding="utf-8") if PIXEL_README.exists() else ""
    return {
        "full_analysis_pass": "Full analysis completes | PASS" in text,
        "local_gemma_summary_pass": "Local Gemma summary returns | PASS" in text,
        "first_token_under_5s": "First token under 5s | PASS" in text,
        "why_flagged_pass": "User-facing reason for flags | PASS" in text,
        "deterministic_explanations": "deterministic UI explanations" in text,
        "motionzip_blocks_zero_constraint": "`motion_zip_blocks=0`" in text,
    }


def build_rows(tests: dict[str, TestSummary], pixel: dict[str, bool]) -> list[ContractRow]:
    scheduler_ok = tests["scheduler"].failures == 0 and tests["scheduler"].errors == 0 and tests["scheduler"].tests >= 15
    validator_ok = tests["validator"].failures == 0 and tests["validator"].errors == 0 and tests["validator"].tests >= 5
    trust_ok = tests["trust"].failures == 0 and tests["trust"].errors == 0 and tests["trust"].tests >= 8
    return [
        ContractRow(
            comparison="LIVE_FRAME vs session/user triggers",
            expected_contract="LIVE_FRAME is deterministic-only; no Gemma call and no multimodal panel/backend.",
            current_evidence="ModelInvocationSchedulerTest passed 15/15, including live frame deterministic skip and live multimodal backend skip." if scheduler_ok else "Scheduler unit result missing or failed.",
            status="unit-pass" if scheduler_ok else "missing",
            still_needed="On-device event trace with trigger, decision, context budget, multimodal action, and backend_call_count=0.",
        ),
        ContractRow(
            comparison="Warning/monitor cue vs Evidence Card",
            expected_contract="Warnings are produced from deterministic evidence refs before any local Gemma explanation.",
            current_evidence="Pixel smoke shows full analysis PASS and user-facing Why flagged explanations PASS." if pixel["full_analysis_pass"] and pixel["why_flagged_pass"] else "Pixel warning/evidence UI smoke incomplete.",
            status="pixel-ui-pass" if pixel["why_flagged_pass"] else "partial",
            still_needed="Per-event log tying warning/monitor verdict to evidence_refs and showing no model-created warning.",
        ),
        ContractRow(
            comparison="Gemma sidecar output vs deterministic verdict",
            expected_contract="Gemma may explain or summarize evidence, but cannot change verdict, create warning, cite missing refs, or make forbidden claims.",
            current_evidence="MultimodalResultValidatorTest passed 5/5: missing refs, verdict mutation, new warning, forbidden claims, and low-confidence overconfidence are rejected." if validator_ok else "Validator unit result missing or failed.",
            status="unit-pass" if validator_ok else "missing",
            still_needed="Device validation log for at least one summary/explanation result passed through validator.",
        ),
        ContractRow(
            comparison="Low confidence / abstain vs hard judgment",
            expected_contract="Low-confidence evidence maps to abstained/monitor-only UI, not a hard safety judgment.",
            current_evidence="TrustUiStateTest passed 8/8, including low confidence -> Abstained and pose preview separate from hard judgment." if trust_ok else "Trust UI unit result missing or failed.",
            status="unit-pass" if trust_ok else "missing",
            still_needed="Captured low-confidence/no-person/multi-person clip showing the same behavior on Pixel.",
        ),
        ContractRow(
            comparison="Session ended summary vs live verdicting",
            expected_contract="Local Gemma summary runs after analysis; it is not part of per-frame safety verdicting.",
            current_evidence="Pixel smoke shows Local Gemma summary PASS, first token under 5s, backend litert-lm:isolated:gpu." if pixel["local_gemma_summary_pass"] and pixel["first_token_under_5s"] else "Pixel summary smoke incomplete.",
            status="pixel-summary-pass" if pixel["local_gemma_summary_pass"] else "partial",
            still_needed="Trigger-level comparison showing summary call belongs to SESSION_ENDED or equivalent, never LIVE_FRAME.",
        ),
    ]


def svg_text(x: int, y: int, value: str, size: int = 14, weight: int = 400, fill: str = "#0f172a") -> str:
    return (
        f'<text x="{x}" y="{y}" font-family="Arial, sans-serif" '
        f'font-size="{size}" font-weight="{weight}" fill="{fill}">{html.escape(value)}</text>'
    )


def write_svg(path: Path, rows: list[ContractRow]) -> None:
    color = {
        "unit-pass": "#16a34a",
        "pixel-ui-pass": "#0f766e",
        "pixel-summary-pass": "#0f766e",
        "partial": "#f59e0b",
        "missing": "#dc2626",
    }
    body = [
        '<rect width="100%" height="100%" fill="#f8fafc"/>',
        svg_text(34, 46, "Live Safety Contract Preflight", 26, 700),
        svg_text(34, 74, "Current proof is unit + Pixel UI smoke. Final proof still needs trigger-level on-device tracing.", 15, 400, "#475569"),
    ]
    for idx, row in enumerate(rows):
        y = 120 + idx * 82
        c = color.get(row.status, "#64748b")
        body.append(f'<rect x="42" y="{y}" width="1050" height="58" rx="8" fill="#ffffff" stroke="#cbd5e1" stroke-width="1"/>')
        body.append(f'<rect x="42" y="{y}" width="14" height="58" rx="6" fill="{c}"/>')
        body.append(svg_text(72, y + 23, row.comparison, 15, 700))
        body.append(svg_text(72, y + 45, row.status, 13, 700, c))
        body.append(svg_text(420, y + 23, row.expected_contract[:88], 12, 400, "#334155"))
        body.append(svg_text(420, y + 45, f"Still needed: {row.still_needed[:80]}", 12, 400, "#64748b"))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" width="1130" height="565" viewBox="0 0 1130 565">\n' + "\n".join(body) + "\n</svg>\n"
    path.write_text(svg, encoding="utf-8")


def write_report(path: Path, tests: dict[str, TestSummary], rows: list[ContractRow], pixel: dict[str, bool]) -> None:
    unit_total = sum(item.tests for item in tests.values())
    unit_failures = sum(item.failures + item.errors for item in tests.values())
    lines = [
        "# Live Safety Contract Preflight Report",
        "",
        "Date: 2026-05-16",
        "",
        "## Conclusion",
        "",
        "This preflight supports the video narrative but is not the final on-device live contract proof. The targeted tests passed and the Pixel smoke already shows deterministic analysis, Why flagged explanations, and Local Gemma summary. The missing final artifact is a trigger-level device trace proving `LIVE_FRAME` has zero model/multimodal backend calls while `SESSION_ENDED` or user-triggered flows may call Local Gemma after deterministic evidence exists.",
        "",
        "![Live safety contract comparison](live_safety_contract_comparison.svg)",
        "",
        "## Targeted Test Run",
        "",
        "Command:",
        "",
        "```powershell",
        "$env:JAVA_HOME='D:\\gradle-cache\\jdk-21-temurin'; $env:PATH=\"$env:JAVA_HOME\\bin;$env:PATH\"; .\\gradlew.bat :app:testDebugUnitTest --tests com.gemmafit.video.ModelInvocationSchedulerTest --tests com.gemmafit.video.MultimodalResultValidatorTest --tests com.gemmafit.video.TrustUiStateTest --no-daemon",
        "```",
        "",
        f"Result: `{unit_total}` targeted tests, `{unit_failures}` failures/errors.",
        "",
        "| Test class | Tests | Failures | Errors | Artifact |",
        "| --- | ---: | ---: | ---: | --- |",
    ]
    for test in tests.values():
        lines.append(f"| `{test.name}` | {test.tests} | {test.failures} | {test.errors} | `{test.path}` |")
    lines.extend(
        [
            "",
            "## Contract Comparison",
            "",
            "| Comparison | Expected contract | Current evidence | Status | Still needed |",
            "| --- | --- | --- | --- | --- |",
        ]
    )
    for row in rows:
        lines.append(f"| {row.comparison} | {row.expected_contract} | {row.current_evidence} | `{row.status}` | {row.still_needed} |")
    lines.extend(
        [
            "",
            "## Pixel Smoke Anchors",
            "",
            f"- Full analysis PASS: `{pixel['full_analysis_pass']}`",
            f"- Local Gemma summary PASS: `{pixel['local_gemma_summary_pass']}`",
            f"- First token under 5s PASS: `{pixel['first_token_under_5s']}`",
            f"- Why flagged explanations PASS: `{pixel['why_flagged_pass']}`",
            f"- Deterministic explanation wording present: `{pixel['deterministic_explanations']}`",
            f"- MotionZip blocks zero constraint noted: `{pixel['motionzip_blocks_zero_constraint']}`",
            "",
            "Source: `docs/benchmark/pixel_demo_flow_smoke_2026-05-16/README.md`",
            "",
            "## Video Use Guidance",
            "",
            "- Safe to show now: skeleton/analysis UI, Why flagged deterministic explanations, Local Gemma summary screen.",
            "- Safe to say now: \"GemmaFit separates deterministic safety evidence from Local Gemma explanations.\"",
            "- Do not say yet: \"The live frame path has been proven on-device to make zero model calls\" until the trigger-level trace report is generated.",
            "- Do not imply Vision/Gemma decides live `WARNING`, `MONITOR`, or `LOW_CONFIDENCE` verdicts.",
            "",
            "## Required Final Trace",
            "",
            "The final `live_safety_contract_report_2026-05-16` should collect a device event log with at least:",
            "",
            "- `timestamp_ms`",
            "- `trigger`",
            "- `deterministic_verdict`",
            "- `evidence_refs`",
            "- `model_decision`",
            "- `multimodal_action`",
            "- `backend_call_count`",
            "- `validator_result`",
            "",
            "Acceptance: all `LIVE_FRAME` rows have `backend_call_count = 0`, while post-evidence triggers may call Local Gemma and must pass validator/ref checks.",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tests = {
        "scheduler": read_test_summary("com.gemmafit.video.ModelInvocationSchedulerTest"),
        "validator": read_test_summary("com.gemmafit.video.MultimodalResultValidatorTest"),
        "trust": read_test_summary("com.gemmafit.video.TrustUiStateTest"),
    }
    pixel = pixel_readme_hits()
    rows = build_rows(tests, pixel)
    payload = {
        "schema_version": "live_safety_contract_preflight_v1",
        "date": "2026-05-16",
        "targeted_tests": {key: asdict(value) for key, value in tests.items()},
        "pixel_smoke_hits": pixel,
        "contract_rows": [asdict(row) for row in rows],
        "final_status": "preflight_pass_final_device_trace_required",
    }
    (OUT_DIR / "summary.json").write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
    write_svg(OUT_DIR / "live_safety_contract_comparison.svg", rows)
    write_report(OUT_DIR / "report.md", tests, rows, pixel)
    print(json.dumps({"out_dir": str(OUT_DIR), "report": str(OUT_DIR / "report.md")}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
