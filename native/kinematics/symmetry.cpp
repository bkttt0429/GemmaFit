#include "symmetry.h"

#include <cmath>
#include <vector>

namespace gemmafit::kinematics {

SymmetryResult evaluate_symmetry(
    const JointAngleSet& angles,
    double max_allowed_diff_deg) {

    SymmetryResult result;
    result.score = 1.0;

    struct Pair {
        std::string left;
        std::string right;
        std::string label;
    };

    std::vector<Pair> pairs = {
        {"left_knee", "right_knee", "knee"},
        {"left_hip", "right_hip", "hip"},
        {"left_shoulder", "right_shoulder", "shoulder"},
        {"left_elbow", "right_elbow", "elbow"},
        {"left_ankle", "right_ankle", "ankle"},
    };

    double total_diff = 0.0;
    int count = 0;

    for (auto& p : pairs) {
        double la = get_angle(angles, p.left);
        double ra = get_angle(angles, p.right);
        double diff = std::abs(la - ra);
        total_diff += diff;
        count++;
        if (diff > result.max_difference_deg) {
            result.max_difference_deg = diff;
        }
        if (diff > 10.0) {
            result.asymmetric_joints.push_back(p.label);
        }
    }

    if (count > 0) {
        double avg_diff = total_diff / count;
        result.score = 1.0 - std::min(avg_diff / max_allowed_diff_deg, 1.0);
        if (result.score < 0.0) result.score = 0.0;
    }

    return result;
}

bool is_asymmetric(const SymmetryResult& result, double threshold_deg) {
    return result.max_difference_deg > threshold_deg;
}

}  // namespace gemmafit::kinematics
