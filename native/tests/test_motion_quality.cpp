#include "../kinematics/com_tracker.h"
#include "../kinematics/joint_angles.h"
#include "../kinematics/motion_quality.h"
#include "../kinematics/movement_classifier.h"

#include <algorithm>
#include <cstdlib>
#include <iostream>
#include <set>
#include <string>
#include <vector>

using gemmafit::kinematics::ComResult;
using gemmafit::kinematics::LandmarkArray;
using gemmafit::kinematics::MotionQualityReport;
using gemmafit::kinematics::Point3;
using gemmafit::kinematics::SafetyReport;

namespace {

int passes = 0;
int fails = 0;

void check(const char* name, bool condition) {
    if (condition) {
        ++passes;
        std::cout << "[PASS] " << name << "\n";
    } else {
        ++fails;
        std::cout << "[FAIL] " << name << "\n";
    }
}

LandmarkArray base_landmarks() {
    LandmarkArray lm{};
    for (auto& p : lm) p = {0.0, 0.0, 0.9};
    lm[0] = {0.50, 0.10, 0.9};
    lm[7] = {0.47, 0.11, 0.9};
    lm[8] = {0.53, 0.11, 0.9};
    lm[11] = {0.42, 0.28, 0.9};
    lm[12] = {0.58, 0.28, 0.9};
    lm[13] = {0.38, 0.45, 0.9};
    lm[14] = {0.62, 0.45, 0.9};
    lm[15] = {0.36, 0.58, 0.9};
    lm[16] = {0.64, 0.58, 0.9};
    lm[23] = {0.45, 0.55, 0.9};
    lm[24] = {0.55, 0.55, 0.9};
    lm[25] = {0.35, 0.75, 0.9};
    lm[26] = {0.65, 0.75, 0.9};
    lm[27] = {0.38, 0.94, 0.9};
    lm[28] = {0.62, 0.94, 0.9};
    lm[29] = {0.38, 0.96, 0.9};
    lm[30] = {0.62, 0.96, 0.9};
    lm[31] = {0.38, 0.98, 0.9};
    lm[32] = {0.62, 0.98, 0.9};
    return lm;
}

LandmarkArray side_squat_landmarks() {
    LandmarkArray lm = base_landmarks();
    lm[11] = {0.500, 0.28, 0.9};
    lm[12] = {0.515, 0.28, 0.9};
    lm[23] = {0.505, 0.55, 0.9};
    lm[24] = {0.520, 0.55, 0.9};
    lm[25] = {0.430, 0.75, 0.9};
    lm[26] = {0.445, 0.75, 0.9};
    lm[27] = {0.500, 0.94, 0.9};
    lm[28] = {0.515, 0.94, 0.9};
    return lm;
}

LandmarkArray push_up_landmarks() {
    LandmarkArray lm = base_landmarks();
    lm[11] = {0.35, 0.55, 0.9};
    lm[12] = {0.45, 0.55, 0.9};
    lm[13] = {0.28, 0.67, 0.9};
    lm[14] = {0.52, 0.67, 0.9};
    lm[15] = {0.35, 0.80, 0.9};
    lm[16] = {0.45, 0.80, 0.9};
    lm[23] = {0.62, 0.58, 0.9};
    lm[24] = {0.72, 0.58, 0.9};
    lm[25] = {0.78, 0.59, 0.9};
    lm[26] = {0.84, 0.59, 0.9};
    lm[27] = {0.90, 0.60, 0.9};
    lm[28] = {0.96, 0.60, 0.9};
    lm[31] = {0.94, 0.61, 0.9};
    lm[32] = {0.99, 0.61, 0.9};
    return lm;
}

LandmarkArray lunge_landmarks() {
    LandmarkArray lm = base_landmarks();
    lm[25] = {0.32, 0.66, 0.9};
    lm[26] = {0.75, 0.83, 0.9};
    lm[27] = {0.25, 0.94, 0.9};
    lm[28] = {0.80, 0.94, 0.9};
    return lm;
}

LandmarkArray narrow_lunge_landmarks() {
    LandmarkArray lm = base_landmarks();
    lm[25] = {0.34, 0.68, 0.9};
    lm[26] = {0.61, 0.86, 0.9};
    lm[27] = {0.46, 0.94, 0.9};
    lm[28] = {0.58, 0.94, 0.9};
    return lm;
}

LandmarkArray deadlift_landmarks() {
    LandmarkArray lm = base_landmarks();
    lm[11] = {0.37, 0.40, 0.9};
    lm[12] = {0.39, 0.40, 0.9};
    lm[23] = {0.54, 0.58, 0.9};
    lm[24] = {0.56, 0.58, 0.9};
    lm[25] = {0.59, 0.76, 0.9};
    lm[26] = {0.61, 0.76, 0.9};
    lm[27] = {0.57, 0.94, 0.9};
    lm[28] = {0.59, 0.94, 0.9};
    lm[31] = {0.57, 0.98, 0.9};
    lm[32] = {0.59, 0.98, 0.9};
    return lm;
}

MotionQualityReport analyze(const LandmarkArray& lm, double visibility_threshold = 0.5) {
    auto angles = gemmafit::kinematics::compute_all_joint_angles(lm);
    auto pattern = gemmafit::kinematics::classify_movement(lm, angles);
    ComResult com;
    com.inside_support = true;
    com.status = gemmafit::kinematics::SupportStatus::kStable;
    SafetyReport safety;
    return gemmafit::kinematics::analyze_motion_quality(
        lm, angles, safety, pattern, com, visibility_threshold);
}

bool has_flag(const std::vector<gemmafit::kinematics::QualityFlag>& flags,
              const std::string& id,
              const std::string& status = "") {
    for (const auto& flag : flags) {
        if (flag.id == id && (status.empty() || flag.status == status)) return true;
    }
    return false;
}

bool has_can_judge(const MotionQualityReport& report, const std::string& metric) {
    for (const auto& item : report.capability_contract.can_judge) {
        if (item.metric == metric) return true;
    }
    return false;
}

bool has_cannot_judge(const MotionQualityReport& report, const std::string& metric) {
    for (const auto& item : report.capability_contract.cannot_judge) {
        if (item.metric == metric) return true;
    }
    return false;
}

bool every_quality_flag_has_evidence_node(const MotionQualityReport& report) {
    for (const auto& flag : report.quality_flags) {
        bool found = false;
        for (const auto& node : report.evidence_dag.nodes) {
            if (node.metric == flag.id &&
                (node.type == "quality_gate" || node.type == "safety_rule")) {
                found = true;
                break;
            }
        }
        if (!found) return false;
    }
    return true;
}

bool every_edge_endpoint_exists(const MotionQualityReport& report) {
    std::set<std::string> ids;
    for (const auto& node : report.evidence_dag.nodes) ids.insert(node.id);
    for (const auto& edge : report.evidence_dag.edges) {
        if (ids.count(edge.from) == 0 || ids.count(edge.to) == 0) return false;
    }
    return true;
}

bool every_node_has_required_contract_fields(const MotionQualityReport& report) {
    for (const auto& node : report.evidence_dag.nodes) {
        if (node.id.empty() ||
            node.type.empty() ||
            node.metric.empty() ||
            node.source_module.empty() ||
            node.source_function.empty() ||
            node.frame_range.empty() ||
            node.evidence_level.empty() ||
            node.reason.empty()) {
            return false;
        }
    }
    return true;
}

bool evidence_node_ids_are_unique(const MotionQualityReport& report) {
    std::set<std::string> ids;
    for (const auto& node : report.evidence_dag.nodes) {
        if (!ids.insert(node.id).second) return false;
    }
    return true;
}

bool every_edge_relation_is_allowed(const MotionQualityReport& report) {
    const std::set<std::string> allowed = {
        "derived_from", "gated_by", "thresholded_by", "supports", "blocks",
    };
    for (const auto& edge : report.evidence_dag.edges) {
        if (allowed.count(edge.relation) == 0) return false;
    }
    return true;
}

bool json_contains_evidence_contract_fields(const MotionQualityReport& report) {
    const std::string json = gemmafit::kinematics::to_json(report);
    return json.find("\"evidence_level\"") != std::string::npos &&
           json.find("\"reason\"") != std::string::npos &&
           json.find("\"evidence_id\"") != std::string::npos;
}

void test_squat_frontal_metrics() {
    std::cout << "\n-- Motion Quality: frontal squat --\n";
    auto report = analyze(base_landmarks());
    check("frontal squat detected", report.exercise == "squat");
    check("frontal view inferred", report.view == "frontal");
    check("template metrics populated", report.template_metrics.size() >= 4);
    check("knee valgus gate applicable", !has_flag(report.not_applicable, "knee_valgus_fppa"));
    check("frontal knee valgus can be judged",
          has_can_judge(report, "frontal_knee_valgus"));
    check("evidence DAG edges are valid",
          every_edge_endpoint_exists(report));
    check("evidence DAG node ids are unique",
          evidence_node_ids_are_unique(report));
    check("evidence DAG edge relations are allowed",
          every_edge_relation_is_allowed(report));
    check("evidence nodes include contract fields",
          every_node_has_required_contract_fields(report));
    check("evidence JSON includes contract fields",
          json_contains_evidence_contract_fields(report));
}

void test_side_squat_refuses_fppa() {
    std::cout << "\n-- Motion Quality: side squat refusal --\n";
    auto report = analyze(side_squat_landmarks());
    check("side squat detected", report.exercise == "squat");
    check("side view inferred", report.view == "side");
    check("FPPA not applicable in side view",
          has_flag(report.not_applicable, "knee_valgus_fppa", "NOT_APPLICABLE"));
    check("side view still judges depth",
          has_can_judge(report, "squat_depth"));
    check("side view cannot judge frontal knee valgus",
          has_cannot_judge(report, "frontal_knee_valgus"));
}

void test_push_up_template_gates() {
    std::cout << "\n-- Motion Quality: push-up --\n";
    auto report = analyze(push_up_landmarks());
    check("push-up detected", report.exercise == "push_up");
    check("knee valgus not applicable for push-up",
          has_flag(report.not_applicable, "knee_valgus_fppa", "NOT_APPLICABLE"));
    check("COM not applicable for push-up",
          has_flag(report.not_applicable, "com_offset", "NOT_APPLICABLE"));
    check("push-up can judge elbow angle",
          has_can_judge(report, "elbow_angle"));
}

void test_lunge_asymmetry_downgrade() {
    std::cout << "\n-- Motion Quality: lunge --\n";
    auto report = analyze(lunge_landmarks());
    check("lunge detected", report.exercise == "lunge");
    check("bilateral asymmetry not single-frame critical",
          has_flag(report.not_applicable, "bilateral_asymmetry", "NOT_APPLICABLE"));
    check("lunge cannot judge bilateral asymmetry",
          has_cannot_judge(report, "bilateral_asymmetry"));
}

void test_narrow_lunge_template_detection() {
    std::cout << "\n-- Motion Quality: narrow lunge --\n";
    auto report = analyze(narrow_lunge_landmarks());
    check("narrow lunge detected", report.exercise == "lunge");
    check("narrow lunge basis includes asymmetric knee bend",
          std::find(report.exercise_basis.begin(), report.exercise_basis.end(),
                    "asymmetric_knee_bend") != report.exercise_basis.end());
}

void test_deadlift_template_detection() {
    std::cout << "\n-- Motion Quality: deadlift --\n";
    auto report = analyze(deadlift_landmarks());
    check("deadlift detected", report.exercise == "deadlift");
    check("deadlift can judge hip hinge",
          has_can_judge(report, "hip_hinge"));
    check("frontal knee valgus not applicable for deadlift",
          has_cannot_judge(report, "frontal_knee_valgus"));
}

void test_low_confidence_gate() {
    std::cout << "\n-- Motion Quality: low confidence --\n";
    auto report = analyze(base_landmarks(), 0.95);
    check("low confidence status", report.overall_status == "LOW_CONFIDENCE");
    check("low confidence flag present",
          has_flag(report.low_confidence, "visibility", "LOW_CONFIDENCE"));
    check("low confidence blocks hard form judgment",
          has_cannot_judge(report, "hard_form_judgment"));
    check("quality flags have evidence nodes",
          every_quality_flag_has_evidence_node(report));
    check("low confidence evidence DAG edges are valid",
          every_edge_endpoint_exists(report));
}

}  // namespace

int main() {
    test_squat_frontal_metrics();
    test_side_squat_refuses_fppa();
    test_push_up_template_gates();
    test_lunge_asymmetry_downgrade();
    test_narrow_lunge_template_detection();
    test_deadlift_template_detection();
    test_low_confidence_gate();

    std::cout << "\nResult: " << passes << " PASS, " << fails << " FAIL\n";
    return fails > 0 ? EXIT_FAILURE : EXIT_SUCCESS;
}
