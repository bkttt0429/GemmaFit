#include "safety_monitor.h"

#include <cmath>

namespace gemmafit::kinematics {

SafetyRuleViolation make_violation(int rule, const std::string& desc,
                                    const std::string& joint,
                                    double severity, double value, double threshold,
                                    const std::string& metric = "",
                                    const std::string& evidence = "prototype_threshold") {
    return {rule, desc, joint, severity, value, threshold, evidence, metric};
}

double frontal_plane_projection_angle(
    const Point3& hip, const Point3& knee, const Point3& ankle) {
    return std::abs(180.0 - angle_between(hip, knee, ankle));
}

SafetyRuleViolation check_rule1_knee_valgus(
    const JointAngleSet&, const SegmentSet& seg, const LandmarkArray& lm) {
    // Rule #1 uses two prototype metrics:
    // 1. knee distance / ankle distance, a fast frontal-view heuristic.
    // 2. FPPA, a literature-aligned 2D frontal-plane projection angle.
    double kx_l = lm[25].x, ky_l = lm[25].y;
    double kx_r = lm[26].x, ky_r = lm[26].y;
    double ax_l = lm[27].x, ay_l = lm[27].y;
    double ax_r = lm[28].x, ay_r = lm[28].y;

    double knee_dist = std::sqrt((kx_l - kx_r) * (kx_l - kx_r) +
                                  (ky_l - ky_r) * (ky_l - ky_r));
    double ankle_dist = std::sqrt((ax_l - ax_r) * (ax_l - ax_r) +
                                   (ay_l - ay_r) * (ay_l - ay_r));

    double ratio = (ankle_dist > 1e-9) ? knee_dist / ankle_dist : 1.0;
    double left_fppa = frontal_plane_projection_angle(lm[23], lm[25], lm[27]);
    double right_fppa = frontal_plane_projection_angle(lm[24], lm[26], lm[28]);
    double max_fppa = std::max(left_fppa, right_fppa);

    bool ratio_triggered = ratio < 0.8;
    bool fppa_triggered = max_fppa > 10.0;
    if (!ratio_triggered && !fppa_triggered) return {};

    std::string side = "bilateral";
    if (left_fppa > right_fppa + 2.0) side = "left";
    else if (right_fppa > left_fppa + 2.0) side = "right";

    double severity = (ratio < 0.65 || max_fppa > 20.0) ? 0.9 : 0.5;
    if (fppa_triggered && (!ratio_triggered || max_fppa / 10.0 > (0.8 - ratio) / 0.8)) {
        return make_violation(1, "Knee frontal-plane deviation (FPPA)",
                              side + "_knee", severity, max_fppa, 10.0,
                              "fppa_deg", "prototype_threshold");
    }
    return make_violation(1, "Knee valgus (knees collapsing inward)",
                          side + "_knee", severity, ratio, 0.8,
                          "knee_ankle_ratio", "prototype_threshold");
}

SafetyRuleViolation check_rule2_spinal_flexion(
    const JointAngleSet& angles, const SegmentSet&) {
    double spine_ang = get_angle(angles, "spine");
    double deviation = std::abs(180.0 - spine_ang);
    if (deviation <= 15.0) return {};

    std::string region = "lumbar";
    if (deviation > 30.0) region = "full_spine";
    else if (deviation > 22.0) region = "thoracic";

    double severity = (deviation > 30.0) ? 0.9 : 0.5;
    return make_violation(2, "Spinal flexion (rounded back)",
                          "spine", severity, deviation, 15.0,
                          "spine_deviation_deg");
}

SafetyRuleViolation check_rule3_joint_overextension(
    const JointAngleSet& angles) {
    for (auto& a : angles.angles) {
        if (a.angle_deg <= 5.0 && a.angle_deg >= 0.0) {
            double severity = (a.angle_deg < 2.0) ? 0.9 : 0.5;
            return make_violation(3, "Joint hyperextension",
                                  a.name, severity, a.angle_deg, 5.0,
                                  "joint_angle_deg");
        }
        if (a.angle_deg >= 175.0 && a.angle_deg <= 180.0) {
            double severity = (a.angle_deg > 178.0) ? 0.9 : 0.5;
            return make_violation(3, "Joint locked at full extension",
                                  a.name, severity, a.angle_deg, 175.0,
                                  "joint_angle_deg");
        }
    }
    return {};
}

SafetyRuleViolation check_rule4_asymmetry(const SymmetryResult& sym) {
    if (!is_asymmetric(sym)) return {};
    double severity = (sym.max_difference_deg > 20.0) ? 0.9 : 0.5;
    std::string joint = !sym.asymmetric_joints.empty()
                            ? sym.asymmetric_joints[0] : "unknown";
    return make_violation(4, "Bilateral asymmetry",
                          joint, severity, sym.max_difference_deg, 10.0,
                          "left_right_angle_delta_deg");
}

SafetyRuleViolation check_rule5_com_offset(const ComResult& com) {
    if (com.status == SupportStatus::kStable || com.inside_support) return {};
    double dist = std::abs(com.signed_distance_to_support);
    double severity = (dist > 0.08) ? 0.9 : 0.5;
    return make_violation(5, "Center of mass outside support base",
                          "com", severity, dist, 0.0,
                          "signed_distance_to_support");
}

SafetyRuleViolation check_rule6_rapid_movement(
    const std::string& joint_name, double angular_velocity_deg_s) {
    const double speed = std::abs(angular_velocity_deg_s);
    if (speed <= kRapidMovementThresholdDegPerSecond) return {};
    double severity = (speed > kSevereRapidMovementThresholdDegPerSecond) ? 0.9 : 0.5;
    return make_violation(6, "Rapid joint movement",
                          joint_name, severity,
                          angular_velocity_deg_s, kRapidMovementThresholdDegPerSecond,
                          "angular_velocity_deg_s");
}

SafetyRuleViolation check_rule7_rom_insufficient(
    const std::string& joint_name, double current_rom, double expected_rom) {
    if (expected_rom <= 0) return {};
    double ratio = current_rom / expected_rom;
    if (ratio >= 0.5) return {};
    double severity = (ratio < 0.3) ? 0.9 : 0.5;
    return make_violation(7, "Insufficient range of motion",
                          joint_name, severity, current_rom, expected_rom,
                          "range_of_motion_deg");
}

SafetyRuleViolation check_rule8_neck_hyperextension(
    const JointAngleSet& angles, const SegmentSet&) {
    double neck_ang = get_angle(angles, "neck");
    double deviation = std::abs(180.0 - neck_ang);
    if (deviation <= 15.0) return {};
    double severity = (deviation > 25.0) ? 0.9 : 0.5;
    return make_violation(8, "Neck hyperextension",
                          "neck", severity, deviation, 15.0,
                          "neck_deviation_deg");
}

SafetyReport evaluate_all_safety_rules(
    const JointAngleSet& angles,
    const SegmentSet& segments,
    const SymmetryResult& symmetry,
    const ComResult& com,
    const LandmarkArray& landmarks) {

    SafetyReport report;

    auto check = [&](SafetyRuleViolation v) {
        if (v.rule > 0) report.violations.push_back(v);
    };

    check(check_rule1_knee_valgus(angles, segments, landmarks));
    check(check_rule2_spinal_flexion(angles, segments));
    check(check_rule3_joint_overextension(angles));
    check(check_rule4_asymmetry(symmetry));
    check(check_rule5_com_offset(com));
    check(check_rule8_neck_hyperextension(angles, segments));

    // Rules 6 & 7 require temporal data (prev frame), checked by caller.
    report.active_count = report.violations.size();
    report.any_active = report.active_count > 0;

    return report;
}

}  // namespace gemmafit::kinematics
