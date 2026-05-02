#include "com_tracker.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>

namespace gemmafit::kinematics {
namespace {

constexpr double kEpsilon = 1e-9;

enum PoseLandmark : std::size_t {
    kNose = 0,
    kLeftEar = 7,
    kRightEar = 8,
    kLeftShoulder = 11,
    kRightShoulder = 12,
    kLeftElbow = 13,
    kRightElbow = 14,
    kLeftWrist = 15,
    kRightWrist = 16,
    kLeftIndex = 19,
    kRightIndex = 20,
    kLeftHip = 23,
    kRightHip = 24,
    kLeftKnee = 25,
    kRightKnee = 26,
    kLeftAnkle = 27,
    kRightAnkle = 28,
    kLeftHeel = 29,
    kRightHeel = 30,
    kLeftFootIndex = 31,
    kRightFootIndex = 32,
};

// de Leva P. (1996), Journal of Biomechanics 29(9), Table 4.
// Mass fractions are relative to whole-body mass. COM fractions are measured
// from the segment proximal or cranial endpoint listed in Table 4.
constexpr std::array<SegmentInertiaParameter, 8> kFemaleDeLeva = {{
    {"head", 0.0668, 0.5894},
    {"trunk", 0.4257, 0.4151},
    {"upper_arm", 0.0255, 0.5754},
    {"forearm", 0.0138, 0.4559},
    {"hand", 0.0056, 0.7474},
    {"thigh", 0.1478, 0.3612},
    {"shank", 0.0481, 0.4416},
    {"foot", 0.0129, 0.4014},
}};

constexpr std::array<SegmentInertiaParameter, 8> kMaleDeLeva = {{
    {"head", 0.0694, 0.5976},
    {"trunk", 0.4346, 0.4486},
    {"upper_arm", 0.0271, 0.5772},
    {"forearm", 0.0162, 0.4574},
    {"hand", 0.0061, 0.7900},
    {"thigh", 0.1416, 0.4095},
    {"shank", 0.0433, 0.4459},
    {"foot", 0.0137, 0.4415},
}};

constexpr std::array<SegmentInertiaParameter, 8> kNeutralDeLeva = {{
    {"head", 0.0681, 0.5935},
    {"trunk", 0.43015, 0.43185},
    {"upper_arm", 0.0263, 0.5763},
    {"forearm", 0.0150, 0.45665},
    {"hand", 0.00585, 0.7687},
    {"thigh", 0.1447, 0.38535},
    {"shank", 0.0457, 0.44375},
    {"foot", 0.0133, 0.42145},
}};

enum SegmentIndex : std::size_t {
    kHeadSegment = 0,
    kTrunkSegment = 1,
    kUpperArmSegment = 2,
    kForearmSegment = 3,
    kHandSegment = 4,
    kThighSegment = 5,
    kShankSegment = 6,
    kFootSegment = 7,
};

bool is_finite(const Point3& point) {
    return std::isfinite(point.x) && std::isfinite(point.y) && std::isfinite(point.z);
}

bool is_finite(const Point2& point) {
    return std::isfinite(point.x) && std::isfinite(point.y);
}

Point3 add(const Point3& lhs, const Point3& rhs) {
    return {lhs.x + rhs.x, lhs.y + rhs.y, lhs.z + rhs.z};
}

Point3 scale(const Point3& point, double factor) {
    return {point.x * factor, point.y * factor, point.z * factor};
}

Point3 midpoint(const Point3& lhs, const Point3& rhs) {
    return scale(add(lhs, rhs), 0.5);
}

Point3 interpolate(const Point3& proximal, const Point3& distal, double fraction) {
    return {
        proximal.x + (distal.x - proximal.x) * fraction,
        proximal.y + (distal.y - proximal.y) * fraction,
        proximal.z + (distal.z - proximal.z) * fraction,
    };
}

double cross(const Point2& origin, const Point2& a, const Point2& b) {
    return (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x);
}

double distance(const Point2& lhs, const Point2& rhs) {
    const double dx = lhs.x - rhs.x;
    const double dy = lhs.y - rhs.y;
    return std::sqrt(dx * dx + dy * dy);
}

double distance_to_segment(const Point2& point, const Point2& a, const Point2& b) {
    const double dx = b.x - a.x;
    const double dy = b.y - a.y;
    const double length_sq = dx * dx + dy * dy;
    if (length_sq <= kEpsilon) {
        return distance(point, a);
    }
    const double t = std::clamp(((point.x - a.x) * dx + (point.y - a.y) * dy) / length_sq, 0.0, 1.0);
    return distance(point, {a.x + t * dx, a.y + t * dy});
}

Point3 weighted_average(const std::vector<std::pair<Point3, double>>& weighted_points) {
    Point3 sum{};
    double total_weight = 0.0;
    for (const auto& [point, weight] : weighted_points) {
        if (!is_finite(point) || weight <= 0.0) {
            continue;
        }
        sum = add(sum, scale(point, weight));
        total_weight += weight;
    }
    if (total_weight <= kEpsilon) {
        return {
            std::numeric_limits<double>::quiet_NaN(),
            std::numeric_limits<double>::quiet_NaN(),
            std::numeric_limits<double>::quiet_NaN(),
        };
    }
    return scale(sum, 1.0 / total_weight);
}

std::string offset_direction(const Point2& point, const std::vector<Point2>& polygon) {
    if (polygon.empty()) {
        return "unknown";
    }

    Point2 centroid{};
    for (const auto& vertex : polygon) {
        centroid.x += vertex.x;
        centroid.y += vertex.y;
    }
    centroid.x /= static_cast<double>(polygon.size());
    centroid.y /= static_cast<double>(polygon.size());

    const double dx = point.x - centroid.x;
    const double dy = point.y - centroid.y;
    if (std::abs(dx) >= std::abs(dy)) {
        return dx >= 0.0 ? "right" : "left";
    }
    return dy >= 0.0 ? "forward" : "backward";
}

}  // namespace

const std::array<SegmentInertiaParameter, 8>& de_leva_parameters(BodyProfile profile) {
    switch (profile) {
        case BodyProfile::kFemale:
            return kFemaleDeLeva;
        case BodyProfile::kMale:
            return kMaleDeLeva;
        case BodyProfile::kNeutral:
        default:
            return kNeutralDeLeva;
    }
}

Point3 estimate_center_of_mass(const LandmarkArray& landmarks, BodyProfile profile) {
    const auto& params = de_leva_parameters(profile);

    const Point3 mid_shoulder = midpoint(landmarks[kLeftShoulder], landmarks[kRightShoulder]);
    const Point3 mid_hip = midpoint(landmarks[kLeftHip], landmarks[kRightHip]);
    const Point3 mid_ear = midpoint(landmarks[kLeftEar], landmarks[kRightEar]);

    std::vector<std::pair<Point3, double>> segments;
    segments.reserve(15);

    // MediaPipe does not expose de Leva's vertex/gonion endpoints. Use the ear
    // midpoint to nose axis as a practical head proxy while retaining the mass
    // fraction from de Leva.
    segments.push_back({
        interpolate(mid_ear, landmarks[kNose], params[kHeadSegment].com_fraction_from_proximal),
        params[kHeadSegment].mass_fraction,
    });

    segments.push_back({
        interpolate(mid_shoulder, mid_hip, params[kTrunkSegment].com_fraction_from_proximal),
        params[kTrunkSegment].mass_fraction,
    });

    const auto add_limb_segment = [&](std::size_t proximal_index,
                                      std::size_t distal_index,
                                      SegmentIndex parameter_index) {
        segments.push_back({
            interpolate(
                landmarks[proximal_index],
                landmarks[distal_index],
                params[parameter_index].com_fraction_from_proximal),
            params[parameter_index].mass_fraction,
        });
    };

    add_limb_segment(kLeftShoulder, kLeftElbow, kUpperArmSegment);
    add_limb_segment(kRightShoulder, kRightElbow, kUpperArmSegment);
    add_limb_segment(kLeftElbow, kLeftWrist, kForearmSegment);
    add_limb_segment(kRightElbow, kRightWrist, kForearmSegment);
    add_limb_segment(kLeftWrist, kLeftIndex, kHandSegment);
    add_limb_segment(kRightWrist, kRightIndex, kHandSegment);
    add_limb_segment(kLeftHip, kLeftKnee, kThighSegment);
    add_limb_segment(kRightHip, kRightKnee, kThighSegment);
    add_limb_segment(kLeftKnee, kLeftAnkle, kShankSegment);
    add_limb_segment(kRightKnee, kRightAnkle, kShankSegment);
    add_limb_segment(kLeftHeel, kLeftFootIndex, kFootSegment);
    add_limb_segment(kRightHeel, kRightFootIndex, kFootSegment);

    return weighted_average(segments);
}

Point2 project_to_plane(const Point3& point, ProjectionPlane plane) {
    if (plane == ProjectionPlane::kXZ) {
        return {point.x, point.z};
    }
    return {point.x, point.y};
}

std::vector<Point2> foot_support_points(const LandmarkArray& landmarks, ProjectionPlane plane) {
    std::vector<Point2> points;
    points.reserve(4);
    for (const std::size_t index : {kLeftHeel, kLeftFootIndex, kRightHeel, kRightFootIndex}) {
        const Point2 projected = project_to_plane(landmarks[index], plane);
        if (is_finite(projected)) {
            points.push_back(projected);
        }
    }
    return points;
}

std::vector<Point2> convex_hull(std::vector<Point2> points) {
    points.erase(
        std::remove_if(points.begin(), points.end(), [](const Point2& point) {
            return !is_finite(point);
        }),
        points.end());

    std::sort(points.begin(), points.end(), [](const Point2& lhs, const Point2& rhs) {
        if (std::abs(lhs.x - rhs.x) > kEpsilon) {
            return lhs.x < rhs.x;
        }
        return lhs.y < rhs.y;
    });

    points.erase(
        std::unique(points.begin(), points.end(), [](const Point2& lhs, const Point2& rhs) {
            return std::abs(lhs.x - rhs.x) <= kEpsilon && std::abs(lhs.y - rhs.y) <= kEpsilon;
        }),
        points.end());

    if (points.size() <= 1) {
        return points;
    }

    std::vector<Point2> hull;
    hull.reserve(points.size() * 2);

    for (const auto& point : points) {
        while (hull.size() >= 2 &&
               cross(hull[hull.size() - 2], hull[hull.size() - 1], point) <= kEpsilon) {
            hull.pop_back();
        }
        hull.push_back(point);
    }

    const std::size_t lower_size = hull.size();
    for (auto it = points.rbegin() + 1; it != points.rend(); ++it) {
        while (hull.size() > lower_size &&
               cross(hull[hull.size() - 2], hull[hull.size() - 1], *it) <= kEpsilon) {
            hull.pop_back();
        }
        hull.push_back(*it);
    }

    if (!hull.empty()) {
        hull.pop_back();
    }
    return hull;
}

bool point_in_convex_polygon(const Point2& point, const std::vector<Point2>& polygon) {
    if (polygon.size() < 3 || !is_finite(point)) {
        return false;
    }

    bool has_positive = false;
    bool has_negative = false;
    for (std::size_t i = 0; i < polygon.size(); ++i) {
        const Point2& a = polygon[i];
        const Point2& b = polygon[(i + 1) % polygon.size()];
        const double c = cross(a, b, point);
        if (c > kEpsilon) {
            has_positive = true;
        } else if (c < -kEpsilon) {
            has_negative = true;
        }
        if (has_positive && has_negative) {
            return false;
        }
    }
    return true;
}

double signed_distance_to_polygon(const Point2& point, const std::vector<Point2>& polygon) {
    if (polygon.empty() || !is_finite(point)) {
        return std::numeric_limits<double>::quiet_NaN();
    }

    double min_distance = std::numeric_limits<double>::infinity();
    if (polygon.size() == 1) {
        min_distance = distance(point, polygon.front());
        return min_distance <= kEpsilon ? 0.0 : -min_distance;
    }

    const std::size_t edge_count = polygon.size() == 2 ? 1 : polygon.size();
    for (std::size_t i = 0; i < edge_count; ++i) {
        const Point2& a = polygon[i];
        const Point2& b = polygon[(i + 1) % polygon.size()];
        min_distance = std::min(min_distance, distance_to_segment(point, a, b));
    }

    if (polygon.size() == 2) {
        return -min_distance;
    }

    return point_in_convex_polygon(point, polygon) ? min_distance : -min_distance;
}

ComResult evaluate_com_over_base_of_support(
    const LandmarkArray& landmarks,
    BodyProfile profile,
    ProjectionPlane plane) {
    ComResult result;
    result.center_of_mass = estimate_center_of_mass(landmarks, profile);
    result.projected_com = project_to_plane(result.center_of_mass, plane);

    const std::vector<Point2> support_points = foot_support_points(landmarks, plane);
    result.support_polygon = convex_hull(support_points);
    result.signed_distance_to_support = signed_distance_to_polygon(
        result.projected_com,
        result.support_polygon);
    result.inside_support = point_in_convex_polygon(result.projected_com, result.support_polygon);

    if (!is_finite(result.center_of_mass) || support_points.size() < 2) {
        result.status = SupportStatus::kInsufficientLandmarks;
        result.offset_direction = "unknown";
        return result;
    }

    if (result.support_polygon.size() < 3) {
        result.status = SupportStatus::kDegenerateSupport;
        result.offset_direction = offset_direction(result.projected_com, result.support_polygon);
        return result;
    }

    result.status = result.inside_support ? SupportStatus::kStable : SupportStatus::kOutsideSupport;
    result.offset_direction = result.inside_support ? "inside" : offset_direction(result.projected_com, result.support_polygon);
    return result;
}

LandmarkArray landmarks_from_float99(const float* values, std::size_t count) {
    LandmarkArray landmarks{};
    const std::size_t usable = std::min(count / 3, kPoseLandmarkCount);
    for (std::size_t i = 0; i < usable; ++i) {
        landmarks[i] = {
            static_cast<double>(values[i * 3]),
            static_cast<double>(values[i * 3 + 1]),
            static_cast<double>(values[i * 3 + 2]),
        };
    }
    for (std::size_t i = usable; i < kPoseLandmarkCount; ++i) {
        const double nan = std::numeric_limits<double>::quiet_NaN();
        landmarks[i] = {nan, nan, nan};
    }
    return landmarks;
}

}  // namespace gemmafit::kinematics
