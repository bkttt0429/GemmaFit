#include "../kinematics/com_tracker.h"
#include "../kinematics/joint_angles.h"
#include "../kinematics/motion_quality.h"
#include "../kinematics/movement_classifier.h"

#include <cstdlib>
#include <iostream>
#include <set>
#include <string>

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
    test_low_confidence_gate();

    std::cout << "\nResult: " << passes << " PASS, " << fails << " FAIL\n";
    return fails > 0 ? EXIT_FAILURE : EXIT_SUCCESS;
}
