#include "body_segments.h"

#include <cmath>

namespace gemmafit::kinematics {

namespace {
constexpr double kPi = 3.14159265358979323846;
Point3 subtract(const Point3& a, const Point3& b) {
    return {a.x - b.x, a.y - b.y, a.z - b.z};
}

double norm(const Point3& p) {
    return std::sqrt(p.x * p.x + p.y * p.y + p.z * p.z);
}

Point3 normalize(const Point3& p) {
    double n = norm(p);
    if (n < 1e-9) return {0.0, 0.0, 0.0};
    return {p.x / n, p.y / n, p.z / n};
}
}  // anonymous namespace

SegmentSet compute_all_segments(const LandmarkArray& landmarks) {
    SegmentSet set;

    auto add = [&](const std::string& name, std::size_t a, std::size_t b) {
        Point3 dir = subtract(landmarks[b], landmarks[a]);
        double len = norm(dir);
        set.segments.push_back({name, landmarks[a], normalize(dir), len});
    };

    add("torso", 11, 23);
    add("torso_right", 12, 24);
    add("left_thigh", 23, 25);
    add("right_thigh", 24, 26);
    add("left_shank", 25, 27);
    add("right_shank", 26, 28);
    add("left_upper_arm", 11, 13);
    add("right_upper_arm", 12, 14);
    add("left_forearm", 13, 15);
    add("right_forearm", 14, 16);

    // Neck
    Point3 ear_mid{
        (landmarks[7].x + landmarks[8].x) / 2.0,
        (landmarks[7].y + landmarks[8].y) / 2.0,
        (landmarks[7].z + landmarks[8].z) / 2.0,
    };
    Point3 shoulder_mid{
        (landmarks[11].x + landmarks[12].x) / 2.0,
        (landmarks[11].y + landmarks[12].y) / 2.0,
        (landmarks[11].z + landmarks[12].z) / 2.0,
    };
    Point3 dir = subtract(shoulder_mid, ear_mid);
    double len = norm(dir);
    set.segments.push_back({"neck", ear_mid, normalize(dir), len});

    return set;
}

double segment_angle_deg(const SegmentVector& a, const SegmentVector& b) {
    double dot = a.direction.x * b.direction.x +
                 a.direction.y * b.direction.y +
                 a.direction.z * b.direction.z;
    if (dot > 1.0) dot = 1.0;
    if (dot < -1.0) dot = -1.0;
    return std::acos(dot) * 180.0 / kPi;
}

}  // namespace gemmafit::kinematics
