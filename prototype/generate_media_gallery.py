"""
generate_media_gallery.py — Generate competition media assets from dashboard analysis.

Creates:
  1. Cover image (1920×1080) — YouTube/Media Gallery
  2. Social card (1200×630) — Twitter/LinkedIn
  3. App screenshot mockup (1080×1920) — Phone portrait

Usage:
    python prototype/generate_media_gallery.py
"""

import sys
import os
import math

proto_dir = os.path.dirname(os.path.abspath(__file__))
if proto_dir not in sys.path:
    sys.path.insert(0, proto_dir)

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Circle, FancyArrowPatch
from matplotlib.collections import LineCollection

from test_dashboard import (
    extract_video_frames_cached, analyze_all_frames_cached,
    lm_to_arrays, draw_skeleton_plotly,
)
from exercise_templates import EXERCISE_TEMPLATES, METRIC_EXTRACTORS, detect_exercise
from applicability_gate import ApplicabilityGate, generate_structured_report
from mock_gemma_feedback import generate_mock_feedback

ASSETS_DIR = os.path.join(os.path.dirname(proto_dir), "docs", "assets")
os.makedirs(ASSETS_DIR, exist_ok=True)

# ─── Color Palette (matches dashboard) ──────────────────────────────────
BG_COLOR = "#0f1724"
CARD_BG = "#1a1a2e"
ACCENT_GREEN = "#00D4AA"
ACCENT_RED = "#FF4444"
ACCENT_ORANGE = "#FFA500"
ACCENT_BLUE = "#4ECDC4"
TEXT_PRIMARY = "#e0e0e0"
TEXT_SECONDARY = "#a0a0a0"

# ─── Landmark connections for matplotlib ────────────────────────────────
SKELETON_CONNECTIONS = [
    ("left_shoulder", "right_shoulder"),
    ("left_shoulder", "left_elbow"), ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"), ("right_elbow", "right_wrist"),
    ("left_shoulder", "left_hip"), ("right_shoulder", "right_hip"),
    ("left_hip", "right_hip"),
    ("left_hip", "left_knee"), ("left_knee", "left_ankle"),
    ("right_hip", "right_knee"), ("right_knee", "right_ankle"),
]

LANDMARK_IDX = {
    "left_shoulder": 11, "right_shoulder": 12,
    "left_elbow": 13, "right_elbow": 14,
    "left_wrist": 15, "right_wrist": 16,
    "left_hip": 23, "right_hip": 24,
    "left_knee": 25, "right_knee": 26,
    "left_ankle": 27, "right_ankle": 28,
}


def draw_skeleton_matplotlib(ax, lm_arr, analysis, show_angles=True, show_violations=True):
    """Draw skeleton with violations highlighted on matplotlib axes."""
    
    # Draw connections
    for a_name, b_name in SKELETON_CONNECTIONS:
        idx_a = LANDMARK_IDX[a_name]
        idx_b = LANDMARK_IDX[b_name]
        
        # Check if either joint has a violation
        violated = False
        if show_violations and analysis.safety_violations:
            for v in analysis.safety_violations:
                v_joint = v.get("joint", "")
                if v_joint in a_name or v_joint in b_name:
                    violated = True
                    break
        
        color = ACCENT_RED if violated else ACCENT_BLUE
        lw = 4 if violated else 2.5
        
        ax.plot([lm_arr[idx_a, 0], lm_arr[idx_b, 0]],
                [lm_arr[idx_a, 1], lm_arr[idx_b, 1]],
                color=color, linewidth=lw, zorder=2)
    
    # Draw joints
    joint_display = {
        "left_knee": "L.K", "right_knee": "R.K",
        "left_hip": "L.H", "right_hip": "R.H",
        "left_elbow": "L.E", "right_elbow": "R.E",
        "left_shoulder": "L.S", "right_shoulder": "R.S",
    }
    
    for jname, idx in LANDMARK_IDX.items():
        x, y = lm_arr[idx, 0], lm_arr[idx, 1]
        
        violated = show_violations and any(v.get("joint", "") == jname for v in analysis.safety_violations)
        color = ACCENT_RED if violated else ACCENT_GREEN
        
        circle = Circle((x, y), 0.018, color=color, zorder=3, ec="white", linewidth=1.5)
        ax.add_patch(circle)
        
        if show_angles and jname in joint_display:
            angle_val = analysis.joint_angles.get(jname)
            if angle_val:
                ax.text(x, y - 0.04, f"{angle_val:.0f}°", 
                       ha="center", va="top", fontsize=7, color="white",
                       fontweight="bold", zorder=4)
    
    # Draw violation halos
    if show_violations:
        for v in analysis.safety_violations:
            jname = v.get("joint", "")
            if jname in LANDMARK_IDX:
                idx = LANDMARK_IDX[jname]
                x, y = lm_arr[idx, 0], lm_arr[idx, 1]
                halo = Circle((x, y), 0.045, color=ACCENT_RED, alpha=0.15, zorder=1)
                ax.add_patch(halo)
    
    ax.set_xlim(0.2, 0.8)
    ax.set_ylim(0.9, 0.1)
    ax.set_aspect('equal')
    ax.axis("off")
    ax.set_facecolor(BG_COLOR)


def draw_quality_panel(ax, analysis):
    """Draw quality flags panel on matplotlib axes."""
    ax.set_facecolor(CARD_BG)
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.axis("off")
    
    if not analysis.gate_result:
        ax.text(0.5, 0.5, "No gate data", ha="center", va="center", color=TEXT_SECONDARY, fontsize=10)
        return
    
    gate = analysis.gate_result
    y_pos = 0.92
    
    # Title
    ax.text(0.05, y_pos, "[ Quality Flags ]", fontsize=11, fontweight="bold", color=TEXT_PRIMARY, va="top")
    y_pos -= 0.12
    
    # Active flags
    active = [f for f in gate.quality_flags if f.status in ("CRITICAL", "WARNING", "MONITOR")]
    for f in active[:4]:
        color = ACCENT_RED if f.status == "CRITICAL" else ACCENT_ORANGE if f.status == "WARNING" else "#FFD700"
        icon = "[!]" if f.status == "CRITICAL" else "[/]" if f.status == "WARNING" else "[i]"
        
        rect = FancyBboxPatch((0.05, y_pos - 0.05), 0.9, 0.08, 
                             boxstyle="round,pad=0.01,rounding_size=0.02",
                             facecolor=color, alpha=0.15, edgecolor=color, linewidth=2)
        ax.add_patch(rect)
        ax.text(0.08, y_pos - 0.01, f"{icon} {f.id}", fontsize=8, color=TEXT_PRIMARY, va="top", fontweight="bold")
        ax.text(0.92, y_pos - 0.01, f.status, fontsize=7, color=color, va="top", ha="right", fontweight="bold")
        y_pos -= 0.11
    
    if not active:
        ax.text(0.5, y_pos - 0.05, "[OK] All Clear", fontsize=10, color=ACCENT_GREEN, ha="center", va="top")
    
    # Exercise badge
    y_pos -= 0.08
    badge_color = ACCENT_GREEN if analysis.exercise_confidence >= 0.6 else ACCENT_ORANGE
    rect = FancyBboxPatch((0.05, y_pos - 0.04), 0.9, 0.06,
                         boxstyle="round,pad=0.01,rounding_size=0.02",
                         facecolor=badge_color, alpha=0.2, edgecolor=badge_color, linewidth=2)
    ax.add_patch(rect)
    ex_label = analysis.exercise.replace("_", " ").title()
    ax.text(0.5, y_pos - 0.01, f"[LIFT] {ex_label} ({analysis.exercise_confidence:.0%})", 
           fontsize=9, color=TEXT_PRIMARY, ha="center", va="top", fontweight="bold")


def draw_coach_bubble(ax, analysis):
    """Draw coach feedback bubble."""
    ax.set_facecolor("none")
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    ax.axis("off")
    
    if not analysis.mock_feedback:
        return
    
    fb = analysis.mock_feedback
    msg = fb.get("message", "")
    priority = fb.get("priority", "low")
    
    color = ACCENT_RED if priority == "high" else ACCENT_ORANGE if priority == "medium" else ACCENT_GREEN
    
    # Bubble background
    rect = FancyBboxPatch((0.05, 0.15), 0.9, 0.7,
                         boxstyle="round,pad=0.02,rounding_size=0.05",
                         facecolor=color, alpha=0.12, edgecolor=color, linewidth=2)
    ax.add_patch(rect)
    
    ax.text(0.5, 0.72, "[COACH] Coach Says", fontsize=10, color=TEXT_PRIMARY, ha="center", va="top", fontweight="bold")
    
    # Word wrap message
    words = msg.split()
    lines = []
    line = ""
    for w in words:
        if len(line) + len(w) < 35:
            line += w + " "
        else:
            lines.append(line.strip())
            line = w + " "
    if line:
        lines.append(line.strip())
    
    y = 0.58
    for line in lines:
        ax.text(0.5, y, line, fontsize=8, color=TEXT_PRIMARY, ha="center", va="top")
        y -= 0.12
    
    ax.text(0.5, 0.18, "Pose-based feedback, not medical diagnosis", 
           fontsize=6, color=TEXT_SECONDARY, ha="center", va="top", style="italic")


def generate_cover_image(analysis, lm_arr, output_path, size=(1920, 1080)):
    """Generate a competition cover image."""
    fig = plt.figure(figsize=(size[0] / 100, size[1] / 100), dpi=100, facecolor=BG_COLOR)
    
    # Layout: left 55% = skeleton, right 45% = panels
    gs = fig.add_gridspec(2, 2, left=0.04, right=0.96, top=0.92, bottom=0.08,
                          width_ratios=[1.1, 1], height_ratios=[1.2, 1],
                          hspace=0.15, wspace=0.1)
    
    # ─── Title banner ──────────────────────────────────────────────
    fig.text(0.5, 0.97, "GemmaFit", fontsize=48, color=ACCENT_GREEN, 
            ha="center", va="top", fontweight="bold", 
            fontfamily="sans-serif")
    fig.text(0.5, 0.93, "Explainable Multi-Exercise Motion Coach  ·  Pixel 8 Pro Offline",
            fontsize=14, color=TEXT_SECONDARY, ha="center", va="top")
    
    # ─── Skeleton panel ────────────────────────────────────────────
    ax_skeleton = fig.add_subplot(gs[:, 0])
    draw_skeleton_matplotlib(ax_skeleton, lm_arr, analysis, show_angles=True, show_violations=True)
    ax_skeleton.set_title("Real-time Biomechanical Analysis", fontsize=12, color=TEXT_PRIMARY, pad=10)
    
    # ─── Quality flags panel ───────────────────────────────────────
    ax_flags = fig.add_subplot(gs[0, 1])
    draw_quality_panel(ax_flags, analysis)
    
    # ─── Coach bubble panel ────────────────────────────────────────
    ax_coach = fig.add_subplot(gs[1, 1])
    draw_coach_bubble(ax_coach, analysis)
    
    # ─── Feature badges at bottom ──────────────────────────────────
    badges = [
        ("[8]", "8 Biomechanical Rules", "Template-gated applicability"),
        ("[AI]", "Gemma 4 Edge", "Function-calling coaching"),
        ("[MOBILE]", "Pixel 8 Pro", "Fully offline inference"),
    ]
    
    badge_x_start = 0.08
    badge_y = 0.04
    badge_spacing = 0.28
    
    for i, (icon, title, subtitle) in enumerate(badges):
        x = badge_x_start + i * badge_spacing
        fig.text(x, badge_y + 0.015, f"{icon} {title}", fontsize=10, color=TEXT_PRIMARY, 
                ha="left", va="center", fontweight="bold")
        fig.text(x, badge_y - 0.015, subtitle, fontsize=8, color=TEXT_SECONDARY, 
                ha="left", va="center")
    
    plt.savefig(output_path, dpi=100, facecolor=BG_COLOR, edgecolor="none", 
                bbox_inches="tight", pad_inches=0.1)
    plt.close()
    print(f"Saved: {output_path}")


def generate_phone_mockup(analysis, lm_arr, output_path, size=(1080, 1920)):
    """Generate a phone portrait mockup."""
    fig = plt.figure(figsize=(size[0] / 100, size[1] / 100), dpi=100, facecolor=BG_COLOR)
    
    # Phone frame
    phone_margin = 0.08
    phone_rect = FancyBboxPatch((phone_margin, 0.05), 1 - 2*phone_margin, 0.9,
                               boxstyle="round,pad=0.01,rounding_size=0.03",
                               facecolor=CARD_BG, edgecolor="#333", linewidth=3)
    fig.patches.append(phone_rect)
    
    # Title
    fig.text(0.5, 0.96, "GemmaFit", fontsize=36, color=ACCENT_GREEN,
            ha="center", va="top", fontweight="bold")
    fig.text(0.5, 0.93, "AI Motion Coach", fontsize=14, color=TEXT_SECONDARY,
            ha="center", va="top")
    
    # Skeleton area (upper half)
    ax_skeleton = fig.add_axes([0.15, 0.45, 0.7, 0.42])
    draw_skeleton_matplotlib(ax_skeleton, lm_arr, analysis, show_angles=True, show_violations=True)
    
    # Coach bubble
    ax_coach = fig.add_axes([0.12, 0.28, 0.76, 0.14])
    draw_coach_bubble(ax_coach, analysis)
    
    # Quality panel
    ax_flags = fig.add_axes([0.12, 0.08, 0.76, 0.18])
    draw_quality_panel(ax_flags, analysis)
    
    plt.savefig(output_path, dpi=100, facecolor=BG_COLOR, edgecolor="none",
                bbox_inches="tight", pad_inches=0.05)
    plt.close()
    print(f"Saved: {output_path}")


def find_best_frame_for_cover(video_path, exercise_override="squat", sample_every=3):
    """Find a frame with CRITICAL or WARNING flags for visual impact."""
    frames, fps = extract_video_frames_cached(video_path, sample_every)
    if not frames:
        return None, None, None
    
    analyses, _ = analyze_all_frames_cached(frames, fps, exercise_override=exercise_override)
    
    # Find frame with most severe flag
    best_idx = 0
    best_score = 0
    
    for i, a in enumerate(analyses):
        score = 0
        if a.gate_result:
            for f in a.gate_result.quality_flags:
                if f.status == "CRITICAL":
                    score += 3
                elif f.status == "WARNING":
                    score += 2
                elif f.status == "MONITOR":
                    score += 1
        
        # Prefer frames with violations but not too many (cleaner visual)
        if 1 <= score <= 5 and score > best_score:
            best_score = score
            best_idx = i
    
    return analyses[best_idx], lm_to_arrays(frames[best_idx]), frames[best_idx]


def main():
    print("GemmaFit Media Gallery Asset Generator")
    print("=" * 50)
    
    video_path = os.path.join(os.path.dirname(proto_dir), "test_assets", "videos", "squat_wikimedia_01.webm")
    
    if not os.path.exists(video_path):
        print(f"[ERROR] Video not found: {video_path}")
        return
    
    print("Finding best frame for cover...")
    analysis, lm_arr, frame_data = find_best_frame_for_cover(video_path, exercise_override="squat", sample_every=3)
    
    if analysis is None:
        print("[ERROR] Could not analyze video")
        return
    
    print(f"Selected frame {analysis.frame_index} with {len(analysis.safety_violations)} violations")
    print(f"Exercise: {analysis.exercise} ({analysis.exercise_confidence:.0%})")
    print(f"Coach: {analysis.mock_feedback.get('message', 'N/A')}")
    
    # Generate cover image
    cover_path = os.path.join(ASSETS_DIR, "gemmafit_cover_1920x1080.png")
    print("\nGenerating cover image (1920×1080)...")
    generate_cover_image(analysis, lm_arr, cover_path, size=(1920, 1080))
    
    # Generate social card
    social_path = os.path.join(ASSETS_DIR, "gemmafit_social_1200x630.png")
    print("\nGenerating social card (1200×630)...")
    generate_cover_image(analysis, lm_arr, social_path, size=(1200, 630))
    
    # Generate phone mockup
    phone_path = os.path.join(ASSETS_DIR, "gemmafit_phone_1080x1920.png")
    print("\nGenerating phone mockup (1080×1920)...")
    generate_phone_mockup(analysis, lm_arr, phone_path, size=(1080, 1920))
    
    # Generate a second variant with deadlift
    deadlift_path = os.path.join(os.path.dirname(proto_dir), "test_assets", "videos", "deadlift_demo.webm")
    if os.path.exists(deadlift_path):
        print("\nGenerating deadlift variant...")
        dl_analysis, dl_lm, _ = find_best_frame_for_cover(deadlift_path, exercise_override="deadlift", sample_every=3)
        if dl_analysis:
            dl_cover = os.path.join(ASSETS_DIR, "gemmafit_cover_deadlift_1920x1080.png")
            generate_cover_image(dl_analysis, dl_lm, dl_cover, size=(1920, 1080))
    
    print("\n" + "=" * 50)
    print("All media assets generated!")
    print(f"Output directory: {ASSETS_DIR}")


if __name__ == "__main__":
    main()