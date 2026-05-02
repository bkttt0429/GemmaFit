#pragma once

#include <array>
#include <string>
#include <vector>

#include "com_tracker.h"

namespace gemmafit::kinematics {

// ── Body segment vector ─────────────────────────────────────────────

struct SegmentVector {
    std::string name;       // e.g. "torso", "left_thigh"
    Point3 origin;          // proximal joint position
    Point3 direction;       // unit-length direction vector
    double length = 0.0;    // Euclidean length (normalized coords)
};

struct SegmentSet {
    std::vector<SegmentVector> segments;
};

// ── Segment definitions ─────────────────────────────────────────────
// Computed from 33 MediaPipe landmarks.
//
// Segments returned:
//   torso, torso_right       — shoulder → hip
//   left_thigh, right_thigh  — hip → knee
//   left_shank, right_shank  — knee → ankle
//   left_upper_arm, right_upper_arm — shoulder → elbow
//   left_forearm, right_forearm     — elbow → wrist
//   neck                      — ear_mid → shoulder_mid

SegmentSet compute_all_segments(const LandmarkArray& landmarks);

// Angle between two segment vectors (deg).
double segment_angle_deg(const SegmentVector& a, const SegmentVector& b);

}  // namespace gemmafit::kinematics
