"""
synthesize_two_person_video.py — build a side-by-side two-person test
clip by horizontally tiling two single-person videos. Used to validate
the subject selector's auto-pick + persistence on real pose data when
no genuine multi-person fitness clip is available.

Output is written to test_assets/videos/synthetic_two_person_squat.mp4.
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LEFT  = PROJECT_ROOT / "test_assets" / "videos" / "squat_wikimedia_01.webm"
DEFAULT_RIGHT = PROJECT_ROOT / "test_assets" / "videos" / "pushup_cdc_01.webm"
DEFAULT_OUT   = PROJECT_ROOT / "test_assets" / "videos" / "synthetic_two_person_squat.mp4"

TARGET_HEIGHT = 480
MAX_FRAMES = 240


def _resize_to_height(frame: np.ndarray, target_h: int) -> np.ndarray:
    h, w = frame.shape[:2]
    scale = target_h / h
    new_w = max(1, int(round(w * scale)))
    return cv2.resize(frame, (new_w, target_h), interpolation=cv2.INTER_AREA)


def _read_n_frames(path: Path, n: int) -> list[np.ndarray]:
    cap = cv2.VideoCapture(str(path))
    frames: list[np.ndarray] = []
    while len(frames) < n:
        ok, f = cap.read()
        if not ok:
            break
        frames.append(f)
    cap.release()
    return frames


def synthesize(left_path: Path, right_path: Path, out_path: Path,
               max_frames: int = MAX_FRAMES, target_h: int = TARGET_HEIGHT) -> None:
    left  = _read_n_frames(left_path,  max_frames)
    right = _read_n_frames(right_path, max_frames)
    if not left or not right:
        raise RuntimeError(f"Failed to read frames from one of {left_path} / {right_path}")

    n = min(len(left), len(right))
    left  = [_resize_to_height(f, target_h) for f in left[:n]]
    right = [_resize_to_height(f, target_h) for f in right[:n]]
    width = left[0].shape[1] + right[0].shape[1]
    out_path.parent.mkdir(parents=True, exist_ok=True)

    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(out_path), fourcc, 30.0, (width, target_h))
    for l, r in zip(left, right):
        canvas = np.concatenate([l, r], axis=1)
        writer.write(canvas)
    writer.release()
    print(f"Wrote {out_path}: {n} frames, {width}x{target_h}, 30 fps")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--left",  default=str(DEFAULT_LEFT))
    ap.add_argument("--right", default=str(DEFAULT_RIGHT))
    ap.add_argument("--out",   default=str(DEFAULT_OUT))
    ap.add_argument("--max-frames", type=int, default=MAX_FRAMES)
    ap.add_argument("--height",     type=int, default=TARGET_HEIGHT)
    args = ap.parse_args()

    synthesize(
        left_path  = Path(args.left),
        right_path = Path(args.right),
        out_path   = Path(args.out),
        max_frames = args.max_frames,
        target_h   = args.height,
    )


if __name__ == "__main__":
    main()
