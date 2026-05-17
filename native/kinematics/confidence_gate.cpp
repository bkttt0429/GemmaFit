#include "confidence_gate.h"

#include <algorithm>
#include <cmath>
#include <sstream>

namespace gemmafit::kinematics {

namespace {

constexpr double kPresenceVisibilityFloor = 0.20;
constexpr double kAnchorVisibilityFloor = 0.35;
constexpr std::size_t kMinHighVisibilityCount = 8;
constexpr double kMinVisibleBboxArea = 0.006;

bool visible_enough(const LandmarkArray& landmarks, std::size_t index, double floor) {
    return index < kPoseLandmarkCount && landmarks[index].z >= floor;
}

}  // namespace

ConfidenceResult evaluate_confidence(
    const LandmarkArray& landmarks, double visibility_threshold) {

    ConfidenceResult result;
    result.threshold = visibility_threshold;

    double vis_sum = 0.0;
    double min_x = 1.0;
    double min_y = 1.0;
    double max_x = 0.0;
    double max_y = 0.0;
    bool has_bbox_point = false;
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        double vis = landmarks[i].z;  // z = visibility
        vis_sum += vis;
        if (vis < visibility_threshold) {
            result.low_visibility_count++;
        } else {
            result.high_visibility_count++;
        }
        if (vis >= kPresenceVisibilityFloor &&
            std::isfinite(landmarks[i].x) &&
            std::isfinite(landmarks[i].y)) {
            min_x = std::min(min_x, landmarks[i].x);
            min_y = std::min(min_y, landmarks[i].y);
            max_x = std::max(max_x, landmarks[i].x);
            max_y = std::max(max_y, landmarks[i].y);
            has_bbox_point = true;
        }
    }

    result.overall_visibility = vis_sum / kPoseLandmarkCount;
    if (has_bbox_point) {
        result.bbox_area = std::max(0.0, max_x - min_x) * std::max(0.0, max_y - min_y);
    }
    for (std::size_t index : {11u, 12u, 23u, 24u}) {
        if (visible_enough(landmarks, index, kAnchorVisibilityFloor)) {
            result.torso_anchor_count++;
        }
    }
    for (std::size_t index : {11u, 12u, 13u, 14u, 15u, 16u}) {
        if (visible_enough(landmarks, index, kAnchorVisibilityFloor)) {
            result.upper_body_anchor_count++;
        }
    }
    result.has_body_anchor = result.torso_anchor_count >= 2 ||
        result.upper_body_anchor_count >= 4;

    const bool overall_ok = result.overall_visibility >= visibility_threshold;
    const bool high_visibility_ok = result.high_visibility_count >= kMinHighVisibilityCount;
    const bool bbox_ok = result.bbox_area >= kMinVisibleBboxArea;
    const bool anchor_ok = result.has_body_anchor;
    result.pass = overall_ok && high_visibility_ok && bbox_ok && anchor_ok;

    if (!result.pass) {
        std::ostringstream reason;
        if (!overall_ok) {
            result.fail_condition = "low_overall_visibility";
            reason << "Low overall landmark visibility (" << result.overall_visibility
                   << " < " << visibility_threshold << ").";
        } else if (!high_visibility_ok) {
            result.fail_condition = "not_enough_high_visibility_landmarks";
            reason << "Not enough high-visibility landmarks ("
                   << result.high_visibility_count << " < "
                   << kMinHighVisibilityCount << ").";
        } else if (!bbox_ok) {
            result.fail_condition = "visible_body_too_small";
            reason << "Visible body region is too small (" << result.bbox_area
                   << " < " << kMinVisibleBboxArea << ").";
        } else {
            result.fail_condition = "missing_body_anchor";
            reason << "Missing reliable torso or upper-body anchor.";
        }
        reason << " Adjust camera angle, distance, or lighting.";
        result.fail_reason = reason.str();
    }

    return result;
}

bool is_confidence_sufficient(
    const LandmarkArray& landmarks, double visibility_threshold) {
    return evaluate_confidence(landmarks, visibility_threshold).pass;
}

}  // namespace gemmafit::kinematics
