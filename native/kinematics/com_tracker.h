#pragma once

#include <array>
#include <cstddef>
#include <string>
#include <vector>

namespace gemmafit::kinematics {

struct Point2 {
    double x = 0.0;
    double y = 0.0;
};

struct Point3 {
    double x = 0.0;
    double y = 0.0;
    double z = 0.0;
};

enum class ProjectionPlane {
    // Use with image-normalized landmarks.
    kXY,
    // Use with MediaPipe world landmarks where Y is vertical and X/Z are ground-plane axes.
    kXZ,
};

enum class BodyProfile {
    kFemale,
    kMale,
    // Mean of de Leva female and male Table 4 values. This is the default because
    // the prototype does not ask the user for sex-specific anthropometrics.
    kNeutral,
};

enum class SupportStatus {
    kStable,
    kOutsideSupport,
    kInsufficientLandmarks,
    kDegenerateSupport,
};

struct SegmentInertiaParameter {
    const char* name;
    double mass_fraction;
    double com_fraction_from_proximal;
};

struct ComResult {
    Point3 center_of_mass;
    Point2 projected_com;
    std::vector<Point2> support_polygon;
    bool inside_support = false;
    double signed_distance_to_support = 0.0;
    std::string offset_direction;
    SupportStatus status = SupportStatus::kInsufficientLandmarks;
};

constexpr std::size_t kPoseLandmarkCount = 33;
using LandmarkArray = std::array<Point3, kPoseLandmarkCount>;

const std::array<SegmentInertiaParameter, 8>& de_leva_parameters(BodyProfile profile);

Point3 estimate_center_of_mass(
    const LandmarkArray& landmarks,
    BodyProfile profile = BodyProfile::kNeutral);

Point2 project_to_plane(const Point3& point, ProjectionPlane plane);

std::vector<Point2> foot_support_points(
    const LandmarkArray& landmarks,
    ProjectionPlane plane = ProjectionPlane::kXZ);

std::vector<Point2> convex_hull(std::vector<Point2> points);

bool point_in_convex_polygon(const Point2& point, const std::vector<Point2>& polygon);

double signed_distance_to_polygon(const Point2& point, const std::vector<Point2>& polygon);

ComResult evaluate_com_over_base_of_support(
    const LandmarkArray& landmarks,
    BodyProfile profile = BodyProfile::kNeutral,
    ProjectionPlane plane = ProjectionPlane::kXZ);

LandmarkArray landmarks_from_float99(const float* values, std::size_t count);

}  // namespace gemmafit::kinematics
