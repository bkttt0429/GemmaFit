#include "muscle_focus.h"

namespace gemmafit::kinematics {

namespace {

struct MuscleLookup {
    DominantJoint joint;
    SupportBaseType base;
    MovementPlane plane;
    const char* primary;
    const char* secondary;
};

const MuscleLookup kLookupTable[] = {
    {DominantJoint::kKnee, SupportBaseType::kBipedal, MovementPlane::kSagittal,
     "quadriceps, gluteus_maximus",
     "hamstrings, calves, core_stabilizers"},
    {DominantJoint::kKnee, SupportBaseType::kBipedalWide, MovementPlane::kSagittal,
     "quadriceps, gluteus_maximus, adductors",
     "hamstrings, calves, core_stabilizers"},
    {DominantJoint::kKnee, SupportBaseType::kUnipedal, MovementPlane::kSagittal,
     "quadriceps, gluteus_maximus, gluteus_medius",
     "hamstrings, adductors, core_stabilizers"},
    {DominantJoint::kHip, SupportBaseType::kBipedal, MovementPlane::kSagittal,
     "gluteus_maximus, hamstrings",
     "erector_spinae, core_stabilizers"},
    {DominantJoint::kShoulder, SupportBaseType::kBipedal, MovementPlane::kSagittal,
     "latissimus_dorsi, rhomboids, biceps",
     "posterior_deltoid, trapezius_mid"},
    {DominantJoint::kShoulder, SupportBaseType::kBipedal, MovementPlane::kFrontal,
     "deltoids, triceps",
     "upper_trapezius, core_stabilizers"},
    {DominantJoint::kShoulder, SupportBaseType::kProneHands, MovementPlane::kSagittal,
     "pectoralis_major, triceps, anterior_deltoid",
     "serratus_anterior, core_stabilizers"},
    {DominantJoint::kElbow, SupportBaseType::kBipedal, MovementPlane::kSagittal,
     "biceps, brachialis",
     "forearm_flexors, shoulder_stabilizers"},
    {DominantJoint::kSpine, SupportBaseType::kProneHands, MovementPlane::kSagittal,
     "rectus_abdominis, transversus_abdominis, obliques",
     "gluteus_maximus, shoulder_stabilizers"},
    {DominantJoint::kSpine, SupportBaseType::kQuadrupedal, MovementPlane::kSagittal,
     "rectus_abdominis, transversus_abdominis, obliques",
     "gluteus_maximus, shoulder_stabilizers"},
};

void split(const char* str, std::vector<std::string>& out) {
    out.clear();
    std::string s(str);
    std::size_t pos = 0;
    while (pos < s.size()) {
        std::size_t comma = s.find(',', pos);
        if (comma == std::string::npos) comma = s.size();
        std::string token = s.substr(pos, comma - pos);
        // trim whitespace
        while (!token.empty() && token.front() == ' ') token.erase(0, 1);
        while (!token.empty() && token.back() == ' ') token.pop_back();
        if (!token.empty()) out.push_back(token);
        pos = comma + 1;
    }
}

}  // anonymous namespace

MuscleFocusEstimate estimate_muscle_focus(const MovementPattern& pattern) {
    MuscleFocusEstimate est;
    est.confidence = pattern.confidence;
    est.limitation_note = "pose_based_estimate_not_emg";

    for (auto& entry : kLookupTable) {
        if (entry.joint == pattern.primary_joint &&
            entry.base == pattern.base &&
            entry.plane == pattern.plane) {
            split(entry.primary, est.primary);
            split(entry.secondary, est.secondary);
            return est;
        }
    }

    // Fallback: use only joint + base
    for (auto& entry : kLookupTable) {
        if (entry.joint == pattern.primary_joint) {
            split(entry.primary, est.primary);
            split(entry.secondary, est.secondary);
            return est;
        }
    }

    est.primary = {"core_stabilizers"};
    est.secondary = {"postural_muscles"};
    return est;
}

std::string muscle_label(const std::string& internal_name) {
    if (internal_name == "quadriceps") return "Quadriceps";
    if (internal_name == "gluteus_maximus") return "Gluteus Maximus";
    if (internal_name == "gluteus_medius") return "Gluteus Medius";
    if (internal_name == "hamstrings") return "Hamstrings";
    if (internal_name == "calves") return "Calves";
    if (internal_name == "adductors") return "Adductors";
    if (internal_name == "core_stabilizers") return "Core Stabilizers";
    if (internal_name == "erector_spinae") return "Erector Spinae";
    if (internal_name == "pectoralis_major") return "Pectoralis Major";
    if (internal_name == "triceps") return "Triceps";
    if (internal_name == "biceps") return "Biceps";
    if (internal_name == "brachialis") return "Brachialis";
    if (internal_name == "deltoids") return "Deltoids";
    if (internal_name == "anterior_deltoid") return "Anterior Deltoid";
    if (internal_name == "posterior_deltoid") return "Posterior Deltoid";
    if (internal_name == "latissimus_dorsi") return "Latissimus Dorsi";
    if (internal_name == "rhomboids") return "Rhomboids";
    if (internal_name == "trapezius_mid") return "Mid Trapezius";
    if (internal_name == "upper_trapezius") return "Upper Trapezius";
    if (internal_name == "serratus_anterior") return "Serratus Anterior";
    if (internal_name == "rectus_abdominis") return "Rectus Abdominis";
    if (internal_name == "transversus_abdominis") return "Transversus Abdominis";
    if (internal_name == "obliques") return "Obliques";
    if (internal_name == "forearm_flexors") return "Forearm Flexors";
    if (internal_name == "shoulder_stabilizers") return "Shoulder Stabilizers";
    if (internal_name == "ankle_stabilizers") return "Ankle Stabilizers";
    if (internal_name == "postural_muscles") return "Postural Muscles";
    return internal_name;
}

}  // namespace gemmafit::kinematics
