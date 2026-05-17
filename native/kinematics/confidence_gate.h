#pragma once

#include "com_tracker.h"

#include <cstddef>
#include <string>

namespace gemmafit::kinematics {

// ── Confidence gate ─────────────────────────────────────────────────
// Blocks unsafe LLM calls when visibility is too low.

struct ConfidenceResult {
    bool pass = false;           // true → safe to call LLM
    double overall_visibility = 0.0;  // 0.0 ~ 1.0 mean of 33 visibilities
    double threshold = 0.6;      // configured threshold
    std::size_t low_visibility_count = 0;  // landmarks below threshold
    std::size_t high_visibility_count = 0;  // landmarks at/above threshold
    double bbox_area = 0.0;      // normalized visible-landmark bbox area
    std::size_t torso_anchor_count = 0;  // shoulders/hips visible enough
    std::size_t upper_body_anchor_count = 0;  // shoulders/elbows/wrists visible enough
    bool has_body_anchor = false;
    std::string fail_condition;  // machine-readable reason
    std::string fail_reason;     // empty if pass
};

// Evaluate whether landmark data is reliable enough for LLM inference.
// pass == true when mean visibility >= threshold.
ConfidenceResult evaluate_confidence(
    const LandmarkArray& landmarks,
    double visibility_threshold = 0.6);

// Quick gate: returns true if safe to proceed.
bool is_confidence_sufficient(
    const LandmarkArray& landmarks,
    double visibility_threshold = 0.6);

}  // namespace gemmafit::kinematics
