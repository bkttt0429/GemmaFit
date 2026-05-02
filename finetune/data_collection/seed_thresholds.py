"""seed_thresholds.py — curated biomechanics thresholds from public references.

Hand-compiled from textbook & journal sources commonly cited in S&C
research. Each entry carries a citation. These are FACTS (numerical
thresholds) — not protected by copyright — and they exist as a
ground-truth bootstrap for `prototype/exercises/core.py` calibration.

Run this script to (re)write `knowledge_base/thresholds_curated.json`.
The PubMed-mined entries live in `thresholds.json`; this file is
separate so we never accidentally overwrite citations.

Sources used (all peer-reviewed or textbook):
  NSCA — Essentials of Strength Training and Conditioning, 4th ed.
  Hewett TE et al., Am J Sports Med 2005 — knee valgus / ACL risk
  Kritz et al., NSCA J 2009 — overhead squat assessment
  Powers CM, J Orthop Sports Phys Ther 2010 — lower extremity kinematics
  Schoenfeld BJ, J Strength Cond Res 2010 — squat depth & knee health
  Escamilla RF, Med Sci Sports Exerc 2001 — squat knee biomechanics
  Cook G, Movement, 2010 — FMS / lunge stability
"""
from __future__ import annotations

import json
from pathlib import Path

THIS_DIR = Path(__file__).resolve().parent
KB_DIR = THIS_DIR / "knowledge_base"
KB_DIR.mkdir(exist_ok=True)
OUT = KB_DIR / "thresholds_curated.json"


CURATED = [
    # ── Squat ────────────────────────────────────────────────────────────
    {
        "exercise": "squat",
        "metric": "knee_flexion_deg_at_parallel",
        "value_deg": 90.0,
        "rationale": "Hip crease parallel to knee — standard 'parallel squat' definition.",
        "citation": "NSCA Essentials of S&C, 4th ed. (Haff & Triplett, 2016).",
    },
    {
        "exercise": "squat",
        "metric": "knee_flexion_deg_full_depth",
        "value_deg": 120.0,
        "rationale": "Full-depth squat (ass-to-grass) — knee flexion typically reaches 120-140°.",
        "citation": "Schoenfeld BJ, J Strength Cond Res 2010; 24(12):3497-506.",
    },
    {
        "exercise": "squat",
        "metric": "trunk_lean_deg_acceptable_max",
        "value_deg": 35.0,
        "rationale": "Trunk forward lean beyond ~35° from vertical increases lumbar shear; "
                     "above this is treated as WARNING in MVP gates.",
        "citation": "Kritz, NSCA Journal 2009; 31(3):76-85 (overhead squat assessment).",
    },
    {
        "exercise": "squat",
        "metric": "knee_valgus_fppa_deg_warning",
        "value_deg": 8.0,
        "rationale": "Frontal-plane projection angle (FPPA) — valgus ≥ 8° is associated with "
                     "elevated knee-injury risk in athletic populations.",
        "citation": "Hewett TE et al., Am J Sports Med 2005; 33(4):492-501.",
    },
    {
        "exercise": "squat",
        "metric": "knee_valgus_fppa_deg_critical",
        "value_deg": 12.0,
        "rationale": "FPPA ≥ 12° flagged as CRITICAL knee valgus.",
        "citation": "Powers CM, J Orthop Sports Phys Ther 2010; 40(2):42-51.",
    },

    # ── Push-up ──────────────────────────────────────────────────────────
    {
        "exercise": "push_up",
        "metric": "elbow_flexion_deg_full_rep",
        "value_deg": 90.0,
        "rationale": "Full-range push-up — elbows reach 90° flexion at the bottom.",
        "citation": "Cogley RM et al., J Strength Cond Res 2005; 19(3):628-33.",
    },
    {
        "exercise": "push_up",
        "metric": "body_line_deviation_deg_warning",
        "value_deg": 12.0,
        "rationale": "Hip sag / pike — shoulder-hip-ankle line deviation > 12° indicates "
                     "loss of plank stability (WARNING).",
        "citation": "Cook G, Movement, 2010 (FMS push-up criterion).",
    },

    # ── Lunge ────────────────────────────────────────────────────────────
    {
        "exercise": "lunge",
        "metric": "front_knee_flexion_deg_target",
        "value_deg": 90.0,
        "rationale": "Front knee flexes to ~90° at lunge bottom, shin near vertical.",
        "citation": "Boyle M, Advances in Functional Training, 2010 (split squat criterion).",
    },
    {
        "exercise": "lunge",
        "metric": "trunk_uprightness_deg_warning",
        "value_deg": 15.0,
        "rationale": "Trunk should remain near-vertical in lunge; >15° forward lean "
                     "shifts load to spine.",
        "citation": "Schütz P et al., J Strength Cond Res 2014; 28(7):1992-2000.",
    },

    # ── Deadlift ─────────────────────────────────────────────────────────
    {
        "exercise": "deadlift",
        "metric": "hip_hinge_deg_setup",
        "value_deg": 60.0,
        "rationale": "Conventional deadlift setup — hip flexion ~60° (range 50-80°).",
        "citation": "Escamilla RF et al., Med Sci Sports Exerc 2001; 33(8):1345-53.",
    },
    {
        "exercise": "deadlift",
        "metric": "trunk_angle_deg_setup",
        "value_deg": 30.0,
        "rationale": "Trunk to 30° above horizontal at start position; further forward "
                     "indicates excessive lumbar shear.",
        "citation": "Cholewicki J et al., Spine 1991; 16(7):793-9.",
    },

    # ── Generic / cross-exercise ────────────────────────────────────────
    {
        "exercise": "any",
        "metric": "joint_angular_velocity_dps_warning",
        "value_deg": 600.0,
        "rationale": "Per Rule 6 — sustained joint angular velocity > 600 deg/s with "
                     "Savitzky-Golay smoothing flags rapid uncontrolled movement.",
        "citation": "Internal threshold; conservative — typical squats 200-400 deg/s "
                    "(Bryanton et al., J Strength Cond Res 2012; 26(10):2820-8).",
    },
    {
        "exercise": "any",
        "metric": "neck_extension_deg_warning",
        "value_deg": 15.0,
        "rationale": "Ear-shoulder-hip line deviation > 15° — neck hyperextension.",
        "citation": "NASM Essentials of Personal Fitness Training, 2018.",
    },
    {
        "exercise": "any",
        "metric": "bilateral_asymmetry_deg_warning",
        "value_deg": 10.0,
        "rationale": "Left-vs-right same-name joint angle delta > 10° (bilateral templates only).",
        "citation": "Ceroni D et al., Knee Surg Sports Traumatol Arthrosc 2012.",
    },
    {
        "exercise": "any",
        "metric": "landmark_visibility_min",
        "value_deg": 0.5,
        "rationale": "MediaPipe pose-landmark visibility < 0.5 → treat as LOW_CONFIDENCE.",
        "citation": "MediaPipe Pose Landmarker docs (Bazarevsky et al., arXiv:2006.10204).",
    },
]


def main():
    payload = {
        "version": 1,
        "_about": (
            "Curated thresholds — facts (numbers + citations), not training "
            "data. Used by core.py to calibrate gate thresholds."
        ),
        "thresholds": CURATED,
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                   encoding="utf-8")
    print(f"Wrote {len(CURATED)} curated thresholds → {OUT}")


if __name__ == "__main__":
    main()
