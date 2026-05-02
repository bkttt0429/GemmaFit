"""
muscle_focus_prototype.py

根據 MovementPattern 推估主要參與肌群。
輸入：movement_classifier 輸出的 pattern dict
輸出：MuscleFocusEstimate dict（與 implementation_plan.md 3.4 節格式一致）

用語規範（NASM + literature_review.md）：
  ✅ "pose-based estimate", "likely primary load area", "may emphasize"
  ❌ "activation percentage", "muscle is not firing", "diagnosis"

用法：
  python muscle_focus_prototype.py          # 執行內建示範
  from muscle_focus_prototype import estimate_muscle_focus
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Literal


# ── 肌群推估映射表（來源：implementation_plan.md 3.4 節）─────────────────
# 鍵為 movement_classifier 輸出的 pattern 字串
# 判斷依據同步記錄，方便移植到 C++

_MUSCLE_MAP: dict[str, dict] = {
    "knee_dominant_bilateral": {
        "primary":   ["quadriceps", "gluteus_maximus"],
        "secondary": ["hamstrings", "calves", "core_stabilizers"],
        "basis":     "knee_hip_synchronized_flexion_extension",
    },
    "hip_dominant_bilateral": {
        "primary":   ["gluteus_maximus", "hamstrings"],
        "secondary": ["erector_spinae", "core_stabilizers"],
        "basis":     "hip_delta_greater_than_knee_delta",
    },
    "horizontal_push": {
        "primary":   ["pectoralis_major", "triceps_brachii", "anterior_deltoid"],
        "secondary": ["serratus_anterior", "core_stabilizers"],
        "basis":     "elbow_extension_plus_shoulder_horizontal_adduction",
    },
    "vertical_push": {
        "primary":   ["deltoid", "triceps_brachii"],
        "secondary": ["upper_trapezius", "core_stabilizers"],
        "basis":     "shoulder_flexion_abduction_plus_elbow_extension",
    },
    "horizontal_pull": {
        "primary":   ["rhomboids", "middle_trapezius", "latissimus_dorsi"],
        "secondary": ["biceps_brachii", "posterior_deltoid"],
        "basis":     "shoulder_extension_plus_elbow_flexion",
    },
    "vertical_pull": {
        "primary":   ["latissimus_dorsi", "biceps_brachii"],
        "secondary": ["lower_trapezius", "core_stabilizers"],
        "basis":     "shoulder_adduction_plus_elbow_flexion",
    },
    "unilateral_stability": {
        "primary":   ["gluteus_medius", "ankle_stabilizers"],
        "secondary": ["core_stabilizers", "hip_adductors"],
        "basis":     "single_limb_support_plus_pelvic_control",
    },
    "core_anti_extension": {
        "primary":   ["rectus_abdominis", "transverse_abdominis", "obliques"],
        "secondary": ["gluteus", "shoulder_stabilizers"],
        "basis":     "ground_contact_plus_trunk_straight_line",
    },
    "core_anti_rotation": {
        "primary":   ["obliques", "deep_core_stabilizers"],
        "secondary": ["gluteus_medius", "back_extensors"],
        "basis":     "resisting_horizontal_rotation",
    },
}

# pattern 不明時的 fallback
_UNKNOWN_PATTERN = {
    "primary":   [],
    "secondary": [],
    "basis":     "pattern_unrecognized",
}


@dataclass
class MuscleFocusEstimate:
    estimated_primary:   list[str]
    estimated_secondary: list[str]
    confidence:          Literal["high", "medium", "low", "unknown"]
    pattern_matched:     str
    note:                str = "pose_based_estimate_not_emg"

    def to_dict(self) -> dict:
        return {
            "estimated_primary":   self.estimated_primary,
            "estimated_secondary": self.estimated_secondary,
            "confidence":          self.confidence,
            "pattern_matched":     self.pattern_matched,
            "note":                self.note,
        }


def _resolve_pattern(movement_pattern: dict) -> tuple[str, str]:
    """
    從 MovementPattern dict 解析出映射鍵與信心等級。
    優先使用 pattern 欄位，退而使用 primary_joint + base 推斷。
    回傳 (map_key, confidence)
    """
    # 直接有 pattern 欄位（movement_classifier 標準輸出）
    pattern: str = movement_pattern.get("pattern", "")
    base:    str = movement_pattern.get("base", "")        # bipedal / unipedal
    pjoint:  str = movement_pattern.get("primary_joint", "")

    # 直接命中映射表
    if pattern in _MUSCLE_MAP:
        return pattern, "high"

    # 由 primary_joint + base 推斷
    if base == "unipedal":
        return "unilateral_stability", "medium"

    if pjoint == "knee":
        return "knee_dominant_bilateral", "medium"

    if pjoint == "hip":
        return "hip_dominant_bilateral", "medium"

    if pjoint in ("elbow", "shoulder"):
        load = movement_pattern.get("load_vector", "")
        if load == "horizontal":
            # 無法從 pose 分辨推拉，降為 low
            return "horizontal_push", "low"
        if load == "vertical":
            return "vertical_push", "low"

    return "unknown", "unknown"


def estimate_muscle_focus(movement_pattern: dict) -> MuscleFocusEstimate:
    """
    主要 API：輸入 movement_classifier 產生的 dict，輸出 MuscleFocusEstimate。

    Args:
        movement_pattern: 包含至少一個可識別欄位的 dict，例如：
            {"pattern": "knee_dominant_bilateral", "phase": "descent", ...}

    Returns:
        MuscleFocusEstimate
    """
    map_key, confidence = _resolve_pattern(movement_pattern)
    entry = _MUSCLE_MAP.get(map_key, _UNKNOWN_PATTERN)

    return MuscleFocusEstimate(
        estimated_primary=entry["primary"],
        estimated_secondary=entry["secondary"],
        confidence=confidence,
        pattern_matched=map_key,
    )


# ── 單元測試 ──────────────────────────────────────────────────────────────

def _run_tests():
    import sys
    passed = failed = 0

    def check(name: str, got, expected):
        nonlocal passed, failed
        ok = got == expected
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}: {got}")
        if ok:
            passed += 1
        else:
            failed += 1
            print(f"         expected: {expected}")

    print("\n── estimate_muscle_focus ──")

    # 1. 直接 pattern 命中
    r = estimate_muscle_focus({"pattern": "knee_dominant_bilateral"})
    check("knee_dominant primary",   r.estimated_primary,   ["quadriceps", "gluteus_maximus"])
    check("knee_dominant secondary", r.estimated_secondary, ["hamstrings", "calves", "core_stabilizers"])
    check("knee_dominant confidence","high",                 r.confidence)
    check("note 固定值",             r.note,                "pose_based_estimate_not_emg")

    r = estimate_muscle_focus({"pattern": "hip_dominant_bilateral"})
    check("hip_dominant primary",    r.estimated_primary,   ["gluteus_maximus", "hamstrings"])

    r = estimate_muscle_focus({"pattern": "horizontal_push"})
    check("horiz_push primary[0]",   r.estimated_primary[0], "pectoralis_major")

    r = estimate_muscle_focus({"pattern": "unilateral_stability"})
    check("unilateral primary[0]",   r.estimated_primary[0], "gluteus_medius")

    # 2. 由 primary_joint 推斷（medium confidence）
    r = estimate_muscle_focus({"primary_joint": "knee", "base": "bipedal"})
    check("fallback knee → pattern", r.pattern_matched, "knee_dominant_bilateral")
    check("fallback knee → medium",  r.confidence,      "medium")

    r = estimate_muscle_focus({"primary_joint": "hip"})
    check("fallback hip → pattern",  r.pattern_matched, "hip_dominant_bilateral")

    # 3. 單腳支撐由 base 推斷
    r = estimate_muscle_focus({"base": "unipedal"})
    check("unipedal → unilateral",   r.pattern_matched, "unilateral_stability")

    # 4. 完全未知
    r = estimate_muscle_focus({})
    check("empty → unknown",         r.confidence, "unknown")
    check("empty primary is []",     r.estimated_primary, [])

    # 5. to_dict 格式
    d = estimate_muscle_focus({"pattern": "core_anti_extension"}).to_dict()
    check("to_dict has note",        "note" in d, True)
    check("to_dict primary correct", d["estimated_primary"][0], "rectus_abdominis")

    print(f"\n{'='*40}")
    print(f"結果：{passed} PASS，{failed} FAIL")
    print(f"{'='*40}")
    sys.exit(0 if failed == 0 else 1)


# ── 示範輸出 ──────────────────────────────────────────────────────────────

def _demo():
    import json
    patterns = [
        {"pattern": "knee_dominant_bilateral", "phase": "descent", "base": "bipedal_wide_stance"},
        {"pattern": "hip_dominant_bilateral",  "phase": "hinge",   "base": "bipedal"},
        {"pattern": "horizontal_push",         "phase": "press",   "base": "bipedal"},
        {"primary_joint": "knee",              "base": "bipedal"},   # fallback
        {"base": "unipedal"},                                         # 單腳
        {},                                                           # 未知
    ]
    for p in patterns:
        result = estimate_muscle_focus(p)
        print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
        print()


if __name__ == "__main__":
    import sys
    if "--test" in sys.argv:
        _run_tests()
    else:
        _demo()
        print("（加上 --test 執行單元測試）")
