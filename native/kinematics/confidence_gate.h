#pragma once

#include "com_tracker.h"

namespace gemmafit::kinematics {

// ── Confidence gate ─────────────────────────────────────────────────
// Blocks unsafe LLM calls when visibility is too low.

struct ConfidenceResult {
    bool pass = false;           // true → safe to call LLM
    double overall_visibility = 0.0;  // 0.0 ~ 1.0 mean of 33 visibilities
    double threshold = 0.6;      // configured threshold
    std::size_t low_visibility_count = 0;  // landmarks below threshold
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
