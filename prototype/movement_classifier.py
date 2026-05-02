"""
movement_classifier.py — 物理模式識別分類樹

不命名動作名稱（不輸出「深蹲」「伏地挺身」），只輸出物理描述：
  - 主導關節 (primary_joint)
  - 運動平面 (plane)
  - 支撐型態 (base)
  - 負荷向量 (load_vector)
  - 收縮相 (phase)
  - 信心分數 (confidence)

分類樹流程：
  1. 支撐型態判定 (Support Base)
  2. 主導關節判定 (Dominant Joint)
  3. 運動平面判定 (Movement Plane)
  4. 收縮相判定 (Contraction Phase)

輸入：33 MediaPipe landmarks (numpy array, shape (33, 3) 或 dict of arrays)
輸出：MovementPattern dataclass
"""

from dataclasses import dataclass, field
from enum import Enum
from typing import List, Tuple

import numpy as np


# ── 列舉 ────────────────────────────────────────────────────────────

class SupportBase(Enum):
    BIPEDAL = "bipedal"                # 雙足站立
    BIPEDAL_WIDE = "bipedal_wide"      # 寬站距雙足
    UNIPEDAL = "unipedal"              # 單足站立
    QUADRUPEDAL = "quadrupedal"        # 雙手+雙足撐地
    PRONE_HANDS = "prone_hands"        # 俯臥雙手撐地（伏地挺身）
    SUPINE = "supine"                  # 仰臥
    SITTING = "sitting"               # 坐姿
    UNKNOWN = "unknown"


class DominantJoint(Enum):
    KNEE = "knee"          # 膝主導
    HIP = "hip"            # 髖主導
    SHOULDER = "shoulder"  # 肩主導
    ELBOW = "elbow"        # 肘主導
    SPINE = "spine"        # 脊柱穩定
    ANKLE = "ankle"        # 踝主導


class MovementPlane(Enum):
    SAGITTAL = "sagittal"       # 矢狀面 (前後)
    FRONTAL = "frontal"         # 冠狀面 (左右)
    TRANSVERSE = "transverse"   # 水平面 (旋轉)
    MIXED = "mixed"


class ContractionPhase(Enum):
    CONCENTRIC = "concentric"    # 向心收縮（對抗重力）
    ECCENTRIC = "eccentric"      # 離心收縮（順應重力）
    ISOMETRIC = "isometric"      # 等長收縮（靜止）
    TRANSITION = "transition"    # 轉換期


class LoadVector(Enum):
    VERTICAL = "vertical"           # 垂直負荷（蹲、舉）
    HORIZONTAL = "horizontal"       # 水平負荷（推、拉）
    ROTATIONAL = "rotational"       # 旋轉負荷
    BODYWEIGHT = "bodyweight"       # 體重為主


# ── 輸出結構 ────────────────────────────────────────────────────────

@dataclass
class MovementPattern:
    """物理模式描述（非動作命名）"""
    pattern_label: str             # 組合標籤 e.g. "bilateral_knee_dominant_sagittal"
    primary_joint: DominantJoint
    secondary_joint: DominantJoint
    plane: MovementPlane
    base: SupportBase
    load_vector: LoadVector
    phase: ContractionPhase
    symmetry: float                # 0-1, 1 = 完美對稱
    cycle_phase: float             # 0-1, 動作週期位置
    joint_angles: dict = field(default_factory=dict)
    angular_velocities: dict = field(default_factory=dict)
    confidence: float = 0.0        # 0-1


# ── MediaPipe 關鍵點索引 ────────────────────────────────────────────
# 使用 PoseLandmark 常數名稱對應的索引值

KEYPOINT = {
    "nose": 0, "left_eye_inner": 1, "left_eye": 2, "left_eye_outer": 3,
    "right_eye_inner": 4, "right_eye": 5, "right_eye_outer": 6,
    "left_ear": 7, "right_ear": 8, "mouth_left": 9, "mouth_right": 10,
    "left_shoulder": 11, "right_shoulder": 12,
    "left_elbow": 13, "right_elbow": 14,
    "left_wrist": 15, "right_wrist": 16,
    "left_pinky": 17, "right_pinky": 18,
    "left_index": 19, "right_index": 20,
    "left_thumb": 21, "right_thumb": 22,
    "left_hip": 23, "right_hip": 24,
    "left_knee": 25, "right_knee": 26,
    "left_ankle": 27, "right_ankle": 28,
    "left_heel": 29, "right_heel": 30,
    "left_foot_index": 31, "right_foot_index": 32,
}

# 肢段定義：(起點, 終點)
SEGMENTS = {
    "torso": ("left_shoulder", "left_hip"),
    "torso_right": ("right_shoulder", "right_hip"),
    "left_thigh": ("left_hip", "left_knee"),
    "right_thigh": ("right_hip", "right_knee"),
    "left_shank": ("left_knee", "left_ankle"),
    "right_shank": ("right_knee", "right_ankle"),
    "left_upper_arm": ("left_shoulder", "left_elbow"),
    "right_upper_arm": ("right_shoulder", "right_elbow"),
    "left_forearm": ("left_elbow", "left_wrist"),
    "right_forearm": ("right_elbow", "right_wrist"),
}

# 關節定義：(A, B, C) → 計算頂點 B 的夾角
JOINTS = {
    "left_knee": ("left_hip", "left_knee", "left_ankle"),
    "right_knee": ("right_hip", "right_knee", "right_ankle"),
    "left_hip": ("left_shoulder", "left_hip", "left_knee"),
    "right_hip": ("right_shoulder", "right_hip", "right_knee"),
    "left_elbow": ("left_shoulder", "left_elbow", "left_wrist"),
    "right_elbow": ("right_shoulder", "right_elbow", "right_wrist"),
    "left_shoulder": ("left_hip", "left_shoulder", "left_elbow"),
    "right_shoulder": ("right_hip", "right_shoulder", "right_elbow"),
    "spine": ("left_shoulder", "left_hip", "left_knee"),
}


# ── 工具函數 ────────────────────────────────────────────────────────

def _get_point(landmarks: np.ndarray, key: str) -> np.ndarray:
    """從 landmarks (33, 3) 取出指定關鍵點 (x, y)。"""
    idx = KEYPOINT[key]
    return landmarks[idx, :2].copy()  # 只取 x, y


def calc_angle(a: np.ndarray, b: np.ndarray, c: np.ndarray) -> float:
    """計算 a-b-c 三點夾角 (度)，b 為頂點。"""
    v1 = a - b
    v2 = c - b
    cos_a = np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9)
    return float(np.degrees(np.arccos(np.clip(cos_a, -1.0, 1.0))))


def calc_segment_angle(a: np.ndarray, b: np.ndarray) -> float:
    """計算 a→b 向量與垂直線的夾角 (度)。"""
    vec = b - a
    return float(np.degrees(np.arctan2(np.abs(vec[0]), np.abs(vec[1]) + 1e-9)))


def calc_distance(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.linalg.norm(a - b))


# ── 步驟 1：支撐型態判定 ────────────────────────────────────────────

def classify_support_base(
    landmarks: np.ndarray,
    visibility_threshold: float = 0.5,
) -> SupportBase:
    """
    基於關鍵點相對位置判定支撐型態。

    邏輯：
    - 腳踝低於髖部且接近畫面底部 → 站立
    - 手腕低於肩膀且接近地面 → 手部支撐
    - 兩腳距離 > 肩寬的 1.2 倍 → 寬站距
    - 單腳可視度低 → 單足站立
    """
    l_ankle = _get_point(landmarks, "left_ankle")
    r_ankle = _get_point(landmarks, "right_ankle")
    l_hip = _get_point(landmarks, "left_hip")
    r_hip = _get_point(landmarks, "right_hip")
    l_wrist = _get_point(landmarks, "left_wrist")
    r_wrist = _get_point(landmarks, "right_wrist")
    l_shoulder = _get_point(landmarks, "left_shoulder")
    r_shoulder = _get_point(landmarks, "right_shoulder")

    mid_hip = (l_hip + r_hip) / 2
    mid_ankle = (l_ankle + r_ankle) / 2
    mid_wrist = (l_wrist + r_wrist) / 2
    mid_shoulder = (l_shoulder + r_shoulder) / 2

    ankle_below_hip = mid_ankle[1] > mid_hip[1]      # Y 越大 → 越下面
    wrist_below_shoulder = mid_wrist[1] > mid_shoulder[1]
    ankle_near_bottom = mid_ankle[1] > 0.7
    wrist_near_bottom = mid_wrist[1] > 0.75

    # 雙足站立
    if ankle_below_hip and ankle_near_bottom:
        # 檢查站寬
        shoulder_width = calc_distance(l_shoulder, r_shoulder)
        ankle_width = calc_distance(l_ankle, r_ankle)
        if ankle_width > shoulder_width * 1.2:
            return SupportBase.BIPEDAL_WIDE
        return SupportBase.BIPEDAL

    # 單足站立
    if ankle_below_hip and not ankle_near_bottom:
        return SupportBase.UNIPEDAL

    # 雙手撐地（伏地挺身等）
    if wrist_near_bottom and wrist_below_shoulder:
        # 確認是否為俯臥 (prone) vs 四足 (quadrupedal)
        hip_y = mid_hip[1]
        shoulder_y = mid_shoulder[1]
        if abs(hip_y - shoulder_y) < 0.1:
            return SupportBase.QUADRUPEDAL
        return SupportBase.PRONE_HANDS

    # 坐姿
    if mid_hip[1] > 0.5 and not ankle_below_hip:
        return SupportBase.SITTING

    return SupportBase.UNKNOWN


# ── 步驟 2：主導關節判定 ────────────────────────────────────────────

def classify_dominant_joint(
    landmarks: np.ndarray,
    prev_angles: dict = None,
    fps: float = 30.0,
) -> Tuple[DominantJoint, DominantJoint, dict]:
    """
    判定主導關節與次要關節。
    基於各關節角度變化量 (ROM) 與角速度。

    returns: (primary_joint, secondary_joint, joint_angles_dict)
    """
    angles = {}
    for name, (a, b, c) in JOINTS.items():
        p_a = _get_point(landmarks, a)
        p_b = _get_point(landmarks, b)
        p_c = _get_point(landmarks, c)
        angles[name] = calc_angle(p_a, p_b, p_c)

    # 分類規則（依優先序）：
    # - 膝關節 ROM > 30° → 膝主導
    # - 髖關節 ROM > 25° → 髖主導
    # - 肩關節 ROM > 30° → 肩主導
    # - 肘關節 ROM > 30° → 肘主導
    # - 其他 → 脊柱穩定

    knee_rom = max(abs(180 - angles.get("left_knee", 180)),
                   abs(180 - angles.get("right_knee", 180)))
    hip_rom = max(abs(180 - angles.get("left_hip", 180)),
                  abs(180 - angles.get("right_hip", 180)))
    shoulder_rom = max(abs(180 - angles.get("left_shoulder", 180)),
                       abs(180 - angles.get("right_shoulder", 180)))
    elbow_rom = max(abs(180 - angles.get("left_elbow", 180)),
                    abs(180 - angles.get("right_elbow", 180)))

    # 膝主導：膝 ROM > 30° AND 髖 ROM > 20° (蹲類)
    if knee_rom > 30 and hip_rom > 20:
        return DominantJoint.KNEE, DominantJoint.HIP, angles

    # 肩主導：肩 ROM > 30° (推/拉類)
    if shoulder_rom > 30:
        return DominantJoint.SHOULDER, DominantJoint.ELBOW, angles

    # 肘主導：肘 ROM > 30° 但肩變動小 (孤立屈伸)
    if elbow_rom > 30:
        return DominantJoint.ELBOW, DominantJoint.SHOULDER, angles

    # 髖主導：髖 ROM > 25° 但膝變動小 (折髖/硬舉類)
    if hip_rom > 25:
        return DominantJoint.HIP, DominantJoint.KNEE, angles

    # 默認：脊柱穩定類 (棒式、核心訓練)
    return DominantJoint.SPINE, DominantJoint.HIP, angles


# ── 步驟 3：運動平面判定 ────────────────────────────────────────────

def classify_movement_plane(
    landmarks: np.ndarray,
    primary_joint: DominantJoint,
    prev_landmarks: np.ndarray = None,
) -> MovementPlane:
    """
    判定主要運動平面。
    比較各關鍵點在 X (水平) 與 Y (垂直) 方向的變化量。
    """
    l_shoulder = _get_point(landmarks, "left_shoulder")
    r_shoulder = _get_point(landmarks, "right_shoulder")
    l_hip = _get_point(landmarks, "left_hip")
    r_hip = _get_point(landmarks, "right_hip")
    l_elbow = _get_point(landmarks, "left_elbow")
    r_elbow = _get_point(landmarks, "right_elbow")

    # 左右肩高度差 → 冠狀面偏移
    shoulder_tilt = abs(l_shoulder[1] - r_shoulder[1])
    hip_tilt = abs(l_hip[1] - r_hip[1])

    # 肘部相對於肩的水平位移 → 水平面
    l_elbow_x_offset = abs(l_elbow[0] - l_shoulder[0])
    r_elbow_x_offset = abs(r_elbow[0] - r_shoulder[0])

    if shoulder_tilt > 0.05 or hip_tilt > 0.05:
        return MovementPlane.FRONTAL

    if l_elbow_x_offset > 0.08 or r_elbow_x_offset > 0.08:
        return MovementPlane.TRANSVERSE

    return MovementPlane.SAGITTAL


# ── 步驟 4：收縮相判定 ──────────────────────────────────────────────

def classify_contraction_phase(
    current_angles: dict,
    prev_angles: dict = None,
    primary_joint: DominantJoint = None,
    base: SupportBase = None,
) -> ContractionPhase:
    """
    判定收縮相。
    根據主導關節角度的變化方向 + 身體相對於重力的位置。
    """
    if not prev_angles:
        return ContractionPhase.TRANSITION

    joint_map = {
        DominantJoint.KNEE: ["left_knee", "right_knee"],
        DominantJoint.HIP: ["left_hip", "right_hip"],
        DominantJoint.SHOULDER: ["left_shoulder", "right_shoulder"],
        DominantJoint.ELBOW: ["left_elbow", "right_elbow"],
        DominantJoint.SPINE: ["spine"],
        DominantJoint.ANKLE: [],
    }

    keys = joint_map.get(primary_joint, [])
    angle_diffs = []
    for k in keys:
        if k in current_angles and k in prev_angles:
            angle_diffs.append(current_angles[k] - prev_angles[k])

    if not angle_diffs:
        return ContractionPhase.ISOMETRIC

    avg_diff = np.mean(angle_diffs)

    if abs(avg_diff) < 0.5:
        return ContractionPhase.ISOMETRIC
    elif abs(avg_diff) < 2.0:
        return ContractionPhase.TRANSITION

    # 負荷方向判定（取決於支撐型態）
    if base in (SupportBase.PRONE_HANDS, SupportBase.QUADRUPEDAL):
        # 伏地挺身：肘彎曲 = 離心 (下降), 肘伸展 = 向心 (上升)
        # 角度變大 = 伸展 = 向心
        return ContractionPhase.CONCENTRIC if avg_diff > 0 else ContractionPhase.ECCENTRIC
    else:
        # 站立：膝/髖彎曲 = 離心 (下蹲), 膝/髖伸展 = 向心 (起立)
        return ContractionPhase.ECCENTRIC if avg_diff < 0 else ContractionPhase.CONCENTRIC


# ── 步驟 5：對稱度 ──────────────────────────────────────────────────

def compute_symmetry(angles: dict) -> float:
    """計算左右對稱分數 (0-1)。"""
    pairs = [
        ("left_knee", "right_knee"),
        ("left_hip", "right_hip"),
        ("left_shoulder", "right_shoulder"),
        ("left_elbow", "right_elbow"),
    ]
    diffs = []
    for l, r in pairs:
        if l in angles and r in angles:
            diffs.append(abs(angles[l] - angles[r]))
    if not diffs:
        return 1.0
    avg_diff = np.mean(diffs)
    # 對稱分數：differences 越小分數越高
    return max(0.0, 1.0 - avg_diff / 20.0)


# ── 主分類函數 ──────────────────────────────────────────────────────

def classify(
    landmarks: np.ndarray,
    prev_landmarks: np.ndarray = None,
    prev_angles: dict = None,
    fps: float = 30.0,
) -> MovementPattern:
    """
    對單幀 landmarks 做物理模式分類。

    landmarks: (33, 3) numpy array (x, y, visibility)
    prev_landmarks: 前一幀的 landmarks (可選)
    prev_angles: 前一幀的角度 dict (可選)
    fps: 幀率

    returns: MovementPattern
    """
    # 步驟 1：支撐型態
    base = classify_support_base(landmarks)

    # 步驟 2：主導關節
    primary_joint, secondary_joint, angles = classify_dominant_joint(
        landmarks, prev_angles, fps
    )

    # 步驟 3：運動平面
    plane = classify_movement_plane(landmarks, primary_joint, prev_landmarks)

    # 步驟 4：收縮相
    phase = classify_contraction_phase(angles, prev_angles, primary_joint, base)

    # 步驟 5：對稱度
    symmetry = compute_symmetry(angles)

    # 組合標籤
    pattern_label = f"{base.value}_{primary_joint.value}_dominant_{plane.value}"

    return MovementPattern(
        pattern_label=pattern_label,
        primary_joint=primary_joint,
        secondary_joint=secondary_joint,
        plane=plane,
        base=base,
        load_vector=_infer_load_vector(base, plane),
        phase=phase,
        symmetry=symmetry,
        cycle_phase=0.5,
        joint_angles=angles,
        confidence=_estimate_confidence(landmarks),
    )


def _infer_load_vector(base: SupportBase, plane: MovementPlane) -> LoadVector:
    """推斷負荷向量。"""
    if base in (SupportBase.PRONE_HANDS, SupportBase.QUADRUPEDAL):
        return LoadVector.HORIZONTAL if plane == MovementPlane.SAGITTAL else LoadVector.BODYWEIGHT
    if plane == MovementPlane.TRANSVERSE:
        return LoadVector.ROTATIONAL
    return LoadVector.VERTICAL


def _estimate_confidence(landmarks: np.ndarray) -> float:
    """估算整體信心分數 (基於關鍵點 visibility)。"""
    visibilities = landmarks[:, 2]  # 第三維度為 visibility
    return float(np.mean(visibilities))


# ── 測試 ────────────────────────────────────────────────────────────
if __name__ == "__main__":
    # 模擬一個接近站姿的 landmarks (x, y, visibility)
    # 這是簡化測試，實際使用需替換為 MediaPipe 輸出
    dummy = np.zeros((33, 3))
    # 簡易填充（僅為結構測試，非真實人體比例）
    for i in range(33):
        dummy[i] = [0.5, 0.5, 0.9]

    # 粗略模擬站立姿勢
    dummy[KEYPOINT["left_shoulder"]]  = [0.45, 0.3, 0.95]
    dummy[KEYPOINT["right_shoulder"]] = [0.55, 0.3, 0.95]
    dummy[KEYPOINT["left_hip"]]       = [0.46, 0.55, 0.9]
    dummy[KEYPOINT["right_hip"]]      = [0.54, 0.55, 0.9]
    dummy[KEYPOINT["left_knee"]]      = [0.42, 0.75, 0.9]
    dummy[KEYPOINT["right_knee"]]     = [0.58, 0.75, 0.9]
    dummy[KEYPOINT["left_ankle"]]     = [0.41, 0.93, 0.85]
    dummy[KEYPOINT["right_ankle"]]    = [0.59, 0.93, 0.85]
    dummy[KEYPOINT["left_elbow"]]     = [0.4, 0.4, 0.9]
    dummy[KEYPOINT["right_elbow"]]    = [0.6, 0.4, 0.9]
    dummy[KEYPOINT["left_wrist"]]     = [0.42, 0.5, 0.9]
    dummy[KEYPOINT["right_wrist"]]    = [0.58, 0.5, 0.9]

    pattern = classify(dummy)
    print(f"Pattern:       {pattern.pattern_label}")
    print(f"Primary:       {pattern.primary_joint.value}")
    print(f"Secondary:     {pattern.secondary_joint.value}")
    print(f"Plane:         {pattern.plane.value}")
    print(f"Base:          {pattern.base.value}")
    print(f"Phase:         {pattern.phase.value}")
    print(f"Load:          {pattern.load_vector.value}")
    print(f"Symmetry:      {pattern.symmetry:.3f}")
    print(f"Confidence:    {pattern.confidence:.3f}")
    if pattern.joint_angles:
        print(f"Joint angles:  {', '.join(f'{k}={v:.1f}°' for k, v in pattern.joint_angles.items())}")
