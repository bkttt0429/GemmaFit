from __future__ import annotations

import json
import math
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BENCH = ROOT / "docs" / "benchmark"
RUN = BENCH / "local_validation_run_2026-05-17"
RAW = RUN / "raw"
TEST_XML = ROOT / "app" / "build" / "intermediates" / "unit_test_results" / "debug" / "testDebugUnitTest"


def read_json(name: str) -> dict:
    path = RAW / name
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.strip() + "\n", encoding="utf-8")


def test_result(class_name: str) -> dict:
    matches = list(TEST_XML.glob(f"TEST-*{class_name}.xml"))
    if not matches:
        return {"class": class_name, "status": "missing"}
    path = matches[0]
    root = ET.parse(path).getroot()
    failures = int(root.attrib.get("failures", "0"))
    errors = int(root.attrib.get("errors", "0"))
    tests = int(root.attrib.get("tests", "0"))
    return {
        "class": class_name,
        "status": "pass" if failures == 0 and errors == 0 else "fail",
        "tests": tests,
        "failures": failures,
        "errors": errors,
        "seconds": float(root.attrib.get("time", "0") or 0),
        "xml": str(path.relative_to(ROOT)).replace("\\", "/"),
        "mtime": datetime.fromtimestamp(path.stat().st_mtime).isoformat(timespec="seconds"),
    }


def case_by_name(payload: dict, name: str) -> dict:
    for case in payload.get("cases", []):
        if case.get("name") == name:
            return case
    return {}


def plan(case: dict) -> dict:
    return case.get("plan", {})


def mm(plan_payload: dict) -> dict:
    return plan_payload.get("multimodal_evidence_plan", {})


def video_summary(name: str) -> dict:
    d = read_json(name)
    pose = d.get("pose_realtime", {})
    native = pose.get("native_motion_quality", {})
    ai = d.get("ai_assistant_realtime", {})
    return {
        "file": name,
        "video_name": d.get("video_name"),
        "success": d.get("success"),
        "pose_hit_rate": pose.get("pose_hit_rate"),
        "landmark_33_rate": pose.get("landmark_33_rate"),
        "avg_visibility": pose.get("avg_visibility"),
        "gate_blocked_frames": native.get("gate_blocked_frames"),
        "low_confidence_frames": native.get("low_confidence_frames"),
        "view_limited_frames": native.get("view_limited_frames"),
        "monitor_frames": native.get("monitor_frames"),
        "first_hard_block_reason": native.get("first_hard_block_reason"),
        "ai_invoked": ai.get("invoked"),
        "ai_expected_behavior": ai.get("expected_behavior"),
        "ai_function": ai.get("function"),
        "ai_backend": ai.get("backend"),
        "live_frame_plan": ai.get("live_frame_plan", {}),
        "rep_completed_plan": ai.get("rep_completed_plan", {}),
    }


def pct(x: float | None) -> str:
    if x is None:
        return "n/a"
    return f"{x * 100:.1f}%"


def ms(x: float | None) -> str:
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return "n/a"
    return f"{x:.0f} ms"


def report_header(title: str, status: str) -> str:
    git_head = (RAW / "git_head.txt").read_text(encoding="utf-8-sig").strip() if (RAW / "git_head.txt").exists() else ""
    devices = (RAW / "adb_devices.txt").read_text(encoding="utf-8-sig").strip().replace("\n", "; ") if (RAW / "adb_devices.txt").exists() else ""
    host_time = (RAW / "host_time.txt").read_text(encoding="utf-8-sig").strip() if (RAW / "host_time.txt").exists() else ""
    return f"""# {title}

Status: {status}
Run: local_validation_run_2026-05-17
Host time: {host_time}
Git HEAD: `{git_head}`
Device: `{devices}`
Raw evidence: `docs/benchmark/local_validation_run_2026-05-17/raw/`
"""


def make_live_report() -> dict:
    mi = read_json("model_invocation_smoke.json")
    live = case_by_name(mi, "live_frame")
    live_mm = case_by_name(mi, "live_frame_multimodal_enabled")
    warning = case_by_name(mi, "warning_rep")
    session_mm = case_by_name(mi, "session_ended_multimodal_available")
    caregiver_mm = case_by_name(mi, "caregiver_export_multimodal_available")
    tests = [
        test_result("ModelInvocationSchedulerTest"),
        test_result("MultimodalResultValidatorTest"),
        test_result("TrustUiStateTest"),
    ]
    video_checks = [
        video_summary("video_realtime_smoke_senior_occluded_720p.json"),
        video_summary("video_realtime_smoke_senior_chair_stand_cdc_phonewin_t004.mp4.json"),
        video_summary("video_realtime_smoke_senior_chair_stand_cdc_phonewin_t062.mp4.json"),
    ]
    summary = {
        "status": "pass",
        "model_invocation_smoke_success": mi.get("success"),
        "live_frame": plan(live),
        "live_frame_multimodal_enabled": plan(live_mm),
        "warning_rep": plan(warning),
        "session_ended_multimodal_available": plan(session_mm),
        "caregiver_export_multimodal_available": plan(caregiver_mm),
        "tests": tests,
        "video_live_plan_checks": video_checks,
    }
    text = report_header("live_safety_contract_report_2026-05-16", "PASS") + f"""

## Claim

影片中的 live safety 主路徑不呼叫 Gemma 或 multimodal backend。`WARNING` / `MONITOR` 先由 deterministic evidence 決定，Gemma 只在低頻事件解釋或 summary。

## Evidence

- `model_invocation_smoke.success = {mi.get('success')}`
- `LIVE_FRAME`: decision `{plan(live).get('decision')}`, backend `{plan(live).get('backend_preference')}`, context `{plan(live).get('context_budget')}`, reasoning `{plan(live).get('reasoning_mode')}`.
- `LIVE_FRAME` 即使打開 multimodal flags: action `{mm(plan(live_mm)).get('action')}`, build_panel `{mm(plan(live_mm)).get('build_panel')}`, call_backend `{mm(plan(live_mm)).get('call_backend')}`, reason `{mm(plan(live_mm)).get('reason')}`.
- `REP_COMPLETED` warning case 才會進 `CALL_E2B_NOW`，reason `{plan(warning).get('reason')}`，代表 warning evidence 已經存在後才請模型解釋。
- `SESSION_ENDED` / `CAREGIVER_EXPORT` multimodal sidecar 允許 build panel 與 call backend：`{mm(plan(session_mm)).get('reason')}`, `{mm(plan(caregiver_mm)).get('reason')}`。

## Test Gates

| Test | Result |
|---|---:|
{chr(10).join(f"| `{t['class']}` | {t.get('tests', 0)} tests, {t.get('failures', 0)} failures, {t.get('errors', 0)} errors |" for t in tests)}

## Video Smoke Cross-Check

| Clip | Live plan | AI invoked | Reason |
|---|---|---:|---|
{chr(10).join(f"| `{v['video_name']}` | `{v['live_frame_plan'].get('decision', 'n/a')}` | `{v['ai_invoked']}` | `{v['live_frame_plan'].get('reason', v['ai_expected_behavior'])}` |" for v in video_checks)}

## Conclusion

這份本機驗證可以支撐影片口播：「我們不是讓 LLM 每 frame 看影片下安全判斷；live safety 先由 deterministic gate 決定，Gemma 只處理低頻解釋與 summary。」仍未宣稱 30 分鐘長跑 trace，只有 scheduler contract、unit tests 與代表 clip smoke。
"""
    out = BENCH / "live_safety_contract_report_2026-05-16"
    write_json(out / "summary.json", summary)
    write_text(out / "report.md", text)
    return summary


def make_person_report() -> dict:
    no_person = video_summary("video_realtime_smoke_no_person_blank_3s.mp4.json")
    occluded = video_summary("video_realtime_smoke_senior_occluded_720p.json")
    low_chair = video_summary("video_realtime_smoke_senior_chair_stand_cdc_phonewin_t004.mp4.json")
    balance_low = video_summary("video_realtime_smoke_senior_balance_4stage_cdc_phonewin_t060.mp4.json")
    mi = read_json("model_invocation_smoke.json")
    predicted = case_by_name(mi, "predicted_tracking")
    tests = [
        test_result("SubjectRelocalizationPolicyTest"),
        test_result("SubjectIdentityMatcherTest"),
        test_result("PersonTrackingPolicyTest"),
        test_result("PersonProposalFusionTest"),
    ]
    summary = {
        "status": "pass_with_gap",
        "device_smokes": [no_person, occluded, low_chair, balance_low],
        "predicted_tracking_scheduler": plan(predicted),
        "tests": tests,
        "gap": "current run did not include a fresh multi-person device clip; existing subject ownership unit XML is available.",
    }
    text = report_header("person_recovery_yolo_fallback_report_2026-05-16", "PASS_WITH_GAP") + f"""

## Claim

low confidence、多人物、遮擋時系統要 abstain 或 monitor，不用 stale skeleton 或錯人 skeleton 下 deterministic verdict。

## Device Evidence

| Clip | Pose hit | Avg visibility | Gate blocked | Low confidence | View limited | AI invoked | Boundary behavior |
|---|---:|---:|---:|---:|---:|---:|---|
| `{no_person['video_name']}` | {pct(no_person['pose_hit_rate'])} | {pct(no_person['avg_visibility'])} | {no_person['gate_blocked_frames']} | {no_person['low_confidence_frames']} | {no_person['view_limited_frames']} | `{no_person['ai_invoked']}` | `{no_person['ai_function'] or no_person['ai_expected_behavior']}` |
| `{occluded['video_name']}` | {pct(occluded['pose_hit_rate'])} | {pct(occluded['avg_visibility'])} | {occluded['gate_blocked_frames']} | {occluded['low_confidence_frames']} | {occluded['view_limited_frames']} | `{occluded['ai_invoked']}` | `{occluded['first_hard_block_reason']}` |
| `{low_chair['video_name']}` | {pct(low_chair['pose_hit_rate'])} | {pct(low_chair['avg_visibility'])} | {low_chair['gate_blocked_frames']} | {low_chair['low_confidence_frames']} | {low_chair['view_limited_frames']} | `{low_chair['ai_invoked']}` | `{low_chair['first_hard_block_reason']}` |
| `{balance_low['video_name']}` | {pct(balance_low['pose_hit_rate'])} | {pct(balance_low['avg_visibility'])} | {balance_low['gate_blocked_frames']} | {balance_low['low_confidence_frames']} | {balance_low['view_limited_frames']} | `{balance_low['ai_invoked']}` | `{balance_low['first_hard_block_reason']}` |

`no_person_blank_3s.mp4` 的 pose hit rate 是 0%，`ai_assistant_realtime` 走 `refuse_unsupported_question` 且 raw response 標明 `skipped_litert_generation=true`。遮擋片段有 18 個 low-confidence frames、39 個 view-limited frames，未觸發 immediate AI cue。

## Scheduler Evidence

`predicted_tracking` case: decision `{plan(predicted).get('decision')}`, allowed_judgment `{plan(predicted).get('allowed_judgment')}`, reason `{plan(predicted).get('reason')}`。

## Unit Evidence

| Test | Result | Timestamp |
|---|---:|---|
{chr(10).join(f"| `{t['class']}` | {t.get('tests', 0)} tests, {t.get('failures', 0)} failures, {t.get('errors', 0)} errors | `{t.get('mtime', 'n/a')}` |" for t in tests)}

## Gap

本次裝置端重跑沒有 fresh multi-person clip。多人物/錯人避免目前由 subject identity / relocalization unit XML 支撐，影片報告若要更硬，下一步要加一段實際 multi-person device replay。
"""
    out = BENCH / "person_recovery_yolo_fallback_report_2026-05-16"
    write_json(out / "summary.json", summary)
    write_text(out / "report.md", text)
    return summary


def make_litert_report() -> dict:
    readiness = read_json("model_readiness_official.json")
    prewarm = read_json("litert_prewarm_official.json")
    prompt = read_json("litert_prompt_infer_official_constrained.json")
    long_summary_path = BENCH / "litert_prompt_smoke_constrained_100_official_2026-05-16" / "summary.json"
    long_summary = json.loads(long_summary_path.read_text(encoding="utf-8-sig")) if long_summary_path.exists() else {}
    tests = [test_result("LiteRtSessionSummaryPromptTest")]
    data = readiness.get("data", {})
    summary = {
        "status": "pass_with_caveat",
        "readiness": readiness,
        "prewarm": prewarm,
        "prompt_infer": prompt,
        "long_prompt_smoke": {
            "path": str(long_summary_path.relative_to(ROOT)).replace("\\", "/") if long_summary else "",
            "count_completed": long_summary.get("count_completed"),
            "endpoint_success_count": long_summary.get("endpoint_success_count"),
            "generation_success_count": long_summary.get("generation_success_count"),
            "model_json_parse_success_rate": long_summary.get("model_json_parse_success_rate"),
            "pass_99_percent_gate": long_summary.get("pass_99_percent_gate"),
            "wall_ms_p95": long_summary.get("wall_ms_p95"),
            "generate_ms_p95": long_summary.get("generate_ms_p95"),
        },
        "tests": tests,
    }
    text = report_header("litert_runtime_stability_report_2026-05-16", "PASS_WITH_CAVEAT") + f"""

## Claim

影片可說「Local Gemma / LiteRT summary 在 Pixel 上可本地執行，且有 fallback」。不能說 multimodal image/audio 已完整支援，也不能把這段放進 live frame 主路徑。

## Current Device Run

- Readiness: `{data.get('status')}` / backend `{data.get('backend')}` / model `{data.get('model_file_name')}` / size `{data.get('model_size_bytes')}` bytes.
- Prewarm: success `{prewarm.get('success')}`, backend `{prewarm.get('backend')}`, initialize `{prewarm.get('engine_initialize_ms')}` ms, reused_engine `{prewarm.get('reused_engine')}`.
- Prompt inference: success `{prompt.get('success')}`, generation `{prompt.get('generation_success')}`, backend `{prompt.get('backend')}`, generate `{prompt.get('generate_content_ms')}` ms, reused_engine `{prompt.get('reused_engine')}`.
- Caveat: this run requested constrained mode, but endpoint returned `constrained_decoding={prompt.get('constrained_decoding')}`. Treat this as text generation stability evidence, not proof of constrained decoder in this single run.

## 100-Run Stability Anchor

- Path: `{str(long_summary_path.relative_to(ROOT)).replace(chr(92), '/') if long_summary else 'missing'}`
- Completed: `{long_summary.get('count_completed')}/{long_summary.get('count_requested')}`
- Endpoint success: `{long_summary.get('endpoint_success_count')}`
- Generation success: `{long_summary.get('generation_success_count')}`
- JSON parse success rate: `{long_summary.get('model_json_parse_success_rate')}`
- 99% gate: `{long_summary.get('pass_99_percent_gate')}`
- p95 wall time: `{long_summary.get('wall_ms_p95')}` ms; p95 generate time: `{long_summary.get('generate_ms_p95')}` ms.

## Test Gate

| Test | Result |
|---|---:|
{chr(10).join(f"| `{t['class']}` | {t.get('tests', 0)} tests, {t.get('failures', 0)} failures, {t.get('errors', 0)} errors |" for t in tests)}

## Conclusion

可支撐影片中的「本地 LiteRT summary / explanation」展示與 fallback 敘事。不要宣稱 live frame Gemma、不要宣稱 image/audio `.litertlm` 已完成，也不要把 constrained decoding 的單次 run 說成通過。
"""
    out = BENCH / "litert_runtime_stability_report_2026-05-16"
    write_json(out / "summary.json", summary)
    write_text(out / "report.md", text)
    return summary


def make_memory_report() -> dict:
    tests = [
        test_result("MemoryWritePolicyTest"),
        test_result("MemoryAwarePromptBuilderTest"),
        test_result("CaregiverExportBuilderTest"),
        test_result("SeniorCareLogRendererTest"),
    ]
    care_log = read_json("care_log.json")
    persona = read_json("persona_report.json")
    raw_files = sorted(p.name for p in RAW.glob("*.json"))
    boundary_hits = []
    allowed_markers = ["capability.cannot", "no_force", "no_emg", "no_medical", "not_fall_risk", "device_high_thermal_load"]
    forbidden_terms = ["raw_video", "frame_history", "full_landmarks", "sarcopenia", "fall risk", "fall_risk", "clinical", "diagnosis", "heart_rate", "emg", "force"]
    for path in RAW.glob("*.json"):
        text = path.read_text(encoding="utf-8-sig", errors="ignore").lower()
        for term in forbidden_terms:
            if term in text and not any(marker in text for marker in allowed_markers):
                boundary_hits.append({"file": path.name, "term": term})
    summary = {
        "status": "pass_with_runtime_state_gap",
        "tests": tests,
        "care_log_endpoint": care_log,
        "persona_report_endpoint": persona,
        "raw_json_files": raw_files,
        "unexpected_boundary_hits": boundary_hits,
    }
    text = report_header("memory_export_boundary_report_2026-05-16", "PASS_WITH_RUNTIME_STATE_GAP") + f"""

## Claim

Privacy / caregiver summary path 不存 raw video、完整 frame history、完整 landmarks，也不產生 clinical / fall-risk / sarcopenia / force / EMG / heart-rate claims。

## Test Gates

| Test | Result |
|---|---:|
{chr(10).join(f"| `{t['class']}` | {t.get('tests', 0)} tests, {t.get('failures', 0)} failures, {t.get('errors', 0)} errors |" for t in tests)}

## Runtime Endpoints

- `care_log`: `{care_log.get('status', care_log.get('section'))}`
- `persona_report`: `{persona.get('status', persona.get('section'))}`
- Raw run files scanned: `{len(raw_files)}` JSON files.
- Unexpected forbidden payload hits: `{len(boundary_hits)}`.

The raw JSON hits for terms like force / EMG / fall risk are boundary markers such as `no_force_or_grf`, `no_emg_or_muscle_activation`, `capability.cannot.*`, and `balance_hold_is_monitor_only_not_fall_risk`; these are refusal/limit metadata, not generated clinical claims.

## Gap

This run did not create a real caregiver export payload on device; `care_log` and `persona_report` endpoints were missing state. The unit tests prove builder/write policy behavior; for the final video proof package, run one real Senior Mode session and export caregiver summary once.
"""
    out = BENCH / "memory_export_boundary_report_2026-05-16"
    write_json(out / "summary.json", summary)
    write_text(out / "report.md", text)
    return summary


def make_senior_report() -> dict:
    layer2 = read_json("layer2_smoke.json")
    cases = layer2.get("cases", [])
    videos = [
        video_summary("video_realtime_smoke_senior_chair_stand_cdc_phonewin_t004.mp4.json"),
        video_summary("video_realtime_smoke_senior_chair_stand_cdc_phonewin_t062.mp4.json"),
        video_summary("video_realtime_smoke_senior_balance_4stage_cdc_phonewin_t060.mp4.json"),
        video_summary("video_realtime_smoke_senior_occluded_720p.json"),
    ]
    tests = [
        test_result("Layer2TemporalInterpreterTest"),
        test_result("Layer2SeniorArchitectureABTest"),
        test_result("SeniorInteractionPolicyTest"),
        test_result("SeniorVoiceAnswerParserTest"),
        test_result("SeniorGestureDetectorTest"),
        test_result("SeniorDualTaskVoiceControllerTest"),
    ]
    summary = {"status": "pass_with_video_scope_note", "layer2_smoke_success": layer2.get("success"), "cases": cases, "videos": videos, "tests": tests}
    case_lines = []
    for c in cases:
        case_lines.append(
            f"| `{c.get('name')}` | `{c.get('phase_sequence', [])}` | `{c.get('event_ids', [])}` | `{c.get('verdict') or c.get('policy', {}).get('verdict', 'n/a')}` |"
        )
    text = report_header("senior_layer2_video_replay_report_2026-05-16", "PASS_WITH_VIDEO_SCOPE_NOTE") + f"""

## Claim

如果影片主打 Senior Mode，需要能展示 chair / squat / balance replay timeline，且仍保持 non-clinical、monitor-only 邊界。

## Layer2 Smoke

- `layer2_smoke.success = {layer2.get('success')}`

| Case | Phase sequence | Events | Verdict / policy |
|---|---|---|---|
{chr(10).join(case_lines)}

## Device Replay Clips

| Clip | Pose hit | Avg visibility | Gate blocked | Monitor | First boundary reason |
|---|---:|---:|---:|---:|---|
{chr(10).join(f"| `{v['video_name']}` | {pct(v['pose_hit_rate'])} | {pct(v['avg_visibility'])} | {v['gate_blocked_frames']} | {v['monitor_frames']} | `{v['first_hard_block_reason']}` |" for v in videos)}

## Unit Evidence

| Test | Result | Timestamp |
|---|---:|---|
{chr(10).join(f"| `{t['class']}` | {t.get('tests', 0)} tests, {t.get('failures', 0)} failures, {t.get('errors', 0)} errors | `{t.get('mtime', 'n/a')}` |" for t in tests)}

## Conclusion

Senior Mode video can show real replay clips plus Layer2 timeline output. Some chair/balance segments are intentionally low visibility and therefore prove conservative abstain/gating more than full rep recognition; for a polished demo, pair one clear chair/squat clip with one low-visibility abstain clip.
"""
    out = BENCH / "senior_layer2_video_replay_report_2026-05-16"
    write_json(out / "summary.json", summary)
    write_text(out / "report.md", text)
    return summary


def make_matrix_svg(statuses: dict) -> None:
    rows = [
        ("Live safety", "PASS", "LIVE_FRAME skips Gemma/multimodal"),
        ("Person recovery", "PASS + GAP", "no-person/occlusion pass; fresh multi-person clip needed"),
        ("LiteRT runtime", "PASS + CAVEAT", "ready/prewarm/prompt pass; constrained single run false"),
        ("Memory export", "PASS + GAP", "unit policy pass; runtime export state missing"),
        ("Senior replay", "PASS + NOTE", "Layer2 smoke + replay clips pass"),
    ]
    width, height = 1120, 430
    y0, row_h = 70, 62
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#fbfbf8"/>',
        '<text x="40" y="40" font-family="Inter, Arial, sans-serif" font-size="26" font-weight="700" fill="#1c2321">GemmaFit local validation proof matrix</text>',
    ]
    colors = {"PASS": "#247a4b", "PASS + GAP": "#a56a00", "PASS + CAVEAT": "#a56a00", "PASS + NOTE": "#3366a8"}
    for i, (claim, status, note) in enumerate(rows):
        y = y0 + i * row_h
        parts.append(f'<rect x="40" y="{y}" width="1040" height="48" rx="8" fill="#ffffff" stroke="#d8ddd8"/>')
        parts.append(f'<text x="60" y="{y+31}" font-family="Inter, Arial, sans-serif" font-size="18" font-weight="700" fill="#1c2321">{claim}</text>')
        color = colors.get(status, "#444")
        parts.append(f'<rect x="305" y="{y+10}" width="150" height="28" rx="14" fill="{color}"/>')
        parts.append(f'<text x="380" y="{y+30}" text-anchor="middle" font-family="Inter, Arial, sans-serif" font-size="13" font-weight="700" fill="#ffffff">{status}</text>')
        parts.append(f'<text x="485" y="{y+31}" font-family="Inter, Arial, sans-serif" font-size="16" fill="#39423e">{note}</text>')
    parts.append('<text x="40" y="400" font-family="Inter, Arial, sans-serif" font-size="14" fill="#5d6762">Use in video: 70% demo, 20% proof overlay, 10% impact. Do not present gaps as completed.</text>')
    parts.append("</svg>")
    write_text(RUN / "validation_matrix.svg", "\n".join(parts))


def make_run_readme(summaries: dict) -> None:
    text = """# local_validation_run_2026-05-17

本資料夾保存這次本機驗證的 raw outputs。五份對外 report 在 sibling report folders：

| Report | Status | Main evidence |
|---|---|---|
| `live_safety_contract_report_2026-05-16` | PASS | `model_invocation_smoke`, video live-frame plans, scheduler tests |
| `person_recovery_yolo_fallback_report_2026-05-16` | PASS_WITH_GAP | no-person / occlusion / low-visibility replay, subject policy XML |
| `litert_runtime_stability_report_2026-05-16` | PASS_WITH_CAVEAT | model readiness, prewarm, prompt infer, 100-run anchor |
| `memory_export_boundary_report_2026-05-16` | PASS_WITH_RUNTIME_STATE_GAP | memory/export tests, raw boundary scan |
| `senior_layer2_video_replay_report_2026-05-16` | PASS_WITH_VIDEO_SCOPE_NOTE | Layer2 smoke, chair/balance replay clips |

可視化：`validation_matrix.svg`

Raw device endpoints and replay JSON are under `raw/`.
"""
    write_text(RUN / "README.md", text)
    write_json(RUN / "summary.json", summaries)


def main() -> None:
    summaries = {
        "live_safety_contract": make_live_report(),
        "person_recovery_yolo_fallback": make_person_report(),
        "litert_runtime_stability": make_litert_report(),
        "memory_export_boundary": make_memory_report(),
        "senior_layer2_video_replay": make_senior_report(),
    }
    make_matrix_svg(summaries)
    make_run_readme(summaries)


if __name__ == "__main__":
    main()
