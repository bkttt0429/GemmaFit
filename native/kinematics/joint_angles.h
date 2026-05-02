#pragma once

#include <array>
#include <cstddef>
#include <string>
#include <vector>

#include "com_tracker.h"

namespace gemmafit::kinematics {

// ── Joint angle data ────────────────────────────────────────────────

struct JointAngle {
    std::string name;     // e.g. "left_knee", "spine"
    double angle_deg = 0.0;
};

struct JointAngleSet {
    std::vector<JointAngle> angles;
};

// ── 12 primary joint angles ─────────────────────────────────────────
// Computed from 33 MediaPipe landmarks.
//
// Angles returned:
//   left_knee, right_knee   — hip-knee-ankle
//   left_hip, right_hip     — shoulder-hip-knee
//   left_elbow, right_elbow — shoulder-elbow-wrist
//   left_shoulder, right_shoulder — hip-shoulder-elbow
//   left_ankle, right_ankle — knee-ankle-foot_index
//   spine                   — shoulder_mid-hip_mid-knee_mid (sagittal)
//   neck                    — ear_mid-shoulder_mid-hip_mid

JointAngleSet compute_all_joint_angles(const LandmarkArray& landmarks);

// Convenience: compute a single joint angle deg for points a-b-c.
double angle_between(const Point3& a, const Point3& b, const Point3& c);

// Derive a single angle from the set by name. Returns 0 if not found.
double get_angle(const JointAngleSet& set, const std::string& name);

}  // namespace gemmafit::kinematics
