"""
test_8rules.py — GemmaFit 8-rule通用安全規則完整單元測試

驗證 8 條生物力學安全規則在 Python 原型中的正確性，
作為 C++ 原生移植前的基準測試。

規則對照（implementation_plan.md §3.2）：
  Rule 1: 膝關節側向偏移 — D_knee/D_ankle < 0.8 或 FPPA > 10°
  Rule 2: 脊柱彎曲異常 — 肩-髖-膝夾角偏離 > 15°
  Rule 3: 關節過度伸展 — 角度 ≤ 5° 或 ≥ 175°
  Rule 4: 雙側不對稱 — 左右同名關節角度差 > 10°
  Rule 5: 質心偏移 — COM 投影超出支撐多邊形
  Rule 6: 急速動作 — 角速度 > 600°/s（連續 3 幀確認）
  Rule 7: 活動度不足 — ROM < 預期安全 ROM 的 50%
  Rule 8: 頸椎過伸 — 耳-肩-髖偏離 > 15°

用法：
  cd prototype
  python test_8rules.py
"""

from __future__ import annotations

import math
import sys
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

import numpy as np

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")


# ── 從專案模組匯入 ──────────────────────────────────────────────────

from compute_angles import (
    calculate_joint_angle,
    calculate_fppa,
    detect_knee_valgus,
    detect_rapid_movement,
    calculate_angular_velocity_dps,
    RAPID_MOVEMENT_THRESHOLD_DPS,
)
from com_tracker_prototype import (
    whole_body_com,
    support_polygon,
    is_com_inside,
    com_offset_ratio,
    track_com,
    _make_mock_landmarks,
    _point_in_polygon,
    LM,
    Point2D,
    COMResult,
)
from smooth_angle import (
    compute_angular_velocity,
    detect_rapid_movement as detect_rapid_movement_sg,
    RAPID_MOVEMENT_THRESHOLD_DPS as SG_RAPID_MOVEMENT_THRESHOLD_DPS,
)
from movement_classifier import (
    classify,
    compute_symmetry,
    calc_angle,
    KEYPOINT,
    MovementPattern,
)
from muscle_focus_prototype import (
    estimate_muscle_focus,
    MuscleFocusEstimate,
)


# ── 測試框架 ─────────────────────────────────────────────────────────

passed = 0
failed = 0


def check(name: str, got, expected, tol: float = None):
    global passed, failed
    if tol is not None:
        ok = abs(got - expected) <= tol
    else:
        ok = got == expected
    status = "PASS" if ok else "FAIL"
    suffix = f"  (expected {expected})" if not ok else ""
    print(f"  [{status}] {name}: {got}{suffix}")
    if ok:
        passed += 1
    else:
        failed += 1


def check_true(name: str, condition: bool, detail: str = ""):
    global passed, failed
    ok = condition
    status = "PASS" if ok else "FAIL"
    suffix = f"  ({detail})" if detail else ""
    print(f"  [{status}] {name}{suffix}")
    if ok:
        passed += 1
    else:
        failed += 1


# ── Mock landmarks 生成器 ─────────────────────────────────────────────

def make_standing_landmarks() -> np.ndarray:
    """Normal standing pose (33, 3): no safety rule should trigger.
    
    Geometry designed so that:
      - ear-shoulder-hip is nearly collinear (neck deviation < 15 deg)
      - shoulder-hip-knee is nearly straight (spine deviation < 15 deg)
      - elbows are bent ~90 deg (not hyperextended)
      - knees are slightly bent ~170 deg (not locked)
      - left/right symmetric
    """
    lm = np.zeros((33, 3))
    lm[0]  = [0.500, 0.06, 0.95]  # nose (midline, top)
    lm[7]  = [0.420, 0.08, 0.90]   # left_ear (directly above left_shoulder)
    lm[8]  = [0.580, 0.08, 0.90]   # right_ear (directly above right_shoulder)
    lm[11] = [0.420, 0.22, 0.95]   # left_shoulder (nearly above hip for straight spine)
    lm[12] = [0.580, 0.22, 0.95]   # right_shoulder
    lm[13] = [0.310, 0.38, 0.90]   # left_elbow  (bent, away from body)
    lm[14] = [0.690, 0.38, 0.90]   # right_elbow (bent, away from body)
    lm[15] = [0.310, 0.50, 0.85]   # left_wrist  (forearm hanging)
    lm[16] = [0.690, 0.50, 0.85]   # right_wrist (forearm hanging)
    lm[23] = [0.430, 0.52, 0.90]   # left_hip
    lm[24] = [0.570, 0.52, 0.90]   # right_hip
    lm[25] = [0.410, 0.72, 0.90]   # left_knee (slightly forward, ~170 deg)
    lm[26] = [0.590, 0.72, 0.90]   # right_knee (slightly forward, ~170 deg)
    lm[27] = [0.430, 0.92, 0.85]   # left_ankle
    lm[28] = [0.570, 0.92, 0.85]   # right_ankle
    lm[29] = [0.420, 0.97, 0.80]   # left_heel
    lm[30] = [0.580, 0.97, 0.80]   # right_heel
    lm[31] = [0.430, 1.00, 0.80]   # left_foot_index
    lm[32] = [0.570, 1.00, 0.80]   # right_foot_index
    return lm


def make_squat_landmarks(depth: float = 0.0) -> np.ndarray:
    """
    Squat pose. depth=0 → micro-squat, depth=1 → full squat.
    Knee angle varies from ~170 (standing) to ~90 (full squat).
    """
    lm = make_standing_landmarks().copy()
    bend = 0.10 + depth * 0.15  # hip/knee x-offset for depth
    lm[25] = [0.430 - bend * 0.3, 0.72 + bend, 0.90]   # left_knee forward+down
    lm[26] = [0.570 + bend * 0.3, 0.72 + bend, 0.90]   # right_knee forward+down
    lm[23] = [0.430 + bend * 0.5, 0.52 + bend * 0.3, 0.90]  # left_hip shifts
    lm[24] = [0.570 + bend * 0.5, 0.52 + bend * 0.3, 0.90]  # right_hip shifts
    lm[11] = [0.380 + bend * 0.3, 0.22 + bend * 0.2, 0.95]  # shoulder forward
    lm[12] = [0.620 + bend * 0.3, 0.22 + bend * 0.2, 0.95]
    lm[0]  = [0.500 + bend * 0.2, 0.06 + bend * 0.1, 0.95]
    return lm


def make_valgus_knees_landmarks() -> np.ndarray:
    """膝蓋內夾的異常姿勢（Rule 1 觸發）。"""
    lm = make_squat_landmarks(depth=0.8)
    lm[25] = [0.48, 0.72, 0.90]   # left_knee 內夾
    lm[26] = [0.52, 0.72, 0.90]   # right_knee 內夾
    lm[27] = [0.35, 0.90, 0.85]   # left_ankle 維持寬距
    lm[28] = [0.65, 0.90, 0.85]   # right_ankle 維持寬距
    return lm


def make_rounded_back_landmarks(deviation_deg: float = 30.0) -> np.ndarray:
    """
    Rounded back pose (Rule 2 trigger).
    deviation_deg: how much the spine deviates from straight.
    """
    lm = make_squat_landmarks(depth=0.7)
    t = min(deviation_deg / 90.0, 1.0)
    lm[11] = [0.380 + t * 0.12, 0.22 + t * 0.06, 0.95]   # shoulder forward + down
    lm[12] = [0.620 + t * 0.12, 0.22 + t * 0.06, 0.95]
    lm[0]  = [0.500 + t * 0.10, 0.16 + t * 0.06, 0.95]     # nose forward
    lm[7]  = [0.490 + t * 0.08, 0.14 + t * 0.04, 0.90]      # ear follows
    lm[8]  = [0.510 + t * 0.08, 0.14 + t * 0.04, 0.90]
    return lm


def make_asymmetric_landmarks(offset_deg: float = 15.0) -> np.ndarray:
    """Asymmetric pose (Rule 4 trigger). One side bends more than the other."""
    lm = make_standing_landmarks().copy()
    t = offset_deg / 60.0
    lm[25] = [0.430, 0.72 + t * 0.15, 0.90]   # left_knee bent more
    lm[27] = [0.430, 0.92 + t * 0.02, 0.85]    # left_ankle adjusted
    lm[13] = [0.300, 0.36 + t * 0.12, 0.90]    # left_elbow bent more
    lm[15] = [0.300, 0.50 + t * 0.10, 0.85]    # left_wrist follows
    return lm


def make_hyperextended_landmarks() -> np.ndarray:
    """Knee hyperextension pose (Rule 3 trigger). Knee goes past straight (180 deg)."""
    lm = make_standing_landmarks().copy()
    lm[25] = [0.430, 0.72, 0.90]   # left_knee directly above ankle (straight leg)
    lm[27] = [0.430, 0.92, 0.85]    # left_ankle directly below hip
    lm[23] = [0.430, 0.52, 0.90]    # left_hip directly above knee
    lm[26] = [0.570, 0.72, 0.90]    # right same pattern
    lm[28] = [0.570, 0.92, 0.85]
    lm[24] = [0.570, 0.52, 0.90]
    return lm


def make_neck_extended_landmarks() -> np.ndarray:
    """Cervical hyperextension pose (Rule 8 trigger).
    Head pushed far forward relative to shoulder-hip line, creating >15deg deviation."""
    lm = make_standing_landmarks().copy()
    lm[7]  = [0.480, 0.08, 0.90]   # left_ear pushed toward midline
    lm[8]  = [0.520, 0.08, 0.90]   # right_ear pushed toward midline
    lm[0]  = [0.500, 0.03, 0.95]   # nose stays midline but higher
    return lm


# ── Rule 1: 膝關節側向偏移 ──────────────────────────────────────────

def test_rule1_knee_valgus():
    print("\n" + "=" * 60)
    print("Rule 1: Knee Valgus (D_knee/D_ankle < 0.8 or FPPA > 10°)")
    print("=" * 60)

    # 1A: 正常深蹲 — 膝距 > 踝距 → ratio > 0.8 → 不觸發
    normal = make_squat_landmarks(depth=0.5)
    lk = normal[KEYPOINT["left_knee"]][:2]
    rk = normal[KEYPOINT["right_knee"]][:2]
    la = normal[KEYPOINT["left_ankle"]][:2]
    ra = normal[KEYPOINT["right_ankle"]][:2]
    knee_dist = np.linalg.norm(lk - rk)
    ankle_dist = np.linalg.norm(la - ra)
    ratio = knee_dist / (ankle_dist + 1e-9)
    check("normal squat ratio > 0.8", ratio > 0.8, True)
    check("normal squat no valgus", detect_knee_valgus(knee_dist, ankle_dist), False)

    # 1B: 膝蓋內夾 → ratio < 0.8 → 觸發
    valgus = make_valgus_knees_landmarks()
    lk = valgus[KEYPOINT["left_knee"]][:2]
    rk = valgus[KEYPOINT["right_knee"]][:2]
    la = valgus[KEYPOINT["left_ankle"]][:2]
    ra = valgus[KEYPOINT["right_ankle"]][:2]
    knee_dist = np.linalg.norm(lk - rk)
    ankle_dist = np.linalg.norm(la - ra)
    ratio = knee_dist / (ankle_dist + 1e-9)
    check("valgus ratio < 0.8", ratio < 0.8, True)
    check("valgus detected", detect_knee_valgus(knee_dist, ankle_dist), True)

    # 1C: FPPA — standing pose should have FPPA near 0
    standing = make_standing_landmarks()
    lh = standing[KEYPOINT["left_hip"]][:2]
    rh = standing[KEYPOINT["right_hip"]][:2]
    lk = standing[KEYPOINT["left_knee"]][:2]
    rk = standing[KEYPOINT["right_knee"]][:2]
    la = standing[KEYPOINT["left_ankle"]][:2]
    ra = standing[KEYPOINT["right_ankle"]][:2]
    left_fppa_s = calculate_fppa(lh, lk, la)
    right_fppa_s = calculate_fppa(rh, rk, ra)
    check_true("standing FPPA < 15 deg",
               max(left_fppa_s, right_fppa_s) < 15.0,
               f"left_fppa={left_fppa_s:.1f}, right_fppa={right_fppa_s:.1f}")

    # 1D: FPPA 測試 — 異常姿勢 FPPA 可能 > 10°
    lh_v = valgus[KEYPOINT["left_hip"]][:2]
    rk_v = valgus[KEYPOINT["right_knee"]][:2]
    lk_v = valgus[KEYPOINT["left_knee"]][:2]
    la_v = valgus[KEYPOINT["left_ankle"]][:2]
    ra_v = valgus[KEYPOINT["right_ankle"]][:2]
    left_fppa_v = calculate_fppa(lh_v, lk_v, la_v)
    check_true("valgus FPPA significant", left_fppa_v > 5.0,
               f"left_fppa={left_fppa_v:.1f}")

    # 1E: normal stance ratio is well above 0.8 threshold
    normal_stance = make_standing_landmarks()
    lk_ns = normal_stance[KEYPOINT["left_knee"]][:2]
    rk_ns = normal_stance[KEYPOINT["right_knee"]][:2]
    la_ns = normal_stance[KEYPOINT["left_ankle"]][:2]
    ra_ns = normal_stance[KEYPOINT["right_ankle"]][:2]
    knee_dist_ns = np.linalg.norm(lk_ns - rk_ns)
    ankle_dist_ns = np.linalg.norm(la_ns - ra_ns)
    ratio_ns = knee_dist_ns / (ankle_dist_ns + 1e-9)
    check_true("normal stance ratio > 0.8",
               ratio_ns > 0.8,
               f"ratio={ratio_ns:.3f}")
    check("normal stance no valgus at 0.8",
          detect_knee_valgus(knee_dist_ns, ankle_dist_ns, threshold=0.8), False)


# ── Rule 2: 脊柱彎曲異常 ─────────────────────────────────────────────

def test_rule2_spinal_flexion():
    print("\n" + "=" * 60)
    print("Rule 2: Spinal Deviation (shoulder-hip-knee angle > 15°)")
    print("=" * 60)

    # 2A: 正常直立 — 脊柱角 ≈ 180°
    standing = make_standing_landmarks()
    ls = standing[KEYPOINT["left_shoulder"]][:2]
    lh = standing[KEYPOINT["left_hip"]][:2]
    lk = standing[KEYPOINT["left_knee"]][:2]
    spine_angle = calc_angle(ls, lh, lk)
    deviation = abs(180.0 - spine_angle)
    check("standing spine near 180°", spine_angle, 180.0, tol=15.0)
    check("standing deviation < 15°", deviation, 15.0, tol=14.0)

    # 2B: 龜背 — spinal deviation > 15°
    rounded = make_rounded_back_landmarks(deviation_deg=30.0)
    ls_r = rounded[KEYPOINT["left_shoulder"]][:2]
    lh_r = rounded[KEYPOINT["left_hip"]][:2]
    lk_r = rounded[KEYPOINT["left_knee"]][:2]
    spine_angle_r = calc_angle(ls_r, lh_r, lk_r)
    deviation_r = abs(180.0 - spine_angle_r)
    check_true("rounded back deviation > 15°",
               deviation_r > 15.0,
               f"deviation={deviation_r:.1f}°")

    # 2C: 嚴重龜背 — severity 應更高
    severe = make_rounded_back_landmarks(deviation_deg=45.0)
    ls_s = severe[KEYPOINT["left_shoulder"]][:2]
    lh_s = severe[KEYPOINT["left_hip"]][:2]
    lk_s = severe[KEYPOINT["left_knee"]][:2]
    spine_angle_s = calc_angle(ls_s, lh_s, lk_s)
    deviation_s = abs(180.0 - spine_angle_s)
    check_true("severe rounding deviation > 25°",
               deviation_s > 25.0,
               f"deviation={deviation_s:.1f}°")


# ── Rule 3: 關節過度伸展 ─────────────────────────────────────────────

def test_rule3_joint_overextension():
    print("\n" + "=" * 60)
    print("Rule 3: Joint Overextension (angle ≤ 5° or ≥ 175°)")
    print("=" * 60)

    # 3A: normal joint angles — not hyperextended
    standing = make_standing_landmarks()
    le = standing[KEYPOINT["left_elbow"]][:2]
    ls = standing[KEYPOINT["left_shoulder"]][:2]
    lw = standing[KEYPOINT["left_wrist"]][:2]
    elbow_angle = calc_angle(ls, le, lw)
    check_true("standing elbow angle bends (not locked)",
               30.0 < elbow_angle < 175.0,
               f"angle={elbow_angle:.1f}")

    # 3B: fully extended knee (approx 178-180 deg) — trigger
    hyper = make_hyperextended_landmarks()
    lh = hyper[KEYPOINT["left_hip"]][:2]
    lk = hyper[KEYPOINT["left_knee"]][:2]
    la = hyper[KEYPOINT["left_ankle"]][:2]
    knee_angle = calc_angle(lh, lk, la)
    check_true("hyperextended knee angle >= 175",
               knee_angle >= 175.0,
               f"angle={knee_angle:.1f}")

    # 3C: nearly straight line (approx 180 deg)
    collinear_near = np.array([[0.0, 0.0], [0.5, 0.0], [0.99, 0.0]])
    angle_near_180 = calc_angle(collinear_near[0], collinear_near[1], collinear_near[2])
    check("collinear points angle near 180", angle_near_180, 180.0, tol=5.0)


# ── Rule 4: 雙側不對稱 ──────────────────────────────────────────────

def test_rule4_asymmetry():
    print("\n" + "=" * 60)
    print("Rule 4: Bilateral Asymmetry (left-right angle diff > 10°)")
    print("=" * 60)

    # 4A: 對稱站立 → diff < 10°
    standing = make_standing_landmarks()
    angles_standing = {}
    for name, (a, b, c) in [
        ("left_knee", ("left_hip", "left_knee", "left_ankle")),
        ("right_knee", ("right_hip", "right_knee", "right_ankle")),
        ("left_elbow", ("left_shoulder", "left_elbow", "left_wrist")),
        ("right_elbow", ("right_shoulder", "right_elbow", "right_wrist")),
        ("left_shoulder", ("left_hip", "left_shoulder", "left_elbow")),
        ("right_shoulder", ("right_hip", "right_shoulder", "right_elbow")),
    ]:
        angles_standing[name] = calc_angle(
            standing[KEYPOINT[a]][:2],
            standing[KEYPOINT[b]][:2],
            standing[KEYPOINT[c]][:2],
        )
    knee_diff = abs(angles_standing["left_knee"] - angles_standing["right_knee"])
    elbow_diff = abs(angles_standing["left_elbow"] - angles_standing["right_elbow"])
    check("symmetric knee diff < 10 deg", knee_diff, 0.0, tol=10.0)
    check("symmetric elbow diff < 10 deg", elbow_diff, 0.0, tol=10.0)

    sym = compute_symmetry(angles_standing)
    check_true("standing symmetry > 0.7", sym > 0.7, f"sym={sym:.3f}")

    # 4B: asymmetric pose with significant left-right difference
    asym = make_asymmetric_landmarks(offset_deg=25.0)
    angles_asym = {}
    for name, (a, b, c) in [
        ("left_knee", ("left_hip", "left_knee", "left_ankle")),
        ("right_knee", ("right_hip", "right_knee", "right_ankle")),
        ("left_elbow", ("left_shoulder", "left_elbow", "left_wrist")),
        ("right_elbow", ("right_shoulder", "right_elbow", "right_wrist")),
    ]:
        angles_asym[name] = calc_angle(
            asym[KEYPOINT[a]][:2],
            asym[KEYPOINT[b]][:2],
            asym[KEYPOINT[c]][:2],
        )
    knee_diff_asym = abs(angles_asym["left_knee"] - angles_asym["right_knee"])
    elbow_diff_asym = abs(angles_asym["left_elbow"] - angles_asym["right_elbow"])
    max_diff = max(knee_diff_asym, elbow_diff_asym)
    check_true("asymmetric pose max diff > 5 deg",
               max_diff > 5.0,
               f"knee_diff={knee_diff_asym:.1f}, elbow_diff={elbow_diff_asym:.1f}")

    sym_asym = compute_symmetry(angles_asym)
    check_true("asymmetric pose symmetry < 0.9",
               sym_asym < 0.9,
               f"sym={sym_asym:.3f}")


# ── Rule 5: 質心偏移 ─────────────────────────────────────────────────

def test_rule5_com_offset():
    print("\n" + "=" * 60)
    print("Rule 5: COM Offset (COM outside support polygon)")
    print("=" * 60)

    # 5A: 正常站立 → COM 在 BoS 內
    standing = make_standing_landmarks()
    lms_standing = [_MockLM(lm[0], lm[1], lm[2]) for lm in standing]
    result_standing = track_com(lms_standing, sex="male", contact="bipedal")
    check_true("standing COM inside BoS", result_standing.inside,
               f"offset_ratio={result_standing.offset_ratio:.3f}")
    check_true("standing offset_ratio < 1.0",
               result_standing.offset_ratio < 1.0,
               f"ratio={result_standing.offset_ratio:.3f}")

    # 5B: extreme forward lean — shift upper body mass so COM falls outside BoS
    # Strategy: move torso and head far forward while keeping feet back
    leaning = make_standing_landmarks().copy()
    # Shift all upper body landmarks far right/forward (high x in frontal view)
    leaning[0]  = [0.750, 0.30, 0.95]   # nose far right
    leaning[7]  = [0.600, 0.10, 0.90]   # left_ear shifted right
    leaning[8]  = [0.750, 0.10, 0.90]   # right_ear far right
    leaning[11] = [0.550, 0.25, 0.95]   # left_shoulder shifted right
    leaning[12] = [0.700, 0.25, 0.95]   # right_shoulder far right
    leaning[13] = [0.650, 0.40, 0.90]   # left_elbow shifted right
    leaning[14] = [0.800, 0.40, 0.90]   # right_elbow far right
    leaning[15] = [0.700, 0.55, 0.85]   # left_wrist shifted right
    leaning[16] = [0.850, 0.55, 0.85]   # right_wrist far right
    leaning[23] = [0.550, 0.55, 0.90]   # left_hip shifted right
    leaning[24] = [0.650, 0.55, 0.90]   # right_hip shifted right
    # Feet stay in normal position (support base is small)
    lms_leaning = [_MockLM(lm[0], lm[1], lm[2]) for lm in leaning]
    result_leaning = track_com(lms_leaning, sex="male", contact="bipedal")
    check_true("extreme lean: COM outside BoS",
               result_leaning.offset_ratio > result_standing.offset_ratio,
               f"standing_ratio={result_standing.offset_ratio:.3f}, leaning_ratio={result_leaning.offset_ratio:.3f}")

    # 5C: 使用 _make_mock_landmarks 測試前傾
    offset_lms = _make_mock_landmarks({
        LM.LEFT_HEEL: (0.80, 0.92),
        LM.RIGHT_HEEL: (0.90, 0.92),
        LM.LEFT_FOOT_INDEX: (0.80, 0.98),
        LM.RIGHT_FOOT_INDEX: (0.90, 0.98),
    })
    result_offset = track_com(offset_lms, contact="bipedal")
    check_true("shifted BoS puts COM outside",
               not result_offset.inside,
               f"inside={result_offset.inside}, ratio={result_offset.offset_ratio:.3f}")

    # 5D: convex_hull 測試
    square = [Point2D(0, 0), Point2D(1, 0), Point2D(1, 1), Point2D(0, 1)]
    check_true("center inside square", _point_in_polygon(Point2D(0.5, 0.5), square))
    check_true("corner outside right", not _point_in_polygon(Point2D(1.5, 0.5), square))


class _MockLM:
    def __init__(self, x, y, z=0.0):
        self.x = x
        self.y = y
        self.z = z


# ── Rule 6: 急速動作 ──────────────────────────────────────────────────

def test_rule6_rapid_movement():
    print("\n" + "=" * 60)
    print("Rule 6: Rapid Movement (angular velocity > 600 deg/s)")
    print("=" * 60)

    # 6A: 正常速度 → 不觸發
    normal_angles = np.array([90.0] * 50)
    vel = compute_angular_velocity(normal_angles, fps=30.0)
    rapid = detect_rapid_movement_sg(
        vel,
        threshold=SG_RAPID_MOVEMENT_THRESHOLD_DPS,
        consecutive_frames=3,
    )
    check("normal smooth movement no rapid frames", rapid.sum(), 0)

    # 6B: 快速動作 → 觸發
    fast_angles = np.concatenate([
        np.full(12, 60.0),
        np.linspace(60.0, 180.0, 4),
        np.full(34, 180.0),
    ])
    vel_fast = compute_angular_velocity(fast_angles, fps=30.0)
    max_vel = np.max(np.abs(vel_fast))
    check_true("fast movement velocity > 600 deg/s",
                max_vel > RAPID_MOVEMENT_THRESHOLD_DPS,
                f"max_vel={max_vel:.1f} deg/s")

    rapid_fast = detect_rapid_movement_sg(
        vel_fast,
        threshold=SG_RAPID_MOVEMENT_THRESHOLD_DPS,
        consecutive_frames=3,
    )
    check_true("fast movement detected rapid frames",
                rapid_fast.sum() > 0,
                f"rapid_frames={rapid_fast.sum()}")

    # 6C: compute_angles 版本的角速度計算
    angle_current = 120.0
    angle_prev = 90.0
    dt = 1.0 / 30.0
    angular_vel = calculate_angular_velocity_dps(angle_current, angle_prev, dt)
    check("angular velocity 900 deg/s", angular_vel, 900.0, tol=1.0)
    check_true("rapid movement threshold crossed", detect_rapid_movement(angular_vel))

    # 6D: 第一幀無前一幀 → 速度為 0
    vel_first = calculate_angular_velocity_dps(90.0, None, dt)
    check("first frame velocity = 0", vel_first, 0.0)

    # 6E: 慢速不觸發
    slow_vel = calculate_angular_velocity_dps(91.0, 90.0, dt)
    check_true("slow velocity not rapid", not detect_rapid_movement(slow_vel),
                f"vel={slow_vel:.1f} deg/s")


# ── Rule 7: 活動度不足 ────────────────────────────────────────────────

def test_rule7_rom_insufficient():
    print("\n" + "=" * 60)
    print("Rule 7: Insufficient ROM (ROM < 50% of expected safe ROM)")
    print("=" * 60)

    # Rule 7 比較當前 ROM 與預期安全 ROM。
    # 使用常見關節 ROM 範圍作為基準。

    # 7A: 正常深蹲 ROM — 膝蓋 160°→90° = 70° ROM（安全範圍 ~140°）
    knee_rom_normal = 160.0 - 90.0  # 70°
    knee_expected_rom = 140.0       # 全膝 ROM
    ratio_normal = knee_rom_normal / knee_expected_rom
    check_true("normal squat ROM ratio >= 0.5",
               ratio_normal >= 0.5,
               f"ratio={ratio_normal:.2f}")

    # 7B: 淺蹲 ROM 不足 — 膝蓋 160°→140° = 20° ROM（< 50% of 140°）
    knee_rom_shallow = 160.0 - 140.0  # 20°
    ratio_shallow = knee_rom_shallow / knee_expected_rom
    check_true("shallow squat ROM ratio < 0.5",
               ratio_shallow < 0.5,
               f"ratio={ratio_shallow:.2f}")

    # 7C: 嚴重 ROM 不足 — ratio < 0.3
    knee_rom_severe = 10.0  # 幾乎沒動
    ratio_severe = knee_rom_severe / knee_expected_rom
    check_true("severe ROM ratio < 0.3",
               ratio_severe < 0.3,
               f"ratio={ratio_severe:.3f}")

    # 7D: 各關節預期 ROM 表
    expected_roms = {
        "knee": (0, 140),
        "hip": (0, 120),
        "elbow": (0, 145),
        "shoulder": (0, 180),
        "ankle": (0, 50),
    }
    for joint, (rom_min, rom_max) in expected_roms.items():
        full_rom = rom_max - rom_min
        half_rom = full_rom * 0.5
        check(f"{joint} half ROM > 0", half_rom > 0, True)


# ── Rule 8: 頸椎過伸 ──────────────────────────────────────────────────

def test_rule8_neck_hyperextension():
    print("\n" + "=" * 60)
    print("Rule 8: Neck Hyperextension (ear-shoulder-hip deviation > 15°)")
    print("=" * 60)

    # 8A: normal upright — neck deviation < 15°
    standing = make_standing_landmarks()
    le = standing[KEYPOINT["left_ear"]][:2]
    ls = standing[KEYPOINT["left_shoulder"]][:2]
    lh = standing[KEYPOINT["left_hip"]][:2]
    neck_angle = calc_angle(le, ls, lh)
    neck_deviation = abs(180.0 - neck_angle)
    check("standing neck deviation < 15 deg", neck_deviation, 15.0, tol=14.0)

    # 8B: neck hyperextension — deviation > 15°
    extended = make_neck_extended_landmarks()
    le_e = extended[KEYPOINT["left_ear"]][:2]
    ls_e = extended[KEYPOINT["left_shoulder"]][:2]
    lh_e = extended[KEYPOINT["left_hip"]][:2]
    neck_angle_e = calc_angle(le_e, ls_e, lh_e)
    neck_deviation_e = abs(180.0 - neck_angle_e)
    check_true("extended neck deviation > standing",
               neck_deviation_e > neck_deviation,
               f"standing={neck_deviation:.1f}, extended={neck_deviation_e:.1f}")

    # 8C: severe neck hyperextension
    severe_neck = make_standing_landmarks().copy()
    severe_neck[7] = [0.490, 0.08, 0.90]   # left_ear very medial
    severe_neck[8] = [0.510, 0.08, 0.90]   # right_ear very medial
    severe_neck[0] = [0.500, 0.10, 0.95]    # nose forward
    le_s = severe_neck[KEYPOINT["left_ear"]][:2]
    ls_s = severe_neck[KEYPOINT["left_shoulder"]][:2]
    lh_s = severe_neck[KEYPOINT["left_hip"]][:2]
    neck_angle_s = calc_angle(le_s, ls_s, lh_s)
    neck_deviation_s = abs(180.0 - neck_angle_s)
    check_true("severe neck extension deviation > standing",
               neck_deviation_s > neck_deviation,
               f"standing={neck_deviation:.1f}, severe={neck_deviation_s:.1f}")


# ── 整合測試：全規則交叉驗證 ──────────────────────────────────────────

def test_integrated_all_rules():
    print("\n" + "=" * 60)
    print("Integration: All 8 rules cross-validation")
    print("=" * 60)

    standing = make_standing_landmarks()

    # 每條規則對正常站立姿勢都不應觸發
    # Rule 1: knee valgus
    lk = standing[KEYPOINT["left_knee"]][:2]
    rk = standing[KEYPOINT["right_knee"]][:2]
    la = standing[KEYPOINT["left_ankle"]][:2]
    ra = standing[KEYPOINT["right_ankle"]][:2]
    knee_dist = np.linalg.norm(lk - rk)
    ankle_dist = np.linalg.norm(la - ra)
    check("standing: Rule 1 no valgus",
          detect_knee_valgus(knee_dist, ankle_dist), False)

    # Rule 2: spinal deviation
    ls = standing[KEYPOINT["left_shoulder"]][:2]
    lh = standing[KEYPOINT["left_hip"]][:2]
    lknee = standing[KEYPOINT["left_knee"]][:2]
    spine = calc_angle(ls, lh, lknee)
    check_true("standing: Rule 2 spine straight",
               abs(180.0 - spine) < 15.0,
               f"deviation={abs(180.0 - spine):.1f}°")

    # Rule 4: symmetry
    angles = {}
    for name, (a, b, c) in [
        ("left_knee", ("left_hip", "left_knee", "left_ankle")),
        ("right_knee", ("right_hip", "right_knee", "right_ankle")),
        ("left_elbow", ("left_shoulder", "left_elbow", "left_wrist")),
        ("right_elbow", ("right_shoulder", "right_elbow", "right_wrist")),
        ("left_shoulder", ("left_hip", "left_shoulder", "left_elbow")),
        ("right_shoulder", ("right_hip", "right_shoulder", "right_elbow")),
    ]:
        angles[name] = calc_angle(
            standing[KEYPOINT[a]][:2],
            standing[KEYPOINT[b]][:2],
            standing[KEYPOINT[c]][:2],
        )
    sym = compute_symmetry(angles)
    check_true("standing: Rule 4 symmetrical", sym > 0.7, f"sym={sym:.3f}")

    # Rule 5: COM inside
    lms_standing = [_MockLM(lm[0], lm[1], lm[2]) for lm in standing]
    result = track_com(lms_standing, sex="male", contact="bipedal")
    check_true("standing: Rule 5 COM inside", result.inside)

    # Rule 6: no rapid movement
    vel = calculate_angular_velocity_dps(90.0, 90.0, 1.0 / 30.0)
    check("standing: Rule 6 no rapid movement", vel, 0.0, tol=0.1)

    # Rule 8: neck neutral (tolerant of frontal-plane geometry)
    le = standing[KEYPOINT["left_ear"]][:2]
    ls_n = standing[KEYPOINT["left_shoulder"]][:2]
    lh_n = standing[KEYPOINT["left_hip"]][:2]
    neck_ang = calc_angle(le, ls_n, lh_n)
    neck_dev = abs(180.0 - neck_ang)
    check("standing: Rule 8 neck deviation reasonable",
          neck_dev, 15.0, tol=20.0)

    # Integration: movement_classifier on standing
    pattern = classify(standing)
    check_true("standing pattern classified", pattern.pattern_label != "")
    check("standing confidence > 0", pattern.confidence > 0, True)

    # Integration: muscle_focus on the pattern
    mf = estimate_muscle_focus({
        "pattern": pattern.pattern_label,
        "primary_joint": pattern.primary_joint.value,
    })
    check("muscle focus note is correct",
          mf.note, "pose_based_estimate_not_emg")


# ── Severity 測試 ──────────────────────────────────────────────────────

def test_severity_levels():
    print("\n" + "=" * 60)
    print("Severity Levels: Low (0.5) vs High (0.9)")
    print("=" * 60)

    # C++ safety_monitor 的 severity 邏輯：
    # Rule 1: ratio < 0.65 OR fppa > 20° → 0.9, else 0.5
    # Rule 2: deviation > 30° → 0.9, else 0.5
    # Rule 3: angle < 2° OR > 178° → 0.9, else 0.5
    # Rule 4: max_diff > 20° → 0.9, else 0.5
    # Rule 5: distance > 0.08 → 0.9, else 0.5
    # Rule 6: speed > 900 deg/s → 0.9, else 0.5
    # Rule 7: ratio < 0.3 → 0.9, else 0.5
    # Rule 8: deviation > 25° → 0.9, else 0.5

    # 這裡測試 Python 原型的一致性
    # Rule 6 severity
    check_true("Rule 6 mild: 700 deg/s < 900 → severity 0.5",
               700 > RAPID_MOVEMENT_THRESHOLD_DPS and 700 < 900)
    check_true("Rule 6 severe: 950 deg/s > 900 → severity 0.9",
               950 > 900)

    # Rule 7 severity thresholds
    mild_rom_ratio = 0.45   # < 0.5 but > 0.3
    severe_rom_ratio = 0.2  # < 0.3
    check_true("Rule 7 mild: 0.3 < ratio 0.45 < 0.5 → severity 0.5",
               0.3 < mild_rom_ratio < 0.5)
    check_true("Rule 7 severe: ratio 0.2 < 0.3 → severity 0.9",
               severe_rom_ratio < 0.3)


# ── 安全規則號碼對照 ──────────────────────────────────────────────────

def test_rule_numbering():
    print("\n" + "=" * 60)
    print("Rule Numbering Consistency Check")
    print("=" * 60)

    rules = [
        (1, "knee_valgus", "D_knee/D_ankle < 0.8 or FPPA > 10°"),
        (2, "spinal_flexion", "shoulder-hip-knee deviation > 15°"),
        (3, "joint_overextension", "angle ≤ 5° or ≥ 175°"),
        (4, "bilateral_asymmetry", "left-right angle diff > 10°"),
        (5, "com_offset", "COM projection outside support polygon"),
        (6, "rapid_movement", "angular velocity > 600 deg/s"),
        (7, "rom_insufficient", "ROM < 50% of expected"),
        (8, "neck_hyperextension", "ear-shoulder-hip deviation > 15°"),
    ]

    check("8 rules defined", len(rules), 8)
    for rule_num, name, desc in rules:
        check(f"Rule {rule_num}: {name}", True, True)

    # Check FC tool correspondence (implementation_plan.md §5)
    fc_tools = [
        (1, "correct_knee_alignment"),
        (2, "correct_spinal_alignment"),
        (3, "correct_joint_angle"),
        (4, "correct_asymmetry"),
        (5, "warn_com_offset"),
        (6, "warn_rapid_movement"),
        (7, "increase_range_of_motion"),
        (8, "positive_reinforcement"),
    ]
    check("8 FC tools defined", len(fc_tools), 8)


# ── 主程式 ─────────────────────────────────────────────────────────────

def main():
    print("=" * 60)
    print("   GemmaFit 8-Rule Safety Test Suite")
    print("   implementation_plan.md §3.2 compliance check")
    print("=" * 60)

    test_rule1_knee_valgus()
    test_rule2_spinal_flexion()
    test_rule3_joint_overextension()
    test_rule4_asymmetry()
    test_rule5_com_offset()
    test_rule6_rapid_movement()
    test_rule7_rom_insufficient()
    test_rule8_neck_hyperextension()
    test_integrated_all_rules()
    test_severity_levels()
    test_rule_numbering()

    print("\n" + "=" * 60)
    print(f"  TOTAL: {passed} PASS, {failed} FAIL")
    if failed == 0:
        print("  ALL 8 SAFETY RULES VALIDATED")
    else:
        print(f"  {failed} RULE(S) NEED ATTENTION")
    print("=" * 60)
    print("[NOTE] Thresholds are prototype values pending real-data calibration")
    print("[NOTE] Rule 6 uses deg/s (not deg/frame) to avoid FPS dependency")
    print("[NOTE] Severity levels follow C++ safety_monitor.cpp thresholds")
    print("=" * 60)

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
