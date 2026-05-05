"""
multi_person_pose.py — MediaPipe Tasks PoseLandmarker wrapper supporting
up to N candidates per frame, matching the Android pipeline's
`PoseLandmarker.PoseLandmarkerOptions.builder().setNumPoses(...)` setup.

This replaces the legacy `mp.solutions.pose` API (single-person only) used
by the older `extract_landmarks.py`. The output is a list of
`PoseCandidate`-shaped numpy arrays the prototype's subject_selector and
kinematics modules can consume.

Usage:
    from multi_person_pose import MultiPersonPose
    detector = MultiPersonPose(num_poses=4)
    for frame_idx, ts_ms, candidates in detector.iter_video("foo.mp4"):
        # candidates is a list of (33, 3) numpy arrays — (x, y, visibility)
        ...
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, List, Optional, Tuple

import cv2
import numpy as np
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision as mp_vision
from mediapipe import Image as MpImage, ImageFormat as MpImageFormat


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MODEL_PATH = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "pose_landmarker_lite.task"


@dataclass
class FrameCandidates:
    """Per-frame multi-person detection result."""
    frame_index: int
    timestamp_ms: int
    width: int
    height: int
    candidates: List[np.ndarray]   # each: (33, 3) float32 — x, y, visibility


class MultiPersonPose:
    """Stateful wrapper for the Tasks PoseLandmarker in VIDEO running mode."""

    def __init__(
        self,
        num_poses: int = 4,
        min_detection_confidence: float = 0.5,
        min_pose_presence_confidence: float = 0.5,
        min_tracking_confidence: float = 0.5,
        model_path: Optional[Path] = None,
    ):
        path = Path(model_path or DEFAULT_MODEL_PATH)
        if not path.exists():
            raise FileNotFoundError(
                f"pose_landmarker model not found at {path}. "
                "Copy it from app/src/main/assets/ or download from "
                "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
            )

        base_opts = mp_python.BaseOptions(model_asset_path=str(path))
        self.options = mp_vision.PoseLandmarkerOptions(
            base_options=base_opts,
            running_mode=mp_vision.RunningMode.VIDEO,
            num_poses=num_poses,
            min_pose_detection_confidence=min_detection_confidence,
            min_pose_presence_confidence=min_pose_presence_confidence,
            min_tracking_confidence=min_tracking_confidence,
        )
        self._landmarker: Optional[mp_vision.PoseLandmarker] = None
        self.num_poses = num_poses

    def __enter__(self) -> "MultiPersonPose":
        self._landmarker = mp_vision.PoseLandmarker.create_from_options(self.options)
        return self

    def __exit__(self, *exc) -> None:
        if self._landmarker is not None:
            self._landmarker.close()
        self._landmarker = None

    def detect_for_video(self, frame_bgr: np.ndarray, timestamp_ms: int) -> List[np.ndarray]:
        """Run pose detection on a single BGR frame; returns up to `num_poses` candidates."""
        if self._landmarker is None:
            raise RuntimeError("Use as a context manager: `with MultiPersonPose() as p: ...`")
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        mp_image = MpImage(image_format=MpImageFormat.SRGB, data=rgb)
        result = self._landmarker.detect_for_video(mp_image, timestamp_ms)

        candidates: List[np.ndarray] = []
        for landmarks in (result.pose_landmarks or []):
            arr = np.zeros((33, 3), dtype=np.float32)
            for i, lm in enumerate(landmarks):
                arr[i, 0] = lm.x
                arr[i, 1] = lm.y
                # MediaPipe Tasks .visibility is a normalized presence probability
                arr[i, 2] = float(lm.visibility) if lm.visibility is not None else 0.0
            candidates.append(arr)
        return candidates

    def iter_video(
        self,
        path: str | os.PathLike,
        sample_every_ms: Optional[int] = None,
    ) -> Iterator[FrameCandidates]:
        """
        Iterate a video file, yielding (frame_index, timestamp_ms, candidates).
        If sample_every_ms is given, frames are subsampled by timestamp to
        match the Android pipeline's analysis cadence (~125 ms = 8 fps).
        """
        cap = cv2.VideoCapture(str(path))
        if not cap.isOpened():
            raise FileNotFoundError(f"Cannot open video: {path}")
        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        try:
            frame_idx = 0
            last_emit_ms = -1
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                ts_ms = int(round(frame_idx / fps * 1000))
                if sample_every_ms is None or (ts_ms - last_emit_ms) >= sample_every_ms:
                    candidates = self.detect_for_video(frame, ts_ms)
                    yield FrameCandidates(
                        frame_index=frame_idx,
                        timestamp_ms=ts_ms,
                        width=width,
                        height=height,
                        candidates=candidates,
                    )
                    last_emit_ms = ts_ms
                frame_idx += 1
        finally:
            cap.release()


def landmarks_to_visibility_summary(landmarks: np.ndarray) -> Tuple[float, int]:
    """Return (mean_visibility, count_above_0.15) for a (33, 3) array."""
    if landmarks.shape != (33, 3):
        return 0.0, 0
    vis = landmarks[:, 2]
    return float(vis.mean()), int((vis > 0.15).sum())


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("video", help="Path to a video file")
    ap.add_argument("--num-poses", type=int, default=4)
    ap.add_argument("--every-ms", type=int, default=125,
                    help="Sample cadence in ms (default 125 = 8 fps to match Android)")
    ap.add_argument("--max-frames", type=int, default=20)
    args = ap.parse_args()

    with MultiPersonPose(num_poses=args.num_poses) as pose:
        for i, fc in enumerate(pose.iter_video(args.video, sample_every_ms=args.every_ms)):
            if i >= args.max_frames:
                break
            n = len(fc.candidates)
            stats = [landmarks_to_visibility_summary(c) for c in fc.candidates]
            stats_str = ", ".join(f"vis={v:.2f}/{cnt}" for v, cnt in stats)
            print(f"frame={fc.frame_index:5d}  t={fc.timestamp_ms:6d}ms  "
                  f"candidates={n}  [{stats_str}]")
