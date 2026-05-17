"""Render an actual-video MotionZip sparse-understanding demo.

The output video overlays:

- sparse tracking cadence (e.g. one tracked pose sample every 12 source frames)
- MotionZip event windows selected from the sparse packet
- the compact evidence state Gemma would receive
- a timeline showing sampled tracking ticks and event blocks

This is a visualization artifact only. It does not send raw video to Gemma.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import cv2
import numpy as np


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RESULTS = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding" / "results.json"
DEFAULT_PACKET = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding" / "packets" / "sparse_stride_2_packet.json"
DEFAULT_OUT = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding" / "motionzip_sparse_stride2_demo.mp4"
DEFAULT_THUMB = REPO_ROOT / "docs" / "benchmark" / "motionzip_sparse_understanding" / "motionzip_sparse_stride2_demo_thumb.png"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def resolve_repo_path(path_text: str) -> Path:
    path = Path(path_text)
    if path.is_absolute():
        return path
    return (REPO_ROOT / path).resolve()


def draw_panel(frame: np.ndarray, x: int, y: int, w: int, h: int, alpha: float = 0.72) -> None:
    overlay = frame.copy()
    cv2.rectangle(overlay, (x, y), (x + w, y + h), (15, 23, 42), -1)
    cv2.addWeighted(overlay, alpha, frame, 1 - alpha, 0, frame)


def put_text(
    frame: np.ndarray,
    text: str,
    org: tuple[int, int],
    scale: float = 0.62,
    color: tuple[int, int, int] = (245, 248, 255),
    thickness: int = 1,
) -> None:
    cv2.putText(frame, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, color, thickness, cv2.LINE_AA)


def wrap_text(text: str, max_chars: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    current: list[str] = []
    for word in words:
        trial = " ".join(current + [word])
        if len(trial) > max_chars and current:
            lines.append(" ".join(current))
            current = [word]
        else:
            current.append(word)
    if current:
        lines.append(" ".join(current))
    return lines


def event_windows(packet: dict[str, Any]) -> list[dict[str, Any]]:
    windows = []
    for block in packet.get("compressed_sparse_blocks", []):
        source_frames = block.get("source_frames") or []
        if len(source_frames) < 2:
            continue
        extrema = block.get("preserved_extrema", {})
        windows.append(
            {
                "start": int(source_frames[0]),
                "end": int(source_frames[1]),
                "state": str(block.get("rule_policy_state", "unknown")),
                "abstain_reason": block.get("abstain_reason"),
                "peak_velocity": extrema.get("peak_velocity_deg_s"),
                "confidence_floor": extrema.get("confidence_floor"),
                "tags": block.get("tokens", []),
            }
        )
    return windows


def active_window(windows: list[dict[str, Any]], frame_idx: int) -> dict[str, Any] | None:
    for window in windows:
        if window["start"] <= frame_idx <= window["end"]:
            return window
    return None


def draw_timeline(
    frame: np.ndarray,
    frame_idx: int,
    total_frames: int,
    source_frame_span: int,
    tracking_interval: int,
    windows: list[dict[str, Any]],
    y: int,
) -> None:
    x0 = 70
    w = frame.shape[1] - 140
    h = 18
    cv2.rectangle(frame, (x0, y), (x0 + w, y + h), (226, 232, 240), -1)
    span = max(source_frame_span, total_frames, 1)
    for sample in range(0, span + 1, tracking_interval):
        x = x0 + int(w * sample / span)
        color = (148, 163, 184)
        cv2.line(frame, (x, y - 6), (x, y + h + 6), color, 1)
    for window in windows:
        x1 = x0 + int(w * window["start"] / span)
        x2 = x0 + int(w * window["end"] / span)
        color = (37, 99, 235) if window["state"] == "monitor_only" else (217, 119, 6)
        cv2.rectangle(frame, (x1, y - 10), (max(x2, x1 + 3), y + h + 10), color, -1)
    x = x0 + int(w * min(frame_idx, span) / span)
    cv2.line(frame, (x, y - 22), (x, y + h + 22), (239, 68, 68), 3)
    put_text(frame, "sampled tracking ticks", (x0, y + 50), 0.48, (51, 65, 85), 1)
    put_text(frame, "MotionZip event blocks", (x0 + 230, y + 50), 0.48, (51, 65, 85), 1)
    put_text(frame, "current frame", (x0 + 470, y + 50), 0.48, (51, 65, 85), 1)


def draw_overlay(
    frame: np.ndarray,
    frame_idx: int,
    total_frames: int,
    results: dict[str, Any],
    packet: dict[str, Any],
    windows: list[dict[str, Any]],
) -> np.ndarray:
    out = frame.copy()
    h, w = out.shape[:2]
    summary = results["summary"]
    variant = next(
        item for item in results["variants"]
        if item["relative_stride"] == summary["lowest_budget_passing_stride"]
    )
    tracking_interval = int(variant["effective_video_frame_interval"])
    active = active_window(windows, frame_idx)

    draw_panel(out, 24, 20, 575, 168)
    put_text(out, "MotionZip sparse video understanding", (44, 54), 0.78, (255, 255, 255), 2)
    put_text(out, f"Actual video frame: {frame_idx}/{total_frames}", (44, 84), 0.55)
    put_text(out, f"Track every {tracking_interval} video frames: {variant['tracked_pose_samples']} samples", (44, 112), 0.55)
    put_text(out, f"Tracked-frame ratio: {summary['lowest_budget_passing_tracked_frame_ratio'] * 100:.1f}%", (44, 140), 0.55)
    put_text(out, f"Understanding score: {variant['understanding_score'] * 100:.1f}%", (44, 168), 0.55, (191, 219, 254), 2)

    draw_panel(out, w - 475, 20, 450, 235)
    put_text(out, "What Gemma receives", (w - 452, 54), 0.72, (255, 255, 255), 2)
    packet_lines = [
        f"activity: {summary['activity_hint']}",
        f"states: {', '.join(summary['dense_reference_states'])}",
        f"event blocks: {len(packet.get('compressed_sparse_blocks', []))}",
        "raw video: not sent",
        "raw landmarks: not sent",
    ]
    y = 88
    for line in packet_lines:
        put_text(out, line, (w - 452, y), 0.52)
        y += 25
    put_text(out, "Compact evidence only:", (w - 452, y + 8), 0.52, (191, 219, 254), 1)
    y += 34
    for line in wrap_text("event windows, extrema, confidence floors, limits, evidence refs", 45):
        put_text(out, line, (w - 452, y), 0.47, (226, 232, 240), 1)
        y += 22

    draw_panel(out, 24, h - 168, w - 48, 138, alpha=0.82)
    draw_timeline(
        out,
        frame_idx=frame_idx,
        total_frames=total_frames,
        source_frame_span=int(summary["source_frame_span"]),
        tracking_interval=tracking_interval,
        windows=windows,
        y=h - 112,
    )

    if active:
        color = (0, 180, 255) if active["state"] == "abstain" else (96, 165, 250)
        cv2.rectangle(out, (24, 205), (720, 292), (15, 23, 42), -1)
        cv2.rectangle(out, (24, 205), (720, 292), color, 3)
        put_text(out, f"MotionZip block active: {active['state']}", (42, 236), 0.68, (255, 255, 255), 2)
        put_text(out, f"confidence_floor={active['confidence_floor']}  peak_velocity={active['peak_velocity']} deg/s", (42, 264), 0.55)
        reason = active.get("abstain_reason") or "bounded monitor-only evidence"
        put_text(out, f"reason: {reason}", (42, 286), 0.55, (226, 232, 240), 1)

    if frame_idx % tracking_interval == 0:
        cv2.circle(out, (w - 60, h - 60), 18, (34, 197, 94), -1)
        put_text(out, "tracked", (w - 150, h - 54), 0.55, (255, 255, 255), 2)

    return out


def letterbox(frame: np.ndarray, width: int, height: int) -> np.ndarray:
    fh, fw = frame.shape[:2]
    scale = min(width / fw, height / fh)
    nw = int(fw * scale)
    nh = int(fh * scale)
    resized = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_AREA)
    canvas = np.zeros((height, width, 3), dtype=np.uint8)
    canvas[:] = (8, 13, 23)
    x = (width - nw) // 2
    y = (height - nh) // 2
    canvas[y:y + nh, x:x + nw] = resized
    return canvas


def main() -> int:
    parser = argparse.ArgumentParser(description="Render actual-video MotionZip sparse demo.")
    parser.add_argument("--results", default=str(DEFAULT_RESULTS))
    parser.add_argument("--packet", default=str(DEFAULT_PACKET))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--thumb", default=str(DEFAULT_THUMB))
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--render-every", type=int, default=2)
    parser.add_argument("--out-fps", type=float, default=15.0)
    args = parser.parse_args()

    results = load_json(resolve_repo_path(args.results))
    packet = load_json(resolve_repo_path(args.packet))
    video_path = resolve_repo_path(results["summary"]["video"])
    output_path = resolve_repo_path(args.out)
    thumb_path = resolve_repo_path(args.thumb)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise SystemExit(f"Could not open video: {video_path}")
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT)) or int(results["summary"]["source_frame_span"])
    writer = cv2.VideoWriter(
        str(output_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        args.out_fps,
        (args.width, args.height),
    )
    if not writer.isOpened():
        raise SystemExit(f"Could not open writer: {output_path}")

    windows = event_windows(packet)
    thumbnail_written = False
    frame_idx = 0
    rendered = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if frame_idx % args.render_every == 0:
            canvas = letterbox(frame, args.width, args.height)
            rendered_frame = draw_overlay(canvas, frame_idx, total_frames, results, packet, windows)
            writer.write(rendered_frame)
            rendered += 1
            if not thumbnail_written and any(window["start"] <= frame_idx <= window["end"] for window in windows):
                cv2.imwrite(str(thumb_path), rendered_frame)
                thumbnail_written = True
        frame_idx += 1

    cap.release()
    writer.release()
    if not thumbnail_written:
        cap = cv2.VideoCapture(str(video_path))
        ok, frame = cap.read()
        if ok:
            cv2.imwrite(str(thumb_path), draw_overlay(letterbox(frame, args.width, args.height), 0, total_frames, results, packet, windows))
        cap.release()

    print(f"video={output_path}")
    print(f"thumbnail={thumb_path}")
    print(f"rendered_frames={rendered}")
    print(f"source_video={video_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
