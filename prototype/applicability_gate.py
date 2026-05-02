"""
applicability_gate.py - context-aware quality gates for GemmaFit.

This replaces the old "run all eight rules on every frame" behavior with a
template-aware gate. The output is still structured and rule-like, but each
metric can be OK, MONITOR, WARNING, CRITICAL, NOT_APPLICABLE, LOW_CONFIDENCE,
or VIEW_LIMITED.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

import numpy as np


@dataclass
class QualityFlag:
    id: str
    status: str
    value: float = 0.0
    threshold: float = 0.0
    evidence: str = ""
    reason: str = ""


@dataclass
class ApplicabilityResult:
    quality_flags: List[QualityFlag] = field(default_factory=list)
    not_applicable: List[QualityFlag] = field(default_factory=list)
    low_confidence: List[QualityFlag] = field(default_factory=list)
    overall_status: str = "OK"
    notes: List[str] = field(default_factory=list)


KEY_INDICES = {
    "squat": [11, 12, 23, 24, 25, 26, 27, 28],
    "push_up": [11, 12, 13, 14, 15, 16, 23, 24],
    "lunge": [11, 12, 23, 24, 25, 26, 27, 28],
    "deadlift": [11, 12, 23, 24, 25, 26, 27, 28],
    "unknown_or_mixed": [11, 12, 23, 24],
}


class ApplicabilityGate:
    """Evaluate only the quality gates that make sense for an exercise."""

    def __init__(self, exercise: str, visibility_threshold: float = 0.5):
        self.exercise = exercise
        self.visibility_threshold = visibility_threshold

    def _is_visible(self, landmarks: np.ndarray, idx: int) -> bool:
        if landmarks.shape[0] <= idx or landmarks.shape[1] < 3:
            return False
        x = float(landmarks[idx, 0])
        y = float(landmarks[idx, 1])
        vis = float(landmarks[idx, 2])
        return vis >= self.visibility_threshold and not (abs(x) < 1e-9 and abs(y) < 1e-9)

    def check_landmark_confidence(self, landmarks: np.ndarray) -> Tuple[bool, float]:
        """Check exercise-specific key landmark visibility."""
        if landmarks.shape[0] < 33 or landmarks.shape[1] < 3:
            return False, 0.0

        indices = KEY_INDICES.get(self.exercise, KEY_INDICES["unknown_or_mixed"])
        visibilities = [float(landmarks[i, 2]) for i in indices if i < landmarks.shape[0]]
        if not visibilities:
            return False, 0.0

        avg_visibility = float(np.mean(visibilities))
        key_visible = all(self._is_visible(landmarks, i) for i in indices if i < landmarks.shape[0])
        return key_visible and avg_visibility >= self.visibility_threshold, avg_visibility

    def evaluate(
        self,
        metrics: Dict[str, float],
        landmarks: np.ndarray,
        angles: Dict[str, float],
        angular_velocities: Optional[Dict[str, float]] = None,
    ) -> ApplicabilityResult:
        result = ApplicabilityResult()
        result.notes.extend(["not_medical_diagnosis", "single_camera_pose_based_feedback"])

        if self.exercise not in ("squat", "push_up", "lunge", "deadlift"):
            result.quality_flags.append(QualityFlag(
                id="exercise_template",
                status="VIEW_LIMITED",
                evidence="heuristic_exercise_detection",
                reason="exercise_not_identified_with_enough_confidence",
            ))
            result.overall_status = "VIEW_LIMITED"
            return result

        is_confident, avg_vis = self.check_landmark_confidence(landmarks)
        if not is_confident:
            result.low_confidence.append(QualityFlag(
                id="visibility",
                status="LOW_CONFIDENCE",
                value=avg_vis,
                threshold=self.visibility_threshold,
                evidence="pose_based_confidence",
                reason=f"Exercise keypoint visibility {avg_vis:.2f} below threshold",
            ))
            result.overall_status = "LOW_CONFIDENCE"
            return result

        self._check_knee_valgus(metrics, result)
        self._check_trunk_angle(metrics, result)
        self._check_body_line(metrics, result)
        self._check_joint_overextension(angles, result)
        self._check_asymmetry(angles, result)
        self._check_com_offset(metrics, result)
        self._check_rapid_movement(angular_velocities, result)
        self._check_rom(metrics, angles, result)
        self._check_neck_position(angles, result)
        self._set_overall_status(result)
        return result

    def _not_applicable(self, result: ApplicabilityResult, gate_id: str, reason: str) -> None:
        result.not_applicable.append(QualityFlag(
            id=gate_id,
            status="NOT_APPLICABLE",
            evidence="template_based_applicability",
            reason=reason,
        ))

    def _set_overall_status(self, result: ApplicabilityResult) -> None:
        statuses = [f.status for f in result.quality_flags]
        for status in ("CRITICAL", "WARNING", "MONITOR", "VIEW_LIMITED"):
            if status in statuses:
                result.overall_status = status
                return
        result.overall_status = "OK"

    def _check_knee_valgus(self, metrics: Dict[str, float], result: ApplicabilityResult) -> None:
        """Rule 1: FPPA/knee-valgus proxy, only frontal lower-body view."""
        if self.exercise != "squat":
            self._not_applicable(result, "knee_valgus_fppa", f"{self.exercise}_template_not_frontal_squat")
            return

        ratio = float(metrics.get("knee_valgus_ratio", 1.0))
        if ratio < 0.8:
            result.quality_flags.append(QualityFlag(
                id="knee_valgus",
                status="CRITICAL" if ratio < 0.65 else "WARNING",
                value=ratio,
                threshold=0.8,
                evidence="pose_based_frontal_view",
            ))

    def _check_trunk_angle(self, metrics: Dict[str, float], result: ApplicabilityResult) -> None:
        if self.exercise == "squat":
            value = float(metrics.get("trunk_lean", 0.0))
            if value > 20.0:
                result.quality_flags.append(QualityFlag(
                    id="spine_flexion",
                    status="CRITICAL" if value > 35.0 else "WARNING",
                    value=value,
                    threshold=20.0,
                    evidence="pose_based_template_metric",
                ))
        elif self.exercise == "deadlift":
            value = float(metrics.get("trunk_angle", metrics.get("trunk_lean", 0.0)))
            if value > 55.0:
                result.quality_flags.append(QualityFlag(
                    id="trunk_angle",
                    status="CRITICAL" if value > 70.0 else "WARNING",
                    value=value,
                    threshold=55.0,
                    evidence="pose_based_template_metric",
                ))
        elif self.exercise == "lunge":
            value = float(metrics.get("trunk_uprightness", 0.0))
            if value > 25.0:
                result.quality_flags.append(QualityFlag(
                    id="trunk_uprightness",
                    status="WARNING" if value > 35.0 else "MONITOR",
                    value=value,
                    threshold=25.0,
                    evidence="pose_based_template_metric",
                ))
        elif self.exercise == "push_up":
            self._not_applicable(result, "trunk_angle", "push_up_uses_body_line_metric")

    def _check_body_line(self, metrics: Dict[str, float], result: ApplicabilityResult) -> None:
        if self.exercise != "push_up":
            return

        if float(metrics.get("body_line_view_limited", 0.0)) >= 0.5:
            result.quality_flags.append(QualityFlag(
                id="body_line",
                status="VIEW_LIMITED",
                evidence="template_metric_visibility",
                reason="lower_body_reference_not_visible_for_body_line",
            ))
            return

        body_line = float(metrics.get("body_line_deviation", 0.0))
        hip_sag = float(metrics.get("hip_sag", 0.0))
        if body_line > 15.0:
            result.quality_flags.append(QualityFlag(
                id="body_line",
                status="WARNING" if body_line > 25.0 else "MONITOR",
                value=body_line,
                threshold=15.0,
                evidence="pose_based_template_metric",
            ))
        if hip_sag > 8.0:
            result.quality_flags.append(QualityFlag(
                id="hip_sag",
                status="WARNING" if hip_sag > 14.0 else "MONITOR",
                value=hip_sag,
                threshold=8.0,
                evidence="pose_based_template_metric",
            ))

    def _check_joint_overextension(self, angles: Dict[str, float], result: ApplicabilityResult) -> None:
        joints_by_exercise = {
            "squat": ["left_knee", "right_knee", "left_hip", "right_hip"],
            "push_up": ["left_elbow", "right_elbow"],
            "lunge": ["left_knee", "right_knee"],
            "deadlift": ["left_hip", "right_hip", "left_knee", "right_knee"],
        }
        for joint in joints_by_exercise.get(self.exercise, []):
            angle = float(angles.get(joint, 90.0))
            if angle <= 2.0 or angle >= 178.0:
                result.quality_flags.append(QualityFlag(
                    id=f"joint_overextension_{joint}",
                    status="MONITOR",
                    value=angle,
                    threshold=178.0 if angle >= 178.0 else 2.0,
                    evidence="pose_based_conservative_monitor",
                ))
                return

    def _check_asymmetry(self, angles: Dict[str, float], result: ApplicabilityResult) -> None:
        if self.exercise == "lunge":
            self._not_applicable(result, "bilateral_asymmetry", "lunge_is_intentionally_unilateral")
            return
        if self.exercise == "push_up":
            pairs = [("left_elbow", "right_elbow"), ("left_shoulder", "right_shoulder")]
        elif self.exercise in ("squat", "deadlift"):
            pairs = [("left_knee", "right_knee"), ("left_hip", "right_hip")]
        else:
            return

        max_diff = 0.0
        max_joint = ""
        for left, right in pairs:
            if left in angles and right in angles:
                diff = abs(float(angles[left]) - float(angles[right]))
                if diff > max_diff:
                    max_diff = diff
                    max_joint = left.replace("left_", "")

        if max_diff > 18.0:
            result.quality_flags.append(QualityFlag(
                id="bilateral_asymmetry",
                status="WARNING" if max_diff > 30.0 else "MONITOR",
                value=max_diff,
                threshold=18.0,
                evidence="pose_based_single_frame",
                reason=f"{max_joint}_asymmetry",
            ))

    def _check_com_offset(self, metrics: Dict[str, float], result: ApplicabilityResult) -> None:
        if self.exercise == "push_up":
            self._not_applicable(result, "com_offset", "push_up_floor_support_not_bos_metric")
            return
        offset = float(metrics.get("com_offset_ratio", 0.0))
        if offset > 1.0:
            result.quality_flags.append(QualityFlag(
                id="com_offset",
                status="MONITOR",
                value=offset,
                threshold=1.0,
                evidence="pose_based_com_estimate",
                reason="dynamic_motion_monitor_only",
            ))

    def _check_rapid_movement(
        self,
        angular_velocities: Optional[Dict[str, float]],
        result: ApplicabilityResult,
    ) -> None:
        if not angular_velocities:
            return
        for joint, velocity in angular_velocities.items():
            velocity = abs(float(velocity))
            if velocity > 600.0:
                result.quality_flags.append(QualityFlag(
                    id="rapid_movement",
                    status="WARNING" if velocity > 900.0 else "MONITOR",
                    value=velocity,
                    threshold=600.0,
                    evidence="smoothed_angular_velocity",
                    reason=f"{joint}_velocity",
                ))
                return

    def _check_rom(self, metrics: Dict[str, float], angles: Dict[str, float], result: ApplicabilityResult) -> None:
        checks = {
            "squat": [("knee", metrics.get("knee_angle", angles.get("left_knee", 180.0)), 90.0, 160.0)],
            "push_up": [("elbow", metrics.get("elbow_angle", angles.get("left_elbow", 180.0)), 45.0, 170.0)],
            "lunge": [("front_knee", metrics.get("front_knee_angle", 180.0), 80.0, 160.0)],
            "deadlift": [("hip", metrics.get("hip_hinge", angles.get("left_hip", 180.0)), 45.0, 170.0)],
        }
        for joint, actual, target_min, target_max in checks.get(self.exercise, []):
            actual = float(actual)
            expected_range = target_max - target_min
            if actual > target_max + 15.0 or actual < target_min:
                continue
            rom_used = target_max - actual
            if rom_used < expected_range * 0.05:
                continue
            threshold = expected_range * 0.15
            if rom_used < threshold:
                result.quality_flags.append(QualityFlag(
                    id=f"rom_{joint}",
                    status="MONITOR",
                    value=rom_used,
                    threshold=threshold,
                    evidence="pose_based_template_metric",
                ))

    def _check_neck_position(self, angles: Dict[str, float], result: ApplicabilityResult) -> None:
        if self.exercise not in ("squat", "deadlift"):
            return
        neck_deviation = abs(180.0 - float(angles.get("neck", 180.0)))
        if neck_deviation > 18.0:
            result.quality_flags.append(QualityFlag(
                id="neck_position",
                status="WARNING" if neck_deviation > 30.0 else "MONITOR",
                value=neck_deviation,
                threshold=18.0,
                evidence="pose_based_low_confidence_downgraded",
            ))


def generate_structured_report(
    frame: int,
    exercise: str,
    exercise_confidence: float,
    phase: str,
    rep: int,
    metrics: Dict[str, float],
    gate_result: ApplicabilityResult,
) -> Dict:
    """Generate the unified structured motion report JSON."""
    return {
        "frame": frame,
        "exercise": exercise,
        "exercise_confidence": round(float(exercise_confidence), 2),
        "phase": phase,
        "rep": rep,
        "metrics": {k: round(float(v), 2) for k, v in metrics.items() if v is not None},
        "quality_flags": [
            {
                "id": f.id,
                "status": f.status,
                "value": round(float(f.value), 2),
                "threshold": round(float(f.threshold), 2) if f.threshold else None,
                "evidence": f.evidence,
                "reason": f.reason,
            }
            for f in gate_result.quality_flags
        ],
        "not_applicable": [
            {"id": f.id, "status": f.status, "reason": f.reason}
            for f in gate_result.not_applicable
        ],
        "low_confidence": [
            {
                "id": f.id,
                "status": f.status,
                "value": round(float(f.value), 2) if f.value else None,
                "reason": f.reason,
            }
            for f in gate_result.low_confidence
        ],
        "overall_status": gate_result.overall_status,
        "notes": gate_result.notes,
    }
