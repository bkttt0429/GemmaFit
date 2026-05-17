"""
Active-focus v2 prototype for hard multi-person overlap clips.

This script is the Python-first implementation of the mobile MOT-lite plan:

  MediaPipe pose candidates
  -> presence-gated full-person proposal boxes
  -> hand-crafted part ReID signature
  -> Kalman-like ROI prediction / keypoint continuity
  -> low-frequency detector-burst hook
  -> observed selected subject OR hold

There is intentionally no new dependency and no always-on YOLO here. The
"detector burst" path is represented by the current MediaPipe candidate boxes
so the decision logic can be validated before plugging in an Android LiteRT /
TFLite person detector.

Example:
  python prototype/target_active_focus_v2.py ^
      --video test_assets/videos/pixel_line_error_1774202137014.mp4 ^
      --label pixel_line_error_active_focus_v2 ^
      --start-ms 1900 --tap-x 0.47 --tap-y 0.53 --num-poses 4
"""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import cv2
import numpy as np

from multi_person_pose import MultiPersonPose
from person_proposals import build_person_proposals, person_bbox_for
from subject_selector import PoseBBox, PoseCandidate, SubjectAppearanceSignature
from validate_kalman_tracking_video import (
    _draw_candidate,
    _put_header,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VIDEO = PROJECT_ROOT / "test_assets" / "videos" / "pixel_line_error_1774202137014.mp4"
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "target_active_focus_v2"

MATCH_KEYPOINTS: Tuple[int, ...] = (11, 12, 23, 24, 25, 26, 27, 28)
PART_WEIGHTS: Dict[str, float] = {
    "head": 0.18,
    "torso": 0.23,
    "upper": 0.10,
    "shorts": 0.28,
    "lower": 0.08,
    "feet": 0.04,
    "full": 0.09,
}


@dataclass
class PartReIdSignature:
    parts: Dict[str, np.ndarray] = field(default_factory=dict)
    stats: Dict[str, np.ndarray] = field(default_factory=dict)

    @property
    def is_valid(self) -> bool:
        return any(_hist_is_valid(hist) for hist in self.parts.values())

    def similarity(self, other: Optional["PartReIdSignature"]) -> float:
        if other is None or not self.is_valid or not other.is_valid:
            return 0.5
        total_weight = 0.0
        weighted = 0.0
        for name, base_weight in PART_WEIGHTS.items():
            a = self.parts.get(name)
            b = other.parts.get(name)
            sa = self.stats.get(name)
            sb = other.stats.get(name)
            if (not _hist_is_valid(a) or not _hist_is_valid(b)) and (not _hist_is_valid(sa) or not _hist_is_valid(sb)):
                continue
            hist_sim = _hist_intersection(a, b) if _hist_is_valid(a) and _hist_is_valid(b) else 0.5
            stat_sim = _stat_similarity(sa, sb) if _hist_is_valid(sa) and _hist_is_valid(sb) else 0.5
            sim = 0.35 * hist_sim + 0.65 * stat_sim
            weighted += base_weight * sim
            total_weight += base_weight
        if total_weight <= 0.0:
            return 0.5
        return _clamp01(weighted / total_weight)


@dataclass
class ActiveFocusConfig:
    identity_accept: float = 0.43
    reid_accept: float = 0.48
    strong_reid: float = 0.56
    core_accept: float = 0.52
    shorts_accept: float = 0.60
    green_contamination_max: float = 0.20
    margin_accept: float = 0.06
    strong_margin: float = 0.10
    hold_burst_frames: int = 2
    burst_interval_ms: int = 500
    max_hold_frames_before_lost: int = 12
    keypoint_radius: float = 0.35
    motion_radius: float = 0.30
    reid_weight: float = 0.35
    keypoint_weight: float = 0.25
    detector_iou_weight: float = 0.20
    color_weight: float = 0.10
    motion_weight: float = 0.10


@dataclass
class TrackState:
    initialized: bool = False
    last_candidate: Optional[PoseCandidate] = None
    last_bbox: Optional[PoseBBox] = None
    velocity_x: float = 0.0
    velocity_y: float = 0.0
    anchor_reid: Optional[PartReIdSignature] = None
    anchor_core: Optional[PartReIdSignature] = None
    anchor_color: Optional[SubjectAppearanceSignature] = None
    hold_frames: int = 0
    last_selected_ms: Optional[int] = None
    last_burst_ms: int = -10_000
    track_id: int = 1


def _clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else value)


def _hist_is_valid(hist: Optional[np.ndarray]) -> bool:
    return hist is not None and hist.size > 0 and float(np.sum(hist)) > 0.0


def _hist_intersection(a: np.ndarray, b: np.ndarray) -> float:
    if a.shape != b.shape:
        return 0.5
    aa = _normalize_hist(a)
    bb = _normalize_hist(b)
    if not _hist_is_valid(aa) or not _hist_is_valid(bb):
        return 0.5
    return _clamp01(float(np.minimum(aa, bb).sum()))


def _stat_similarity(a: np.ndarray, b: np.ndarray) -> float:
    if a.shape != b.shape:
        return 0.5
    aa = np.nan_to_num(a.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)
    bb = np.nan_to_num(b.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)
    if aa.size != 6:
        return 0.5
    weights = np.array([0.30, 0.20, 0.12, 0.23, 0.08, 0.07], dtype=np.float32)
    distance = float(np.sum(np.abs(aa - bb) * weights))
    return _clamp01(1.0 - distance)


def _normalize_hist(hist: np.ndarray) -> np.ndarray:
    clean = np.nan_to_num(hist.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)
    clean = np.maximum(clean, 0.0)
    total = float(clean.sum())
    if total <= 0.0:
        return np.zeros_like(clean, dtype=np.float32)
    return clean / total


def _bbox_iou(a: PoseBBox, b: PoseBBox) -> float:
    left = max(a.left, b.left)
    top = max(a.top, b.top)
    right = min(a.right, b.right)
    bottom = min(a.bottom, b.bottom)
    inter = max(0.0, right - left) * max(0.0, bottom - top)
    union = a.area + b.area - inter
    if union <= 0.0:
        return 0.0
    return _clamp01(inter / union)


def _bbox_center(bbox: PoseBBox) -> Tuple[float, float]:
    return 0.5 * (bbox.left + bbox.right), 0.5 * (bbox.top + bbox.bottom)


def _center_distance(a: PoseBBox, b: PoseBBox) -> float:
    ax, ay = _bbox_center(a)
    bx, by = _bbox_center(b)
    return math.hypot(ax - bx, ay - by)


def _expand_bbox(bbox: PoseBBox, scale_x: float, scale_y: float) -> PoseBBox:
    cx, cy = _bbox_center(bbox)
    half_w = bbox.width * scale_x * 0.5
    half_h = bbox.height * scale_y * 0.5
    return PoseBBox(
        left=max(0.0, cx - half_w),
        top=max(0.0, cy - half_h),
        right=min(1.0, cx + half_w),
        bottom=min(1.0, cy + half_h),
    )


def _predict_bbox(state: TrackState, timestamp_ms: int) -> Optional[PoseBBox]:
    if state.last_bbox is None:
        return None
    if state.last_selected_ms is None:
        return state.last_bbox
    dt_s = max(0.0, min(1.0, (timestamp_ms - state.last_selected_ms) / 1000.0))
    dx = state.velocity_x * dt_s
    dy = state.velocity_y * dt_s
    return PoseBBox(
        left=_clamp01(state.last_bbox.left + dx),
        top=_clamp01(state.last_bbox.top + dy),
        right=_clamp01(state.last_bbox.right + dx),
        bottom=_clamp01(state.last_bbox.bottom + dy),
    )


def _rect_from_norm(
    bbox: PoseBBox,
    width: int,
    height: int,
) -> Tuple[int, int, int, int]:
    left = int(np.floor(_clamp01(bbox.left) * width))
    top = int(np.floor(_clamp01(bbox.top) * height))
    right = int(np.ceil(_clamp01(bbox.right) * width))
    bottom = int(np.ceil(_clamp01(bbox.bottom) * height))
    left = max(0, min(width - 1, left))
    top = max(0, min(height - 1, top))
    right = max(left + 1, min(width, right))
    bottom = max(top + 1, min(height, bottom))
    return left, top, right, bottom


def _rect_for_keypoints(
    candidate: PoseCandidate,
    indices: Iterable[int],
    pad_x_scale: float,
    pad_y_scale: float,
) -> Optional[PoseBBox]:
    arr = candidate.landmarks
    visible = [
        index
        for index in indices
        if index < arr.shape[0] and arr[index, 2] >= 0.25
    ]
    if not visible:
        return None
    xs = arr[visible, 0]
    ys = arr[visible, 1]
    pad_x = max(0.015, candidate.bbox.width * pad_x_scale)
    pad_y = max(0.015, candidate.bbox.height * pad_y_scale)
    return PoseBBox(
        left=max(0.0, float(xs.min()) - pad_x),
        top=max(0.0, float(ys.min()) - pad_y),
        right=min(1.0, float(xs.max()) + pad_x),
        bottom=min(1.0, float(ys.max()) + pad_y),
    )


def _shorts_bbox(candidate: PoseCandidate) -> Optional[PoseBBox]:
    arr = candidate.landmarks
    hips = [idx for idx in (23, 24) if idx < arr.shape[0] and arr[idx, 2] >= 0.25]
    knees = [idx for idx in (25, 26) if idx < arr.shape[0] and arr[idx, 2] >= 0.25]
    if not hips:
        return None
    x_indices = hips + knees
    xs = arr[x_indices, 0]
    hip_y = float(np.mean(arr[hips, 1]))
    if knees:
        knee_y = float(np.mean(arr[knees, 1]))
        bottom_y = hip_y + 0.58 * max(0.02, knee_y - hip_y)
    else:
        bottom_y = candidate.bbox.top + 0.65 * candidate.bbox.height
    pad_x = max(0.02, candidate.bbox.width * 0.16)
    pad_y = max(0.015, candidate.bbox.height * 0.06)
    return PoseBBox(
        left=max(0.0, float(xs.min()) - pad_x),
        top=max(0.0, hip_y - pad_y),
        right=min(1.0, float(xs.max()) + pad_x),
        bottom=min(1.0, bottom_y + pad_y),
    )


def _head_bbox(candidate: PoseCandidate) -> Optional[PoseBBox]:
    arr = candidate.landmarks
    head_indices = tuple(range(0, 11))
    visible = [
        idx for idx in head_indices
        if idx < arr.shape[0] and arr[idx, 2] >= 0.20
    ]
    if visible:
        xs = arr[visible, 0]
        ys = arr[visible, 1]
        pad_x = max(0.02, candidate.bbox.width * 0.10)
        pad_y = max(0.02, candidate.bbox.height * 0.08)
        return PoseBBox(
            left=max(0.0, float(xs.min()) - pad_x),
            top=max(0.0, float(ys.min()) - pad_y),
            right=min(1.0, float(xs.max()) + pad_x),
            bottom=min(1.0, float(ys.max()) + pad_y),
        )
    if candidate.bbox.height <= 0.0:
        return None
    return PoseBBox(
        left=candidate.bbox.left,
        top=candidate.bbox.top,
        right=candidate.bbox.right,
        bottom=min(1.0, candidate.bbox.top + 0.22 * candidate.bbox.height),
    )


def _hist_for_bbox(frame: np.ndarray, bbox: Optional[PoseBBox]) -> np.ndarray:
    if bbox is None:
        return np.zeros(16 * 8 * 4, dtype=np.float32)
    h, w = frame.shape[:2]
    left, top, right, bottom = _rect_from_norm(bbox, w, h)
    crop = frame[top:bottom, left:right]
    if crop.size == 0 or crop.shape[0] < 3 or crop.shape[1] < 3:
        return np.zeros(16 * 8 * 4, dtype=np.float32)
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1, 2], None, [16, 8, 4], [0, 180, 0, 256, 0, 256])
    return _normalize_hist(hist.reshape(-1))


def _stats_for_bbox(frame: np.ndarray, bbox: Optional[PoseBBox]) -> np.ndarray:
    if bbox is None:
        return np.zeros(6, dtype=np.float32)
    h, w = frame.shape[:2]
    left, top, right, bottom = _rect_from_norm(bbox, w, h)
    crop = frame[top:bottom, left:right]
    if crop.size == 0 or crop.shape[0] < 3 or crop.shape[1] < 3:
        return np.zeros(6, dtype=np.float32)
    hsv = cv2.cvtColor(crop, cv2.COLOR_BGR2HSV)
    hue = hsv[:, :, 0].astype(np.float32)
    sat = hsv[:, :, 1].astype(np.float32)
    val = hsv[:, :, 2].astype(np.float32)
    valid = np.isfinite(hue) & np.isfinite(sat) & np.isfinite(val)
    if not valid.any():
        return np.zeros(6, dtype=np.float32)
    hue = hue[valid]
    sat = sat[valid]
    val = val[valid]
    saturated = sat >= 70.0
    green = (hue >= 35.0) & (hue <= 92.0) & saturated
    return np.array([
        float(np.mean(val < 95.0)),
        float(np.mean(val > 170.0)),
        float(np.mean(sat > 70.0)),
        float(np.mean(green)),
        float(np.mean(sat) / 255.0),
        float(np.mean(val) / 255.0),
    ], dtype=np.float32)


def _appearance_signature_from_person_bbox(
    frame: np.ndarray,
    candidate: PoseCandidate,
) -> SubjectAppearanceSignature:
    return SubjectAppearanceSignature(_hist_for_bbox(frame, person_bbox_for(candidate)))


def _part_reid_signature(frame: np.ndarray, candidate: PoseCandidate) -> PartReIdSignature:
    parts: Dict[str, np.ndarray] = {}
    stats: Dict[str, np.ndarray] = {}
    head = _head_bbox(candidate)
    torso = _rect_for_keypoints(candidate, (11, 12, 23, 24), 0.18, 0.10)
    upper = _rect_for_keypoints(candidate, (11, 12, 13, 14, 15, 16), 0.16, 0.12)
    shorts = _shorts_bbox(candidate)
    lower = _rect_for_keypoints(candidate, (23, 24, 25, 26, 27, 28), 0.16, 0.10)
    feet = _rect_for_keypoints(candidate, (27, 28, 29, 30, 31, 32), 0.20, 0.18)
    boxes = {
        "head": head,
        "torso": torso,
        "upper": upper,
        "shorts": shorts,
        "lower": lower,
        "feet": feet,
        "full": person_bbox_for(candidate),
    }
    for name, bbox in boxes.items():
        parts[name] = _hist_for_bbox(frame, bbox)
        stats[name] = _stats_for_bbox(frame, bbox)
    return PartReIdSignature(parts=parts, stats=stats)


def _tap_core_signature(frame: np.ndarray, x: float, y: float) -> PartReIdSignature:
    bbox = PoseBBox(
        left=max(0.0, x - 0.045),
        top=max(0.0, y - 0.060),
        right=min(1.0, x + 0.045),
        bottom=min(1.0, y + 0.060),
    )
    return PartReIdSignature(
        parts={"core": _hist_for_bbox(frame, bbox)},
        stats={"core": _stats_for_bbox(frame, bbox)},
    )


def _single_part_similarity(
    anchor: PartReIdSignature,
    anchor_part: str,
    candidate: PartReIdSignature,
    candidate_part: str,
) -> float:
    ah = anchor.parts.get(anchor_part)
    ch = candidate.parts.get(candidate_part)
    ast = anchor.stats.get(anchor_part)
    cst = candidate.stats.get(candidate_part)
    hist_sim = _hist_intersection(ah, ch) if _hist_is_valid(ah) and _hist_is_valid(ch) else 0.5
    stat_sim = _stat_similarity(ast, cst) if _hist_is_valid(ast) and _hist_is_valid(cst) else 0.5
    return _clamp01(0.35 * hist_sim + 0.65 * stat_sim)


def _core_similarity(anchor_core: Optional[PartReIdSignature], candidate: PartReIdSignature) -> float:
    if anchor_core is None or not anchor_core.is_valid:
        return 0.5
    return _clamp01(float(np.mean([
        _single_part_similarity(anchor_core, "core", candidate, "torso"),
        _single_part_similarity(anchor_core, "core", candidate, "upper"),
    ])))


def _shorts_similarity(anchor: Optional[PartReIdSignature], candidate: PartReIdSignature) -> float:
    if anchor is None or not anchor.is_valid:
        return 0.5
    return _single_part_similarity(anchor, "shorts", candidate, "shorts")


def _green_contamination(anchor_core: Optional[PartReIdSignature], candidate: PartReIdSignature) -> float:
    if anchor_core is None or not anchor_core.is_valid:
        return 0.0
    anchor_stats = anchor_core.stats.get("core")
    if not _hist_is_valid(anchor_stats):
        return 0.0
    anchor_green = float(anchor_stats[3]) if anchor_stats.size >= 4 else 0.0
    if anchor_green >= 0.12:
        return 0.0
    values: List[float] = []
    for part in ("torso", "upper"):
        stats = candidate.stats.get(part)
        if _hist_is_valid(stats) and stats.size >= 4:
            values.append(float(stats[3]))
    if not values:
        return 0.0
    return max(0.0, max(values) - anchor_green)


def _select_index_from_tap(candidates: List[PoseCandidate], x: float, y: float) -> int:
    containing = [
        (i, person_bbox_for(c).area)
        for i, c in enumerate(candidates)
        if person_bbox_for(c).contains(x, y)
    ]
    if containing:
        return max(containing, key=lambda item: item[1])[0]
    best_i = -1
    best_d = float("inf")
    for i, c in enumerate(candidates):
        cx, cy = _bbox_center(person_bbox_for(c))
        d = math.hypot(cx - x, cy - y)
        if d < best_d:
            best_d = d
            best_i = i
    return best_i


def _mean_keypoint_distance(previous: PoseCandidate, candidate: PoseCandidate) -> float:
    a = previous.landmarks
    b = candidate.landmarks
    distances: List[float] = []
    for idx in MATCH_KEYPOINTS:
        if idx >= a.shape[0] or idx >= b.shape[0]:
            continue
        if a[idx, 2] <= 0.15 or b[idx, 2] <= 0.15:
            continue
        distances.append(float(math.hypot(a[idx, 0] - b[idx, 0], a[idx, 1] - b[idx, 1])))
    if not distances:
        return 1.0
    return float(np.mean(distances))


def _score_candidates(
    candidates: List[PoseCandidate],
    signatures: List[PartReIdSignature],
    state: TrackState,
    cfg: ActiveFocusConfig,
    timestamp_ms: int,
) -> List[dict]:
    predicted_bbox = _predict_bbox(state, timestamp_ms)
    scored: List[dict] = []
    for i, candidate in enumerate(candidates):
        reid = state.anchor_reid.similarity(signatures[i]) if state.anchor_reid is not None else 0.5
        core = _core_similarity(state.anchor_core, signatures[i])
        shorts = _shorts_similarity(state.anchor_reid, signatures[i])
        green = _green_contamination(state.anchor_core, signatures[i])
        color = (
            state.anchor_color.similarity(candidate.appearance)
            if state.anchor_color is not None
            else 0.5
        )
        if state.last_candidate is not None and state.hold_frames <= cfg.max_hold_frames_before_lost:
            keypoint = _clamp01(1.0 - _mean_keypoint_distance(state.last_candidate, candidate) / cfg.keypoint_radius)
        else:
            keypoint = 0.5
        if predicted_bbox is not None:
            person_bbox = person_bbox_for(candidate)
            detector_iou = _bbox_iou(predicted_bbox, person_bbox)
            motion = _clamp01(1.0 - _center_distance(predicted_bbox, person_bbox) / cfg.motion_radius)
        else:
            detector_iou = 0.0
            motion = 0.5
        identity = _clamp01(
            cfg.reid_weight * reid +
            cfg.keypoint_weight * keypoint +
            cfg.detector_iou_weight * detector_iou +
            cfg.color_weight * color +
            cfg.motion_weight * motion
        )
        scored.append({
            "index": i,
            "identity": identity,
            "reid": reid,
            "keypoint": keypoint,
            "core": core,
            "shorts": shorts,
            "green_contamination": green,
            "detector_iou": detector_iou,
            "color": color,
            "motion": motion,
        })
    scored.sort(key=lambda row: row["identity"], reverse=True)
    return scored


def _should_accept(best: dict, margin: float, cfg: ActiveFocusConfig, candidate_count: int) -> bool:
    if best["reid"] < cfg.reid_accept:
        return False
    if best["core"] < cfg.core_accept:
        return False
    if best["shorts"] < cfg.shorts_accept:
        return False
    if best["green_contamination"] > cfg.green_contamination_max:
        return False
    clear_reid_reacquire = (
        best["reid"] >= cfg.strong_reid and
        margin >= cfg.strong_margin and
        (best["keypoint"] >= 0.40 or best["motion"] >= 0.25)
    )
    if clear_reid_reacquire:
        return True
    if best["identity"] < cfg.identity_accept:
        return False
    if candidate_count <= 1:
        return best["reid"] >= cfg.strong_reid or best["identity"] >= cfg.identity_accept + 0.08
    if margin >= cfg.margin_accept:
        return True
    return best["reid"] >= cfg.strong_reid and margin >= cfg.strong_margin


def _update_track_state(
    state: TrackState,
    candidate: PoseCandidate,
    signature: PartReIdSignature,
    timestamp_ms: int,
    anchor_core: Optional[PartReIdSignature] = None,
) -> None:
    candidate_bbox = person_bbox_for(candidate)
    if state.last_bbox is not None and state.last_selected_ms is not None:
        dt_s = max(0.001, (timestamp_ms - state.last_selected_ms) / 1000.0)
        prev_cx, prev_cy = _bbox_center(state.last_bbox)
        cur_cx, cur_cy = _bbox_center(candidate_bbox)
        state.velocity_x = 0.70 * state.velocity_x + 0.30 * ((cur_cx - prev_cx) / dt_s)
        state.velocity_y = 0.70 * state.velocity_y + 0.30 * ((cur_cy - prev_cy) / dt_s)
    state.last_candidate = candidate
    state.last_bbox = candidate_bbox
    state.last_selected_ms = timestamp_ms
    state.hold_frames = 0
    if state.anchor_reid is None:
        state.anchor_reid = signature
    if state.anchor_core is None and anchor_core is not None and anchor_core.is_valid:
        state.anchor_core = anchor_core
    if state.anchor_color is None:
        state.anchor_color = candidate.appearance
    state.initialized = True


def _draw_dashed_line(
    frame: np.ndarray,
    start: Tuple[int, int],
    end: Tuple[int, int],
    color: Tuple[int, int, int],
    thickness: int,
    dash_px: int = 10,
    gap_px: int = 6,
) -> None:
    sx, sy = start
    ex, ey = end
    length = math.hypot(ex - sx, ey - sy)
    if length <= 0.0:
        return
    step = dash_px + gap_px
    for offset in range(0, int(length), step):
        dash_end = min(offset + dash_px, length)
        t0 = offset / length
        t1 = dash_end / length
        x0 = int(round(sx + (ex - sx) * t0))
        y0 = int(round(sy + (ey - sy) * t0))
        x1 = int(round(sx + (ex - sx) * t1))
        y1 = int(round(sy + (ey - sy) * t1))
        cv2.line(frame, (x0, y0), (x1, y1), color, thickness, cv2.LINE_AA)


def _draw_dashed_rect(
    frame: np.ndarray,
    left: int,
    top: int,
    right: int,
    bottom: int,
    color: Tuple[int, int, int],
    thickness: int,
) -> None:
    _draw_dashed_line(frame, (left, top), (right, top), color, thickness)
    _draw_dashed_line(frame, (right, top), (right, bottom), color, thickness)
    _draw_dashed_line(frame, (right, bottom), (left, bottom), color, thickness)
    _draw_dashed_line(frame, (left, bottom), (left, top), color, thickness)


def _draw_predicted_roi(
    frame: np.ndarray,
    bbox: Optional[PoseBBox],
    color: Tuple[int, int, int],
    label: str,
    dashed: bool = False,
    thickness: int = 2,
) -> None:
    if bbox is None:
        return
    h, w = frame.shape[:2]
    left, top, right, bottom = _rect_from_norm(bbox, w, h)
    if dashed:
        _draw_dashed_rect(frame, left, top, right, bottom, color, thickness)
    else:
        cv2.rectangle(frame, (left, top), (right, bottom), color, thickness, cv2.LINE_AA)
    cv2.putText(
        frame,
        label,
        (left, max(18, top - 8)),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.52,
        color,
        1,
        cv2.LINE_AA,
    )


def _draw_person_bbox(
    frame: np.ndarray,
    bbox: PoseBBox,
    color: Tuple[int, int, int],
    label: str,
    thickness: int,
) -> None:
    h, w = frame.shape[:2]
    left, top, right, bottom = _rect_from_norm(bbox, w, h)
    cv2.rectangle(frame, (left, top), (right, bottom), color, thickness, cv2.LINE_AA)
    cv2.putText(
        frame,
        label,
        (left, min(h - 6, max(18, bottom + 16))),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.48,
        color,
        1,
        cv2.LINE_AA,
    )


def _make_contact_sheet(
    overlay_path: Path,
    output_path: Path,
    cols: int = 4,
    rows: int = 3,
) -> None:
    cap = cv2.VideoCapture(str(overlay_path))
    if not cap.isOpened():
        return
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
    if total <= 0:
        cap.release()
        return
    indices = np.linspace(0, total - 1, cols * rows).astype(int)
    frames: List[np.ndarray] = []
    for idx in indices:
        cap.set(cv2.CAP_PROP_POS_FRAMES, int(idx))
        ok, frame = cap.read()
        if not ok:
            continue
        frame = cv2.resize(frame, (360, 240), interpolation=cv2.INTER_AREA)
        frames.append(frame)
    cap.release()
    if not frames:
        return
    while len(frames) < cols * rows:
        frames.append(np.zeros_like(frames[0]))
    row_imgs = [np.hstack(frames[r * cols:(r + 1) * cols]) for r in range(rows)]
    sheet = np.vstack(row_imgs)
    cv2.imwrite(str(output_path), sheet)


def _run(
    video_path: Path,
    out_dir: Path,
    label: str,
    start_ms: int,
    tap_x: float,
    tap_y: float,
    every_ms: int,
    max_frames: int,
    num_poses: int,
    min_selected_ratio: float,
) -> dict:
    if not video_path.exists():
        raise FileNotFoundError(video_path)
    out_dir.mkdir(parents=True, exist_ok=True)

    overlay_path = out_dir / f"{label}_active_focus_v2_overlay.mp4"
    trace_path = out_dir / f"{label}_active_focus_v2_trace.json"
    summary_path = out_dir / f"{label}_active_focus_v2_summary.json"
    contact_sheet_path = out_dir / f"{label}_active_focus_v2_contact_sheet.jpg"

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

    cfg = ActiveFocusConfig()
    state = TrackState()
    trace: List[dict] = []
    frame_idx = 0
    last_emit_ms = -1
    detect_seconds = 0.0
    reid_seconds = 0.0
    started = time.time()

    with MultiPersonPose(num_poses=num_poses) as pose:
        try:
            while True:
                ok, frame = cap.read()
                if not ok:
                    break
                ts_ms = int(round(frame_idx / fps * 1000))
                frame_idx += 1
                if ts_ms < start_ms or (ts_ms - last_emit_ms) < every_ms:
                    continue
                if max_frames > 0 and len(trace) >= max_frames:
                    break

                detect_start = time.perf_counter()
                raw_candidates = pose.detect_for_video(frame, ts_ms)
                detect_seconds += time.perf_counter() - detect_start

                proposals, gated_out = build_person_proposals(raw_candidates)
                candidates = [proposal.candidate for proposal in proposals]
                for candidate in candidates:
                    candidate.appearance = _appearance_signature_from_person_bbox(frame, candidate)

                reid_start = time.perf_counter()
                signatures = [_part_reid_signature(frame, c) for c in candidates]
                reid_seconds += time.perf_counter() - reid_start

                selected_index: Optional[int] = None
                reason = "no_person_detected" if not candidates else "not_initialized"
                detector_burst = False
                scored: List[dict] = []
                margin: Optional[float] = None
                best_row: Optional[dict] = None

                if not state.initialized and candidates:
                    tap_i = _select_index_from_tap(candidates, tap_x, tap_y)
                    if tap_i >= 0:
                        selected_index = tap_i
                        reason = "manual_target_initialized"
                        core = _tap_core_signature(frame, tap_x, tap_y)
                        _update_track_state(state, candidates[tap_i], signatures[tap_i], ts_ms, anchor_core=core)
                elif state.initialized:
                    if state.hold_frames >= cfg.hold_burst_frames and ts_ms - state.last_burst_ms >= cfg.burst_interval_ms:
                        detector_burst = True
                        state.last_burst_ms = ts_ms
                    scored = _score_candidates(candidates, signatures, state, cfg, ts_ms)
                    pose_only_reacquire_blocked = state.hold_frames >= cfg.max_hold_frames_before_lost
                    if pose_only_reacquire_blocked:
                        state.hold_frames += 1
                        reason = "subject_lost"
                    elif scored:
                        best_row = scored[0]
                        second = scored[1] if len(scored) > 1 else None
                        margin = best_row["identity"] - second["identity"] if second is not None else 1.0
                        if _should_accept(best_row, margin, cfg, len(scored)):
                            selected_index = int(best_row["index"])
                            reason = "observed_subject_reid"
                            _update_track_state(state, candidates[selected_index], signatures[selected_index], ts_ms)
                        else:
                            state.hold_frames += 1
                            if state.hold_frames >= cfg.max_hold_frames_before_lost:
                                reason = "subject_lost"
                            elif detector_burst:
                                reason = "detector_burst_hold"
                            elif best_row["reid"] < cfg.reid_accept:
                                reason = "subject_reid_mismatch"
                            elif best_row["core"] < cfg.core_accept:
                                reason = "subject_tap_core_mismatch"
                            elif best_row["shorts"] < cfg.shorts_accept:
                                reason = "subject_shorts_mismatch"
                            elif best_row["green_contamination"] > cfg.green_contamination_max:
                                reason = "subject_color_contaminated"
                            elif margin is not None and margin < cfg.margin_accept:
                                reason = "subject_identity_uncertain"
                            else:
                                reason = "subject_temporarily_occluded"
                    else:
                        state.hold_frames += 1
                        reason = "no_pose_candidates_hold" if state.hold_frames < cfg.max_hold_frames_before_lost else "subject_lost"

                predicted_bbox = _predict_bbox(state, ts_ms)
                hold_frame = state.initialized and selected_index is None
                for i, candidate in enumerate(candidates):
                    is_selected = i == selected_index
                    color = (64, 220, 120) if is_selected else (128, 156, 180)
                    label_text = f"cand={i} vis={candidate.avg_visibility:.2f}"
                    score_item = next((row for row in scored if int(row["index"]) == i), None)
                    if score_item is not None:
                        label_text += f" id={score_item['identity']:.2f} reid={score_item['reid']:.2f}"
                        label_text += f" core={score_item['core']:.2f} shorts={score_item['shorts']:.2f}"
                    _draw_person_bbox(
                        frame,
                        person_bbox_for(candidate),
                        color,
                        f"person {i}",
                        2 if is_selected else 1,
                    )
                    _draw_candidate(frame, candidate, label_text, color, is_selected, draw_skeleton=is_selected)

                if hold_frame:
                    if reason == "subject_lost":
                        _draw_predicted_roi(
                            frame,
                            predicted_bbox,
                            (40, 70, 255),
                            "target lost",
                            dashed=True,
                            thickness=2,
                        )
                    else:
                        _draw_predicted_roi(
                            frame,
                            predicted_bbox,
                            (0, 220, 255),
                            f"ROI hold {state.hold_frames}",
                            dashed=True,
                            thickness=2,
                        )

                row = {
                    "sample_index": len(trace),
                    "frame": frame_idx - 1,
                    "timestamp_ms": ts_ms,
                    "raw_candidates": len(raw_candidates),
                    "candidates_after_gate": len(candidates),
                    "proposals_after_nms": len(proposals),
                    "proposals_gated_out": gated_out,
                    "initialized": state.initialized,
                    "selected_index": selected_index,
                    "track_id": state.track_id if state.initialized else -1,
                    "hold_frame": hold_frame,
                    "hold_frames": state.hold_frames,
                    "detector_burst": detector_burst,
                    "fallback_source": "mediapipe_pose_burst" if detector_burst else "none",
                    "reason": reason,
                    "best_identity_score": round(float(best_row["identity"]), 5) if best_row is not None else None,
                    "best_reid_similarity": round(float(best_row["reid"]), 5) if best_row is not None else None,
                    "best_keypoint_score": round(float(best_row["keypoint"]), 5) if best_row is not None else None,
                    "best_core_similarity": round(float(best_row["core"]), 5) if best_row is not None else None,
                    "best_shorts_similarity": round(float(best_row["shorts"]), 5) if best_row is not None else None,
                    "best_green_contamination": round(float(best_row["green_contamination"]), 5) if best_row is not None else None,
                    "best_detector_iou": round(float(best_row["detector_iou"]), 5) if best_row is not None else None,
                    "best_color_score": round(float(best_row["color"]), 5) if best_row is not None else None,
                    "best_motion_score": round(float(best_row["motion"]), 5) if best_row is not None else None,
                    "top2_identity_margin": round(float(margin), 5) if margin is not None else None,
                    "selected_center": (
                        [
                            round(float(_bbox_center(person_bbox_for(candidates[selected_index]))[0]), 5),
                            round(float(_bbox_center(person_bbox_for(candidates[selected_index]))[1]), 5),
                        ]
                        if selected_index is not None
                        else None
                    ),
                    "predicted_bbox": (
                        [
                            round(float(predicted_bbox.left), 5),
                            round(float(predicted_bbox.top), 5),
                            round(float(predicted_bbox.right), 5),
                            round(float(predicted_bbox.bottom), 5),
                        ]
                        if predicted_bbox is not None
                        else None
                    ),
                    "candidate_centers": [
                        [
                            round(float(_bbox_center(person_bbox_for(c))[0]), 5),
                            round(float(_bbox_center(person_bbox_for(c))[1]), 5),
                        ]
                        for c in candidates
                    ],
                    "candidate_person_bboxes": [
                        [
                            round(float(person_bbox_for(c).left), 5),
                            round(float(person_bbox_for(c).top), 5),
                            round(float(person_bbox_for(c).right), 5),
                            round(float(person_bbox_for(c).bottom), 5),
                        ]
                        for c in candidates
                    ],
                    "candidate_scores": [
                        {
                            "index": int(item["index"]),
                            "identity": round(float(item["identity"]), 5),
                            "reid": round(float(item["reid"]), 5),
                            "keypoint": round(float(item["keypoint"]), 5),
                            "core": round(float(item["core"]), 5),
                            "shorts": round(float(item["shorts"]), 5),
                            "green_contamination": round(float(item["green_contamination"]), 5),
                            "detector_iou": round(float(item["detector_iou"]), 5),
                            "color": round(float(item["color"]), 5),
                            "motion": round(float(item["motion"]), 5),
                        }
                        for item in scored
                    ],
                }
                trace.append(row)

                header = [
                    f"GemmaFit active focus v2 | {label}",
                    f"frame={row['frame']} t={ts_ms}ms selected={selected_index} hold={hold_frame} reason={reason}",
                    f"raw={row['raw_candidates']} proposals={row['proposals_after_nms']} gated_out={gated_out} burst={detector_burst}",
                    f"id={row['best_identity_score']} reid={row['best_reid_similarity']} margin={row['top2_identity_margin']}",
                    f"tap=({tap_x:.3f}, {tap_y:.3f})",
                ]
                _put_header(frame, header)
                writer.write(frame)
                last_emit_ms = ts_ms
        finally:
            cap.release()
            writer.release()

    selected_frames = sum(1 for row in trace if row["selected_index"] is not None)
    initialized_frames = sum(1 for row in trace if row["initialized"])
    hold_frames = sum(1 for row in trace if row["hold_frame"])
    burst_frames = sum(1 for row in trace if row["detector_burst"])
    subject_lost_frames = sum(1 for row in trace if row["reason"] == "subject_lost")
    low_reid_selected = sum(
        1
        for row in trace
        if row["selected_index"] is not None and row["best_reid_similarity"] is not None and row["best_reid_similarity"] < cfg.reid_accept
    )
    selected_ratio = selected_frames / max(1, initialized_frames)

    _make_contact_sheet(overlay_path, contact_sheet_path)

    summary = {
        "label": label,
        "video": str(video_path),
        "outputs": {
            "overlay_video": str(overlay_path),
            "trace_json": str(trace_path),
            "summary_json": str(summary_path),
            "contact_sheet": str(contact_sheet_path),
        },
        "config": {
            "start_ms": start_ms,
            "tap": [tap_x, tap_y],
            "every_ms": every_ms,
            "num_poses": num_poses,
            "min_selected_ratio": min_selected_ratio,
            "proposal_bbox": "expanded_person_bbox",
            "identity_accept": cfg.identity_accept,
            "reid_accept": cfg.reid_accept,
            "core_accept": cfg.core_accept,
            "shorts_accept": cfg.shorts_accept,
            "green_contamination_max": cfg.green_contamination_max,
            "margin_accept": cfg.margin_accept,
            "score_weights": {
                "reid": cfg.reid_weight,
                "keypoint": cfg.keypoint_weight,
                "detector_iou": cfg.detector_iou_weight,
                "color": cfg.color_weight,
                "motion": cfg.motion_weight,
            },
        },
        "summary": {
            "frames_analyzed": len(trace),
            "initialized_frames": initialized_frames,
            "selected_frames": selected_frames,
            "selected_ratio": round(selected_ratio, 5),
            "hold_frames": hold_frames,
            "detector_burst_frames": burst_frames,
            "subject_lost_frames": subject_lost_frames,
            "low_reid_selected_frames": low_reid_selected,
            "elapsed_s": round(time.time() - started, 3),
            "detect_seconds": round(detect_seconds, 4),
            "reid_seconds": round(reid_seconds, 4),
            "avg_reid_ms_per_sample": round((reid_seconds / max(1, len(trace))) * 1000.0, 4),
        },
        "assertions": [
            {
                "name": "manual target initialized",
                "passed": initialized_frames > 0,
                "detail": f"initialized_frames={initialized_frames}",
            },
            {
                "name": "no selected frame below ReID threshold",
                "passed": low_reid_selected == 0,
                "detail": f"low_reid_selected_frames={low_reid_selected}",
            },
            {
                "name": "selected target coverage meets requested ratio",
                "passed": selected_ratio >= min_selected_ratio,
                "detail": f"selected_frames={selected_frames}/{initialized_frames} ratio={selected_ratio:.3f} required={min_selected_ratio:.3f}",
            },
            {
                "name": "fallback burst was used during ambiguity",
                "passed": burst_frames > 0,
                "detail": f"detector_burst_frames={burst_frames}",
            },
        ],
    }

    trace_path.write_text(json.dumps(trace, indent=2, ensure_ascii=False), encoding="utf-8")
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", default=str(DEFAULT_VIDEO))
    parser.add_argument("--label", default="pixel_line_error_active_focus_v2")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR))
    parser.add_argument("--start-ms", type=int, default=1900)
    parser.add_argument("--tap-x", type=float, default=0.47)
    parser.add_argument("--tap-y", type=float, default=0.53)
    parser.add_argument("--every-ms", type=int, default=125)
    parser.add_argument("--max-frames", type=int, default=0)
    parser.add_argument("--num-poses", type=int, default=4)
    parser.add_argument("--min-selected-ratio", type=float, default=0.75)
    args = parser.parse_args()

    summary = _run(
        video_path=Path(args.video),
        out_dir=Path(args.out_dir),
        label=args.label,
        start_ms=args.start_ms,
        tap_x=args.tap_x,
        tap_y=args.tap_y,
        every_ms=args.every_ms,
        max_frames=args.max_frames,
        num_poses=args.num_poses,
        min_selected_ratio=args.min_selected_ratio,
    )
    sm = summary["summary"]
    print(f"Overlay: {summary['outputs']['overlay_video']}")
    print(f"Trace:   {summary['outputs']['trace_json']}")
    print(f"Summary: {summary['outputs']['summary_json']}")
    print(f"Sheet:   {summary['outputs']['contact_sheet']}")
    print(
        "frames={frames_analyzed} selected={selected_frames} "
        "ratio={selected_ratio} hold={hold_frames} burst={detector_burst_frames} "
        "lost={subject_lost_frames} avg_reid_ms={avg_reid_ms_per_sample}".format(**sm)
    )
    failures = 0
    for item in summary["assertions"]:
        tag = "OK" if item["passed"] else "FAIL"
        print(f"{tag:4s} {item['name']}: {item['detail']}")
        if not item["passed"]:
            failures += 1
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
