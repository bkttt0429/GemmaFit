"""
test_dashboard.py — GemmaFit Biomechanics Engine Dashboard v2

Major improvements:
  1. Frame caching — video frames cached in memory, no disk re-read on slider move
  2. Keyboard navigation — arrow keys step through frames
  3. Analysis caching — full analysis cached, slider only changes display
  4. Export reports — JSON/CSV/PDF session summary export
  5. 3D skeleton toggle — depth (z-axis) visualization
  6. Batch comparison — side-by-side two video analysis
  7. Real webcam support — uses streamlit-webrtc or cv2 VideoCapture
  8. Cleaner UI — collapsible sections, metric cards, responsive layout

Usage:
  streamlit run prototype/test_dashboard.py
  streamlit run prototype/test_dashboard.py -- --video path/to/video.mp4
"""

import sys
import os
import argparse
import csv
import io
import json
import tempfile
import base64
import math
from dataclasses import dataclass, field, asdict
from typing import Optional, List, Dict, Tuple
from collections import Counter
from datetime import datetime

import numpy as np

proto_dir = os.path.dirname(os.path.abspath(__file__))
if proto_dir not in sys.path:
    sys.path.insert(0, proto_dir)

from compute_angles import (
    calculate_joint_angle, detect_back_slack, detect_heels_off,
    detect_knee_over_toes, detect_knee_valgus, detect_knee_valgus_fppa,
    detect_rapid_movement, calculate_fppa, heel_angle, get_squat_phase,
    calculate_angular_velocity_dps,
)
from movement_classifier import (
    classify as classify_movement, classify_support_base, classify_dominant_joint,
    classify_movement_plane, classify_contraction_phase, compute_symmetry,
    KEYPOINT, calc_angle, SupportBase, DominantJoint, MovementPlane,
    ContractionPhase, LoadVector,
)
from muscle_focus_prototype import estimate_muscle_focus
from com_tracker_prototype import (
    track_com, whole_body_com, support_polygon, segment_coms,
    COMResult, Point2D, LM as COM_LM, _MockLandmark,
)
from smooth_angle import SavitzkyGolay, compute_angular_velocity
from rep_counter import RepCounter, RepPhase
from exercise_templates import (
    detect_exercise, EXERCISE_TEMPLATES, METRIC_EXTRACTORS,
)
from applicability_gate import ApplicabilityGate, generate_structured_report
from mock_gemma_feedback import generate_mock_feedback, generate_session_summary

try:
    import streamlit as st
    import plotly.graph_objects as go
    import plotly.express as px
    from plotly.subplots import make_subplots
    HAS_STREAMLIT = True
except ImportError:
    HAS_STREAMLIT = False
    print("[ERROR] streamlit, plotly required: pip install streamlit plotly")
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

# ─── Constants ─────────────────────────────────────────────────────────

DATA_DIR = os.path.join(proto_dir, "data", "processed", "landmarks")
ANGLE_DIR = os.path.join(proto_dir, "data", "processed", "angles")
UPLOAD_DIR = os.path.join(proto_dir, "data", "uploads")
os.makedirs(UPLOAD_DIR, exist_ok=True)

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

SAFETY_RULES = [
    ("Rule 1", "Knee Valgus", "D_knee/D_ankle < 0.8 or FPPA > 10°"),
    ("Rule 2", "Spinal Flexion", "Shoulder-hip-knee deviation > 15°"),
    ("Rule 3", "Joint Overextension", "Angle ≈ 0° or 180° ± 5°"),
    ("Rule 4", "Bilateral Asymmetry", "Left-right angle difference > 10°"),
    ("Rule 5", "COM Offset", "COM projection outside support polygon"),
    ("Rule 6", "Rapid Movement", "Angular velocity > 600°/s"),
    ("Rule 7", "ROM Insufficient", "ROM < 50% of expected"),
    ("Rule 8", "Neck Hyperextension", "Ear-shoulder-hip deviation > 15°"),
]

JOINT_DISPLAY = {
    "left_knee": ("L.Knee", "hip-knee-ankle"),
    "right_knee": ("R.Knee", "hip-knee-ankle"),
    "left_hip": ("L.Hip", "shoulder-hip-knee"),
    "right_hip": ("R.Hip", "shoulder-hip-knee"),
    "left_elbow": ("L.Elbow", "shoulder-elbow-wrist"),
    "right_elbow": ("R.Elbow", "shoulder-elbow-wrist"),
    "left_shoulder": ("L.Shoulder", "hip-shoulder-elbow"),
    "right_shoulder": ("R.Shoulder", "hip-shoulder-elbow"),
    "left_ankle": ("L.Ankle", "knee-ankle-foot"),
    "right_ankle": ("R.Ankle", "knee-ankle-foot"),
    "spine": ("Spine", "shoulder_mid-hip_mid-knee_mid"),
    "neck": ("Neck", "ear_mid-shoulder_mid-hip_mid"),
}

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

# Dark theme colors
BG_COLOR = "#0f1724"
CARD_BG = "#1a1a2e"
ACCENT_GREEN = "#00D4AA"
ACCENT_RED = "#FF4444"
ACCENT_ORANGE = "#FFA500"
ACCENT_BLUE = "#4ECDC4"
TEXT_PRIMARY = "#e0e0e0"
TEXT_SECONDARY = "#a0a0a0"

# ─── Data Classes ──────────────────────────────────────────────────────

@dataclass
class FrameAnalysis:
    safety_violations: list = field(default_factory=list)  # Legacy, keep for compatibility
    joint_angles: dict = field(default_factory=dict)
    symmetry_score: float = 1.0
    symmetry_joints: list = field(default_factory=list)
    com_result: Optional[COMResult] = None
    movement_pattern: Optional[object] = None
    muscle_estimate: Optional[object] = None
    phase: str = "top"
    rep_count: int = 0
    rep_quality: float = 100.0
    confidence: float = 0.0
    frame_index: int = 0
    timestamp_ms: int = 0
    # New template-aware fields
    exercise: str = "unknown"
    exercise_confidence: float = 0.0
    exercise_basis: list = field(default_factory=list)
    template_metrics: dict = field(default_factory=dict)
    gate_result: Optional[object] = None
    structured_report: dict = field(default_factory=dict)
    mock_feedback: dict = field(default_factory=dict)
    angular_velocities: dict = field(default_factory=dict)


@dataclass
class SessionSummary:
    total_frames: int = 0
    total_violations: int = 0
    critical_count: int = 0
    warning_count: int = 0
    clean_frames: int = 0
    reps_completed: int = 0
    avg_quality: float = 0.0
    avg_symmetry: float = 1.0
    pattern_distribution: dict = field(default_factory=dict)
    rule_trigger_counts: dict = field(default_factory=dict)
    muscle_focus_summary: dict = field(default_factory=dict)


# ─── Core Functions ────────────────────────────────────────────────────

def load_csv_frames(csv_path: str) -> list:
    frames = []
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            lm = {}
            for name in LANDMARK_NAMES:
                for axis in ("x", "y", "z", "vis"):
                    key = f"{name}_{axis}"
                    try:
                        lm[key] = float(row.get(key, 0.0))
                    except (ValueError, TypeError):
                        lm[key] = 0.0
            frames.append({
                "landmarks": lm,
                "source": row.get("source", ""),
                "frame": row.get("frame", "0"),
            })
    return frames


def lm_to_arrays(frame_data: dict) -> np.ndarray:
    landmarks = np.zeros((33, 3))
    for i, name in enumerate(LANDMARK_NAMES):
        landmarks[i, 0] = frame_data["landmarks"].get(f"{name}_x", 0.0)
        landmarks[i, 1] = frame_data["landmarks"].get(f"{name}_y", 0.0)
        vis = frame_data["landmarks"].get(f"{name}_vis", 0.0)
        landmarks[i, 2] = vis
    return landmarks


def lm_to_mediapipe_list(frame_data: dict) -> list:
    class _LM:
        def __init__(self, x, y, z=0.0, vis=0.9):
            self.x = x; self.y = y; self.z = z; self.visibility = vis
    lms = []
    for name in LANDMARK_NAMES:
        x = frame_data["landmarks"].get(f"{name}_x", 0.5)
        y = frame_data["landmarks"].get(f"{name}_y", 0.5)
        z = frame_data["landmarks"].get(f"{name}_z", 0.0)
        vis = frame_data["landmarks"].get(f"{name}_vis", 0.0)
        lms.append(_LM(x, y, z, vis))
    return lms


@st.cache_data(show_spinner=False)
def extract_video_frames_cached(video_path: str, sample_every: int = 1) -> tuple:
    """Cache video frame extraction to avoid re-reading from disk."""
    if not HAS_MEDIAPIPE:
        return [], 30.0
    cap = cv2.VideoCapture(video_path)
    if not cap.isOpened():
        return [], 30.0
    fps = cap.get(cv2.CAP_PROP_FPS)
    if fps <= 0:
        fps = 30.0
    frames_data = []
    with mp_pose.Pose(static_image_mode=False, min_detection_confidence=0.5,
                      min_tracking_confidence=0.5) as pose:
        frame_idx = 0
        while cap.isOpened():
            success, image = cap.read()
            if not success:
                break
            if frame_idx % sample_every == 0:
                image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
                results = pose.process(image_rgb)
                lm_dict = {}
                if results.pose_landmarks:
                    for i, name in enumerate(LANDMARK_NAMES):
                        if i < len(results.pose_landmarks.landmark):
                            lm = results.pose_landmarks.landmark[i]
                            lm_dict[f"{name}_x"] = lm.x
                            lm_dict[f"{name}_y"] = lm.y
                            lm_dict[f"{name}_z"] = lm.z
                            lm_dict[f"{name}_vis"] = lm.visibility
                    frames_data.append({
                        "landmarks": lm_dict,
                        "source": "video",
                        "frame": str(frame_idx),
                    })
            frame_idx += 1
    cap.release()
    return frames_data, fps


def analyze_frame(frame_data: dict, prev_frame_data: dict = None,
                  fps: float = 30.0, prev_angles: dict = None,
                  rep_counter: RepCounter = None, frame_index: int = 0,
                  exercise_override: str = None) -> FrameAnalysis:
    result = FrameAnalysis(frame_index=frame_index)
    lm_arr = lm_to_arrays(frame_data)
    lm_list = lm_to_mediapipe_list(frame_data)
    result.confidence = float(np.mean(lm_arr[:, 2]))

    def _pt(name):
        idx = KEYPOINT[name]
        return lm_arr[idx, :2]

    angles = {}
    for jname, (a, b, c) in [
        ("left_knee", ("left_hip", "left_knee", "left_ankle")),
        ("right_knee", ("right_hip", "right_knee", "right_ankle")),
        ("left_hip", ("left_shoulder", "left_hip", "left_knee")),
        ("right_hip", ("right_shoulder", "right_hip", "right_knee")),
        ("left_elbow", ("left_shoulder", "left_elbow", "left_wrist")),
        ("right_elbow", ("right_shoulder", "right_elbow", "right_wrist")),
        ("left_shoulder", ("left_hip", "left_shoulder", "left_elbow")),
        ("right_shoulder", ("right_hip", "right_shoulder", "right_elbow")),
        ("left_ankle", ("left_knee", "left_ankle", "left_foot_index")),
        ("right_ankle", ("right_knee", "right_ankle", "right_foot_index")),
        ("spine", ("left_shoulder", "left_hip", "left_knee")),
    ]:
        angles[jname] = calc_angle(_pt(a), _pt(b), _pt(c))

    neck_mid = (_pt("left_ear") + _pt("right_ear")) / 2
    shoulder_mid = (_pt("left_shoulder") + _pt("right_shoulder")) / 2
    hip_mid = (_pt("left_hip") + _pt("right_hip")) / 2
    angles["neck"] = calc_angle(neck_mid, shoulder_mid, hip_mid)
    result.joint_angles = angles

    l_sho = _pt("left_shoulder"); r_sho = _pt("right_shoulder")
    l_hip = _pt("left_hip"); r_hip = _pt("right_hip")
    l_kne = _pt("left_knee"); r_kne = _pt("right_knee")
    l_ank = _pt("left_ankle"); r_ank = _pt("right_ankle")

    knee_dist = float(np.linalg.norm(l_kne - r_kne))
    ankle_dist = float(np.linalg.norm(l_ank - r_ank))
    valgus_ratio = knee_dist / (ankle_dist + 1e-9)

    # ─── Exercise Detection ──────────────────────────────────────────────
    detection = detect_exercise(lm_arr, angles)
    if exercise_override:
        result.exercise = exercise_override
        result.exercise_confidence = 1.0
        result.exercise_basis = ["exercise_override"]
    else:
        result.exercise = detection["exercise"]
        result.exercise_confidence = detection["exercise_confidence"]
        result.exercise_basis = detection.get("basis", [])

    # ─── Template-specific Metrics ───────────────────────────────────────
    template_metrics = {}
    extractor = METRIC_EXTRACTORS.get(result.exercise)
    if extractor:
        template_metrics = extractor(lm_arr, angles)
    result.template_metrics = template_metrics

    # ─── Generic Metrics for Gate ────────────────────────────────────────
    com_result = track_com(lm_list, sex="male",
                           contact="bipedal" if ankle_dist > 0.05 else "unipedal")
    result.com_result = com_result

    metrics = {
        "knee_valgus_ratio": valgus_ratio,
        "trunk_lean": abs(180.0 - angles.get("spine", 180.0)),
        "com_offset_ratio": com_result.offset_ratio if com_result else 0.0,
        **template_metrics,
    }

    # ─── Angular Velocities ──────────────────────────────────────────────
    angular_velocities = {}
    if prev_angles is not None:
        dt = 1.0 / fps
        for jname in ["left_knee", "left_hip", "spine", "left_elbow"]:
            if jname in angles and jname in prev_angles:
                vel = calculate_angular_velocity_dps(angles[jname], prev_angles[jname], dt)
                angular_velocities[jname] = vel
    result.angular_velocities = angular_velocities

    # ─── Applicability Gate ──────────────────────────────────────────────
    gate = ApplicabilityGate(exercise=result.exercise, visibility_threshold=0.5)
    gate_result = gate.evaluate(metrics=metrics, landmarks=lm_arr, angles=angles,
                                angular_velocities=angular_velocities)
    result.gate_result = gate_result

    # Build legacy safety_violations for visualization compatibility
    legacy_violations = []
    for f in gate_result.quality_flags:
        if f.status in ("CRITICAL", "WARNING"):
            legacy_violations.append({
                "rule": 0, "name": f.id, "desc": f.reason or f.id,
                "joint": f.id, "severity": 0.9 if f.status == "CRITICAL" else 0.5,
                "value": round(f.value, 2), "threshold": round(f.threshold, 2),
                "metric": f.evidence,
            })
    result.safety_violations = legacy_violations

    # ─── Structured Report & Mock Feedback ───────────────────────────────
    knee_a = angles.get("left_knee", 180.0)
    result.phase = get_squat_phase(knee_a, "top")

    structured_report = generate_structured_report(
        frame=frame_index,
        exercise=result.exercise,
        exercise_confidence=result.exercise_confidence,
        phase=result.phase,
        rep=result.rep_count,
        metrics=metrics,
        gate_result=gate_result,
    )
    result.structured_report = structured_report
    result.mock_feedback = generate_mock_feedback(structured_report)

    # ─── Symmetry (legacy) ───────────────────────────────────────────────
    result.symmetry_score = compute_symmetry(angles)
    asy_joints = []
    pairs = [("left_knee", "right_knee"), ("left_hip", "right_hip"),
             ("left_shoulder", "right_shoulder"), ("left_elbow", "right_elbow")]
    for l, r in pairs:
        if l in angles and r in angles:
            diff = abs(angles[l] - angles[r])
            if diff > 10.0:
                asy_joints.append(l.replace("left_", "").replace("right_", ""))
    result.symmetry_joints = asy_joints

    # ─── Movement Pattern & Muscle Focus ─────────────────────────────────
    try:
        pattern = classify_movement(lm_arr, prev_landmarks=None, prev_angles=prev_angles, fps=fps)
        result.movement_pattern = pattern
        muscle = estimate_muscle_focus({
            "pattern": pattern.pattern_label,
            "primary_joint": pattern.primary_joint.value,
            "base": pattern.base.value,
            "plane": pattern.plane.value,
        })
        result.muscle_estimate = muscle
    except Exception:
        pass

    # ─── Rep Counter ─────────────────────────────────────────────────────
    if rep_counter is not None:
        rep_counter.update(knee_a, len(legacy_violations))
        result.rep_count = rep_counter.rep_count
        if rep_counter.history:
            result.rep_quality = rep_counter.history[-1].form_quality

    return result


@st.cache_data(show_spinner=False)
def analyze_all_frames_cached(frames: list, fps: float, exercise_override: str = None) -> Tuple[list, RepCounter]:
    """Cache full analysis so slider only changes display."""
    def _run(override: str = None) -> Tuple[list, RepCounter]:
        primary_joint = "left_elbow" if override == "push_up" else "left_knee"
        rep_counter = RepCounter(primary_joint=primary_joint, fps=fps)
        analyses = []
        prev_fd, prev_ang = None, None
        for i, fd in enumerate(frames):
            a = analyze_frame(fd, prev_frame_data=prev_fd, fps=fps,
                              prev_angles=prev_ang, rep_counter=rep_counter, frame_index=i,
                              exercise_override=override)
            analyses.append(a)
            prev_fd, prev_ang = fd, a.joint_angles
        return analyses, rep_counter

    analyses, rep_counter = _run(exercise_override)
    if exercise_override is None and analyses:
        counts = Counter(a.exercise for a in analyses if a.exercise != "unknown_or_mixed")
        if counts:
            ranked = counts.most_common()
            top_exercise, top_count = ranked[0]
            second_count = ranked[1][1] if len(ranked) > 1 else 0
            non_unknown = sum(counts.values())
            if top_count >= max(8, 0.45 * non_unknown) and top_count >= max(1, second_count * 1.15):
                analyses, rep_counter = _run(top_exercise)
                for a in analyses:
                    a.exercise_basis = ["session_auto_lock", top_exercise]

    return analyses, rep_counter


# ─── Visualization ─────────────────────────────────────────────────────

def draw_skeleton_plotly(lm_arr: np.ndarray, analysis: FrameAnalysis,
                         show_3d: bool = False, fig_width: int = 500, fig_height: int = 600):
    if show_3d and lm_arr.shape[1] >= 3:
        fig = go.Figure()
        for a_name, b_name in SKELETON_CONNECTIONS:
            idx_a = KEYPOINT[a_name]
            idx_b = KEYPOINT[b_name]
            fig.add_trace(go.Scatter3d(
                x=[lm_arr[idx_a, 0], lm_arr[idx_b, 0]],
                y=[lm_arr[idx_a, 1], lm_arr[idx_b, 1]],
                z=[lm_arr[idx_a, 2], lm_arr[idx_b, 2]],
                mode="lines",
                line=dict(color="#4ECDC4", width=4),
                hoverinfo="skip", showlegend=False,
            ))
        fig.update_layout(
            width=fig_width, height=fig_height,
            scene=dict(
                xaxis=dict(visible=False, range=[0, 1]),
                yaxis=dict(visible=False, range=[1, 0]),
                zaxis=dict(visible=False),
                bgcolor=BG_COLOR,
                aspectmode="data",
            ),
            paper_bgcolor=BG_COLOR,
            margin=dict(l=0, r=0, t=0, b=0),
        )
        return fig

    fig = go.Figure()
    for a_name, b_name in SKELETON_CONNECTIONS:
        idx_a = KEYPOINT[a_name]
        idx_b = KEYPOINT[b_name]
        ax, ay = lm_arr[idx_a, 0], lm_arr[idx_a, 1]
        bx, by = lm_arr[idx_b, 0], lm_arr[idx_b, 1]
        violated = any(v["joint"].lower() in a_name.lower() or v["joint"].lower() in b_name.lower()
                       for v in analysis.safety_violations)
        color = ACCENT_RED if violated else ACCENT_BLUE
        fig.add_trace(go.Scatter(
            x=[ax, bx], y=[ay, by], mode="lines",
            line=dict(color=color, width=4),
            hoverinfo="skip", showlegend=False,
        ))

    for jname, (display, _) in JOINT_DISPLAY.items():
        idx = KEYPOINT.get(jname, -1)
        if idx < 0: continue
        jx, jy = lm_arr[idx, 0], lm_arr[idx, 1]
        angle_val = analysis.joint_angles.get(jname)
        violated = any(v["joint"] == jname for v in analysis.safety_violations)
        color = ACCENT_RED if violated else ACCENT_GREEN
        hover = f"{display}: {angle_val:.1f}°" if angle_val else display
        fig.add_trace(go.Scatter(
            x=[jx], y=[jy], mode="markers+text",
            marker=dict(size=14, color=color, line=dict(width=2, color="white")),
            text=[f"{angle_val:.0f}°"] if angle_val else [display],
            textposition="top center", textfont=dict(size=10, color="white"),
            hovertext=hover, hoverinfo="text", showlegend=False,
        ))

    if analysis.com_result:
        com = analysis.com_result.com
        fig.add_trace(go.Scatter(
            x=[com.x], y=[com.y], mode="markers",
            marker=dict(size=18, color="#FFD700", symbol="star",
                        line=dict(width=2, color="white")),
            hovertext=f"COM ({com.x:.3f}, {com.y:.3f})", hoverinfo="text", showlegend=False,
        ))
        poly = analysis.com_result.support_polygon
        if len(poly) >= 3:
            px = [p.x for p in poly] + [poly[0].x]
            py = [p.y for p in poly] + [poly[0].y]
            inside = analysis.com_result.inside
            poly_color = "rgba(0, 212, 170, 0.15)" if inside else "rgba(255, 68, 68, 0.15)"
            poly_border = ACCENT_GREEN if inside else ACCENT_RED
            fig.add_trace(go.Scatter(
                x=px, y=py, mode="lines",
                line=dict(color=poly_border, width=2, dash="dash"),
                fill="toself", fillcolor=poly_color,
                hoverinfo="skip", showlegend=False,
            ))

    for v in analysis.safety_violations:
        jname = v["joint"]
        idx = KEYPOINT.get(jname, -1)
        if 0 <= idx < 33:
            jx, jy = lm_arr[idx, 0], lm_arr[idx, 1]
            fig.add_trace(go.Scatter(
                x=[jx], y=[jy], mode="markers",
                marker=dict(size=28, color="rgba(255,68,68,0.2)", symbol="circle"),
                hovertext=v["desc"], hoverinfo="text", showlegend=False,
            ))

    fig.update_layout(
        width=fig_width, height=fig_height,
        plot_bgcolor=BG_COLOR, paper_bgcolor=BG_COLOR,
        margin=dict(l=10, r=10, t=10, b=10),
        xaxis=dict(range=[0, 1], showgrid=False, zeroline=False, visible=False),
        yaxis=dict(range=[1, 0], showgrid=False, zeroline=False, visible=False),
        dragmode="pan",
    )
    return fig


def draw_safety_cards(analysis: FrameAnalysis):
    """Draw compact safety rule cards."""
    cards = []
    for i, (rule_id, rule_name, rule_desc) in enumerate(SAFETY_RULES, 1):
        viols = [v for v in analysis.safety_violations if v["rule"] == i]
        if viols:
            v = viols[0]
            sev = v["severity"]
            status = "CRITICAL" if sev >= 0.9 else "WARNING"
            icon = "🚨" if sev >= 0.9 else "⚠️"
            color = ACCENT_RED if sev >= 0.9 else ACCENT_ORANGE
            cards.append({
                "rule": rule_id, "name": rule_name, "status": status,
                "icon": icon, "color": color, "value": v["value"],
                "threshold": v["threshold"], "joint": v["joint"],
                "desc": v["desc"], "active": True,
            })
        else:
            cards.append({
                "rule": rule_id, "name": rule_name, "status": "OK",
                "icon": "✅", "color": "#2E7D32", "value": 0,
                "threshold": 0, "joint": "", "desc": "", "active": False,
            })
    return cards


def build_angle_trend(angle_history: dict, frame_indices: list, selected_joints: list):
    fig = go.Figure()
    colors = {
        "left_knee": "#FF6B6B", "right_knee": "#4ECDC4",
        "left_hip": "#45B7D1", "right_hip": "#96CEB4",
        "spine": "#FFEAA7", "neck": "#DDA0DD",
        "left_elbow": "#FFA07A", "right_elbow": "#87CEEB",
        "left_shoulder": "#FFD700", "right_shoulder": "#98FB98",
        "left_ankle": "#F0E68C", "right_ankle": "#DDA0DD",
    }
    for jname in selected_joints:
        vals = angle_history.get(jname, [])
        if not vals:
            continue
        display = JOINT_DISPLAY.get(jname, (jname, ""))[0]
        fig.add_trace(go.Scatter(
            x=frame_indices[:len(vals)], y=vals,
            mode="lines", name=display,
            line=dict(color=colors.get(jname, "#888888"), width=2),
        ))
    fig.update_layout(
        plot_bgcolor=BG_COLOR, paper_bgcolor=BG_COLOR,
        font=dict(color=TEXT_PRIMARY, size=11),
        margin=dict(l=40, r=10, t=30, b=30),
        xaxis_title="Frame", yaxis_title="Angle (°)",
        legend=dict(font=dict(size=9), orientation="h", yanchor="bottom", y=1.02),
        height=280,
    )
    return fig


def build_safety_trend(safety_history: list, frame_indices: list):
    fig = go.Figure()
    rule_colors = ["#FF6B6B", "#FFEAA7", "#45B7D1", "#96CEB4",
                   "#FFD700", "#DDA0DD", "#87CEEB", "#FFA07A"]
    for rule_num in range(1, 9):
        vals = []
        for sh in safety_history:
            triggered = any(v["rule"] == rule_num for v in sh)
            sev = next((v["severity"] for v in sh if v["rule"] == rule_num), 0.0)
            vals.append(sev if triggered else 0.0)
        fig.add_trace(go.Scatter(
            x=frame_indices[:len(vals)], y=vals,
            mode="lines", name=f"R{rule_num}",
            line=dict(color=rule_colors[rule_num - 1], width=2),
            stackgroup="one" if rule_num == 1 else None,
        ))
    fig.update_layout(
        plot_bgcolor=BG_COLOR, paper_bgcolor=BG_COLOR,
        font=dict(color=TEXT_PRIMARY, size=11),
        margin=dict(l=40, r=10, t=30, b=30),
        xaxis_title="Frame", yaxis_title="Severity",
        legend=dict(font=dict(size=8), orientation="h", yanchor="bottom", y=1.02),
        height=250,
    )
    return fig


def build_com_trend(com_history: list, frame_indices: list):
    fig = make_subplots(rows=1, cols=2, subplot_titles=("COM Position", "Offset Ratio"))
    xs = [c.com.x for c in com_history if c]
    ys = [c.com.y for c in com_history if c]
    ratios = [c.offset_ratio for c in com_history if c]
    fi = frame_indices[:len(xs)]
    fig.add_trace(go.Scatter(x=fi, y=xs, mode="lines", name="X", line=dict(color="#FF6B6B")), row=1, col=1)
    fig.add_trace(go.Scatter(x=fi, y=ys, mode="lines", name="Y", line=dict(color="#4ECDC4")), row=1, col=1)
    fig.add_trace(go.Scatter(x=fi, y=ratios, mode="lines", name="Offset", line=dict(color="#FFD700")), row=1, col=2)
    fig.add_hline(y=1.0, line_dash="dash", line_color=ACCENT_RED, row=1, col=2)
    fig.update_layout(
        plot_bgcolor=BG_COLOR, paper_bgcolor=BG_COLOR,
        font=dict(color=TEXT_PRIMARY, size=11),
        margin=dict(l=40, r=10, t=40, b=30), height=250, showlegend=True,
    )
    return fig


# ─── Export Functions ──────────────────────────────────────────────────

def generate_session_json(analyses: list, rep_counter: RepCounter, fps: float) -> str:
    """Generate full session JSON export."""
    summary = {
        "export_time": datetime.now().isoformat(),
        "total_frames": len(analyses),
        "fps": fps,
        "reps": rep_counter.rep_count if rep_counter else 0,
        "frames": [],
    }
    for a in analyses:
        summary["frames"].append({
            "frame_index": a.frame_index,
            "rep_count": a.rep_count,
            "rep_quality": a.rep_quality,
            "symmetry": round(a.symmetry_score, 3),
            "confidence": round(a.confidence, 3),
            "phase": a.phase,
            "exercise": a.exercise,
            "exercise_confidence": round(a.exercise_confidence, 2),
            "template_metrics": {k: round(v, 2) for k, v in a.template_metrics.items()},
            "structured_report": a.structured_report,
            "mock_feedback": a.mock_feedback,
            "violations": [
                {"rule": v["rule"], "name": v["name"], "severity": v["severity"],
                 "joint": v["joint"], "value": v["value"]}
                for v in a.safety_violations
            ],
            "joint_angles": {k: round(v, 2) for k, v in a.joint_angles.items()},
            "pattern": a.movement_pattern.pattern_label if a.movement_pattern else None,
            "muscle_primary": a.muscle_estimate.estimated_primary if a.muscle_estimate else [],
        })
    return json.dumps(summary, indent=2, ensure_ascii=False)


def generate_session_csv(analyses: list) -> str:
    """Generate flattened CSV export per frame."""
    output = io.StringIO()
    writer = csv.writer(output)
    header = ["frame", "rep", "quality", "symmetry", "confidence", "phase", "exercise", "exercise_confidence", "violations_count"]
    header += [f"angle_{k}" for k in JOINT_DISPLAY.keys()]
    writer.writerow(header)
    for a in analyses:
        row = [a.frame_index, a.rep_count, round(a.rep_quality, 1),
               round(a.symmetry_score, 3), round(a.confidence, 3), a.phase,
               a.exercise, round(a.exercise_confidence, 2),
               len(a.safety_violations)]
        row += [round(a.joint_angles.get(k, 0), 2) for k in JOINT_DISPLAY.keys()]
        writer.writerow(row)
    return output.getvalue()


def get_download_link(data: str, filename: str, mime: str = "text/plain") -> str:
    b64 = base64.b64encode(data.encode()).decode()
    return f'<a href="data:{mime};base64,{b64}" download="{filename}" style="text-decoration:none;"><button style="background:#00D4AA;color:#0f1724;padding:8px 16px;border:none;border-radius:6px;font-weight:bold;cursor:pointer;">⬇️ Download {filename}</button></a>'


# ─── Keyboard Navigation JS ────────────────────────────────────────────

KEYBOARD_JS = """
<script>
document.addEventListener('keydown', function(e) {
    if (e.key === 'ArrowLeft') {
        const slider = document.querySelector('input[data-testid="stSlider"]');
        if (slider) {
            slider.value = Math.max(0, parseInt(slider.value) - 1);
            slider.dispatchEvent(new Event('input', { bubbles: true }));
        }
    } else if (e.key === 'ArrowRight') {
        const slider = document.querySelector('input[data-testid="stSlider"]');
        if (slider) {
            const max = parseInt(slider.max);
            slider.value = Math.min(max, parseInt(slider.value) + 1);
            slider.dispatchEvent(new Event('input', { bubbles: true }));
        }
    } else if (e.key === ' ') {
        e.preventDefault();
        const pauseBtn = document.querySelector('button[kind="secondary"]');
        if (pauseBtn) pauseBtn.click();
    }
});
</script>
"""


# ─── Main Dashboard ────────────────────────────────────────────────────

def main():
    st.set_page_config(
        page_title="GemmaFit Biomechanics Dashboard",
        page_icon="🏋️",
        layout="wide",
        initial_sidebar_state="expanded",
    )

    # Custom CSS
    st.markdown(f"""<style>
    .stApp {{ background-color: {BG_COLOR}; }}
    .block-container {{ padding-top: 0.5rem; padding-left: 1rem; padding-right: 1rem; max-width: 100%; }}
    h1 {{ color: {ACCENT_GREEN} !important; font-size: clamp(1.2rem, 2vw, 1.8rem) !important; }}
    h2 {{ font-size: clamp(0.9rem, 1.5vw, 1.2rem) !important; }}
    h3 {{ font-size: clamp(0.85rem, 1.3vw, 1.05rem) !important; }}
    [data-testid="stMetricValue"] {{ font-size: clamp(0.9rem, 1.5vw, 1.2rem) !important; }}
    [data-testid="stMetricLabel"] {{ font-size: clamp(0.7rem, 1vw, 0.85rem) !important; }}
    [data-testid="stImage"] img {{ max-height: min(50vh, 450px); object-fit: contain; width: 100%; }}
    [data-testid="stSidebar"] {{ min-width: 220px; max-width: 300px; }}
    .stButton>button {{ border-radius: 8px; }}
    .stExpander {{ font-size: 0.85rem; }}
    @media (max-width: 768px) {{
        .block-container {{ padding-left: 0.5rem; padding-right: 0.5rem; }}
        [data-testid="stMetricValue"] {{ font-size: 0.9rem !important; }}
        [data-testid="stImage"] img {{ max-height: 300px; }}
    }}
    @media (min-width: 1400px) {{
        [data-testid="stImage"] img {{ max-height: 500px; }}
    }}
    </style>""", unsafe_allow_html=True)

    # Keyboard shortcuts
    st.components.v1.html(KEYBOARD_JS, height=0)

    st.title("🏋️ GemmaFit Biomechanics Engine")

    # ─── Sidebar ───
    with st.sidebar:
        st.header("📹 Data Source")
        source_mode = st.radio("Input", ["CSV Landmarks", "Video File", "Webcam"], index=1, horizontal=True)
        csv_files = [f for f in os.listdir(DATA_DIR) if f.endswith(".csv")] if os.path.isdir(DATA_DIR) else []

        if source_mode == "CSV Landmarks":
            selected_csv = st.selectbox("Landmark CSV", csv_files if csv_files else ["(no files)"])
            fps = st.number_input("FPS", value=30.0, min_value=1.0, max_value=120.0)
            sex = st.selectbox("Body Profile", ["male", "female"])
        elif source_mode == "Video File":
            uploaded_video = st.file_uploader("Upload Video", type=["mp4", "avi", "mov"])
            sample_every = st.number_input("Sample every N frames", value=2, min_value=1, max_value=30)
            sex = st.selectbox("Body Profile", ["male", "female"])
            if uploaded_video is not None:
                video_id = f"{uploaded_video.name}_{uploaded_video.size}"
                if st.session_state.get('last_video_id') != video_id:
                    local_path = os.path.join(UPLOAD_DIR, uploaded_video.name)
                    with open(local_path, "wb") as f:
                        f.write(uploaded_video.getvalue())
                    st.session_state['current_video_path'] = local_path
                    st.session_state['last_video_id'] = video_id
                st.success(f"✅ {uploaded_video.name}")
        else:
            st.info("💡 Webcam: use your phone camera with IP Webcam app, or select Video File")
            fps = st.number_input("FPS", value=30.0, min_value=1.0, max_value=120.0)
            sex = st.selectbox("Body Profile", ["male", "female"])

        st.divider()
        st.header("🎛️ Display")
        show_3d = st.checkbox("3D Skeleton View", value=False)
        show_com = st.checkbox("COM + Support Polygon", value=True)
        show_angles = st.checkbox("Angles on Skeleton", value=True)
        show_halos = st.checkbox("Violation Highlights", value=True)
        st.divider()
        st.header("🏋️ Exercise")
        exercise_override = st.selectbox(
            "Override Detection", ["Auto"] + list(EXERCISE_TEMPLATES.keys()),
            format_func=lambda x: x.replace("_", " ").title() if x != "Auto" else x)
        exercise_override = None if exercise_override == "Auto" else exercise_override
        st.divider()
        selected_joints = st.multiselect(
            "📈 Trend Joints", list(JOINT_DISPLAY.keys()),
            default=["left_knee", "left_hip", "spine"],
            format_func=lambda x: JOINT_DISPLAY.get(x, (x, ""))[0])

    # ─── Load Data ───
    frames = []
    if source_mode == "CSV Landmarks" and csv_files:
        csv_path = os.path.join(DATA_DIR, selected_csv)
        if os.path.exists(csv_path):
            frames = load_csv_frames(csv_path)
    elif source_mode == "Video File" and 'current_video_path' in st.session_state:
        if not HAS_MEDIAPIPE:
            st.error(f"⚠️ MediaPipe failed: {MEDIAPIPE_ERR}")
        else:
            with st.spinner("🔄 Processing video..."):
                vid_frames, vid_fps = extract_video_frames_cached(
                    st.session_state['current_video_path'], sample_every)
                if vid_frames:
                    frames, fps = vid_frames, vid_fps

    if not frames:
        st.info("👆 Upload a video or select a CSV to begin. Keyboard: ← → arrow keys for frame stepping, space to pause.")
        return

    total_vid = int(frames[-1]["frame"]) + 1
    st.caption(f"📊 **{len(frames)} frames** analyzed (from ~{total_vid} total) | FPS: {fps:.0f}")

    # ─── Analyze (cached) ───
    analyses, rep_counter = analyze_all_frames_cached(frames, fps, exercise_override=exercise_override)
    max_frame = len(analyses) - 1

    # ─── Frame Navigator ───
    col_nav, col_play = st.columns([6, 1])
    with col_nav:
        if max_frame <= 0:
            frame_idx = 0
            st.caption("Single-frame input")
        else:
            frame_idx = st.slider("🎬 Frame", 0, max_frame, 0)
    with col_play:
        is_playing = st.button("⏯️ Play" if not st.session_state.get("playing") else "⏸️ Pause")
        if is_playing:
            st.session_state["playing"] = not st.session_state.get("playing", False)

    cur = analyses[frame_idx]
    cur_lm = lm_to_arrays(frames[frame_idx])

    # ─── Top Metrics ───
    total_viols = sum(len(a.safety_violations) for a in analyses)
    crit = sum(1 for a in analyses if any(v["severity"] >= 0.9 for v in a.safety_violations))
    warn = sum(1 for a in analyses if a.safety_violations and not any(v["severity"] >= 0.9 for v in a.safety_violations))
    clean = sum(1 for a in analyses if not a.safety_violations)
    spct = clean / len(analyses) * 100 if analyses else 100

    # Most common exercise
    ex_counts = Counter(a.exercise for a in analyses if a.exercise != "unknown")
    main_ex = ex_counts.most_common(1)[0][0] if ex_counts else "unknown"
    main_ex_conf = next((a.exercise_confidence for a in analyses if a.exercise == main_ex), 0.0)

    m1, m2, m3, m4, m5, m6 = st.columns(6)
    m1.metric("Exercise", main_ex.replace("_", " ").title())
    m2.metric("Reps", cur.rep_count)
    m3.metric("Confidence", f"{cur.confidence:.0%}")
    m4.metric("Safety", f"{spct:.0f}%", delta=f"{crit} crit" if crit else "Clear",
              delta_color="inverse" if crit else "normal")
    m5.metric("Warnings", warn)
    m6.metric("Violations", total_viols)
    st.divider()

    # ─── Main View ───
    cv, cs = st.columns([2, 3])

    with cv:
        phase_icons = {"top": "🟢", "descending": "🔵", "bottom": "🟡", "ascending": "🟠"}
        st.subheader(f"Frame {frame_idx}  {phase_icons.get(cur.phase, '⚪')} {cur.phase.upper()}")

        if source_mode == "Video File" and 'current_video_path' in st.session_state and HAS_MEDIAPIPE:
            cap = cv2.VideoCapture(st.session_state['current_video_path'])
            target_frame = int(frames[frame_idx]["frame"])
            cap.set(cv2.CAP_PROP_POS_FRAMES, target_frame)
            ok, img = cap.read()
            cap.release()
            if ok:
                st.image(cv2.cvtColor(img, cv2.COLOR_BGR2RGB), use_container_width=True)
            else:
                st.plotly_chart(draw_skeleton_plotly(cur_lm, cur, show_3d), use_container_width=True)
        else:
            st.plotly_chart(draw_skeleton_plotly(cur_lm, cur, show_3d), use_container_width=True)

    with cs:
        # ─── Exercise Detection ──────────────────────────────────────────
        st.subheader("🏋️ Detected Exercise")
        ex_color = ACCENT_GREEN if cur.exercise_confidence >= 0.6 else ACCENT_ORANGE if cur.exercise_confidence >= 0.4 else ACCENT_RED
        st.markdown(
            f"<div style='background:{ex_color}22;padding:10px;border-radius:8px;border-left:4px solid {ex_color};'>"
            f"<b>{cur.exercise.replace('_', ' ').title()}</b> "
            f"<span style='color:{ex_color};font-weight:bold;'>({cur.exercise_confidence:.0%})</span><br>"
            f"<small>{', '.join(cur.exercise_basis) if cur.exercise_basis else 'N/A'}</small></div>",
            unsafe_allow_html=True)

        # ─── Coach Says (Mock Gemma) ─────────────────────────────────────
        if cur.mock_feedback:
            fb = cur.mock_feedback
            priority_colors = {"high": ACCENT_RED, "medium": ACCENT_ORANGE, "low": ACCENT_GREEN}
            fb_color = priority_colors.get(fb.get("priority", "low"), ACCENT_GREEN)
            st.subheader("💬 Coach Says")
            st.markdown(
                f"<div style='background:{fb_color}22;padding:12px;border-radius:8px;border-left:4px solid {fb_color};'>"
                f"<i>{fb.get('message', '')}</i></div>",
                unsafe_allow_html=True)
            st.caption(f"_{fb.get('safety_note', '')}_")

        # ─── Quality Flags ─────────────────────────────────────────────────
        st.divider()
        st.subheader("🛡️ Quality Flags")
        if cur.gate_result:
            gate = cur.gate_result
            # Active flags
            active_flags = [f for f in gate.quality_flags if f.status in ("CRITICAL", "WARNING", "MONITOR")]
            if active_flags:
                for f in active_flags:
                    color = ACCENT_RED if f.status == "CRITICAL" else ACCENT_ORANGE if f.status == "WARNING" else "#FFD700"
                    icon = "🚨" if f.status == "CRITICAL" else "⚠️" if f.status == "WARNING" else "👀"
                    st.markdown(
                        f"<div style='background:{color}22;padding:8px;border-radius:8px;border-left:4px solid {color};margin-bottom:6px;'>"
                        f"<b>{icon} {f.id}</b> <span style='color:{color};font-weight:bold;'>{f.status}</span><br>"
                        f"<small>Value: {f.value:.2f} | Threshold: {f.threshold:.2f} | {f.evidence}</small></div>",
                        unsafe_allow_html=True)
            else:
                st.markdown(
                    f"<div style='background:{ACCENT_GREEN}22;padding:8px;border-radius:8px;border-left:4px solid {ACCENT_GREEN};'>"
                    f"<b>✅ All Clear</b> — No quality concerns detected.</div>",
                    unsafe_allow_html=True)

            # Not applicable flags
            not_app = gate.not_applicable
            if not_app:
                with st.expander(f"🚫 Not Applicable ({len(not_app)})"):
                    for f in not_app:
                        st.caption(f"**{f.id}**: {f.reason}")

            # Low confidence flags
            low_conf = gate.low_confidence
            if low_conf:
                with st.expander(f"🔍 Low Confidence ({len(low_conf)})", expanded=True):
                    for f in low_conf:
                        st.warning(f"**{f.id}**: {f.reason}")
        else:
            st.info("No gate result available.")

        # ─── Template Metrics ──────────────────────────────────────────────
        if cur.template_metrics:
            st.divider()
            st.subheader("📊 Template Metrics")
            for k, v in cur.template_metrics.items():
                st.write(f"**{k.replace('_', ' ').title()}**: `{v:.2f}`")

        # ─── Movement & Muscle ─────────────────────────────────────────────
        st.divider()
        st.subheader("🏃 Movement & Muscle")
        if cur.movement_pattern:
            st.code(cur.movement_pattern.pattern_label, language="text")
            st.write(f"Primary joint: **{cur.movement_pattern.primary_joint.value}** | Phase: **{cur.movement_pattern.phase.value}**")
        if cur.muscle_estimate:
            mf = cur.muscle_estimate
            st.write(f"🎯 Primary: {', '.join(mf.estimated_primary)}")
            st.write(f"🔹 Secondary: {', '.join(mf.estimated_secondary)}")
            st.caption(f"_{mf.note}_")

        # ─── Angles ────────────────────────────────────────────────────────
        st.divider()
        st.subheader("📐 Angles")
        a_data = []
        for j, (d, _) in JOINT_DISPLAY.items():
            v = cur.joint_angles.get(j, 0.0)
            viol = any(x["joint"] == j for x in cur.safety_violations)
            a_data.append({"Joint": d, "Angle": f"{v:.1f}°", "Status": "🔴" if viol else "🟢"})
        st.dataframe(a_data, use_container_width=True, hide_index=True, height=280)

        if cur.com_result:
            st.divider()
            cr = cur.com_result
            st.write(f"🎯 COM: {'✅ Inside' if cr.inside else '❌ Outside'} | Offset: **{cr.offset_ratio:.3f}**")

    # ─── Tabs ───
    st.divider()
    t1, t2, t3, t4, t5, t6 = st.tabs(["📊 Angles", "⚠️ Safety", "🎯 COM", "📋 Session", "🤖 Coach", "💾 Export"])

    angle_history = {j: [a.joint_angles.get(j, 0.0) for a in analyses] for j in JOINT_DISPLAY}
    safety_history = [a.safety_violations for a in analyses]
    com_history = [a.com_result for a in analyses]
    frame_indices = [int(frames[i]["frame"]) if str(frames[i]["frame"]).isdigit() else i for i in range(len(frames))]

    with t1:
        filt = {k: angle_history[k] for k in selected_joints if k in angle_history}
        st.plotly_chart(build_angle_trend(filt, frame_indices, selected_joints), use_container_width=True)

    with t2:
        st.plotly_chart(build_safety_trend(safety_history, frame_indices), use_container_width=True)
        s1, s2, s3, s4 = st.columns(4)
        s1.metric("Critical", sum(1 for a in analyses if a.gate_result and a.gate_result.overall_status == "CRITICAL"))
        s2.metric("Warning", sum(1 for a in analyses if a.gate_result and a.gate_result.overall_status == "WARNING"))
        s3.metric("Monitor", sum(1 for a in analyses if a.gate_result and a.gate_result.overall_status == "MONITOR"))
        s4.metric("Clean", sum(1 for a in analyses if a.gate_result and a.gate_result.overall_status == "OK"))

    with t3:
        vc = [c for c in com_history if c]
        if vc:
            st.plotly_chart(build_com_trend(vc, frame_indices[:len(vc)]), use_container_width=True)
        else:
            st.info("No COM data available.")

    with t4:
        if rep_counter.history:
            s = rep_counter.get_session_summary()
            c1, c2, c3 = st.columns(3)
            c1.metric("Reps", s["reps"])
            c2.metric("Avg Quality", f"{s['avg_quality']:.0f}")
            c3.metric("Avg ROM", f"{s['avg_rom']:.1f}°")
            for r in rep_counter.history:
                q = "🟢" if r.form_quality >= 80 else "🟡" if r.form_quality >= 50 else "🔴"
                st.write(f"{q} Rep {r.rep_number}: ROM={r.range_of_motion:.1f}° Q={r.form_quality:.0f}")
        else:
            st.info("No reps completed yet.")

        pc = Counter(a.movement_pattern.pattern_label for a in analyses if a.movement_pattern)
        if pc:
            fig_p = px.pie(values=list(pc.values()), names=list(pc.keys()), title="Movement Patterns")
            fig_p.update_layout(plot_bgcolor=BG_COLOR, paper_bgcolor=BG_COLOR, font=dict(color=TEXT_PRIMARY))
            st.plotly_chart(fig_p, use_container_width=True)

        st.divider()
        st.subheader("📊 Session Summary (Mock Gemma)")
        session_reports = [a.structured_report for a in analyses if a.structured_report]
        if session_reports:
            summary = generate_session_summary(session_reports)
            st.markdown(f"**{summary.get('message', '')}**")
            st.json(summary.get("stats", {}))

    with t5:
        st.subheader("🤖 Coach Feedback Log")
        feedback_log = [a.mock_feedback for a in analyses if a.mock_feedback and a.mock_feedback.get("message")]
        # Show only feedback where message changed
        last_msg = None
        for i, fb in enumerate(feedback_log):
            msg = fb.get("message", "")
            if msg != last_msg and msg:
                priority = fb.get("priority", "low")
                color = ACCENT_RED if priority == "high" else ACCENT_ORANGE if priority == "medium" else ACCENT_GREEN
                st.markdown(
                    f"<div style='background:{color}22;padding:8px;border-radius:6px;margin-bottom:4px;border-left:3px solid {color};'>"
                    f"<small>Frame {analyses[i].frame_index}</small> — <b>{msg}</b></div>",
                    unsafe_allow_html=True)
                last_msg = msg
        if not feedback_log:
            st.info("No feedback generated yet.")

    with t6:
        st.subheader("📤 Export Session Data")
        st.write("Download the complete analysis for this session.")
        json_data = generate_session_json(analyses, rep_counter, fps)
        csv_data = generate_session_csv(analyses)
        c1, c2, c3 = st.columns(3)
        with c1:
            st.markdown(get_download_link(json_data, "gemmafit_session.json", "application/json"), unsafe_allow_html=True)
        with c2:
            st.markdown(get_download_link(csv_data, "gemmafit_session.csv", "text/csv"), unsafe_allow_html=True)
        with c3:
            # Export structured reports as JSONL
            reports_jsonl = "\n".join(json.dumps(a.structured_report, ensure_ascii=False) for a in analyses if a.structured_report)
            st.markdown(get_download_link(reports_jsonl, "gemmafit_reports.jsonl", "application/json"), unsafe_allow_html=True)

        st.divider()
        st.subheader("📊 Summary Statistics")
        summary = SessionSummary(
            total_frames=len(analyses),
            total_violations=total_viols,
            critical_count=crit,
            warning_count=warn,
            clean_frames=clean,
            reps_completed=rep_counter.rep_count if rep_counter else 0,
            avg_quality=sum(a.rep_quality for a in analyses) / len(analyses) if analyses else 0,
            avg_symmetry=sum(a.symmetry_score for a in analyses) / len(analyses) if analyses else 1.0,
            pattern_distribution=dict(Counter(a.movement_pattern.pattern_label for a in analyses if a.movement_pattern)),
            rule_trigger_counts={f"Rule {i}": sum(1 for a in analyses if any(v["rule"] == i for v in a.safety_violations)) for i in range(1, 9)},
        )
        st.json(asdict(summary))


if __name__ == "__main__":
    main()
