"""Presence-gated full-person proposals from MediaPipe pose landmarks.

The pose-derived landmark bbox is intentionally tight around confident joints.
For tap hit testing, ROI prediction, and ReID crops we need a more person-like
box that covers the visible body. This module keeps that conversion shared
between proposal validation and active-focus tracking.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import List, Optional, Tuple

import numpy as np

from subject_selector import PoseBBox, PoseCandidate, build_candidate


VISIBILITY_FLOOR = 0.25
TORSO_POINTS: Tuple[int, ...] = (11, 12, 23, 24)
UPPER_BODY_POINTS: Tuple[int, ...] = (11, 12, 13, 14, 15, 16, 23, 24)
HEAD_POINTS: Tuple[int, ...] = tuple(range(0, 11))
FEET_POINTS: Tuple[int, ...] = (27, 28, 29, 30, 31, 32)
PERSON_BBOX_ATTR = "_gemmafit_person_bbox"


@dataclass
class PersonProposal:
    source_index: int
    candidate: PoseCandidate
    raw_bbox: PoseBBox
    person_bbox: PoseBBox
    high_visibility_count: int
    torso_visible: int
    upper_visible: int


def clamp01(value: float) -> float:
    return 0.0 if value < 0.0 else (1.0 if value > 1.0 else value)


def bbox_iou(a: PoseBBox, b: PoseBBox) -> float:
    left = max(a.left, b.left)
    top = max(a.top, b.top)
    right = min(a.right, b.right)
    bottom = min(a.bottom, b.bottom)
    inter = max(0.0, right - left) * max(0.0, bottom - top)
    union = a.area + b.area - inter
    if union <= 0.0:
        return 0.0
    return clamp01(inter / union)


def person_bbox_for(candidate: PoseCandidate) -> PoseBBox:
    bbox = getattr(candidate, PERSON_BBOX_ATTR, None)
    return bbox if isinstance(bbox, PoseBBox) else candidate.bbox


def attach_person_bbox(candidate: PoseCandidate, bbox: PoseBBox) -> PoseCandidate:
    setattr(candidate, PERSON_BBOX_ATTR, bbox)
    return candidate


def visible_indices(landmarks: np.ndarray, indices: Tuple[int, ...]) -> List[int]:
    return [
        idx for idx in indices
        if idx < landmarks.shape[0]
        and float(landmarks[idx, 2]) >= VISIBILITY_FLOOR
        and math.isfinite(float(landmarks[idx, 0]))
        and math.isfinite(float(landmarks[idx, 1]))
    ]


def mean_xy(landmarks: np.ndarray, indices: List[int]) -> Optional[Tuple[float, float]]:
    if not indices:
        return None
    return (
        float(np.mean(landmarks[indices, 0])),
        float(np.mean(landmarks[indices, 1])),
    )


def expanded_person_bbox(candidate: PoseCandidate) -> PoseBBox:
    """Convert a tight landmark bbox into a visible full-person proposal box."""
    landmarks = candidate.landmarks
    raw = candidate.bbox
    vis = np.nan_to_num(landmarks[:, 2], nan=0.0, posinf=0.0, neginf=0.0)
    mask = (
        (vis >= VISIBILITY_FLOOR)
        & np.isfinite(landmarks[:, 0])
        & np.isfinite(landmarks[:, 1])
    )
    if not np.any(mask):
        return raw

    xs = np.clip(landmarks[mask, 0], 0.0, 1.0)
    ys = np.clip(landmarks[mask, 1], 0.0, 1.0)
    left = float(xs.min())
    right = float(xs.max())
    top = float(ys.min())
    bottom = float(ys.max())

    shoulders = visible_indices(landmarks, (11, 12))
    hips = visible_indices(landmarks, (23, 24))
    head = visible_indices(landmarks, HEAD_POINTS)
    feet = visible_indices(landmarks, FEET_POINTS)

    shoulder_center = mean_xy(landmarks, shoulders)
    hip_center = mean_xy(landmarks, hips)
    torso_h = abs((hip_center[1] - shoulder_center[1]) if shoulder_center and hip_center else 0.0)
    shoulder_w = 0.0
    if 11 in shoulders and 12 in shoulders:
        shoulder_w = abs(float(landmarks[11, 0] - landmarks[12, 0]))

    raw_w = max(right - left, raw.width, 0.01)
    raw_h = max(bottom - top, raw.height, 0.01)
    body_scale = max(raw_w, raw_h * 0.45, shoulder_w * 1.8, torso_h * 0.9, 0.04)

    side_pad = max(raw_w * 0.20, shoulder_w * 0.55, body_scale * 0.12, 0.018)
    top_pad = max(raw_h * 0.08, torso_h * 0.18, body_scale * 0.06, 0.010)
    bottom_pad = max(raw_h * 0.12, torso_h * 0.30, body_scale * 0.10, 0.018)

    if head:
        top = min(top, float(np.min(landmarks[head, 1])))
        top_pad = max(top_pad, body_scale * 0.04)
    else:
        top_pad = max(top_pad, body_scale * 0.10)

    if feet:
        bottom = max(bottom, float(np.max(landmarks[feet, 1])))
        bottom_pad = max(bottom_pad, body_scale * 0.05)
    else:
        bottom_pad = max(bottom_pad, body_scale * 0.22)

    expanded = PoseBBox(
        left=clamp01(left - side_pad),
        top=clamp01(top - top_pad),
        right=clamp01(right + side_pad),
        bottom=clamp01(bottom + bottom_pad),
    )

    min_w = min(0.42, max(raw_w * 1.25, shoulder_w * 2.0, 0.055))
    min_h = min(0.85, max(raw_h * 1.18, torso_h * 2.4, 0.14))
    cx = 0.5 * (expanded.left + expanded.right)
    cy = 0.5 * (expanded.top + expanded.bottom)
    if expanded.width < min_w:
        expanded.left = clamp01(cx - min_w * 0.5)
        expanded.right = clamp01(cx + min_w * 0.5)
    if expanded.height < min_h:
        expanded.top = clamp01(cy - min_h * 0.5)
        expanded.bottom = clamp01(cy + min_h * 0.5)

    return expanded


def nms_proposals(proposals: List[PersonProposal], iou_threshold: float = 0.72) -> List[PersonProposal]:
    ranked = sorted(
        proposals,
        key=lambda p: (
            p.candidate.avg_visibility,
            p.high_visibility_count,
            p.person_bbox.area,
        ),
        reverse=True,
    )
    kept: List[PersonProposal] = []
    for proposal in ranked:
        if any(bbox_iou(proposal.person_bbox, kept_one.person_bbox) >= iou_threshold for kept_one in kept):
            continue
        kept.append(proposal)
    kept.sort(key=lambda p: p.source_index)
    return kept


def build_person_proposals(raw_candidates: List[np.ndarray]) -> Tuple[List[PersonProposal], int]:
    proposals: List[PersonProposal] = []
    gated_out = 0
    for idx, landmarks in enumerate(raw_candidates):
        candidate = build_candidate(landmarks)
        if candidate is None:
            gated_out += 1
            continue
        person_bbox = expanded_person_bbox(candidate)
        attach_person_bbox(candidate, person_bbox)
        vis = np.nan_to_num(candidate.landmarks[:, 2], nan=0.0, posinf=0.0, neginf=0.0)
        high_mask = vis >= VISIBILITY_FLOOR
        proposals.append(
            PersonProposal(
                source_index=idx,
                candidate=candidate,
                raw_bbox=candidate.bbox,
                person_bbox=person_bbox,
                high_visibility_count=int(high_mask.sum()),
                torso_visible=sum(bool(high_mask[i]) for i in TORSO_POINTS),
                upper_visible=sum(bool(high_mask[i]) for i in UPPER_BODY_POINTS),
            )
        )
    return nms_proposals(proposals), gated_out
