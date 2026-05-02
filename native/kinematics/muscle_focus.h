#pragma once

#include <string>
#include <vector>

#include "movement_classifier.h"

namespace gemmafit::kinematics {

// ── Muscle focus estimation (pose-based, NOT EMG) ───────────────────
// Inference from joint kinematics, support base, and movement plane.

struct MuscleFocusEstimate {
    std::vector<std::string> primary;     // e.g. {"quadriceps", "gluteus_maximus"}
    std::vector<std::string> secondary;   // e.g. {"hamstrings", "core_stabilizers"}
    double confidence = 0.0;              // 0.0 ~ 1.0
    std::string limitation_note;          // "pose_based_estimate_not_emg"
};

// Estimate muscle groups from movement pattern.
// Uses a lookup table based on dominant joint + plane + base.
MuscleFocusEstimate estimate_muscle_focus(const MovementPattern& pattern);

// Human-readable label for UI display (e.g. "quadriceps" → "Quadriceps").
std::string muscle_label(const std::string& internal_name);

}  // namespace gemmafit::kinematics
