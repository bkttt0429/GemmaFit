"""
YOLO person segmentation + mask ReID prototype for crowded target focus.

This experiment checks whether a detector/segmentation layer can keep a
manually tapped target more reliably than CSRT/KCF + MediaPipe pose in crowded
basketball footage. It is prototype-only and does not change Android code.

Example:
  python prototype/target_active_focus_yolo_reid.py ^
      --video test_assets/videos/pixel_line_error_1774202137014.mp4 ^
      --label pixel_line_error_black_yolo_reid ^
      --start-ms 1900 --tap-x 0.47 --tap-y 0.53
"""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np
from ultralytics import YOLO

from multi_person_pose import MultiPersonPose
from person_proposals import build_person_proposals, person_bbox_for
from subject_selector import PoseBBox, PoseCandidate
from target_active_focus_v2 import (
    _bbox_center,
    _bbox_iou,
    _center_distance,
    _draw_candidate,
    _draw_person_bbox,
    _make_contact_sheet,
    _put_header,
    _rect_from_norm,
)


PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_VIDEO = PROJECT_ROOT / "test_assets" / "videos" / "pixel_line_error_1774202137014.mp4"
DEFAULT_OUT_DIR = PROJECT_ROOT / "prototype" / "outputs" / "target_active_focus_yolo_reid"

PART_WEIGHTS: Dict[str, float] = {
    "upper": 0.36,
    "lower": 0.34,
    "full": 0.18,
    "shoes": 0.12,
}

STAT_BLACK = 0
STAT_WHITE = 1
STAT_GREEN = 3
STAT_MEAN_VAL = 6
POSE_MASK_KEYPOINTS: Tuple[int, ...] = (11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
POSE_MASK_TORSO_KEYPOINTS: Tuple[int, ...] = (11, 12, 23, 24)


@dataclass
class MaskSignature:
    hists: Dict[str, np.ndarray]
    stats: Dict[str, np.ndarray]

    def similarity(self, other: Optional["MaskSignature"]) -> float:
        if other is None:
            return 0.0
        weighted = 0.0
        total = 0.0
        for part, weight in PART_WEIGHTS.items():
            hist_sim = _hist_intersection(self.hists.get(part), other.hists.get(part))
            stat_sim = _stat_similarity(self.stats.get(part), other.stats.get(part))
            weighted += weight * (0.55 * hist_sim + 0.45 * stat_sim)
            total += weight
        return 0.0 if total <= 0.0 else _clamp01(weighted / total)

    def blend(self, other: Optional["MaskSignature"], alpha: float = 0.04) -> "MaskSignature":
        if other is None:
            return self
        alpha = _clamp01(alpha)
        hists: Dict[str, np.ndarray] = {}
        stats: Dict[str, np.ndarray] = {}
        for part in PART_WEIGHTS:
            a = self.hists.get(part)
            b = other.hists.get(part)
            hists[part] = _blend_array(a, b, alpha)
            sa = self.stats.get(part)
            sb = other.stats.get(part)
            stats[part] = _blend_array(sa, sb, alpha)
        return MaskSignature(hists=hists, stats=stats)


@dataclass
class PersonDetection:
    index: int
    bbox: PoseBBox
    confidence: float
    mask: np.ndarray
    signature: MaskSignature


@dataclass
class YoloTrackState:
    initialized: bool = False
    anchor: Optional[MaskSignature] = None
    last_bbox: Optional[PoseBBox] = None
    velocity_x: float = 0.0
    velocity_y: float = 0.0
    last_selected_ms: Optional[int] = None
    hold_frames: int = 0
    lost_since_ms: Optional[int] = None


def _clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else value)


def _normalize_hist(hist: np.ndarray) -> np.ndarray:
    clean = np.nan_to_num(hist.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)
    clean = np.maximum(clean, 0.0)
    total = float(clean.sum())
    if total <= 0.0:
        return np.zeros_like(clean, dtype=np.float32)
    return clean / total


def _hist_intersection(a: Optional[np.ndarray], b: Optional[np.ndarray]) -> float:
    if a is None or b is None or a.size == 0 or b.size == 0 or a.shape != b.shape:
        return 0.0
    aa = _normalize_hist(a)
    bb = _normalize_hist(b)
    if float(aa.sum()) <= 0.0 or float(bb.sum()) <= 0.0:
        return 0.0
    return _clamp01(float(np.minimum(aa, bb).sum()))


def _stat_similarity(a: Optional[np.ndarray], b: Optional[np.ndarray]) -> float:
    if a is None or b is None or a.shape != b.shape or a.size != 7:
        return 0.0
    aa = np.nan_to_num(a.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)
    bb = np.nan_to_num(b.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)
    weights = np.array([0.18, 0.18, 0.14, 0.22, 0.08, 0.10, 0.10], dtype=np.float32)
    distance = float(np.sum(np.abs(aa - bb) * weights))
    return _clamp01(1.0 - distance)


def _blend_array(a: Optional[np.ndarray], b: Optional[np.ndarray], alpha: float) -> np.ndarray:
    if a is None or a.size == 0:
        return np.array([] if b is None else b, dtype=np.float32)
    if b is None or b.size == 0 or a.shape != b.shape:
        return np.array(a, dtype=np.float32)
    return ((1.0 - alpha) * a.astype(np.float32) + alpha * b.astype(np.float32)).astype(np.float32)


def _stat_at(stats: Optional[np.ndarray], index: int, default: float = 0.0) -> float:
    if stats is None or stats.size <= index:
        return default
    return float(stats[index])


def _part_mask(mask: np.ndarray, bbox: PoseBBox, part: str) -> np.ndarray:
    height, width = mask.shape[:2]
    left, top, right, bottom = _rect_from_norm(bbox, width, height)
    result = np.zeros_like(mask, dtype=np.uint8)
    if right <= left or bottom <= top:
        return result
    box_h = bottom - top
    if part == "upper":
        y0 = top + int(0.12 * box_h)
        y1 = top + int(0.58 * box_h)
    elif part == "lower":
        y0 = top + int(0.48 * box_h)
        y1 = top + int(0.86 * box_h)
    elif part == "shoes":
        y0 = top + int(0.78 * box_h)
        y1 = bottom
    else:
        y0 = top
        y1 = bottom
    result[y0:y1, left:right] = mask[y0:y1, left:right]
    return result


def _masked_hist(frame: np.ndarray, mask: np.ndarray) -> np.ndarray:
    if mask is None or int(mask.sum()) < 16:
        return np.zeros(16 * 8 * 4, dtype=np.float32)
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    hist = cv2.calcHist([hsv], [0, 1, 2], mask.astype(np.uint8), [16, 8, 4], [0, 180, 0, 256, 0, 256])
    return _normalize_hist(hist.reshape(-1))


def _masked_stats(frame: np.ndarray, mask: np.ndarray) -> np.ndarray:
    if mask is None or int(mask.sum()) < 16:
        return np.zeros(7, dtype=np.float32)
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    selected = mask.astype(bool)
    hue = hsv[:, :, 0][selected].astype(np.float32)
    sat = hsv[:, :, 1][selected].astype(np.float32)
    val = hsv[:, :, 2][selected].astype(np.float32)
    if hue.size == 0:
        return np.zeros(7, dtype=np.float32)
    green = (hue >= 35.0) & (hue <= 92.0) & (sat >= 70.0)
    white = (val >= 170.0) & (sat <= 95.0)
    black = val <= 80.0
    return np.array([
        float(np.mean(black)),
        float(np.mean(white)),
        float(np.mean(val >= 170.0)),
        float(np.mean(green)),
        float(np.mean(sat >= 70.0)),
        float(np.mean(sat) / 255.0),
        float(np.mean(val) / 255.0),
    ], dtype=np.float32)


def _signature_for_detection(frame: np.ndarray, bbox: PoseBBox, mask: np.ndarray) -> MaskSignature:
    hists: Dict[str, np.ndarray] = {}
    stats: Dict[str, np.ndarray] = {}
    for part in PART_WEIGHTS:
        part_mask = _part_mask(mask, bbox, part)
        hists[part] = _masked_hist(frame, part_mask)
        stats[part] = _masked_stats(frame, part_mask)
    return MaskSignature(hists=hists, stats=stats)


def _detect_people(model: YOLO, frame: np.ndarray, conf: float, imgsz: int) -> List[PersonDetection]:
    height, width = frame.shape[:2]
    result = model.predict(frame, imgsz=imgsz, conf=conf, classes=[0], verbose=False)[0]
    detections: List[PersonDetection] = []
    boxes = result.boxes
    if boxes is None or len(boxes) == 0:
        return detections
    polygons = result.masks.xy if result.masks is not None else []
    xyxy = boxes.xyxy.cpu().numpy()
    confs = boxes.conf.cpu().numpy()
    for i, rect in enumerate(xyxy):
        x0, y0, x1, y1 = [float(v) for v in rect]
        bbox = PoseBBox(
            left=_clamp01(x0 / width),
            top=_clamp01(y0 / height),
            right=_clamp01(x1 / width),
            bottom=_clamp01(y1 / height),
        )
        if bbox.area <= 0.002:
            continue
        mask = np.zeros((height, width), dtype=np.uint8)
        if i < len(polygons) and len(polygons[i]) >= 3:
            pts = np.asarray(polygons[i], dtype=np.int32)
            cv2.fillPoly(mask, [pts], 1)
        else:
            left, top, right, bottom = _rect_from_norm(bbox, width, height)
            mask[top:bottom, left:right] = 1
        detections.append(
            PersonDetection(
                index=i,
                bbox=bbox,
                confidence=float(confs[i]),
                mask=mask,
                signature=_signature_for_detection(frame, bbox, mask),
            )
        )
    return detections


def _select_detection_from_tap(detections: List[PersonDetection], x: float, y: float) -> int:
    containing = [(i, det.bbox.area) for i, det in enumerate(detections) if det.bbox.contains(x, y)]
    if containing:
        return max(containing, key=lambda item: item[1])[0]
    best_i = -1
    best_distance = float("inf")
    for i, det in enumerate(detections):
        cx, cy = _bbox_center(det.bbox)
        distance = math.hypot(cx - x, cy - y)
        if distance < best_distance:
            best_i = i
            best_distance = distance
    return best_i


def _predict_bbox(state: YoloTrackState, ts_ms: int) -> Optional[PoseBBox]:
    if state.last_bbox is None:
        return None
    if state.last_selected_ms is None:
        return state.last_bbox
    dt_s = max(0.0, min(1.0, (ts_ms - state.last_selected_ms) / 1000.0))
    return PoseBBox(
        left=_clamp01(state.last_bbox.left + state.velocity_x * dt_s),
        top=_clamp01(state.last_bbox.top + state.velocity_y * dt_s),
        right=_clamp01(state.last_bbox.right + state.velocity_x * dt_s),
        bottom=_clamp01(state.last_bbox.bottom + state.velocity_y * dt_s),
    )


def _area_similarity(a: Optional[PoseBBox], b: PoseBBox) -> float:
    if a is None or a.area <= 0.0 or b.area <= 0.0:
        return 0.5
    ratio = max(a.area, b.area) / max(1e-6, min(a.area, b.area))
    return _clamp01(1.0 - math.log(max(1.0, ratio)) / math.log(4.0))


def _edge_partial_score(bbox: PoseBBox) -> float:
    height = max(0.0, bbox.bottom - bbox.top)
    side_cut = bbox.left < 0.06 or bbox.right > 0.94
    lower_body_only = bbox.top > 0.55 and bbox.bottom > 0.92
    side_partial = side_cut and height < 0.56
    if lower_body_only and side_partial:
        return 1.0
    if lower_body_only or side_partial:
        return 0.7
    return 0.0


def _score_detections(detections: List[PersonDetection], state: YoloTrackState, ts_ms: int) -> List[dict]:
    predicted = _predict_bbox(state, ts_ms)
    scored: List[dict] = []
    anchor = state.anchor
    anchor_upper = anchor.stats.get("upper") if anchor is not None else None
    anchor_lower = anchor.stats.get("lower") if anchor is not None else None
    anchor_upper_green = _stat_at(anchor_upper, STAT_GREEN)
    anchor_upper_black = _stat_at(anchor_upper, STAT_BLACK)
    anchor_lower_black = _stat_at(anchor_lower, STAT_BLACK)
    anchor_upper_white = _stat_at(anchor_upper, STAT_WHITE)
    anchor_lower_white = _stat_at(anchor_lower, STAT_WHITE)
    anchor_upper_val = _stat_at(anchor_upper, STAT_MEAN_VAL)
    anchor_lower_val = _stat_at(anchor_lower, STAT_MEAN_VAL)
    for i, det in enumerate(detections):
        appearance = anchor.similarity(det.signature) if anchor is not None else 0.5
        iou = _bbox_iou(predicted, det.bbox) if predicted is not None else 0.0
        center = _clamp01(1.0 - (_center_distance(predicted, det.bbox) if predicted is not None else 0.0) / 0.48)
        area = _area_similarity(predicted, det.bbox)
        edge_partial = _edge_partial_score(det.bbox)
        upper_stats = det.signature.stats.get("upper")
        lower_stats = det.signature.stats.get("lower")
        upper_green = _stat_at(upper_stats, STAT_GREEN)
        upper_black = _stat_at(upper_stats, STAT_BLACK)
        lower_black = _stat_at(lower_stats, STAT_BLACK)
        upper_white = _stat_at(upper_stats, STAT_WHITE)
        lower_white = _stat_at(lower_stats, STAT_WHITE)
        upper_val = _stat_at(upper_stats, STAT_MEAN_VAL)
        lower_val = _stat_at(lower_stats, STAT_MEAN_VAL)
        green_penalty = max(0.0, upper_green - anchor_upper_green - 0.12)
        black_drop = (
            max(0.0, anchor_upper_black - upper_black - 0.18) +
            max(0.0, anchor_lower_black - lower_black - 0.18)
        )
        brightness_jump = (
            max(0.0, upper_val - anchor_upper_val - 0.15) +
            max(0.0, lower_val - anchor_lower_val - 0.15)
        )
        white_jump = (
            max(0.0, upper_white - anchor_upper_white - 0.12) +
            max(0.0, lower_white - anchor_lower_white - 0.12)
        )
        white_penalty = max(0.0, lower_white - anchor_lower_white - 0.18)
        identity = _clamp01(
            0.52 * appearance +
            0.19 * center +
            0.12 * iou +
            0.10 * area +
            0.07 * det.confidence -
            0.22 * green_penalty -
            0.18 * white_penalty -
            0.34 * black_drop -
            0.26 * brightness_jump -
            0.24 * white_jump -
            0.38 * edge_partial
        )
        scored.append({
            "index": i,
            "identity": identity,
            "appearance": appearance,
            "center": center,
            "iou": iou,
            "area": area,
            "confidence": det.confidence,
            "upper_green": upper_green,
            "upper_black": upper_black,
            "lower_black": lower_black,
            "upper_white": upper_white,
            "lower_white": lower_white,
            "upper_val": upper_val,
            "lower_val": lower_val,
            "green_penalty": green_penalty,
            "white_penalty": white_penalty,
            "black_drop": black_drop,
            "brightness_jump": brightness_jump,
            "white_jump": white_jump,
            "edge_partial": edge_partial,
        })
    scored.sort(key=lambda item: item["identity"], reverse=True)
    return scored


def _accept(best: dict, margin: float, state: YoloTrackState) -> Tuple[bool, str]:
    reacquiring = state.hold_frames > 0 or state.lost_since_ms is not None
    if best["green_penalty"] > 0.12:
        return False, "green_contamination_hold"
    if best["white_penalty"] > 0.20:
        return False, "shorts_color_mismatch_hold"
    if best.get("black_drop", 0.0) > 0.20:
        return False, "target_darkness_mismatch_hold"
    if best.get("brightness_jump", 0.0) > 0.20:
        return False, "target_brightness_mismatch_hold"
    if best.get("white_jump", 0.0) > 0.22:
        return False, "target_white_mismatch_hold"
    if best.get("edge_partial", 0.0) >= 0.7:
        return False, "partial_body_edge_hold"
    if best["iou"] < 0.08 and best["appearance"] < (0.76 if reacquiring else 0.72):
        return False, "weak_identity_jump_hold"
    if best["appearance"] < (0.60 if reacquiring else 0.54):
        return False, "mask_reid_mismatch_hold"
    if best["center"] < (0.35 if reacquiring else 0.25) and best["iou"] < 0.08:
        return False, "motion_gate_hold"
    if margin < (0.06 if reacquiring else 0.035) and best["identity"] < 0.72:
        return False, "identity_margin_hold"
    threshold = 0.64 if reacquiring else 0.58
    if best["identity"] < threshold:
        return False, "identity_score_hold"
    return True, "yolo_reid_reacquired" if reacquiring else "yolo_reid_confirmed"


def _update_state(state: YoloTrackState, detection: PersonDetection, ts_ms: int) -> None:
    if state.last_bbox is not None and state.last_selected_ms is not None:
        dt_s = max(0.001, (ts_ms - state.last_selected_ms) / 1000.0)
        prev_cx, prev_cy = _bbox_center(state.last_bbox)
        cur_cx, cur_cy = _bbox_center(detection.bbox)
        state.velocity_x = 0.70 * state.velocity_x + 0.30 * ((cur_cx - prev_cx) / dt_s)
        state.velocity_y = 0.70 * state.velocity_y + 0.30 * ((cur_cy - prev_cy) / dt_s)
    state.last_bbox = detection.bbox
    state.last_selected_ms = ts_ms
    state.hold_frames = 0
    state.lost_since_ms = None
    state.initialized = True
    if state.anchor is None:
        state.anchor = detection.signature
    elif detection.confidence >= 0.50:
        state.anchor = state.anchor.blend(detection.signature, alpha=0.03)


def _pose_mask_match(candidate: PoseCandidate, detection: PersonDetection) -> dict:
    mask = detection.mask
    if mask is None or mask.size == 0:
        return {"ratio": 0.0, "torso_ratio": 0.0, "visible": 0}
    height, width = mask.shape[:2]
    # A small dilation absorbs segmentation contour noise without letting a
    # neighboring player win purely from bbox overlap.
    match_mask = cv2.dilate(mask.astype(np.uint8), np.ones((7, 7), dtype=np.uint8), iterations=1)
    weighted_hits = 0.0
    weighted_total = 0.0
    torso_hits = 0
    torso_total = 0
    visible = 0
    for idx in POSE_MASK_KEYPOINTS:
        if idx >= candidate.landmarks.shape[0]:
            continue
        x = float(candidate.landmarks[idx, 0])
        y = float(candidate.landmarks[idx, 1])
        visibility = float(candidate.landmarks[idx, 2])
        if visibility < 0.25 or not math.isfinite(x) or not math.isfinite(y):
            continue
        px = int(round(_clamp01(x) * (width - 1)))
        py = int(round(_clamp01(y) * (height - 1)))
        hit = bool(match_mask[py, px])
        weight = 1.6 if idx in POSE_MASK_TORSO_KEYPOINTS else 1.0
        weighted_total += weight
        weighted_hits += weight if hit else 0.0
        visible += 1
        if idx in POSE_MASK_TORSO_KEYPOINTS:
            torso_total += 1
            torso_hits += 1 if hit else 0
    ratio = weighted_hits / weighted_total if weighted_total > 0.0 else 0.0
    torso_ratio = torso_hits / torso_total if torso_total > 0 else 0.0
    return {"ratio": ratio, "torso_ratio": torso_ratio, "visible": visible}


def _match_pose_to_detection(candidates: List[PoseCandidate], detection: Optional[PersonDetection]) -> Optional[int]:
    if detection is None:
        return None
    best_i: Optional[int] = None
    best_score = -1.0
    for i, candidate in enumerate(candidates):
        bbox = person_bbox_for(candidate)
        iou = _bbox_iou(bbox, detection.bbox)
        center = _clamp01(1.0 - _center_distance(bbox, detection.bbox) / 0.35)
        mask_match = _pose_mask_match(candidate, detection)
        if mask_match["visible"] < 4:
            continue
        if mask_match["ratio"] < 0.34:
            continue
        if mask_match["torso_ratio"] < 0.34 and iou < 0.45:
            continue
        score = 0.42 * iou + 0.23 * center + 0.25 * mask_match["ratio"] + 0.10 * mask_match["torso_ratio"]
        if score > best_score:
            best_score = score
            best_i = i
    if best_i is None or best_score < 0.44:
        return None
    return best_i


def _draw_detection(frame: np.ndarray, det: PersonDetection, label: str, color: Tuple[int, int, int], selected: bool) -> None:
    height, width = frame.shape[:2]
    left, top, right, bottom = _rect_from_norm(det.bbox, width, height)
    if selected:
        overlay = frame.copy()
        overlay[det.mask.astype(bool)] = (
            0.70 * overlay[det.mask.astype(bool)] + 0.30 * np.array(color, dtype=np.float32)
        ).astype(np.uint8)
        cv2.addWeighted(overlay, 0.70, frame, 0.30, 0, frame)
    cv2.rectangle(frame, (left, top), (right, bottom), color, 3 if selected else 1, cv2.LINE_AA)
    cv2.putText(frame, label, (left, max(18, top - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.48, color, 1, cv2.LINE_AA)


def _expanded_roi_rect(
    bbox: PoseBBox,
    width: int,
    height: int,
    pad_x: float = 0.18,
    pad_y_top: float = 0.14,
    pad_y_bottom: float = 0.10,
) -> Tuple[int, int, int, int, PoseBBox]:
    left_n = _clamp01(bbox.left - bbox.width * pad_x)
    top_n = _clamp01(bbox.top - bbox.height * pad_y_top)
    right_n = _clamp01(bbox.right + bbox.width * pad_x)
    bottom_n = _clamp01(bbox.bottom + bbox.height * pad_y_bottom)
    left = max(0, min(width - 2, int(math.floor(left_n * width))))
    top = max(0, min(height - 2, int(math.floor(top_n * height))))
    right = max(left + 2, min(width, int(math.ceil(right_n * width))))
    bottom = max(top + 2, min(height, int(math.ceil(bottom_n * height))))
    roi = PoseBBox(
        left=left / width,
        top=top / height,
        right=right / width,
        bottom=bottom / height,
    )
    return left, top, right, bottom, roi


def _target_masked_roi(frame: np.ndarray, detection: PersonDetection) -> Tuple[Optional[np.ndarray], Optional[PoseBBox], float]:
    height, width = frame.shape[:2]
    left, top, right, bottom, roi = _expanded_roi_rect(detection.bbox, width, height)
    crop = frame[top:bottom, left:right]
    mask_crop = detection.mask[top:bottom, left:right].astype(np.uint8)
    if crop.size == 0 or mask_crop.size == 0:
        return None, None, 0.0
    mask_ratio = float(mask_crop.mean())
    if int(mask_crop.sum()) < 32:
        return None, roi, mask_ratio
    kernel = np.ones((9, 9), dtype=np.uint8)
    dilated = cv2.dilate(mask_crop, kernel, iterations=1).astype(bool)
    dimmed = (crop.astype(np.float32) * 0.14).astype(np.uint8)
    dimmed[dilated] = crop[dilated]
    return dimmed, roi, mask_ratio


def _remap_crop_landmarks(landmarks: np.ndarray, roi: PoseBBox) -> np.ndarray:
    remapped = np.array(landmarks, dtype=np.float32, copy=True)
    remapped[:, 0] = np.clip(roi.left + remapped[:, 0] * roi.width, 0.0, 1.0)
    remapped[:, 1] = np.clip(roi.top + remapped[:, 1] * roi.height, 0.0, 1.0)
    return remapped


def _detect_pose_in_detection_roi(
    pose: MultiPersonPose,
    frame: np.ndarray,
    detection: Optional[PersonDetection],
    timestamp_ms: int,
) -> Tuple[Optional[PoseCandidate], dict]:
    info = {
        "pose_mode": "yolo_roi_single_pose",
        "roi_pose_raw_candidates": 0,
        "roi_pose_gated_out": 0,
        "roi_mask_ratio": None,
        "roi_pose_mask_ratio": None,
        "roi_pose_torso_ratio": None,
    }
    if detection is None:
        return None, info

    roi_frame, roi_bbox, mask_ratio = _target_masked_roi(frame, detection)
    info["roi_mask_ratio"] = round(mask_ratio, 5)
    if roi_frame is None or roi_bbox is None:
        return None, info

    raw_candidates = pose.detect_for_video(roi_frame, timestamp_ms)
    info["roi_pose_raw_candidates"] = len(raw_candidates)
    remapped_candidates = [_remap_crop_landmarks(candidate, roi_bbox) for candidate in raw_candidates]
    proposals, gated_out = build_person_proposals(remapped_candidates)
    info["roi_pose_gated_out"] = gated_out
    if not proposals:
        return None, info

    candidate = proposals[0].candidate
    mask_match = _pose_mask_match(candidate, detection)
    info["roi_pose_mask_ratio"] = round(float(mask_match["ratio"]), 5)
    info["roi_pose_torso_ratio"] = round(float(mask_match["torso_ratio"]), 5)
    if mask_match["visible"] < 4 or mask_match["ratio"] < 0.30:
        return None, info
    if mask_match["torso_ratio"] < 0.25 and mask_match["ratio"] < 0.42:
        return None, info
    return candidate, info


def _run(
    video_path: Path,
    out_dir: Path,
    label: str,
    model_name: str,
    start_ms: int,
    tap_x: float,
    tap_y: float,
    every_ms: int,
    max_frames: int,
    num_poses: int,
    conf: float,
    imgsz: int,
    max_hold_ms: int,
) -> dict:
    if not video_path.exists():
        raise FileNotFoundError(video_path)
    out_dir.mkdir(parents=True, exist_ok=True)
    overlay_path = out_dir / f"{label}_yolo_reid_overlay.mp4"
    trace_path = out_dir / f"{label}_yolo_reid_trace.json"
    summary_path = out_dir / f"{label}_yolo_reid_summary.json"
    contact_sheet_path = out_dir / f"{label}_yolo_reid_contact_sheet.jpg"

    model = YOLO(model_name)
    cap = cv2.VideoCapture(str(video_path))
    if not cap.isOpened():
        raise FileNotFoundError(f"Cannot open video: {video_path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    writer = cv2.VideoWriter(str(overlay_path), cv2.VideoWriter_fourcc(*"mp4v"), 1000.0 / every_ms, (width, height))
    if not writer.isOpened():
        cap.release()
        raise RuntimeError(f"Cannot create output video: {overlay_path}")

    state = YoloTrackState()
    trace: List[dict] = []
    frame_idx = 0
    last_emit_ms = -1
    detect_seconds = 0.0
    pose_seconds = 0.0
    started = time.time()

    with MultiPersonPose(num_poses=1) as pose:
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

                yolo_start = time.perf_counter()
                detections = _detect_people(model, frame, conf=conf, imgsz=imgsz)
                detect_seconds += time.perf_counter() - yolo_start

                selected_detection_index: Optional[int] = None
                selected_pose_index: Optional[int] = None
                selected_pose_candidate: Optional[PoseCandidate] = None
                selected_detection: Optional[PersonDetection] = None
                scored: List[dict] = []
                best: Optional[dict] = None
                margin: Optional[float] = None
                roi_pose_info: dict = {
                    "pose_mode": "yolo_roi_single_pose",
                    "roi_pose_raw_candidates": 0,
                    "roi_pose_gated_out": 0,
                    "roi_mask_ratio": None,
                    "roi_pose_mask_ratio": None,
                    "roi_pose_torso_ratio": None,
                }
                reason = "no_person_detected" if not detections else "not_initialized"

                if not state.initialized and detections:
                    tap_i = _select_detection_from_tap(detections, tap_x, tap_y)
                    if tap_i >= 0:
                        selected_detection_index = tap_i
                        selected_detection = detections[tap_i]
                        _update_state(state, selected_detection, ts_ms)
                        reason = "manual_target_initialized"
                elif state.initialized:
                    scored = _score_detections(detections, state, ts_ms)
                    if scored:
                        best = scored[0]
                        second = scored[1] if len(scored) > 1 else None
                        margin = best["identity"] - second["identity"] if second is not None else 1.0
                        accepted, reason = _accept(best, margin, state)
                        if accepted:
                            selected_detection_index = int(best["index"])
                            selected_detection = detections[selected_detection_index]
                            _update_state(state, selected_detection, ts_ms)
                        else:
                            state.hold_frames += 1
                            if state.lost_since_ms is None:
                                state.lost_since_ms = ts_ms
                    else:
                        state.hold_frames += 1
                        if state.lost_since_ms is None:
                            state.lost_since_ms = ts_ms
                        reason = "detector_no_person_hold"
                    if state.lost_since_ms is not None and ts_ms - state.lost_since_ms > max_hold_ms:
                        reason = "target_lost"

                pose_start = time.perf_counter()
                selected_pose_candidate, roi_pose_info = _detect_pose_in_detection_roi(pose, frame, selected_detection, ts_ms)
                if selected_pose_candidate is not None:
                    selected_pose_index = 0
                pose_seconds += time.perf_counter() - pose_start

                for i, det in enumerate(detections):
                    is_selected = i == selected_detection_index
                    score_item = next((item for item in scored if int(item["index"]) == i), None)
                    label_text = f"person {i} conf={det.confidence:.2f}"
                    if score_item is not None:
                        label_text += f" id={score_item['identity']:.2f} app={score_item['appearance']:.2f}"
                    _draw_detection(frame, det, label_text, (64, 220, 120) if is_selected else (128, 156, 180), is_selected)

                if selected_pose_candidate is not None:
                    _draw_person_bbox(frame, person_bbox_for(selected_pose_candidate), (64, 220, 120), "roi pose", 2)
                    _draw_candidate(frame, selected_pose_candidate, "roi_pose", (64, 220, 120), True, draw_skeleton=True)

                row = {
                    "sample_index": len(trace),
                    "frame": frame_idx - 1,
                    "timestamp_ms": ts_ms,
                    "initialized": state.initialized,
                    "detections": len(detections),
                    "pose_candidates": roi_pose_info["roi_pose_raw_candidates"],
                    "pose_gated_out": roi_pose_info["roi_pose_gated_out"],
                    "pose_mode": roi_pose_info["pose_mode"],
                    "roi_mask_ratio": roi_pose_info["roi_mask_ratio"],
                    "roi_pose_mask_ratio": roi_pose_info["roi_pose_mask_ratio"],
                    "roi_pose_torso_ratio": roi_pose_info["roi_pose_torso_ratio"],
                    "selected_detection_index": selected_detection_index,
                    "selected_pose_index": selected_pose_index,
                    "hold_frames": state.hold_frames,
                    "reason": reason,
                    "best_identity": round(float(best["identity"]), 5) if best else None,
                    "best_appearance": round(float(best["appearance"]), 5) if best else None,
                    "best_center": round(float(best["center"]), 5) if best else None,
                    "best_iou": round(float(best["iou"]), 5) if best else None,
                    "best_green_penalty": round(float(best["green_penalty"]), 5) if best else None,
                    "best_white_penalty": round(float(best["white_penalty"]), 5) if best else None,
                    "best_black_drop": round(float(best["black_drop"]), 5) if best else None,
                    "best_brightness_jump": round(float(best["brightness_jump"]), 5) if best else None,
                    "best_white_jump": round(float(best["white_jump"]), 5) if best else None,
                    "best_edge_partial": round(float(best["edge_partial"]), 5) if best else None,
                    "top2_margin": round(float(margin), 5) if margin is not None else None,
                    "selected_bbox": (
                        [
                            round(float(selected_detection.bbox.left), 5),
                            round(float(selected_detection.bbox.top), 5),
                            round(float(selected_detection.bbox.right), 5),
                            round(float(selected_detection.bbox.bottom), 5),
                        ]
                        if selected_detection is not None
                        else None
                    ),
                    "detection_bboxes": [
                        [
                            round(float(det.bbox.left), 5),
                            round(float(det.bbox.top), 5),
                            round(float(det.bbox.right), 5),
                            round(float(det.bbox.bottom), 5),
                        ]
                        for det in detections
                    ],
                    "scores": [
                        {k: (round(float(v), 5) if isinstance(v, (float, np.floating)) else v) for k, v in item.items()}
                        for item in scored
                    ],
                }
                trace.append(row)

                header = [
                    f"GemmaFit YOLO-seg ReID | {label} | {model_name}",
                    f"frame={row['frame']} t={ts_ms}ms selected_det={selected_detection_index} selected_pose={selected_pose_index} reason={reason}",
                    f"dets={row['detections']} poses={row['pose_candidates']} hold={row['hold_frames']}",
                    f"id={row['best_identity']} app={row['best_appearance']} center={row['best_center']} margin={row['top2_margin']}",
                    f"dark_drop={row['best_black_drop']} bright_jump={row['best_brightness_jump']} white_jump={row['best_white_jump']} edge={row['best_edge_partial']}",
                    f"tap=({tap_x:.3f}, {tap_y:.3f})",
                ]
                _put_header(frame, header)
                writer.write(frame)
                last_emit_ms = ts_ms
        finally:
            cap.release()
            writer.release()

    selected_detection_frames = sum(1 for row in trace if row["selected_detection_index"] is not None)
    selected_pose_frames = sum(1 for row in trace if row["selected_pose_index"] is not None)
    target_lost_frames = sum(1 for row in trace if row["reason"] == "target_lost")
    hold_frames = sum(1 for row in trace if row["selected_detection_index"] is None and row["initialized"])

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
            "model": model_name,
            "start_ms": start_ms,
            "tap": [tap_x, tap_y],
            "every_ms": every_ms,
            "num_poses": num_poses,
            "conf": conf,
            "imgsz": imgsz,
            "max_hold_ms": max_hold_ms,
        },
        "summary": {
            "frames_analyzed": len(trace),
            "selected_detection_frames": selected_detection_frames,
            "selected_detection_ratio": round(selected_detection_frames / max(1, len(trace)), 5),
            "selected_pose_frames": selected_pose_frames,
            "selected_pose_ratio": round(selected_pose_frames / max(1, len(trace)), 5),
            "hold_frames": hold_frames,
            "target_lost_frames": target_lost_frames,
            "elapsed_s": round(time.time() - started, 3),
            "yolo_seconds": round(detect_seconds, 4),
            "pose_seconds": round(pose_seconds, 4),
            "avg_yolo_ms_per_sample": round((detect_seconds / max(1, len(trace))) * 1000.0, 4),
        },
        "assertions": [
            {
                "name": "manual target initialized",
                "passed": any(row["reason"] == "manual_target_initialized" for row in trace),
                "detail": f"selected_detection_frames={selected_detection_frames}",
            },
            {
                "name": "detector selected target on at least some frames",
                "passed": selected_detection_frames > 0,
                "detail": f"selected_detection_frames={selected_detection_frames}/{len(trace)}",
            },
        ],
    }
    trace_path.write_text(json.dumps(trace, indent=2, ensure_ascii=False), encoding="utf-8")
    summary_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", default=str(DEFAULT_VIDEO))
    parser.add_argument("--label", default="pixel_line_error_yolo_reid")
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT_DIR))
    parser.add_argument("--model", default="yolov8n-seg.pt")
    parser.add_argument("--start-ms", type=int, default=1900)
    parser.add_argument("--tap-x", type=float, default=0.47)
    parser.add_argument("--tap-y", type=float, default=0.53)
    parser.add_argument("--every-ms", type=int, default=125)
    parser.add_argument("--max-frames", type=int, default=0)
    parser.add_argument("--num-poses", type=int, default=4)
    parser.add_argument("--conf", type=float, default=0.22)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--max-hold-ms", type=int, default=1500)
    args = parser.parse_args()

    summary = _run(
        video_path=Path(args.video),
        out_dir=Path(args.out_dir),
        label=args.label,
        model_name=args.model,
        start_ms=args.start_ms,
        tap_x=args.tap_x,
        tap_y=args.tap_y,
        every_ms=args.every_ms,
        max_frames=args.max_frames,
        num_poses=args.num_poses,
        conf=args.conf,
        imgsz=args.imgsz,
        max_hold_ms=args.max_hold_ms,
    )
    sm = summary["summary"]
    print(f"Overlay: {summary['outputs']['overlay_video']}")
    print(f"Trace:   {summary['outputs']['trace_json']}")
    print(f"Summary: {summary['outputs']['summary_json']}")
    print(f"Sheet:   {summary['outputs']['contact_sheet']}")
    print(
        "frames={frames_analyzed} det_selected={selected_detection_frames} "
        "det_ratio={selected_detection_ratio} pose_selected={selected_pose_frames} "
        "pose_ratio={selected_pose_ratio} hold={hold_frames} lost={target_lost_frames} "
        "avg_yolo_ms={avg_yolo_ms_per_sample}".format(**sm)
    )
    failures = 0
    for item in summary["assertions"]:
        tag = "OK" if item["passed"] else "FAIL"
        if not item["passed"]:
            failures += 1
        print(f"{tag:4s} {item['name']}: {item['detail']}")
    if failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
