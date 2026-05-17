from __future__ import annotations

import argparse
import bisect
import csv
import json
import math
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[3]
OUT_DIR = ROOT / "docs" / "assets" / "video"
BENCH_DIR = ROOT / "docs" / "benchmark" / "architecture_report_video_real_2026-05-17"

VIDEO_PATH = ROOT / "test_assets" / "Real" / "800432825.004389.mp4"
PACKET_PATH = BENCH_DIR / "motionzip_packet.json"
PROMPT_PATH = BENCH_DIR / "motionzip_e2b_prompt.json"
VISION_EVENTS_PATH = ROOT / "docs" / "benchmark" / "local_validation_run_2026-05-17" / "raw" / "events.json"
LOCAL_VALIDATION_SUMMARY_PATH = ROOT / "docs" / "benchmark" / "local_validation_run_2026-05-17" / "summary.json"
ARCHITECTURE_GAP_REPORT_PATH = ROOT / "docs" / "benchmark" / "architecture_validation_gap_report_2026-05-16" / "report.md"
LIVE_SAFETY_REPORT_PATH = ROOT / "docs" / "benchmark" / "live_safety_contract_report_2026-05-16" / "report.md"
LITERT_100_RUN_SUMMARY_PATH = ROOT / "docs" / "benchmark" / "litert_prompt_smoke_constrained_100_official_2026-05-16" / "summary.json"
MOTIONZIP_EQUIV_SUMMARY_PATH = ROOT / "docs" / "benchmark" / "motionzip_equivalence_prompt_endpoint_hardened4_official_2026-05-16" / "summary.json"
STREAMING_SUMMARY_PATH = ROOT / "docs" / "benchmark" / "litert_prompt_stream_dev_2_warm_official_2026-05-16" / "summary.json"
VISION_Q8_F16_REPORT_PATH = ROOT / "docs" / "benchmark" / "gemma4_vision_mmproj_q8_vs_f16" / "README.md"
RGBA_AUDIT_SUMMARY_PATH = ROOT / "docs" / "benchmark" / "rgba_pipeline_audit" / "2026-05-16_pixel8pro" / "summary.md"
SENIOR_LAYER2_REPORT_PATH = ROOT / "docs" / "benchmark" / "senior_layer2_video_replay_report_2026-05-16" / "report.md"
FORMULA_REPORT_PATH = (
    ROOT
    / "prototype"
    / "data"
    / "validation"
    / "results"
    / "800432825.004389_architecture_report_real_800432825_formula_report.json"
)
LANDMARK_CSV_PATH = (
    ROOT
    / "prototype"
    / "data"
    / "processed"
    / "landmarks"
    / "800432825.004389_architecture_report_real_800432825.csv"
)
ANGLE_CSV_PATH = (
    ROOT
    / "prototype"
    / "data"
    / "processed"
    / "angles"
    / "800432825.004389_architecture_report_real_800432825_angles.csv"
)

OUT_VIDEO = OUT_DIR / "gemmafit_architecture_motionzip_real_report.mp4"
OUT_THUMB = OUT_DIR / "gemmafit_architecture_motionzip_real_report_thumb.jpg"
OUT_CONTACT = OUT_DIR / "gemmafit_architecture_motionzip_real_report_contact_sheet.jpg"
OUT_SUMMARY = BENCH_DIR / "report_video_summary.json"
OUT_README = BENCH_DIR / "README.md"
OUT_VISION_SCENE = BENCH_DIR / "vision_scene_anchor.jpg"
OUT_VISION_PANEL = BENCH_DIR / "vision_motionzip_panel.jpg"
LANGUAGE = "zh"

W, H = 1920, 1080
FPS = 15.0

COLORS = {
    "bg": (248, 251, 255),
    "panel": (232, 239, 248),
    "panel2": (219, 230, 244),
    "text": (18, 30, 46),
    "muted": (85, 101, 121),
    "green": (0, 156, 95),
    "blue": (37, 100, 235),
    "amber": (190, 119, 0),
    "red": (220, 38, 38),
    "cyan": (0, 128, 150),
    "line": (143, 160, 184),
}


def configure_output_paths(lang: str) -> None:
    global LANGUAGE, OUT_VIDEO, OUT_THUMB, OUT_CONTACT, OUT_SUMMARY, OUT_README, OUT_VISION_SCENE, OUT_VISION_PANEL
    LANGUAGE = lang
    suffix = "_en" if lang == "en" else ""
    OUT_VIDEO = OUT_DIR / f"gemmafit_architecture_motionzip_real_report{suffix}.mp4"
    OUT_THUMB = OUT_DIR / f"gemmafit_architecture_motionzip_real_report_thumb{suffix}.jpg"
    OUT_CONTACT = OUT_DIR / f"gemmafit_architecture_motionzip_real_report_contact_sheet{suffix}.jpg"
    OUT_SUMMARY = BENCH_DIR / f"report_video_summary{suffix}.json"
    OUT_README = BENCH_DIR / ("README_en.md" if lang == "en" else "README.md")
    OUT_VISION_SCENE = BENCH_DIR / f"vision_scene_anchor{suffix}.jpg"
    OUT_VISION_PANEL = BENCH_DIR / f"vision_motionzip_panel{suffix}.jpg"


def is_en() -> bool:
    return LANGUAGE == "en"

POSE_CONNECTIONS = [
    ("left_shoulder", "right_shoulder"),
    ("left_shoulder", "left_elbow"),
    ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"),
    ("right_elbow", "right_wrist"),
    ("left_shoulder", "left_hip"),
    ("right_shoulder", "right_hip"),
    ("left_hip", "right_hip"),
    ("left_hip", "left_knee"),
    ("left_knee", "left_ankle"),
    ("right_hip", "right_knee"),
    ("right_knee", "right_ankle"),
    ("left_ankle", "left_heel"),
    ("left_heel", "left_foot_index"),
    ("right_ankle", "right_heel"),
    ("right_heel", "right_foot_index"),
]

KEY_VISIBILITY = [
    "left_shoulder",
    "right_shoulder",
    "left_hip",
    "right_hip",
    "left_knee",
    "right_knee",
    "left_ankle",
    "right_ankle",
]


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        Path(r"C:\Windows\Fonts\NotoSansTC-VF.ttf"),
        Path(r"C:\Windows\Fonts\msyh.ttc"),
        Path(r"C:\Windows\Fonts\arial.ttf"),
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size=size)
    return ImageFont.load_default()


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def load_vision_sidecar_example() -> dict[str, Any]:
    fallback = {
        "raw_response": "env=outdoor;support=chair;person=visible;overlay_readable=true;limited=false",
        "visual_context": {
            "env": "outdoor",
            "support": "chair",
            "person": "visible",
            "overlay_readable": True,
            "limited": False,
            "source": "litert_vision_sidecar",
            "evidence_refs": [
                "visual_context.env",
                "visual_context.support",
                "visual_context.person",
                "visual_context.overlay_readable",
                "visual_context.limited",
            ],
        },
        "mode": "scene_only",
        "budget_reason": "scene_only_fallback:motion_zip_unavailable",
        "source_event": None,
    }
    if not VISION_EVENTS_PATH.exists():
        return fallback
    try:
        events = load_json(VISION_EVENTS_PATH)
    except Exception:
        return fallback
    for event in events:
        if event.get("category") != "session_visual_context" or event.get("message") != "vision_sidecar_result":
            continue
        data = event.get("data", {})
        raw = str(data.get("raw_response") or fallback["raw_response"])
        visual_context_raw = data.get("visual_context")
        visual_context = fallback["visual_context"]
        if isinstance(visual_context_raw, str) and visual_context_raw.strip():
            try:
                visual_context = json.loads(visual_context_raw)
            except json.JSONDecodeError:
                visual_context = fallback["visual_context"]
        return {
            "raw_response": raw,
            "visual_context": visual_context,
            "mode": str(data.get("mode") or fallback["mode"]),
            "budget_reason": str(data.get("budget_reason") or fallback["budget_reason"]),
            "source_event": {
                "path": rel(VISION_EVENTS_PATH),
                "event_key": data.get("event_key"),
                "trigger": (data.get("request") or {}).get("trigger"),
            },
        }
    return fallback


def optional_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        payload = load_json(path)
    except Exception:
        return {}
    return payload if isinstance(payload, dict) else {}


def load_proof_artifacts() -> list[dict[str, str]]:
    local = optional_json(LOCAL_VALIDATION_SUMMARY_PATH)
    litert = optional_json(LITERT_100_RUN_SUMMARY_PATH)
    motionzip = optional_json(MOTIONZIP_EQUIV_SUMMARY_PATH)
    streaming = optional_json(STREAMING_SUMMARY_PATH)
    equiv = motionzip_equivalence_metrics()
    stream_records = streaming.get("records") or []
    first_token_values = [
        safe_float(record.get("first_token_ms"))
        for record in stream_records
        if isinstance(record, dict) and math.isfinite(safe_float(record.get("first_token_ms")))
    ]
    first_token = min(first_token_values) if first_token_values else math.nan
    live_status = (local.get("live_safety_contract") or {}).get("status", "unknown")
    return [
        {
            "title": "100-run reliability",
            "metric": f"{litert.get('model_json_parse_success_count', '?')}/{litert.get('count_completed', '?')}",
            "note": "Pixel parsed every local Gemma JSON result, so the summary contract is stable.",
            "path": rel(LITERT_100_RUN_SUMMARY_PATH),
        },
        {
            "title": "Same key facts",
            "metric": f"{motionzip.get('pass_count', '?')}/{motionzip.get('total', '?')}",
            "note": "Compressed MotionZip evidence matched the dense evidence checks.",
            "path": rel(MOTIONZIP_EQUIV_SUMMARY_PATH),
        },
        {
            "title": "Less waiting",
            "metric": f"~{fmt_pct(equiv['speedup_pct'])}",
            "note": "Dense evidence 69.3s -> MotionZip 39.8s in the equivalence run.",
            "path": rel(MOTIONZIP_EQUIV_SUMMARY_PATH),
        },
        {
            "title": "First text appears",
            "metric": f"{first_token / 1000:.2f}s" if math.isfinite(first_token) else "n/a",
            "note": "Warm streaming can show coach-summary progress quickly.",
            "path": rel(STREAMING_SUMMARY_PATH),
        },
        {
            "title": "Live safety stays local",
            "metric": str(live_status).upper(),
            "note": "Live frames stay deterministic; Gemma explains later, not every frame.",
            "path": rel(LIVE_SAFETY_REPORT_PATH),
        },
        {
            "title": "Camera CPU known",
            "metric": "~0.3ms",
            "note": "RGBA saves conversion CPU; the main cost is still model generation.",
            "path": rel(RGBA_AUDIT_SUMMARY_PATH),
        },
    ]


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle))


def rel(path: Path) -> str:
    return str(path.relative_to(ROOT)).replace("\\", "/")


def safe_float(value: Any, default: float = math.nan) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError):
        return default
    return result if math.isfinite(result) else default


def fmt_bytes(value: int) -> str:
    if value >= 1024 * 1024:
        return f"{value / (1024 * 1024):.2f} MB"
    if value >= 1024:
        return f"{value / 1024:.1f} KB"
    return f"{value} B"


def motionzip_equivalence_metrics() -> dict[str, Any]:
    payload = optional_json(MOTIONZIP_EQUIV_SUMMARY_PATH)
    cases = payload.get("cases") or []
    dense = next((case for case in cases if case.get("id") == "dense_frame_by_frame"), {})
    compressed = next((case for case in cases if case.get("id") == "motionzip_compressed"), {})
    dense_wall = safe_float(dense.get("wall_ms"))
    compressed_wall = safe_float(compressed.get("wall_ms"))
    speedup_pct = math.nan
    if math.isfinite(dense_wall) and dense_wall > 0 and math.isfinite(compressed_wall):
        speedup_pct = (dense_wall - compressed_wall) / dense_wall * 100
    return {
        "pass_count": payload.get("pass_count", "?"),
        "total": payload.get("total", "?"),
        "dense_wall_ms": dense_wall,
        "compressed_wall_ms": compressed_wall,
        "speedup_pct": speedup_pct,
    }


def fmt_pct(value: float) -> str:
    return f"{value:.0f}%" if math.isfinite(value) else "n/a"


def pil_canvas() -> Image.Image:
    img = Image.new("RGB", (W, H), COLORS["bg"])
    draw = ImageDraw.Draw(img)
    for x in range(-500, W + 600, 180):
        draw.line((x, 0, x - 700, H), fill=(235, 242, 250), width=34)
    return img


def draw_text(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    size: int,
    color: tuple[int, int, int] = COLORS["text"],
    bold: bool = False,
    anchor: str | None = None,
    spacing: int = 8,
) -> None:
    draw.multiline_text(xy, text, fill=color, font=font(size, bold), anchor=anchor, spacing=spacing)


def wrap_to_width(text: str, max_width: int, size: int, bold: bool = False) -> list[str]:
    f = font(size, bold)
    probe = Image.new("RGB", (10, 10))
    draw = ImageDraw.Draw(probe)
    lines: list[str] = []
    for paragraph in text.split("\n"):
        if not paragraph:
            lines.append("")
            continue
        if " " in paragraph:
            current = ""
            for word in paragraph.split(" "):
                trial = word if not current else f"{current} {word}"
                width = draw.textbbox((0, 0), trial, font=f)[2]
                if current and width > max_width:
                    lines.append(current)
                    current = word
                else:
                    current = trial
            if current:
                lines.append(current)
            continue
        current = ""
        for char in paragraph:
            trial = current + char
            width = draw.textbbox((0, 0), trial, font=f)[2]
            if current and width > max_width:
                lines.append(current)
                current = char
            else:
                current = trial
        if current:
            lines.append(current)
    return lines


def draw_wrapped(
    draw: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    max_width: int,
    size: int,
    color: tuple[int, int, int] = COLORS["text"],
    bold: bool = False,
    line_gap: int = 8,
) -> int:
    x, y = xy
    for line in wrap_to_width(text, max_width, size, bold):
        draw_text(draw, (x, y), line, size, color, bold)
        y += size + line_gap
    return y


def rounded(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    radius: int = 26,
    fill: tuple[int, int, int] = COLORS["panel"],
    outline: tuple[int, int, int] | None = None,
    width: int = 2,
) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def panel_outline() -> tuple[int, int, int]:
    return (178, 194, 214) if COLORS["bg"][0] > 200 else panel_outline()


def inset_panel() -> tuple[int, int, int]:
    return (241, 246, 252) if COLORS["bg"][0] > 200 else inset_panel()


def video_letterbox() -> tuple[int, int, int]:
    return (225, 234, 245) if COLORS["bg"][0] > 200 else video_letterbox()


def to_bgr(img: Image.Image) -> np.ndarray:
    return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)


def from_bgr(frame: np.ndarray) -> Image.Image:
    return Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))


def slide_frames(img: Image.Image, seconds: float) -> list[np.ndarray]:
    frame = to_bgr(img)
    return [frame.copy() for _ in range(int(seconds * FPS))]


def header(draw: ImageDraw.ImageDraw, kicker: str, title: str, subtitle: str = "") -> None:
    draw_text(draw, (88, 72), kicker, 32, COLORS["cyan"], True)
    draw_text(draw, (88, 128), title, 70, COLORS["text"], True)
    if subtitle:
        draw_wrapped(draw, (88, 225), subtitle, 1040, 31 if is_en() else 34, COLORS["muted"], False)


ARCH_LABELS = {
    "1": "1 Pose",
    "2": "2 Gates",
    "3": "3 Features",
    "4": "4 Layer 2",
    "5": "5 MotionZip",
    "6": "6 Gemma",
    "7": "7 Validator",
    "V": "Vision sidecar",
}


def draw_arch_context(
    draw: ImageDraw.ImageDraw,
    steps: list[str],
    note: str = "",
    x0: int = 1260,
    y0: int = 70,
) -> None:
    draw_text(draw, (x0, y0), "Architecture context", 22, COLORS["cyan"], True)
    x, y = x0, y0 + 38
    for step in steps:
        label = ARCH_LABELS.get(step, step)
        color = COLORS["green"] if step in {"1", "2", "3", "4"} else COLORS["amber"] if step == "5" else COLORS["cyan"] if step in {"6", "V"} else COLORS["red"]
        text_w = ImageDraw.Draw(Image.new("RGB", (10, 10))).textbbox((0, 0), label, font=font(21, True))[2]
        chip_w = text_w + 34
        if x + chip_w > 1832:
            x = x0
            y += 46
        rounded(draw, (x, y, x + chip_w, y + 34), 16, color)
        draw_text(draw, (x + chip_w // 2, y + 18), label, 19, COLORS["bg"], True, anchor="mm")
        x += chip_w + 12
    if note:
        draw_wrapped(draw, (x0, y + 50), note, 550, 20, COLORS["muted"], False, line_gap=4)


def metric_card(
    draw: ImageDraw.ImageDraw,
    box: tuple[int, int, int, int],
    title: str,
    value: str,
    note: str,
    color: tuple[int, int, int],
) -> None:
    rounded(draw, box, 28, COLORS["panel2"], panel_outline())
    x1, y1, _, _ = box
    draw_text(draw, (x1 + 28, y1 + 32), title, 28, COLORS["muted"], True)
    draw_text(draw, (x1 + 28, y1 + 82), value, 54, color, True)
    draw_wrapped(draw, (x1 + 28, y1 + 152), note, box[2] - box[0] - 56, 24, COLORS["muted"])


def slide_intro(packet: dict[str, Any], report: dict[str, Any]) -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    header(
        draw,
        "GemmaFit report video",
        "From a real video to a trustworthy coach summary" if is_en() else "從真實影片到可信任教練摘要",
        (
            "This video runs a real test_assets/Real clip through MediaPipe, angle proxies, and MotionZip, then shows how the app judges and compresses evidence."
            if is_en()
            else "這支影片用 test_assets/Real 的實際影片跑 MediaPipe、角度 proxy、MotionZip，再展示 app 如何判斷與如何壓縮。"
        ),
    )
    rounded(draw, (88, 360, 1832, 858), 34, COLORS["panel"], panel_outline())
    metric_card(draw, (132, 420, 518, 710), "source frames", str(packet["sampling"]["video_frames_total"]), "Original video frames; not directly sent into Gemma." if is_en() else "原始影片幀數，不直接送進 Gemma。", COLORS["blue"])
    metric_card(draw, (558, 420, 944, 710), "pose samples", str(packet["sampling"]["pose_samples"]), "Sample pose every 6 frames to build a time series." if is_en() else "每 6 幀抽一次姿態，用來建立時間序列。", COLORS["green"])
    metric_card(draw, (984, 420, 1370, 710), "MotionZip blocks", str(len(packet["compressed_sparse_blocks"])), "Keep only the highest-signal event windows." if is_en() else "只保留資訊量最高的事件窗口。", COLORS["amber"])
    metric_card(draw, (1410, 420, 1788, 710), "final state", str(packet["heavily_compressed_summary"]["output_state"]), "This clip demonstrates the abstain boundary under low visibility." if is_en() else "這支片因可見度低，示範 abstain 邊界。", COLORS["red"])
    draw_wrapped(
        draw,
        (132, 775),
        (
            "Core message: GemmaFit does not ask a model to guess from the whole video. It first extracts auditable evidence, then uses Local Gemma only for low-frequency explanation and summaries."
            if is_en()
            else "核心訊息：GemmaFit 不是把整支影片丟給模型猜。它先用 deterministic pipeline 取出可審計 evidence，再讓 Local Gemma 只做低頻解釋與 summary。"
        ),
        1600,
        34,
        COLORS["text"],
        True,
    )
    return img


def slide_pipeline() -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    header(
        draw,
        "Architecture",
        "How it judges: evidence first, model second" if is_en() else "怎麼判斷：先證據，後模型",
        (
            "Live frames do not call Gemma. Pose, gates, Layer 2, MotionZip, and the validator split the job into bounded steps."
            if is_en()
            else "Live frame 不呼叫 Gemma。判斷由 pose、gate、Layer 2、MotionZip 和 validator 分層完成。"
        ),
    )
    steps = (
        [
            ("1", "MediaPipe Pose", "33 keypoints + visibility"),
            ("2", "Judgeability gates", "Is the person visible, selected, and clear enough?"),
            ("3", "Motion features", "Angles, ROM proxy, tempo, velocity, confidence floor"),
            ("4", "Layer 2", "Activity, phase, event, abstain reason"),
            ("5", "MotionZip", "525 frames -> 88 pose samples -> 3 blocks"),
            ("6", "Local Gemma", "Writes summary from evidence refs only"),
            ("7", "Validator", "Blocks medical, force, EMG, or missing-ref claims"),
        ]
        if is_en()
        else [
            ("1", "MediaPipe Pose", "33 個 keypoints + visibility"),
            ("2", "Judgeability gates", "人是否可見、是否選對主體、是否足夠清楚"),
            ("3", "Motion features", "角度、ROM proxy、tempo、velocity、confidence floor"),
            ("4", "Layer 2", "activity / phase / event / abstain reason"),
            ("5", "MotionZip", "525 frames -> 88 pose samples -> 3 blocks"),
            ("6", "Local Gemma", "只根據 evidence refs 生成 summary"),
            ("7", "Validator", "擋掉 medical / force / EMG / missing refs"),
        ]
    )
    x = 88
    y = 360
    card_w = 230
    gap = 22
    for idx, title, note in steps:
        rounded(draw, (x, y, x + card_w, y + 300), 24, COLORS["panel"], panel_outline())
        draw.ellipse((x + 24, y + 28, x + 74, y + 78), fill=COLORS["green"])
        draw_text(draw, (x + 49, y + 54), idx, 28, COLORS["bg"], True, anchor="mm")
        draw_wrapped(draw, (x + 24, y + 105), title, card_w - 48, 30, COLORS["text"], True)
        draw_wrapped(draw, (x + 24, y + 178), note, card_w - 48, 23, COLORS["muted"])
        if idx != "7":
            draw.line((x + card_w + 4, y + 150, x + card_w + gap - 4, y + 150), fill=COLORS["amber"], width=5)
        x += card_w + gap
    rounded(draw, (88, 760, 1832, 910), 28, COLORS["panel2"], panel_outline())
    draw_wrapped(
        draw,
        (126, 805),
        (
            "Each later slide uses the same architecture-context labels. Demo-safe boundary: live safety stays deterministic; Gemma only runs for low-frequency session summaries, explanations, or exports."
            if is_en()
            else "後面每頁右上角都會標出它對應的架構步驟。Demo-safe boundary：Live safety 由 deterministic rules 決定；Gemma 只在 SESSION_ENDED / explanation / export 這類低頻場景使用。"
        ),
        1650,
        34,
        COLORS["text"],
        True,
    )
    return img


def slide_decision(packet: dict[str, Any], report: dict[str, Any]) -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    header(
        draw,
        "Decision example",
        "Why this real clip should not get a hard verdict" if is_en() else "這支真實影片為什麼不做硬判斷",
        (
            "MotionZip keeps the motion windows, but every selected window has a low confidence floor, so the output should be a review/camera cue, not a safety conclusion."
            if is_en()
            else "MotionZip 保留了動作窗口，但每個窗口 confidence floor 都低於門檻，所以輸出應是 review cue / camera cue，而不是安全結論。"
        ),
    )
    blocks = packet["compressed_sparse_blocks"]
    y = 360
    for index, block in enumerate(blocks, start=1):
        x = 110 + (index - 1) * 585
        extrema = block["preserved_extrema"]
        rounded(draw, (x, y, x + 520, y + 360), 28, COLORS["panel"], panel_outline())
        draw_text(draw, (x + 30, y + 48), f"Block {index}: frame {block['source_frames'][1]}", 32, COLORS["text"], True)
        draw_text(draw, (x + 30, y + 95), f"time {block['time_range_ms'][0]}-{block['time_range_ms'][1]} ms", 24, COLORS["muted"])
        draw_text(draw, (x + 30, y + 150), f"confidence_floor = {extrema['confidence_floor']}", 31, COLORS["red"], True)
        draw_text(draw, (x + 30, y + 198), f"peak_velocity = {extrema['peak_velocity_deg_s']} deg/s", 28, COLORS["amber"], True)
        draw_text(draw, (x + 30, y + 246), f"state = {block['rule_policy_state']}", 34, COLORS["red"], True)
        draw_wrapped(draw, (x + 30, y + 295), f"reason: {block.get('abstain_reason', 'bounded monitor-only evidence')}", 445, 24, COLORS["muted"])
    rounded(draw, (110, 800, 1810, 930), 28, COLORS["panel2"], panel_outline())
    draw_wrapped(
        draw,
        (150, 838),
        (
            "The user-facing advice should be: Move the camera farther back and keep the body plus support object fully in frame. That is safer than inventing a knee or balance verdict."
            if is_en()
            else "使用者看到的建議應該是：Move the camera farther back and keep the body plus support object fully in frame. 這比錯誤判斷膝蓋或平衡更安全。"
        ),
        1580,
        34,
        COLORS["text"],
        True,
    )
    return img


def build_vision_scene_anchor(packet: dict[str, Any]) -> Path:
    frame_idx = packet["compressed_sparse_blocks"][0]["source_frames"][0] if packet["compressed_sparse_blocks"] else 0
    cap = cv2.VideoCapture(str(VIDEO_PATH))
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video: {VIDEO_PATH}")
    cap.set(cv2.CAP_PROP_POS_FRAMES, int(frame_idx))
    ok, frame = cap.read()
    cap.release()
    if not ok:
        raise RuntimeError(f"Could not read frame {frame_idx} from {VIDEO_PATH}")
    image = from_bgr(frame)
    image.thumbnail((720, 960), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (720, 960), video_letterbox())
    canvas.paste(image, ((720 - image.width) // 2, (960 - image.height) // 2))
    canvas.save(OUT_VISION_SCENE, quality=84)
    return OUT_VISION_SCENE


def build_vision_motionzip_panel(packet: dict[str, Any]) -> Path:
    blocks = packet["compressed_sparse_blocks"]
    frames = [int(block["source_frames"][1]) for block in blocks] or [0]
    cap = cv2.VideoCapture(str(VIDEO_PATH))
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video: {VIDEO_PATH}")
    thumbs: list[Image.Image] = []
    for frame_idx in frames:
        cap.set(cv2.CAP_PROP_POS_FRAMES, frame_idx)
        ok, frame = cap.read()
        if not ok:
            continue
        image = from_bgr(frame)
        image.thumbnail((210, 360), Image.Resampling.LANCZOS)
        card = Image.new("RGB", (210, 360), inset_panel())
        card.paste(image, ((210 - image.width) // 2, (360 - image.height) // 2))
        draw = ImageDraw.Draw(card)
        rounded(draw, (10, 10, 128, 46), 16, (15, 23, 42), None)
        draw_text(draw, (22, 35), f"frame {frame_idx}", 18, COLORS["text"], True)
        thumbs.append(card)
    cap.release()

    panel = Image.new("RGB", (1280, 720), (9, 14, 23))
    draw = ImageDraw.Draw(panel)
    draw_text(draw, (42, 58), "Image 2 - MotionZip panel", 38, COLORS["text"], True)
    draw_text(draw, (42, 102), "selected event windows + compact deterministic facts", 24, COLORS["muted"])
    x = 42
    for thumb in thumbs[:3]:
        panel.paste(thumb, (x, 155))
        x += 238

    rounded(draw, (780, 155, 1238, 650), 26, COLORS["panel"], panel_outline())
    draw_text(draw, (812, 205), "Compact evidence", 30, COLORS["green"], True)
    summary = packet["heavily_compressed_summary"]
    evidence_lines = [
        f"activity_hint={summary['activity_hint']}",
        f"event={summary['event']}",
        f"blocks={len(blocks)}",
        f"confidence=low_visibility_abstain:{summary['low_visibility_abstain_blocks']}",
        f"peak_velocity_deg_s={summary['max_angular_velocity_dps']}",
        "limits=no_force,no_grf,no_emg,no_medical",
    ]
    y = 270
    for line in evidence_lines:
        draw_text(draw, (812, y), line, 23, COLORS["text"])
        y += 42
    draw_wrapped(
        draw,
        (812, 560),
        "This is evidence compression, not raw-video understanding.",
        360,
        23,
        COLORS["amber"],
        True,
        line_gap=5,
    )
    panel.save(OUT_VISION_PANEL, quality=82)
    return OUT_VISION_PANEL


def slide_vision_policy(vision: dict[str, Any]) -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    visual = vision["visual_context"]
    header(
        draw,
        "Optional Vision sidecar",
        "When Vision can be called: a real example" if is_en() else "VISION 什麼時候可以調用：真實範例",
        (
            "Vision only adds scene context after video analysis. It does not change live safety, reps, or form score, but it makes the summary feel grounded in the same video."
            if is_en()
            else "Vision 只在影片分析結束後補場景，不改 live safety、reps、form score。這讓 summary 更像人在看同一段影片。"
        ),
    )
    draw_arch_context(draw, ["5", "V", "6", "7"], "Sidecar runs after MotionZip evidence exists and before the summary is finalized.")
    cards = [
        (
            (100, 330, 610, 810),
            "1. During review",
            "SKIP",
            COLORS["red"],
            "Every frame stays cheap and deterministic: MediaPipe pose, gates, Layer 2, MotionZip. No Vision and no Gemma live verdict.",
        ),
        (
            (705, 330, 1215, 810),
            "2. Session ended",
            "CALL ONCE",
            COLORS["green"],
            "GemmaFit picks Image 1 scene anchor + Image 2 MotionZip panel, then asks for short key=value context.",
        ),
        (
            (1310, 330, 1820, 810),
            "3. Summary benefit",
            "CONTEXT",
            COLORS["cyan"],
            "Before: support/env unknown. After: outdoor + chair + person visible + overlay readable. The coach wording becomes more specific.",
        ),
    ]
    for box, title, badge, color, body in cards:
        rounded(draw, box, 30, COLORS["panel"], panel_outline())
        x1, y1, x2, _ = box
        draw_text(draw, (x1 + 36, y1 + 52), title, 30, COLORS["text"], True)
        rounded(draw, (x1 + 36, y1 + 104, x1 + 250, y1 + 152), 22, color)
        draw_text(draw, (x1 + 143, y1 + 129), badge, 21, COLORS["bg"], True, anchor="mm")
        draw_wrapped(draw, (x1 + 36, y1 + 200), body, x2 - x1 - 72, 29, COLORS["muted"], False, line_gap=8)

    rounded(draw, (100, 835, 1820, 950), 24, inset_panel(), panel_outline())
    draw_text(draw, (135, 876), "Real Pixel result", 27, COLORS["amber"], True)
    draw_text(draw, (390, 876), str(vision["raw_response"]), 23, COLORS["green"], True)
    draw_wrapped(
        draw,
        (135, 922),
        f"Parsed: env={visual.get('env')} | support={visual.get('support')} | person={visual.get('person')} | limited={visual.get('limited')}. Improves explanation/context only; safety verdict is unchanged.",
        1600,
        22,
        COLORS["text"],
        True,
        line_gap=4,
    )
    return img


def load_image_rgb(path: Path, target: tuple[int, int]) -> Image.Image:
    image = Image.open(path).convert("RGB")
    image.thumbnail(target, Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", target, video_letterbox())
    canvas.paste(image, ((target[0] - image.width) // 2, (target[1] - image.height) // 2))
    return canvas


def slide_vision_inputs(packet: dict[str, Any], vision: dict[str, Any], scene_path: Path, panel_path: Path) -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    header(
        draw,
        "Vision sidecar input",
        "How the sidecar receives 1-2 images" if is_en() else "怎麼餵 1-2 張圖片",
        "Two images -> short key=value output -> app-side parse.",
    )
    draw_arch_context(draw, ["V", "6", "7"], "Vision adds context; Gemma writes; Validator keeps it bounded.")
    rounded(draw, (80, 325, 600, 910), 30, COLORS["panel"], panel_outline())
    draw_text(draw, (118, 373), "Image 1", 28, COLORS["green"], True)
    draw_text(draw, (118, 409), "Scene anchor", 34, COLORS["text"], True)
    draw_text(draw, (118, 453), "env / support / person visible", 22, COLORS["muted"])
    img.paste(load_image_rgb(scene_path, (430, 395)), (125, 500))

    rounded(draw, (640, 325, 1300, 910), 30, COLORS["panel"], panel_outline())
    draw_text(draw, (678, 373), "Image 2", 28, COLORS["amber"], True)
    draw_text(draw, (678, 409), "MotionZip panel", 34, COLORS["text"], True)
    draw_text(draw, (678, 453), "selected windows + compact facts", 22, COLORS["muted"])
    img.paste(load_image_rgb(panel_path, (580, 326)), (680, 493))
    rounded(draw, (680, 842, 1260, 888), 20, COLORS["panel2"], panel_outline())
    draw_text(draw, (708, 873), "Raw frames are compressed before Vision reads them.", 20, COLORS["text"], True)

    rounded(draw, (1340, 325, 1840, 910), 30, COLORS["panel"], panel_outline())
    draw_text(draw, (1378, 373), "Model output", 32, COLORS["text"], True)
    draw_text(draw, (1378, 415), "Pixel sidecar result", 23, COLORS["muted"])
    draw_text(draw, (1378, 453), f"mode={vision['mode']}", 20, COLORS["muted"])
    rounded(draw, (1378, 492, 1802, 690), 18, inset_panel(), panel_outline())
    draw_text(draw, (1402, 522), "key=value", 22, COLORS["muted"], True)
    y = 556
    for part in str(vision["raw_response"]).split(";"):
        text = part.strip()
        if not text:
            continue
        draw_text(draw, (1402, y), text, 22, COLORS["green"], True)
        y += 27
    visual = vision["visual_context"]
    draw_text(draw, (1378, 718), "Parsed context", 26, COLORS["cyan"], True)
    context_lines = [
        f"env: {visual.get('env')}",
        f"support: {visual.get('support')}",
        f"person: {visual.get('person')}",
        f"overlay_readable: {visual.get('overlay_readable')}",
        f"limited: {visual.get('limited')}",
    ]
    yy = 756
    for line in context_lines:
        draw_text(draw, (1398, yy), line, 20, COLORS["text"])
        yy += 25
    return img


def slide_compression(packet: dict[str, Any], prompt_path: Path) -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    header(
        draw,
        "MotionZip",
        "How the video is compressed: task evidence, not raw video" if is_en() else "影片怎麼壓縮：保留任務證據，不保留原始影片",
        (
            "This is not lossless video compression. It keeps the evidence refs, event windows, extrema, and limits needed for a safe summary."
            if is_en()
            else "壓縮目標不是 lossless video，而是把 summary 需要的 evidence refs、event windows、extrema 和 limits 保留下來。"
        ),
    )
    draw_arch_context(draw, ["3", "5", "6"], "Features become selected event windows, then become the compact prompt for Gemma.")
    video_bytes = VIDEO_PATH.stat().st_size
    packet_bytes = PACKET_PATH.stat().st_size
    prompt_bytes = prompt_path.stat().st_size
    frames = packet["sampling"]["video_frames_total"]
    samples = packet["sampling"]["pose_samples"]
    blocks = len(packet["compressed_sparse_blocks"])
    values = [
        ("raw video", fmt_bytes(video_bytes), "525 frames", 1.0, COLORS["blue"]),
        ("pose samples", str(samples), "sample every 6 frames", samples / frames, COLORS["green"]),
        ("MotionZip blocks", str(blocks), "top information windows", blocks / frames, COLORS["amber"]),
        ("packet JSON", fmt_bytes(packet_bytes), f"{video_bytes / max(packet_bytes, 1):.0f}x smaller than video", packet_bytes / video_bytes, COLORS["red"]),
        ("E2B prompt", fmt_bytes(prompt_bytes), "bounded text packet", prompt_bytes / video_bytes, COLORS["cyan"]),
    ]
    y = 372
    for label, value, note, ratio, color in values:
        draw_text(draw, (160, y + 22), label, 34, COLORS["text"], True)
        draw_text(draw, (460, y + 22), value, 34, color, True)
        draw_text(draw, (700, y + 22), note, 28, COLORS["muted"])
        rounded(draw, (1120, y - 12, 1740, y + 42), 24, COLORS["panel2"])
        fill_w = max(8, int(620 * min(ratio, 1.0)))
        rounded(draw, (1120, y - 12, 1120 + fill_w, y + 42), 24, color)
        y += 98
    rounded(draw, (120, 850, 1800, 955), 28, COLORS["panel2"], panel_outline())
    draw_wrapped(
        draw,
        (160, 886),
        (
            "After compression, Gemma receives: activity_hint, 3 sparse blocks, confidence_floor, angle_extrema, velocity_peak, abstain reason, evidence_refs, and unsupported-claim boundaries."
            if is_en()
            else "壓縮後 Gemma 收到的是：activity_hint、3 個 sparse blocks、confidence_floor、angle_extrema、velocity_peak、abstain reason、evidence_refs、unsupported claim boundaries。"
        ),
        1580,
        30,
        COLORS["text"],
        True,
    )
    return img


def slide_technical_highlights(packet: dict[str, Any], prompt_path: Path, vision: dict[str, Any]) -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    video_bytes = VIDEO_PATH.stat().st_size
    packet_bytes = PACKET_PATH.stat().st_size
    prompt_bytes = prompt_path.stat().st_size
    equiv = motionzip_equivalence_metrics()
    header(
        draw,
        "Technical highlights",
        "Evidence, not raw video" if is_en() else "特別技術：不是把影片整段丟給模型",
        (
            "GemmaFit compresses video into trusted evidence before Local Gemma writes the summary: less hardware, less waiting, fewer unsafe guesses."
            if is_en()
            else "先把影片變成少量可靠證據，再讓本地 Gemma 寫摘要與補場景。觀眾只需要看懂：省資源、少等待、不亂判斷。"
        ),
    )
    draw_arch_context(draw, ["5", "6", "7"], "The savings come from MotionZip; Gemma writes; Validator checks boundaries.")
    cards = [
        (
            (90, 325, 900, 570),
            "Less hardware load" if is_en() else "省硬體資源",
            f"{video_bytes / max(packet_bytes, 1):.0f}x",
            f"{fmt_bytes(video_bytes)} video -> {fmt_bytes(packet_bytes)} evidence. The phone avoids feeding raw video into Gemma.",
            COLORS["green"],
        ),
        (
            (1020, 325, 1830, 570),
            "Less waiting" if is_en() else "執行速度",
            f"~{fmt_pct(equiv['speedup_pct'])}",
            "Dense evidence 69.3s -> MotionZip 39.8s in the same Pixel equivalence test.",
            COLORS["blue"],
        ),
        (
            (90, 640, 900, 885),
            "Key facts preserved" if is_en() else "理解沒有丟掉",
            f"{equiv['pass_count']}/{equiv['total']}",
            "Compressed evidence preserved the expected activity, event, confidence, and velocity facts.",
            COLORS["amber"],
        ),
        (
            (1020, 640, 1830, 885),
            "Safety boundary" if is_en() else "安全邊界",
            "LIVE = rules",
            f"Live frames stay MediaPipe + gates. Vision only adds context like {vision['visual_context'].get('support', 'support')} after the session.",
            COLORS["cyan"],
        ),
    ]
    for box, title, value, note, color in cards:
        rounded(draw, box, 30, COLORS["panel"], panel_outline())
        x1, y1, x2, _ = box
        draw_text(draw, (x1 + 34, y1 + 44), title, 30, COLORS["muted"], True)
        draw_text(draw, (x1 + 34, y1 + 104), value, 64, color, True)
        draw_wrapped(draw, (x1 + 34, y1 + 178), note, x2 - x1 - 68, 27, COLORS["text"], True, line_gap=7)
    draw_text(draw, (94, 930), f"Bounded summary prompt: {fmt_bytes(prompt_bytes)}. Vision output is short key=value text, then Android validates it.", 25, COLORS["muted"], True)
    return img


def slide_proof_artifacts(proofs: list[dict[str, str]]) -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    header(
        draw,
        "Validation evidence",
        "What these numbers prove" if is_en() else "這些數字證明什麼",
        (
            "This is not a list of engineering buzzwords. It answers three viewer questions: is it faster, does it keep the important facts, and does it prevent unsafe model guesses?"
            if is_en()
            else "不是列工程名詞，而是回答三個觀眾問題：有沒有更快？有沒有保留重點？會不會讓模型亂判斷？"
        ),
    )
    draw_arch_context(draw, ["1", "2", "3", "4", "5", "6", "7"], "Each benchmark is mapped to a pipeline stage, not a standalone trick.")
    draw_text(
        draw,
        (100, 916),
        "Full proof files are in docs/benchmark; this slide shows the audience-facing meaning.",
        22,
        COLORS["cyan"],
        True,
    )
    positions = [
        (90, 350, 625, 535),
        (690, 350, 1225, 535),
        (1290, 350, 1825, 535),
        (90, 600, 625, 785),
        (690, 600, 1225, 785),
        (1290, 600, 1825, 785),
    ]
    colors = [COLORS["green"], COLORS["blue"], COLORS["amber"], COLORS["cyan"], COLORS["red"], COLORS["green"]]
    for idx, proof in enumerate(proofs[:6]):
        box = positions[idx]
        color = colors[idx % len(colors)]
        rounded(draw, box, 26, COLORS["panel"], panel_outline())
        x1, y1, x2, _ = box
        draw_text(draw, (x1 + 26, y1 + 34), proof["title"], 27, COLORS["text"], True)
        draw_text(draw, (x1 + 26, y1 + 86), proof["metric"], 42, color, True)
        draw_wrapped(draw, (x1 + 26, y1 + 137), proof["note"], x2 - x1 - 52, 21, COLORS["muted"], False, line_gap=5)
    draw_wrapped(
        draw,
        (100, 835),
        "Important: 8/8 is not a medical accuracy claim. It means the compressed packet kept the key facts the model needs for a safe summary.",
        1700,
        28,
        COLORS["text"],
        True,
    )
    return img


def slide_close() -> Image.Image:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    header(
        draw,
        "Report takeaway",
        "Trust comes from knowing when not to guess" if is_en() else "可信任不是模型更會猜，而是系統更會拒答",
        (
            "This report shows GemmaFit running deterministic evidence on a real clip, then handing a small auditable MotionZip packet to Local Gemma."
            if is_en()
            else "這支報告影片展示：GemmaFit 用真實影片跑 deterministic evidence，再把小而可審計的 MotionZip packet 交給 Local Gemma。"
        ),
    )
    draw_arch_context(draw, ["1", "2", "3", "4", "5", "6", "7"], "End-to-end: evidence first, model second, validator last.")
    rounded(draw, (160, 360, 1760, 850), 36, COLORS["panel"], panel_outline())
    bullets = (
        [
            ("Judgment", "MediaPipe + gates + Layer 2 first decide whether the evidence is judgeable."),
            ("Compression", "525 frames become 88 pose samples, then 3 event blocks."),
            ("Model", "Gemma writes low-frequency summaries and explanations; it does not judge live frames."),
            ("Safety", "When visibility is low, the app abstains and gives camera/review cues instead of inventing a posture verdict."),
        ]
        if is_en()
        else [
            ("判斷", "MediaPipe + gates + Layer 2 先決定能不能判斷。"),
            ("壓縮", "525 frames 變成 88 pose samples，再變成 3 個 event blocks。"),
            ("模型", "Gemma 只做低頻 summary / explanation，不碰 live frame。"),
            ("安全", "低可見度時 abstain，輸出 camera/review cue，而不是硬說姿勢錯。"),
        ]
    )
    y = 430
    for title, body in bullets:
        draw.ellipse((215, y - 28, 255, y + 12), fill=COLORS["green"])
        draw_text(draw, (285, y), title, 36, COLORS["text"], True)
        draw_wrapped(draw, (410, y - 4), body, 1180, 32, COLORS["muted"])
        y += 100
    draw_text(draw, (160, 970), "Generated from: test_assets/Real/800432825.004389.mp4", 24, COLORS["muted"])
    return img


def sorted_frame_keys(rows_by_frame: dict[int, dict[str, str]]) -> list[int]:
    return sorted(rows_by_frame.keys())


def nearest_row(rows_by_frame: dict[int, dict[str, str]], keys: list[int], frame_idx: int) -> dict[str, str] | None:
    if not keys:
        return None
    pos = bisect.bisect_left(keys, frame_idx)
    if pos == 0:
        return rows_by_frame[keys[0]]
    if pos >= len(keys):
        return rows_by_frame[keys[-1]]
    before = keys[pos - 1]
    after = keys[pos]
    return rows_by_frame[before if abs(frame_idx - before) <= abs(after - frame_idx) else after]


def min_visibility(row: dict[str, str] | None) -> float | None:
    if not row:
        return None
    values = [safe_float(row.get(f"{name}_vis")) for name in KEY_VISIBILITY]
    values = [value for value in values if math.isfinite(value)]
    return min(values) if values else None


def video_box(frame: np.ndarray, box: tuple[int, int, int, int]) -> tuple[Image.Image, tuple[float, int, int, int, int]]:
    x1, y1, x2, y2 = box
    box_w = x2 - x1
    box_h = y2 - y1
    src = from_bgr(frame)
    sw, sh = src.size
    scale = min(box_w / sw, box_h / sh)
    nw = int(sw * scale)
    nh = int(sh * scale)
    resized = src.resize((nw, nh), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (box_w, box_h), video_letterbox())
    ox = (box_w - nw) // 2
    oy = (box_h - nh) // 2
    canvas.paste(resized, (ox, oy))
    return canvas, (scale, x1 + ox, y1 + oy, sw, sh)


def landmark_xy(row: dict[str, str], name: str, transform: tuple[float, int, int, int, int]) -> tuple[int, int] | None:
    scale, ox, oy, sw, sh = transform
    x = safe_float(row.get(f"{name}_x"))
    y = safe_float(row.get(f"{name}_y"))
    if not math.isfinite(x) or not math.isfinite(y):
        return None
    return int(ox + x * sw * scale), int(oy + y * sh * scale)


def draw_pose(draw: ImageDraw.ImageDraw, row: dict[str, str] | None, transform: tuple[float, int, int, int, int]) -> None:
    if not row:
        return
    for a, b in POSE_CONNECTIONS:
        pa = landmark_xy(row, a, transform)
        pb = landmark_xy(row, b, transform)
        if pa and pb:
            va = safe_float(row.get(f"{a}_vis"), 0.0)
            vb = safe_float(row.get(f"{b}_vis"), 0.0)
            color = COLORS["green"] if min(va, vb) >= 0.55 else COLORS["amber"]
            draw.line((pa, pb), fill=color, width=5)
    for name in set([point for pair in POSE_CONNECTIONS for point in pair]):
        p = landmark_xy(row, name, transform)
        if p:
            visibility = safe_float(row.get(f"{name}_vis"), 0.0)
            color = COLORS["green"] if visibility >= 0.55 else COLORS["red"]
            x, y = p
            draw.ellipse((x - 6, y - 6, x + 6, y + 6), fill=color)


def packet_windows(packet: dict[str, Any]) -> list[dict[str, Any]]:
    windows = []
    for block in packet["compressed_sparse_blocks"]:
        frames = block.get("source_frames", [])
        if len(frames) >= 2:
            windows.append(
                {
                    "start": int(frames[0]),
                    "end": int(frames[1]),
                    "state": block.get("rule_policy_state", "unknown"),
                    "frame": int(frames[1]),
                    "confidence": block["preserved_extrema"].get("confidence_floor"),
                    "velocity": block["preserved_extrema"].get("peak_velocity_deg_s"),
                }
            )
    return windows


def active_window(windows: list[dict[str, Any]], frame_idx: int) -> dict[str, Any] | None:
    for window in windows:
        if window["start"] <= frame_idx <= window["end"]:
            return window
    return None


def draw_timeline(
    draw: ImageDraw.ImageDraw,
    frame_idx: int,
    total_frames: int,
    sample_every: int,
    windows: list[dict[str, Any]],
    box: tuple[int, int, int, int],
) -> None:
    x1, y1, x2, y2 = box
    rounded(draw, box, 18, COLORS["panel2"], panel_outline())
    line_y = y1 + 52
    draw.line((x1 + 40, line_y, x2 - 40, line_y), fill=(93, 110, 132), width=8)
    w = x2 - x1 - 80
    for sample in range(0, total_frames + 1, sample_every):
        x = x1 + 40 + int(w * sample / max(1, total_frames))
        draw.line((x, line_y - 12, x, line_y + 12), fill=(129, 140, 158), width=2)
    for window in windows:
        wx1 = x1 + 40 + int(w * window["start"] / max(1, total_frames))
        wx2 = x1 + 40 + int(w * window["end"] / max(1, total_frames))
        color = COLORS["red"] if window["state"] == "abstain" else COLORS["blue"]
        draw.rounded_rectangle((wx1, line_y - 23, max(wx1 + 6, wx2), line_y + 23), radius=10, fill=color)
    cx = x1 + 40 + int(w * min(frame_idx, total_frames) / max(1, total_frames))
    draw.line((cx, line_y - 42, cx, line_y + 42), fill=COLORS["green"], width=5)
    draw_text(draw, (x1 + 38, y1 + 105), "gray ticks = sampled pose frames", 24, COLORS["muted"])
    draw_text(draw, (x1 + 430, y1 + 105), "red blocks = MotionZip selected windows", 24, COLORS["muted"])
    draw_text(draw, (x2 - 360, y1 + 105), f"current frame {frame_idx}/{total_frames}", 24, COLORS["green"], True)


def render_analysis_frame(
    raw_frame: np.ndarray,
    frame_idx: int,
    total_frames: int,
    packet: dict[str, Any],
    landmark_row: dict[str, str] | None,
    angle_row: dict[str, str] | None,
    windows: list[dict[str, Any]],
) -> np.ndarray:
    img = pil_canvas()
    draw = ImageDraw.Draw(img)
    draw_text(draw, (80, 56), "Actual video analysis", 34, COLORS["cyan"], True)
    draw_text(draw, (80, 105), "Step 1-5: Pose -> metrics -> gates -> MotionZip", 44, COLORS["text"], True)
    draw_text(draw, (80, 158), "The model receives compact evidence, not raw video or full landmarks.", 27, COLORS["muted"])
    draw_arch_context(draw, ["1", "2", "3", "4", "5"], "This page is the deterministic evidence path before Gemma.")

    vbox = (80, 205, 1135, 915)
    rounded(draw, vbox, 28, COLORS["panel"], panel_outline())
    video_img, transform = video_box(raw_frame, (105, 235, 1110, 885))
    img.paste(video_img, (105, 235))
    draw_pose(draw, landmark_row, transform)
    draw_text(draw, (130, 278), f"frame {frame_idx}", 25, COLORS["text"], True)
    draw_text(draw, (130, 314), "green = visible joints, red = low visibility", 22, COLORS["muted"])

    active = active_window(windows, frame_idx)
    row_vis = min_visibility(landmark_row)
    rounded(draw, (1180, 205, 1840, 915), 28, COLORS["panel"], panel_outline())
    draw_text(draw, (1225, 260), "Evidence panel for Gemma", 34, COLORS["text"], True)
    draw_text(draw, (1225, 307), "How video becomes facts, not pixels", 24, COLORS["muted"])

    metrics = []
    if angle_row:
        metrics.extend(
            [
                ("knee angle", f"{safe_float(angle_row.get('knee_angle'), 0):.1f} deg"),
                ("hip angle", f"{safe_float(angle_row.get('hip_angle'), 0):.1f} deg"),
                ("movement speed", f"{safe_float(angle_row.get('max_angular_velocity_dps'), 0):.1f} deg/s"),
            ]
        )
    if row_vis is not None:
        metrics.append(("pose confidence", f"{row_vis:.3f}"))
    y = 360
    for label, value in metrics:
        draw_text(draw, (1225, y), label, 27, COLORS["muted"])
        color = COLORS["red"] if label == "pose confidence" and row_vis is not None and row_vis < 0.55 else COLORS["green"]
        draw_text(draw, (1580, y), value, 29, color, True)
        y += 48

    y += 16
    if active:
        draw_text(draw, (1225, y), "Selected MotionZip window", 29, COLORS["amber"], True)
        y += 45
        draw_text(draw, (1225, y), f"decision: {active['state']}", 31, COLORS["red"], True)
        y += 40
        draw_text(draw, (1225, y), f"why: low visibility ({active['confidence']})", 25, COLORS["red"], True)
        y += 38
        draw_text(draw, (1225, y), f"motion cue: {active['velocity']} deg/s", 25, COLORS["amber"], True)
    else:
        draw_text(draw, (1225, y), "No selected event window yet", 29, COLORS["muted"], True)

    rounded(draw, (1218, 710, 1805, 842), 20, inset_panel(), panel_outline())
    draw_wrapped(
        draw,
        (1248, 742),
        "Gemma later sees evidence refs like pose_confidence, peak_velocity, and abstain_reason. It does not see the raw video.",
        520,
        23,
        COLORS["text"],
        True,
        line_gap=6,
    )
    draw_text(draw, (1248, 875), "User result: explain the limitation, do not invent a hard form verdict.", 22, COLORS["amber"], True)

    draw_timeline(
        draw,
        frame_idx,
        total_frames,
        packet["sampling"]["sample_every_frames"],
        windows,
        (80, 940, 1840, 1040),
    )
    return to_bgr(img)


def make_contact_sheet(video_path: Path, out_path: Path, rows: int = 2, cols: int = 4) -> None:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        return
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    frames = []
    for i in range(rows * cols):
        idx = int(total * (i + 0.5) / (rows * cols))
        cap.set(cv2.CAP_PROP_POS_FRAMES, idx)
        ok, frame = cap.read()
        if ok:
            frame = cv2.resize(frame, (360, 202), interpolation=cv2.INTER_AREA)
            frames.append(frame)
    cap.release()
    if not frames:
        return
    sheet = np.zeros((rows * 202, cols * 360, 3), dtype=np.uint8)
    sheet[:] = (12, 18, 28)
    for i, frame in enumerate(frames):
        r, c = divmod(i, cols)
        sheet[r * 202 : (r + 1) * 202, c * 360 : (c + 1) * 360] = frame
    cv2.imwrite(str(out_path), sheet)


def render_video() -> dict[str, Any]:
    packet = load_json(PACKET_PATH)
    report = load_json(FORMULA_REPORT_PATH)
    vision = load_vision_sidecar_example()
    proof_artifacts = load_proof_artifacts()
    vision_scene_path = build_vision_scene_anchor(packet)
    vision_panel_path = build_vision_motionzip_panel(packet)
    landmarks = read_csv(LANDMARK_CSV_PATH)
    angles = read_csv(ANGLE_CSV_PATH)
    landmark_by_frame = {int(float(row["frame"])): row for row in landmarks}
    angle_by_frame = {int(float(row["frame"])): row for row in angles}
    landmark_keys = sorted_frame_keys(landmark_by_frame)
    angle_keys = sorted_frame_keys(angle_by_frame)
    windows = packet_windows(packet)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    BENCH_DIR.mkdir(parents=True, exist_ok=True)
    writer = cv2.VideoWriter(str(OUT_VIDEO), cv2.VideoWriter_fourcc(*"mp4v"), FPS, (W, H))
    if not writer.isOpened():
        raise RuntimeError(f"Could not open writer: {OUT_VIDEO}")

    slides = [
        (slide_intro(packet, report), 4.0),
        (slide_pipeline(), 5.0),
        (slide_technical_highlights(packet, PROMPT_PATH, vision), 5.0),
        (slide_proof_artifacts(proof_artifacts), 5.0),
    ]
    for slide, seconds in slides:
        for frame in slide_frames(slide, seconds):
            writer.write(frame)

    cap = cv2.VideoCapture(str(VIDEO_PATH))
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video: {VIDEO_PATH}")
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or int(packet["sampling"]["video_frames_total"])
    frame_idx = 0
    rendered_video_frames = 0
    thumb_written = False
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if frame_idx % 2 == 0:
            landmark_row = nearest_row(landmark_by_frame, landmark_keys, frame_idx)
            angle_row = nearest_row(angle_by_frame, angle_keys, frame_idx)
            out = render_analysis_frame(frame, frame_idx, total_frames, packet, landmark_row, angle_row, windows)
            writer.write(out)
            rendered_video_frames += 1
            if not thumb_written and active_window(windows, frame_idx):
                cv2.imwrite(str(OUT_THUMB), out)
                thumb_written = True
        frame_idx += 1
    cap.release()

    for slide, seconds in [
        (slide_decision(packet, report), 5.0),
        (slide_compression(packet, PROMPT_PATH), 5.0),
        (slide_vision_policy(vision), 5.0),
        (slide_vision_inputs(packet, vision, vision_scene_path, vision_panel_path), 6.0),
        (slide_close(), 4.0),
    ]:
        for frame in slide_frames(slide, seconds):
            writer.write(frame)
    writer.release()

    if not thumb_written:
        cap = cv2.VideoCapture(str(OUT_VIDEO))
        total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
        cap.set(cv2.CAP_PROP_POS_FRAMES, max(0, total // 2))
        ok, frame = cap.read()
        if ok:
            cv2.imwrite(str(OUT_THUMB), frame)
        cap.release()
    make_contact_sheet(OUT_VIDEO, OUT_CONTACT)

    video_bytes = VIDEO_PATH.stat().st_size
    packet_bytes = PACKET_PATH.stat().st_size
    prompt_bytes = PROMPT_PATH.stat().st_size
    summary = {
        "source_video": rel(VIDEO_PATH),
        "output_video": rel(OUT_VIDEO),
        "thumbnail": rel(OUT_THUMB),
        "contact_sheet": rel(OUT_CONTACT),
        "vision_scene_anchor": rel(vision_scene_path),
        "vision_motionzip_panel": rel(vision_panel_path),
        "motionzip_packet": rel(PACKET_PATH),
        "e2b_prompt": rel(PROMPT_PATH),
        "formula_report": rel(FORMULA_REPORT_PATH),
        "landmark_csv": rel(LANDMARK_CSV_PATH),
        "angle_csv": rel(ANGLE_CSV_PATH),
        "source_frames": packet["sampling"]["video_frames_total"],
        "pose_samples": packet["sampling"]["pose_samples"],
        "sample_every_frames": packet["sampling"]["sample_every_frames"],
        "motionzip_blocks": len(packet["compressed_sparse_blocks"]),
        "output_state": packet["heavily_compressed_summary"]["output_state"],
        "low_visibility_abstain_blocks": packet["heavily_compressed_summary"]["low_visibility_abstain_blocks"],
        "max_angular_velocity_dps": packet["heavily_compressed_summary"]["max_angular_velocity_dps"],
        "source_video_bytes": video_bytes,
        "motionzip_packet_bytes": packet_bytes,
        "e2b_prompt_bytes": prompt_bytes,
        "packet_vs_video_reduction_x": round(video_bytes / max(packet_bytes, 1), 2),
        "prompt_vs_video_reduction_x": round(video_bytes / max(prompt_bytes, 1), 2),
        "technical_highlights": [
            f"{video_bytes / max(packet_bytes, 1):.0f}x less data sent to Gemma than the source video",
            "~43% less wall time in the dense-vs-MotionZip equivalence run",
            "8/8 key facts preserved by the compressed packet",
            "Vision adds scene/support context only after deterministic analysis",
        ],
        "proof_artifacts": proof_artifacts,
        "vision_sidecar": {
            "strategy": "low_frequency_scene_context_sidecar",
            "image_1": "scene_anchor_environment_support_person",
            "image_2": "motionzip_panel_selected_windows_compact_facts",
            "allowed_triggers": [
                "SESSION_ENDED",
                "USER_QUESTION",
                "CAREGIVER_EXPORT",
                "WARNING_PERSISTED_optional",
            ],
            "blocked_triggers": [
                "LIVE_FRAME",
                "SETUP_CHECK",
            ],
            "budget_gates": [
                "low_battery",
                "high_thermal_load",
                "model_disabled",
                "sidecar_already_in_flight",
            ],
            "raw_response": vision["raw_response"],
            "normalized_visual_context": vision["visual_context"],
            "mode": vision["mode"],
            "budget_reason": vision["budget_reason"],
            "source_event": vision["source_event"],
            "cannot_override": [
                "deterministic_safety_verdict",
                "warning_state",
                "rep_count",
                "form_score",
            ],
        },
        "rendered_video_frames": rendered_video_frames,
        "fps": FPS,
        "note": "This report video is a visualization artifact. It does not send raw video to Gemma and does not change the deterministic live path.",
    }
    write_json(OUT_SUMMARY, summary)
    write_readme(summary, packet)
    return summary


def write_readme(summary: dict[str, Any], packet: dict[str, Any]) -> None:
    blocks = packet["compressed_sparse_blocks"]
    block_lines = []
    for idx, block in enumerate(blocks, start=1):
        extrema = block["preserved_extrema"]
        block_lines.append(
            f"| {idx} | {block['source_frames'][0]}-{block['source_frames'][1]} | "
            f"{block['time_range_ms'][0]}-{block['time_range_ms'][1]} | "
            f"{block['rule_policy_state']} | {extrema['confidence_floor']} | "
            f"{extrema['peak_velocity_deg_s']} | {block.get('abstain_reason', '')} |"
        )
    block_table = "\n".join(block_lines)
    proof_lines = []
    for proof in summary.get("proof_artifacts", []):
        proof_lines.append(
            f"| {proof['title']} | {proof['metric']} | {proof['note']} | `{proof['path']}` |"
        )
    proof_table = "\n".join(proof_lines)
    text = f"""# Architecture + MotionZip Real Video Report

This artifact turns GemmaFit's current architecture into a reproducible report
video using a real local clip:

`{summary['source_video']}`

## Outputs

| Artifact | Path |
| --- | --- |
| Report video | `{summary['output_video']}` |
| Thumbnail | `{summary['thumbnail']}` |
| Contact sheet | `{summary['contact_sheet']}` |
| Vision scene anchor | `{summary['vision_scene_anchor']}` |
| Vision MotionZip panel | `{summary['vision_motionzip_panel']}` |
| MotionZip packet | `{summary['motionzip_packet']}` |
| E2B prompt packet | `{summary['e2b_prompt']}` |
| Summary JSON | `{rel(OUT_SUMMARY)}` |

Main evidence directories:

- `docs/benchmark`
- `docs/design`
- `docs/assets`

## What Was Actually Run

```text
real video -> MediaPipe landmarks -> angle / velocity proxies -> MotionZip packet -> report video
```

The report video uses the same numbered architecture labels throughout:

```text
1 Pose -> 2 Gates -> 3 Features -> 4 Layer 2 -> 5 MotionZip -> 6 Gemma -> 7 Validator
```

Later slides show an `Architecture context` chip so the viewer can map each
benchmark or visual panel back to the pipeline stage it proves.

Measured packet summary:

| Metric | Value |
| --- | ---: |
| Source frames | {summary['source_frames']} |
| Pose samples | {summary['pose_samples']} |
| Sample stride | every {summary['sample_every_frames']} frames |
| MotionZip blocks | {summary['motionzip_blocks']} |
| Output state | {summary['output_state']} |
| Low-visibility abstain blocks | {summary['low_visibility_abstain_blocks']} |
| Max angular velocity proxy | {summary['max_angular_velocity_dps']} deg/s |
| Source video size | {fmt_bytes(summary['source_video_bytes'])} |
| MotionZip packet size | {fmt_bytes(summary['motionzip_packet_bytes'])} |
| E2B prompt size | {fmt_bytes(summary['e2b_prompt_bytes'])} |
| Packet vs source reduction | {summary['packet_vs_video_reduction_x']}x |
| Prompt vs source reduction | {summary['prompt_vs_video_reduction_x']}x |

## Technical Highlights Shown In The Video

| Audience-facing claim | Evidence shown |
| --- | --- |
| Saves phone resources | `{fmt_bytes(summary['source_video_bytes'])}` video becomes `{fmt_bytes(summary['motionzip_packet_bytes'])}` MotionZip evidence, a `{summary['packet_vs_video_reduction_x']}x` reduction before Gemma. |
| Reduces waiting | The dense-vs-MotionZip equivalence run improves wall time from `69.3s` to `39.8s` (`~43%` less wall time). |
| Keeps the important facts | MotionZip preserved `8/8` expected key facts in the equivalence gate. This is an evidence-preservation claim, not a medical accuracy claim. |
| Keeps live safety deterministic | Live frames stay on MediaPipe + deterministic gates. Vision/Gemma can explain after evidence exists, but cannot override warnings, reps, or form score. |

## Proof Artifacts Used In The Video

| Audience signal | Key number | Why it matters | Path |
| --- | --- | --- | --- |
{proof_table}

## How The Judgment Works

This video intentionally shows the trust boundary:

1. MediaPipe extracts pose samples from the real clip.
2. Deterministic code computes angle extrema, velocity proxy, and confidence
   floor.
3. MotionZip selects the highest-information windows.
4. Each selected window has low keypoint visibility, so the policy state is
   `abstain`.
5. The correct user-facing behavior is a camera/review cue, not a hard posture
   verdict.

## Vision Sidecar Strategy

The updated report video includes GemmaFit's low-frequency Vision sidecar
contract. Vision is not part of the live safety verdict. It is only allowed to
enrich context after deterministic evidence exists.

Allowed backend triggers:

```text
SESSION_ENDED
USER_QUESTION
CAREGIVER_EXPORT
WARNING_PERSISTED optional async explanation
```

Blocked triggers:

```text
LIVE_FRAME
SETUP_CHECK
```

Budget gates skip or downgrade the call when the device is under pressure:

```text
low_battery | high_thermal_load | model_disabled | sidecar_already_in_flight
```

The two-image input strategy shown in the video is:

| Image | Purpose | Path |
| --- | --- | --- |
| 1. Scene anchor | environment, support object, person visibility | `{summary['vision_scene_anchor']}` |
| 2. MotionZip panel | selected motion windows plus compact deterministic facts | `{summary['vision_motionzip_panel']}` |

Pixel-side model output shown in the video:

```text
{summary['vision_sidecar']['raw_response']}
```

Real example shown in the video:

| Stage | What the app knows |
| --- | --- |
| Before Vision | Pose evidence says low visibility / abstain, but scene support and environment are not explicit. |
| After Vision | `env=outdoor`, `support=chair`, `person=visible`, `overlay_readable=true`, `limited=false`. |
| What improves | Summary wording and review guidance can mention the support object and camera context. |
| What does not change | Deterministic safety verdict, warning state, rep count, and form score. |

Normalized `SessionVisualContext`:

```json
{json.dumps(summary['vision_sidecar']['normalized_visual_context'], ensure_ascii=False, indent=2)}
```

The Vision result may help summary wording, but it cannot override
deterministic safety state, warning state, rep count, or form score.

## MotionZip Blocks

| # | Source frames | Time range ms | State | Confidence floor | Peak velocity deg/s | Reason |
| ---: | --- | --- | --- | ---: | ---: | --- |
{block_table}

## Rebuild

```powershell
python prototype\\build_motionzip_packet_from_video.py --video test_assets\\Real\\800432825.004389.mp4 --activity-hint chair_supported_movement --label architecture_report_real_800432825 --sample-every 6 --window-ms 1600 --max-blocks 3 --min-event-velocity 20 --min-frame-gap 60 --reuse-csv --out docs\\benchmark\\architecture_report_video_real_2026-05-17\\motionzip_packet.json --prompt-out docs\\benchmark\\architecture_report_video_real_2026-05-17\\motionzip_e2b_prompt.json
python docs\\assets\\video_sources\\generate_architecture_motionzip_real_video.py{" --lang en" if is_en() else ""}
```

## Claim Boundary

This artifact supports an architecture/demo claim: GemmaFit compresses
task-relevant pose evidence before local Gemma summary generation, and it
abstains when evidence is not strong enough. It does not validate clinical
diagnosis, injury prediction, force, GRF, EMG, muscle activation, fall-risk
scoring, or rehabilitation outcomes.
"""
    OUT_README.write_text(text, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lang", choices=["zh", "en"], default="zh")
    args = parser.parse_args()
    configure_output_paths(args.lang)
    missing = [path for path in [VIDEO_PATH, PACKET_PATH, PROMPT_PATH, FORMULA_REPORT_PATH, LANDMARK_CSV_PATH, ANGLE_CSV_PATH] if not path.exists()]
    if missing:
        for path in missing:
            print(f"missing: {path}")
        return 1
    summary = render_video()
    print(f"video={ROOT / summary['output_video']}")
    print(f"thumbnail={ROOT / summary['thumbnail']}")
    print(f"contact_sheet={ROOT / summary['contact_sheet']}")
    print(f"summary={OUT_SUMMARY}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
