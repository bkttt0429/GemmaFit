#pragma once

#include <string>
#include <vector>

#include "body_segments.h"
#include "com_tracker.h"
#include "joint_angles.h"
#include "symmetry.h"

namespace gemmafit::kinematics {

// ── 8 universal safety rules ────────────────────────────────────────

constexpr double kRapidMovementThresholdDegPerSecond = 600.0;
constexpr double kSevereRapidMovementThresholdDegPerSecond = 900.0;

struct SafetyRuleViolation {
    int rule = 0;            // 1 ~ 8
    std::string description;
    std::string joint;       // affected joint name
    double severity = 0.0;   // 0.0 ~ 1.0
    double value = 0.0;      // measured metric value
    double threshold = 0.0;  // rule threshold
    std::string evidence = "prototype_threshold";
    std::string metric;      // e.g. knee_ankle_ratio, fppa_deg
};

struct SafetyReport {
    std::vector<SafetyRuleViolation> violations;
    bool any_active = false;
    std::size_t active_count = 0;
};

// ── Rule evaluation functions ───────────────────────────────────────

SafetyRuleViolation check_rule1_knee_valgus(
    const JointAngleSet& angles,
    const SegmentSet& segments,
    const LandmarkArray& landmarks);

SafetyRuleViolation check_rule2_spinal_flexion(
    const JointAngleSet& angles,
    const SegmentSet& segments);

SafetyRuleViolation check_rule3_joint_overextension(
    const JointAngleSet& angles);

SafetyRuleViolation check_rule4_asymmetry(
    const SymmetryResult& symmetry);

SafetyRuleViolation check_rule5_com_offset(
    const ComResult& com);

SafetyRuleViolation check_rule6_rapid_movement(
    const std::string& joint_name,
    double angular_velocity_deg_s);

SafetyRuleViolation check_rule7_rom_insufficient(
    const std::string& joint_name,
    double current_rom_deg,
    double expected_rom_deg);

SafetyRuleViolation check_rule8_neck_hyperextension(
    const JointAngleSet& angles,
    const SegmentSet& segments);

// Run all 8 rules and return the full report.
SafetyReport evaluate_all_safety_rules(
    const JointAngleSet& angles,
    const SegmentSet& segments,
    const SymmetryResult& symmetry,
    const ComResult& com,
    const LandmarkArray& landmarks);

}  // namespace gemmafit::kinematics
