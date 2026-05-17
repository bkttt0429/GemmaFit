"""
Validate the Python Kalman subject re-match path on a real video.

Outputs:
  - <label>_kalman_summary.json
  - <label>_kalman_trace.json
  - <label>_kalman_overlay.mp4

Example:
  python prototype/validate_kalman_tracking_video.py ^
      --video test_assets/videos/pixel_line_error_1774202137014.mp4 ^
      --label pixel_line_error --max-frames 80
"""
from __future__ import annotations

import argparse
import json
import time
from collections import Counter
from pathlib import Path
from typing import List, Optional, Tuple

import cv2
import numpy as np

from multi_person_pose import MultiPersonPose
from subject_selector import (
    PoseCandidate,
    SubjectAppearanceSignature,
    SubjectLockStatus,
    SubjectSelector,
    SubjectSelectorConfig,
    build_candidate,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VIDEO = PROJECT_ROOT / "test_assets" / "videos" / "pixel_line_error_1774202137014.mp4"
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "kalman_tracking_video"

SKELETON_EDGES: Tuple[Tuple[int, int], ...] = (
    (11, 12),
    (11, 13), (13, 15),
    (12, 14), (14, 16),
    (11, 23), (12, 24),
    (23, 24),
    (23, 25), (25, 27),
    (24, 26), (26, 28),
)


def _draw_candidate(
    frame: np.ndarray,
    candidate: PoseCandidate,
    label: str,
    color: Tuple[int, int, int],
    selected: bool,
    draw_skeleton: bool = True,
) -> None:
    h, w = frame.shape[:2]
    left = int(candidate.bbox.left * w)
    top = int(candidate.bbox.top * h)
    right = int(candidate.bbox.right * w)
    bottom = int(candidate.bbox.bottom * h)
    thickness = 3 if selected else 1
    cv2.rectangle(frame, (left, top), (right, bottom), color, thickness)

    arr = candidate.landmarks
    if draw_skeleton:
        for a, b in SKELETON_EDGES:
            if arr[a, 2] < 0.20 or arr[b, 2] < 0.20:
                continue
            ax, ay = int(arr[a, 0] * w), int(arr[a, 1] * h)
            bx, by = int(arr[b, 0] * w), int(arr[b, 1] * h)
            cv2.line(frame, (ax, ay), (bx, by), color, thickness, cv2.LINE_AA)

    cx = int(candidate.center_x * w)
    cy = int(candidate.center_y * h)
    cv2.circle(frame, (cx, cy), 5 if selected else 3, color, -1, cv2.LINE_AA)

    text_y = max(20, top - 8)
    cv2.putText(
        frame,
        label,
        (left, text_y),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.5,
        color,
        1,
        cv2.LINE_AA,
    )


def _put_header(frame: np.ndarray, lines: List[str]) -> None:
    x = 12
    y = 24
    line_h = 22
    width = min(frame.shape[1] - 24, 760)
    height = 12 + line_h * len(lines)
    overlay = frame.copy()
    cv2.rectangle(overlay, (6, 6), (6 + width, 6 + height), (15, 23, 42), -1)
    cv2.addWeighted(overlay, 0.74, frame, 0.26, 0, frame)
    for line in lines:
        cv2.putText(
            frame,
            line,
            (x, y),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.56,
            (235, 245, 255),
            1,
            cv2.LINE_AA,
        )
        y += line_h


def _appearance_signature_from_candidate(
    frame: np.ndarray,
    candidate: PoseCandidate,
) -> Optional[SubjectAppearanceSignature]:
    h, w = frame.shape[:2]
    arr = candidate.landmarks

    def rect_for_keypoints(
        indices: Tuple[int, ...],
        pad_x_scale: float,
        pad_y_scale: float,
        fallback_top_scale: float,
        fallback_bottom_scale: float,
    ) -> Tuple[int, int, int, int]:
        visible = [
            idx for idx in indices
            if idx < arr.shape[0] and arr[idx, 2] >= 0.25
        ]
        if visible:
            xs = arr[visible, 0]
            ys = arr[visible, 1]
            pad_x = max(0.02, candidate.bbox.width * pad_x_scale)
            pad_y = max(0.02, candidate.bbox.height * pad_y_scale)
            left_n = max(0.0, float(xs.min()) - pad_x)
            right_n = min(1.0, float(xs.max()) + pad_x)
            top_n = max(0.0, float(ys.min()) - pad_y)
            bottom_n = min(1.0, float(ys.max()) + pad_y)
        else:
            left_n = candidate.bbox.left
            right_n = candidate.bbox.right
            top_n = candidate.bbox.top + candidate.bbox.height * fallback_top_scale
            bottom_n = candidate.bbox.top + candidate.bbox.height * fallback_bottom_scale
        left = int(np.floor(left_n * w))
        right = int(np.ceil(right_n * w))
        top = int(np.floor(top_n * h))
        bottom = int(np.ceil(bottom_n * h))
        left = max(0, min(w - 1, left))
        right = max(left + 1, min(w, right))
        top = max(0, min(h - 1, top))
        bottom = max(top + 1, min(h, bottom))
        return left, top, right, bottom

    def hist_for_rect(rect: Tuple[int, int, int, int]) -> np.ndarray:
        left, top, right, bottom = rect
        crop = frame[top:bottom, left:right]
        if crop.size == 0 or crop.shape[0] < 4 or crop.shape[1] < 4:
            return np.zeros(12 * 6 * 4, dtype=np.float32)
        hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
        hist = cv2.calcHist([hsv], [0, 1, 2], None, [12, 6, 4], [0, 180, 0, 256, 0, 256])
        hist = hist.astype(np.float32).reshape(-1)
        total = float(hist.sum())
        if total <= 0.0:
            return np.zeros(12 * 6 * 4, dtype=np.float32)
        return hist / total

    def hist_for_keypoint_patches(indices: Tuple[int, ...]) -> np.ndarray:
        radius = max(3, int(round(min(w, h) * 0.012)))
        hist = np.zeros(12 * 6 * 4, dtype=np.float32)
        used = 0
        for idx in indices:
            if idx >= arr.shape[0] or arr[idx, 2] < 0.25:
                continue
            cx = int(round(float(arr[idx, 0]) * w))
            cy = int(round(float(arr[idx, 1]) * h))
            left = max(0, cx - radius)
            right = min(w, cx + radius + 1)
            top = max(0, cy - radius)
            bottom = min(h, cy + radius + 1)
            patch = frame[top:bottom, left:right]
            if patch.size == 0 or patch.shape[0] < 2 or patch.shape[1] < 2:
                continue
            hsv = cv2.cvtColor(patch, cv2.COLOR_BGR2HSV)
            patch_hist = cv2.calcHist([hsv], [0, 1, 2], None, [12, 6, 4], [0, 180, 0, 256, 0, 256])
            hist += patch_hist.astype(np.float32).reshape(-1)
            used += 1
        if used <= 0:
            return hist
        total = float(hist.sum())
        if total <= 0.0:
            return np.zeros_like(hist, dtype=np.float32)
        return hist / total

    upper = rect_for_keypoints((11, 12, 13, 14, 15, 16, 23, 24), 0.16, 0.12, 0.00, 0.65)
    lower = rect_for_keypoints((23, 24, 25, 26, 27, 28), 0.14, 0.10, 0.40, 1.00)
    full = (
        int(np.floor(max(0.0, candidate.bbox.left) * w)),
        int(np.floor(max(0.0, candidate.bbox.top) * h)),
        int(np.ceil(min(1.0, candidate.bbox.right) * w)),
        int(np.ceil(min(1.0, candidate.bbox.bottom) * h)),
    )
    full_left = max(0, min(w - 1, full[0]))
    full_top = max(0, min(h - 1, full[1]))
    full_right = max(full_left + 1, min(w, full[2]))
    full_bottom = max(full_top + 1, min(h, full[3]))
    full = (full_left, full_top, full_right, full_bottom)

    hist = np.concatenate([
        hist_for_keypoint_patches((11, 12, 13, 14, 15, 16, 23, 24)) * 0.25,
        hist_for_keypoint_patches((23, 24, 25, 26, 27, 28)) * 0.10,
        hist_for_rect(upper) * 0.40,
        hist_for_rect(lower) * 0.20,
        hist_for_rect(full) * 0.05,
    ]).astype(np.float32)
    if float(hist.sum()) <= 0.0:
        return None
    return SubjectAppearanceSignature(hist)


def _longest_positive_track_run(trace: List[dict]) -> int:
    longest = 0
    current = 0
    last: Optional[int] = None
    for row in trace:
        tid = int(row["track_id"])
        if tid >= 0 and tid == last:
            current += 1
        elif tid >= 0:
            current = 1
            last = tid
        else:
            longest = max(longest, current)
            current = 0
            last = None
        longest = max(longest, current)
    return longest


def _count_track_switches(trace: List[dict]) -> int:
    switches = 0
    last: Optional[int] = None
    for row in trace:
        tid = int(row["track_id"])
        if tid < 0:
            continue
        if last is not None and tid != last:
            switches += 1
        last = tid
    return switches


def _count_identity_switches(trace: List[dict]) -> int:
    switches = 0
    last_consistent: Optional[bool] = None
    for row in trace:
        current = row.get("identity_consistent")
        if current is None:
            continue
        current_bool = bool(current)
        if last_consistent is True and current_bool is False:
            switches += 1
        last_consistent = current_bool
    return switches


def _count_weak_reacquire_frames(trace: List[dict], min_similarity: float = 0.56) -> int:
    weak = 0
    previous_held = False
    for row in trace:
        similarity = row.get("appearance_similarity_to_lock")
        if (
            previous_held
            and row.get("selected_center") is not None
            and similarity is not None
            and float(similarity) < min_similarity
        ):
            weak += 1
        previous_held = bool(row.get("held_frame"))
    return weak


def _run_validation(
    video_path: Path,
    out_dir: Path,
    label: str,
    max_frames: int,
    every_ms: int,
    num_poses: int,
    auto_tap_first_candidate: bool,
    start_ms: int,
    tap_x: Optional[float],
    tap_y: Optional[float],
    min_selected_ratio: float,
) -> dict:
    if not video_path.exists():
        raise FileNotFoundError(video_path)

    out_dir.mkdir(parents=True, exist_ok=True)
    trace_path = out_dir / f"{label}_kalman_trace.json"
    summary_path = out_dir / f"{label}_kalman_summary.json"
    overlay_path = out_dir / f"{label}_kalman_overlay.mp4"

    cfg = SubjectSelectorConfig(
        kalman_enabled=True,
        subject_lost_frames=5,
        subject_min_visibility=0.10,
    )
    selector = SubjectSelector(cfg)

    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise FileNotFoundError(f"Cannot open video: {video_path}")

    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    out_fps = 1000.0 / every_ms
    writer = cv2.VideoWriter(
        str(overlay_path),
        cv2.VideoWriter_fourcc(*"mp4v"),
        out_fps,
        (width, height),
    )
    if not writer.isOpened():
        cap.release()
        raise RuntimeError(f"Cannot create output video: {overlay_path}")

    trace: List[dict] = []
    frame_idx = 0
    last_emit_ms = -1
    select_seconds = 0.0
    detect_seconds = 0.0
    started = time.time()
    tap_applied = False
    tap_point: Optional[Tuple[float, float]] = None
    target_signature: Optional[SubjectAppearanceSignature] = None

    with MultiPersonPose(num_poses=num_poses) as pose:
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                ts_ms = int(round(frame_idx / fps * 1000))
                frame_idx += 1
                if ts_ms < start_ms:
                    continue
                if (ts_ms - last_emit_ms) < every_ms:
                    continue
                if max_frames > 0 and len(trace) >= max_frames:
                    break

                detect_start = time.perf_counter()
                raw_candidates = pose.detect_for_video(frame, ts_ms)
                detect_seconds += time.perf_counter() - detect_start

                candidates: List[PoseCandidate] = []
                for arr in raw_candidates:
                    built = build_candidate(arr, keypoint_visibility_floor=cfg.keypoint_visibility_floor)
                    if built is not None:
                        built.appearance = _appearance_signature_from_candidate(frame, built)
                        candidates.append(built)

                if tap_x is not None and tap_y is not None and not tap_applied and candidates:
                    tap_point = (float(tap_x), float(tap_y))
                    selector.clear_lock()
                    selector.request_tap(tap_point[0], tap_point[1])
                    tap_applied = True
                elif auto_tap_first_candidate and not tap_applied and candidates:
                    # Simulate a user choosing the primary visible subject. This
                    # validates the locked-subject Kalman re-match path instead
                    # of the cold-start auto-lock path.
                    target = max(candidates, key=lambda c: (c.bbox.area, c.avg_visibility))
                    tap_point = (target.center_x, target.center_y)
                    selector.request_tap(target.center_x, target.center_y)
                    tap_applied = True

                select_start = time.perf_counter()
                selected = selector.update(candidates, timestamp_ms=ts_ms)
                select_seconds += time.perf_counter() - select_start

                appearance_similarity: Optional[float] = None
                identity_consistent: Optional[bool] = None
                match_margin: Optional[float] = None
                if selected.candidate is not None and selected.candidate.appearance is not None:
                    if target_signature is None:
                        target_signature = selected.candidate.appearance
                    appearance_similarity = target_signature.similarity(selected.candidate.appearance)
                    identity_consistent = appearance_similarity >= 0.42
                    match_margin = selected.candidate.match_margin

                selected_source_index = selected.active_index
                hold_frame = selected.candidate is None and "subject_hold" in selected.trust_flags
                for i, candidate in enumerate(candidates):
                    is_selected = selected.has_candidate and i == selected_source_index
                    color = (64, 220, 120) if is_selected else (128, 156, 180)
                    score = candidate.track_score if is_selected else 0.0
                    _draw_candidate(
                        frame,
                        candidate,
                        label=f"cand={i} vis={candidate.avg_visibility:.2f} score={score:.2f}",
                        color=color,
                        selected=is_selected,
                        draw_skeleton=is_selected or not hold_frame,
                    )

                row = {
                    "sample_index": len(trace),
                    "frame": frame_idx - 1,
                    "timestamp_ms": ts_ms,
                    "raw_candidates": len(raw_candidates),
                    "candidates_after_gate": len(candidates),
                    "active_index": selected.active_index,
                    "track_id": selected.track_id,
                    "status": selected.status.value,
                    "reason": selected.reason,
                    "trust_flags": selected.trust_flags,
                    "selected_center": (
                        [
                            round(float(selected.candidate.center_x), 5),
                            round(float(selected.candidate.center_y), 5),
                        ]
                        if selected.candidate is not None
                        else None
                    ),
                    "selected_area": (
                        round(float(selected.candidate.bbox.area), 6)
                        if selected.candidate is not None
                        else None
                    ),
                    "selected_visibility": (
                        round(float(selected.candidate.avg_visibility), 5)
                        if selected.candidate is not None
                        else None
                    ),
                    "kalman_track_score": (
                        round(float(selected.candidate.track_score), 5)
                        if selected.candidate is not None
                        else None
                    ),
                    "appearance_similarity_to_lock": (
                        round(float(appearance_similarity), 5)
                        if appearance_similarity is not None
                        else None
                    ),
                    "selected_appearance_score": (
                        round(float(selected.candidate.appearance_score), 5)
                        if selected.candidate is not None
                        else None
                    ),
                    "top2_match_margin": (
                        round(float(match_margin), 5)
                        if match_margin is not None
                        else None
                    ),
                    "identity_consistent": identity_consistent,
                    "held_frame": hold_frame,
                    "candidate_centers": [
                        [round(float(c.center_x), 5), round(float(c.center_y), 5)]
                        for c in candidates
                    ],
                    "candidate_appearance_scores": [
                        round(float(c.appearance_score), 5) for c in candidates
                    ],
                    "candidate_match_margins": [
                        round(float(c.match_margin), 5) for c in candidates
                    ],
                    "candidate_visibilities": [
                        round(float(c.avg_visibility), 5) for c in candidates
                    ],
                    "candidate_areas": [
                        round(float(c.bbox.area), 6) for c in candidates
                    ],
                    "tap_point": (
                        [round(float(tap_point[0]), 5), round(float(tap_point[1]), 5)]
                        if tap_point is not None
                        else None
                    ),
                }
                trace.append(row)

                header = [
                    f"GemmaFit Kalman tracking validation | {label}",
                    f"frame={row['frame']} t={ts_ms}ms status={row['status']} track={row['track_id']} active={row['active_index']}",
                    f"raw={row['raw_candidates']} gated={row['candidates_after_gate']} score={row['kalman_track_score']} reason={row['reason'] or '-'}",
                    f"appearance={row['appearance_similarity_to_lock']} margin={row['top2_match_margin']} held={row['held_frame']}",
                ]
                if tap_point is not None:
                    header.append(f"manual-lock simulation tap=({tap_point[0]:.3f}, {tap_point[1]:.3f})")
                _put_header(frame, header)
                writer.write(frame)
                last_emit_ms = ts_ms
        finally:
            cap.release()
            writer.release()

    elapsed_s = time.time() - started
    statuses = Counter(row["status"] for row in trace)
    candidate_counts = Counter(int(row["candidates_after_gate"]) for row in trace)
    positive_tracks = sorted({int(row["track_id"]) for row in trace if int(row["track_id"]) >= 0})
    frames_with_candidate = sum(1 for row in trace if int(row["candidates_after_gate"]) > 0)
    locked_frames = sum(
        1
        for row in trace
        if row["status"] in {SubjectLockStatus.SINGLE_AUTO.value, SubjectLockStatus.AUTO_LOCKED.value, SubjectLockStatus.LOCKED.value}
    )
    selected_frames = sum(1 for row in trace if row.get("selected_center") is not None)
    selected_ratio = selected_frames / max(1, len(trace))
    track_switches = _count_track_switches(trace)
    identity_switches = _count_identity_switches(trace)
    wrong_person_frames = sum(1 for row in trace if row.get("identity_consistent") is False)
    hold_frames = sum(1 for row in trace if row.get("held_frame") is True)
    weak_reacquire_frames = _count_weak_reacquire_frames(trace)
    appearance_values = [
        float(row["appearance_similarity_to_lock"])
        for row in trace
        if row.get("appearance_similarity_to_lock") is not None
    ]
    match_margins = [
        float(row["top2_match_margin"])
        for row in trace
        if row.get("top2_match_margin") is not None
    ]

    assertions = [
        {
            "name": "video produced analyzed frames",
            "passed": len(trace) > 0,
            "detail": f"frames={len(trace)}",
        },
        {
            "name": "pose detector produced at least one usable candidate",
            "passed": frames_with_candidate > 0,
            "detail": f"frames_with_candidate={frames_with_candidate}/{len(trace)}",
        },
        {
            "name": "kalman path enabled",
            "passed": cfg.kalman_enabled is True,
            "detail": f"kalman_enabled={cfg.kalman_enabled}",
        },
        {
            "name": "track id stayed stable",
            "passed": track_switches == 0 and len(positive_tracks) <= 1,
            "detail": f"track_ids={positive_tracks}, switches={track_switches}",
        },
        {
            "name": "identity did not switch",
            "passed": identity_switches == 0 and wrong_person_frames == 0,
            "detail": f"identity_switches={identity_switches}, wrong_person_frames={wrong_person_frames}, hold_frames={hold_frames}",
        },
        {
            "name": "reacquire after hold required strong identity evidence",
            "passed": weak_reacquire_frames == 0,
            "detail": f"weak_reacquire_frames={weak_reacquire_frames}",
        },
        {
            "name": "no SUBJECT_LOST during sampled run",
            "passed": statuses.get(SubjectLockStatus.SUBJECT_LOST.value, 0) == 0,
            "detail": f"subject_lost={statuses.get(SubjectLockStatus.SUBJECT_LOST.value, 0)}",
        },
        {
            "name": "locked on most candidate frames",
            "passed": frames_with_candidate == 0 or locked_frames / frames_with_candidate >= 0.85,
            "detail": f"locked_frames={locked_frames}, candidate_frames={frames_with_candidate}",
        },
        {
            "name": "selected target coverage meets requested ratio",
            "passed": min_selected_ratio <= 0.0 or selected_ratio >= min_selected_ratio,
            "detail": f"selected_frames={selected_frames}/{len(trace)} ratio={selected_ratio:.3f} required={min_selected_ratio:.3f}",
        },
        {
            "name": "kalman scores are bounded",
            "passed": all(
                row["kalman_track_score"] is None or 0.0 <= float(row["kalman_track_score"]) <= 1.0
                for row in trace
            ),
            "detail": "scores in [0, 1] or null",
        },
    ]

    summary = {
        "label": label,
        "video": str(video_path),
        "outputs": {
            "overlay_video": str(overlay_path),
            "trace_json": str(trace_path),
            "summary_json": str(summary_path),
        },
        "config": {
            "kalman_enabled": cfg.kalman_enabled,
            "kalman_chi2_gate_3df": cfg.kalman_chi2_gate_3df,
            "kalman_default_dt_s": cfg.kalman_default_dt_s,
            "subject_lost_frames": cfg.subject_lost_frames,
            "sample_every_ms": every_ms,
            "start_ms": start_ms,
            "num_poses": num_poses,
            "auto_tap_first_candidate": auto_tap_first_candidate,
            "min_selected_ratio": min_selected_ratio,
            "identity_score_weights": {
                "kalman_motion": 0.45,
                "keypoint": 0.25,
                "appearance": 0.25,
                "area_visibility": 0.05,
            },
            "identity_thresholds": {
                "appearance_accept": 0.42,
                "appearance_strong": 0.55,
                "reacquire_accept": 0.56,
                "reacquire_margin": 0.10,
            },
            "tap_point": (
                [round(float(tap_point[0]), 5), round(float(tap_point[1]), 5)]
                if tap_point is not None
                else None
            ),
        },
        "video_info": {
            "source_fps": fps,
            "width": width,
            "height": height,
            "output_fps": out_fps,
        },
        "summary": {
            "frames_analyzed": len(trace),
            "frames_with_candidate": frames_with_candidate,
            "status_distribution": dict(statuses),
            "candidate_count_distribution": {str(k): v for k, v in sorted(candidate_counts.items())},
            "distinct_track_ids": positive_tracks,
            "track_switches": track_switches,
            "longest_positive_track_run": _longest_positive_track_run(trace),
            "locked_frames": locked_frames,
            "selected_frames": selected_frames,
            "selected_ratio": round(selected_ratio, 5),
            "multi_person_frames": sum(1 for row in trace if int(row["candidates_after_gate"]) >= 2),
            "identity_switches": identity_switches,
            "wrong_person_frames": wrong_person_frames,
            "hold_frames": hold_frames,
            "weak_reacquire_frames": weak_reacquire_frames,
            "min_appearance_similarity": round(min(appearance_values), 5) if appearance_values else None,
            "mean_appearance_similarity": (
                round(sum(appearance_values) / len(appearance_values), 5)
                if appearance_values
                else None
            ),
            "min_top2_match_margin": round(min(match_margins), 5) if match_margins else None,
            "elapsed_s": round(elapsed_s, 3),
            "detect_seconds": round(detect_seconds, 4),
            "select_seconds": round(select_seconds, 6),
            "avg_select_ms": round((select_seconds / max(1, len(trace))) * 1000.0, 4),
        },
        "assertions": assertions,
    }

    trace_path.write_text(json.dumps(trace, indent=2, ensure_ascii=False), encoding="utf-8")
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", default=str(DEFAULT_VIDEO))
    parser.add_argument("--label", default="pixel_line_error")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR))
    parser.add_argument("--max-frames", type=int, default=80, help="0 means all sampled frames")
    parser.add_argument("--every-ms", type=int, default=125, help="125 ms = 8 fps")
    parser.add_argument("--num-poses", type=int, default=4)
    parser.add_argument("--start-ms", type=int, default=0, help="Ignore frames before this timestamp.")
    parser.add_argument("--tap-x", type=float, default=None, help="Manual-lock normalized x coordinate.")
    parser.add_argument("--tap-y", type=float, default=None, help="Manual-lock normalized y coordinate.")
    parser.add_argument(
        "--min-selected-ratio",
        type=float,
        default=0.0,
        help="Optional active selected-subject coverage requirement; 0 disables the check.",
    )
    parser.add_argument(
        "--auto-tap-first-candidate",
        action="store_true",
        help="Simulate manual subject lock on the first usable candidate.",
    )
    args = parser.parse_args()

    summary = _run_validation(
        video_path=Path(args.video),
        out_dir=Path(args.out_dir),
        label=args.label,
        max_frames=args.max_frames,
        every_ms=args.every_ms,
        num_poses=args.num_poses,
        auto_tap_first_candidate=args.auto_tap_first_candidate,
        start_ms=args.start_ms,
        tap_x=args.tap_x,
        tap_y=args.tap_y,
        min_selected_ratio=args.min_selected_ratio,
    )

    n_pass = sum(1 for item in summary["assertions"] if item["passed"])
    n_fail = sum(1 for item in summary["assertions"] if not item["passed"])
    sm = summary["summary"]
    print(f"Output dir: {args.out_dir}")
    print(f"Overlay: {summary['outputs']['overlay_video']}")
    print(f"Trace:   {summary['outputs']['trace_json']}")
    print(f"Summary: {summary['outputs']['summary_json']}")
    print(
        "frames={frames_analyzed} candidates={frames_with_candidate} "
        "tracks={distinct_track_ids} switches={track_switches} "
        "identity_switches={identity_switches} wrong_person={wrong_person_frames} "
        "hold={hold_frames} lost={lost} avg_select_ms={avg_select_ms}".format(
            **sm,
            lost=sm["status_distribution"].get(SubjectLockStatus.SUBJECT_LOST.value, 0),
        )
    )
    for item in summary["assertions"]:
        tag = "OK" if item["passed"] else "FAIL"
        print(f"{tag:4s} {item['name']}: {item['detail']}")
    print(f"Total assertions: pass={n_pass} fail={n_fail}")
    if n_fail:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
