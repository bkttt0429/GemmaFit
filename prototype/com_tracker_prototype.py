"""
com_tracker_prototype.py

全身質心（COM）估算 + 支撐多邊形（Base of Support）判斷。
對應 native/kinematics/com_tracker.cpp（Rule #5 質心偏移）。

架構：
  1. DE_LEVA_*   — 節段質量比例常數（TODO：等 Codex 交付 De Leva 1996 表格後填入）
  2. segment_com()    — 單一肢段質心座標
  3. whole_body_com() — 全身加權質心投影（XY 平面）
  4. support_polygon() — 支撐底面積頂點（TODO：等 Codex 交付凸包實作後填入）
  5. is_com_inside()   — COM 是否在支撐多邊形內
  6. com_offset_ratio()— COM 偏移量 / 多邊形半徑（用於 Rule 5 severity）

輸入：MediaPipe landmarks dict（正規化座標 0-1，y 向下為正）
輸出：COMResult dataclass

用法：
  from com_tracker_prototype import whole_body_com, is_com_inside, com_offset_ratio
"""

from __future__ import annotations
import math
from dataclasses import dataclass
from typing import NamedTuple

import numpy as np

# ─────────────────────────────────────────────────────────────────────────────
# De Leva (1996) segment mass and COM fractions.
#
# De Leva, P. (1996). Adjustments to Zatsiorsky-Seluyanov's segment inertia
# parameters. Journal of Biomechanics, 29(9), 1223-1230.
#
# 格式：每個肢段 → (質量佔全身比例, 近端到質心的比例)
# 近端 = 該肢段較靠近身體中心的那個關節
# Values are normalized fractions, not percentages. COM fractions are measured
# from the proximal endpoint used by each simplified MediaPipe segment.
# ─────────────────────────────────────────────────────────────────────────────

# (mass_fraction, proximal_com_fraction)
DE_LEVA_MALE = {
    # 肢段名稱        質量佔比   近端到質心比例
    "head":          (0.0694,   0.5002),
    "trunk":         (0.4346,   0.4486),
    "upper_arm_l":   (0.0271,   0.5772),
    "upper_arm_r":   (0.0271,   0.5772),
    "forearm_l":     (0.0162,   0.4574),
    "forearm_r":     (0.0162,   0.4574),
    "hand_l":        (0.0061,   0.3691),
    "hand_r":        (0.0061,   0.3691),
    "thigh_l":       (0.1416,   0.4095),
    "thigh_r":       (0.1416,   0.4095),
    "shank_l":       (0.0433,   0.4459),
    "shank_r":       (0.0433,   0.4459),
    "foot_l":        (0.0137,   0.4415),
    "foot_r":        (0.0137,   0.4415),
}

DE_LEVA_FEMALE = {
    "head":          (0.0668,   0.4841),
    "trunk":         (0.4257,   0.4203),
    "upper_arm_l":   (0.0255,   0.5754),
    "upper_arm_r":   (0.0255,   0.5754),
    "forearm_l":     (0.0138,   0.4559),
    "forearm_r":     (0.0138,   0.4559),
    "hand_l":        (0.0056,   0.3494),
    "hand_r":        (0.0056,   0.3494),
    "thigh_l":       (0.1478,   0.3612),
    "thigh_r":       (0.1478,   0.3612),
    "shank_l":       (0.0481,   0.4352),
    "shank_r":       (0.0481,   0.4352),
    "foot_l":        (0.0129,   0.4014),
    "foot_r":        (0.0129,   0.4014),
}


# ─────────────────────────────────────────────────────────────────────────────
# MediaPipe landmark 索引對應（33 keypoints，符合 CLAUDE.md：禁用魔術數字）
# ─────────────────────────────────────────────────────────────────────────────

class LM:
    NOSE            = 0
    LEFT_EAR        = 7;  RIGHT_EAR        = 8
    LEFT_SHOULDER   = 11; RIGHT_SHOULDER   = 12
    LEFT_ELBOW      = 13; RIGHT_ELBOW      = 14
    LEFT_WRIST      = 15; RIGHT_WRIST      = 16
    LEFT_HIP        = 23; RIGHT_HIP        = 24
    LEFT_KNEE       = 25; RIGHT_KNEE       = 26
    LEFT_ANKLE      = 27; RIGHT_ANKLE      = 28
    LEFT_HEEL       = 29; RIGHT_HEEL       = 30
    LEFT_FOOT_INDEX = 31; RIGHT_FOOT_INDEX = 32


class Point2D(NamedTuple):
    x: float
    y: float


@dataclass
class COMResult:
    com:              Point2D          # 全身質心投影（正規化座標）
    support_polygon:  list[Point2D]    # 支撐底面積頂點
    inside:           bool             # COM 是否在多邊形內
    offset_ratio:     float            # COM 偏移量 / 多邊形等效半徑（0=中心，1=邊界）
    evidence:         str = "prototype_threshold"
    note:             str = "estimated_com_not_force_plate"


# ─────────────────────────────────────────────────────────────────────────────
# 輔助：從 landmarks list 取座標
# ─────────────────────────────────────────────────────────────────────────────

def _lm(landmarks: list, idx: int) -> Point2D:
    l = landmarks[idx]
    return Point2D(l.x, l.y)


def _midpoint(a: Point2D, b: Point2D) -> Point2D:
    return Point2D((a.x + b.x) / 2, (a.y + b.y) / 2)


def _lerp(a: Point2D, b: Point2D, t: float) -> Point2D:
    """線性插值：t=0 → a，t=1 → b（用於近端→質心定位）"""
    return Point2D(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)


# ─────────────────────────────────────────────────────────────────────────────
# 1. 各肢段質心座標
# ─────────────────────────────────────────────────────────────────────────────

def segment_coms(landmarks: list, sex: str = "male") -> dict[str, Point2D]:
    """
    計算 14 個肢段的質心座標。
    De Leva proximal_com_fraction 決定質心在近端到遠端間的位置。

    """
    table = DE_LEVA_MALE if sex == "male" else DE_LEVA_FEMALE

    def com(proximal: Point2D, distal: Point2D, segment_key: str) -> Point2D:
        _, frac = table[segment_key]
        return _lerp(proximal, distal, frac)

    ls = landmarks   # shorthand
    coms: dict[str, Point2D] = {}

    l_sho = _lm(ls, LM.LEFT_SHOULDER);   r_sho = _lm(ls, LM.RIGHT_SHOULDER)
    l_elb = _lm(ls, LM.LEFT_ELBOW);      r_elb = _lm(ls, LM.RIGHT_ELBOW)
    l_wri = _lm(ls, LM.LEFT_WRIST);      r_wri = _lm(ls, LM.RIGHT_WRIST)
    l_hip = _lm(ls, LM.LEFT_HIP);        r_hip = _lm(ls, LM.RIGHT_HIP)
    l_kne = _lm(ls, LM.LEFT_KNEE);       r_kne = _lm(ls, LM.RIGHT_KNEE)
    l_ank = _lm(ls, LM.LEFT_ANKLE);      r_ank = _lm(ls, LM.RIGHT_ANKLE)
    l_toe = _lm(ls, LM.LEFT_FOOT_INDEX); r_toe = _lm(ls, LM.RIGHT_FOOT_INDEX)
    nose  = _lm(ls, LM.NOSE)
    mid_sho = _midpoint(l_sho, r_sho)
    mid_hip = _midpoint(l_hip, r_hip)

    coms["head"]        = com(mid_sho, nose,    "head")
    coms["trunk"]       = com(mid_sho, mid_hip, "trunk")
    coms["upper_arm_l"] = com(l_sho,  l_elb,   "upper_arm_l")
    coms["upper_arm_r"] = com(r_sho,  r_elb,   "upper_arm_r")
    coms["forearm_l"]   = com(l_elb,  l_wri,   "forearm_l")
    coms["forearm_r"]   = com(r_elb,  r_wri,   "forearm_r")
    coms["hand_l"]      = com(l_wri,  l_wri,   "hand_l")   # wrist≈hand tip
    coms["hand_r"]      = com(r_wri,  r_wri,   "hand_r")
    coms["thigh_l"]     = com(l_hip,  l_kne,   "thigh_l")
    coms["thigh_r"]     = com(r_hip,  r_kne,   "thigh_r")
    coms["shank_l"]     = com(l_kne,  l_ank,   "shank_l")
    coms["shank_r"]     = com(r_kne,  r_ank,   "shank_r")
    coms["foot_l"]      = com(l_ank,  l_toe,   "foot_l")
    coms["foot_r"]      = com(r_ank,  r_toe,   "foot_r")

    return coms


# ─────────────────────────────────────────────────────────────────────────────
# 2. 全身加權質心
# ─────────────────────────────────────────────────────────────────────────────

def whole_body_com(landmarks: list, sex: str = "male") -> Point2D:
    """
    De Leva 加權全身質心（XY 投影）。
    """
    table = DE_LEVA_MALE if sex == "male" else DE_LEVA_FEMALE
    coms  = segment_coms(landmarks, sex)

    total_mass = sum(table[k][0] for k in coms)
    if total_mass < 1e-9:
        return Point2D(0.5, 0.5)

    wx = sum(table[k][0] * coms[k].x for k in coms)
    wy = sum(table[k][0] * coms[k].y for k in coms)
    return Point2D(wx / total_mass, wy / total_mass)


# ─────────────────────────────────────────────────────────────────────────────
# 3. 支撐多邊形（Base of Support）
# ─────────────────────────────────────────────────────────────────────────────

def _convex_hull(points: list[Point2D]) -> list[Point2D]:
    """
    Andrew monotone-chain convex hull, O(n log n).
    Returns hull vertices in counter-clockwise order without repeating the first point.
    """
    unique = sorted(set(points))
    if len(unique) <= 1:
        return unique

    def cross(o: Point2D, a: Point2D, b: Point2D) -> float:
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    lower: list[Point2D] = []
    for p in unique:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)

    upper: list[Point2D] = []
    for p in reversed(unique):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)

    hull = lower[:-1] + upper[:-1]
    if len(hull) == 2 and hull[0] == hull[1]:
        return [hull[0]]
    return hull


def support_polygon(landmarks: list, contact: str = "bipedal") -> list[Point2D]:
    """
    計算支撐底面積頂點。

    contact 模式：
      "bipedal"  — 雙腳站立，四個頂點（雙腳跟 + 雙腳尖）
      "unipedal" — 單腳站立（左腳），兩個頂點（腳跟 + 腳尖）+ 小緩衝
      "hands_feet" — 撐地（伏地挺身等），四點 + 手部

    """
    l_heel = _lm(landmarks, LM.LEFT_HEEL)
    r_heel = _lm(landmarks, LM.RIGHT_HEEL)
    l_toe  = _lm(landmarks, LM.LEFT_FOOT_INDEX)
    r_toe  = _lm(landmarks, LM.RIGHT_FOOT_INDEX)

    if contact == "bipedal":
        pts = [l_heel, r_heel, r_toe, l_toe]
    elif contact == "unipedal":
        pts = [l_heel, l_toe]
    elif contact == "hands_feet":
        l_wri = _lm(landmarks, LM.LEFT_WRIST)
        r_wri = _lm(landmarks, LM.RIGHT_WRIST)
        pts = [l_heel, r_heel, r_toe, l_toe, l_wri, r_wri]
    else:
        pts = [l_heel, r_heel, r_toe, l_toe]

    return _convex_hull(pts)


# ─────────────────────────────────────────────────────────────────────────────
# 4. COM 是否在支撐多邊形內（射線法）
# ─────────────────────────────────────────────────────────────────────────────

def _point_in_polygon(pt: Point2D, polygon: list[Point2D]) -> bool:
    """射線投射法（Ray Casting）判斷點是否在多邊形內。"""
    n = len(polygon)
    if n < 3:
        # 退化多邊形：用距離判斷是否在緩衝範圍內
        cx = sum(p.x for p in polygon) / n
        cy = sum(p.y for p in polygon) / n
        return math.hypot(pt.x - cx, pt.y - cy) < 0.05

    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        if ((yi > pt.y) != (yj > pt.y)) and \
           (pt.x < (xj - xi) * (pt.y - yi) / (yj - yi + 1e-9) + xi):
            inside = not inside
        j = i
    return inside


def is_com_inside(com: Point2D, polygon: list[Point2D]) -> bool:
    return _point_in_polygon(com, polygon)


# ─────────────────────────────────────────────────────────────────────────────
# 5. COM 偏移比率（用於 Rule 5 severity）
# ─────────────────────────────────────────────────────────────────────────────

def com_offset_ratio(com: Point2D, polygon: list[Point2D]) -> float:
    """
    COM 到多邊形重心的距離 / 多邊形等效半徑。
    0.0 = 完全在中心，1.0 = 在邊界，> 1.0 = 超出邊界（Rule 5 觸發）。
    """
    if not polygon:
        return 0.0
    cx = sum(p.x for p in polygon) / len(polygon)
    cy = sum(p.y for p in polygon) / len(polygon)
    centroid = Point2D(cx, cy)

    # 等效半徑：多邊形各頂點到重心的平均距離
    radii = [math.hypot(p.x - cx, p.y - cy) for p in polygon]
    equiv_radius = sum(radii) / len(radii) if radii else 1e-6

    dist = math.hypot(com.x - centroid.x, com.y - centroid.y)
    return dist / (equiv_radius + 1e-9)


# ─────────────────────────────────────────────────────────────────────────────
# 6. 主要 API
# ─────────────────────────────────────────────────────────────────────────────

def track_com(landmarks: list, sex: str = "male",
              contact: str = "bipedal") -> COMResult:
    """
    整合入口：輸入 MediaPipe landmarks，回傳 COMResult。
    Rule 5 使用：result.inside == False 時觸發質心偏移警告。
    """
    com     = whole_body_com(landmarks, sex)
    polygon = support_polygon(landmarks, contact)

    # 重力投影：將 COM 投影到支撐多邊形所在的地面高度（image y 軸向下）
    # 生物力學穩定性取決於 COM 的水平投影是否落在 BoS 內
    if polygon:
        ground_y = sum(p.y for p in polygon) / len(polygon)
        projected = Point2D(com.x, ground_y)
    else:
        projected = com

    inside  = is_com_inside(projected, polygon)
    ratio   = com_offset_ratio(projected, polygon)

    return COMResult(
        com=com,
        support_polygon=polygon,
        inside=inside,
        offset_ratio=round(ratio, 4),
    )


# ─────────────────────────────────────────────────────────────────────────────
# 單元測試（不依賴 MediaPipe，用 mock landmarks）
# ─────────────────────────────────────────────────────────────────────────────

class _MockLandmark:
    def __init__(self, x, y, z=0.0):
        self.x = x; self.y = y; self.z = z


def _make_mock_landmarks(overrides: dict[int, tuple] = {}) -> list:
    """產生 33 個預設站立姿勢的 mock landmarks。"""
    defaults = {
        LM.NOSE:            (0.50, 0.05),
        LM.LEFT_EAR:        (0.45, 0.08), LM.RIGHT_EAR:        (0.55, 0.08),
        LM.LEFT_SHOULDER:   (0.40, 0.20), LM.RIGHT_SHOULDER:   (0.60, 0.20),
        LM.LEFT_ELBOW:      (0.35, 0.40), LM.RIGHT_ELBOW:      (0.65, 0.40),
        LM.LEFT_WRIST:      (0.32, 0.55), LM.RIGHT_WRIST:      (0.68, 0.55),
        LM.LEFT_HIP:        (0.43, 0.50), LM.RIGHT_HIP:        (0.57, 0.50),
        LM.LEFT_KNEE:       (0.43, 0.70), LM.RIGHT_KNEE:       (0.57, 0.70),
        LM.LEFT_ANKLE:      (0.43, 0.88), LM.RIGHT_ANKLE:      (0.57, 0.88),
        LM.LEFT_HEEL:       (0.42, 0.92), LM.RIGHT_HEEL:       (0.58, 0.92),
        LM.LEFT_FOOT_INDEX: (0.43, 0.98), LM.RIGHT_FOOT_INDEX: (0.57, 0.98),
    }
    defaults.update(overrides)
    lms = [_MockLandmark(0.5, 0.5) for _ in range(33)]
    for idx, (x, y) in defaults.items():
        lms[idx] = _MockLandmark(x, y)
    return lms


def _run_tests():
    import sys
    passed = failed = 0

    def check(name, got, expected, tol=None):
        nonlocal passed, failed
        if tol is not None:
            ok = abs(got - expected) <= tol
        else:
            ok = got == expected
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {got}")
        if not ok:
            failed += 1
            print(f"         expected: {expected}")
        else:
            passed += 1

    lms = _make_mock_landmarks()

    print("\n── segment_coms ──")
    coms = segment_coms(lms)
    check("trunk com exists",       "trunk" in coms,  True)
    check("all 14 segments",        len(coms),         14)
    check("thigh_l x in range",     0 < coms["thigh_l"].x < 1, True)

    print("\n── whole_body_com (De Leva weighted) ──")
    com = whole_body_com(lms)
    check("com.x near center",      com.x, 0.5, tol=0.15)
    check("com.y upper half",       com.y < 0.7, True)

    print("\n── support_polygon (bipedal) ──")
    poly = support_polygon(lms, "bipedal")
    check("bipedal 4 points",       len(poly), 4)

    print("\n── point_in_polygon ──")
    square = [Point2D(0,0), Point2D(1,0), Point2D(1,1), Point2D(0,1)]
    check("center inside",          _point_in_polygon(Point2D(0.5, 0.5), square), True)
    check("outside right",          _point_in_polygon(Point2D(1.5, 0.5), square), False)
    check("outside left edge",      _point_in_polygon(Point2D(-0.1, 0.5), square), False)

    print("\n── convex_hull ──")
    hull = _convex_hull([Point2D(1, 1), Point2D(0, 0), Point2D(1, 0), Point2D(0.5, 0.5), Point2D(0, 1)])
    check("hull removes interior point", len(hull), 4)

    print("\n── is_com_inside (standing) ──")
    result = track_com(lms)
    check("standing com inside",    result.inside, True)
    check("offset_ratio < 1",       result.offset_ratio < 1.0, True)
    check("evidence label",         result.evidence, "prototype_threshold")
    check("note label",             result.note, "estimated_com_not_force_plate")

    print("\n── com_offset_ratio ──")
    sq = [Point2D(0,0), Point2D(1,0), Point2D(1,1), Point2D(0,1)]
    ratio_center = com_offset_ratio(Point2D(0.5, 0.5), sq)
    ratio_edge   = com_offset_ratio(Point2D(1.5, 0.5), sq)
    check("center ratio ~0",        ratio_center < 0.1, True)
    check("outside ratio > 1",      ratio_edge   > 1.0, True)

    print(f"\n{'='*40}")
    print(f"結果：{passed} PASS，{failed} FAIL")
    print(f"[NOTE] COM uses De Leva (1996) segment mass and segment COM fractions")
    print(f"[NOTE] BoS uses Andrew monotone-chain convex hull")
    print(f"{'='*40}")
    sys.exit(0 if failed == 0 else 1)


if __name__ == "__main__":
    import sys
    if "--test" in sys.argv:
        _run_tests()
    else:
        lms = _make_mock_landmarks()
        result = track_com(lms)
        import json
        print(json.dumps({
            "com":            {"x": result.com.x, "y": result.com.y},
            "inside":         result.inside,
            "offset_ratio":   result.offset_ratio,
            "support_points": len(result.support_polygon),
            "evidence":       result.evidence,
            "note":           result.note,
        }, indent=2))
        print("\n（加上 --test 執行單元測試）")
