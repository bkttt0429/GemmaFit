#include "kinematics_bridge.h"

#include "../kinematics/motion_quality.h"

#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace gemmafit::bridge {

namespace {

std::mutex g_subject_selector_mutex;
gemmafit::kinematics::SubjectSelector g_subject_selector;

std::string json_escape(const std::string& value) {
    std::ostringstream ss;
    for (char c : value) {
        switch (c) {
            case '\\': ss << "\\\\"; break;
            case '"': ss << "\\\""; break;
            case '\n': ss << "\\n"; break;
            case '\r': ss << "\\r"; break;
            case '\t': ss << "\\t"; break;
            default: ss << c; break;
        }
    }
    return ss.str();
}

KinematicsOutput run_pipeline_for_landmarks(
    const gemmafit::kinematics::LandmarkArray& landmarks,
    const gemmafit::kinematics::LandmarkArray* previous_landmarks,
    double visibility_threshold,
    const std::string& subject_selection_json) {

    using namespace gemmafit::kinematics;
    KinematicsOutput output;
    output.subject_selection_json = subject_selection_json;

    ConfidenceResult conf = evaluate_confidence(landmarks, visibility_threshold);
    output.confidence_json = to_json(conf);
    if (!conf.pass) {
        output.success = true;
        std::ostringstream blocked;
        blocked << "{"
                << "\"gate\":\"blocked\","
                << "\"reason\":\"" << json_escape(conf.fail_reason) << "\","
                << "\"confidence\":" << output.confidence_json;
        if (!subject_selection_json.empty()) {
            blocked << ",\"subject_selection\":" << subject_selection_json;
        }
        blocked << "}";
        output.combined_json = blocked.str();
        return output;
    }

    JointAngleSet angles = compute_all_joint_angles(landmarks);
    JointAngleSet previous_angles;
    if (previous_landmarks != nullptr) {
        previous_angles = compute_all_joint_angles(*previous_landmarks);
    }
    output.error_json = to_json(angles);

    SegmentSet segments = compute_all_segments(landmarks);
    SymmetryResult symmetry = evaluate_symmetry(angles);
    ComResult com = evaluate_com_over_base_of_support(landmarks);
    SafetyReport safety = evaluate_all_safety_rules(
        angles, segments, symmetry, com, landmarks);
    MovementPattern pattern = classify_movement(
        landmarks,
        angles,
        previous_landmarks,
        previous_landmarks != nullptr ? &previous_angles : nullptr);
    MuscleFocusEstimate muscle = estimate_muscle_focus(pattern);

    output.pattern_json = to_json(pattern);
    output.safety_json = to_json(safety);
    output.muscle_json = to_json(muscle);
    output.motion_report_json = build_motion_report_json(
        landmarks, angles, safety, pattern, com, visibility_threshold);

    std::ostringstream combined;
    combined << "{";
    bool wrote = false;
    if (!subject_selection_json.empty()) {
        combined << "\"subject_selection\":" << subject_selection_json;
        wrote = true;
    }
    if (wrote) combined << ",";
    combined << "\"confidence\":" << output.confidence_json << ","
             << "\"pattern\":" << output.pattern_json << ","
             << "\"safety\":" << output.safety_json << ","
             << "\"muscle\":" << output.muscle_json << ","
             << "\"motion_report\":" << output.motion_report_json
             << "}";
    output.combined_json = combined.str();
    output.success = true;

    return output;
}

gemmafit::kinematics::SubjectSelection update_subject_selector_locked(
    const float* candidates_nx99,
    std::size_t candidate_count,
    bool has_tap,
    double tap_x,
    double tap_y,
    bool clear_lock) {

    using namespace gemmafit::kinematics;
    if (clear_lock) {
        g_subject_selector.reset();
    }
    if (has_tap) {
        g_subject_selector.request_tap(tap_x, tap_y);
    }

    std::vector<PoseCandidate> candidates = SubjectSelector::candidates_from_float(
        candidates_nx99,
        std::min(candidate_count, g_subject_selector.config().max_candidates),
        g_subject_selector.config().keypoint_visibility_floor);
    SubjectSelection selection = g_subject_selector.update(candidates);
    selection.candidate_count = candidate_count;
    return selection;
}

}  // namespace

KinematicsOutput run_biomechanics_pipeline(
    const float* landmarks_99,
    const float* prev_landmarks_99,
    double visibility_threshold) {

    using namespace gemmafit::kinematics;
    LandmarkArray landmarks = landmarks_from_float99(landmarks_99, 99);
    LandmarkArray previous_landmarks;
    LandmarkArray* previous_ptr = nullptr;
    if (prev_landmarks_99 != nullptr) {
        previous_landmarks = landmarks_from_float99(prev_landmarks_99, 99);
        previous_ptr = &previous_landmarks;
    }
    return run_pipeline_for_landmarks(
        landmarks,
        previous_ptr,
        visibility_threshold,
        "");
}

KinematicsOutput run_biomechanics_pipeline_candidates(
    const float* candidates_nx99,
    std::size_t candidate_count,
    double visibility_threshold,
    long long timestamp_ms,
    bool has_tap,
    double tap_x,
    double tap_y,
    bool clear_lock) {

    (void)timestamp_ms;
    KinematicsOutput output;

    std::lock_guard<std::mutex> lock(g_subject_selector_mutex);
    auto selection = update_subject_selector_locked(
        candidates_nx99,
        candidate_count,
        has_tap,
        tap_x,
        tap_y,
        clear_lock);
    const std::string selection_json = gemmafit::kinematics::to_json(selection);
    output.subject_selection_json = selection_json;

    if (!selection.has_candidate) {
        output.success = true;
        const std::string reason = selection.reason.empty()
            ? "multi_person_selection_required"
            : selection.reason;
        std::ostringstream blocked;
        blocked << "{"
                << "\"gate\":\"blocked\","
                << "\"reason\":\"" << json_escape(reason) << "\","
                << "\"subject_selection\":" << selection_json
                << "}";
        output.combined_json = blocked.str();
        return output;
    }

    return run_pipeline_for_landmarks(
        selection.candidate.landmarks,
        nullptr,
        visibility_threshold,
        selection_json);
}

std::string select_subject_candidates(
    const float* candidates_nx99,
    std::size_t candidate_count,
    long long timestamp_ms,
    bool has_tap,
    double tap_x,
    double tap_y,
    bool clear_lock) {

    (void)timestamp_ms;
    std::lock_guard<std::mutex> lock(g_subject_selector_mutex);
    const auto selection = update_subject_selector_locked(
        candidates_nx99,
        candidate_count,
        has_tap,
        tap_x,
        tap_y,
        clear_lock);
    return gemmafit::kinematics::to_json(selection);
}

void reset_subject_selector() {
    std::lock_guard<std::mutex> lock(g_subject_selector_mutex);
    g_subject_selector.reset();
}

std::string to_json(const gemmafit::kinematics::JointAngleSet& angles) {
    std::ostringstream ss;
    ss << "{";
    bool first = true;
    for (const auto& a : angles.angles) {
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
    for (const auto& v : report.violations) {
        if (!first) ss << ",";
        first = false;
        ss << "{\"rule\":" << v.rule
           << ",\"joint\":\"" << json_escape(v.joint) << "\""
           << ",\"description\":\"" << json_escape(v.description) << "\""
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
       << "\"pattern_label\":\"" << json_escape(pattern.pattern_label) << "\","
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
    ss << "{\"estimated_primary\":[";
    bool first = true;
    for (const auto& m : focus.primary) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << json_escape(m) << "\"";
    }
    ss << "],\"estimated_secondary\":[";
    first = true;
    for (const auto& m : focus.secondary) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << json_escape(m) << "\"";
    }
    ss << "],\"confidence\":" << focus.confidence
       << ",\"note\":\"" << json_escape(focus.limitation_note) << "\""
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
    if (!conf.fail_reason.empty()) {
        ss << ",\"fail_reason\":\"" << json_escape(conf.fail_reason) << "\"";
    }
    ss << "}";
    return ss.str();
}

}  // namespace gemmafit::bridge
