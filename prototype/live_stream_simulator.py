"""
live_stream_simulator.py — desktop LIVE_STREAM equivalent of the Android
camera path. Replays a local video at wallclock pace through MediaPipe
PoseLandmarker in LIVE_STREAM mode, drives the GemmaFit pipeline
(subject_selector → smoothed exercise classifier → regional confidence
gate) on the async result callback, and renders an overlay window so the
behavior under realistic backpressure can be inspected without flashing
an APK.

Differences vs `test_subject_selector_video.py`:
  - VIDEO mode is synchronous and never drops frames; LIVE_STREAM drops
    the moment the inference thread falls behind.
  - The callback fires on MediaPipe's worker thread; results return out
    of order with the originating frame, so the simulator pairs each
    result back to the frame buffered at the same timestamp.
  - Wallclock pacing exposes timing bugs (rep counter on uneven dt,
    coach voice cooldown vs event cadence) that VIDEO smoke can't see.

Usage:
    python prototype/live_stream_simulator.py path/to/video.mp4
    python prototype/live_stream_simulator.py path/to/video.mp4 --throttle-fps 5
    python prototype/live_stream_simulator.py path/to/video.mp4 --headless

Hotkeys (in window):
    q          quit
    space      pause / resume
    r          reset subject lock + smoothed classifier
    mouse      click on a candidate to request manual lock
"""
from __future__ import annotations

import argparse
import json
import queue
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np
from mediapipe import Image as MpImage, ImageFormat as MpImageFormat
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import vision as mp_vision

sys.path.insert(0, str(Path(__file__).resolve().parent))
from exercises.core import SmoothedExerciseDetector  # noqa: E402
from multi_person_pose import DEFAULT_MODEL_PATH       # noqa: E402
from regional_confidence import apply_regional_gate    # noqa: E402
from subject_selector import (                         # noqa: E402
    PoseCandidate,
    SubjectLockStatus,
    SubjectSelector,
    SubjectSelectorConfig,
    build_candidate,
)


# ── Result delivered from MediaPipe's worker thread ─────────────────

@dataclass
class _Delivery:
    result: object              # mp_vision.PoseLandmarkerResult
    image_ts_ms: int            # the timestamp we passed to detect_async
    delivered_wall_ms: int      # wallclock time at which the callback fired


# ── Per-frame snapshot for paired rendering ─────────────────────────

@dataclass
class _Snapshot:
    frame_idx: int
    frame_bgr: np.ndarray
    pushed_wall_ms: int


# ── Per-second statistics ───────────────────────────────────────────

@dataclass
class _LiveStats:
    frames_pushed: int = 0
    frames_rendered: int = 0
    callbacks_received: int = 0
    callbacks_dropped_no_frame: int = 0
    label_changes: int = 0
    last_label: str = ""
    callback_lag_sum_ms: float = 0.0
    callback_lag_samples: int = 0
    lock_state_changes: int = 0
    last_lock_state: str = ""

    def average_callback_lag_ms(self) -> float:
        return (self.callback_lag_sum_ms / self.callback_lag_samples
                if self.callback_lag_samples else 0.0)


# ── Simulator ────────────────────────────────────────────────────────

class LiveStreamSimulator:
    def __init__(
        self,
        num_poses: int = 4,
        model_path: Optional[Path] = None,
        kalman_enabled: bool = False,
        smoothing_window: int = 9,
        smoothing_switch_min_streak: int = 4,
        result_queue_size: int = 32,
        frame_buffer_size: int = 256,
        regional_gate_exercise: Optional[str] = None,
    ):
        path = Path(model_path or DEFAULT_MODEL_PATH)
        if not path.exists():
            raise FileNotFoundError(f"PoseLandmarker model not found: {path}")

        self.regional_gate_exercise_override = regional_gate_exercise
        self._result_queue: "queue.Queue[_Delivery]" = queue.Queue(
            maxsize=result_queue_size,
        )
        self._frame_buffer: Dict[int, _Snapshot] = {}
        self._frame_buffer_lock = threading.Lock()
        self._frame_buffer_size = frame_buffer_size

        cfg = SubjectSelectorConfig(kalman_enabled=kalman_enabled)
        self.selector = SubjectSelector(cfg)
        self.smoothed = SmoothedExerciseDetector(
            window_frames=smoothing_window,
            switch_min_streak=smoothing_switch_min_streak,
        )

        opts = mp_vision.PoseLandmarkerOptions(
            base_options=mp_python.BaseOptions(model_asset_path=str(path)),
            running_mode=mp_vision.RunningMode.LIVE_STREAM,
            num_poses=num_poses,
            min_pose_detection_confidence=0.5,
            min_pose_presence_confidence=0.5,
            min_tracking_confidence=0.5,
            result_callback=self._mp_callback,
        )
        self._landmarker = mp_vision.PoseLandmarker.create_from_options(opts)
        self._wall_start_monotonic = time.monotonic()
        # MediaPipe LIVE_STREAM requires strictly increasing timestamps.
        self._last_submitted_ts_ms = -1

        self.stats = _LiveStats()
        self._pending_tap: Optional[Tuple[float, float]] = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        try:
            self._landmarker.close()
        except Exception:
            pass

    # ── MediaPipe callback (runs on worker thread) ──────────────────

    def _mp_callback(self, result, image, timestamp_ms: int) -> None:
        delivered = self._wall_now_ms()
        self.stats.callbacks_received += 1
        try:
            self._result_queue.put_nowait(
                _Delivery(result=result, image_ts_ms=timestamp_ms,
                          delivered_wall_ms=delivered)
            )
        except queue.Full:
            # Drop the oldest delivery to free space — backpressure realism.
            try:
                _ = self._result_queue.get_nowait()
            except queue.Empty:
                pass
            self._result_queue.put_nowait(
                _Delivery(result=result, image_ts_ms=timestamp_ms,
                          delivered_wall_ms=delivered)
            )

    # ── Public API ──────────────────────────────────────────────────

    def push_frame(self, frame_bgr: np.ndarray, frame_idx: int) -> int:
        """Submit a frame to MediaPipe with the current wallclock timestamp."""
        ts_ms = self._wall_now_ms()
        # Enforce strict monotonicity (LIVE_STREAM contract).
        if ts_ms <= self._last_submitted_ts_ms:
            ts_ms = self._last_submitted_ts_ms + 1
        self._last_submitted_ts_ms = ts_ms
        rgb = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2RGB)
        mp_image = MpImage(image_format=MpImageFormat.SRGB, data=rgb)
        snapshot = _Snapshot(
            frame_idx=frame_idx, frame_bgr=frame_bgr.copy(),
            pushed_wall_ms=ts_ms,
        )
        with self._frame_buffer_lock:
            self._frame_buffer[ts_ms] = snapshot
            # Cap size — drop oldest first.
            if len(self._frame_buffer) > self._frame_buffer_size:
                oldest = sorted(self._frame_buffer.keys())[
                    : len(self._frame_buffer) - self._frame_buffer_size
                ]
                for k in oldest:
                    self._frame_buffer.pop(k, None)

        self._landmarker.detect_async(mp_image, ts_ms)
        self.stats.frames_pushed += 1
        return ts_ms

    def pop_pending_render(self) -> Optional[Tuple[_Snapshot, _Delivery, dict]]:
        """Pop one delivery and render-data tuple, or None if no result is ready."""
        try:
            delivery = self._result_queue.get_nowait()
        except queue.Empty:
            return None

        with self._frame_buffer_lock:
            snapshot = self._frame_buffer.pop(delivery.image_ts_ms, None)
        if snapshot is None:
            self.stats.callbacks_dropped_no_frame += 1
            return None

        # Build candidates for the selector.
        candidates = []
        for landmarks in (delivery.result.pose_landmarks or []):
            arr = np.zeros((33, 3), dtype=np.float32)
            for i, lm in enumerate(landmarks):
                arr[i, 0] = lm.x
                arr[i, 1] = lm.y
                arr[i, 2] = float(lm.visibility) if lm.visibility is not None else 0.0
            built = build_candidate(arr)
            if built is not None:
                candidates.append(built)

        # Apply pending tap (set by mouse click on main thread).
        if self._pending_tap is not None:
            tx, ty = self._pending_tap
            self._pending_tap = None
            self.selector.request_tap(tx, ty)

        sel = self.selector.update(candidates)

        smoothed_label = ""
        gate_passed = None
        gate_failed_regions: List[str] = []
        if sel.has_candidate and sel.candidate is not None:
            sm = self.smoothed.update(sel.candidate.landmarks)
            smoothed_label = sm.exercise
            gate_exercise = (
                self.regional_gate_exercise_override or sm.exercise
                if sm.exercise != "unknown" else "squat"
            )
            gate = apply_regional_gate(sel.candidate.landmarks, gate_exercise)
            gate_passed = gate.passed_template
            gate_failed_regions = gate.failed_regions

        # Stats
        if smoothed_label and smoothed_label != self.stats.last_label:
            self.stats.label_changes += 1
            self.stats.last_label = smoothed_label
        lock_str = sel.status.value
        if lock_str != self.stats.last_lock_state:
            self.stats.lock_state_changes += 1
            self.stats.last_lock_state = lock_str
        lag_ms = delivery.delivered_wall_ms - snapshot.pushed_wall_ms
        self.stats.callback_lag_sum_ms += lag_ms
        self.stats.callback_lag_samples += 1
        self.stats.frames_rendered += 1

        return snapshot, delivery, {
            "candidates": candidates,
            "selection": sel,
            "smoothed_label": smoothed_label,
            "gate_passed": gate_passed,
            "gate_failed_regions": gate_failed_regions,
            "callback_lag_ms": lag_ms,
        }

    def reset_subject(self) -> None:
        self.selector.clear_lock()
        self.smoothed.reset()
        self.stats.last_label = ""
        self.stats.last_lock_state = ""

    def request_tap(self, x_norm: float, y_norm: float) -> None:
        # Stored on the simulator; applied on the next pop_pending_render().
        self._pending_tap = (x_norm, y_norm)

    def _wall_now_ms(self) -> int:
        return int((time.monotonic() - self._wall_start_monotonic) * 1000)


# ── Overlay rendering ───────────────────────────────────────────────

_GREEN  = (60, 220, 90)
_GRAY   = (160, 160, 160)
_RED    = (60, 60, 220)
_YELLOW = (40, 220, 220)
_WHITE  = (240, 240, 240)
_BLACK  = (0, 0, 0)


def _draw_box(frame: np.ndarray, c: PoseCandidate, color: Tuple[int, int, int],
              label: str = "") -> None:
    h, w = frame.shape[:2]
    p1 = (int(c.bbox.left * w),  int(c.bbox.top * h))
    p2 = (int(c.bbox.right * w), int(c.bbox.bottom * h))
    cv2.rectangle(frame, p1, p2, color, 2)
    if label:
        cv2.putText(frame, label, (p1[0], max(p1[1] - 6, 12)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 1, cv2.LINE_AA)


def _draw_skeleton(frame: np.ndarray, lm: np.ndarray, color: Tuple[int, int, int]) -> None:
    h, w = frame.shape[:2]
    edges = [
        (11, 12), (11, 13), (13, 15), (12, 14), (14, 16),
        (11, 23), (12, 24), (23, 24), (23, 25), (25, 27),
        (24, 26), (26, 28), (27, 29), (28, 30), (29, 31), (30, 32),
    ]
    for a, b in edges:
        if lm[a, 2] < 0.15 or lm[b, 2] < 0.15:
            continue
        pa = (int(lm[a, 0] * w), int(lm[a, 1] * h))
        pb = (int(lm[b, 0] * w), int(lm[b, 1] * h))
        cv2.line(frame, pa, pb, color, 2, cv2.LINE_AA)
    for i in range(33):
        if lm[i, 2] >= 0.15:
            cv2.circle(frame, (int(lm[i, 0] * w), int(lm[i, 1] * h)),
                       3, color, -1, cv2.LINE_AA)


def _draw_overlay(frame: np.ndarray, snapshot: _Snapshot, delivery: _Delivery,
                  pipeline: dict, stats: _LiveStats, paused: bool) -> None:
    h, w = frame.shape[:2]
    candidates: List[PoseCandidate] = pipeline["candidates"]
    sel = pipeline["selection"]
    smoothed_label = pipeline["smoothed_label"]
    gate_passed = pipeline["gate_passed"]
    gate_failed = pipeline["gate_failed_regions"]
    lag_ms = pipeline["callback_lag_ms"]

    # Non-locked candidates first (gray).
    for i, c in enumerate(candidates):
        if i == sel.active_index:
            continue
        _draw_box(frame, c, _GRAY, label=f"#{i} v={c.avg_visibility:.2f}")

    # Locked candidate (green/yellow depending on lock state).
    if sel.has_candidate and sel.candidate is not None:
        lock_color = (
            _GREEN if sel.status in (
                SubjectLockStatus.LOCKED,
                SubjectLockStatus.AUTO_LOCKED,
                SubjectLockStatus.SINGLE_AUTO,
            ) else _YELLOW
        )
        _draw_box(frame, sel.candidate, lock_color,
                  label=f"track={sel.track_id} {sel.status.value}")
        _draw_skeleton(frame, sel.candidate.landmarks, lock_color)

    # Header with key stats.
    header_lines = [
        f"frame {snapshot.frame_idx}  pushed_t={snapshot.pushed_wall_ms}ms",
        f"lock={sel.status.value}  track_id={sel.track_id}  "
        f"candidates={len(candidates)}",
        f"smoothed_exercise={smoothed_label or '-'}  "
        f"region_gate={('OK' if gate_passed else 'FAIL') if gate_passed is not None else '-'}"
        + (f" failed={gate_failed}" if gate_failed else ""),
        f"callback_lag={lag_ms}ms  pushed={stats.frames_pushed}  "
        f"rendered={stats.frames_rendered}  "
        f"dropped={stats.callbacks_dropped_no_frame}",
        f"label_changes={stats.label_changes}  lock_state_changes={stats.lock_state_changes}"
        + ("   [PAUSED]" if paused else ""),
    ]
    y = 22
    for line in header_lines:
        # Black shadow + white text for legibility on any background.
        cv2.putText(frame, line, (10 + 1, y + 1),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, _BLACK, 2, cv2.LINE_AA)
        cv2.putText(frame, line, (10, y),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, _WHITE, 1, cv2.LINE_AA)
        y += 18


# ── Main loop ───────────────────────────────────────────────────────

def run(
    video_path: Path,
    *,
    speed: float = 1.0,
    throttle_pose_fps: Optional[float] = None,
    headless: bool = False,
    out_log: Optional[Path] = None,
    out_video: Optional[Path] = None,
    max_seconds: Optional[float] = None,
    num_poses: int = 4,
    kalman_enabled: bool = False,
    regional_gate_exercise: Optional[str] = None,
) -> dict:
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise FileNotFoundError(f"Cannot open video: {video_path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    writer = None
    if out_video is not None:
        out_video.parent.mkdir(parents=True, exist_ok=True)
        fourcc = cv2.VideoWriter_fourcc(*"mp4v")
        writer = cv2.VideoWriter(str(out_video), fourcc, max(fps / speed, 5.0),
                                 (width, height))

    window_name = f"GemmaFit LIVE_STREAM — {video_path.name}"
    if not headless:
        cv2.namedWindow(window_name, cv2.WINDOW_NORMAL)

    paused = False
    last_render: Optional[Tuple[_Snapshot, _Delivery, dict]] = None
    pending_render: Optional[Tuple[_Snapshot, _Delivery, dict]] = None
    pose_throttle_dt_s = 1.0 / throttle_pose_fps if throttle_pose_fps else 0.0
    last_pose_push_t = 0.0

    log_events: List[dict] = []

    sim_start = time.monotonic()

    def _mouse_handler(event, x, y, flags, _):
        if event == cv2.EVENT_LBUTTONDOWN:
            sim.request_tap(x / width, y / height)

    with LiveStreamSimulator(
        num_poses=num_poses,
        kalman_enabled=kalman_enabled,
        regional_gate_exercise=regional_gate_exercise,
    ) as sim:
        if not headless:
            cv2.setMouseCallback(window_name, _mouse_handler)

        frame_idx = 0
        try:
            while True:
                if max_seconds is not None and (time.monotonic() - sim_start) >= max_seconds:
                    break

                if not paused:
                    ok, frame = cap.read()
                    if not ok:
                        break
                    target_wall_s = (frame_idx / fps / speed)
                    while time.monotonic() - sim_start < target_wall_s:
                        # Drain any results while we wait.
                        d = sim.pop_pending_render()
                        if d is not None:
                            pending_render = d
                        if not headless:
                            key = cv2.waitKey(1) & 0xFF
                            if _handle_key(key, sim) == "quit":
                                return _finalize(sim, log_events, out_log, sim_start)
                            if key == ord(' '):
                                paused = True
                                break
                        time.sleep(0.001)

                    # Throttle pose pushes (simulate slow CPU).
                    now = time.monotonic()
                    if pose_throttle_dt_s == 0.0 or (now - last_pose_push_t) >= pose_throttle_dt_s:
                        sim.push_frame(frame, frame_idx)
                        last_pose_push_t = now

                    frame_idx += 1

                # Drain results once per main-loop iteration.
                d = sim.pop_pending_render()
                if d is not None:
                    pending_render = d

                # Choose what to render.
                render_target = pending_render or last_render
                if render_target is not None:
                    snapshot, delivery, pipeline = render_target
                    canvas = snapshot.frame_bgr.copy()
                    _draw_overlay(canvas, snapshot, delivery, pipeline,
                                  sim.stats, paused)
                    if writer is not None:
                        writer.write(canvas)
                    if not headless:
                        cv2.imshow(window_name, canvas)
                    log_events.append({
                        "frame_idx":     snapshot.frame_idx,
                        "pushed_t_ms":   snapshot.pushed_wall_ms,
                        "delivered_t_ms": delivery.delivered_wall_ms,
                        "callback_lag_ms": pipeline["callback_lag_ms"],
                        "n_candidates":  len(pipeline["candidates"]),
                        "lock_status":   pipeline["selection"].status.value,
                        "track_id":      pipeline["selection"].track_id,
                        "smoothed_label": pipeline["smoothed_label"],
                        "gate_passed":   pipeline["gate_passed"],
                    })
                    last_render = render_target
                    pending_render = None

                if not headless:
                    key = cv2.waitKey(1) & 0xFF
                    action = _handle_key(key, sim)
                    if action == "quit":
                        break
                    if action == "pause_toggle":
                        paused = not paused
        finally:
            cap.release()
            if writer is not None:
                writer.release()
            if not headless:
                cv2.destroyAllWindows()

        return _finalize(sim, log_events, out_log, sim_start)


def _handle_key(key: int, sim: LiveStreamSimulator) -> Optional[str]:
    if key == 0xFF:
        return None
    if key == ord('q') or key == 27:
        return "quit"
    if key == ord(' '):
        return "pause_toggle"
    if key == ord('r'):
        sim.reset_subject()
        return "reset"
    return None


def _finalize(sim: LiveStreamSimulator, events: List[dict],
              out_log: Optional[Path], sim_start: float) -> dict:
    elapsed = time.monotonic() - sim_start
    stats = sim.stats
    summary = {
        "elapsed_s": round(elapsed, 2),
        "frames_pushed": stats.frames_pushed,
        "frames_rendered": stats.frames_rendered,
        "callbacks_received": stats.callbacks_received,
        "callbacks_dropped_no_frame": stats.callbacks_dropped_no_frame,
        "input_fps":     round(stats.frames_pushed / elapsed, 2) if elapsed > 0 else 0.0,
        "callback_fps":  round(stats.callbacks_received / elapsed, 2) if elapsed > 0 else 0.0,
        "rendered_fps":  round(stats.frames_rendered / elapsed, 2) if elapsed > 0 else 0.0,
        "avg_callback_lag_ms": round(stats.average_callback_lag_ms(), 2),
        "label_changes":      stats.label_changes,
        "lock_state_changes": stats.lock_state_changes,
        "last_label":          stats.last_label,
        "last_lock_state":     stats.last_lock_state,
    }
    if out_log is not None:
        out_log.parent.mkdir(parents=True, exist_ok=True)
        out_log.write_text(json.dumps({
            "summary": summary,
            "events":  events,
        }, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("video", type=Path, help="Path to test video")
    ap.add_argument("--speed", type=float, default=1.0,
                    help="Wallclock playback speed (1.0 = real time)")
    ap.add_argument("--throttle-fps", type=float, default=None,
                    help="Cap pose-detection submissions to N fps to simulate slow CPU")
    ap.add_argument("--headless", action="store_true",
                    help="Run without cv2 window (CI / regression)")
    ap.add_argument("--out-log", type=Path, default=None,
                    help="Write per-frame trace + summary JSON here")
    ap.add_argument("--out-video", type=Path, default=None,
                    help="Optional annotated MP4 output")
    ap.add_argument("--max-seconds", type=float, default=None,
                    help="Stop after N wallclock seconds (regression cap)")
    ap.add_argument("--num-poses", type=int, default=4)
    ap.add_argument("--kalman", action="store_true",
                    help="Enable Kalman re-match in subject_selector")
    ap.add_argument("--regional-gate-exercise", default=None,
                    help="Force regional gate to use this exercise template")
    args = ap.parse_args()

    summary = run(
        video_path=args.video,
        speed=args.speed,
        throttle_pose_fps=args.throttle_fps,
        headless=args.headless,
        out_log=args.out_log,
        out_video=args.out_video,
        max_seconds=args.max_seconds,
        num_poses=args.num_poses,
        kalman_enabled=args.kalman,
        regional_gate_exercise=args.regional_gate_exercise,
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
