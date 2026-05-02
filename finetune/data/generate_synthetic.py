"""
generate_synthetic.py — 合成 600 筆 Gemma 4 Function Calling 微調數據

根據 8 條通用安全規則 + 肌群推估 + 物理模式，生成結構化 FC 訓練數據。

輸出格式（每筆）：
{
  "input": {
    "pattern": "bilateral_knee_dominant_sagittal",
    "phase": "descent",
    "estimated_primary": ["quadriceps", "gluteus_maximus"],
    "estimated_secondary": ["hamstrings", "core_stabilizers"],
    "anomalies": [{...}]
  },
  "output": {
    "function": "correct_knee_alignment",
    "args": {"side": "left", "ratio": 0.72, "severity": "moderate"}
  }
}

覆蓋情境（600 筆）：
  - 單一規則觸發 × 9 種模式 × 6 變化 = 54 筆 × 8 rules ≈ 432 筆
  - 雙規則組合觸發 × 6 變化 × 10 combo = ~60 筆
  - 無異常鼓勵 × 9 種模式 × 6 變化 = ~54 筆
  - 邊界值/接近觸發但未觸發 × 9 模式 × 3 = ~27 筆
  - 低信心度場景 × ~27 筆（交給 ConfidenceGate，仍模擬）

用法：
  python generate_synthetic.py
  python generate_synthetic.py --count 1200 --output data/fc_training_data_large.json
"""

import argparse
import json
import math
import os
import random
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

random.seed(42)

VAL_RATIO = 0.15
OUTPUT_DIR = os.path.dirname(__file__)
os.makedirs(OUTPUT_DIR, exist_ok=True)


# ── 運動模式模板 ────────────────────────────────────────────────────
# 9 種通用物理模式

MOVEMENT_PATTERNS = [
    {
        "pattern": "bilateral_knee_dominant_sagittal",
        "primary": ["quadriceps", "gluteus_maximus"],
        "secondary": ["hamstrings", "calves", "core_stabilizers"],
        "phases": ["top", "descent", "bottom", "ascent"],
        "joints": ["left_knee", "right_knee", "left_hip", "right_hip"],
        "description": "雙側膝主導矢狀面（蹲類）",
    },
    {
        "pattern": "bilateral_hip_dominant_sagittal",
        "primary": ["gluteus_maximus", "hamstrings"],
        "secondary": ["erector_spinae", "core_stabilizers"],
        "phases": ["top", "descent", "bottom", "ascent"],
        "joints": ["left_hip", "right_hip", "left_knee", "right_knee"],
        "description": "雙側髖主導矢狀面（折髖/硬舉類）",
    },
    {
        "pattern": "unipedal_knee_hip_dominant_sagittal",
        "primary": ["quadriceps", "gluteus_maximus", "gluteus_medius"],
        "secondary": ["hamstrings", "adductors", "core_stabilizers"],
        "phases": ["descent", "bottom", "ascent"],
        "joints": ["left_knee", "left_hip", "right_knee", "right_hip"],
        "description": "單側膝髖主導（弓箭步類）",
    },
    {
        "pattern": "prone_hands_shoulder_dominant_sagittal",
        "primary": ["pectoralis_major", "triceps", "anterior_deltoid"],
        "secondary": ["serratus_anterior", "core_stabilizers"],
        "phases": ["top", "descent", "bottom", "ascent"],
        "joints": ["left_elbow", "right_elbow", "left_shoulder", "right_shoulder"],
        "description": "俯臥雙手肩主導矢狀面（伏地挺身類）",
    },
    {
        "pattern": "bipedal_elbow_dominant_sagittal",
        "primary": ["biceps", "brachialis"],
        "secondary": ["forearm_flexors", "shoulder_stabilizers"],
        "phases": ["descent", "bottom", "ascent"],
        "joints": ["left_elbow", "right_elbow"],
        "description": "雙側肘主導矢狀面（彎舉類）",
    },
    {
        "pattern": "bipedal_shoulder_dominant_frontal",
        "primary": ["deltoids", "triceps"],
        "secondary": ["upper_trapezius", "core_stabilizers"],
        "phases": ["descent", "bottom", "ascent"],
        "joints": ["left_shoulder", "right_shoulder", "left_elbow", "right_elbow"],
        "description": "垂直推冠狀面",
    },
    {
        "pattern": "bipedal_shoulder_dominant_sagittal",
        "primary": ["latissimus_dorsi", "rhomboids", "biceps"],
        "secondary": ["posterior_deltoid", "trapezius_mid"],
        "phases": ["descent", "bottom", "ascent"],
        "joints": ["left_elbow", "right_elbow", "left_shoulder", "right_shoulder"],
        "description": "水平拉矢狀面",
    },
    {
        "pattern": "prone_quadrupedal_spine_stabilizer",
        "primary": ["rectus_abdominis", "transversus_abdominis", "obliques"],
        "secondary": ["gluteus_maximus", "shoulder_stabilizers"],
        "phases": ["isometric"],
        "joints": ["spine"],
        "description": "核心抗伸展（棒式類）",
    },
    {
        "pattern": "unipedal_hip_stabilizer_frontal",
        "primary": ["gluteus_medius", "ankle_stabilizers", "core"],
        "secondary": ["adductors"],
        "phases": ["isometric", "descent", "bottom", "ascent"],
        "joints": ["left_hip", "right_hip", "left_knee", "right_knee"],
        "description": "單腳穩定冠狀面",
    },
]


# ── 安全規則數據生成器 ──────────────────────────────────────────────
# 每條規則的參數範圍與觸發條件

@dataclass
class AnomalyTemplate:
    rule: int
    name: str
    fc_function: str
    generate_args: callable  # (pattern_dict) → dict


def _gen_rule1_knee(params: dict) -> dict:
    """Rule 1: 膝關節側向偏移 (ratio < 0.8)"""
    side = random.choice(["left", "right", "bilateral"])
    if side == "bilateral":
        ratio = round(random.uniform(0.50, 0.78), 2)
        severity = "severe" if ratio < 0.65 else "moderate"
        return {
            "anomaly": {"rule": 1, "joint": "both_knees", "ratio": ratio, "severity": severity},
            "output": {
                "function": "correct_knee_alignment",
                "args": {"side": "bilateral", "ratio": ratio, "severity": severity},
            },
        }
    else:
        ratio = round(random.uniform(0.55, 0.79), 2)
        severity = "severe" if ratio < 0.65 else "moderate"
        joint = f"{side}_knee"
        return {
            "anomaly": {"rule": 1, "joint": joint, "ratio": ratio, "severity": severity},
            "output": {
                "function": "correct_knee_alignment",
                "args": {"side": side, "ratio": ratio, "severity": severity},
            },
        }


def _gen_rule2_spine(params: dict) -> dict:
    """Rule 2: 脊柱彎曲異常 (deviation > 15°)"""
    region = random.choice(["lumbar", "thoracic", "full_spine"])
    deviation = round(random.uniform(15.5, 40.0), 1)
    severity = "severe" if deviation > 30 else "moderate"
    return {
        "anomaly": {"rule": 2, "deviation": deviation, "region": region, "severity": severity},
        "output": {
            "function": "correct_spinal_alignment",
            "args": {"deviation": deviation, "region": region},
        },
    }


def _gen_rule3_overextend(params: dict) -> dict:
    """Rule 3: 關節過度伸展 (角度 ≈ 0° or 180° ± 5°)"""
    joints = params.get("joints", ["left_knee", "right_knee", "left_elbow", "right_elbow"])
    joint = random.choice(joints)
    angle_type = random.choice(["hyperextended", "locked"])
    if angle_type == "locked":
        current = round(random.uniform(175, 180), 1)
        safe = (150, 175)
    else:
        current = round(random.uniform(0, 5), 1)
        safe = (15, 165)
    return {
        "anomaly": {"rule": 3, "joint": joint, "current_angle": current, "angle_type": angle_type},
        "output": {
            "function": "correct_joint_angle",
            "args": {"joint": joint, "current": current, "safe_range": list(safe)},
        },
    }


def _gen_rule4_asymmetry(params: dict) -> dict:
    """Rule 4: 雙側不對稱 (> 10° difference)"""
    joint_map = {
        "bilateral_knee_dominant_sagittal": "knee",
        "bilateral_hip_dominant_sagittal": "hip",
        "prone_hands_shoulder_dominant_sagittal": "shoulder",
        "unipedal_knee_hip_dominant_sagittal": "hip",
        "bipedal_elbow_dominant_sagittal": "elbow",
    }
    pattern = params.get("pattern", "")
    joint_name = joint_map.get(pattern, random.choice(["knee", "hip", "shoulder", "elbow"]))
    left = round(random.uniform(60, 120), 1)
    diff = round(random.uniform(10.5, 25.0), 1)
    right = left + diff if random.random() > 0.5 else left - diff
    return {
        "anomaly": {
            "rule": 4,
            "joint": joint_name,
            "left_angle": round(left, 1),
            "right_angle": round(abs(right), 1),
            "difference": round(diff, 1),
        },
        "output": {
            "function": "correct_asymmetry",
            "args": {"joint": joint_name, "left": round(left, 1), "right": round(abs(right), 1)},
        },
    }


def _gen_rule5_com(params: dict) -> dict:
    """Rule 5: 質心偏移 (COM 超出支撐多邊形)"""
    direction = random.choice(["forward", "backward", "left", "right", "forward_left"])
    distance = round(random.uniform(0.02, 0.15), 3)
    return {
        "anomaly": {"rule": 5, "com_direction": direction, "offset_distance": distance},
        "output": {
            "function": "warn_com_offset",
            "args": {"direction": direction, "distance": distance},
        },
    }


def _gen_rule6_rapid(params: dict) -> dict:
    """Rule 6: 急速動作 (> 60 deg/s)"""
    joints = params.get("joints", ["left_knee", "right_knee", "left_elbow", "right_elbow"])
    joint = random.choice(joints)
    velocity = round(random.uniform(65, 200), 1)
    severity = "severe" if velocity > 120 else "moderate"
    return {
        "anomaly": {"rule": 6, "joint": joint, "angular_velocity": velocity, "severity": severity},
        "output": {
            "function": "warn_rapid_movement",
            "args": {"joint": joint, "velocity": velocity},
        },
    }


def _gen_rule7_rom(params: dict) -> dict:
    """Rule 7: 活動度不足 (ROM < 50%)"""
    joints = params.get("joints", ["left_knee", "right_knee", "left_elbow", "right_elbow"])
    joint = random.choice(joints)
    target_rom = random.choice([90, 120, 150])
    current_rom = round(random.uniform(target_rom * 0.15, target_rom * 0.48), 1)
    return {
        "anomaly": {
            "rule": 7,
            "joint": joint,
            "current_rom": current_rom,
            "target_rom": target_rom,
            "percentage": round(current_rom / target_rom * 100, 1),
        },
        "output": {
            "function": "increase_range_of_motion",
            "args": {"joint": joint, "current": current_rom, "target": target_rom},
        },
    }


def _gen_rule8_neck(params: dict) -> dict:
    """Rule 8: 頸椎過伸 (ear-shoulder-hip deviation > 15°)"""
    direction = random.choice(["hyperextension", "hyperflexion"])
    deviation = round(random.uniform(15.5, 35.0), 1)
    severity = "severe" if deviation > 25 else "moderate"
    return {
        "anomaly": {"rule": 8, "deviation": deviation, "direction": direction, "severity": severity},
        "output": {
            "function": "correct_spinal_alignment",
            "args": {"deviation": deviation, "region": "cervical"},
        },
    }


# 規則生成器列表
RULE_GENERATORS: List[AnomalyTemplate] = [
    AnomalyTemplate(rule=1, name="knee_valgus", fc_function="correct_knee_alignment", generate_args=_gen_rule1_knee),
    AnomalyTemplate(rule=2, name="spinal_flexion", fc_function="correct_spinal_alignment", generate_args=_gen_rule2_spine),
    AnomalyTemplate(rule=3, name="joint_overextension", fc_function="correct_joint_angle", generate_args=_gen_rule3_overextend),
    AnomalyTemplate(rule=4, name="asymmetry", fc_function="correct_asymmetry", generate_args=_gen_rule4_asymmetry),
    AnomalyTemplate(rule=5, name="com_offset", fc_function="warn_com_offset", generate_args=_gen_rule5_com),
    AnomalyTemplate(rule=6, name="rapid_movement", fc_function="warn_rapid_movement", generate_args=_gen_rule6_rapid),
    AnomalyTemplate(rule=7, name="rom_insufficient", fc_function="increase_range_of_motion", generate_args=_gen_rule7_rom),
    AnomalyTemplate(rule=8, name="neck_hyperextension", fc_function="correct_spinal_alignment", generate_args=_gen_rule8_neck),
]


# ── 數據生成核心 ────────────────────────────────────────────────────

def generate_single_anomaly(pattern: dict, rule_gen: AnomalyTemplate) -> dict:
    """生成單一規則觸發的訓練數據。"""
    result = rule_gen.generate_args({
        "pattern": pattern["pattern"],
        "joints": pattern["joints"],
    })
    anomaly = result["anomaly"]
    anomaly["evidence"] = "prototype_threshold"
    phase = random.choice(pattern["phases"])
    return {
        "input": {
            "pattern": pattern["pattern"],
            "phase": phase,
            "estimated_primary": pattern["primary"],
            "estimated_secondary": pattern["secondary"],
            "anomalies": [anomaly],
        },
        "output": result["output"],
    }


def generate_double_anomaly(pattern: dict, r1: AnomalyTemplate, r2: AnomalyTemplate) -> dict:
    """生成雙規則同時觸發的訓練數據（優先處理較嚴重者）。"""
    res1 = r1.generate_args({"pattern": pattern["pattern"], "joints": pattern["joints"]})
    res2 = r2.generate_args({"pattern": pattern["pattern"], "joints": pattern["joints"]})
    res1["anomaly"]["evidence"] = "prototype_threshold"
    res2["anomaly"]["evidence"] = "prototype_threshold"

    # 優先級：膝關節 > 脊柱 > 關節過展 > 對稱 > 重心 > 急速 > 活動度 > 頸椎
    priority_order = {1: 1, 2: 2, 8: 3, 3: 4, 4: 5, 5: 6, 6: 7, 7: 8}
    if priority_order.get(r1.rule, 9) <= priority_order.get(r2.rule, 9):
        primary, winning_rule = res1, r1.rule
    else:
        primary, winning_rule = res2, r2.rule

    output = dict(primary["output"])
    output["selection_basis"] = f"rule_{winning_rule}_highest_priority"

    phase = random.choice(pattern["phases"])
    return {
        "input": {
            "pattern": pattern["pattern"],
            "phase": phase,
            "estimated_primary": pattern["primary"],
            "estimated_secondary": pattern["secondary"],
            "anomalies": [res1["anomaly"], res2["anomaly"]],
        },
        "output": output,
    }


def generate_no_anomaly(pattern: dict, streak: int = None) -> dict:
    """生成無異常的正面鼓勵數據。"""
    if streak is None:
        streak = random.choice([30, 45, 60, 90, 120])
    phase = pattern["phases"][-1]  # top or ascent → 動作完成狀態
    return {
        "input": {
            "pattern": pattern["pattern"],
            "phase": phase,
            "estimated_primary": pattern["primary"],
            "estimated_secondary": pattern["secondary"],
            "anomalies": [],
            "streak_frames": streak,
        },
        "output": {
            "function": "positive_reinforcement",
            "args": {
                "pattern": pattern["pattern"],
                "primary_muscles": pattern["primary"],
                "streak": streak,
            },
        },
    }


def generate_borderline(pattern: dict, rule_gen: AnomalyTemplate) -> dict:
    """生成邊界值數據（接近觸發閾值但未觸發）→ 不輸出 FC 調用或輸出鼓勵。"""
    phase = random.choice(pattern["phases"])
    # 生成接近但未超標的數值
    if rule_gen.rule == 1:
        ratio = round(random.uniform(0.80, 0.85), 2)
        anomaly = {"rule": 1, "note": "borderline", "ratio": ratio}
    elif rule_gen.rule == 2:
        deviation = round(random.uniform(12, 15), 1)
        anomaly = {"rule": 2, "note": "borderline", "deviation": deviation}
    elif rule_gen.rule == 4:
        diff = round(random.uniform(7, 10), 1)
        anomaly = {"rule": 4, "note": "borderline", "difference": diff}
    elif rule_gen.rule == 6:
        vel = round(random.uniform(50, 60), 1)
        anomaly = {"rule": 6, "note": "borderline", "angular_velocity": vel}
    elif rule_gen.rule == 7:
        target = random.choice([90, 120])
        current = round(random.uniform(target * 0.50, target * 0.55), 1)
        anomaly = {"rule": 7, "note": "borderline", "current_rom": current, "target_rom": target}
    else:
        anomaly = {"rule": rule_gen.rule, "note": "borderline"}

    return {
        "input": {
            "pattern": pattern["pattern"],
            "phase": phase,
            "estimated_primary": pattern["primary"],
            "estimated_secondary": pattern["secondary"],
            "anomalies": [anomaly],
        },
        "output": {
            "function": "positive_reinforcement",
            "args": {
                "pattern": pattern["pattern"],
                "primary_muscles": pattern["primary"],
            },
        },
    }


def generate_low_visibility(pattern: dict) -> dict:
    """生成低可見度場景（實際上由 ConfidenceGate 攔截）。"""
    confidence = round(random.uniform(0.35, 0.58), 2)
    return {
        "input": {
            "pattern": pattern["pattern"],
            "phase": "unknown",
            "estimated_primary": pattern["primary"],
            "estimated_secondary": pattern["secondary"],
            "anomalies": [],
            "confidence": confidence,
            "note": "low_visibility",
        },
        "output": {
            "function": "warn_poor_visibility",
            "args": {"confidence": confidence, "message": "adjust_camera_or_lighting"},
        },
    }


# ── 主生成函數 ──────────────────────────────────────────────────────

def generate_training_data(total: int = 600) -> List[dict]:
    """
    生成指定數量的 FC 訓練數據。

    配比：
      60% 單一規則觸發 (360 筆)
      10% 雙規則觸發 (60 筆)
      10% 無異常鼓勵 (60 筆)
      10% 邊界值 (60 筆)
       5% 低可見度 (30 筆)
       5% 極端多規則 (30 筆)
    """
    data: List[dict] = []

    # 配比計算
    n_single = max(1, int(total * 0.60))
    n_double = max(1, int(total * 0.10))
    n_clean = max(1, int(total * 0.10))
    n_border = max(1, int(total * 0.10))
    n_lowconf = max(1, int(total * 0.05))
    n_extreme = max(1, int(total * 0.05))

    # ── 單一規則觸發 ──
    for _ in range(n_single):
        rule_gen = random.choice(RULE_GENERATORS)
        pattern = random.choice(MOVEMENT_PATTERNS)

        # 確保規則與模式具有合理性（如頸部規則不限模式，膝關節規則需有膝關節）
        if rule_gen.rule == 1:  # 膝內夾 → 需有下肢的動作
            pattern = random.choice([p for p in MOVEMENT_PATTERNS if "knee" in str(p["joints"])])
        elif rule_gen.rule in (2, 3, 4, 5, 6, 7, 8):
            pass  # 規則通用於任何模式

        data.append(generate_single_anomaly(pattern, rule_gen))

    # ── 雙規則觸發 ──
    for _ in range(n_double):
        rule_pairs = [
            (1, 4), (1, 5), (1, 2),  # 膝內夾 + 不對稱/偏移/彎曲
            (2, 8),                    # 背弓 + 頸過伸（同 FC: spinal）
            (2, 4), (3, 4),           # 姿勢 + 不對稱
            (5, 6),                    # 重心 + 急速
            (6, 7),                    # 急速 + ROM 不足
            (1, 7),                    # 膝內夾 + ROM 不足
            (3, 6),                    # 過伸 + 急速
            (4, 5),                    # 不對稱 + 重心偏移
            (2, 6),                    # 背弓 + 急速
            (3, 7),                    # 過伸 + ROM 不足
        ]
        r1_idx, r2_idx = random.choice(rule_pairs)
        r1 = RULE_GENERATORS[r1_idx - 1]
        r2 = RULE_GENERATORS[r2_idx - 1]
        pattern = random.choice(MOVEMENT_PATTERNS)
        data.append(generate_double_anomaly(pattern, r1, r2))

    # ── 無異常鼓勵 ──
    for _ in range(n_clean):
        pattern = random.choice(MOVEMENT_PATTERNS)
        data.append(generate_no_anomaly(pattern))

    # ── 邊界值 ──
    for _ in range(n_border):
        rule_gen = random.choice(RULE_GENERATORS)
        pattern = random.choice(MOVEMENT_PATTERNS)
        data.append(generate_borderline(pattern, rule_gen))

    # ── 低可見度 ──
    for _ in range(n_lowconf):
        pattern = random.choice(MOVEMENT_PATTERNS)
        data.append(generate_low_visibility(pattern))

    # ── 極端多規則 (3+ 同時觸發) ──
    for _ in range(n_extreme):
        n_rules = random.choice([3, 4])
        selected_rules = random.sample(RULE_GENERATORS, n_rules)
        pattern = random.choice(MOVEMENT_PATTERNS)
        results = [r.generate_args({"pattern": pattern["pattern"], "joints": pattern["joints"]})
                    for r in selected_rules]

        # 取最優先規則作為輸出
        priority = [1, 2, 8, 3, 4, 5, 6, 7]
        best_result = min(results, key=lambda x: priority.index(selected_rules[results.index(x)].rule)
                           if selected_rules[results.index(x)].rule in priority else 99)

        data.append({
            "input": {
                "pattern": pattern["pattern"],
                "phase": random.choice(pattern["phases"]),
                "estimated_primary": pattern["primary"],
                "estimated_secondary": pattern["secondary"],
                "anomalies": [r["anomaly"] for r in results],
            },
            "output": best_result["output"],
        })

    random.shuffle(data)
    return data[:total]


# ── 驗證 ────────────────────────────────────────────────────────────

def validate_data(data: List[dict]) -> Tuple[int, int]:
    """驗證數據格式完整性。"""
    errors = 0
    valid_functions = {
        "correct_knee_alignment", "correct_spinal_alignment",
        "correct_joint_angle", "correct_asymmetry",
        "warn_com_offset", "warn_rapid_movement",
        "increase_range_of_motion", "positive_reinforcement",
        "warn_poor_visibility",
    }

    for i, item in enumerate(data):
        try:
            # 必要欄位檢查
            assert "input" in item, f"missing input"
            assert "output" in item, f"missing output"
            inp = item["input"]
            out = item["output"]
            assert "pattern" in inp
            assert "phase" in inp
            assert "estimated_primary" in inp
            assert "estimated_secondary" in inp
            assert "anomalies" in inp
            assert "function" in out
            assert "args" in out
            assert out["function"] in valid_functions, f"invalid function: {out['function']}"
        except AssertionError as e:
            print(f"[ERROR] Item {i}: {e}")
            errors += 1

    return len(data) - errors, errors


def print_statistics(data: List[dict]):
    """印出數據統計。"""
    func_count = {}
    rule_count = {}
    pattern_count = {}

    for item in data:
        func = item["output"]["function"]
        func_count[func] = func_count.get(func, 0) + 1

        for a in item["input"]["anomalies"]:
            r = a.get("rule", 0)
            rule_count[r] = rule_count.get(r, 0) + 1

        pat = item["input"]["pattern"]
        pattern_count[pat] = pattern_count.get(pat, 0) + 1

    print(f"\n{'='*60}")
    print(f"Total items: {len(data)}")
    print(f"\nFunction distribution:")
    for func, cnt in sorted(func_count.items()):
        pct = cnt / len(data) * 100
        print(f"  {func:<30} {cnt:>4}  ({pct:5.1f}%)")

    print(f"\nRule distribution:")
    for rule, cnt in sorted(rule_count.items()):
        print(f"  Rule {rule}: {cnt}")

    print(f"\nPattern distribution:")
    for pat, cnt in sorted(pattern_count.items()):
        print(f"  {pat:<55} {cnt:>4}")

    # 樣例
    print(f"\n{'='*60}")
    print("Sample entries (first 3):")
    for item in data[:3]:
        print(json.dumps(item, ensure_ascii=False, indent=2))
        print()


# ── CLI ──────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="GemmaFit FC Training Data Generator")
    parser.add_argument("--count", type=int, default=600, help="生成筆數 (預設 600)")
    parser.add_argument("--output", type=str, default="fc_training_data.json",
                        help="輸出檔名")
    parser.add_argument("--seed", type=int, default=42, help="隨機種子")
    args = parser.parse_args()

    random.seed(args.seed)

    print(f"Generating {args.count} FC training examples...")
    data = generate_training_data(total=args.count)

    valid, errors = validate_data(data)
    print(f"Validation: {valid} valid, {errors} errors")

    print_statistics(data)

    n_val = max(1, int(len(data) * VAL_RATIO))
    val_data = data[:n_val]
    train_data = data[n_val:]

    output_obj = {
        "meta": {
            "total": len(data),
            "train": len(train_data),
            "validation": len(val_data),
            "val_ratio": VAL_RATIO,
            "seed": args.seed,
            "generated_at": "2026-05-01",
            "rule_version": "prototype_threshold",
        },
        "train": train_data,
        "validation": val_data,
    }

    output_path = os.path.join(OUTPUT_DIR, args.output)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(output_obj, f, ensure_ascii=False, indent=2)

    print(f"\nTrain: {len(train_data)}, Validation: {len(val_data)}")
    print(f"Saved to: {output_path}")
    print(f"File size: {os.path.getsize(output_path) / 1024:.1f} KB")


if __name__ == "__main__":
    main()
