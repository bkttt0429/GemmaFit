#pragma once

#include <map>
#include <string>
#include <vector>

#include "com_tracker.h"
#include "joint_angles.h"
#include "movement_classifier.h"
#include "safety_monitor.h"

namespace gemmafit::kinematics {

struct NamedMetric {
    std::string name;
    double value = 0.0;
};

struct QualityFlag {
    std::string id;
    std::string status;      // OK, MONITOR, WARNING, CRITICAL, NOT_APPLICABLE, LOW_CONFIDENCE, VIEW_LIMITED
    double value = 0.0;
    double threshold = 0.0;
    std::string evidence;
    std::string reason;
    std::string joint;
    int rule = 0;
};

struct EvidenceNode {
    std::string id;
    std::string type;        // landmark_visibility, template_metric, quality_gate, safety_rule, not_applicable_gate, capability
    std::string label;
    std::string metric;
    double value = 0.0;
    std::string unit;
    double confidence = 0.0;
    std::string status = "OK";
    std::string source_module = "motion_quality";
    std::string source_function;
    std::string frame_range = "current_frame";
    std::vector<std::string> landmark_refs;
    std::string evidence_level;
    std::string reason;
};

struct EvidenceEdge {
    std::string from;
    std::string to;
    std::string relation;    // derived_from, gated_by, thresholded_by, supports, blocks
};

struct CapabilityItem {
    std::string metric;
    std::string reason;
    double confidence_ceiling = 0.0;
    std::vector<std::string> required_evidence;
    std::vector<std::string> evidence_refs;
};

struct CapabilityContract {
    std::vector<CapabilityItem> can_judge;
    std::vector<CapabilityItem> cannot_judge;
};

struct EvidenceDag {
    std::vector<EvidenceNode> nodes;
    std::vector<EvidenceEdge> edges;
};

struct ExerciseTemplateDetection {
    std::string exercise = "unknown";
    double confidence = 0.0;
    std::string view = "unknown";  // frontal, side, oblique, unknown
    std::vector<std::string> basis;
    std::map<std::string, double> candidate_scores;
};

struct MotionQualityReport {
    std::string exercise = "unknown";
    double exercise_confidence = 0.0;
    std::string view = "unknown";
    std::vector<std::string> exercise_basis;
    std::map<std::string, double> candidate_scores;
    std::vector<NamedMetric> template_metrics;
    std::vector<QualityFlag> quality_flags;
    std::vector<QualityFlag> not_applicable;
    std::vector<QualityFlag> low_confidence;
    std::string overall_status = "OK";
    std::vector<std::string> notes;
    CapabilityContract capability_contract;
    EvidenceDag evidence_dag;
};

ExerciseTemplateDetection detect_exercise_template(
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const MovementPattern& pattern);

std::vector<NamedMetric> extract_template_metrics(
    const std::string& exercise,
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const ComResult& com);

MotionQualityReport analyze_motion_quality(
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const SafetyReport& safety,
    const MovementPattern& pattern,
    const ComResult& com,
    double visibility_threshold = 0.5);

std::string to_json(const MotionQualityReport& report);

std::string build_motion_report_json(
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const SafetyReport& safety,
    const MovementPattern& pattern,
    const ComResult& com,
    double visibility_threshold = 0.5);

}  // namespace gemmafit::kinematics
