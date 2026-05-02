"""dashboard_v3.py — GemmaFit Phase 3 Multi-Exercise Prototype Dashboard

New in v3:
  - Heuristic exercise auto-detection (squat / push_up / lunge / deadlift)
  - Exercise template: only activate relevant quality gates per exercise
  - Applicability gate layer: NOT_APPLICABLE / LOW_CONFIDENCE / VIEW_LIMITED
  - StructuredMotionReport per frame
  - mock_gemma_feedback coaching message

Usage:
  streamlit run prototype/dashboard_v3.py
  streamlit run prototype/dashboard_v3.py -- --squat   (load squat demo asset)
  streamlit run prototype/dashboard_v3.py -- --pushup  (load push-up demo asset)
"""

import sys, os, csv, io, json, math, argparse, base64
from dataclasses import dataclass, field, asdict
from typing import Optional, List, Dict, Tuple
from collections import Counter
from datetime import datetime

import numpy as np

PROTO_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT_DIR  = os.path.dirname(PROTO_DIR)
if PROTO_DIR not in sys.path:
    sys.path.insert(0, PROTO_DIR)

from compute_angles import (
    calculate_fppa, detect_knee_valgus, detect_knee_valgus_fppa,
    detect_rapid_movement, get_squat_phase, calculate_angular_velocity_dps,
)
from movement_classifier import (
    classify as classify_movement, compute_symmetry,
    KEYPOINT, calc_angle,
)
from muscle_focus_prototype import estimate_muscle_focus
from com_tracker_prototype import track_com, COMResult, _MockLandmark
from smooth_angle import SavitzkyGolay
from rep_counter import RepCounter

from exercises.core import (
    TEMPLATES, ExerciseTemplate, ExerciseDetectionResult,
    QualityFlag, NotApplicableNote, StructuredMotionReport,
    detect_exercise, apply_gates, extract_template_metrics,
    build_report, mock_gemma_feedback,
    STATUS_OK, STATUS_MONITOR, STATUS_WARNING, STATUS_CRITICAL,
    STATUS_NOT_APPLICABLE, STATUS_LOW_CONFIDENCE, STATUS_VIEW_LIMITED,
)
from gemma_local import gemma_feedback as gemma_feedback_local, HAS_LLAMA, LLAMA_ERR

GEMMA_MODELS = {
    "Gemma 4 E2B (Q4)": os.path.join(ROOT_DIR, "models", "gemma4-e2b-Q4_K_M.gguf"),
    "Gemma 4 E4B (Q4)": os.path.join(ROOT_DIR, "models", "gemma4-e4b-Q4_K_M.gguf"),
}

try:
    import streamlit as st
    import plotly.graph_objects as go
    import plotly.express as px
    from plotly.subplots import make_subplots
except ImportError:
    print("[ERROR] pip install streamlit plotly")
    sys.exit(1)

try:
    import cv2
    import mediapipe as mp
    HAS_MEDIAPIPE = True
    MEDIAPIPE_ERR = None
except Exception as e:
    HAS_MEDIAPIPE = False
    MEDIAPIPE_ERR = str(e)

mp_pose = mp.solutions.pose if HAS_MEDIAPIPE else None

# ── Asset paths ───────────────────────────────────────────────────────────────
ASSET_DIR  = os.path.join(ROOT_DIR, "test_assets", "videos")
SQUAT_VID  = os.path.join(ASSET_DIR, "squat_wikimedia_01.webm")
PUSHUP_VID = os.path.join(ASSET_DIR, "pushup_cdc_01.webm")
UPLOAD_DIR = os.path.join(PROTO_DIR, "data", "uploads")
DATA_DIR   = os.path.join(PROTO_DIR, "data", "processed", "landmarks")
os.makedirs(UPLOAD_DIR, exist_ok=True)

# ── Constants ─────────────────────────────────────────────────────────────────
LANDMARK_NAMES = [
    "nose", "left_eye_inner", "left_eye", "left_eye_outer",
    "right_eye_inner", "right_eye", "right_eye_outer",
    "left_ear", "right_ear", "mouth_left", "mouth_right",
    "left_shoulder", "right_shoulder",
    "left_elbow", "right_elbow",
    "left_wrist", "right_wrist",
    "left_pinky", "right_pinky",
    "left_index", "right_index",
    "left_thumb", "right_thumb",
    "left_hip", "right_hip",
    "left_knee", "right_knee",
    "left_ankle", "right_ankle",
    "left_heel", "right_heel",
    "left_foot_index", "right_foot_index",
]

SKELETON_CONNECTIONS = [
    ("left_shoulder", "right_shoulder"),
    ("left_shoulder", "left_elbow"), ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"), ("right_elbow", "right_wrist"),
    ("left_shoulder", "left_hip"), ("right_shoulder", "right_hip"),
    ("left_hip", "right_hip"),
    ("left_hip", "left_knee"), ("left_knee", "left_ankle"),
    ("right_hip", "right_knee"), ("right_knee", "right_ankle"),
    ("left_ankle", "left_heel"), ("left_heel", "left_foot_index"),
    ("right_ankle", "right_heel"), ("right_heel", "right_foot_index"),
]

# Dark theme
BG        = "#0f1724"
CARD_BG   = "#1a1a2e"
GREEN     = "#00D4AA"
RED       = "#FF4444"
ORANGE    = "#FFA500"
BLUE      = "#4ECDC4"
YELLOW    = "#FFD700"
TEXT      = "#e0e0e0"
TEXT_DIM  = "#a0a0a0"

STATUS_COLOR = {
    STATUS_OK:             "#2E7D32",
    STATUS_MONITOR:        "#F9A825",
    STATUS_WARNING:        ORANGE,
    STATUS_CRITICAL:       RED,
    STATUS_NOT_APPLICABLE: "#546E7A",
    STATUS_LOW_CONFIDENCE: "#7B1FA2",
    STATUS_VIEW_LIMITED:   "#1565C0",
}

EXERCISE_ICON = {
    "squat":    "🏋️",
    "push_up":  "💪",
    "lunge":    "🦵",
    "deadlift": "🔩",
    "unknown":  "❓",
}


# ── Data Loading ──────────────────────────────────────────────────────────────

def load_csv_frames(path: str) -> list:
    frames = []
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            lm = {}
            for name in LANDMARK_NAMES:
                for ax in ("x", "y", "z", "vis"):
                    k = f"{name}_{ax}"
                    try:    lm[k] = float(row.get(k, 0.0))
                    except: lm[k] = 0.0
            frames.append({"landmarks": lm, "source": row.get("source", ""), "frame": row.get("frame", "0")})
    return frames


def lm_to_arr(fd: dict) -> np.ndarray:
    arr = np.zeros((33, 3))
    for i, name in enumerate(LANDMARK_NAMES):
        arr[i, 0] = fd["landmarks"].get(f"{name}_x", 0.0)
        arr[i, 1] = fd["landmarks"].get(f"{name}_y", 0.0)
        arr[i, 2] = fd["landmarks"].get(f"{name}_vis", 0.9)
    return arr


def lm_to_mp_list(fd: dict) -> list:
    class _LM:
        def __init__(self, x, y, z=0.0, vis=0.9):
            self.x=x; self.y=y; self.z=z; self.visibility=vis
    return [_LM(fd["landmarks"].get(f"{n}_x", .5),
                fd["landmarks"].get(f"{n}_y", .5),
                fd["landmarks"].get(f"{n}_z", 0.),
                fd["landmarks"].get(f"{n}_vis", .9))
            for n in LANDMARK_NAMES]


@st.cache_data(show_spinner=False)
def extract_video_cached(video_path: str, sample_every: int = 1) -> Tuple[list, float]:
    if not HAS_MEDIAPIPE:
        return [], 30.0
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        return [], 30.0
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    frames_data = []
    with mp_pose.Pose(static_image_mode=False,
                      min_detection_confidence=0.5,
                      min_tracking_confidence=0.5) as pose:
        idx = 0
        while cap.isOpened():
            ok, img = cap.read()
            if not ok: break
            if idx % sample_every == 0:
                rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
                res = pose.process(rgb)
                lm = {}
                if res.pose_landmarks:
                    for i, name in enumerate(LANDMARK_NAMES):
                        if i < len(res.pose_landmarks.landmark):
                            lp = res.pose_landmarks.landmark[i]
                            lm[f"{name}_x"] = lp.x
                            lm[f"{name}_y"] = lp.y
                            lm[f"{name}_z"] = lp.z
                            lm[f"{name}_vis"] = lp.visibility
                frames_data.append({"landmarks": lm, "source": "video", "frame": str(idx)})
            idx += 1
    cap.release()
    return frames_data, fps


# ── Per-frame Analysis ────────────────────────────────────────────────────────

@dataclass
class FrameResultV3:
    frame_index: int = 0
    joint_angles: dict = field(default_factory=dict)
    raw_violations: list = field(default_factory=list)
    com_result: Optional[COMResult] = None
    movement_pattern: Optional[object] = None
    muscle_estimate: Optional[object] = None
    rep_count: int = 0
    rep_quality: float = 100.0
    phase: str = "top"
    confidence: float = 0.0
    # Phase 3 additions
    exercise_result: Optional[ExerciseDetectionResult] = None
    template: Optional[ExerciseTemplate] = None
    quality_flags: List[QualityFlag] = field(default_factory=list)
    not_applicable: List[NotApplicableNote] = field(default_factory=list)
    template_metrics: dict = field(default_factory=dict)
    report: Optional[StructuredMotionReport] = None
    gemma: Optional[dict] = None


def _pt(lm_arr: np.ndarray, name: str) -> np.ndarray:
    return lm_arr[KEYPOINT[name], :2]


def _compute_angles(lm_arr: np.ndarray) -> dict:
    angle_defs = [
        ("left_knee",      ("left_hip",      "left_knee",      "left_ankle")),
        ("right_knee",     ("right_hip",     "right_knee",     "right_ankle")),
        ("left_hip",       ("left_shoulder", "left_hip",       "left_knee")),
        ("right_hip",      ("right_shoulder","right_hip",      "right_knee")),
        ("left_elbow",     ("left_shoulder", "left_elbow",     "left_wrist")),
        ("right_elbow",    ("right_shoulder","right_elbow",    "right_wrist")),
        ("left_shoulder",  ("left_hip",      "left_shoulder",  "left_elbow")),
        ("right_shoulder", ("right_hip",     "right_shoulder", "right_elbow")),
        ("left_ankle",     ("left_knee",     "left_ankle",     "left_foot_index")),
        ("right_ankle",    ("right_knee",    "right_ankle",    "right_foot_index")),
        ("spine",          ("left_shoulder", "left_hip",       "left_knee")),
    ]
    angles = {}
    for jname, (a, b, c) in angle_defs:
        angles[jname] = calc_angle(_pt(lm_arr, a), _pt(lm_arr, b), _pt(lm_arr, c))
    ear_mid = (_pt(lm_arr, "left_ear") + _pt(lm_arr, "right_ear")) / 2
    sho_mid = (_pt(lm_arr, "left_shoulder") + _pt(lm_arr, "right_shoulder")) / 2
    hip_mid = (_pt(lm_arr, "left_hip") + _pt(lm_arr, "right_hip")) / 2
    angles["neck"] = calc_angle(ear_mid, sho_mid, hip_mid)
    return angles


def _raw_violations(lm_arr: np.ndarray, angles: dict,
                    fps: float, prev_angles: Optional[dict]) -> list:
    violations = []
    l_hip = _pt(lm_arr, "left_hip");   r_hip = _pt(lm_arr, "right_hip")
    l_kne = _pt(lm_arr, "left_knee");  r_kne = _pt(lm_arr, "right_knee")
    l_ank = _pt(lm_arr, "left_ankle"); r_ank = _pt(lm_arr, "right_ankle")

    knee_d  = float(np.linalg.norm(l_kne - r_kne))
    ankle_d = float(np.linalg.norm(l_ank - r_ank))
    l_fppa  = calculate_fppa(l_hip, l_kne, l_ank)
    r_fppa  = calculate_fppa(r_hip, r_kne, r_ank)
    max_fppa = max(l_fppa, r_fppa)
    valgus_ratio = knee_d / (ankle_d + 1e-9)

    # Rule 1
    if detect_knee_valgus(knee_d, ankle_d) or detect_knee_valgus_fppa(max_fppa):
        sev  = 0.9 if (valgus_ratio < 0.65 or max_fppa > 20) else 0.5
        side = "left" if l_fppa > r_fppa + 2 else "right" if r_fppa > l_fppa + 2 else "bilateral"
        val  = max_fppa if detect_knee_valgus_fppa(max_fppa) else valgus_ratio
        violations.append({"rule": 1, "name": "Knee Valgus",
                            "joint": f"{side}_knee", "severity": sev,
                            "value": round(val, 2), "threshold": 0.8})

    # Rule 2
    spine_dev = abs(180.0 - angles.get("spine", 180.0))
    if spine_dev > 15.0:
        violations.append({"rule": 2, "name": "Spinal Flexion",
                            "joint": "spine", "severity": 0.9 if spine_dev > 30 else 0.5,
                            "value": round(spine_dev, 1), "threshold": 15.0})

    # Rule 3
    for aname, aval in angles.items():
        if aval <= 5.0 or aval >= 175.0:
            violations.append({"rule": 3, "name": "Joint Overextension",
                                "joint": aname, "severity": 0.9 if (aval < 2 or aval > 178) else 0.5,
                                "value": round(aval, 1), "threshold": 175.0 if aval >= 175 else 5.0})
            break

    # Rule 4
    pairs = [("left_knee","right_knee"),("left_hip","right_hip"),
             ("left_shoulder","right_shoulder"),("left_elbow","right_elbow")]
    max_diff = 0.0; asy_joints = []
    for l, r in pairs:
        diff = abs(angles.get(l, 0) - angles.get(r, 0))
        if diff > max_diff: max_diff = diff
        if diff > 10.0: asy_joints.append(l.replace("left_",""))
    if asy_joints:
        violations.append({"rule": 4, "name": "Bilateral Asymmetry",
                            "joint": asy_joints[0], "severity": 0.9 if max_diff > 20 else 0.5,
                            "value": round(max_diff, 1), "threshold": 10.0})

    # Rule 5
    contact = "bipedal" if ankle_d > 0.05 else "unipedal"
    com = track_com(lm_to_mp_list_from_arr(lm_arr), sex="male", contact=contact)
    if not com.inside:
        violations.append({"rule": 5, "name": "COM Offset",
                            "joint": "com", "severity": 0.9 if com.offset_ratio > 1.2 else 0.5,
                            "value": round(com.offset_ratio, 3), "threshold": 1.0,
                            "_com": com})

    # Rule 6
    if prev_angles:
        for jn in ["left_knee", "left_hip", "left_elbow", "spine"]:
            if jn in angles and jn in prev_angles:
                vel = abs(calculate_angular_velocity_dps(angles[jn], prev_angles[jn], 1.0 / fps))
                if detect_rapid_movement(vel):
                    violations.append({"rule": 6, "name": "Rapid Movement",
                                        "joint": jn, "severity": 0.9 if vel > 900 else 0.5,
                                        "value": round(vel, 0), "threshold": 600.0})
                    break

    # Rule 8
    neck_dev = abs(180.0 - angles.get("neck", 180.0))
    if neck_dev > 15.0:
        violations.append({"rule": 8, "name": "Neck Hyperextension",
                            "joint": "neck", "severity": 0.9 if neck_dev > 25 else 0.5,
                            "value": round(neck_dev, 1), "threshold": 15.0})

    return violations


def lm_to_mp_list_from_arr(lm_arr: np.ndarray) -> list:
    class _LM:
        def __init__(self, x, y, z=0.0, vis=0.9):
            self.x=x; self.y=y; self.z=z; self.visibility=vis
    return [_LM(float(lm_arr[i,0]), float(lm_arr[i,1]), 0.0,
                float(lm_arr[i,2]) if lm_arr.shape[1]>2 else 0.9)
            for i in range(33)]


def analyze_frame_v3(
    fd: dict,
    frame_index: int,
    fps: float = 30.0,
    prev_angles: Optional[dict] = None,
    rep_counter: Optional[RepCounter] = None,
    exercise_override: Optional[str] = None,
) -> FrameResultV3:
    result = FrameResultV3(frame_index=frame_index)
    lm_arr = lm_to_arr(fd)

    angles = _compute_angles(lm_arr)
    result.joint_angles = angles

    # COM
    l_ank = _pt(lm_arr, "left_ankle"); r_ank = _pt(lm_arr, "right_ankle")
    ankle_d = float(np.linalg.norm(l_ank - r_ank))
    contact = "bipedal" if ankle_d > 0.05 else "unipedal"
    result.com_result = track_com(lm_to_mp_list_from_arr(lm_arr), sex="male", contact=contact)

    # Movement pattern (physical, no exercise name)
    try:
        pattern = classify_movement(lm_arr, prev_landmarks=None, prev_angles=prev_angles, fps=fps)
        result.movement_pattern = pattern
        result.muscle_estimate = estimate_muscle_focus({
            "pattern": pattern.pattern_label,
            "primary_joint": pattern.primary_joint.value,
            "base": pattern.base.value,
            "plane": pattern.plane.value,
        })
    except Exception:
        pass

    # Phase and reps
    knee_a = angles.get("left_knee", 180.0)
    result.phase = get_squat_phase(knee_a, "top")
    result.confidence = float(np.mean(lm_arr[:, 2]))
    if rep_counter:
        rep_counter.update(knee_a, 0)
        result.rep_count = rep_counter.rep_count
        if rep_counter.history:
            result.rep_quality = rep_counter.history[-1].form_quality

    # Raw violations (all 8 rules, no template filter)
    result.raw_violations = _raw_violations(lm_arr, angles, fps, prev_angles)

    # ── Phase 3: exercise detection + template gates ──────────────────────────
    ex_result = detect_exercise(lm_arr)
    if exercise_override and exercise_override != "auto":
        ex_result = ExerciseDetectionResult(
            exercise=exercise_override,
            exercise_confidence=1.0,
            candidate_scores=ex_result.candidate_scores,
            basis=["manual_override"],
            status=STATUS_OK,
        )
    result.exercise_result = ex_result
    template = TEMPLATES.get(ex_result.exercise, TEMPLATES["unknown"])
    result.template = template

    qf, na = apply_gates(result.raw_violations, template, lm_arr, angles, ex_result.exercise_confidence)
    result.quality_flags = qf
    result.not_applicable = na

    result.template_metrics = extract_template_metrics(angles, template, lm_arr, fps, prev_angles)

    report = build_report(frame_index, ex_result, result.phase,
                          result.rep_count, result.template_metrics, qf, na)
    result.report = report
    result.gemma = mock_gemma_feedback(report)

    return result


@st.cache_data(show_spinner=False)
def analyze_all_cached(frames: list, fps: float,
                       exercise_override: str = "auto") -> list:
    rc = RepCounter(primary_joint="left_knee", fps=fps)
    results = []
    prev_ang = None
    for i, fd in enumerate(frames):
        r = analyze_frame_v3(fd, i, fps, prev_ang, rc, exercise_override)
        results.append(r)
        prev_ang = r.joint_angles
    return results


# ── Visualization ─────────────────────────────────────────────────────────────

def overlay_skeleton_cv(img: np.ndarray, lm_arr: np.ndarray,
                        result: FrameResultV3,
                        lm_history: Optional[List[np.ndarray]] = None,
                        com_history: Optional[List[Tuple[float, float]]] = None,
                        trail_len: int = 20) -> np.ndarray:
    """Draw skeleton + flagged joints + COM onto a BGR OpenCV frame.

    lm_history / com_history hold the most recent past frames (oldest first)
    and are rendered as fading trails for wrist / knee / COM."""
    h, w = img.shape[:2]
    out = img.copy()

    flagged_joints = {f.joint for f in result.quality_flags
                      if f.status in (STATUS_CRITICAL, STATUS_WARNING)}

    def hex_to_bgr(c: str) -> tuple:
        c = c.lstrip("#")
        return (int(c[4:6],16), int(c[2:4],16), int(c[0:2],16))

    BLUE_BGR = hex_to_bgr(BLUE)
    RED_BGR  = hex_to_bgr(RED)
    GREEN_BGR = hex_to_bgr(GREEN)
    YELLOW_BGR = hex_to_bgr(YELLOW)
    ORANGE_BGR = hex_to_bgr(ORANGE)

    # ── Joint trails (drawn first, so skeleton sits on top) ───────────────────
    def _draw_trail(joint_name: str, color_bgr: tuple) -> None:
        if not lm_history: return
        idx = KEYPOINT.get(joint_name, -1)
        if idx < 0: return
        pts = []
        for past in lm_history[-trail_len:]:
            x, y = int(past[idx,0]*w), int(past[idx,1]*h)
            if x == 0 and y == 0: continue
            pts.append((x, y))
        for i in range(1, len(pts)):
            alpha = i / max(1, len(pts))                # 0 (old) → 1 (new)
            shaded = tuple(int(c * (0.25 + 0.75*alpha)) for c in color_bgr)
            cv2.line(out, pts[i-1], pts[i], shaded,
                     max(1, int(1 + 2*alpha)), cv2.LINE_AA)

    _draw_trail("left_wrist",  ORANGE_BGR)
    _draw_trail("right_wrist", ORANGE_BGR)
    _draw_trail("left_knee",   BLUE_BGR)
    _draw_trail("right_knee",  BLUE_BGR)

    # COM trail
    if com_history:
        com_pts = [(int(x*w), int(y*h)) for (x,y) in com_history[-trail_len:]]
        for i in range(1, len(com_pts)):
            alpha = i / max(1, len(com_pts))
            shaded = tuple(int(c * (0.25 + 0.75*alpha)) for c in YELLOW_BGR)
            cv2.line(out, com_pts[i-1], com_pts[i], shaded,
                     max(1, int(1 + 2*alpha)), cv2.LINE_AA)

    # Bones
    for a_name, b_name in SKELETON_CONNECTIONS:
        ia, ib = KEYPOINT[a_name], KEYPOINT[b_name]
        ax, ay = int(lm_arr[ia,0]*w), int(lm_arr[ia,1]*h)
        bx, by = int(lm_arr[ib,0]*w), int(lm_arr[ib,1]*h)
        if ax==0 and ay==0: continue
        if bx==0 and by==0: continue
        hit = any(j in a_name or j in b_name for j in flagged_joints)
        cv2.line(out, (ax,ay), (bx,by),
                 RED_BGR if hit else BLUE_BGR, 3, lineType=cv2.LINE_AA)

    # Joints + angle labels
    for jname in ["left_knee","right_knee","left_hip","right_hip",
                  "left_elbow","right_elbow","left_shoulder","right_shoulder"]:
        idx = KEYPOINT.get(jname, -1)
        if idx < 0 or idx >= 33: continue
        jx, jy = int(lm_arr[idx,0]*w), int(lm_arr[idx,1]*h)
        if jx==0 and jy==0: continue
        flag_status = next((f.status for f in result.quality_flags if f.joint == jname), None)
        color = hex_to_bgr(STATUS_COLOR.get(flag_status, GREEN)) if flag_status else GREEN_BGR
        cv2.circle(out, (jx,jy), 6, color, -1, lineType=cv2.LINE_AA)
        cv2.circle(out, (jx,jy), 7, (255,255,255), 1, lineType=cv2.LINE_AA)
        ang = result.joint_angles.get(jname)
        if ang is not None:
            cv2.putText(out, f"{ang:.0f}", (jx+8, jy-6),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5,
                        (255,255,255), 1, cv2.LINE_AA)

    # COM star + support polygon
    if result.com_result:
        cx = int(result.com_result.com.x * w)
        cy = int(result.com_result.com.y * h)
        cv2.drawMarker(out, (cx,cy), YELLOW_BGR,
                       markerType=cv2.MARKER_STAR, markerSize=18, thickness=2)
        poly = result.com_result.support_polygon
        if len(poly) >= 3:
            pts = np.array([[int(p.x*w), int(p.y*h)] for p in poly], dtype=np.int32)
            inside = result.com_result.inside
            cv2.polylines(out, [pts], True,
                          GREEN_BGR if inside else RED_BGR, 2, cv2.LINE_AA)

    return out


def draw_skeleton(lm_arr: np.ndarray, result: FrameResultV3,
                  w: int = 480, h: int = 580) -> go.Figure:
    fig = go.Figure()

    flagged_joints = {f.joint for f in result.quality_flags
                      if f.status in (STATUS_CRITICAL, STATUS_WARNING)}

    for a_name, b_name in SKELETON_CONNECTIONS:
        ia, ib = KEYPOINT[a_name], KEYPOINT[b_name]
        ax, ay = lm_arr[ia, 0], lm_arr[ia, 1]
        bx, by = lm_arr[ib, 0], lm_arr[ib, 1]
        hit = any(j in a_name or j in b_name for j in flagged_joints)
        fig.add_trace(go.Scatter(x=[ax,bx], y=[ay,by], mode="lines",
            line=dict(color=RED if hit else BLUE, width=4),
            hoverinfo="skip", showlegend=False))

    for jname in ["left_knee","right_knee","left_hip","right_hip",
                  "left_elbow","right_elbow","left_shoulder","right_shoulder","spine"]:
        idx = KEYPOINT.get(jname, -1)
        if idx < 0 or idx >= 33: continue
        jx, jy = lm_arr[idx, 0], lm_arr[idx, 1]
        angle_val = result.joint_angles.get(jname)
        flag_status = next((f.status for f in result.quality_flags if f.joint == jname), None)
        color = STATUS_COLOR.get(flag_status, GREEN) if flag_status else GREEN
        txt = f"{angle_val:.0f}°" if angle_val else ""
        fig.add_trace(go.Scatter(
            x=[jx], y=[jy], mode="markers+text",
            marker=dict(size=14, color=color, line=dict(width=2, color="white")),
            text=[txt], textposition="top center",
            textfont=dict(size=9, color="white"),
            hovertext=f"{jname}: {angle_val:.1f}°" if angle_val else jname,
            hoverinfo="text", showlegend=False,
        ))

    # COM star
    if result.com_result:
        cx = result.com_result.com.x
        cy = result.com_result.com.y
        fig.add_trace(go.Scatter(x=[cx], y=[cy], mode="markers",
            marker=dict(size=16, color=YELLOW, symbol="star",
                        line=dict(width=2, color="white")),
            hovertext=f"COM ({cx:.3f},{cy:.3f})", hoverinfo="text", showlegend=False))
        poly = result.com_result.support_polygon
        if len(poly) >= 3:
            px_ = [p.x for p in poly] + [poly[0].x]
            py_ = [p.y for p in poly] + [poly[0].y]
            inside = result.com_result.inside
            fig.add_trace(go.Scatter(x=px_, y=py_, mode="lines",
                fill="toself",
                line=dict(color=GREEN if inside else RED, width=2, dash="dash"),
                fillcolor="rgba(0,212,170,0.10)" if inside else "rgba(255,68,68,0.10)",
                hoverinfo="skip", showlegend=False))

    fig.update_layout(
        width=w, height=h,
        plot_bgcolor='rgba(0,0,0,0)', paper_bgcolor='rgba(0,0,0,0)',
        margin=dict(l=0, r=0, t=0, b=0),
        xaxis=dict(range=[0,1], showgrid=False, zeroline=False, visible=False),
        yaxis=dict(range=[1,0], showgrid=False, zeroline=False, visible=False),
    )
    return fig


def exercise_badge_html(ex_result: ExerciseDetectionResult) -> str:
    icon = EXERCISE_ICON.get(ex_result.exercise, "❓")
    ex   = ex_result.exercise.replace("_", " ").title()
    conf = ex_result.exercise_confidence
    bar_w = int(conf * 100)
    color = GREEN if conf >= 0.55 else (ORANGE if conf >= 0.30 else RED)
    status_label = ex_result.status if ex_result.status != STATUS_OK else ""
    return f"""
<div style="background: rgba(26,26,46,0.7); border: 1px solid rgba(255,255,255,0.05); border-radius: 12px; padding: 16px; margin-bottom: 16px; box-shadow: 0 4px 12px rgba(0,0,0,0.2);">
<div style="font-size: 1.6rem; font-weight: 800; color: {color}; display: flex; align-items: center; gap: 10px;">
<span>{icon}</span> {ex}
</div>
<div style="display: flex; align-items: center; gap: 12px; margin: 12px 0;">
<div style="flex: 1; background: rgba(255,255,255,0.05); border-radius: 6px; height: 10px; overflow: hidden; box-shadow: inset 0 1px 3px rgba(0,0,0,0.5);">
<div style="width: {bar_w}%; background: linear-gradient(90deg, {color}88, {color}); height: 100%; border-radius: 6px; transition: width 0.3s ease;"></div>
</div>
<span style="color: #fff; font-size: 0.9rem; font-weight: 600; min-width: 45px; text-align: right;">{conf:.0%}</span>
</div>
<div style="color: {TEXT_DIM}; font-size: 0.85rem; display: flex; flex-direction: column; gap: 4px;">
<div><strong style="color:#aaa;">Basis:</strong> {', '.join(ex_result.basis[:3]) or '—'}</div>
{f'<div style="color:{BLUE}; font-weight: 600; margin-top: 4px; padding: 4px 8px; background: {BLUE}22; border-radius: 4px; display: inline-block; width: max-content;">{status_label}</div>' if status_label else ''}
</div>
</div>
"""


def gemma_card_html(gemma: dict) -> str:
    level = gemma.get("level", "ok")
    border_color = {
        "critical": RED, "warning": ORANGE, "monitor": YELLOW,
        "ok": GREEN, "low_confidence": "#9C27B0",
    }.get(level, GREEN)
    icon = {"critical":"🚨","warning":"⚠️","monitor":"👀","ok":"✅","low_confidence":"🔍"}.get(level,"💬")
    msg = gemma.get("message", "")
    note = gemma.get("safety_note", "")
    return f"""
<div style="background: linear-gradient(145deg, rgba(26,26,46,0.9) 0%, rgba(15,23,36,0.8) 100%); border: 1px solid rgba(255,255,255,0.05); border-left: 6px solid {border_color}; box-shadow: 0 8px 32px 0 rgba(0,0,0,0.37); backdrop-filter: blur(10px); border-radius: 12px; padding: 16px 20px; margin-bottom: 16px; transition: transform 0.2s ease, box-shadow 0.2s ease;">
<div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px;">
<div style="font-size: 0.8rem; color: {TEXT_DIM}; text-transform: uppercase; letter-spacing: 0.1em; font-weight: 600;">✨ Gemma Coach Insights</div>
<div style="background: {border_color}22; padding: 4px 10px; border-radius: 20px; color: {border_color}; font-size: 0.75rem; font-weight: bold; text-transform: uppercase;">{level}</div>
</div>
<div style="font-size: 1.15rem; color: #ffffff; margin: 8px 0; font-weight: 600; line-height: 1.4;">
{icon} {msg}
</div>
<div style="font-size: 0.85rem; color: {TEXT_DIM}; font-style: italic; border-top: 1px solid rgba(255,255,255,0.05); padding-top: 8px; margin-top: 8px;">
💡 {note}
</div>
</div>
"""


def quality_flags_html(flags: List[QualityFlag], na: List[NotApplicableNote]) -> str:
    parts = []
    for f in flags:
        c = STATUS_COLOR.get(f.status, "#888")
        icon = {"CRITICAL":"🚨","WARNING":"⚠️","MONITOR":"👀",
                "LOW_CONFIDENCE":"🔍","OK":"✅"}.get(f.status,"•")
        parts.append(
            f'<div style="background: {c}11; border: 1px solid {c}44; border-left: 4px solid {c};'
            f'border-radius: 8px; padding: 10px 14px; margin-bottom: 8px; box-shadow: 0 2px 8px rgba(0,0,0,0.1);">'
            f'<div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">'
            f'<b style="color:{c}; font-size: 0.95rem;">{icon} {f.status}</b>'
            f'<span style="background: rgba(255,255,255,0.1); padding: 2px 6px; border-radius: 4px; color:{TEXT_DIM}; font-size:0.75rem; font-family: monospace;">Rule {f.rule}</span>'
            f'</div>'
            f'<div style="color: #fff; font-size: 0.9rem; font-weight: 500; margin-bottom: 4px;">{f.joint.replace("_", " ").title()}</div>'
            f'<div style="display: flex; gap: 12px; color:{TEXT_DIM}; font-size:0.8rem;">'
            f'<span>Value: <strong style="color: #fff;">{f.value}</strong></span>'
            f'<span>Threshold: <strong>{f.threshold}</strong></span>'
            f'</div>'
            f'</div>'
        )
    for n in na:
        c = STATUS_COLOR[STATUS_NOT_APPLICABLE]
        reason = n.reason.replace("_", " ")
        parts.append(
            f'<div style="background: {c}11; border: 1px solid {c}33; border-left: 3px solid {c};'
            f'border-radius: 8px; padding: 8px 12px; margin-bottom: 6px;">'
            f'<div style="display: flex; align-items: center; gap: 8px;">'
            f'<span style="color:{c}; font-size:0.9rem;">⊘ <strong>N/A</strong></span>'
            f'<span style="color:{TEXT_DIM}; font-size:0.8rem;">Rule {n.rule} — {reason}</span>'
            f'</div>'
            f'</div>'
        )
    if not parts:
        return f"""
<div style="background: {GREEN}11; border: 1px solid {GREEN}44; border-left: 4px solid {GREEN}; border-radius: 8px; padding: 14px; display: flex; align-items: center; gap: 10px;">
<div style="font-size: 1.5rem;">✅</div>
<div>
<div style="color: {GREEN}; font-weight: bold; font-size: 1rem;">All Gates Passed</div>
<div style="color: {TEXT_DIM}; font-size: 0.85rem;">Form is looking great!</div>
</div>
</div>
"""
    return "".join(parts)


def candidate_bar_chart(scores: Dict[str, float]) -> go.Figure:
    ex    = list(scores.keys())
    vals  = list(scores.values())
    icons = [EXERCISE_ICON.get(e, "?") for e in ex]
    labels = [f"{EXERCISE_ICON.get(e,'?')} {e.replace('_',' ')}" for e in ex]
    colors = [GREEN if v == max(vals) else BLUE for v in vals]
    fig = go.Figure(go.Bar(x=labels, y=vals, marker_color=colors,
                           text=[f"{v:.2f}" for v in vals], textposition="auto"))
    fig.update_layout(
        plot_bgcolor='rgba(0,0,0,0)', paper_bgcolor='rgba(0,0,0,0)',
        font=dict(color=TEXT, size=11, family="Outfit"),
        yaxis=dict(range=[0, 1.05], showgrid=False, visible=False),
        xaxis=dict(showgrid=False),
        margin=dict(l=0, r=0, t=10, b=0), height=180,
        showlegend=False,
    )
    return fig


def build_angle_trend(results: list, joints: list) -> go.Figure:
    colors = {"left_knee":"#FF6B6B","right_knee":"#4ECDC4","left_hip":"#45B7D1",
              "right_hip":"#96CEB4","spine":"#FFEAA7","neck":"#DDA0DD",
              "left_elbow":"#FFA07A","right_elbow":"#87CEEB"}
    fig = go.Figure()
    for j in joints:
        vals = [r.joint_angles.get(j, 0.0) for r in results]
        label = j.replace("_", " ").title()
        fig.add_trace(go.Scatter(
            x=list(range(len(vals))), y=vals, mode="lines", name=label,
            line=dict(color=colors.get(j, "#888"), width=2)))
    fig.update_layout(plot_bgcolor='rgba(0,0,0,0)', paper_bgcolor='rgba(0,0,0,0)',
        font=dict(color=TEXT, size=11, family="Outfit"), height=260,
        xaxis_title="Frame", yaxis_title="Angle (°)",
        margin=dict(l=40,r=10,t=20,b=30),
        legend=dict(font=dict(size=9), orientation="h", yanchor="bottom", y=1.02))
    return fig


def build_quality_trend(results: list) -> go.Figure:
    fig = go.Figure()
    status_map = {STATUS_CRITICAL:3, STATUS_WARNING:2, STATUS_MONITOR:1, STATUS_OK:0}
    status_colors = {3:RED, 2:ORANGE, 1:YELLOW, 0:GREEN}
    status_fill = {
        3: "rgba(255, 68, 68, 0.2)",
        2: "rgba(255, 140, 0, 0.2)",
        1: "rgba(255, 200, 61, 0.2)",
        0: "rgba(0, 212, 170, 0.2)"
    }
    for level, color in status_colors.items():
        vals = []
        for r in results:
            max_sev = max((status_map.get(f.status, 0) for f in r.quality_flags), default=0)
            vals.append(1 if max_sev == level else 0)
        label = [k for k,v in status_map.items() if v == level][0]
        fig.add_trace(go.Scatter(
            x=list(range(len(vals))), y=vals, mode="lines", name=label,
            line=dict(color=color, width=2), fill="tozeroy",
            fillcolor=status_fill[level]))
    fig.update_layout(plot_bgcolor='rgba(0,0,0,0)', paper_bgcolor='rgba(0,0,0,0)',
        font=dict(color=TEXT, size=11, family="Outfit"), height=200,
        xaxis_title="Frame", yaxis_title="",
        margin=dict(l=40,r=10,t=20,b=30),
        legend=dict(font=dict(size=9), orientation="h", yanchor="bottom", y=1.02))
    return fig


def build_exercise_timeline(results: list) -> go.Figure:
    ex_num = {"squat":3,"push_up":2,"lunge":1,"deadlift":0.5,"unknown":0}
    ex_color = {"squat":GREEN,"push_up":BLUE,"lunge":ORANGE,"deadlift":YELLOW,"unknown":TEXT_DIM}
    fig = go.Figure()
    for ex, num in ex_num.items():
        xs = [i for i, r in enumerate(results)
              if r.exercise_result and r.exercise_result.exercise == ex]
        ys = [num] * len(xs)
        if xs:
            fig.add_trace(go.Scatter(
                x=xs, y=ys, mode="markers", name=ex.replace("_"," ").title(),
                marker=dict(color=ex_color[ex], size=8, symbol="square")))
    fig.update_layout(plot_bgcolor='rgba(0,0,0,0)', paper_bgcolor='rgba(0,0,0,0)',
        font=dict(color=TEXT, size=11, family="Outfit"), height=150,
        xaxis_title="Frame",
        yaxis=dict(tickvals=list(ex_num.values()),
                   ticktext=[e.replace("_"," ").title() for e in ex_num.keys()],
                   showgrid=False),
        margin=dict(l=70,r=10,t=20,b=30),
        legend=dict(font=dict(size=9), orientation="h", yanchor="bottom", y=1.02))
    return fig


# ── Export ────────────────────────────────────────────────────────────────────

def export_json(results: list, fps: float) -> str:
    out = {
        "export_time": datetime.now().isoformat(),
        "total_frames": len(results),
        "fps": fps,
        "frames": [r.report.to_dict() for r in results if r.report],
    }
    return json.dumps(out, indent=2, ensure_ascii=False)


def export_csv(results: list) -> str:
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(["frame","exercise","confidence","phase","rep",
                "critical","warning","monitor","not_applicable",
                "left_knee","right_knee","left_hip","right_hip","spine",
                "gemma_level","gemma_msg"])
    for r in results:
        flags = r.quality_flags
        w.writerow([
            r.frame_index,
            r.exercise_result.exercise if r.exercise_result else "",
            r.exercise_result.exercise_confidence if r.exercise_result else 0,
            r.phase, r.rep_count,
            sum(1 for f in flags if f.status==STATUS_CRITICAL),
            sum(1 for f in flags if f.status==STATUS_WARNING),
            sum(1 for f in flags if f.status==STATUS_MONITOR),
            len(r.not_applicable),
            round(r.joint_angles.get("left_knee",0),1),
            round(r.joint_angles.get("right_knee",0),1),
            round(r.joint_angles.get("left_hip",0),1),
            round(r.joint_angles.get("right_hip",0),1),
            round(r.joint_angles.get("spine",0),1),
            r.gemma.get("level","") if r.gemma else "",
            r.gemma.get("message","") if r.gemma else "",
        ])
    return buf.getvalue()


def dl_link(data: str, fname: str, mime: str = "text/plain") -> str:
    b64 = base64.b64encode(data.encode()).decode()
    return (f'<a href="data:{mime};base64,{b64}" download="{fname}">'
            f'<button style="background:{GREEN};color:{BG};padding:8px 16px;'
            f'border:none;border-radius:6px;font-weight:bold;cursor:pointer;">'
            f'⬇ {fname}</button></a>')


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    # CLI args for default demo asset
    args = sys.argv[1:]
    default_video = None
    if "--squat" in args:
        default_video = SQUAT_VID
    elif "--pushup" in args:
        default_video = PUSHUP_VID

    st.set_page_config(page_title="GemmaFit v3 — Multi-Exercise", page_icon="🏋️",
                       layout="wide", initial_sidebar_state="expanded")

    st.markdown(f"""<style>
    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;600;800&display=swap');
    
    .stApp {{ 
        background: linear-gradient(135deg, #0f1724 0%, #0d1117 100%); 
        font-family: 'Outfit', sans-serif;
    }}
    .block-container {{ 
        padding-top: 2rem; 
        padding-left: 2rem; 
        padding-right: 2rem; 
        max-width: 95%; 
    }}
    h1, h2, h3, h4, h5, h6 {{ 
        font-family: 'Outfit', sans-serif !important;
    }}
    h2 {{ color: {GREEN} !important; font-weight: 600; padding-bottom: 8px; margin-top: 1rem; }}
    h3 {{ color: {BLUE} !important; font-weight: 600; margin-top: 1rem; }}
    
    [data-testid="stMetricValue"] {{ 
        font-size: 2rem !important; 
        font-weight: 800 !important; 
        color: {GREEN} !important;
    }}
    [data-testid="stMetricLabel"] {{
        font-size: 0.9rem !important;
        text-transform: uppercase;
        letter-spacing: 1px;
        color: {TEXT_DIM} !important;
    }}
    
    /* Glassmorphism for sidebar */
    div[data-testid="stSidebar"] {{
        background: rgba(26, 26, 46, 0.6) !important;
        backdrop-filter: blur(12px);
        border-right: 1px solid rgba(255,255,255,0.05);
    }}
    
    /* Tabs styling */
    .stTabs [data-baseweb="tab-list"] {{
        gap: 24px;
    }}
    .stTabs [data-baseweb="tab"] {{
        height: 50px;
        white-space: pre-wrap;
        background-color: transparent;
        border-radius: 4px 4px 0px 0px;
        gap: 1px;
        padding-top: 10px;
        padding-bottom: 10px;
        font-family: 'Outfit', sans-serif;
        font-size: 1.1rem;
    }}
    .stTabs [aria-selected="true"] {{
        background-color: rgba(0, 212, 170, 0.1) !important;
        border-bottom-color: {GREEN} !important;
    }}
    
    </style>""", unsafe_allow_html=True)

    st.markdown(f"""
    <div style="text-align: center; margin-bottom: 2rem; padding: 2rem; background: rgba(26,26,46,0.4); border-radius: 16px; border: 1px solid rgba(255,255,255,0.05);">
        <h1 style="font-size: 3.5rem; margin-bottom: 0.5rem; background: -webkit-linear-gradient(45deg, {GREEN}, {BLUE}); -webkit-background-clip: text; -webkit-text-fill-color: transparent; font-weight: 800;">GemmaFit v3</h1>
        <p style="font-size: 1.2rem; color: {TEXT_DIM}; font-weight: 300; letter-spacing: 1px;">Explainable Multi-Exercise Coach • Edge Biomechanics</p>
    </div>
    """, unsafe_allow_html=True)

    # ── Sidebar ──────────────────────────────────────────────────────────────
    with st.sidebar:
        st.header("📹 Input")
        source = st.radio("Source", ["Demo Assets", "Upload Video", "CSV Landmarks"], horizontal=True)

        if source == "Demo Assets":
            demo_choice = st.radio("Demo", ["Squat (Wikimedia)", "Push-up (CDC)"])
            sample_every = st.slider("Sample every N frames", 1, 10, 2)
            fps = 30.0
            sex = "male"
        elif source == "Upload Video":
            uploaded = st.file_uploader("Video (mp4 / webm / avi / mov)")
            if uploaded and not uploaded.name.lower().endswith((".mp4",".webm",".avi",".mov")):
                st.error("Please upload an mp4, webm, avi, or mov file.")
                uploaded = None
            sample_every = st.slider("Sample every N frames", 1, 10, 2)
            sex = st.selectbox("Body Profile", ["male","female"])
            fps = 30.0
        else:
            csvs = [f for f in os.listdir(DATA_DIR) if f.endswith(".csv")] if os.path.isdir(DATA_DIR) else []
            sel_csv = st.selectbox("CSV", csvs or ["(none)"])
            fps = st.number_input("FPS", value=30.0, min_value=1.0)
            sex = st.selectbox("Body Profile", ["male","female"])

        st.divider()
        st.header("🎯 Exercise Override")
        ex_override = st.selectbox(
            "Force exercise template",
            ["auto", "squat", "push_up", "lunge", "deadlift"],
            help="Override auto-detection for testing")

        st.divider()
        st.header("🤖 Coach")
        coach_options = ["Mock (deterministic)"]
        for name, p in GEMMA_MODELS.items():
            if os.path.exists(p):
                coach_options.append(name)
        if not HAS_LLAMA:
            st.caption(f"⚠ llama-cpp-python not loaded — Gemma options disabled. {LLAMA_ERR or ''}")
            coach_choice = "Mock (deterministic)"
        else:
            coach_choice = st.radio("Source", coach_options, index=0,
                help="Mock is fast and per-frame. Gemma runs only on the selected frame.")

        st.divider()
        st.header("📊 Display")
        trend_joints = st.multiselect(
            "Angle Trends", ["left_knee","right_knee","left_hip","right_hip",
                             "spine","left_elbow","right_elbow"],
            default=["left_knee","left_hip","spine"],
            format_func=lambda x: x.replace("_"," ").title())

    # ── Load frames ──────────────────────────────────────────────────────────
    frames: list = []
    vid_path = None

    if source == "Demo Assets":
        vid_path = SQUAT_VID if "Squat" in demo_choice else PUSHUP_VID
        if not os.path.exists(vid_path):
            st.error(f"Demo asset not found: `{vid_path}`")
            st.info("Run the asset downloader or place the video file there.")
            return
        if not HAS_MEDIAPIPE:
            st.error(f"MediaPipe unavailable: {MEDIAPIPE_ERR}")
            return
        with st.spinner("Extracting landmarks…"):
            frames, fps = extract_video_cached(vid_path, sample_every)

    elif source == "Upload Video" and "uploaded" in dir() and uploaded:
        local = os.path.join(UPLOAD_DIR, uploaded.name)
        if st.session_state.get("_last_vid") != uploaded.name:
            with open(local, "wb") as f: f.write(uploaded.getvalue())
            st.session_state["_last_vid"] = uploaded.name
            st.session_state["_vid_path"] = local
        vid_path = st.session_state.get("_vid_path")
        if vid_path and HAS_MEDIAPIPE:
            with st.spinner("Extracting landmarks…"):
                frames, fps = extract_video_cached(vid_path, sample_every)

    elif source == "CSV Landmarks" and os.path.isdir(DATA_DIR):
        csv_path = os.path.join(DATA_DIR, sel_csv)
        if os.path.exists(csv_path):
            frames = load_csv_frames(csv_path)

    if not frames:
        st.info("👆 Select a demo asset or upload a video to begin.")
        with st.expander("Quick start"):
            st.markdown(f"""
**Demo assets:**
- `{SQUAT_VID}`
- `{PUSHUP_VID}`

Run with pre-loaded demo:
```bash
streamlit run prototype/dashboard_v3.py -- --squat
streamlit run prototype/dashboard_v3.py -- --pushup
```
""")
        return

    # ── Analyse (cached) ─────────────────────────────────────────────────────
    st.caption(f"📊 **{len(frames)} frames** | FPS: {fps:.0f} | Override: {ex_override}")
    with st.spinner("Analysing…"):
        results = analyze_all_cached(frames, fps, ex_override)
    max_f = len(results) - 1

    # ── Playback State ───────────────────────────────────────────────────────
    import time
    if "current_frame" not in st.session_state:
        st.session_state.current_frame = 0
    if "is_playing" not in st.session_state:
        st.session_state.is_playing = False

    if st.session_state.current_frame > max_f:
        st.session_state.current_frame = max_f

    cur = results[st.session_state.current_frame]
    cur_lm = lm_to_arr(frames[st.session_state.current_frame])

    # ── Gemma override for the currently selected frame ─────────────────────
    # Mock runs in batch for all frames (fast). Real Gemma is too slow per-frame,
    # so we only invoke it for the frame the user is viewing, with a session
    # cache keyed on (frame_idx, model_path).
    if (HAS_LLAMA and coach_choice in GEMMA_MODELS
            and cur.report is not None):
        model_path = GEMMA_MODELS[coach_choice]
        cache_key = ("gemma_cache", coach_choice, st.session_state.current_frame)
        if cache_key in st.session_state:
            cur.gemma = st.session_state[cache_key]
        else:
            with st.spinner(f"Generating coaching with {coach_choice}…"):
                try:
                    cur.gemma = gemma_feedback_local(cur.report, model_path)
                    st.session_state[cache_key] = cur.gemma
                except Exception as e:
                    st.warning(f"Gemma inference failed, using mock: {e}")

    # ── Top metrics (Compact) ────────────────────────────────────────────────
    n_crit   = sum(1 for r in results if any(f.status==STATUS_CRITICAL  for f in r.quality_flags))
    n_warn   = sum(1 for r in results if any(f.status==STATUS_WARNING   for f in r.quality_flags))
    n_clean  = sum(1 for r in results if not r.quality_flags
                                         or all(f.status==STATUS_OK for f in r.quality_flags))
    ex_dist  = Counter(r.exercise_result.exercise for r in results if r.exercise_result)
    dominant = ex_dist.most_common(1)[0][0] if ex_dist else "unknown"

    m1,m2,m3,m4,m5 = st.columns(5)
    m1.metric("Exercise",  EXERCISE_ICON.get(dominant,"?") + " " + dominant.replace("_"," ").title())
    m2.metric("Reps",       cur.rep_count)
    m3.metric("Confidence", f"{cur.confidence:.0%}")
    m4.metric("Critical",   n_crit,  delta="frames" if n_crit else "none", delta_color="inverse" if n_crit else "off")
    m5.metric("Clean",      n_clean, delta=f"{n_clean/len(results):.0%}" if results else "0%")
    st.divider()

    # ── Concentrated Main Layout ─────────────────────────────────────────────
    col_main, col_side = st.columns([5, 4])

    with col_main:
        st.subheader("📹 Video Analysis")
        # Video / Skeleton overlay
        if vid_path and HAS_MEDIAPIPE:
            cap = cv2.VideoCapture(vid_path)
            cap.set(cv2.CAP_PROP_POS_FRAMES, int(frames[st.session_state.current_frame]["frame"]))
            ok, img = cap.read(); cap.release()
            if ok:
                start = max(0, st.session_state.current_frame - 20)
                lm_hist = [lm_to_arr(frames[i]) for i in range(start, st.session_state.current_frame + 1)]
                com_hist = [
                    (results[i].com_result.com.x, results[i].com_result.com.y)
                    for i in range(start, st.session_state.current_frame + 1)
                    if results[i].com_result is not None
                ]
                overlay = overlay_skeleton_cv(img, cur_lm, cur,
                                              lm_history=lm_hist,
                                              com_history=com_hist)
                st.image(cv2.cvtColor(overlay, cv2.COLOR_BGR2RGB), use_container_width=True)
            else:
                st.plotly_chart(draw_skeleton(cur_lm, cur), use_container_width=True)
        else:
            st.plotly_chart(draw_skeleton(cur_lm, cur), use_container_width=True)

        # Video Controls
        st.markdown("<div style='margin-top: 10px;'></div>", unsafe_allow_html=True)
        ctrl_col1, ctrl_col2 = st.columns([1, 4])
        with ctrl_col1:
            btn_label = "⏸ Pause" if st.session_state.is_playing else "▶ Play"
            if st.button(btn_label, use_container_width=True):
                st.session_state.is_playing = not st.session_state.is_playing
                if st.session_state.is_playing and st.session_state.current_frame >= max_f:
                    st.session_state.current_frame = 0
                st.rerun()
        with ctrl_col2:
            new_f = st.slider("Frame", 0, max_f, st.session_state.current_frame, label_visibility="collapsed")
            if new_f != st.session_state.current_frame:
                st.session_state.current_frame = new_f
                st.session_state.is_playing = False
                st.rerun()

        # Gemma Coach below video
        st.markdown("<div style='margin-top: 16px;'></div>", unsafe_allow_html=True)
        if cur.gemma:
            st.markdown(gemma_card_html(cur.gemma), unsafe_allow_html=True)

    with col_side:
        # Badge & Detection
        if cur.exercise_result:
            st.markdown(exercise_badge_html(cur.exercise_result), unsafe_allow_html=True)

        st.subheader("📐 Template Metrics")
        if cur.template_metrics:
            metrics_html = '<div style="display:grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 5px; margin-bottom: 20px;">'
            for k, v in cur.template_metrics.items():
                unit = "°/s" if "dps" in k or "tempo" in k else ("%" if "pct" in k else "°")
                label = k.replace("_"," ").title()
                metrics_html += f"""
<div style="background: rgba(255,255,255,0.03); border-radius: 8px; padding: 10px; border: 1px solid rgba(255,255,255,0.05); box-shadow: inset 0 2px 4px rgba(0,0,0,0.2);">
<div style="font-size: 0.7rem; color: {TEXT_DIM}; margin-bottom: 2px; text-transform: uppercase; letter-spacing: 0.5px;">{label}</div>
<div style="font-size: 1.2rem; font-weight: 800; color: #fff;">{v}<span style="font-size: 0.8rem; color: {GREEN}; margin-left: 3px; font-weight: 600;">{unit}</span></div>
</div>
"""
            metrics_html += '</div>'
            st.markdown(metrics_html, unsafe_allow_html=True)
        else:
            st.caption("No template metrics available.")

        st.subheader("🛡️ Quality Gates")
        if cur.quality_flags or cur.not_applicable:
            st.markdown(quality_flags_html(cur.quality_flags, cur.not_applicable),
                        unsafe_allow_html=True)
        else:
            st.markdown(quality_flags_html([], []), unsafe_allow_html=True)

        if cur.movement_pattern:
            st.subheader("🏃 Physical Pattern")
            p_html = f"""
<div style="background: rgba(26,26,46,0.6); border: 1px solid rgba(255,255,255,0.05); border-radius: 10px; padding: 14px; margin-bottom: 16px;">
<div style="font-family: monospace; color: {BLUE}; font-size: 1.05rem; font-weight: bold; margin-bottom: 10px; background: rgba(0,0,0,0.3); padding: 8px 12px; border-radius: 6px;">
{cur.movement_pattern.pattern_label}
</div>
<div style="display: flex; gap: 12px; font-size: 0.85rem;">
<div style="background: rgba(255,255,255,0.05); padding: 6px 10px; border-radius: 6px; flex: 1;">
<span style="color: {TEXT_DIM}; display: block; margin-bottom: 2px;">Joint</span> <strong style="color: #fff; font-size: 1rem;">{cur.movement_pattern.primary_joint.value}</strong>
</div>
<div style="background: rgba(255,255,255,0.05); padding: 6px 10px; border-radius: 6px; flex: 1;">
<span style="color: {TEXT_DIM}; display: block; margin-bottom: 2px;">Phase</span> <strong style="color: #fff; font-size: 1rem;">{cur.movement_pattern.phase.value}</strong>
</div>
</div>
</div>
"""
            st.markdown(p_html, unsafe_allow_html=True)
            
        if cur.muscle_estimate:
            st.subheader("🎯 Muscle Focus")
            mf = cur.muscle_estimate
            mf_html = f"""
<div style="background: rgba(26,26,46,0.6); border: 1px solid rgba(255,255,255,0.05); border-radius: 10px; padding: 16px;">
<div style="margin-bottom: 12px;">
<span style="font-size: 0.75rem; color: {TEXT_DIM}; text-transform: uppercase; letter-spacing: 0.5px; display: block; margin-bottom: 4px;">Primary Engagement</span>
<div style="color: {GREEN}; font-weight: 800; font-size: 1.1rem;">{', '.join(mf.estimated_primary)}</div>
</div>
<div style="margin-bottom: 14px;">
<span style="font-size: 0.75rem; color: {TEXT_DIM}; text-transform: uppercase; letter-spacing: 0.5px; display: block; margin-bottom: 4px;">Secondary / Stabilizers</span>
<div style="color: #fff; font-size: 0.95rem; font-weight: 500;">{', '.join(mf.estimated_secondary[:3])}</div>
</div>
<div style="background: rgba(0,0,0,0.3); padding: 10px 12px; border-radius: 8px; font-size: 0.85rem; color: {TEXT_DIM}; font-style: italic; border-left: 3px solid {BLUE};">
💡 {mf.note}
</div>
</div>
"""
            st.markdown(mf_html, unsafe_allow_html=True)

    # ── Tabs ──────────────────────────────────────────────────────────────────
    st.divider()
    if st.session_state.is_playing:
        st.info("⚡ **播放中...** (為提升 Streamlit 繪圖與畫面更新效能，暫時隱藏底部全域趨勢圖與報告。暫停後即可查看)")
    else:
        t1, t2, t3, t4 = st.tabs(["📈 Angle Trends", "🔬 Exercise Timeline", "📋 Report", "💾 Export"])

        with t1:
            st.plotly_chart(build_angle_trend(results, trend_joints), width="stretch")
            st.plotly_chart(build_quality_trend(results), width="stretch")

        with t2:
            st.plotly_chart(build_exercise_timeline(results), width="stretch")
            ex_cnt = Counter(r.exercise_result.exercise for r in results if r.exercise_result)
            st.write("Exercise distribution:")
            for ex, cnt in ex_cnt.most_common():
                pct = cnt / len(results) * 100
                st.write(f"  {EXERCISE_ICON.get(ex,'?')} {ex.replace('_',' ').title()}: "
                         f"{cnt} frames ({pct:.0f}%)")

        with t3:
            st.subheader("Structured Motion Report — current frame")
            if cur.report:
                st.json(cur.report.to_dict())
            st.subheader("Gemma Feedback — current frame")
            if cur.gemma:
                st.json(cur.gemma)

        with t4:
            st.subheader("Export Session Data")
            c1, c2 = st.columns(2)
            with c1:
                st.markdown(dl_link(export_json(results, fps),
                                    "gemmafit_v3_session.json", "application/json"),
                            unsafe_allow_html=True)
            with c2:
                st.markdown(dl_link(export_csv(results),
                                    "gemmafit_v3_session.csv", "text/csv"),
                            unsafe_allow_html=True)
            st.divider()
            crit_frames = [r for r in results if any(f.status==STATUS_CRITICAL for f in r.quality_flags)]
            st.metric("Critical-flag frames", len(crit_frames))
            na_total = sum(len(r.not_applicable) for r in results)
            st.metric("NOT_APPLICABLE invocations (total)", na_total,
                      help="Rules suppressed by template gates across all frames")

    # ── Automatic Playback Loop ─────────────────────────────────────────────
    if st.session_state.is_playing:
        if st.session_state.current_frame < max_f:
            st.session_state.current_frame += max(1, int(fps / 15))
            st.rerun()
        else:
            st.session_state.is_playing = False
            st.rerun()


if __name__ == "__main__":
    main()
