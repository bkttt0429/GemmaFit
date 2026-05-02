#pragma once

#include <string>

#include "../kinematics/com_tracker.h"
#include "../kinematics/confidence_gate.h"
#include "../kinematics/joint_angles.h"
#include "../kinematics/movement_classifier.h"
#include "../kinematics/muscle_focus.h"
#include "../kinematics/safety_monitor.h"
#include "../kinematics/symmetry.h"

namespace gemmafit::bridge {

// ── JNI Kinematics Bridge ───────────────────────────────────────────
// Accepts float[99] (33 landmarks × x,y,z) from Kotlin,
// runs the full biomechanics pipeline, and returns JSON.

struct KinematicsOutput {
    bool success = false;
    std::string error_json;              // JSON for movement pattern
    std::string pattern_json;            // JSON for movement pattern
    std::string safety_json;             // JSON for safety report
    std::string muscle_json;             // JSON for muscle focus
    std::string motion_report_json;      // JSON for exercise template metrics and quality gates
    std::string confidence_json;         // JSON for confidence gate
    std::string combined_json;           // all-in-one JSON for LLM input
};

// Run full biomechanics pipeline from raw float[99].
// Returns all computed data as JSON strings.
KinematicsOutput run_biomechanics_pipeline(
    const float* landmarks_99,
    const float* prev_landmarks_99 = nullptr,
    double visibility_threshold = 0.6);

// Serialize individual structs to JSON.
std::string to_json(const gemmafit::kinematics::JointAngleSet& angles);
std::string to_json(const gemmafit::kinematics::SafetyReport& report);
std::string to_json(const gemmafit::kinematics::MovementPattern& pattern);
std::string to_json(const gemmafit::kinematics::MuscleFocusEstimate& focus);
std::string to_json(const gemmafit::kinematics::ConfidenceResult& confidence);

}  // namespace gemmafit::bridge
