#include "../kinematics/com_tracker.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <numeric>
#include <vector>

using gemmafit::kinematics::BodyProfile;
using gemmafit::kinematics::ComResult;
using gemmafit::kinematics::LandmarkArray;
using gemmafit::kinematics::Point2;
using gemmafit::kinematics::Point3;
using gemmafit::kinematics::ProjectionPlane;
using gemmafit::kinematics::SupportStatus;

namespace {

int passed = 0;
int failed = 0;

void check(const char* name, bool condition) {
    if (condition) {
        ++passed;
        std::cout << "[PASS] " << name << "\n";
    } else {
        ++failed;
        std::cout << "[FAIL] " << name << "\n";
    }
}

bool near(double lhs, double rhs, double tolerance = 1e-6) {
    return std::abs(lhs - rhs) <= tolerance;
}

LandmarkArray make_balanced_pose() {
    LandmarkArray lm{};
    for (auto& point : lm) {
        point = {0.0, 0.0, 0.0};
    }

    lm[0] = {0.0, 1.72, 0.02};       // nose
    lm[7] = {-0.08, 1.72, 0.0};      // left ear
    lm[8] = {0.08, 1.72, 0.0};       // right ear
    lm[11] = {-0.22, 1.45, 0.0};     // left shoulder
    lm[12] = {0.22, 1.45, 0.0};      // right shoulder
    lm[13] = {-0.45, 1.08, 0.02};    // left elbow
    lm[14] = {0.45, 1.08, 0.02};     // right elbow
    lm[15] = {-0.40, 0.75, 0.02};    // left wrist
    lm[16] = {0.40, 0.75, 0.02};     // right wrist
    lm[19] = {-0.39, 0.62, 0.04};    // left index
    lm[20] = {0.39, 0.62, 0.04};     // right index
    lm[23] = {-0.16, 0.95, 0.0};     // left hip
    lm[24] = {0.16, 0.95, 0.0};      // right hip
    lm[25] = {-0.14, 0.52, 0.02};    // left knee
    lm[26] = {0.14, 0.52, 0.02};     // right knee
    lm[27] = {-0.13, 0.08, 0.0};     // left ankle
    lm[28] = {0.13, 0.08, 0.0};      // right ankle
    lm[29] = {-0.13, 0.0, -0.14};    // left heel
    lm[30] = {0.13, 0.0, -0.14};     // right heel
    lm[31] = {-0.13, 0.0, 0.20};     // left foot index
    lm[32] = {0.13, 0.0, 0.20};      // right foot index
    return lm;
}

void shift_body_without_feet(LandmarkArray& lm, double dx) {
    for (std::size_t i = 0; i < lm.size(); ++i) {
        if (i == 29 || i == 30 || i == 31 || i == 32) {
            continue;
        }
        lm[i].x += dx;
    }
}

}  // namespace

int main() {
    {
        for (const auto profile : {BodyProfile::kFemale, BodyProfile::kMale, BodyProfile::kNeutral}) {
            const auto& params = gemmafit::kinematics::de_leva_parameters(profile);
            const double total =
                params[0].mass_fraction +
                params[1].mass_fraction +
                2.0 * (params[2].mass_fraction + params[3].mass_fraction +
                       params[4].mass_fraction + params[5].mass_fraction +
                       params[6].mass_fraction + params[7].mass_fraction);
            check("de Leva segment masses sum to whole body", near(total, 1.0, 1e-4));
        }
    }

    {
        std::vector<Point2> points = {
            {1.0, 1.0}, {0.0, 0.0}, {1.0, 0.0}, {0.5, 0.5}, {0.0, 1.0},
        };
        const auto hull = gemmafit::kinematics::convex_hull(points);
        check("convex hull removes interior point", hull.size() == 4);
        check("point inside convex hull", gemmafit::kinematics::point_in_convex_polygon({0.5, 0.5}, hull));
        check("point outside convex hull", !gemmafit::kinematics::point_in_convex_polygon({1.5, 0.5}, hull));
        check("inside signed distance is positive", gemmafit::kinematics::signed_distance_to_polygon({0.5, 0.5}, hull) > 0.0);
        check("outside signed distance is negative", gemmafit::kinematics::signed_distance_to_polygon({1.5, 0.5}, hull) < 0.0);
    }

    {
        const LandmarkArray lm = make_balanced_pose();
        const Point3 com = gemmafit::kinematics::estimate_center_of_mass(lm);
        check("estimated COM is finite", std::isfinite(com.x) && std::isfinite(com.y) && std::isfinite(com.z));
        check("symmetric pose COM x near midline", near(com.x, 0.0, 0.02));

        const ComResult result = gemmafit::kinematics::evaluate_com_over_base_of_support(
            lm,
            BodyProfile::kNeutral,
            ProjectionPlane::kXZ);
        check("balanced pose has support polygon", result.support_polygon.size() == 4);
        check("balanced pose COM inside BoS", result.status == SupportStatus::kStable && result.inside_support);
        check("balanced pose signed margin positive", result.signed_distance_to_support > 0.0);
    }

    {
        LandmarkArray lm = make_balanced_pose();
        shift_body_without_feet(lm, 0.45);
        const ComResult result = gemmafit::kinematics::evaluate_com_over_base_of_support(
            lm,
            BodyProfile::kNeutral,
            ProjectionPlane::kXZ);
        check("shifted pose COM outside BoS", result.status == SupportStatus::kOutsideSupport && !result.inside_support);
        check("shifted pose signed margin negative", result.signed_distance_to_support < 0.0);
        check("shifted pose direction is right", result.offset_direction == "right");
    }

    std::cout << "Result: " << passed << " PASS, " << failed << " FAIL\n";
    return failed == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
