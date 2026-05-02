"""
exercise_templates.py - GemmaFit exercise template definitions.

Phase 3 uses these templates to keep feedback context-aware:
  Pose -> Motion Trace -> Exercise Template -> Structured Metrics -> Feedback

The goal is not to judge every exercise with the same global safety rules.
Each template exposes only the metrics that are reasonable for that exercise,
and downstream quality gates can mark unsuitable metrics as NOT_APPLICABLE or
VIEW_LIMITED.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np


VIS_THRESHOLD = 0.35

IDX = {
    "left_shoulder": 11,
    "right_shoulder": 12,
    "left_elbow": 13,
    "right_elbow": 14,
    "left_wrist": 15,
    "right_wrist": 16,
    "left_hip": 23,
    "right_hip": 24,
    "left_knee": 25,
    "right_knee": 26,
    "left_ankle": 27,
    "right_ankle": 28,
}


@dataclass
class ExerciseTemplate:
    name: str
    display_name: str
    required_metrics: List[str]
    disabled_rules: List[str]
    heuristic_weight: float = 1.0
    body_orientation: str = "upright"
    primary_joint: str = "knee"
    support_type: str = "bipedal"
    target_rom: Dict[str, Tuple[float, float]] = field(default_factory=dict)


EXERCISE_TEMPLATES: Dict[str, ExerciseTemplate] = {
    "squat": ExerciseTemplate(
        name="squat",
        display_name="Squat",
        required_metrics=["depth", "knee_angle", "hip_angle", "trunk_lean", "tempo"],
        disabled_rules=["elbow_overextension", "shoulder_asymmetry"],
        body_orientation="upright",
        primary_joint="knee",
        support_type="bipedal",
        target_rom={"knee": (90.0, 160.0), "hip": (80.0, 170.0)},
    ),
    "push_up": ExerciseTemplate(
        name="push_up",
        display_name="Push-up",
        required_metrics=["elbow_angle", "body_line", "hip_sag", "push_up_depth", "tempo"],
        disabled_rules=["knee_valgus_fppa", "bilateral_knee_asymmetry", "com_offset_dynamic"],
        body_orientation="horizontal",
        primary_joint="elbow",
        support_type="floor_support",
        target_rom={"elbow": (45.0, 170.0)},
    ),
    "lunge": ExerciseTemplate(
        name="lunge",
        display_name="Lunge",
        required_metrics=["front_knee_angle", "step_length_proxy", "trunk_uprightness", "stability"],
        disabled_rules=["bilateral_asymmetry_single_frame_critical"],
        body_orientation="upright",
        primary_joint="knee",
        support_type="unilateral",
        target_rom={"front_knee": (80.0, 160.0), "back_knee": (80.0, 160.0)},
    ),
    "deadlift": ExerciseTemplate(
        name="deadlift",
        display_name="Deadlift",
        required_metrics=["hip_hinge", "trunk_angle", "bar_or_body_path_proxy", "tempo"],
        disabled_rules=["knee_valgus_fppa"],
        body_orientation="upright",
        primary_joint="hip",
        support_type="bipedal",
        target_rom={"hip": (45.0, 170.0), "trunk": (0.0, 60.0)},
    ),
}


def _pt(landmarks: np.ndarray, idx: int) -> np.ndarray:
    return landmarks[idx, :2]


def _visible(landmarks: np.ndarray, idx: int, threshold: float = VIS_THRESHOLD) -> bool:
    if landmarks.shape[0] <= idx:
        return False
    x = float(landmarks[idx, 0])
    y = float(landmarks[idx, 1])
    if abs(x) < 1e-9 and abs(y) < 1e-9:
        return False
    if landmarks.shape[1] < 3:
        return True
    return float(landmarks[idx, 2]) >= threshold


def _midpoint(landmarks: np.ndarray, left_idx: int, right_idx: int) -> np.ndarray:
    return (_pt(landmarks, left_idx) + _pt(landmarks, right_idx)) / 2.0


def _angle(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    v1 = a - b
    v2 = c - b
    denom = np.linalg.norm(v1) * np.linalg.norm(v2)
    if denom < 1e-9:
        return 180.0
    cos_a = np.dot(v1, v2) / denom
    return float(np.degrees(np.arccos(np.clip(cos_a, -1.0, 1.0))))


def _distance_to_line(point: np.ndarray, a: np.ndarray, b: np.ndarray) -> float:
    ab = b - a
    denom = np.linalg.norm(ab)
    if denom < 1e-9:
        return 0.0
    return float(abs(np.cross(ab, point - a)) / denom)


def _segment_angle_from_vertical(a: np.ndarray, b: np.ndarray) -> float:
    vec = b - a
    return float(np.degrees(np.arctan2(abs(vec[0]), abs(vec[1]) + 1e-9)))


def _visible_angle(
    landmarks: np.ndarray,
    angles: Dict[str, float],
    angle_key: str,
    required_indices: Tuple[int, int, int],
) -> Optional[float]:
    if all(_visible(landmarks, i) for i in required_indices):
        return angles.get(angle_key)
    return None


def _min_visible_angle(
    landmarks: np.ndarray,
    angles: Dict[str, float],
    left_key: str,
    right_key: str,
    left_indices: Tuple[int, int, int],
    right_indices: Tuple[int, int, int],
    default: float = 180.0,
) -> float:
    values = []
    left = _visible_angle(landmarks, angles, left_key, left_indices)
    right = _visible_angle(landmarks, angles, right_key, right_indices)
    for value in (left, right):
        if value is not None and np.isfinite(value):
            values.append(float(value))
    return min(values) if values else default


def _lower_visible_count(landmarks: np.ndarray) -> int:
    return sum(_visible(landmarks, IDX[name]) for name in (
        "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle"
    ))


def compute_squat_metrics(landmarks: np.ndarray, angles: Dict[str, float]) -> Dict[str, float]:
    """Extract squat-specific metrics."""
    hip_y = float(landmarks[IDX["left_hip"], 1])
    knee_y = float(landmarks[IDX["left_knee"], 1])
    ankle_y = float(landmarks[IDX["left_ankle"], 1])
    depth = max(0.0, min(1.0, (ankle_y - hip_y) / (ankle_y - knee_y + 1e-6)))

    shoulder_mid = _midpoint(landmarks, IDX["left_shoulder"], IDX["right_shoulder"])
    hip_mid = _midpoint(landmarks, IDX["left_hip"], IDX["right_hip"])

    return {
        "depth": depth,
        "knee_angle": _min_visible_angle(
            landmarks, angles, "left_knee", "right_knee",
            (IDX["left_hip"], IDX["left_knee"], IDX["left_ankle"]),
            (IDX["right_hip"], IDX["right_knee"], IDX["right_ankle"]),
        ),
        "hip_angle": _min_visible_angle(
            landmarks, angles, "left_hip", "right_hip",
            (IDX["left_shoulder"], IDX["left_hip"], IDX["left_knee"]),
            (IDX["right_shoulder"], IDX["right_hip"], IDX["right_knee"]),
        ),
        "trunk_lean": _segment_angle_from_vertical(shoulder_mid, hip_mid),
    }


def compute_push_up_metrics(landmarks: np.ndarray, angles: Dict[str, float]) -> Dict[str, float]:
    """Extract push-up metrics and mark lower-body view limits explicitly."""
    elbow_angle = _min_visible_angle(
        landmarks, angles, "left_elbow", "right_elbow",
        (IDX["left_shoulder"], IDX["left_elbow"], IDX["left_wrist"]),
        (IDX["right_shoulder"], IDX["right_elbow"], IDX["right_wrist"]),
    )

    shoulder_mid = _midpoint(landmarks, IDX["left_shoulder"], IDX["right_shoulder"])
    hip_mid = _midpoint(landmarks, IDX["left_hip"], IDX["right_hip"])

    lower_ref = None
    if _visible(landmarks, IDX["left_ankle"]) or _visible(landmarks, IDX["right_ankle"]):
        lower_ref = _midpoint(landmarks, IDX["left_ankle"], IDX["right_ankle"])
    elif _visible(landmarks, IDX["left_knee"]) or _visible(landmarks, IDX["right_knee"]):
        lower_ref = _midpoint(landmarks, IDX["left_knee"], IDX["right_knee"])

    if lower_ref is None or not (_visible(landmarks, IDX["left_hip"]) or _visible(landmarks, IDX["right_hip"])):
        body_line_deviation = 0.0
        hip_sag = 0.0
        view_limited = 1.0
    else:
        body_line_deviation = abs(180.0 - _angle(shoulder_mid, hip_mid, lower_ref))
        hip_sag = _distance_to_line(hip_mid, shoulder_mid, lower_ref) * 100.0
        view_limited = 0.0

    return {
        "elbow_angle": elbow_angle,
        "body_line_deviation": body_line_deviation,
        "hip_sag": hip_sag,
        "body_line_view_limited": view_limited,
        "push_up_depth": max(0.0, 180.0 - elbow_angle),
    }


def compute_lunge_metrics(landmarks: np.ndarray, angles: Dict[str, float]) -> Dict[str, float]:
    """Extract lunge-specific metrics."""
    left_knee_x = float(landmarks[IDX["left_knee"], 0])
    right_knee_x = float(landmarks[IDX["right_knee"], 0])
    front_is_left = left_knee_x < right_knee_x
    front_key = "left_knee" if front_is_left else "right_knee"

    shoulder_mid = _midpoint(landmarks, IDX["left_shoulder"], IDX["right_shoulder"])
    hip_mid = _midpoint(landmarks, IDX["left_hip"], IDX["right_hip"])
    left_knee = float(angles.get("left_knee", 180.0))
    right_knee = float(angles.get("right_knee", 180.0))

    return {
        "front_knee_angle": float(angles.get(front_key, 180.0)),
        "step_length_proxy": abs(float(landmarks[IDX["left_ankle"], 0]) - float(landmarks[IDX["right_ankle"], 0])),
        "trunk_uprightness": _segment_angle_from_vertical(shoulder_mid, hip_mid),
        "stability": float(max(landmarks[IDX["left_hip"], 2], landmarks[IDX["right_hip"], 2])),
        "knee_asymmetry_expected": abs(left_knee - right_knee),
    }


def compute_deadlift_metrics(landmarks: np.ndarray, angles: Dict[str, float]) -> Dict[str, float]:
    """Extract deadlift-specific metrics."""
    shoulder_mid = _midpoint(landmarks, IDX["left_shoulder"], IDX["right_shoulder"])
    hip_mid = _midpoint(landmarks, IDX["left_hip"], IDX["right_hip"])

    return {
        "hip_hinge": _min_visible_angle(
            landmarks, angles, "left_hip", "right_hip",
            (IDX["left_shoulder"], IDX["left_hip"], IDX["left_knee"]),
            (IDX["right_shoulder"], IDX["right_hip"], IDX["right_knee"]),
        ),
        "trunk_angle": _segment_angle_from_vertical(shoulder_mid, hip_mid),
        "body_path_proxy": float(landmarks[IDX["left_hip"], 0]),
    }


METRIC_EXTRACTORS = {
    "squat": compute_squat_metrics,
    "push_up": compute_push_up_metrics,
    "lunge": compute_lunge_metrics,
    "deadlift": compute_deadlift_metrics,
}


def detect_exercise(
    landmarks: np.ndarray,
    angles: Dict[str, float],
    prev_landmarks: Optional[np.ndarray] = None,
) -> Dict:
    """Heuristic exercise detection for the Phase 3 prototype dashboard."""
    if landmarks.shape[0] < 33:
        return {
            "exercise": "unknown_or_mixed",
            "exercise_confidence": 0.0,
            "candidate_scores": {},
            "basis": ["insufficient_landmarks"],
        }

    lower_count = _lower_visible_count(landmarks)
    arm_count = sum(_visible(landmarks, IDX[name]) for name in (
        "left_shoulder", "right_shoulder", "left_elbow", "right_elbow", "left_wrist", "right_wrist"
    ))

    shoulder_mid = _midpoint(landmarks, IDX["left_shoulder"], IDX["right_shoulder"])
    hip_mid = _midpoint(landmarks, IDX["left_hip"], IDX["right_hip"])
    ankle_mid = _midpoint(landmarks, IDX["left_ankle"], IDX["right_ankle"])
    wrist_mid = _midpoint(landmarks, IDX["left_wrist"], IDX["right_wrist"])

    shoulder_width = float(np.linalg.norm(_pt(landmarks, IDX["left_shoulder"]) - _pt(landmarks, IDX["right_shoulder"])))
    ankle_separation = abs(float(landmarks[IDX["left_ankle"], 0]) - float(landmarks[IDX["right_ankle"], 0])) if lower_count >= 2 else 0.0
    knee_height_diff = abs(float(landmarks[IDX["left_knee"], 1]) - float(landmarks[IDX["right_knee"], 1])) if lower_count >= 4 else 0.0
    knee_asymmetry = abs(float(angles.get("left_knee", 180.0)) - float(angles.get("right_knee", 180.0)))

    knee_angle = _min_visible_angle(
        landmarks, angles, "left_knee", "right_knee",
        (IDX["left_hip"], IDX["left_knee"], IDX["left_ankle"]),
        (IDX["right_hip"], IDX["right_knee"], IDX["right_ankle"]),
    )
    hip_angle = _min_visible_angle(
        landmarks, angles, "left_hip", "right_hip",
        (IDX["left_shoulder"], IDX["left_hip"], IDX["left_knee"]),
        (IDX["right_shoulder"], IDX["right_hip"], IDX["right_knee"]),
    )
    elbow_angle = _min_visible_angle(
        landmarks, angles, "left_elbow", "right_elbow",
        (IDX["left_shoulder"], IDX["left_elbow"], IDX["left_wrist"]),
        (IDX["right_shoulder"], IDX["right_elbow"], IDX["right_wrist"]),
    )

    knee_bend = max(0.0, 180.0 - knee_angle)
    hip_bend = max(0.0, 180.0 - hip_angle)
    elbow_bend = max(0.0, 180.0 - elbow_angle)
    trunk_lean = _segment_angle_from_vertical(shoulder_mid, hip_mid)

    standing_support = lower_count >= 4 and ankle_mid[1] > hip_mid[1] + 0.08
    wrist_support = arm_count >= 4 and wrist_mid[1] > shoulder_mid[1] + 0.12
    floor_support = wrist_support and elbow_bend > 15.0 and (lower_count < 4 or hip_mid[1] > 0.80)
    lunge_signal = (
        lower_count >= 4
        and ankle_separation > max(0.04, shoulder_width * 0.45)
        and knee_height_diff > 0.055
    )

    scores = {name: 0.0 for name in EXERCISE_TEMPLATES}

    if floor_support:
        scores["push_up"] += 0.55
    if wrist_support and elbow_bend > 20.0:
        scores["push_up"] += 0.25
    if wrist_support and lower_count < 4:
        scores["push_up"] += 0.20
    if elbow_bend > 30.0 and knee_bend < 20.0 and hip_bend < 25.0 and ankle_mid[1] < 0.92:
        scores["push_up"] += 0.60

    if standing_support:
        scores["squat"] += 0.25
        scores["deadlift"] += 0.20
        scores["lunge"] += 0.20
    if standing_support and not lunge_signal and trunk_lean <= 20.0:
        scores["squat"] += 0.25
    if knee_bend > 15.0:
        scores["squat"] += 0.35
        scores["lunge"] += 0.25
    if hip_bend > 15.0:
        scores["squat"] += 0.15
        scores["deadlift"] += 0.30
    if trunk_lean <= 35.0 and knee_bend > 20.0:
        scores["squat"] += 0.20
    if trunk_lean > 25.0 and hip_bend > 10.0:
        scores["deadlift"] += 0.35
    if hip_bend > knee_bend + 10.0:
        scores["deadlift"] += 0.20
    if lunge_signal:
        scores["lunge"] += 0.45
        scores["squat"] = max(0.0, scores["squat"] - 0.10)
    if knee_asymmetry > 25.0 and lower_count >= 4:
        scores["lunge"] += 0.15

    scores = {k: min(1.0, round(v, 3)) for k, v in scores.items()}
    best = max(scores, key=scores.get)
    best_score = scores[best]
    sorted_scores = sorted(scores.values(), reverse=True)

    basis = []
    if floor_support:
        basis.append("floor_support")
    if standing_support:
        basis.append("standing_support")
    if knee_bend > 15.0:
        basis.append("knee_flexion")
    if hip_bend > 15.0:
        basis.append("hip_hinge")
    if elbow_bend > 20.0:
        basis.append("elbow_flexion")
    if lunge_signal:
        basis.append("unilateral_lunge_signal")
    if trunk_lean > 25.0:
        basis.append("trunk_lean")

    if best_score < 0.45:
        return {
            "exercise": "unknown_or_mixed",
            "exercise_confidence": best_score,
            "candidate_scores": scores,
            "basis": ["low_confidence_detection"],
        }
    if len(sorted_scores) >= 2 and sorted_scores[0] - sorted_scores[1] < 0.08:
        return {
            "exercise": "unknown_or_mixed",
            "exercise_confidence": best_score,
            "candidate_scores": scores,
            "basis": ["ambiguous_template_scores"],
        }

    return {
        "exercise": best,
        "exercise_confidence": best_score,
        "candidate_scores": scores,
        "basis": basis,
    }
