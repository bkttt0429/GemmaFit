"""
de_leva.py — De Leva (1996) 人體節段參數表

來源：
  De Leva, P. (1996). Adjustments to Zatsiorsky-Seluyanov's segment
  inertia parameters. Journal of Biomechanics, 29(9), 1223-1230.

提供兩種性別的：
  1. 節段質量比例（佔全身質量 %）
  2. 節段質心位置（從近端關節算起，佔節段長度 %）

用途：com_tracker.cpp 質心估算、MuscleFocusEstimator 力矩粗估。

注意：此處為標準成人平均值（男性 174cm/73kg，女性 164cm/60kg），
      不取代實際個體化量測。
"""

from typing import Dict, Tuple


# ── 節段質量比例 (% of total body mass) ─────────────────────────────
# De Leva 1996, Table 4 (male) and Table 5 (female)

SEGMENT_MASS_MALE: Dict[str, float] = {
    "head_neck":        6.94,
    "trunk":            43.46,
    "upper_arm_left":   2.71,
    "upper_arm_right":  2.71,
    "forearm_left":     1.62,
    "forearm_right":    1.62,
    "hand_left":        0.61,
    "hand_right":       0.61,
    "thigh_left":       14.16,
    "thigh_right":      14.16,
    "shank_left":       4.33,
    "shank_right":      4.33,
    "foot_left":        1.37,
    "foot_right":       1.37,
}

SEGMENT_MASS_FEMALE: Dict[str, float] = {
    "head_neck":        6.68,
    "trunk":            42.57,
    "upper_arm_left":   2.55,
    "upper_arm_right":  2.55,
    "forearm_left":     1.38,
    "forearm_right":    1.38,
    "hand_left":        0.56,
    "hand_right":       0.56,
    "thigh_left":       14.78,
    "thigh_right":      14.78,
    "shank_left":       4.81,
    "shank_right":      4.81,
    "foot_left":        1.29,
    "foot_right":       1.29,
}

# 驗證：質量加總應接近 100%（浮點四捨五入容忍 ±0.02%）
assert abs(sum(SEGMENT_MASS_MALE.values()) - 100.0) < 0.02, \
    f"Male total={sum(SEGMENT_MASS_MALE.values()):.2f}%"
assert abs(sum(SEGMENT_MASS_FEMALE.values()) - 100.0) < 0.02, \
    f"Female total={sum(SEGMENT_MASS_FEMALE.values()):.2f}%"

# ── 節段質心位置 (% from proximal joint end) ────────────────────────
# De Leva 1996, Table 4 (male) and Table 5 (female)
# 100% = 遠端關節, 0% = 近端關節

SEGMENT_COM_MALE: Dict[str, float] = {
    "head_neck":        50.02,   # vertex → C7 (40.98% from C7 toward vertex)
    "trunk":            44.86,   # hip center → shoulder midpoint
    "upper_arm":        57.72,   # shoulder → elbow
    "forearm":          45.74,   # elbow → wrist
    "hand":             36.91,   # wrist → third fingertip
    "thigh":            40.95,   # hip → knee
    "shank":            44.59,   # knee → ankle
    "foot":             44.15,   # heel → toe
}

SEGMENT_COM_FEMALE: Dict[str, float] = {
    "head_neck":        48.41,
    "trunk":            42.03,
    "upper_arm":        57.54,
    "forearm":          45.59,
    "hand":             34.94,
    "thigh":            36.12,
    "shank":            43.52,
    "foot":             40.14,
}


# ── 便利函數 ────────────────────────────────────────────────────────

def get_segment_mass(segment: str, female: bool = False) -> float:
    """取得節段質量 (% body mass)。"""
    table = SEGMENT_MASS_FEMALE if female else SEGMENT_MASS_MALE
    return table.get(segment, 0.0)


def get_segment_com(segment: str, female: bool = False) -> float:
    """取得節段質心距離（從近端算起，佔節段比例 0-1）。"""
    table = SEGMENT_COM_FEMALE if female else SEGMENT_COM
    val = table.get(segment, 50.0)
    return val / 100.0


def get_body_segment_params(female: bool = False) -> Tuple[Dict[str, float], Dict[str, float]]:
    """回傳 (mass_table, com_table) 以 % 為單位。"""
    if female:
        return SEGMENT_MASS_FEMALE.copy(), SEGMENT_COM_FEMALE.copy()
    return SEGMENT_MASS_MALE.copy(), SEGMENT_COM_MALE.copy()


# ── 質心估算 (簡化 2D 版) ────────────────────────────────────────────
# 配合 MediaPipe landmarks 做全身 COM 粗估
# 各節段端點使用 landmarks 對應索引（參見 KEYPOINT dict）

# 節段端點對應 (近端 landmark, 遠端 landmark)
SEGMENT_ENDPOINTS: Dict[str, Tuple[str, str]] = {
    "head_neck":         ("nose", "mid_shoulder"),          # 模擬頸部
    "trunk":             ("mid_shoulder", "mid_hip"),       # 軀幹
    "upper_arm_left":    ("left_shoulder", "left_elbow"),
    "upper_arm_right":   ("right_shoulder", "right_elbow"),
    "forearm_left":      ("left_elbow", "left_wrist"),
    "forearm_right":     ("right_elbow", "right_wrist"),
    "hand_left":         ("left_wrist", "left_index"),      # 簡化（掌到食指）
    "hand_right":        ("right_wrist", "right_index"),
    "thigh_left":        ("left_hip", "left_knee"),
    "thigh_right":       ("right_hip", "right_knee"),
    "shank_left":        ("left_knee", "left_ankle"),
    "shank_right":       ("right_knee", "right_ankle"),
    "foot_left":         ("left_heel", "left_foot_index"),
    "foot_right":        ("right_heel", "right_foot_index"),
}


def estimate_com(
    landmarks: "np.ndarray",
    body_mass_kg: float = 70.0,
    female: bool = False,
) -> "np.ndarray":
    """
    從 landmarks (33, 3) 估算全身質心投影位置。

    landmarks: numpy array (33, 3) — MediaPipe 輸出
    body_mass_kg: 體重 (kg)，預設 70 kg
    female: True 使用女性節段參數

    returns: numpy array [x, y] — 質心投影座標（正規化 0-1）
    """
    import numpy as np

    mass_table, com_table = get_body_segment_params(female)

    total_moment_x = 0.0
    total_moment_y = 0.0
    total_mass = 0.0

    for seg_name, (prox_key, dist_key) in SEGMENT_ENDPOINTS.items():
        mass_pct = mass_table.get(seg_name, 0.0)
        com_pct = com_table.get(seg_name, 50.0) / 100.0

        if mass_pct == 0:
            continue

        # 取得端點位置 (從 landmarks 取出)
        try:
            prox = _get_landmark_point(landmarks, prox_key)
            dist = _get_landmark_point(landmarks, dist_key)
        except (KeyError, IndexError):
            continue

        # 節段質心：沿著近端→遠端方向，com_pct 比例位置
        seg_com = prox + com_pct * (dist - prox)
        seg_mass = mass_pct / 100.0 * body_mass_kg

        total_moment_x += seg_com[0] * seg_mass
        total_moment_y += seg_com[1] * seg_mass
        total_mass += seg_mass

    if total_mass < 0.01:
        return np.array([0.5, 0.5])

    return np.array([total_moment_x / total_mass, total_moment_y / total_mass])


# ── 內部輔助 ────────────────────────────────────────────────────────

# 與 movement_classifier.py 保持一致的關鍵點索引
_KEYPOINT_INDEX = {
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


def _get_landmark_point(landmarks: "np.ndarray", key: str) -> "np.ndarray":
    """從 landmarks 陣列取出指定關鍵點 (x, y)。"""
    if key == "mid_shoulder":
        left = landmarks[_KEYPOINT_INDEX["left_shoulder"], :2]
        right = landmarks[_KEYPOINT_INDEX["right_shoulder"], :2]
        return (left + right) / 2
    if key == "mid_hip":
        left = landmarks[_KEYPOINT_INDEX["left_hip"], :2]
        right = landmarks[_KEYPOINT_INDEX["right_hip"], :2]
        return (left + right) / 2
    idx = _KEYPOINT_INDEX[key]
    return landmarks[idx, :2].copy()


# ── 測試 ────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("De Leva (1996) Segment Parameters\n")

    print("═ Segment Mass (% body mass) ═")
    print(f"{'Segment':<20} {'Male':>8} {'Female':>8}")
    print("-" * 38)
    for seg in SEGMENT_MASS_MALE:
        print(f"{seg:<20} {SEGMENT_MASS_MALE[seg]:>7.2f}% {SEGMENT_MASS_FEMALE[seg]:>7.2f}%")

    print(f"\n{'Total':<20} {sum(SEGMENT_MASS_MALE.values()):>7.2f}% {sum(SEGMENT_MASS_FEMALE.values()):>7.2f}%")

    print("\n═ Segment COM (% from proximal) ═")
    print(f"{'Segment':<20} {'Male':>8} {'Female':>8}")
    print("-" * 38)
    for seg in SEGMENT_COM_MALE:
        print(f"{seg:<20} {SEGMENT_COM_MALE[seg]:>7.2f}% {SEGMENT_COM_FEMALE[seg]:>7.2f}%")

    print("\n─ COM estimation demo ─")
    import numpy as np

    # 模擬站立 landmarks
    dummy = np.zeros((33, 3))
    for i in range(33):
        dummy[i, 2] = 0.9

    dummy[11] = [0.45, 0.30, 0.95]   # left_shoulder
    dummy[12] = [0.55, 0.30, 0.95]   # right_shoulder
    dummy[23] = [0.46, 0.55, 0.90]   # left_hip
    dummy[24] = [0.54, 0.55, 0.90]   # right_hip
    dummy[25] = [0.42, 0.75, 0.90]   # left_knee
    dummy[26] = [0.58, 0.75, 0.90]   # right_knee
    dummy[27] = [0.41, 0.93, 0.85]   # left_ankle
    dummy[28] = [0.59, 0.93, 0.85]   # right_ankle
    dummy[29] = [0.42, 0.95, 0.80]   # left_heel
    dummy[30] = [0.58, 0.95, 0.80]   # right_heel
    dummy[31] = [0.43, 0.97, 0.80]   # left_foot_index
    dummy[32] = [0.57, 0.97, 0.80]   # right_foot_index

    com = estimate_com(dummy, body_mass_kg=70.0)
    print(f"Estimated COM: ({com[0]:.3f}, {com[1]:.3f})")
    print(f"Expected: ~(0.50, ~0.55-0.60)")  # 接近軀幹中心
