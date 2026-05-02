#include "confidence_gate.h"

namespace gemmafit::kinematics {

ConfidenceResult evaluate_confidence(
    const LandmarkArray& landmarks, double visibility_threshold) {

    ConfidenceResult result;
    result.threshold = visibility_threshold;

    double vis_sum = 0.0;
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        double vis = landmarks[i].z;  // z = visibility
        vis_sum += vis;
        if (vis < visibility_threshold) {
            result.low_visibility_count++;
        }
    }

    result.overall_visibility = vis_sum / kPoseLandmarkCount;
    result.pass = result.overall_visibility >= visibility_threshold;

    if (!result.pass) {
        result.fail_reason = "Low overall landmark visibility (" +
                             std::to_string(result.overall_visibility) +
                             " < " + std::to_string(visibility_threshold) +
                             "). Adjust camera angle or lighting.";
    }

    return result;
}

bool is_confidence_sufficient(
    const LandmarkArray& landmarks, double visibility_threshold) {
    return evaluate_confidence(landmarks, visibility_threshold).pass;
}

}  // namespace gemmafit::kinematics
