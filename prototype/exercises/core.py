"""exercises/core.py — Exercise templates, heuristic detector, applicability gates,
structured motion report, and mock Gemma feedback.

Pipeline:
  lm_arr (33×3 numpy)
    → detect_exercise()          → ExerciseDetectionResult
    → template = TEMPLATES[exercise]
    → raw violations (from analyze_frame)
    → apply_gates()              → (List[QualityFlag], List[NotApplicableNote])
    → extract_template_metrics() → Dict[str, float]
    → build_report()             → StructuredMotionReport
    → mock_gemma_feedback()      → dict
"""

import math
import random
from dataclasses import dataclass, field, asdict
from typing import Dict, List, Optional, Tuple

import numpy as np

# ── Status codes ─────────────────────────────────────────────────────────────
STATUS_OK             = "OK"
STATUS_MONITOR        = "MONITOR"
STATUS_WARNING        = "WARNING"
STATUS_CRITICAL       = "CRITICAL"
STATUS_NOT_APPLICABLE = "NOT_APPLICABLE"
STATUS_LOW_CONFIDENCE = "LOW_CONFIDENCE"
STATUS_VIEW_LIMITED   = "VIEW_LIMITED"

# ── MediaPipe landmark indices (PoseLandmark constants) ───────────────────────
_KP = {
    "nose": 0,
    "left_ear": 7, "right_ear": 8,
    "left_shoulder": 11, "right_shoulder": 12,
    "left_elbow": 13, "right_elbow": 14,
    "left_wrist": 15, "right_wrist": 16,
    "left_hip": 23, "right_hip": 24,
    "left_knee": 25, "right_knee": 26,
    "left_ankle": 27, "right_ankle": 28,
    "left_heel": 29, "right_heel": 30,
    "left_foot_index": 31, "right_foot_index": 32,
}


# ── Exercise Templates ────────────────────────────────────────────────────────

@dataclass
class ExerciseTemplate:
    name: str
    display: str
    metrics: List[str]
    disabled_rules: List[int]       # rules that are always NOT_APPLICABLE
    soft_rules: Dict[int, str]      # rule → downgraded status (e.g. CRITICAL→MONITOR)
    target_joints: List[str]
    description: str = ""


TEMPLATES: Dict[str, ExerciseTemplate] = {
    "squat": ExerciseTemplate(
        name="squat",
        display="Squat",
        metrics=["left_knee_angle", "right_knee_angle", "left_hip_angle",
                 "right_hip_angle", "trunk_lean_deg", "tempo_dps"],
        disabled_rules=[],
        soft_rules={5: STATUS_MONITOR},   # COM during dynamic squat → MONITOR not CRITICAL
        target_joints=["left_knee", "right_knee", "left_hip", "right_hip", "spine"],
        description="Bilateral knee-dominant sagittal movement",
    ),
    "push_up": ExerciseTemplate(
        name="push_up",
        display="Push-up",
        metrics=["left_elbow_angle", "right_elbow_angle", "body_line_deviation_deg",
                 "hip_sag_pct", "tempo_dps"],
        disabled_rules=[1, 7],  # knee valgus FPPA + lower-body ROM → NOT_APPLICABLE
        soft_rules={
            4: STATUS_MONITOR,        # bilateral asymmetry in prone → MONITOR
            5: STATUS_NOT_APPLICABLE, # COM/BoS not applicable prone
        },
        target_joints=["left_elbow", "right_elbow", "left_shoulder", "right_shoulder", "spine"],
        description="Prone hands shoulder-dominant sagittal movement",
    ),
    "lunge": ExerciseTemplate(
        name="lunge",
        display="Lunge",
        metrics=["front_knee_angle", "back_knee_angle", "knee_asymmetry_deg",
                 "trunk_lean_deg", "tempo_dps"],
        disabled_rules=[],
        soft_rules={
            4: STATUS_MONITOR,  # left-right asymmetry is expected in lunge
            5: STATUS_MONITOR,  # COM shift during lunge → MONITOR
        },
        target_joints=["left_knee", "right_knee", "left_hip", "right_hip", "spine"],
        description="Unipedal knee-hip dominant sagittal movement",
    ),
    "deadlift": ExerciseTemplate(
        name="deadlift",
        display="Deadlift (P1 Bonus)",
        metrics=["hip_hinge_angle_deg", "trunk_angle_deg", "knee_angle_deg", "tempo_dps"],
        disabled_rules=[1],   # FPPA less relevant for conventional deadlift sagittal pattern
        soft_rules={5: STATUS_MONITOR},
        target_joints=["left_hip", "right_hip", "spine", "left_knee", "right_knee"],
        description="Hip-dominant sagittal movement with hip hinge",
    ),
    "unknown": ExerciseTemplate(
        name="unknown",
        display="Unknown / Mixed",
        metrics=[],
        disabled_rules=[1, 4, 7],  # suppress exercise-specific gates for unknown
        soft_rules={
            2: STATUS_MONITOR, 3: STATUS_MONITOR, 5: STATUS_MONITOR,
            6: STATUS_MONITOR, 8: STATUS_MONITOR,
        },
        target_joints=[],
        description="Exercise could not be determined with sufficient confidence",
    ),
}


# ── Heuristic Exercise Detector ───────────────────────────────────────────────

@dataclass
class ExerciseDetectionResult:
    exercise: str
    exercise_confidence: float
    candidate_scores: Dict[str, float]
    basis: List[str]
    status: str = STATUS_OK


def _xy(lm_arr: np.ndarray, name: str) -> np.ndarray:
    return lm_arr[_KP[name], :2]


def _vis(lm_arr: np.ndarray, name: str) -> float:
    return float(lm_arr[_KP[name], 2]) if lm_arr.shape[1] > 2 else 0.9


def _angle3(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    v1, v2 = a - b, c - b
    cos = np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9)
    return math.degrees(math.acos(float(np.clip(cos, -1.0, 1.0))))


def _mean_vis(lm_arr: np.ndarray, names: List[str]) -> float:
    return float(np.mean([_vis(lm_arr, name) for name in names]))


def _extract_features(lm_arr: np.ndarray) -> Dict[str, float]:
    f = {}

    l_sho = _xy(lm_arr, "left_shoulder");  r_sho = _xy(lm_arr, "right_shoulder")
    l_hip = _xy(lm_arr, "left_hip");       r_hip = _xy(lm_arr, "right_hip")
    l_kne = _xy(lm_arr, "left_knee");      r_kne = _xy(lm_arr, "right_knee")
    l_ank = _xy(lm_arr, "left_ankle");     r_ank = _xy(lm_arr, "right_ankle")
    l_wri = _xy(lm_arr, "left_wrist");     r_wri = _xy(lm_arr, "right_wrist")
    l_elb = _xy(lm_arr, "left_elbow");     r_elb = _xy(lm_arr, "right_elbow")

    sho_mid = (l_sho + r_sho) / 2
    hip_mid = (l_hip + r_hip) / 2
    wri_mid = (l_wri + r_wri) / 2
    ank_mid = (l_ank + r_ank) / 2

    # ── Body orientation in image space (y increases downward) ──────────────
    # trunk_dy: vertical separation of shoulder vs hip (large → upright)
    trunk_dy = abs(hip_mid[1] - sho_mid[1])
    f["trunk_vertical"]   = float(np.clip(trunk_dy / 0.22, 0.0, 1.0))
    f["trunk_horizontal"] = float(np.clip(1.0 - trunk_dy / 0.12, 0.0, 1.0))

    # ── Knee angles ──────────────────────────────────────────────────────────
    l_kne_ang = _angle3(l_hip, l_kne, l_ank)
    r_kne_ang = _angle3(r_hip, r_kne, r_ank)
    f["l_knee_angle"] = l_kne_ang
    f["r_knee_angle"] = r_kne_ang
    avg_knee = (l_kne_ang + r_kne_ang) / 2.0
    f["bilateral_knee_flexion"] = float(np.clip((180.0 - avg_knee) / 80.0, 0.0, 1.0))
    f["knee_asymmetry"]  = float(np.clip(abs(l_kne_ang - r_kne_ang) / 50.0, 0.0, 1.0))
    f["knee_symmetry"]   = 1.0 - f["knee_asymmetry"]
    f["front_knee_bent"] = float(np.clip((180.0 - min(l_kne_ang, r_kne_ang)) / 60.0, 0.0, 1.0))

    # ── Elbow angles ─────────────────────────────────────────────────────────
    l_elb_ang = _angle3(l_sho, l_elb, l_wri)
    r_elb_ang = _angle3(r_sho, r_elb, r_wri)
    f["bilateral_elbow_flexion"] = float(np.clip((180.0 - (l_elb_ang + r_elb_ang) / 2.0) / 80.0, 0.0, 1.0))

    # ── Hip angles ───────────────────────────────────────────────────────────
    l_hip_ang = _angle3(l_sho, l_hip, l_kne)
    r_hip_ang = _angle3(r_sho, r_hip, r_kne)
    f["bilateral_hip_flexion"] = float(np.clip((180.0 - (l_hip_ang + r_hip_ang) / 2.0) / 80.0, 0.0, 1.0))

    # ── Wrist position ───────────────────────────────────────────────────────
    # wrists below hip: positive → wrists hang below hips (deadlift)
    wri_below_hip = float(wri_mid[1] - hip_mid[1])  # +ve in image = lower
    f["wrists_below_hip"]       = float(np.clip(wri_below_hip / 0.12, 0.0, 1.0))
    f["wrists_above_hip"]       = float(np.clip(-wri_below_hip / 0.08, 0.0, 1.0))
    # wrists near shoulder height (push-up hand placement)
    wri_vs_sho = abs(float(wri_mid[1] - sho_mid[1]))
    f["wrists_near_shoulder"]   = float(np.clip(1.0 - wri_vs_sho / 0.18, 0.0, 1.0))
    # wrists near ankle/floor level (push-up floor support)
    wri_vs_ank = abs(float(wri_mid[1] - ank_mid[1]))
    f["wrists_near_floor"]      = float(np.clip(1.0 - wri_vs_ank / 0.25, 0.0, 1.0))
    # Upper-body-only support proxy for push-up views where lower body is cropped.
    wri_below_sho = float(wri_mid[1] - sho_mid[1])
    wri_sho_dist = float(np.linalg.norm(wri_mid - sho_mid))
    f["wrists_below_shoulders"] = float(np.clip(wri_below_sho / 0.22, 0.0, 1.0))
    f["wrist_shoulder_separation"] = float(np.clip(wri_sho_dist / 0.28, 0.0, 1.0))
    f["upper_body_hand_support"] = float(max(
        f["wrists_below_shoulders"],
        f["wrist_shoulder_separation"],
    ))

    # ── Bipedal feet separation (proxy for standing vs prone) ───────────────
    ankle_sep = float(np.linalg.norm(l_ank - r_ank))
    f["bipedal_stance"] = float(np.clip(ankle_sep / 0.25, 0.0, 1.0))

    # ── Region visibility gates ─────────────────────────────────────────────
    upper_body = ["left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
                  "left_wrist", "right_wrist"]
    torso = ["left_shoulder", "right_shoulder", "left_hip", "right_hip"]
    lower_body = ["left_hip", "right_hip", "left_knee", "right_knee",
                  "left_ankle", "right_ankle"]
    f["upper_body_visibility"] = _mean_vis(lm_arr, upper_body)
    f["torso_visibility"] = _mean_vis(lm_arr, torso)
    f["lower_body_visibility"] = _mean_vis(lm_arr, lower_body)
    f["upper_template_visibility"] = min(
        f["upper_body_visibility"],
        f["torso_visibility"],
    )
    f["lower_template_visibility"] = min(
        f["lower_body_visibility"],
        f["torso_visibility"],
    )
    f["visibility"] = float(np.mean([
        f["upper_body_visibility"],
        f["torso_visibility"],
        f["lower_body_visibility"],
    ]))

    return f


def detect_exercise(
    lm_arr: np.ndarray,
    angle_history: Optional[list] = None,
) -> ExerciseDetectionResult:
    """Heuristic exercise scorer. Returns best-match exercise + confidence."""
    f = _extract_features(lm_arr)
    scores: Dict[str, float] = {}
    bases:  Dict[str, List[str]] = {}

    # ── Squat: upright + bilateral knee flexion + symmetric ─────────────────
    sq = 0.0; sq_b: List[str] = []
    sq += 0.35 * f["trunk_vertical"]
    if f["trunk_vertical"] > 0.6: sq_b.append("upright_trunk")
    sq += 0.30 * f["bilateral_knee_flexion"]
    if f["bilateral_knee_flexion"] > 0.15: sq_b.append("bilateral_knee_flexion")
    sq += 0.20 * f["knee_symmetry"]
    if f["knee_symmetry"] > 0.65: sq_b.append("symmetric_knees")
    sq += 0.10 * f["wrists_above_hip"]
    sq += 0.05 * f["bipedal_stance"]
    sq *= f["lower_template_visibility"]
    if f["lower_template_visibility"] >= 0.5: sq_b.append("lower_body_visible")
    scores["squat"] = float(np.clip(sq, 0, 1))
    bases["squat"] = sq_b

    # ── Push-up: upper-body support + elbow flexion, independent of ankles ──
    pu = 0.0; pu_b: List[str] = []
    support_with_arm_evidence = f["upper_body_hand_support"] * (
        0.35 + 0.65 * f["bilateral_elbow_flexion"]
    )
    pu += 0.35 * f["bilateral_elbow_flexion"]
    if f["bilateral_elbow_flexion"] > 0.2: pu_b.append("elbow_flexion")
    pu += 0.45 * support_with_arm_evidence
    if support_with_arm_evidence > 0.4: pu_b.append("upper_body_hand_support")
    pu += 0.10 * f["trunk_horizontal"]
    if f["trunk_horizontal"] > 0.5: pu_b.append("horizontal_body")
    pu += 0.10 * f["upper_template_visibility"]
    if f["upper_template_visibility"] >= 0.5: pu_b.append("upper_body_visible")
    scores["push_up"] = float(np.clip(pu, 0, 1))
    bases["push_up"] = pu_b

    # ── Lunge: upright + strong knee asymmetry + front knee bent ────────────
    lu = 0.0; lu_b: List[str] = []
    lu += 0.25 * f["trunk_vertical"]
    if f["trunk_vertical"] > 0.5: lu_b.append("upright_trunk")
    lu += 0.40 * f["knee_asymmetry"]
    if f["knee_asymmetry"] > 0.3: lu_b.append("asymmetric_knees")
    lu += 0.25 * f["front_knee_bent"]
    if f["front_knee_bent"] > 0.3: lu_b.append("front_knee_bent")
    lu += 0.10 * f["bipedal_stance"]
    # Penalize if knees are too symmetric (that's squat, not lunge)
    lu -= 0.15 * f["knee_symmetry"]
    lu *= f["lower_template_visibility"]
    if f["lower_template_visibility"] >= 0.5: lu_b.append("lower_body_visible")
    scores["lunge"] = float(np.clip(lu, 0, 1))
    bases["lunge"] = lu_b

    # ── Deadlift: wrists hang below hip + hip flexion + moderate trunk lean ─
    dl = 0.0; dl_b: List[str] = []
    dl += 0.40 * f["wrists_below_hip"]
    if f["wrists_below_hip"] > 0.2: dl_b.append("wrists_below_hip")
    dl += 0.30 * f["bilateral_hip_flexion"]
    if f["bilateral_hip_flexion"] > 0.2: dl_b.append("hip_dominant_flexion")
    # Trunk: forward lean (not full horizontal, not fully upright)
    trunk_mid = abs(f["trunk_vertical"] - 0.55)
    dl += 0.20 * float(np.clip(1.0 - trunk_mid / 0.4, 0, 1))
    if 0.3 < f["trunk_vertical"] < 0.85: dl_b.append("forward_trunk_lean")
    # Penalize knee-dominant flexion (that's squat)
    dl -= 0.10 * f["bilateral_knee_flexion"]
    dl *= f["lower_template_visibility"]
    if f["lower_template_visibility"] >= 0.5: dl_b.append("lower_body_visible")
    scores["deadlift"] = float(np.clip(dl, 0, 1))
    bases["deadlift"] = dl_b

    # ── Pick winner ──────────────────────────────────────────────────────────
    best = max(scores, key=scores.get)
    best_score = scores[best]
    sorted_vals = sorted(scores.values(), reverse=True)
    margin = sorted_vals[0] - sorted_vals[1] if len(sorted_vals) > 1 else sorted_vals[0]

    if best_score < 0.25 or margin < 0.07:
        best = "unknown"
        best_score = 0.0
        status = STATUS_VIEW_LIMITED
        basis = ["insufficient_detection_confidence"]
    else:
        required_visibility = (
            f["upper_template_visibility"]
            if best == "push_up"
            else f["lower_template_visibility"]
        )
        status = STATUS_OK if required_visibility >= 0.5 else STATUS_VIEW_LIMITED
        basis = bases.get(best, [])
        if required_visibility < 0.5:
            basis.append("low_required_landmark_visibility")

    return ExerciseDetectionResult(
        exercise=best,
        exercise_confidence=round(best_score, 3),
        candidate_scores={k: round(v, 3) for k, v in scores.items()},
        basis=basis,
        status=status,
    )


# ── Temporal smoothing for exercise label (Algorithm #3) ─────────────────────
#
# Single-frame `detect_exercise` flickers when the user is at the static
# top of a squat (trunk vertical, knees ≈170°): squat / deadlift scores
# get very close and the winner can swap each frame. SmoothedExerciseDetector
# wraps the per-frame call with:
#   - a sliding majority vote over the last `window_frames` frames
#   - hysteresis: requires `switch_min_streak` consecutive winning votes
#     against the current label before switching
#
# Behavior parity: when smoothing decides "no switch yet", the returned
# result keeps the previously-stable label but reports the raw winner's
# score under `candidate_scores` for diagnostics. status, basis, and
# candidate_scores still reflect the latest underlying frame.

from collections import Counter, deque


class SmoothedExerciseDetector:
    """Stateful wrapper around `detect_exercise` that suppresses single-frame flicker."""

    def __init__(
        self,
        window_frames: int = 9,
        switch_min_streak: int = 4,
        unknown_passthrough: bool = True,
    ):
        if window_frames < 1:
            raise ValueError("window_frames must be >= 1")
        if switch_min_streak < 1:
            raise ValueError("switch_min_streak must be >= 1")
        self.window_frames = window_frames
        self.switch_min_streak = switch_min_streak
        self.unknown_passthrough = unknown_passthrough
        self._votes: deque = deque(maxlen=window_frames)
        self._stable_label: Optional[str] = None
        self._switch_streak = 0
        self._raw_label_history: List[str] = []   # diagnostics
        self.flips_raw = 0
        self.flips_smoothed = 0

    def reset(self) -> None:
        self._votes.clear()
        self._stable_label = None
        self._switch_streak = 0
        self._raw_label_history.clear()
        self.flips_raw = 0
        self.flips_smoothed = 0

    def update(
        self,
        lm_arr: np.ndarray,
        angle_history: Optional[list] = None,
    ) -> "ExerciseDetectionResult":
        raw = detect_exercise(lm_arr, angle_history=angle_history)
        self._votes.append(raw.exercise)

        # Diagnostics: count raw flips vs smoothed flips.
        if self._raw_label_history and raw.exercise != self._raw_label_history[-1]:
            self.flips_raw += 1
        self._raw_label_history.append(raw.exercise)

        # Unknown bypasses smoothing — we want VIEW_LIMITED to surface immediately.
        if self.unknown_passthrough and raw.exercise == "unknown":
            new_label = "unknown"
        else:
            counter = Counter(self._votes)
            top_label, _ = counter.most_common(1)[0]
            if self._stable_label is None:
                # First non-unknown frame seeds the stable label.
                new_label = raw.exercise
            elif top_label == self._stable_label:
                self._switch_streak = 0
                new_label = self._stable_label
            else:
                self._switch_streak += 1
                if self._switch_streak >= self.switch_min_streak:
                    new_label = top_label
                else:
                    new_label = self._stable_label

        if new_label != self._stable_label and self._stable_label is not None:
            self.flips_smoothed += 1
        self._stable_label = new_label

        # Build the same dataclass shape, but with the smoothed label.
        # Diagnostics live on the detector instance, not the result, to
        # keep ExerciseDetectionResult's downstream consumers unchanged.
        smoothed_basis = list(raw.basis)
        if new_label != raw.exercise and raw.exercise != "unknown":
            smoothed_basis.append(f"smoothed_held_against_raw={raw.exercise}")
        return ExerciseDetectionResult(
            exercise=new_label,
            exercise_confidence=raw.exercise_confidence,
            candidate_scores=raw.candidate_scores,
            basis=smoothed_basis,
            status=raw.status,
        )


# ── Quality Flags and NOT_APPLICABLE ─────────────────────────────────────────

@dataclass
class QualityFlag:
    id: str
    status: str
    rule: int
    joint: str
    value: float
    threshold: float
    evidence: str = "pose_based_template_metric"
    reason: str = ""


@dataclass
class NotApplicableNote:
    id: str
    rule: int
    status: str
    reason: str


def apply_gates(
    violations: list,
    template: ExerciseTemplate,
    lm_arr: np.ndarray,
    angles: dict,
    exercise_confidence: float,
) -> Tuple[List[QualityFlag], List[NotApplicableNote]]:
    """Apply exercise template gates to raw safety violations.

    Returns (quality_flags, not_applicable_notes).
    Rules in template.disabled_rules → NOT_APPLICABLE.
    Rules in template.soft_rules    → overridden status.
    Low visibility                  → LOW_CONFIDENCE.
    """
    quality_flags:   List[QualityFlag]       = []
    not_applicable:  List[NotApplicableNote] = []

    vis_joints = ["left_shoulder", "right_shoulder", "left_hip", "right_hip",
                  "left_knee", "right_knee"]
    avg_vis = float(np.mean([lm_arr[_KP[j], 2] if lm_arr.shape[1] > 2 else 0.9
                              for j in vis_joints]))

    for v in violations:
        rule = v["rule"]
        rid  = f"rule_{rule}_{v['name'].lower().replace(' ', '_')}"

        # Hard disabled by template
        if rule in template.disabled_rules:
            not_applicable.append(NotApplicableNote(
                id=rid, rule=rule, status=STATUS_NOT_APPLICABLE,
                reason=f"{template.name}_template_disables_rule_{rule}",
            ))
            continue

        # Soft override includes NOT_APPLICABLE
        soft = template.soft_rules.get(rule, "")
        if soft == STATUS_NOT_APPLICABLE:
            not_applicable.append(NotApplicableNote(
                id=rid, rule=rule, status=STATUS_NOT_APPLICABLE,
                reason=f"not_applicable_for_{template.name}",
            ))
            continue

        # Low visibility → LOW_CONFIDENCE
        if avg_vis < 0.5:
            quality_flags.append(QualityFlag(
                id=rid, status=STATUS_LOW_CONFIDENCE, rule=rule,
                joint=v["joint"], value=round(avg_vis, 3), threshold=0.5,
                reason="landmark_confidence_below_0.5",
            ))
            continue

        # Derive base status from raw severity
        raw_status = STATUS_CRITICAL if v["severity"] >= 0.9 else STATUS_WARNING

        # Apply soft override (e.g. MONITOR overrides CRITICAL/WARNING)
        final_status = soft if soft else raw_status

        quality_flags.append(QualityFlag(
            id=rid, status=final_status, rule=rule,
            joint=v["joint"], value=v["value"], threshold=v["threshold"],
            evidence="pose_based_template_metric",
        ))

    return quality_flags, not_applicable


# ── Template-specific Metric Extractor ────────────────────────────────────────

def extract_template_metrics(
    angles: dict,
    template: ExerciseTemplate,
    lm_arr: np.ndarray,
    fps: float = 30.0,
    prev_angles: Optional[dict] = None,
) -> Dict[str, float]:
    m: Dict[str, float] = {}

    if template.name in ("squat", "lunge"):
        m["left_knee_angle"]  = round(angles.get("left_knee", 0.0), 1)
        m["right_knee_angle"] = round(angles.get("right_knee", 0.0), 1)
        m["left_hip_angle"]   = round(angles.get("left_hip", 0.0), 1)
        m["right_hip_angle"]  = round(angles.get("right_hip", 0.0), 1)
        m["trunk_lean_deg"]   = round(abs(180.0 - angles.get("spine", 180.0)), 1)
        if template.name == "lunge":
            lk = angles.get("left_knee", 180.0)
            rk = angles.get("right_knee", 180.0)
            front, back = min(lk, rk), max(lk, rk)
            m["front_knee_angle"]    = round(front, 1)
            m["back_knee_angle"]     = round(back, 1)
            m["knee_asymmetry_deg"]  = round(back - front, 1)

    elif template.name == "push_up":
        m["left_elbow_angle"]        = round(angles.get("left_elbow", 0.0), 1)
        m["right_elbow_angle"]       = round(angles.get("right_elbow", 0.0), 1)
        m["body_line_deviation_deg"] = round(abs(180.0 - angles.get("spine", 180.0)), 1)
        # Hip sag: how much hip deviates below the shoulder-ankle line
        if lm_arr is not None:
            sho_y = (lm_arr[_KP["left_shoulder"], 1] + lm_arr[_KP["right_shoulder"], 1]) / 2
            ank_y = (lm_arr[_KP["left_ankle"], 1]    + lm_arr[_KP["right_ankle"], 1])    / 2
            hip_y = (lm_arr[_KP["left_hip"], 1]      + lm_arr[_KP["right_hip"], 1])      / 2
            body_len = abs(ank_y - sho_y)
            if body_len > 0.05:
                t = 0.5  # hip should be midpoint
                ideal_hip_y = sho_y + (ank_y - sho_y) * t
                # positive sag = hip droops below line (larger y in image)
                m["hip_sag_pct"] = round((hip_y - ideal_hip_y) / body_len * 100.0, 1)

    elif template.name == "deadlift":
        avg_hip = (angles.get("left_hip", 0.0) + angles.get("right_hip", 0.0)) / 2
        avg_kne = (angles.get("left_knee", 0.0) + angles.get("right_knee", 0.0)) / 2
        m["hip_hinge_angle_deg"] = round(avg_hip, 1)
        m["trunk_angle_deg"]     = round(angles.get("spine", 0.0), 1)
        m["knee_angle_deg"]      = round(avg_kne, 1)
        # Bar path proxy: horizontal offset of wrist (where bar would be held)
        # from mid-foot, normalised by shoulder width. Small = bar tracks
        # vertically over mid-foot. Single-camera proxy, not a force estimate.
        if lm_arr is not None:
            wrist_x = (lm_arr[_KP["left_wrist"], 0] + lm_arr[_KP["right_wrist"], 0]) / 2
            foot_x  = (lm_arr[_KP["left_ankle"], 0] + lm_arr[_KP["right_ankle"], 0]) / 2
            sho_w   = abs(lm_arr[_KP["left_shoulder"], 0] - lm_arr[_KP["right_shoulder"], 0])
            if sho_w > 0.02:
                m["bar_over_midfoot_offset_pct"] = round(
                    (wrist_x - foot_x) / sho_w * 100.0, 1)

    # Common: angular velocity of primary joint as tempo proxy
    if prev_angles:
        primary = "left_elbow" if template.name == "push_up" else "left_knee"
        if primary in angles and primary in prev_angles:
            dps = abs(angles[primary] - prev_angles[primary]) * fps
            m["tempo_dps"] = round(dps, 1)

    return m


# ── Structured Motion Report ──────────────────────────────────────────────────

@dataclass
class StructuredMotionReport:
    frame: int
    exercise: str
    exercise_confidence: float
    phase: str
    rep: int
    metrics: Dict[str, float]
    quality_flags: List[QualityFlag]
    not_applicable: List[NotApplicableNote]
    candidate_scores: Dict[str, float]
    notes: List[str] = field(default_factory=lambda: [
        "not_medical_diagnosis",
        "single_camera_pose_based_feedback",
    ])

    def to_dict(self) -> dict:
        return {
            "frame": self.frame,
            "exercise": self.exercise,
            "exercise_confidence": self.exercise_confidence,
            "phase": self.phase,
            "rep": self.rep,
            "metrics": self.metrics,
            "quality_flags": [
                {"id": f.id, "status": f.status, "rule": f.rule,
                 "joint": f.joint, "value": f.value, "threshold": f.threshold,
                 "evidence": f.evidence}
                for f in self.quality_flags
            ],
            "not_applicable": [
                {"id": n.id, "rule": n.rule, "status": n.status, "reason": n.reason}
                for n in self.not_applicable
            ],
            "candidate_scores": self.candidate_scores,
            "notes": self.notes,
        }


def build_report(
    frame_idx: int,
    exercise_result: ExerciseDetectionResult,
    phase: str,
    rep: int,
    metrics: Dict[str, float],
    quality_flags: List[QualityFlag],
    not_applicable: List[NotApplicableNote],
) -> StructuredMotionReport:
    return StructuredMotionReport(
        frame=frame_idx,
        exercise=exercise_result.exercise,
        exercise_confidence=exercise_result.exercise_confidence,
        phase=phase,
        rep=rep,
        metrics=metrics,
        quality_flags=quality_flags,
        not_applicable=not_applicable,
        candidate_scores=exercise_result.candidate_scores,
    )


# ── Mock Gemma Feedback ───────────────────────────────────────────────────────

_RULE_MSG: Dict[int, Dict[str, str]] = {
    1: {
        STATUS_WARNING:  "Your knee is caving inward. Push the {joint} outward in line with your toes.",
        STATUS_CRITICAL: "Significant knee valgus at {joint}. Reduce load and drive knees out over toes.",
    },
    2: {
        STATUS_WARNING:  "Your back is rounding. Engage your core and lift your chest.",
        STATUS_CRITICAL: "Significant spinal rounding — stop and reset with a neutral spine.",
    },
    3: {
        STATUS_WARNING:  "Keep a slight bend at {joint} — avoid fully locking it out.",
        STATUS_CRITICAL: "Joint {joint} is nearly hyperextended. Control the range of motion.",
    },
    4: {
        STATUS_WARNING:  "Left-right imbalance at {joint}. Focus on equal weight distribution.",
        STATUS_MONITOR:  "Slight asymmetry at {joint} — normal mid-movement; monitor over reps.",
    },
    5: {
        STATUS_WARNING:  "Your weight is shifting. Keep balance centred over your base of support.",
        STATUS_MONITOR:  "COM shifting mid-rep — common; watch for consistent offset.",
    },
    6: {
        STATUS_WARNING:  "Movement is too fast. Control the tempo — smooth, deliberate motion.",
        STATUS_CRITICAL: "Very high joint velocity detected. Slow down to protect your joints.",
    },
    7: {
        STATUS_WARNING:  "Range of motion at {joint} is below target. Aim for full depth if mobility allows.",
    },
    8: {
        STATUS_WARNING:  "Keep your head neutral — eyes forward, not upward.",
        STATUS_CRITICAL: "Neck is in an unsafe position. Align your head with your spine.",
    },
}

_EX_WRAP: Dict[str, str] = {
    "squat":    "During your squat: {msg} Drive through your heels on the way up.",
    "push_up":  "During your push-up: {msg} Keep your body in a straight line shoulder to ankle.",
    "lunge":    "During your lunge: {msg} Keep front shin vertical and trunk tall.",
    "deadlift": "During the deadlift: {msg} Maintain a neutral spine and engage your lats.",
    "unknown":  "{msg}",
}

_POSITIVE: List[str] = [
    "Great form — keep it up.",
    "Looking strong. Excellent posture.",
    "Solid movement quality. Stay consistent.",
    "Nice control. Maintain that depth.",
    "Good alignment. You've got this.",
]


def mock_gemma_feedback(report: StructuredMotionReport) -> dict:
    """Deterministic per-frame coaching message from StructuredMotionReport.

    Simulates what a fine-tuned Gemma 4 model would produce.
    Consumes the same JSON schema expected by the future real prompt.
    """
    # Priority: CRITICAL > WARNING > MONITOR > LOW_CONFIDENCE > OK
    priority = {STATUS_CRITICAL: 0, STATUS_WARNING: 1, STATUS_MONITOR: 2,
                STATUS_LOW_CONFIDENCE: 3, STATUS_OK: 99}

    active = [f for f in report.quality_flags if f.status != STATUS_OK]
    active.sort(key=lambda f: priority.get(f.status, 99))

    na_summary = ""
    if report.not_applicable:
        reasons = [n.reason.replace("_", " ") for n in report.not_applicable[:2]]
        na_summary = f" (Note: {'; '.join(reasons)} -- not applicable for {report.exercise}.)"

    if not active:
        msg = _POSITIVE[report.frame % len(_POSITIVE)]
        level = "ok"
    else:
        top = active[0]
        rule_msgs = _RULE_MSG.get(top.rule, {})
        raw = (rule_msgs.get(top.status)
               or rule_msgs.get(STATUS_WARNING)
               or "Form issue detected.")
        raw = raw.format(joint=top.joint, value=round(top.value, 1))
        wrap = _EX_WRAP.get(report.exercise, "{msg}")
        msg = wrap.format(msg=raw)
        level = top.status.lower()

    return {
        "source":      "mock_gemma_feedback",
        "frame":       report.frame,
        "level":       level,
        "message":     msg + na_summary,
        "safety_note": "Pose-based coaching only — not medical diagnosis.",
    }
