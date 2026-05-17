"""Render a report-video demo for multimodal evidence compression.

The demo uses one checked-in public workout clip and compares two evidence
paths side by side:

* dense visual path: raw sampled frames / montage;
* compressed sidecar path: one Evidence Panel plus MotionZip facts.

This is a presentation artifact only. It does not call Gemma, does not store a
new raw-frame history, and does not change the deterministic live safety path.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import cv2
import numpy as np


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT_DIR = REPO_ROOT / "docs" / "benchmark" / "multimodal_image_compression_reproof_2026-05-16"
DEFAULT_RESULTS = DEFAULT_OUT_DIR / "results.json"
DEFAULT_VIDEO = DEFAULT_OUT_DIR / "report_video_comparison_demo.mp4"
DEFAULT_STORYBOARD = DEFAULT_OUT_DIR / "report_video_comparison_storyboard.png"
DEFAULT_THUMB = DEFAULT_OUT_DIR / "report_video_comparison_thumb.png"
DEFAULT_NOTES = DEFAULT_OUT_DIR / "report_video_demo_notes.md"


BG = (248, 250, 252)
INK = (15, 23, 42)
MUTED = (71, 85, 105)
PANEL = (255, 255, 255)
BORDER = (203, 213, 225)
BLUE = (37, 99, 235)
GREEN = (22, 163, 74)
RED = (220, 38, 38)
ORANGE = (234, 88, 12)
SLATE = (51, 65, 85)


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def resolve_repo_path(path_text: str) -> Path:
    path = Path(path_text)
    if path.is_absolute():
        return path
    return (REPO_ROOT / path).resolve()


def fmt_bytes(value: int) -> str:
    if value >= 1024 * 1024:
        return f"{value / (1024 * 1024):.2f} MiB"
    if value >= 1024:
        return f"{value / 1024:.1f} KiB"
    return f"{value} B"


def pct(value: float) -> str:
    return f"{value:.1f}%"


def canvas(width: int, height: int) -> np.ndarray:
    out = np.zeros((height, width, 3), dtype=np.uint8)
    out[:] = BG
    return out


def text(
    img: np.ndarray,
    value: str,
    x: int,
    y: int,
    scale: float = 0.72,
    color: tuple[int, int, int] = INK,
    thickness: int = 1,
) -> None:
    cv2.putText(img, value, (x, y), cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)


def wrap_words(value: str, max_chars: int) -> list[str]:
    words = value.split()
    lines: list[str] = []
    current: list[str] = []
    for word in words:
        trial = " ".join(current + [word])
        if current and len(trial) > max_chars:
            lines.append(" ".join(current))
            current = [word]
        else:
            current.append(word)
    if current:
        lines.append(" ".join(current))
    return lines


def rounded_panel(img: np.ndarray, x: int, y: int, w: int, h: int, fill: tuple[int, int, int] = PANEL) -> None:
    cv2.rectangle(img, (x, y), (x + w, y + h), fill, -1)
    cv2.rectangle(img, (x, y), (x + w, y + h), BORDER, 2)


def badge(img: np.ndarray, label: str, x: int, y: int, color: tuple[int, int, int]) -> None:
    size, _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, 0.55, 2)
    w = size[0] + 24
    cv2.rectangle(img, (x, y), (x + w, y + 32), color, -1)
    text(img, label, x + 12, y + 22, 0.55, (255, 255, 255), 2)


def metric_box(
    img: np.ndarray,
    title: str,
    value: str,
    x: int,
    y: int,
    w: int,
    color: tuple[int, int, int],
    note: str | None = None,
) -> None:
    rounded_panel(img, x, y, w, 108)
    text(img, title, x + 20, y + 30, 0.55, MUTED, 1)
    text(img, value, x + 20, y + 68, 1.0, color, 2)
    if note:
        text(img, note, x + 20, y + 94, 0.48, MUTED, 1)


def fit_image(src: np.ndarray, w: int, h: int, fill: tuple[int, int, int] = (226, 232, 240)) -> np.ndarray:
    if src is None or src.size == 0:
        out = np.zeros((h, w, 3), dtype=np.uint8)
        out[:] = fill
        text(out, "missing image", 24, h // 2, 0.8, MUTED, 2)
        return out
    sh, sw = src.shape[:2]
    scale = min(w / sw, h / sh)
    nw = max(1, int(sw * scale))
    nh = max(1, int(sh * scale))
    resized = cv2.resize(src, (nw, nh), interpolation=cv2.INTER_AREA)
    out = np.zeros((h, w, 3), dtype=np.uint8)
    out[:] = fill
    x = (w - nw) // 2
    y = (h - nh) // 2
    out[y : y + nh, x : x + nw] = resized
    return out


def paste(dst: np.ndarray, src: np.ndarray, x: int, y: int) -> None:
    h, w = src.shape[:2]
    dst[y : y + h, x : x + w] = src


def draw_bar(
    img: np.ndarray,
    label: str,
    value: float,
    max_value: float,
    x: int,
    y: int,
    w: int,
    color: tuple[int, int, int],
    display: str,
) -> None:
    text(img, label, x, y + 18, 0.58, SLATE, 1)
    cv2.rectangle(img, (x + 260, y), (x + 260 + w, y + 24), (226, 232, 240), -1)
    fill_w = int(w * value / max_value) if max_value > 0 else 0
    cv2.rectangle(img, (x + 260, y), (x + 260 + fill_w, y + 24), color, -1)
    text(img, display, x + 280 + w, y + 20, 0.58, INK, 2)


def arrow(img: np.ndarray, x1: int, y1: int, x2: int, y2: int, color: tuple[int, int, int]) -> None:
    cv2.arrowedLine(img, (x1, y1), (x2, y2), color, 5, cv2.LINE_AA, tipLength=0.16)


def load_image(path: Path) -> np.ndarray:
    img = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(path)
    return img


def read_video_frame(video_path: Path, index: int) -> tuple[np.ndarray, int]:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video: {video_path}")
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    cap.set(cv2.CAP_PROP_POS_FRAMES, min(max(index, 0), total - 1))
    ok, frame = cap.read()
    cap.release()
    if not ok:
        frame = np.zeros((720, 1280, 3), dtype=np.uint8)
        frame[:] = (226, 232, 240)
    return frame, total


def draw_comparison_frame(
    frame: np.ndarray,
    dense_montage: np.ndarray,
    sidecar_panel: np.ndarray,
    results: dict[str, Any],
    source_frame: int,
    total_source_frames: int,
    width: int,
    height: int,
) -> np.ndarray:
    out = canvas(width, height)
    byte_cmp = results["byte_comparison"]
    assets = results["image_assets"]
    visual = results["visual_output_comparison"]
    source = results["source_video"]
    official = results["official_litert_text_equivalence"]

    dense_pass = visual["dense_raw_montage"]["pass_count"]
    sidecar_pass = visual["motionzip_sidecar"]["pass_count"]
    total_checks = visual["motionzip_sidecar"]["total"]

    text(out, "One video, two evidence paths", 60, 62, 1.15, INK, 2)
    text(out, "Raw frames are useful for demos; the app sends bounded sidecar evidence to Gemma only on low-frequency triggers.", 60, 96, 0.62, MUTED, 1)
    text(out, f"Source: {source['path']} ({fmt_bytes(source['bytes'])})", 60, 122, 0.55, MUTED, 1)

    rounded_panel(out, 54, 150, 820, 566)
    badge(out, "A. dense visual path", 78, 176, BLUE)
    text(out, "Current source video frame", 78, 232, 0.72, INK, 2)
    video_view = fit_image(frame, 760, 330, fill=(15, 23, 42))
    paste(out, video_view, 84, 254)
    text(out, f"frame {source_frame} / {total_source_frames}", 104, 294, 0.55, (255, 255, 255), 2)
    text(out, "raw video / sampled frame history", 104, 324, 0.55, (226, 232, 240), 1)
    text(out, "Dense montage baseline", 78, 626, 0.66, INK, 2)
    dense_thumb = fit_image(dense_montage, 456, 92)
    paste(out, dense_thumb, 78, 646)
    text(out, f"{fmt_bytes(assets['dense_raw_montage']['bytes'])}", 560, 674, 0.75, BLUE, 2)
    text(out, f"Gemma field pass: {dense_pass}/{total_checks}", 560, 706, 0.65, INK, 2)

    rounded_panel(out, 1046, 150, 820, 566)
    badge(out, "B. compressed sidecar", 1070, 176, GREEN)
    text(out, "Single Evidence Panel q70", 1070, 232, 0.72, INK, 2)
    panel_view = fit_image(sidecar_panel, 560, 360)
    paste(out, panel_view, 1176, 254)
    text(out, f"{fmt_bytes(assets['compressed_panel_q70']['bytes'])}", 1070, 674, 0.75, GREEN, 2)
    text(out, f"Gemma field pass: {sidecar_pass}/{total_checks}", 1070, 706, 0.65, INK, 2)

    arrow(out, 902, 390, 1014, 390, ORANGE)
    text(out, "compress", 914, 350, 0.62, ORANGE, 2)
    text(out, f"-{pct(byte_cmp['panel_q70_vs_source_video_reduction_pct'])}", 900, 438, 0.74, GREEN, 2)
    text(out, "vs source video", 894, 468, 0.52, MUTED, 1)

    rounded_panel(out, 54, 760, 1812, 280)
    text(out, "Report comparison", 78, 804, 0.82, INK, 2)
    draw_bar(out, "Output fields preserved", dense_pass, total_checks, 78, 846, 420, BLUE, f"dense {dense_pass}/{total_checks}")
    draw_bar(out, "Output fields preserved", sidecar_pass, total_checks, 78, 900, 420, GREEN, f"sidecar {sidecar_pass}/{total_checks}")
    draw_bar(out, "Generate-time reduction", official["generate_content_reduction_pct"], 100, 78, 948, 420, GREEN, pct(official["generate_content_reduction_pct"]))

    metric_box(out, "Source video", fmt_bytes(source["bytes"]), 1150, 824, 205, SLATE)
    metric_box(out, "Dense montage", fmt_bytes(assets["dense_raw_montage"]["bytes"]), 1380, 824, 205, BLUE, "visual baseline")
    metric_box(out, "Panel q70", fmt_bytes(assets["compressed_panel_q70"]["bytes"]), 1610, 824, 205, GREEN, "sidecar input")

    text(out, "Boundary: evidence compression for explanations/summaries only, not live safety verdicting.", 78, 1000, 0.58, RED, 2)
    text(out, "LIVE_FRAME remains MediaPipe / Layer 2 / Trust Matrix / Evidence Card.", 78, 1028, 0.58, RED, 2)
    return out


def write_notes(path: Path, results: dict[str, Any], video_path: Path, out_video: Path, storyboard: Path) -> None:
    byte_cmp = results["byte_comparison"]
    visual = results["visual_output_comparison"]
    official = results["official_litert_text_equivalence"]
    lines = [
        "# Report Video Demo Notes",
        "",
        f"Source video: `{video_path.relative_to(REPO_ROOT)}`",
        "",
        "## What To Show",
        "",
        "1. Start with the left side: the raw workout clip is real video evidence, but it is too expensive and too broad to send as every-frame multimodal input.",
        "2. Move to the right side: the Evidence Panel / MotionZip sidecar keeps only selected visual evidence plus bounded facts.",
        "3. Point at the bottom metrics: dense montage recovered fewer constrained fields, while the sidecar preserved the task-critical evidence fields.",
        "4. End on the boundary statement: this supports low-frequency explanation and summary, not live verdict changes.",
        "",
        "## Numbers On Screen",
        "",
        f"- Source video size: `{results['source_video']['bytes']}` bytes.",
        f"- Panel q70 size: `{results['image_assets']['compressed_panel_q70']['bytes']}` bytes.",
        f"- Panel q70 reduction vs source video: `{byte_cmp['panel_q70_vs_source_video_reduction_pct']:.2f}%`.",
        f"- Dense visual field pass: `{visual['dense_raw_montage']['pass_count']} / {visual['dense_raw_montage']['total']}`.",
        f"- Sidecar visual field pass: `{visual['motionzip_sidecar']['pass_count']} / {visual['motionzip_sidecar']['total']}`.",
        f"- Official LiteRT text equivalence: `{official['pass_count']} / {official['total']}`.",
        f"- Generate-time reduction: `{official['generate_content_reduction_pct']:.2f}%`.",
        "",
        "## Generated Assets",
        "",
        f"- Video: `{out_video.relative_to(REPO_ROOT)}`",
        f"- Storyboard: `{storyboard.relative_to(REPO_ROOT)}`",
        "",
        "## Safety Boundary",
        "",
        "`LIVE_FRAME` remains deterministic-only. The sidecar is for `USER_QUESTION`, `SESSION_ENDED`, `CAREGIVER_EXPORT`, and optional `WARNING_PERSISTED`.",
    ]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def render_video(
    results: dict[str, Any],
    video_path: Path,
    dense_montage: np.ndarray,
    sidecar_panel: np.ndarray,
    output_path: Path,
    thumb_path: Path,
    width: int,
    height: int,
    seconds: float,
    fps: float,
) -> None:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise RuntimeError(f"Could not open video: {video_path}")
    total_source_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or 1
    output_frames = max(1, int(seconds * fps))
    writer = cv2.VideoWriter(str(output_path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (width, height))
    if not writer.isOpened():
        cap.release()
        raise RuntimeError(f"Could not open video writer: {output_path}")

    thumb = None
    for out_index in range(output_frames):
        source_index = int((out_index / max(output_frames - 1, 1)) * (total_source_frames - 1))
        cap.set(cv2.CAP_PROP_POS_FRAMES, source_index)
        ok, frame = cap.read()
        if not ok:
            frame = np.zeros((720, 1280, 3), dtype=np.uint8)
            frame[:] = (226, 232, 240)
        rendered = draw_comparison_frame(
            frame,
            dense_montage,
            sidecar_panel,
            results,
            source_index,
            total_source_frames,
            width,
            height,
        )
        writer.write(rendered)
        if out_index == output_frames // 2:
            thumb = rendered.copy()

    cap.release()
    writer.release()
    if thumb is not None:
        cv2.imwrite(str(thumb_path), thumb)


def main() -> int:
    parser = argparse.ArgumentParser(description="Render multimodal compression comparison assets for a report video.")
    parser.add_argument("--results", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--out-video", type=Path, default=DEFAULT_VIDEO)
    parser.add_argument("--storyboard", type=Path, default=DEFAULT_STORYBOARD)
    parser.add_argument("--thumb", type=Path, default=DEFAULT_THUMB)
    parser.add_argument("--notes", type=Path, default=DEFAULT_NOTES)
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--seconds", type=float, default=12.0)
    parser.add_argument("--fps", type=float, default=15.0)
    args = parser.parse_args()

    results_path = args.results.resolve()
    results = load_json(results_path)
    assets = results["image_assets"]
    video_path = resolve_repo_path(results["source_video"]["path"])
    dense_montage = load_image(resolve_repo_path(assets["dense_raw_montage"]["path"]))
    sidecar_panel = load_image(resolve_repo_path(assets["compressed_panel_q70"]["path"]))

    args.out_video.parent.mkdir(parents=True, exist_ok=True)
    frame, total = read_video_frame(video_path, 210)
    storyboard = draw_comparison_frame(
        frame,
        dense_montage,
        sidecar_panel,
        results,
        210,
        total,
        args.width,
        args.height,
    )
    cv2.imwrite(str(args.storyboard.resolve()), storyboard)
    render_video(
        results,
        video_path,
        dense_montage,
        sidecar_panel,
        args.out_video.resolve(),
        args.thumb.resolve(),
        args.width,
        args.height,
        args.seconds,
        args.fps,
    )
    write_notes(args.notes.resolve(), results, video_path, args.out_video.resolve(), args.storyboard.resolve())

    print(json.dumps({
        "video": str(args.out_video.resolve()),
        "storyboard": str(args.storyboard.resolve()),
        "thumbnail": str(args.thumb.resolve()),
        "notes": str(args.notes.resolve()),
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
