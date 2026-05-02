"""
test_angles.py — 不需要攝影機的單元測試
測試 compute_angles.py 的角度計算與錯誤偵測邏輯
"""

import math
import numpy as np
import sys

from compute_angles import (
    calculate_angular_velocity_dps,
    calculate_fppa,
    calculate_joint_angle,
    detect_back_slack,
    detect_heels_off,
    detect_knee_valgus,
    detect_knee_valgus_fppa,
    detect_rapid_movement,
    get_squat_phase,
    heel_angle,
)

passed = 0
failed = 0

def check(name, got, expected, tol=1.0):
    global passed, failed
    ok = abs(got - expected) <= tol if isinstance(expected, float) else (got == expected)
    status = "PASS" if ok else "FAIL"
    if ok:
        passed += 1
    else:
        failed += 1
    print(f"  [{status}] {name}: got={got}, expected={expected}")

def section(title):
    print(f"\n── {title} ──")


# ── 1. 直角測試（90°） ───────────────────────────────────────────
section("calculate_joint_angle")

right_angle = calculate_joint_angle(
    np.array([0.0, 1.0]),   # j1（上）
    np.array([0.0, 0.0]),   # j2（頂點）
    np.array([1.0, 0.0]),   # j3（右）
)
check("直角 90°", right_angle, 90.0)

straight = calculate_joint_angle(
    np.array([0.0, 1.0]),
    np.array([0.0, 0.0]),
    np.array([0.0, -1.0]),
)
check("直線 180°", straight, 180.0)

acute = calculate_joint_angle(
    np.array([1.0, 1.0]),
    np.array([0.0, 0.0]),
    np.array([1.0, 0.0]),
)
check("銳角 45°", acute, 45.0)


# ── 2. heel_angle ────────────────────────────────────────────────
section("heel_angle")

# atan2(heel - foot_index)：腳跟在腳尖左側 → 向量指向左 → 180°
# 這是「平放腳」的正常基準值（與 rohanx01 實作一致）
ha_flat = heel_angle(np.array([0.0, 0.0]), np.array([0.1, 0.0]))
check("腳跟平放基準值 = 180°", ha_flat, 180.0)

# 腳跟抬起：heel.y 減小（往上移），向量改變 → 角度偏離 180°
ha_up = heel_angle(np.array([0.0, 0.5]), np.array([0.1, 0.6]))
check("腳跟抬起角度 != 180°", abs(ha_up - 180.0) > 3, True)


# ── 3. detect_knee_valgus ────────────────────────────────────────
section("detect_knee_valgus")

# 膝距 = 踝距 → ratio=1.0 → 正常
check("正常膝距", detect_knee_valgus(1.0, 1.0), False)

# 膝距遠小於踝距 → 內夾
check("嚴重內夾 ratio=0.5", detect_knee_valgus(0.5, 1.0), True)

# 邊界值
check("邊界 ratio=0.74 → 內夾", detect_knee_valgus(0.74, 1.0), True)
check("邊界 ratio=0.76 → 正常", detect_knee_valgus(0.76, 1.0), False)


# ── 3B. FPPA（Frontal Plane Projection Angle）────────────────────
section("calculate_fppa / detect_knee_valgus_fppa")

fppa_straight = calculate_fppa(
    np.array([0.0, 0.0]),   # hip
    np.array([0.0, 1.0]),   # knee
    np.array([0.0, 2.0]),   # ankle
)
check("髖膝踝一直線 → FPPA 0°", fppa_straight, 0.0)

fppa_offset = calculate_fppa(
    np.array([0.0, 0.0]),
    np.array([0.2, 1.0]),
    np.array([0.0, 2.0]),
)
check("膝蓋偏離髖踝線 → FPPA > 10°", fppa_offset > 10.0, True)
check("FPPA 9° → 不觸發", detect_knee_valgus_fppa(9.0), False)
check("FPPA 11° → 觸發", detect_knee_valgus_fppa(11.0), True)


# ── 4. detect_back_slack ─────────────────────────────────────────
section("detect_back_slack")

check("正常軀幹 ratio=1.0", detect_back_slack(1.0, 1.0), False)
check("龜背 ratio=0.90",    detect_back_slack(0.90, 1.0), True)
check("邊界 ratio=0.93",    detect_back_slack(0.93, 1.0), False)
check("邊界 ratio=0.92",    detect_back_slack(0.92, 1.0), True)


# ── 5. detect_heels_off ──────────────────────────────────────────
section("detect_heels_off")

check("腳跟平放",         detect_heels_off(10.0, 10.0), False)
check("腳跟抬起 Δ=5°",   detect_heels_off(15.0, 10.0), True)
check("邊界 Δ=3° → 正常", detect_heels_off(13.0, 10.0), False)
check("邊界 Δ=4° → 抬起", detect_heels_off(14.0, 10.0), True)


# ── 5B. Rule 6：角速度 deg/s ────────────────────────────────────
section("calculate_angular_velocity_dps / detect_rapid_movement")

check(
    "角度 100°→130°，dt=0.1s → 300 deg/s",
    calculate_angular_velocity_dps(130.0, 100.0, 0.1),
    300.0,
)
check("第一幀無 previous angle → 0 deg/s", calculate_angular_velocity_dps(130.0, None, 0.1), 0.0)
check("無效 dt → 0 deg/s", calculate_angular_velocity_dps(130.0, 100.0, 0.0), 0.0)
check("600 deg/s 邊界 → 正常", detect_rapid_movement(600.0), False)
check("601 deg/s → 急速動作", detect_rapid_movement(601.0), True)


# ── 6. get_squat_phase ───────────────────────────────────────────
section("get_squat_phase")

check("站直 → top",              get_squat_phase(170.0, "top"),        "top")
check("下蹲中 from top",         get_squat_phase(130.0, "top"),        "descending")
check("下蹲中 from descending",  get_squat_phase(120.0, "descending"), "descending")
check("底部 < 110°",             get_squat_phase(95.0,  "descending"), "bottom")
check("起身中 from bottom",      get_squat_phase(120.0, "bottom"),     "ascending")
check("起身中 from ascending",   get_squat_phase(130.0, "ascending"),  "ascending")
check("回到站立",                get_squat_phase(165.0, "ascending"),  "top")


# ── 7. 真實深蹲幾何場景 ──────────────────────────────────────────
section("真實深蹲場景（正規化座標）")

# 模擬站立姿勢（y 向下為正）
stand_shoulder = np.array([0.5,  0.20])
stand_hip      = np.array([0.5,  0.50])
stand_knee     = np.array([0.5,  0.70])
stand_ankle    = np.array([0.5,  0.90])

knee_ang_stand = calculate_joint_angle(stand_hip, stand_knee, stand_ankle)
check("站立膝蓋角度 > 160°", knee_ang_stand > 160, True)

# 模擬深蹲底部（hip 大幅降低，knee 明顯前移，角度 < 110°）
# 使用 y 向下為正的正規化座標
squat_shoulder = np.array([0.5,  0.30])
squat_hip      = np.array([0.5,  0.68])
squat_knee     = np.array([0.62, 0.82])
squat_ankle    = np.array([0.5,  0.95])

knee_ang_squat = calculate_joint_angle(squat_hip, squat_knee, squat_ankle)
check("深蹲底部膝蓋角度 < 110°", knee_ang_squat < 110, True)

phase_bottom = get_squat_phase(knee_ang_squat, "descending")
check("深蹲底部階段 = bottom", phase_bottom, "bottom")


# ── 結果摘要 ─────────────────────────────────────────────────────
print(f"\n{'='*40}")
print(f"結果：{passed} PASS，{failed} FAIL")
print(f"{'='*40}")
sys.exit(0 if failed == 0 else 1)
