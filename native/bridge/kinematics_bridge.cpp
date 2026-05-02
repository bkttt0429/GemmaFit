#include "kinematics_bridge.h"

#include "../kinematics/motion_quality.h"

#include <sstream>
#include <string>

namespace gemmafit::bridge {

KinematicsOutput run_biomechanics_pipeline(
    const float* landmarks_99, const float* prev_landmarks_99,
    double visibility_threshold) {

    using namespace gemmafit::kinematics;
    KinematicsOutput output;

    // Parse input
    LandmarkArray landmarks = landmarks_from_float99(landmarks_99, 99);
    LandmarkArray prev_landmarks;
    bool has_prev = (prev_landmarks_99 != nullptr);
    if (has_prev) prev_landmarks = landmarks_from_float99(prev_landmarks_99, 99);

    // Step 1: Confidence gate
    ConfidenceResult conf = evaluate_confidence(landmarks, visibility_threshold);
    output.confidence_json = to_json(conf);
    if (!conf.pass) {
        output.success = true;  // gate is successful, but LLM should not be called
        output.combined_json = "{\"gate\":\"blocked\",\"reason\":\"" +
                               conf.fail_reason + "\"}";
        return output;
    }

    // Step 2: Joint angles
    JointAngleSet angles = compute_all_joint_angles(landmarks);
    JointAngleSet prev_angles;
    if (has_prev) prev_angles = compute_all_joint_angles(prev_landmarks);
    output.error_json = to_json(angles);

    // Step 3: Body segments
    SegmentSet segments = compute_all_segments(landmarks);

    // Step 4: Symmetry
    SymmetryResult symmetry = evaluate_symmetry(angles);

    // Step 5: COM
    ComResult com = evaluate_com_over_base_of_support(landmarks);

    // Step 6: Safety rules
    SafetyReport safety = evaluate_all_safety_rules(
        angles, segments, symmetry, com, landmarks);

    // Step 7: Movement classifier
    MovementPattern pattern = classify_movement(
        landmarks, angles,
        has_prev ? &prev_landmarks : nullptr,
        has_prev ? &prev_angles : nullptr);

    // Step 8: Muscle focus
    MuscleFocusEstimate muscle = estimate_muscle_focus(pattern);

    // Serialize
    output.pattern_json = to_json(pattern);
    output.safety_json = to_json(safety);
    output.muscle_json = to_json(muscle);
    output.motion_report_json = gemmafit::kinematics::build_motion_report_json(
        landmarks, angles, safety, pattern, com, visibility_threshold);

    // Combined JSON for LLM input
    std::ostringstream combined;
    combined << "{"
             << "\"pattern\":" << to_json(pattern) << ","
             << "\"safety\":" << to_json(safety) << ","
             << "\"muscle\":" << to_json(muscle) << ","
             << "\"motion_report\":" << output.motion_report_json
             << "}";
    output.combined_json = combined.str();
    output.success = true;

    return output;
}

std::string to_json(const gemmafit::kinematics::JointAngleSet& angles) {
    std::ostringstream ss;
    ss << "{";
    bool first = true;
    for (auto& a : angles.angles) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << a.name << "\":" << a.angle_deg;
    }
    ss << "}";
    return ss.str();
}

std::string to_json(const gemmafit::kinematics::SafetyReport& report) {
    std::ostringstream ss;
    ss << "{\"any_active\":" << (report.any_active ? "true" : "false")
       << ",\"active_count\":" << report.active_count
       << ",\"violations\":[";
    bool first = true;
    for (auto& v : report.violations) {
        if (!first) ss << ",";
        first = false;
        ss << "{\"rule\":" << v.rule
           << ",\"joint\":\"" << v.joint << "\""
           << ",\"description\":\"" << v.description << "\""
           << ",\"severity\":" << v.severity
           << ",\"value\":" << v.value
           << ",\"threshold\":" << v.threshold << "}";
    }
    ss << "]}";
    return ss.str();
}

std::string to_json(const gemmafit::kinematics::MovementPattern& pattern) {
    auto plane_str = [](gemmafit::kinematics::MovementPlane p) {
        switch (p) {
            case gemmafit::kinematics::MovementPlane::kSagittal: return "sagittal";
            case gemmafit::kinematics::MovementPlane::kFrontal: return "frontal";
            case gemmafit::kinematics::MovementPlane::kTransverse: return "transverse";
            default: return "mixed";
        }
    };
    auto phase_str = [](gemmafit::kinematics::ContractionPhase p) {
        switch (p) {
            case gemmafit::kinematics::ContractionPhase::kConcentric: return "concentric";
            case gemmafit::kinematics::ContractionPhase::kEccentric: return "eccentric";
            case gemmafit::kinematics::ContractionPhase::kIsometric: return "isometric";
            default: return "transition";
        }
    };

    std::ostringstream ss;
    ss << "{"
       << "\"pattern_label\":\"" << pattern.pattern_label << "\","
       << "\"plane\":\"" << plane_str(pattern.plane) << "\","
       << "\"phase\":\"" << phase_str(pattern.phase) << "\","
       << "\"symmetry\":" << pattern.symmetry_score << ","
       << "\"cycle_phase\":" << pattern.cycle_phase << ","
       << "\"confidence\":" << pattern.confidence
       << "}";
    return ss.str();
}

std::string to_json(const gemmafit::kinematics::MuscleFocusEstimate& focus) {
    std::ostringstream ss;
    ss << "{"
       << "\"estimated_primary\":[";
    bool first = true;
    for (auto& m : focus.primary) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << m << "\"";
    }
    ss << "],\"estimated_secondary\":[";
    first = true;
    for (auto& m : focus.secondary) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << m << "\"";
    }
    ss << "],\"confidence\":" << focus.confidence
       << ",\"note\":\"" << focus.limitation_note << "\""
       << "}";
    return ss.str();
}

std::string to_json(const gemmafit::kinematics::ConfidenceResult& conf) {
    std::ostringstream ss;
    ss << "{"
       << "\"pass\":" << (conf.pass ? "true" : "false")
       << ",\"overall_visibility\":" << conf.overall_visibility
       << ",\"threshold\":" << conf.threshold
       << ",\"low_count\":" << conf.low_visibility_count;
    if (!conf.fail_reason.empty())
        ss << ",\"fail_reason\":\"" << conf.fail_reason << "\"";
    ss << "}";
    return ss.str();
}

}  // namespace gemmafit::bridge
