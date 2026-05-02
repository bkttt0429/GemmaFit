#include "joint_angles.h"

#include <cmath>

namespace gemmafit::kinematics {
namespace {
constexpr double kPi = 3.14159265358979323846;
}  // namespace

double angle_between(const Point3& a, const Point3& b, const Point3& c) {
    double v1x = a.x - b.x, v1y = a.y - b.y, v1z = a.z - b.z;
    double v2x = c.x - b.x, v2y = c.y - b.y, v2z = c.z - b.z;
    double dot = v1x * v2x + v1y * v2y + v1z * v2z;
    double n1 = std::sqrt(v1x * v1x + v1y * v1y + v1z * v1z);
    double n2 = std::sqrt(v2x * v2x + v2y * v2y + v2z * v2z);
    if (n1 < 1e-9 || n2 < 1e-9) return 0.0;
    double cos_a = dot / (n1 * n2);
    if (cos_a > 1.0) cos_a = 1.0;
    if (cos_a < -1.0) cos_a = -1.0;
    return std::acos(cos_a) * 180.0 / kPi;
}

JointAngleSet compute_all_joint_angles(const LandmarkArray& landmarks) {
    JointAngleSet set;

    auto add = [&](const std::string& name, std::size_t a, std::size_t b, std::size_t c) {
        double ang = angle_between(landmarks[a], landmarks[b], landmarks[c]);
        set.angles.push_back({name, ang});
    };

    // Knee: hip-knee-ankle
    add("left_knee", 23, 25, 27);
    add("right_knee", 24, 26, 28);

    // Hip: shoulder-hip-knee
    add("left_hip", 11, 23, 25);
    add("right_hip", 12, 24, 26);

    // Elbow: shoulder-elbow-wrist
    add("left_elbow", 11, 13, 15);
    add("right_elbow", 12, 14, 16);

    // Shoulder: hip-shoulder-elbow
    add("left_shoulder", 23, 11, 13);
    add("right_shoulder", 24, 12, 14);

    // Ankle: knee-ankle-foot_index
    add("left_ankle", 25, 27, 31);
    add("right_ankle", 26, 28, 32);

    // Spine: shoulder_mid-hip_mid-knee_mid
    Point3 shoulder_mid{
        (landmarks[11].x + landmarks[12].x) / 2.0,
        (landmarks[11].y + landmarks[12].y) / 2.0,
        (landmarks[11].z + landmarks[12].z) / 2.0,
    };
    Point3 hip_mid{
        (landmarks[23].x + landmarks[24].x) / 2.0,
        (landmarks[23].y + landmarks[24].y) / 2.0,
        (landmarks[23].z + landmarks[24].z) / 2.0,
    };
    Point3 knee_mid{
        (landmarks[25].x + landmarks[26].x) / 2.0,
        (landmarks[25].y + landmarks[26].y) / 2.0,
        (landmarks[25].z + landmarks[26].z) / 2.0,
    };
    double spine_ang = angle_between(shoulder_mid, hip_mid, knee_mid);
    set.angles.push_back({"spine", spine_ang});

    // Neck: ear_mid-shoulder_mid-hip_mid
    Point3 ear_mid{
        (landmarks[7].x + landmarks[8].x) / 2.0,
        (landmarks[7].y + landmarks[8].y) / 2.0,
        (landmarks[7].z + landmarks[8].z) / 2.0,
    };
    double neck_ang = angle_between(ear_mid, shoulder_mid, hip_mid);
    set.angles.push_back({"neck", neck_ang});

    return set;
}

double get_angle(const JointAngleSet& set, const std::string& name) {
    for (auto& a : set.angles) {
        if (a.name == name) return a.angle_deg;
    }
    return 0.0;
}

}  // namespace gemmafit::kinematics
