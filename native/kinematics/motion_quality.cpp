#include "motion_quality.h"

#include <algorithm>
#include <cmath>
#include <iomanip>
#include <set>
#include <sstream>

namespace gemmafit::kinematics {
namespace {

constexpr std::size_t kLeftShoulder = 11;
constexpr std::size_t kRightShoulder = 12;
constexpr std::size_t kLeftElbow = 13;
constexpr std::size_t kRightElbow = 14;
constexpr std::size_t kLeftWrist = 15;
constexpr std::size_t kRightWrist = 16;
constexpr std::size_t kLeftHip = 23;
constexpr std::size_t kRightHip = 24;
constexpr std::size_t kLeftKnee = 25;
constexpr std::size_t kRightKnee = 26;
constexpr std::size_t kLeftAnkle = 27;
constexpr std::size_t kRightAnkle = 28;
constexpr std::size_t kLeftFoot = 31;
constexpr std::size_t kRightFoot = 32;
constexpr double kPi = 3.14159265358979323846;

const std::map<std::string, std::vector<std::size_t>> kKeyLandmarks = {
    {"squat", {kLeftShoulder, kRightShoulder, kLeftHip, kRightHip, kLeftKnee, kRightKnee, kLeftAnkle, kRightAnkle}},
    {"push_up", {kLeftShoulder, kRightShoulder, kLeftElbow, kRightElbow, kLeftWrist, kRightWrist, kLeftHip, kRightHip}},
    {"lunge", {kLeftShoulder, kRightShoulder, kLeftHip, kRightHip, kLeftKnee, kRightKnee, kLeftAnkle, kRightAnkle}},
    {"deadlift", {kLeftShoulder, kRightShoulder, kLeftHip, kRightHip, kLeftKnee, kRightKnee, kLeftAnkle, kRightAnkle}},
    {"unknown", {kLeftShoulder, kRightShoulder, kLeftHip, kRightHip}},
};

double clamp(double value, double lo, double hi) {
    return std::max(lo, std::min(hi, value));
}

double round3(double value) {
    return std::round(value * 1000.0) / 1000.0;
}

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

bool has_point(const LandmarkArray& landmarks, std::size_t idx) {
    return idx < landmarks.size() &&
           !(std::abs(landmarks[idx].x) < 1e-9 && std::abs(landmarks[idx].y) < 1e-9);
}

bool visible(const LandmarkArray& landmarks, std::size_t idx, double threshold = 0.35) {
    return has_point(landmarks, idx) && landmarks[idx].z >= threshold;
}

Point2 pt(const LandmarkArray& landmarks, std::size_t idx) {
    return {landmarks[idx].x, landmarks[idx].y};
}

Point2 midpoint2(const LandmarkArray& landmarks, std::size_t a, std::size_t b) {
    return {
        (landmarks[a].x + landmarks[b].x) * 0.5,
        (landmarks[a].y + landmarks[b].y) * 0.5,
    };
}

double distance2(Point2 a, Point2 b) {
    const double dx = a.x - b.x;
    const double dy = a.y - b.y;
    return std::sqrt(dx * dx + dy * dy);
}

double angle2d(Point2 a, Point2 b, Point2 c) {
    const double v1x = a.x - b.x;
    const double v1y = a.y - b.y;
    const double v2x = c.x - b.x;
    const double v2y = c.y - b.y;
    const double dot = v1x * v2x + v1y * v2y;
    const double n1 = std::sqrt(v1x * v1x + v1y * v1y);
    const double n2 = std::sqrt(v2x * v2x + v2y * v2y);
    if (n1 < 1e-9 || n2 < 1e-9) return 180.0;
    return std::acos(clamp(dot / (n1 * n2), -1.0, 1.0)) * 180.0 / kPi;
}

double distance_to_line(Point2 point, Point2 a, Point2 b) {
    const double abx = b.x - a.x;
    const double aby = b.y - a.y;
    const double denom = std::sqrt(abx * abx + aby * aby);
    if (denom < 1e-9) return 0.0;
    return std::abs(abx * (a.y - point.y) - (a.x - point.x) * aby) / denom;
}

double segment_angle_from_vertical(Point2 a, Point2 b) {
    const double dx = b.x - a.x;
    const double dy = b.y - a.y;
    return std::atan2(std::abs(dx), std::abs(dy) + 1e-9) * 180.0 / kPi;
}

double joint_angle_2d(const LandmarkArray& landmarks,
                      std::size_t a, std::size_t b, std::size_t c,
                      double default_value = 180.0) {
    if (!has_point(landmarks, a) || !has_point(landmarks, b) || !has_point(landmarks, c)) {
        return default_value;
    }
    return angle2d(pt(landmarks, a), pt(landmarks, b), pt(landmarks, c));
}

double min_pair_angle_2d(const LandmarkArray& landmarks,
                         std::size_t left_a, std::size_t left_b, std::size_t left_c,
                         std::size_t right_a, std::size_t right_b, std::size_t right_c,
                         double default_value = 180.0) {
    std::vector<double> values;
    if (visible(landmarks, left_a) && visible(landmarks, left_b) && visible(landmarks, left_c)) {
        values.push_back(joint_angle_2d(landmarks, left_a, left_b, left_c));
    }
    if (visible(landmarks, right_a) && visible(landmarks, right_b) && visible(landmarks, right_c)) {
        values.push_back(joint_angle_2d(landmarks, right_a, right_b, right_c));
    }
    if (values.empty()) return default_value;
    return *std::min_element(values.begin(), values.end());
}

double lower_visible_count(const LandmarkArray& landmarks) {
    const std::size_t indices[] = {kLeftHip, kRightHip, kLeftKnee, kRightKnee, kLeftAnkle, kRightAnkle};
    double count = 0.0;
    for (std::size_t idx : indices) {
        if (visible(landmarks, idx)) count += 1.0;
    }
    return count;
}

double arm_visible_count(const LandmarkArray& landmarks) {
    const std::size_t indices[] = {kLeftShoulder, kRightShoulder, kLeftElbow, kRightElbow, kLeftWrist, kRightWrist};
    double count = 0.0;
    for (std::size_t idx : indices) {
        if (visible(landmarks, idx)) count += 1.0;
    }
    return count;
}

double metric_value(const std::vector<NamedMetric>& metrics,
                    const std::string& key,
                    double default_value = 0.0) {
    for (const auto& metric : metrics) {
        if (metric.name == key) return metric.value;
    }
    return default_value;
}

std::string infer_view(const LandmarkArray& landmarks) {
    if (!visible(landmarks, kLeftShoulder) || !visible(landmarks, kRightShoulder) ||
        !visible(landmarks, kLeftHip) || !visible(landmarks, kRightHip)) {
        return "unknown";
    }
    const Point2 shoulder_mid = midpoint2(landmarks, kLeftShoulder, kRightShoulder);
    const Point2 hip_mid = midpoint2(landmarks, kLeftHip, kRightHip);
    const double torso_height = std::max(0.05, distance2(shoulder_mid, hip_mid));
    const double shoulder_width = distance2(pt(landmarks, kLeftShoulder), pt(landmarks, kRightShoulder));
    const double hip_width = distance2(pt(landmarks, kLeftHip), pt(landmarks, kRightHip));
    const double width_ratio = std::max(shoulder_width, hip_width) / torso_height;

    if (width_ratio < 0.18) return "side";
    if (width_ratio < 0.32) return "oblique";
    return "frontal";
}

bool is_frontal_view(const std::string& view) {
    return view == "frontal" || view == "oblique";
}

double avg_key_visibility(const LandmarkArray& landmarks, const std::string& exercise) {
    auto it = kKeyLandmarks.find(exercise);
    const auto& indices = (it == kKeyLandmarks.end()) ? kKeyLandmarks.at("unknown") : it->second;
    if (indices.empty()) return 0.0;
    double sum = 0.0;
    for (std::size_t idx : indices) {
        sum += (idx < landmarks.size()) ? landmarks[idx].z : 0.0;
    }
    return sum / static_cast<double>(indices.size());
}

bool all_key_visible(const LandmarkArray& landmarks,
                     const std::string& exercise,
                     double threshold) {
    auto it = kKeyLandmarks.find(exercise);
    const auto& indices = (it == kKeyLandmarks.end()) ? kKeyLandmarks.at("unknown") : it->second;
    for (std::size_t idx : indices) {
        if (!visible(landmarks, idx, threshold)) return false;
    }
    return true;
}

std::vector<NamedMetric> compute_squat_metrics(
    const LandmarkArray& landmarks,
    const ComResult& com) {
    const double knee_angle = min_pair_angle_2d(
        landmarks, kLeftHip, kLeftKnee, kLeftAnkle, kRightHip, kRightKnee, kRightAnkle);
    const double hip_angle = min_pair_angle_2d(
        landmarks, kLeftShoulder, kLeftHip, kLeftKnee, kRightShoulder, kRightHip, kRightKnee);
    const Point2 shoulder_mid = midpoint2(landmarks, kLeftShoulder, kRightShoulder);
    const Point2 hip_mid = midpoint2(landmarks, kLeftHip, kRightHip);
    const double hip_y = (landmarks[kLeftHip].y + landmarks[kRightHip].y) * 0.5;
    const double knee_y = (landmarks[kLeftKnee].y + landmarks[kRightKnee].y) * 0.5;
    const double ankle_y = (landmarks[kLeftAnkle].y + landmarks[kRightAnkle].y) * 0.5;
    const double depth = clamp((ankle_y - hip_y) / (std::abs(ankle_y - knee_y) + 1e-6), 0.0, 1.0);
    const double knee_dist = distance2(midpoint2(landmarks, kLeftKnee, kLeftKnee),
                                       midpoint2(landmarks, kRightKnee, kRightKnee));
    const double ankle_dist = distance2(midpoint2(landmarks, kLeftAnkle, kLeftAnkle),
                                        midpoint2(landmarks, kRightAnkle, kRightAnkle));
    const double ratio = ankle_dist > 1e-9 ? knee_dist / ankle_dist : 1.0;
    const double left_fppa = std::abs(180.0 - joint_angle_2d(landmarks, kLeftHip, kLeftKnee, kLeftAnkle));
    const double right_fppa = std::abs(180.0 - joint_angle_2d(landmarks, kRightHip, kRightKnee, kRightAnkle));

    return {
        {"depth", depth},
        {"knee_angle", knee_angle},
        {"hip_angle", hip_angle},
        {"trunk_lean", segment_angle_from_vertical(shoulder_mid, hip_mid)},
        {"knee_valgus_ratio", ratio},
        {"fppa_deg", std::max(left_fppa, right_fppa)},
        {"com_offset_ratio", com.inside_support ? 0.0 : 1.25},
        {"tempo_deg_s", 0.0},
    };
}

std::vector<NamedMetric> compute_push_up_metrics(const LandmarkArray& landmarks) {
    const double elbow_angle = min_pair_angle_2d(
        landmarks, kLeftShoulder, kLeftElbow, kLeftWrist, kRightShoulder, kRightElbow, kRightWrist);
    const Point2 shoulder_mid = midpoint2(landmarks, kLeftShoulder, kRightShoulder);
    const Point2 hip_mid = midpoint2(landmarks, kLeftHip, kRightHip);

    bool lower_ref_visible = visible(landmarks, kLeftAnkle) || visible(landmarks, kRightAnkle) ||
                             visible(landmarks, kLeftKnee) || visible(landmarks, kRightKnee);
    Point2 lower_ref = midpoint2(landmarks, kLeftAnkle, kRightAnkle);
    if (!visible(landmarks, kLeftAnkle) && !visible(landmarks, kRightAnkle)) {
        lower_ref = midpoint2(landmarks, kLeftKnee, kRightKnee);
    }

    double body_line = 0.0;
    double hip_sag = 0.0;
    double view_limited = 1.0;
    if (lower_ref_visible && (visible(landmarks, kLeftHip) || visible(landmarks, kRightHip))) {
        body_line = std::abs(180.0 - angle2d(shoulder_mid, hip_mid, lower_ref));
        hip_sag = distance_to_line(hip_mid, shoulder_mid, lower_ref) * 100.0;
        view_limited = 0.0;
    }

    return {
        {"elbow_angle", elbow_angle},
        {"push_up_depth", std::max(0.0, 180.0 - elbow_angle)},
        {"body_line_deviation", body_line},
        {"hip_sag", hip_sag},
        {"body_line_view_limited", view_limited},
        {"tempo_deg_s", 0.0},
    };
}

std::vector<NamedMetric> compute_lunge_metrics(
    const LandmarkArray& landmarks,
    const ComResult& com) {
    const double left_knee_angle = joint_angle_2d(landmarks, kLeftHip, kLeftKnee, kLeftAnkle);
    const double right_knee_angle = joint_angle_2d(landmarks, kRightHip, kRightKnee, kRightAnkle);
    const bool front_is_left = landmarks[kLeftKnee].x < landmarks[kRightKnee].x;
    const double front_knee = front_is_left ? left_knee_angle : right_knee_angle;
    const Point2 shoulder_mid = midpoint2(landmarks, kLeftShoulder, kRightShoulder);
    const Point2 hip_mid = midpoint2(landmarks, kLeftHip, kRightHip);

    return {
        {"front_knee_angle", front_knee},
        {"step_length_proxy", std::abs(landmarks[kLeftAnkle].x - landmarks[kRightAnkle].x)},
        {"trunk_uprightness", segment_angle_from_vertical(shoulder_mid, hip_mid)},
        {"stability", clamp(1.0 - std::abs(com.signed_distance_to_support) * 5.0, 0.0, 1.0)},
        {"knee_asymmetry_expected", std::abs(left_knee_angle - right_knee_angle)},
        {"tempo_deg_s", 0.0},
    };
}

std::vector<NamedMetric> compute_deadlift_metrics(
    const LandmarkArray& landmarks,
    const ComResult&) {
    const double hip_angle = min_pair_angle_2d(
        landmarks, kLeftShoulder, kLeftHip, kLeftKnee, kRightShoulder, kRightHip, kRightKnee);
    const double knee_angle = min_pair_angle_2d(
        landmarks, kLeftHip, kLeftKnee, kLeftAnkle, kRightHip, kRightKnee, kRightAnkle);
    const Point2 shoulder_mid = midpoint2(landmarks, kLeftShoulder, kRightShoulder);
    const Point2 hip_mid = midpoint2(landmarks, kLeftHip, kRightHip);
    const double body_path = std::abs(midpoint2(landmarks, kLeftWrist, kRightWrist).x -
                                      midpoint2(landmarks, kLeftAnkle, kRightAnkle).x);

    return {
        {"hip_hinge", hip_angle},
        {"trunk_angle", segment_angle_from_vertical(shoulder_mid, hip_mid)},
        {"bar_or_body_path_proxy", body_path},
        {"knee_angle", knee_angle},
        {"tempo_deg_s", 0.0},
    };
}

std::string metric_id_for_rule(const SafetyRuleViolation& violation) {
    if (!violation.metric.empty()) return violation.metric;
    switch (violation.rule) {
        case 1: return "knee_valgus_fppa";
        case 2: return "trunk_alignment";
        case 3: return "joint_overextension";
        case 4: return "bilateral_asymmetry";
        case 5: return "com_offset";
        case 6: return "rapid_movement";
        case 7: return "range_of_motion";
        case 8: return "neck_position";
        default: return "quality_gate";
    }
}

std::string status_for_violation(const SafetyRuleViolation& violation,
                                 const std::string& exercise) {
    if (violation.rule == 5 && exercise != "unknown") return "MONITOR";
    if (violation.rule == 3) return "MONITOR";
    return (violation.severity >= 0.9) ? "CRITICAL" : "WARNING";
}

bool violation_applicable(const SafetyRuleViolation& violation,
                          const std::string& exercise,
                          const std::string& view) {
    if (exercise == "unknown") return false;
    if (violation.rule == 1) return exercise == "squat" && is_frontal_view(view);
    if (violation.rule == 4) return exercise != "lunge";
    if (violation.rule == 5) return exercise != "push_up";
    return true;
}

QualityFlag make_flag(const std::string& id,
                      const std::string& status,
                      double value,
                      double threshold,
                      const std::string& evidence,
                      const std::string& reason,
                      const std::string& joint = "",
                      int rule = 0) {
    return {id, status, value, threshold, evidence, reason, joint, rule};
}

void add_not_applicable(MotionQualityReport& report,
                        const std::string& id,
                        const std::string& reason,
                        const std::string& joint = "",
                        int rule = 0,
                        double threshold = 0.0) {
    report.not_applicable.push_back(make_flag(
        id, "NOT_APPLICABLE", 0.0, threshold, "template_based_applicability", reason, joint, rule));
}

void apply_template_gates(MotionQualityReport& report,
                          const JointAngleSet& angles,
                          const SafetyReport& safety,
                          const ComResult& com) {
    if (report.exercise == "unknown") {
        report.quality_flags.push_back(make_flag(
            "exercise_template", "VIEW_LIMITED", report.exercise_confidence, 0.45,
            "heuristic_exercise_detection", "exercise_not_identified_with_enough_confidence"));
        add_not_applicable(report, "template_metrics", "exercise template was not identified");
        return;
    }

    if (report.exercise != "squat") {
        add_not_applicable(
            report, "knee_valgus_fppa",
            "Rule 1 FPPA requires a frontal or near-frontal squat view",
            "knee", 1, 10.0);
    } else if (!is_frontal_view(report.view)) {
        add_not_applicable(
            report, "knee_valgus_fppa",
            "Rule 1 FPPA is view-limited; use a frontal or near-frontal squat recording",
            "knee", 1, 10.0);
    }

    if (report.exercise == "lunge") {
        add_not_applicable(
            report, "bilateral_asymmetry",
            "Lunge is intentionally unilateral; side-to-side angle difference is not a single-frame critical rule",
            "knee", 4, 18.0);
    }
    if (report.exercise == "push_up") {
        add_not_applicable(
            report, "com_offset",
            "COM/BoS is not a high-confidence push-up quality metric in this view",
            "com", 5, 1.0);
        add_not_applicable(
            report, "trunk_angle",
            "Push-up uses body-line and hip-sag metrics instead of standing trunk angle",
            "trunk", 2, 0.0);
    }

    for (const auto& violation : safety.violations) {
        if (!violation_applicable(violation, report.exercise, report.view)) continue;
        report.quality_flags.push_back(make_flag(
            metric_id_for_rule(violation),
            status_for_violation(violation, report.exercise),
            violation.value,
            violation.threshold,
            violation.evidence.empty() ? "prototype_threshold" : violation.evidence,
            report.exercise + ":" + violation.description,
            violation.joint,
            violation.rule));
    }

    if (report.exercise == "squat" && is_frontal_view(report.view)) {
        const double ratio = metric_value(report.template_metrics, "knee_valgus_ratio", 1.0);
        const double fppa = metric_value(report.template_metrics, "fppa_deg", 0.0);
        if (ratio < 0.8 || fppa > 10.0) {
            report.quality_flags.push_back(make_flag(
                fppa > 10.0 ? "fppa_deg" : "knee_valgus_ratio",
                (ratio < 0.65 || fppa > 20.0) ? "CRITICAL" : "WARNING",
                fppa > 10.0 ? fppa : ratio,
                fppa > 10.0 ? 10.0 : 0.8,
                "pose_based_frontal_view",
                "squat_frontal_knee_alignment_proxy",
                "knee",
                1));
        }
    }

    if (report.exercise == "squat") {
        const double trunk = metric_value(report.template_metrics, "trunk_lean", 0.0);
        if (trunk > 20.0) {
            report.quality_flags.push_back(make_flag(
                "trunk_lean", trunk > 35.0 ? "CRITICAL" : "WARNING",
                trunk, 20.0, "pose_based_template_metric",
                "squat_trunk_lean_exceeds_prototype_threshold", "trunk", 2));
        }
    } else if (report.exercise == "deadlift") {
        const double trunk = metric_value(report.template_metrics, "trunk_angle", 0.0);
        if (trunk > 55.0) {
            report.quality_flags.push_back(make_flag(
                "trunk_angle", trunk > 70.0 ? "CRITICAL" : "WARNING",
                trunk, 55.0, "pose_based_template_metric",
                "deadlift_trunk_angle_exceeds_prototype_threshold", "trunk", 2));
        }
    } else if (report.exercise == "lunge") {
        const double trunk = metric_value(report.template_metrics, "trunk_uprightness", 0.0);
        if (trunk > 25.0) {
            report.quality_flags.push_back(make_flag(
                "trunk_uprightness", trunk > 35.0 ? "WARNING" : "MONITOR",
                trunk, 25.0, "pose_based_template_metric",
                "lunge_trunk_uprightness_monitor", "trunk", 2));
        }
    } else if (report.exercise == "push_up") {
        if (metric_value(report.template_metrics, "body_line_view_limited", 0.0) >= 0.5) {
            report.quality_flags.push_back(make_flag(
                "body_line", "VIEW_LIMITED", 0.0, 0.0,
                "template_metric_visibility",
                "lower_body_reference_not_visible_for_body_line", "hip", 0));
        }
        const double body_line = metric_value(report.template_metrics, "body_line_deviation", 0.0);
        const double hip_sag = metric_value(report.template_metrics, "hip_sag", 0.0);
        if (body_line > 15.0) {
            report.quality_flags.push_back(make_flag(
                "body_line", body_line > 25.0 ? "WARNING" : "MONITOR",
                body_line, 15.0, "pose_based_template_metric",
                "push_up_body_line_deviation", "hip", 0));
        }
        if (hip_sag > 8.0) {
            report.quality_flags.push_back(make_flag(
                "hip_sag", hip_sag > 14.0 ? "WARNING" : "MONITOR",
                hip_sag, 8.0, "pose_based_template_metric",
                "push_up_hip_sag_proxy", "hip", 0));
        }
    }

    if (report.exercise != "push_up" && !com.inside_support &&
        com.status != SupportStatus::kStable) {
        const bool already_has_com = std::any_of(
            report.quality_flags.begin(), report.quality_flags.end(),
            [](const QualityFlag& flag) { return flag.id == "com_offset" || flag.id == "signed_distance_to_support"; });
        if (!already_has_com) {
            report.quality_flags.push_back(make_flag(
                "com_offset", "MONITOR", std::abs(com.signed_distance_to_support), 1.0,
                "pose_based_com_estimate", "dynamic_motion_monitor_only", "com", 5));
        }
    }

    if (report.exercise == "lunge") {
        const double asym = metric_value(report.template_metrics, "knee_asymmetry_expected", 0.0);
        if (asym > 35.0) {
            report.quality_flags.push_back(make_flag(
                "lunge_stability", "MONITOR", asym, 35.0,
                "pose_based_unilateral_template",
                "unilateral movement is monitored over time instead of single-frame critical",
                "knee", 4));
        }
    }

    const std::vector<std::string> joints = {
        "left_knee", "right_knee", "left_hip", "right_hip", "left_elbow", "right_elbow"};
    for (const auto& joint : joints) {
        const double angle = get_angle(angles, joint);
        if (angle <= 2.0 || angle >= 178.0) {
            report.quality_flags.push_back(make_flag(
                "joint_overextension_" + joint, "MONITOR", angle,
                angle >= 178.0 ? 178.0 : 2.0,
                "pose_based_conservative_monitor",
                "locked_or_extreme_joint_angle_monitor", joint, 3));
            break;
        }
    }
}

std::string overall_status(const MotionQualityReport& report) {
    if (!report.low_confidence.empty()) return "LOW_CONFIDENCE";
    for (const auto& flag : report.quality_flags) {
        if (flag.status == "CRITICAL") return "CRITICAL";
    }
    for (const auto& flag : report.quality_flags) {
        if (flag.status == "WARNING") return "WARNING";
    }
    for (const auto& flag : report.quality_flags) {
        if (flag.status == "VIEW_LIMITED") return "VIEW_LIMITED";
    }
    for (const auto& flag : report.quality_flags) {
        if (flag.status == "MONITOR") return "MONITOR";
    }
    return "OK";
}

void append_flags_json(std::ostringstream& ss, const std::vector<QualityFlag>& flags) {
    ss << "[";
    bool first = true;
    for (const auto& flag : flags) {
        if (!first) ss << ",";
        first = false;
        ss << "{"
           << "\"id\":\"" << json_escape(flag.id) << "\","
           << "\"status\":\"" << json_escape(flag.status) << "\","
           << "\"value\":" << flag.value << ","
           << "\"threshold\":" << flag.threshold << ","
           << "\"evidence\":\"" << json_escape(flag.evidence) << "\","
           << "\"reason\":\"" << json_escape(flag.reason) << "\","
           << "\"joint\":\"" << json_escape(flag.joint) << "\","
           << "\"rule\":" << flag.rule
           << "}";
    }
    ss << "]";
}

void append_string_array_json(std::ostringstream& ss, const std::vector<std::string>& values) {
    ss << "[";
    bool first = true;
    for (const auto& value : values) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << json_escape(value) << "\"";
    }
    ss << "]";
}

}  // namespace

ExerciseTemplateDetection detect_exercise_template(
    const LandmarkArray& landmarks,
    const JointAngleSet&,
    const MovementPattern& pattern) {
    ExerciseTemplateDetection result;
    result.view = infer_view(landmarks);

    const double lower_count = lower_visible_count(landmarks);
    const double arms_count = arm_visible_count(landmarks);
    const Point2 shoulder_mid = midpoint2(landmarks, kLeftShoulder, kRightShoulder);
    const Point2 hip_mid = midpoint2(landmarks, kLeftHip, kRightHip);
    const Point2 ankle_mid = midpoint2(landmarks, kLeftAnkle, kRightAnkle);
    const Point2 wrist_mid = midpoint2(landmarks, kLeftWrist, kRightWrist);
    const double shoulder_width = distance2(pt(landmarks, kLeftShoulder), pt(landmarks, kRightShoulder));
    const double ankle_separation = std::abs(landmarks[kLeftAnkle].x - landmarks[kRightAnkle].x);
    const double knee_height_diff = std::abs(landmarks[kLeftKnee].y - landmarks[kRightKnee].y);

    const double knee_angle = min_pair_angle_2d(
        landmarks, kLeftHip, kLeftKnee, kLeftAnkle, kRightHip, kRightKnee, kRightAnkle);
    const double hip_angle = min_pair_angle_2d(
        landmarks, kLeftShoulder, kLeftHip, kLeftKnee, kRightShoulder, kRightHip, kRightKnee);
    const double elbow_angle = min_pair_angle_2d(
        landmarks, kLeftShoulder, kLeftElbow, kLeftWrist, kRightShoulder, kRightElbow, kRightWrist);
    const double left_knee = joint_angle_2d(landmarks, kLeftHip, kLeftKnee, kLeftAnkle);
    const double right_knee = joint_angle_2d(landmarks, kRightHip, kRightKnee, kRightAnkle);

    const double knee_bend = std::max(0.0, 180.0 - knee_angle);
    const double hip_bend = std::max(0.0, 180.0 - hip_angle);
    const double elbow_bend = std::max(0.0, 180.0 - elbow_angle);
    const double trunk_lean = segment_angle_from_vertical(shoulder_mid, hip_mid);

    const bool standing_support = lower_count >= 4.0 && ankle_mid.y > hip_mid.y + 0.08;
    const bool wrist_support = arms_count >= 4.0 && wrist_mid.y > shoulder_mid.y + 0.12;
    const bool floor_support = wrist_support && elbow_bend > 15.0 &&
                               (lower_count < 4.0 || hip_mid.y > 0.70 || std::abs(hip_mid.y - shoulder_mid.y) < 0.20);
    const bool lunge_signal = lower_count >= 4.0 &&
                              ankle_separation > std::max(0.04, shoulder_width * 0.45) &&
                              knee_height_diff > 0.055;
    const double knee_asymmetry = std::abs(left_knee - right_knee);

    std::map<std::string, double> scores = {
        {"squat", 0.0}, {"push_up", 0.0}, {"lunge", 0.0}, {"deadlift", 0.0},
    };

    if (floor_support) scores["push_up"] += 0.55;
    if (wrist_support && elbow_bend > 20.0) scores["push_up"] += 0.25;
    if (wrist_support && lower_count < 4.0) scores["push_up"] += 0.20;
    if (elbow_bend > 30.0 && knee_bend < 25.0 && hip_bend < 35.0) scores["push_up"] += 0.35;

    if (standing_support) {
        scores["squat"] += 0.25;
        scores["deadlift"] += 0.20;
        scores["lunge"] += 0.20;
    }
    if (standing_support && !lunge_signal && trunk_lean <= 22.0) scores["squat"] += 0.25;
    if (knee_bend > 15.0) {
        scores["squat"] += 0.35;
        scores["lunge"] += 0.25;
    }
    if (hip_bend > 15.0) {
        scores["squat"] += 0.15;
        scores["deadlift"] += 0.30;
    }
    if (trunk_lean <= 35.0 && knee_bend > 20.0) scores["squat"] += 0.20;
    if (trunk_lean > 25.0 && hip_bend > 10.0) scores["deadlift"] += 0.35;
    if (hip_bend > knee_bend + 10.0) scores["deadlift"] += 0.20;
    if (lunge_signal) {
        scores["lunge"] += 0.55;
        scores["squat"] = std::max(0.0, scores["squat"] - 0.20);
    }
    if (knee_asymmetry > 25.0 && lower_count >= 4.0) scores["lunge"] += 0.15;
    if (pattern.primary_joint == DominantJoint::kHip) scores["deadlift"] += 0.10;
    if (pattern.primary_joint == DominantJoint::kKnee) scores["squat"] += 0.05;

    for (auto& item : scores) item.second = clamp(round3(item.second), 0.0, 1.0);
    result.candidate_scores = scores;

    auto best = std::max_element(
        scores.begin(), scores.end(),
        [](const auto& lhs, const auto& rhs) { return lhs.second < rhs.second; });
    std::vector<double> sorted;
    for (const auto& item : scores) sorted.push_back(item.second);
    std::sort(sorted.begin(), sorted.end(), std::greater<double>());

    if (floor_support) result.basis.push_back("floor_support");
    if (standing_support) result.basis.push_back("standing_support");
    if (knee_bend > 15.0) result.basis.push_back("knee_flexion");
    if (hip_bend > 15.0) result.basis.push_back("hip_hinge");
    if (elbow_bend > 20.0) result.basis.push_back("elbow_flexion");
    if (lunge_signal) result.basis.push_back("unilateral_lunge_signal");
    if (trunk_lean > 25.0) result.basis.push_back("trunk_lean");
    if (!pattern.pattern_label.empty()) result.basis.push_back(pattern.pattern_label);

    if (best == scores.end() || best->second < 0.45) {
        result.exercise = "unknown";
        result.confidence = best == scores.end() ? 0.0 : best->second;
        result.basis = {"low_confidence_detection"};
        return result;
    }
    if (sorted.size() >= 2 && sorted[0] - sorted[1] < 0.08) {
        result.exercise = "unknown";
        result.confidence = sorted[0];
        result.basis = {"ambiguous_template_scores"};
        return result;
    }

    result.exercise = best->first;
    result.confidence = best->second;
    return result;
}

std::vector<NamedMetric> extract_template_metrics(
    const std::string& exercise,
    const LandmarkArray& landmarks,
    const JointAngleSet&,
    const ComResult& com) {
    if (exercise == "squat") return compute_squat_metrics(landmarks, com);
    if (exercise == "push_up") return compute_push_up_metrics(landmarks);
    if (exercise == "lunge") return compute_lunge_metrics(landmarks, com);
    if (exercise == "deadlift") return compute_deadlift_metrics(landmarks, com);
    return {{"tempo_deg_s", 0.0}};
}

MotionQualityReport analyze_motion_quality(
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const SafetyReport& safety,
    const MovementPattern& pattern,
    const ComResult& com,
    double visibility_threshold) {
    MotionQualityReport report;
    report.notes = {
        "movement_quality_feedback_not_medical_diagnosis",
        "single_camera_pose_based_feedback",
        "joint_force_out_of_scope",
    };

    const ExerciseTemplateDetection detection = detect_exercise_template(landmarks, angles, pattern);
    report.exercise = detection.exercise;
    report.exercise_confidence = detection.confidence;
    report.view = detection.view;
    report.exercise_basis = detection.basis;
    report.candidate_scores = detection.candidate_scores;
    report.template_metrics = extract_template_metrics(report.exercise, landmarks, angles, com);

    if (report.exercise != "unknown") {
        const double avg_vis = avg_key_visibility(landmarks, report.exercise);
        if (!all_key_visible(landmarks, report.exercise, visibility_threshold)) {
            QualityFlag flag = make_flag(
                "visibility", "LOW_CONFIDENCE", avg_vis, visibility_threshold,
                "pose_based_confidence",
                "exercise_keypoint_visibility_below_threshold");
            report.low_confidence.push_back(flag);
            report.quality_flags.push_back(flag);
            report.overall_status = "LOW_CONFIDENCE";
            return report;
        }
    }

    apply_template_gates(report, angles, safety, com);
    report.overall_status = overall_status(report);
    return report;
}

std::string to_json(const MotionQualityReport& report) {
    std::ostringstream ss;
    ss << std::fixed << std::setprecision(3);
    ss << "{"
       << "\"exercise\":\"" << json_escape(report.exercise) << "\","
       << "\"exercise_confidence\":" << report.exercise_confidence << ","
       << "\"view\":\"" << json_escape(report.view) << "\","
       << "\"exercise_basis\":";
    append_string_array_json(ss, report.exercise_basis);

    ss << ",\"candidate_scores\":{";
    bool first = true;
    for (const auto& item : report.candidate_scores) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << json_escape(item.first) << "\":" << item.second;
    }
    ss << "},\"template_metrics\":{";
    first = true;
    for (const auto& metric : report.template_metrics) {
        if (!first) ss << ",";
        first = false;
        ss << "\"" << json_escape(metric.name) << "\":" << metric.value;
    }
    ss << "},\"quality_flags\":";
    append_flags_json(ss, report.quality_flags);
    ss << ",\"not_applicable\":";
    append_flags_json(ss, report.not_applicable);
    ss << ",\"low_confidence\":";
    append_flags_json(ss, report.low_confidence);
    ss << ",\"overall_status\":\"" << json_escape(report.overall_status) << "\","
       << "\"unsupported_judgments\":["
       << "\"joint_force\","
       << "\"clinical_injury_risk\","
       << "\"medical_diagnosis\","
       << "\"muscle_activation_percentage\""
       << "],\"model_boundary\":\"movement_quality_feedback_not_medical_diagnosis\","
       << "\"notes\":";
    append_string_array_json(ss, report.notes);
    ss << "}";
    return ss.str();
}

std::string build_motion_report_json(
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const SafetyReport& safety,
    const MovementPattern& pattern,
    const ComResult& com,
    double visibility_threshold) {
    return to_json(analyze_motion_quality(
        landmarks, angles, safety, pattern, com, visibility_threshold));
}

}  // namespace gemmafit::kinematics
