#pragma once

#include <string>
#include <vector>

#include "com_tracker.h"
#include "joint_angles.h"

namespace gemmafit::kinematics {

// ── Symmetry evaluation ─────────────────────────────────────────────

struct SymmetryResult {
    double score = 1.0;  // 0.0 ~ 1.0, 1.0 = perfect symmetry
    std::vector<std::string> asymmetric_joints;
    double max_difference_deg = 0.0;
};

// Compute bilateral symmetry from joint angle set.
// Compares left-right pairs: knee, hip, shoulder, elbow, ankle.
// Score = 1.0 - clamp(avg_diff_deg / max_allowed_diff, 0, 1).
// max_allowed_diff defaults to 20 degrees.
SymmetryResult evaluate_symmetry(
    const JointAngleSet& angles,
    double max_allowed_diff_deg = 20.0);

// True if any joint pair exceeds the asymmetry threshold (10°).
bool is_asymmetric(const SymmetryResult& result, double threshold_deg = 10.0);

}  // namespace gemmafit::kinematics
