#pragma once

#include <string>

#include "com_tracker.h"
#include "joint_angles.h"

namespace gemmafit::kinematics {

// ── Movement pattern classification ─────────────────────────────────
// Outputs physical description, NOT exercise name.

enum class DominantJoint {
    kKnee,
    kHip,
    kShoulder,
    kElbow,
    kSpine,
    kAnkle,
    kUnknown,
};

enum class MovementPlane {
    kSagittal,
    kFrontal,
    kTransverse,
    kMixed,
};

enum class ContractionPhase {
    kConcentric,
    kEccentric,
    kIsometric,
    kTransition,
};

enum class SupportBaseType {
    kBipedal,
    kBipedalWide,
    kUnipedal,
    kQuadrupedal,
    kProneHands,
    kSupine,
    kSitting,
    kUnknown,
};

enum class LoadVector {
    kVertical,
    kHorizontal,
    kRotational,
    kBodyweight,
};

struct MovementPattern {
    std::string pattern_label;       // e.g. "bilateral_knee_dominant_sagittal"
    DominantJoint primary_joint = DominantJoint::kUnknown;
    DominantJoint secondary_joint = DominantJoint::kUnknown;
    MovementPlane plane = MovementPlane::kSagittal;
    SupportBaseType base = SupportBaseType::kUnknown;
    LoadVector load = LoadVector::kVertical;
    ContractionPhase phase = ContractionPhase::kTransition;
    double symmetry_score = 1.0;     // 0.0 ~ 1.0
    double cycle_phase = 0.5;        // 0.0 ~ 1.0
    double confidence = 0.0;         // 0.0 ~ 1.0
};

// Primary classification function.
MovementPattern classify_movement(
    const LandmarkArray& landmarks,
    const JointAngleSet& angles,
    const LandmarkArray* prev_landmarks = nullptr,
    const JointAngleSet* prev_angles = nullptr);

// Sub-classifiers exposed for testing.
SupportBaseType classify_support_base(const LandmarkArray& landmarks);
DominantJoint classify_dominant_joint(const JointAngleSet& angles);
MovementPlane classify_movement_plane(const LandmarkArray& landmarks,
                                      DominantJoint primary_joint);
ContractionPhase classify_contraction_phase(const JointAngleSet& angles,
                                            const JointAngleSet* prev_angles,
                                            DominantJoint primary_joint,
                                            SupportBaseType base);

}  // namespace gemmafit::kinematics
