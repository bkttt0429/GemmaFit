"""
movement_classifier_prototype.py

物理動作模式分類器：MediaPipe landmarks → MovementPattern。
不命名動作（不輸出「深蹲」），只輸出物理描述 + 動作向量。
輸出與 muscle_focus_prototype.estimate_muscle_focus() 直接相容。

分類優先序：
  1. 單腳支撐      → unilateral_stability
  2. 核心撐地      → core_anti_extension
  3. 下肢主導      → knee_dominant_bilateral / hip_dominant_bilateral
  4. 上肢主導      → horizontal_push / vertical_push
  5. 無明顯動作    → unknown

用法：
  python movement_classifier_prototype.py          # 示範
  python movement_classifier_prototype.py --test   # 單元測試
"""

from __future__ import annotations
import math
from dataclasses import dataclass
from typing import Optional


# ── MediaPipe landmark 索引（禁用魔術數字）────────────────────────────────
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


# ── 閾值（集中管理，C++ 移植時逐一對應）────────────────────────────────────
_T = {
    "visibility_min":         0.5,   # landmark 可見度門檻
    "unipedal_y_diff":        0.15,  # 兩踝 y 差超過此值 → 視為單腳
    "trunk_horiz_tol":        0.15,  # 肩-髖 y 差 < 此值 → 軀幹水平（plank）
    "wrist_floor_tol":        0.20,  # 手腕-腳踝 y 差 < 此值 → 手撐地
    "knee_flexion_min":       15.0,  # 膝屈曲 > 此值 → 下肢活躍（°）
    "hip_flexion_min":        15.0,  # 髖屈曲 > 此值 → 下肢活躍（°）
    "knee_vs_hip_ratio":       0.7,  # knee_flex >= hip_flex * ratio → 膝主導
    "elbow_flexion_min":      25.0,  # 肘屈曲 > 此值 → 上肢活躍（°）
    "leg_straight_max":       30.0,  # 膝屈曲 < 此值 → 腿視為直（上肢判斷用）
    "wrist_above_head":        0.05, # sho.y - wri.y > 此值 → 手腕在頭頂（y 向下為正）
    "wrist_at_shoulder_tol":   0.15, # |wri.y - sho.y| <= 此值 → 水平方向
    "phase_delta_min":         3.0,  # 角度幀差 > 此值才算「移動中」（°）
}


# ── 幾何輔助 ────────────────────────────────────────────────────────────────

def _lm(landmarks, idx):
    return landmarks[idx]


def _angle_at(a, b, c) -> float:
    """計算在頂點 b 處的向量夾角（0–180°）。"""
    ax, ay = a.x - b.x, a.y - b.y
    cx, cy = c.x - b.x, c.y - b.y
    dot = ax * cx + ay * cy
    mag = math.hypot(ax, ay) * math.hypot(cx, cy)
    if mag < 1e-9:
        return 0.0
    return math.degrees(math.acos(max(-1.0, min(1.0, dot / mag))))


def _visible(landmark, thr: float) -> bool:
    return getattr(landmark, "visibility", 1.0) >= thr


# ── 輸出結構 ─────────────────────────────────────────────────────────────────

@dataclass
class MovementPattern:
    pattern:       str   # 與 muscle_focus_prototype._MUSCLE_MAP 的鍵一致
    primary_joint: str   # "knee" | "hip" | "elbow" | "shoulder" | "trunk" | "unknown"
    base:          str   # "bipedal" | "unipedal"
    load_vector:   str   # "horizontal" | "vertical" | "unknown"
    phase:         str   # "ascent" | "descent" | "hold" | "unknown"
    confidence:    str   # "high" | "medium" | "low" | "unknown"
    basis:         str   # 判斷依據字串（debug / C++ 對照用）

    def to_dict(self) -> dict:
        return {
            "pattern":       self.pattern,
            "primary_joint": self.primary_joint,
            "base":          self.base,
            "load_vector":   self.load_vector,
            "phase":         self.phase,
            "confidence":    self.confidence,
            "basis":         self.basis,
        }


# ── 子判斷函式 ───────────────────────────────────────────────────────────────

def _detect_base(landmarks) -> str:
    l_ank = _lm(landmarks, LM.LEFT_ANKLE)
    r_ank = _lm(landmarks, LM.RIGHT_ANKLE)
    if not _visible(l_ank, _T["visibility_min"]) or \
       not _visible(r_ank, _T["visibility_min"]):
        return "unipedal"
    if abs(l_ank.y - r_ank.y) > _T["unipedal_y_diff"]:
        return "unipedal"
    return "bipedal"


def _joint_angles(landmarks) -> dict[str, float]:
    ls = landmarks
    raw = {
        "knee_l":  _angle_at(_lm(ls, LM.LEFT_HIP),      _lm(ls, LM.LEFT_KNEE),   _lm(ls, LM.LEFT_ANKLE)),
        "knee_r":  _angle_at(_lm(ls, LM.RIGHT_HIP),     _lm(ls, LM.RIGHT_KNEE),  _lm(ls, LM.RIGHT_ANKLE)),
        "hip_l":   _angle_at(_lm(ls, LM.LEFT_SHOULDER),  _lm(ls, LM.LEFT_HIP),    _lm(ls, LM.LEFT_KNEE)),
        "hip_r":   _angle_at(_lm(ls, LM.RIGHT_SHOULDER), _lm(ls, LM.RIGHT_HIP),   _lm(ls, LM.RIGHT_KNEE)),
        "elbow_l": _angle_at(_lm(ls, LM.LEFT_SHOULDER),  _lm(ls, LM.LEFT_ELBOW),  _lm(ls, LM.LEFT_WRIST)),
        "elbow_r": _angle_at(_lm(ls, LM.RIGHT_SHOULDER), _lm(ls, LM.RIGHT_ELBOW), _lm(ls, LM.RIGHT_WRIST)),
    }
    a = raw
    a["knee_avg"]      = (a["knee_l"]  + a["knee_r"])  / 2
    a["hip_avg"]       = (a["hip_l"]   + a["hip_r"])   / 2
    a["elbow_avg"]     = (a["elbow_l"] + a["elbow_r"]) / 2
    a["knee_flexion"]  = max(0.0, 180.0 - a["knee_avg"])
    a["hip_flexion"]   = max(0.0, 180.0 - a["hip_avg"])
    a["elbow_flexion"] = max(0.0, 180.0 - a["elbow_avg"])
    return a


def _detect_phase(angles: dict, prev_angles: Optional[dict]) -> str:
    if prev_angles is None:
        return "unknown"
    delta = ((angles["knee_avg"] - prev_angles.get("knee_avg", angles["knee_avg"])) +
             (angles["hip_avg"]  - prev_angles.get("hip_avg",  angles["hip_avg"]))) / 2
    if delta >  _T["phase_delta_min"]:  return "ascent"
    if delta < -_T["phase_delta_min"]:  return "descent"
    return "hold"


def _classify_upper(landmarks, angles: dict, base: str, phase: str
                    ) -> Optional[MovementPattern]:
    """
    上肢模式判斷。
    靜態姿態無法可靠區分 push vs pull，預設輸出 push（confidence=low）。
    """
    ls = landmarks
    l_sho = _lm(ls, LM.LEFT_SHOULDER);  r_sho = _lm(ls, LM.RIGHT_SHOULDER)
    l_wri = _lm(ls, LM.LEFT_WRIST);     r_wri = _lm(ls, LM.RIGHT_WRIST)
    avg_sho_y = (l_sho.y + r_sho.y) / 2
    avg_wri_y = (l_wri.y + r_wri.y) / 2

    # 手腕在肩膀上方（y 向下，sho.y > wri.y → 手腕更高）
    if avg_sho_y - avg_wri_y > _T["wrist_above_head"]:
        return MovementPattern(
            "vertical_push", "shoulder", base, "vertical", phase, "low",
            f"wrists_above_shoulders_by_{avg_sho_y - avg_wri_y:.2f}",
        )

    # 手腕在肩膀同高且肘關節屈曲 → 水平動作
    if (abs(avg_wri_y - avg_sho_y) <= _T["wrist_at_shoulder_tol"] and
            angles["elbow_flexion"] >= _T["elbow_flexion_min"]):
        return MovementPattern(
            "horizontal_push", "elbow", base, "horizontal", phase, "low",
            f"wrists_at_shoulder_height_elbow_flex={angles['elbow_flexion']:.1f}",
        )

    return None


# ── 主要 API ─────────────────────────────────────────────────────────────────

def classify_movement(
    landmarks,
    prev_landmarks=None,
    prev_angles: Optional[dict] = None,
) -> MovementPattern:
    """
    物理動作模式分類。

    Args:
        landmarks:      33 個 MediaPipe landmark（需有 .x .y 屬性）
        prev_landmarks: 前一幀 landmarks（可選，用於相位偵測）
        prev_angles:    前一幀角度 dict（可選，優先於 prev_landmarks）

    Returns:
        MovementPattern（to_dict() 可直接傳入 estimate_muscle_focus()）
    """
    base   = _detect_base(landmarks)
    angles = _joint_angles(landmarks)

    if prev_angles is None and prev_landmarks is not None:
        prev_angles = _joint_angles(prev_landmarks)
    phase = _detect_phase(angles, prev_angles)

    # ── 規則 1：單腳支撐 ──────────────────────────────────────────────────
    if base == "unipedal":
        return MovementPattern(
            "unilateral_stability", "hip", "unipedal", "vertical",
            phase, "high", "single_limb_support_detected",
        )

    # ── 規則 2：核心撐地（plank / push-up）────────────────────────────────
    ls = landmarks
    avg_sho_y = (_lm(ls, LM.LEFT_SHOULDER).y + _lm(ls, LM.RIGHT_SHOULDER).y) / 2
    avg_hip_y = (_lm(ls, LM.LEFT_HIP).y      + _lm(ls, LM.RIGHT_HIP).y)      / 2
    avg_wri_y = (_lm(ls, LM.LEFT_WRIST).y    + _lm(ls, LM.RIGHT_WRIST).y)    / 2
    avg_ank_y = (_lm(ls, LM.LEFT_ANKLE).y    + _lm(ls, LM.RIGHT_ANKLE).y)    / 2

    trunk_horiz   = abs(avg_sho_y - avg_hip_y) < _T["trunk_horiz_tol"]
    wrists_grounded = abs(avg_wri_y - avg_ank_y) < _T["wrist_floor_tol"]

    if trunk_horiz and wrists_grounded:
        return MovementPattern(
            "core_anti_extension", "trunk", "bipedal", "horizontal",
            phase, "high", "trunk_horizontal_wrists_at_floor",
        )

    # ── 規則 3：下肢主導 ──────────────────────────────────────────────────
    kf = angles["knee_flexion"]
    hf = angles["hip_flexion"]

    if kf >= _T["knee_flexion_min"] or hf >= _T["hip_flexion_min"]:
        if kf >= hf * _T["knee_vs_hip_ratio"]:
            return MovementPattern(
                "knee_dominant_bilateral", "knee", base, "vertical",
                phase, "high",
                f"knee_flex={kf:.1f}_hip_flex={hf:.1f}",
            )
        else:
            return MovementPattern(
                "hip_dominant_bilateral", "hip", base, "vertical",
                phase, "high",
                f"hip_flex={hf:.1f}_dominates_knee_flex={kf:.1f}",
            )

    # ── 規則 4：上肢主導（腿部接近直立時才判斷）──────────────────────────
    if kf < _T["leg_straight_max"]:
        upper = _classify_upper(landmarks, angles, base, phase)
        if upper is not None:
            return upper

    # ── Fallback：站立靜止或無法識別 ─────────────────────────────────────
    return MovementPattern(
        "unknown", "unknown", base, "unknown",
        phase, "unknown", "no_dominant_movement_detected",
    )


# ── Mock landmarks（不依賴 MediaPipe，用於單元測試）──────────────────────────

class _ML:
    """Mock Landmark with optional visibility."""
    __slots__ = ("x", "y", "z", "visibility")
    def __init__(self, x, y, z=0.0, visibility=1.0):
        self.x = x; self.y = y; self.z = z; self.visibility = visibility


def _standing() -> list:
    """預設站立姿勢（所有關節接近伸直）。"""
    base = {
        LM.NOSE:            (0.50, 0.05),
        LM.LEFT_SHOULDER:   (0.40, 0.20), LM.RIGHT_SHOULDER:   (0.60, 0.20),
        LM.LEFT_ELBOW:      (0.38, 0.38), LM.RIGHT_ELBOW:      (0.62, 0.38),
        LM.LEFT_WRIST:      (0.37, 0.54), LM.RIGHT_WRIST:      (0.63, 0.54),
        LM.LEFT_HIP:        (0.43, 0.50), LM.RIGHT_HIP:        (0.57, 0.50),
        LM.LEFT_KNEE:       (0.43, 0.70), LM.RIGHT_KNEE:       (0.57, 0.70),
        LM.LEFT_ANKLE:      (0.43, 0.88), LM.RIGHT_ANKLE:      (0.57, 0.88),
        LM.LEFT_HEEL:       (0.42, 0.92), LM.RIGHT_HEEL:       (0.58, 0.92),
        LM.LEFT_FOOT_INDEX: (0.43, 0.98), LM.RIGHT_FOOT_INDEX: (0.57, 0.98),
    }
    lms = [_ML(0.5, 0.5) for _ in range(33)]
    for idx, (x, y) in base.items():
        lms[idx] = _ML(x, y)
    return lms


def _override(lms: list, overrides: dict) -> list:
    """複製 landmark list 並套用覆寫（不修改原始 list）。"""
    new = list(lms)
    for idx, val in overrides.items():
        if len(val) == 2:
            new[idx] = _ML(val[0], val[1])
        else:
            new[idx] = _ML(val[0], val[1], val[2], val[3] if len(val) > 3 else 1.0)
    return new


# ── 單元測試 ──────────────────────────────────────────────────────────────────

def _run_tests():
    import sys
    passed = failed = 0

    def check(name, got, expected):
        nonlocal passed, failed
        ok = got == expected
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {got}")
        if not ok:
            failed += 1
            print(f"         expected: {expected}")
        else:
            passed += 1

    def checkf(name, got, lo, hi):
        nonlocal passed, failed
        ok = lo <= got <= hi
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {got:.2f}  (expect [{lo},{hi}])")
        if not ok: failed += 1
        else:      passed += 1

    base_lms = _standing()

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 1. 深蹲（knee_dominant_bilateral）──")
    squat = _override(base_lms, {
        LM.LEFT_HIP:   (0.43, 0.62), LM.RIGHT_HIP:   (0.57, 0.62),
        LM.LEFT_KNEE:  (0.35, 0.72), LM.RIGHT_KNEE:  (0.65, 0.72),
    })
    r = classify_movement(squat)
    check("pattern", r.pattern, "knee_dominant_bilateral")
    check("primary_joint", r.primary_joint, "knee")
    check("base", r.base, "bipedal")
    check("confidence", r.confidence, "high")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 2. 折髖（hip_dominant_bilateral）──")
    hinge = _override(base_lms, {
        LM.LEFT_SHOULDER:  (0.30, 0.45), LM.RIGHT_SHOULDER: (0.50, 0.45),
        LM.LEFT_HIP:       (0.43, 0.45), LM.RIGHT_HIP:      (0.57, 0.45),
        # 膝蓋保持接近直立（y 位置不變）
    })
    r = classify_movement(hinge)
    check("pattern", r.pattern, "hip_dominant_bilateral")
    check("primary_joint", r.primary_joint, "hip")
    check("load_vector", r.load_vector, "vertical")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 3. 撐地（core_anti_extension）──")
    plank = _override(base_lms, {
        LM.LEFT_SHOULDER:  (0.30, 0.45), LM.RIGHT_SHOULDER: (0.50, 0.45),
        LM.LEFT_HIP:       (0.50, 0.45), LM.RIGHT_HIP:      (0.65, 0.45),
        LM.LEFT_WRIST:     (0.20, 0.88), LM.RIGHT_WRIST:    (0.40, 0.88),
        LM.LEFT_ANKLE:     (0.70, 0.88), LM.RIGHT_ANKLE:    (0.85, 0.88),
        LM.LEFT_KNEE:      (0.60, 0.65), LM.RIGHT_KNEE:     (0.75, 0.65),
    })
    r = classify_movement(plank)
    check("pattern", r.pattern, "core_anti_extension")
    check("primary_joint", r.primary_joint, "trunk")
    check("load_vector", r.load_vector, "horizontal")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 4. 單腳支撐（unilateral_stability）──")
    # 右踝不可見 → 單腳
    single = _override(base_lms, {
        LM.RIGHT_ANKLE: (0.57, 0.88, 0.0, 0.0),  # visibility=0
    })
    r = classify_movement(single)
    check("pattern", r.pattern, "unilateral_stability")
    check("base", r.base, "unipedal")
    check("confidence", r.confidence, "high")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 5. 垂直推（vertical_push）──")
    vpress = _override(base_lms, {
        LM.LEFT_ELBOW:  (0.35, 0.15), LM.RIGHT_ELBOW: (0.65, 0.15),
        LM.LEFT_WRIST:  (0.40, 0.05), LM.RIGHT_WRIST: (0.60, 0.05),
    })
    r = classify_movement(vpress)
    check("pattern", r.pattern, "vertical_push")
    check("load_vector", r.load_vector, "vertical")
    check("confidence", r.confidence, "low")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 6. 水平推（horizontal_push）──")
    hpress = _override(base_lms, {
        LM.LEFT_ELBOW:  (0.25, 0.30), LM.RIGHT_ELBOW: (0.75, 0.30),
        LM.LEFT_WRIST:  (0.40, 0.35), LM.RIGHT_WRIST: (0.60, 0.35),
    })
    r = classify_movement(hpress)
    check("pattern", r.pattern, "horizontal_push")
    check("load_vector", r.load_vector, "horizontal")
    check("confidence", r.confidence, "low")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 7. 動作相位偵測 ──")
    prev_a = {"knee_avg": 130.0, "hip_avg": 140.0}   # 前幀更屈曲
    curr_a = _joint_angles(base_lms)                  # 現在更接近直立
    phase  = _detect_phase(curr_a, prev_a)
    check("ascent detected", phase, "ascent")

    prev_a2 = {"knee_avg": 175.0, "hip_avg": 175.0}  # 前幀更直立
    phase2  = _detect_phase(_joint_angles(squat), prev_a2)
    check("descent detected", phase2, "descent")

    phase3 = _detect_phase(curr_a, None)
    check("unknown when no prev", phase3, "unknown")

    hold_a = {"knee_avg": curr_a["knee_avg"], "hip_avg": curr_a["hip_avg"]}
    phase4 = _detect_phase(curr_a, hold_a)
    check("hold detected", phase4, "hold")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 8. 站立靜止（unknown）──")
    r = classify_movement(base_lms)
    check("pattern", r.pattern, "unknown")
    check("confidence", r.confidence, "unknown")

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 9. to_dict() 格式 ──")
    d = classify_movement(squat).to_dict()
    for key in ("pattern", "primary_joint", "base", "load_vector", "phase",
                "confidence", "basis"):
        check(f"key '{key}' exists", key in d, True)

    # ──────────────────────────────────────────────────────────────────────
    print("\n── 10. muscle_focus 整合 ──")
    try:
        from muscle_focus_prototype import estimate_muscle_focus
        mp_dict = classify_movement(squat).to_dict()
        mf = estimate_muscle_focus(mp_dict)
        check("mf.pattern_matched", mf.pattern_matched, "knee_dominant_bilateral")
        check("mf.confidence", mf.confidence, "high")
        check("quadriceps in primary", "quadriceps" in mf.estimated_primary, True)
    except ImportError:
        print("  [SKIP] muscle_focus_prototype not found")

    print(f"\n{'='*44}")
    print(f"結果：{passed} PASS，{failed} FAIL")
    print(f"{'='*44}")
    import sys
    sys.exit(0 if failed == 0 else 1)


# ── 示範輸出 ──────────────────────────────────────────────────────────────────

def _demo():
    import json
    base = _standing()

    scenarios = [
        ("深蹲",     _override(base, {
            LM.LEFT_HIP:  (0.43, 0.62), LM.RIGHT_HIP:  (0.57, 0.62),
            LM.LEFT_KNEE: (0.35, 0.72), LM.RIGHT_KNEE: (0.65, 0.72),
        })),
        ("折髖",     _override(base, {
            LM.LEFT_SHOULDER:  (0.30, 0.45), LM.RIGHT_SHOULDER: (0.50, 0.45),
            LM.LEFT_HIP:       (0.43, 0.45), LM.RIGHT_HIP:      (0.57, 0.45),
        })),
        ("撐地",     _override(base, {
            LM.LEFT_SHOULDER:  (0.30, 0.45), LM.RIGHT_SHOULDER: (0.50, 0.45),
            LM.LEFT_HIP:       (0.50, 0.45), LM.RIGHT_HIP:      (0.65, 0.45),
            LM.LEFT_WRIST:     (0.20, 0.88), LM.RIGHT_WRIST:    (0.40, 0.88),
            LM.LEFT_ANKLE:     (0.70, 0.88), LM.RIGHT_ANKLE:    (0.85, 0.88),
            LM.LEFT_KNEE:      (0.60, 0.65), LM.RIGHT_KNEE:     (0.75, 0.65),
        })),
        ("垂直推",   _override(base, {
            LM.LEFT_ELBOW:  (0.35, 0.15), LM.RIGHT_ELBOW: (0.65, 0.15),
            LM.LEFT_WRIST:  (0.40, 0.05), LM.RIGHT_WRIST: (0.60, 0.05),
        })),
        ("水平推",   _override(base, {
            LM.LEFT_ELBOW:  (0.25, 0.30), LM.RIGHT_ELBOW: (0.75, 0.30),
            LM.LEFT_WRIST:  (0.40, 0.35), LM.RIGHT_WRIST: (0.60, 0.35),
        })),
        ("單腳",     _override(base, {LM.RIGHT_ANKLE: (0.57, 0.88, 0.0, 0.0)})),
        ("站立靜止", base),
    ]

    for name, lms in scenarios:
        result = classify_movement(lms)
        print(f"{name:8s} → {json.dumps(result.to_dict(), ensure_ascii=False)}")


if __name__ == "__main__":
    import sys
    if "--test" in sys.argv:
        _run_tests()
    else:
        _demo()
        print("\n（加上 --test 執行單元測試）")
