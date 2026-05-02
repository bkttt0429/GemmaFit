"""
compute_angles.py

從 data/processed/landmarks/ 的 CSV 計算每幀的關節角度特徵，
輸出到 data/processed/angles/。

各函式來源：
  calculate_joint_angle  → github.com/rohanx01/Squat-Analysis-Model (dot product 法，比 arctan2 更穩)
  back_slacking          → github.com/rohanx01/Squat-Analysis-Model (與起始姿勢比較)
  heels_off_ground       → github.com/rohanx01/Squat-Analysis-Model (heel-footindex 角度比較)
  knee_over_toes         → github.com/rohanx01/Squat-Analysis-Model (髖膝角度 + x 座標)
  knee_valgus_ratio      → squat_prototype.py（膝距 / 踝距）
  frontal_plane_projection_angle → Ugalde et al. 2015 / FPPA 文獻方向（前視角 2D 投影）
  angular_velocity_dps   → Rule 6 使用 deg/s，不使用 deg/frame，避免 FPS 依賴
  squat_phase            → 以 knee_hip_angle 做四段式狀態機（Nihar Palem 閾值）

用法：
  python compute_angles.py --input data/processed/landmarks/squat_zenodo_good.csv
  python compute_angles.py --input data/processed/landmarks/  # 批次處理整個資料夾
"""

import argparse
import csv
import math
import os
import sys

import numpy as np

INPUT_DIR = os.path.join("data", "processed", "landmarks")
OUTPUT_DIR = os.path.join("data", "processed", "angles")
os.makedirs(OUTPUT_DIR, exist_ok=True)

DEFAULT_FPS = 30.0
FPPA_VALGUS_THRESHOLD_DEG = 10.0       # prototype threshold; 待本地資料校準
RAPID_MOVEMENT_THRESHOLD_DPS = 600.0   # prototype threshold; Rule 6 統一用 deg/s

OUTPUT_HEADER = [
    "source", "frame", "label",
    "knee_angle",          # 髖-膝-踝 (理想 90-110°，下蹲底部)
    "hip_angle",           # 肩-髖-膝 (理想 80-100°，下蹲底部)
    "back_angle",          # 肩-髖-踝 (理想 70-90°)
    "torso_length_ratio",  # 當前肩髖距 / 站立時肩髖距 (< 0.93 → 龜背)
    "heel_angle",          # heel-foot_index 角度
    "heel_angle_delta",    # heel_angle - baseline（> 3° → 腳跟抬起）
    "valgus_ratio",        # 膝距 / 踝距（< 0.75 → 膝蓋內夾）
    "left_fppa",           # 前視角髖-膝-踝 2D 投影偏離直線角度（度）
    "right_fppa",
    "max_fppa",
    "knee_over_toes",      # 1 = 膝蓋超過腳尖, 0 = 正常
    "knee_velocity_dps",   # Rule 6: 連續幀角速度，單位 deg/s
    "hip_velocity_dps",
    "back_velocity_dps",
    "max_angular_velocity_dps",
    "rapid_movement",      # 1 = 任一追蹤角速度超過 rapid threshold
    "squat_phase",         # top / descending / bottom / ascending
    "back_slack",          # 1 = 偵測到龜背, 0 = 正常
    "heels_off",           # 1 = 腳跟抬起, 0 = 正常
    "knee_valgus_ratio",   # 1 = 膝距/踝距 heuristic 觸發
    "knee_valgus_fppa",    # 1 = FPPA heuristic 觸發
    "knee_valgus",         # 1 = 膝蓋內夾, 0 = 正常
]


# ── 核心角度計算 ────────────────────────────────────────────────
# Source: github.com/rohanx01/Squat-Analysis-Model

def calculate_joint_angle(j1: np.ndarray, j2: np.ndarray, j3: np.ndarray) -> float:
    """用 dot product 計算 j1-j2-j3 夾角（度）。j2 為頂點。"""
    v1 = j1 - j2
    v2 = j3 - j2
    cos_a = np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9)
    return float(np.degrees(np.arccos(np.clip(cos_a, -1.0, 1.0))))


def heel_angle(heel: np.ndarray, foot_index: np.ndarray) -> float:
    """heel → foot_index 方向角（度）。"""
    return abs(math.degrees(math.atan2(
        heel[1] - foot_index[1],
        heel[0] - foot_index[0]
    )))


def knee_hip_angle(hip: np.ndarray, knee: np.ndarray) -> float:
    """hip → knee 方向角（度），用於深蹲階段判斷與 rep 計數。"""
    return abs(math.degrees(math.atan2(
        hip[1] - knee[1],
        hip[0] - knee[0]
    )))


def calculate_fppa(hip: np.ndarray, knee: np.ndarray, ankle: np.ndarray) -> float:
    """
    Frontal Plane Projection Angle (FPPA) 的簡化 2D 投影版本。

    前視角下，髖-膝-踝若接近一直線，FPPA 約為 0°；膝蓋偏離髖踝連線時，
    FPPA 會增加。此值不判斷內/外側方向，只輸出偏離量，方向判斷留給後續
    C++ 以身體中線與左右側資訊處理。
    """
    joint_angle = calculate_joint_angle(hip, knee, ankle)
    return abs(180.0 - joint_angle)


def calculate_angular_velocity_dps(current_angle: float, previous_angle: float | None, dt_seconds: float | None) -> float:
    """計算角速度，單位 deg/s。第一幀或無效 dt 回傳 0。"""
    if previous_angle is None or dt_seconds is None or dt_seconds <= 0:
        return 0.0
    return abs(current_angle - previous_angle) / dt_seconds


def parse_frame_index(frame: str) -> float | None:
    """CSV frame 欄位可能是字串；無法解析時回傳 None。"""
    try:
        return float(frame)
    except (TypeError, ValueError):
        return None


def estimate_dt_seconds(current_frame: str, previous_frame: str | None, fps: float) -> float | None:
    """用 frame delta / fps 估算連續列時間差。"""
    if fps <= 0:
        return None
    current_idx = parse_frame_index(current_frame)
    previous_idx = parse_frame_index(previous_frame) if previous_frame is not None else None
    if current_idx is None or previous_idx is None:
        return None
    frame_delta = current_idx - previous_idx
    if frame_delta <= 0:
        return None
    return frame_delta / fps


# ── 深蹲階段狀態機 ──────────────────────────────────────────────
# 閾值參考：Nihar Palem (2024) Squat Form Analysis Using MediaPipe
# knee_angle 與 squat_prototype.py 的起立/下蹲邏輯一致

def get_squat_phase(knee_ang: float, prev_phase: str) -> str:
    """
    以膝蓋角度定義四段式深蹲階段。
    top:        knee_angle > 160
    descending: 160 >= knee_angle >= 110，前一段為 top
    bottom:     knee_angle < 110
    ascending:  knee_angle >= 110，前一段為 bottom
    """
    if knee_ang > 160:
        return "top"
    elif knee_ang < 110:
        return "bottom"
    elif prev_phase in ("top", "descending"):
        return "descending"
    else:
        return "ascending"


# ── 錯誤偵測 ────────────────────────────────────────────────────
# Source: github.com/rohanx01/Squat-Analysis-Model

def detect_back_slack(torso_length: float, baseline_length: float, tolerance: float = 7.0) -> bool:
    """
    龜背：當前肩髖距比站立基準短超過 tolerance pixels（正規化座標需換算）。
    使用正規化版本：ratio < 0.93。
    """
    if baseline_length < 1e-6:
        return False
    return (torso_length / baseline_length) < 0.93


def detect_heels_off(current_heel_angle: float, baseline_heel_angle: float, tolerance: float = 3.0) -> bool:
    """腳跟抬起：heel 角度比站立基準大超過 tolerance 度。"""
    return current_heel_angle > baseline_heel_angle + tolerance


def detect_knee_valgus(knee_dist: float, ankle_dist: float, threshold: float = 0.75) -> bool:
    """膝蓋內夾：膝距 / 踝距 < threshold。"""
    return (knee_dist / (ankle_dist + 1e-9)) < threshold


def detect_knee_valgus_fppa(max_fppa: float, threshold: float = FPPA_VALGUS_THRESHOLD_DEG) -> bool:
    """FPPA heuristic：偏離直線角度過大時標記為膝關節 frontal-plane 偏移。"""
    return max_fppa > threshold


def detect_rapid_movement(max_velocity_dps: float, threshold_dps: float = RAPID_MOVEMENT_THRESHOLD_DPS) -> bool:
    """Rule 6：角速度使用 deg/s，不使用 deg/frame。"""
    return max_velocity_dps > threshold_dps


def detect_knee_over_toes(hip: np.ndarray, knee: np.ndarray, foot_index: np.ndarray) -> bool:
    """
    膝蓋超過腳尖：hip-knee 角度 < 44° 且 knee.x > foot_index.x（側面視角）。
    Source: rohanx01/Squat-Analysis-Model knee_over_toes()
    """
    angle = abs(math.degrees(math.atan2(hip[1] - knee[1], hip[0] - knee[0])))
    return angle < 44 and knee[0] > foot_index[0]


# ── CSV 處理 ─────────────────────────────────────────────────────

def lm(row: dict, name: str) -> np.ndarray:
    """從 CSV 列取出指定 landmark 的 (x, y) numpy array。"""
    return np.array([float(row[f"{name}_x"]), float(row[f"{name}_y"])])


def process_csv(
    input_csv: str,
    output_csv: str,
    fps: float = DEFAULT_FPS,
    rapid_threshold_dps: float = RAPID_MOVEMENT_THRESHOLD_DPS,
):
    print(f"處理：{input_csv}")

    rows_in = []
    with open(input_csv, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows_in = list(reader)

    if not rows_in:
        print("  [skip] 空檔案")
        return

    rows_out = []
    baseline_torso = None
    baseline_heel = None
    prev_phase = "top"
    prev_source = None
    prev_frame = None
    prev_knee_angle = None
    prev_hip_angle = None
    prev_back_angle = None

    for row in rows_in:
        source = row.get("source", "")
        frame = row.get("frame", "")
        label = row.get("label", "")

        try:
            shoulder  = lm(row, "left_shoulder")
            hip       = lm(row, "left_hip")
            knee      = lm(row, "left_knee")
            ankle     = lm(row, "left_ankle")
            heel      = lm(row, "left_heel")
            foot_idx  = lm(row, "left_foot_index")
            r_hip     = lm(row, "right_hip")
            r_knee    = lm(row, "right_knee")
            r_ankle   = lm(row, "right_ankle")
        except (KeyError, ValueError):
            continue

        if prev_source != source:
            prev_phase = "top"
            prev_frame = None
            prev_knee_angle = None
            prev_hip_angle = None
            prev_back_angle = None

        # 關節角度
        k_angle = calculate_joint_angle(hip, knee, ankle)
        h_angle = calculate_joint_angle(shoulder, hip, knee)
        b_angle = calculate_joint_angle(shoulder, hip, ankle)

        # 軀幹長度（正規化座標，0~1）
        torso_len = float(np.linalg.norm(shoulder - hip))

        # Heel 角度
        h_heel = heel_angle(heel, foot_idx)

        # 站立基準：膝蓋角 > 167°（站直狀態）
        if k_angle > 167:
            baseline_torso = torso_len
            baseline_heel = h_heel

        torso_ratio = (torso_len / baseline_torso) if baseline_torso else 1.0
        heel_delta = (h_heel - baseline_heel) if baseline_heel is not None else 0.0

        # Valgus（雙腳距離比）
        knee_dist = float(np.linalg.norm(knee - r_knee))
        ankle_dist = float(np.linalg.norm(ankle - r_ankle))
        valgus = knee_dist / (ankle_dist + 1e-9)
        left_fppa = calculate_fppa(hip, knee, ankle)
        right_fppa = calculate_fppa(r_hip, r_knee, r_ankle)
        max_fppa = max(left_fppa, right_fppa)

        # Rule 6：角速度用 deg/s，dt 由 frame delta / fps 推估
        dt_seconds = estimate_dt_seconds(frame, prev_frame, fps)
        knee_velocity = calculate_angular_velocity_dps(k_angle, prev_knee_angle, dt_seconds)
        hip_velocity = calculate_angular_velocity_dps(h_angle, prev_hip_angle, dt_seconds)
        back_velocity = calculate_angular_velocity_dps(b_angle, prev_back_angle, dt_seconds)
        max_velocity = max(knee_velocity, hip_velocity, back_velocity)

        # 階段判斷
        phase = get_squat_phase(k_angle, prev_phase)
        prev_phase = phase

        # 錯誤旗標
        back_slack = int(detect_back_slack(torso_len, baseline_torso or torso_len))
        heels_off  = int(detect_heels_off(h_heel, baseline_heel or h_heel))
        valgus_ratio_err = int(detect_knee_valgus(knee_dist, ankle_dist))
        valgus_fppa_err = int(detect_knee_valgus_fppa(max_fppa))
        valgus_err = int(bool(valgus_ratio_err or valgus_fppa_err))
        kot_err    = int(detect_knee_over_toes(hip, knee, foot_idx))
        rapid_err = int(detect_rapid_movement(max_velocity, rapid_threshold_dps))

        rows_out.append([
            source, frame, label,
            round(k_angle, 3),
            round(h_angle, 3),
            round(b_angle, 3),
            round(torso_ratio, 4),
            round(h_heel, 3),
            round(heel_delta, 3),
            round(valgus, 4),
            round(left_fppa, 3),
            round(right_fppa, 3),
            round(max_fppa, 3),
            kot_err,
            round(knee_velocity, 3),
            round(hip_velocity, 3),
            round(back_velocity, 3),
            round(max_velocity, 3),
            rapid_err,
            phase,
            back_slack,
            heels_off,
            valgus_ratio_err,
            valgus_fppa_err,
            valgus_err,
        ])

        prev_source = source
        prev_frame = frame
        prev_knee_angle = k_angle
        prev_hip_angle = h_angle
        prev_back_angle = b_angle

    with open(output_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(OUTPUT_HEADER)
        writer.writerows(rows_out)

    print(f"  → {len(rows_out)} 列輸出：{output_csv}")


def make_output_path(input_csv: str) -> str:
    base = os.path.splitext(os.path.basename(input_csv))[0]
    return os.path.join(OUTPUT_DIR, f"{base}_angles.csv")


def main():
    parser = argparse.ArgumentParser(description="GemmaFit Angle Computer")
    parser.add_argument(
        "--input",
        required=True,
        help="輸入 CSV 檔案路徑，或包含多個 CSV 的資料夾路徑",
    )
    parser.add_argument(
        "--fps",
        type=float,
        default=DEFAULT_FPS,
        help=f"輸入 landmarks 的原始影片 FPS，用於 Rule 6 deg/s 計算（預設 {DEFAULT_FPS:g}）",
    )
    parser.add_argument(
        "--rapid-threshold-dps",
        type=float,
        default=RAPID_MOVEMENT_THRESHOLD_DPS,
        help=f"Rule 6 急速動作閾值，單位 deg/s（預設 {RAPID_MOVEMENT_THRESHOLD_DPS:g}，待校準）",
    )
    args = parser.parse_args()

    if os.path.isdir(args.input):
        csvs = [
            os.path.join(args.input, f)
            for f in sorted(os.listdir(args.input))
            if f.endswith(".csv")
        ]
        if not csvs:
            print(f"[錯誤] 在 {args.input} 找不到 CSV 檔。")
            sys.exit(1)
        for csv_path in csvs:
            process_csv(csv_path, make_output_path(csv_path), args.fps, args.rapid_threshold_dps)
    elif os.path.isfile(args.input):
        process_csv(args.input, make_output_path(args.input), args.fps, args.rapid_threshold_dps)
    else:
        print(f"[錯誤] 路徑不存在：{args.input}")
        sys.exit(1)


if __name__ == "__main__":
    main()
