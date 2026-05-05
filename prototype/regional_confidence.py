"""
regional_confidence.py — Algorithm #2: per-region confidence gating.

The native `confidence_gate.cpp` averages visibility across all 33
landmarks, which dilutes signal — a frame with great upper-body
visibility (0.95) and occluded lower-body (0.10) averages out to
something like 0.55 and passes the gate, even though squat metrics are
unmeasurable.

Per-region gating splits the 33-keypoint mask into four regions and
asks each exercise template "which regions do you NEED?". The squat
template requires `lower_body`; push_up requires `upper_body + torso`;
balance_hold requires `lower_body + torso`. Frames that fail
template-required regions emit `VIEW_LIMITED` instead of being silently
averaged into a misleading OK.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple

import numpy as np

# Region partition. Indices match the MediaPipe 33-landmark layout.
# Some landmarks belong to multiple regions (e.g. shoulders/hips are
# torso anchors AND part of upper/lower body chains) — we keep them in
# the most physically meaningful region for the gate.

REGIONS: Dict[str, Tuple[int, ...]] = {
    "head":       (0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10),       # face + ears + mouth
    "torso":      (11, 12, 23, 24),                          # shoulders + hips
    "upper_body": (13, 14, 15, 16, 17, 18, 19, 20, 21, 22),  # elbows, wrists, hands
    "lower_body": (25, 26, 27, 28, 29, 30, 31, 32),          # knees, ankles, heels, feet
}


@dataclass
class RegionRequirement:
    """How an exercise template gates landmark confidence per region."""
    required: Set[str]            # regions whose mean visibility must clear `min_mean`
    min_mean: float = 0.5
    min_visible_per_region: int = 3   # at least N keypoints with vis > 0.15


@dataclass
class RegionalConfidence:
    """Per-region report; the gate decision lives in `passed_template`."""
    means:        Dict[str, float]
    visible_counts: Dict[str, int]
    passed_template: bool = True
    failed_regions: List[str] = field(default_factory=list)
    reason: str = ""
    template_used: Optional[str] = None


# Template-specific region requirements. New templates added here.
TEMPLATE_REGION_REQUIREMENTS: Dict[str, RegionRequirement] = {
    "squat":              RegionRequirement(required={"lower_body", "torso"},   min_mean=0.50),
    "push_up":            RegionRequirement(required={"upper_body", "torso"},   min_mean=0.50),
    "lunge":              RegionRequirement(required={"lower_body", "torso"},   min_mean=0.45),
    "deadlift":           RegionRequirement(required={"lower_body", "torso"},   min_mean=0.50),
    # Senior Strength Mode templates
    "chair_sit_to_stand": RegionRequirement(required={"lower_body", "torso"},   min_mean=0.45),
    "supported_squat":    RegionRequirement(required={"lower_body", "torso"},   min_mean=0.40),
    "balance_hold":       RegionRequirement(required={"lower_body", "torso"},   min_mean=0.45),
    "step_touch":         RegionRequirement(required={"lower_body"},            min_mean=0.40),
}

# Fallback when exercise is unknown — require torso + at least one limb region.
DEFAULT_REQUIREMENT = RegionRequirement(
    required={"torso"},
    min_mean=0.50,
    min_visible_per_region=2,
)


def evaluate_regional_confidence(
    landmarks: np.ndarray,
    keypoint_visibility_floor: float = 0.15,
) -> Tuple[Dict[str, float], Dict[str, int]]:
    """Return (mean_visibility_per_region, visible_keypoint_count_per_region)."""
    if landmarks.shape != (33, 3):
        raise ValueError(f"expected (33, 3) landmarks, got {landmarks.shape}")
    means: Dict[str, float] = {}
    counts: Dict[str, int] = {}
    vis = landmarks[:, 2]
    for name, idx in REGIONS.items():
        v = vis[list(idx)]
        means[name] = float(v.mean()) if v.size else 0.0
        counts[name] = int((v > keypoint_visibility_floor).sum())
    return means, counts


def apply_regional_gate(
    landmarks: np.ndarray,
    exercise: str,
    requirement: Optional[RegionRequirement] = None,
) -> RegionalConfidence:
    """
    Decide whether the frame's region visibilities meet the template's
    requirements. Returns a `RegionalConfidence` with `passed_template`
    plus diagnostics.
    """
    means, counts = evaluate_regional_confidence(landmarks)
    req = requirement or TEMPLATE_REGION_REQUIREMENTS.get(exercise, DEFAULT_REQUIREMENT)

    failed: List[str] = []
    reasons: List[str] = []
    for region in req.required:
        if region not in means:
            failed.append(region)
            reasons.append(f"unknown_region:{region}")
            continue
        if means[region] < req.min_mean:
            failed.append(region)
            reasons.append(f"{region}_mean<{req.min_mean:.2f}")
            continue
        if counts[region] < req.min_visible_per_region:
            failed.append(region)
            reasons.append(f"{region}_visible_count<{req.min_visible_per_region}")
    return RegionalConfidence(
        means=means,
        visible_counts=counts,
        passed_template=not failed,
        failed_regions=failed,
        reason="; ".join(reasons),
        template_used=exercise if exercise in TEMPLATE_REGION_REQUIREMENTS else None,
    )


def occlude_region(landmarks: np.ndarray, region: str, new_visibility: float = 0.05) -> np.ndarray:
    """Test helper: zero (or near-zero) visibility for one region's keypoints."""
    if region not in REGIONS:
        raise KeyError(f"unknown region: {region}")
    out = landmarks.copy()
    for idx in REGIONS[region]:
        out[idx, 2] = new_visibility
    return out
