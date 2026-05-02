#include "movement_classifier.h"

#include <cmath>

namespace gemmafit::kinematics {

SupportBaseType classify_support_base(const LandmarkArray& landmarks) {
    double mid_hip_y = (landmarks[23].y + landmarks[24].y) / 2.0;
    double mid_ankle_y = (landmarks[27].y + landmarks[28].y) / 2.0;
    double mid_wrist_y = (landmarks[15].y + landmarks[16].y) / 2.0;
    double mid_shoulder_y = (landmarks[11].y + landmarks[12].y) / 2.0;

    bool ankle_below_hip = mid_ankle_y > mid_hip_y;
    bool wrist_below_shoulder = mid_wrist_y > mid_shoulder_y;

    if (ankle_below_hip && mid_ankle_y > 0.7) {
        // Check stance width
        double ankle_width = std::abs(landmarks[27].x - landmarks[28].x);
        double shoulder_width = std::abs(landmarks[11].x - landmarks[12].x);
        if (ankle_width > shoulder_width * 1.2)
            return SupportBaseType::kBipedalWide;
        return SupportBaseType::kBipedal;
    }

    if (wrist_below_shoulder && mid_wrist_y > 0.75)
        return SupportBaseType::kProneHands;

    if (wrist_below_shoulder && mid_wrist_y > mid_hip_y)
        return SupportBaseType::kQuadrupedal;

    return SupportBaseType::kUnknown;
}

DominantJoint classify_dominant_joint(const JointAngleSet& angles) {
    double knee_rom = std::max(
        std::abs(180.0 - get_angle(angles, "left_knee")),
        std::abs(180.0 - get_angle(angles, "right_knee")));
    double hip_rom = std::max(
        std::abs(180.0 - get_angle(angles, "left_hip")),
        std::abs(180.0 - get_angle(angles, "right_hip")));
    double shoulder_rom = std::max(
        std::abs(180.0 - get_angle(angles, "left_shoulder")),
        std::abs(180.0 - get_angle(angles, "right_shoulder")));
    double elbow_rom = std::max(
        std::abs(180.0 - get_angle(angles, "left_elbow")),
        std::abs(180.0 - get_angle(angles, "right_elbow")));

    if (knee_rom > 30.0 && hip_rom > 20.0) return DominantJoint::kKnee;
    if (shoulder_rom > 30.0) return DominantJoint::kShoulder;
    if (elbow_rom > 30.0) return DominantJoint::kElbow;
    if (hip_rom > 25.0) return DominantJoint::kHip;
    return DominantJoint::kSpine;
}

MovementPlane classify_movement_plane(
    const LandmarkArray& landmarks, DominantJoint) {
    double shoulder_tilt = std::abs(landmarks[11].y - landmarks[12].y);
    double hip_tilt = std::abs(landmarks[23].y - landmarks[24].y);
    if (shoulder_tilt > 0.05 || hip_tilt > 0.05)
        return MovementPlane::kFrontal;
    return MovementPlane::kSagittal;
}

ContractionPhase classify_contraction_phase(
    const JointAngleSet& angles,
    const JointAngleSet* prev_angles,
    DominantJoint primary_joint,
    SupportBaseType) {
    if (!prev_angles) return ContractionPhase::kTransition;

    // Use primary joint average for phase detection
    auto get_pair = [&](const JointAngleSet& a, const std::string& l,
                         const std::string& r) -> double {
        return (get_angle(a, l) + get_angle(a, r)) / 2.0;
    };

    double cur = 0.0, prev = 0.0;
    switch (primary_joint) {
        case DominantJoint::kKnee:
            cur = get_pair(angles, "left_knee", "right_knee");
            prev = get_pair(*prev_angles, "left_knee", "right_knee");
            break;
        case DominantJoint::kHip:
            cur = get_pair(angles, "left_hip", "right_hip");
            prev = get_pair(*prev_angles, "left_hip", "right_hip");
            break;
        case DominantJoint::kShoulder:
            cur = get_pair(angles, "left_shoulder", "right_shoulder");
            prev = get_pair(*prev_angles, "left_shoulder", "right_shoulder");
            break;
        case DominantJoint::kElbow:
            cur = get_pair(angles, "left_elbow", "right_elbow");
            prev = get_pair(*prev_angles, "left_elbow", "right_elbow");
            break;
        default:
            return ContractionPhase::kTransition;
    }

    double diff = cur - prev;
    if (std::abs(diff) < 0.5) return ContractionPhase::kIsometric;
    if (std::abs(diff) < 2.0) return ContractionPhase::kTransition;
    return (diff > 0) ? ContractionPhase::kConcentric
                       : ContractionPhase::kEccentric;
}

MovementPattern classify_movement(
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const LandmarkArray* prev_landmarks,
    const JointAngleSet* prev_angles) {

    MovementPattern pattern;

    pattern.base = classify_support_base(landmarks);
    pattern.primary_joint = classify_dominant_joint(angles);
    pattern.plane = classify_movement_plane(landmarks, pattern.primary_joint);
    pattern.phase = classify_contraction_phase(
        angles, prev_angles, pattern.primary_joint, pattern.base);

    // Build label
    auto base_name = [](SupportBaseType b) {
        switch (b) {
            case SupportBaseType::kBipedal: return "bipedal";
            case SupportBaseType::kBipedalWide: return "bipedal_wide";
            case SupportBaseType::kUnipedal: return "unipedal";
            case SupportBaseType::kQuadrupedal: return "quadrupedal";
            case SupportBaseType::kProneHands: return "prone_hands";
            default: return "unknown";
        }
    };
    auto joint_name = [](DominantJoint j) {
        switch (j) {
            case DominantJoint::kKnee: return "knee";
            case DominantJoint::kHip: return "hip";
            case DominantJoint::kShoulder: return "shoulder";
            case DominantJoint::kElbow: return "elbow";
            case DominantJoint::kSpine: return "spine";
            default: return "unknown";
        }
    };
    auto plane_name = [](MovementPlane p) {
        switch (p) {
            case MovementPlane::kSagittal: return "sagittal";
            case MovementPlane::kFrontal: return "frontal";
            case MovementPlane::kTransverse: return "transverse";
            default: return "mixed";
        }
    };

    pattern.pattern_label = std::string(base_name(pattern.base)) + "_" +
                            joint_name(pattern.primary_joint) +
                            "_dominant_" + plane_name(pattern.plane);

    // Secondary joint
    pattern.secondary_joint = (pattern.primary_joint == DominantJoint::kKnee)
                                  ? DominantJoint::kHip : DominantJoint::kKnee;

    // Symmetry from joint differences
    double knee_diff = std::abs(get_angle(angles, "left_knee") -
                                 get_angle(angles, "right_knee"));
    double hip_diff = std::abs(get_angle(angles, "left_hip") -
                                get_angle(angles, "right_hip"));
    double avg_diff = (knee_diff + hip_diff) / 2.0;
    pattern.symmetry_score = std::max(0.0, 1.0 - avg_diff / 20.0);

    // Confidence as mean visibility
    double vis_sum = 0.0;
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i)
        vis_sum += landmarks[i].z;  // z = visibility
    pattern.confidence = vis_sum / kPoseLandmarkCount;

    pattern.load = (pattern.base == SupportBaseType::kProneHands)
                       ? LoadVector::kHorizontal : LoadVector::kVertical;

    return pattern;
}

}  // namespace gemmafit::kinematics
