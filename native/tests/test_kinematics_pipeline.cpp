#include "../kinematics/joint_angles.h"
#include "../kinematics/body_segments.h"
#include "../kinematics/symmetry.h"
#include "../kinematics/movement_classifier.h"
#include "../kinematics/muscle_focus.h"
#include "../kinematics/confidence_gate.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

using gemmafit::kinematics::LandmarkArray;
using gemmafit::kinematics::JointAngleSet;
using gemmafit::kinematics::Point3;

namespace {

int passes = 0;
int fails = 0;

void check(const char* name, bool condition) {
    if (condition) { ++passes; std::cout << "[PASS] " << name << "\n"; }
    else { ++fails; std::cout << "[FAIL] " << name << "\n"; }
}

LandmarkArray make_standing_pose() {
    LandmarkArray lm{};
    for (auto& p : lm) p = {0.0, 0.0, 0.9};  // all visible

    // Nose (0)
    lm[0]  = {0.5, 0.10, 0.9};
    // Ears (7, 8)
    lm[7]  = {0.45, 0.10, 0.9};
    lm[8]  = {0.55, 0.10, 0.9};
    // Shoulders (11, 12)
    lm[11] = {0.45, 0.25, 0.9};
    lm[12] = {0.55, 0.25, 0.9};
    // Elbows (13, 14)
    lm[13] = {0.42, 0.40, 0.9};
    lm[14] = {0.58, 0.40, 0.9};
    // Wrists (15, 16)
    lm[15] = {0.40, 0.55, 0.9};
    lm[16] = {0.60, 0.55, 0.9};
    // Hips (23, 24)
    lm[23] = {0.47, 0.50, 0.9};
    lm[24] = {0.53, 0.50, 0.9};
    // Knees (25, 26)
    lm[25] = {0.47, 0.70, 0.9};
    lm[26] = {0.53, 0.70, 0.9};
    // Ankles (27, 28)
    lm[27] = {0.47, 0.90, 0.9};
    lm[28] = {0.53, 0.90, 0.9};
    // Heels (29, 30)
    lm[29] = {0.47, 0.92, 0.9};
    lm[30] = {0.53, 0.92, 0.9};
    // Foot index (31, 32)
    lm[31] = {0.47, 0.95, 0.9};
    lm[32] = {0.53, 0.95, 0.9};
    return lm;
}

LandmarkArray make_squat_pose() {
    LandmarkArray lm = make_standing_pose();
    // Bend knees, lower hips
    lm[25] = {0.47, 0.65, 0.10};  // left knee bent forward
    lm[26] = {0.53, 0.65, 0.10};  // right knee bent
    lm[23] = {0.47, 0.45, -0.02}; // hips lower
    lm[24] = {0.53, 0.45, -0.02};
    lm[13] = {0.38, 0.40, 0.05};  // arms forward
    lm[14] = {0.62, 0.40, 0.05};
    lm[15] = {0.35, 0.50, 0.05};
    lm[16] = {0.65, 0.50, 0.05};
    return lm;
}

void test_joint_angles() {
    std::cout << "\n-- Joint Angles --\n";
    auto standing = make_standing_pose();
    auto angles = gemmafit::kinematics::compute_all_joint_angles(standing);

    check("12 joint angles computed", angles.angles.size() >= 10);

    double lk = gemmafit::kinematics::get_angle(angles, "left_knee");
    check("standing knee near 180", std::abs(lk - 180.0) < 30.0);

    double lh = gemmafit::kinematics::get_angle(angles, "left_hip");
    check("standing hip near 180", std::abs(lh - 180.0) < 30.0);

    auto squat_lm = make_squat_pose();
    auto squat_angles = gemmafit::kinematics::compute_all_joint_angles(squat_lm);
    double squat_knee = gemmafit::kinematics::get_angle(squat_angles, "left_knee");
    check("squat knee flexed (< standing knee)", squat_knee < lk - 5.0);
}

void test_confidence_gate() {
    std::cout << "\n-- Confidence Gate --\n";
    auto lm = make_standing_pose();
    auto conf = gemmafit::kinematics::evaluate_confidence(lm, 0.5);
    check("standing pose passes gate", conf.pass);

    auto bad = make_standing_pose();
    for (int i = 0; i < 33; i++) bad[i].z = 0.2;
    auto conf2 = gemmafit::kinematics::evaluate_confidence(bad, 0.5);
    check("low visibility fails gate", !conf2.pass);
}

void test_symmetry() {
    std::cout << "\n-- Symmetry --\n";
    auto lm = make_standing_pose();
    auto angles = gemmafit::kinematics::compute_all_joint_angles(lm);
    auto sym = gemmafit::kinematics::evaluate_symmetry(angles);
    check("standing symmetry score is high (> 0.9)", sym.score > 0.9);
}

void test_movement_classifier() {
    std::cout << "\n-- Movement Classifier --\n";
    auto lm = make_squat_pose();
    auto angles = gemmafit::kinematics::compute_all_joint_angles(lm);
    auto pattern = gemmafit::kinematics::classify_movement(lm, angles);
    check("pattern label not empty", !pattern.pattern_label.empty());
    check("symmetry score in [0,1]", pattern.symmetry_score >= 0.0 && pattern.symmetry_score <= 1.0);
}

void test_muscle_focus() {
    std::cout << "\n-- Muscle Focus --\n";
    auto lm = make_squat_pose();
    auto angles = gemmafit::kinematics::compute_all_joint_angles(lm);
    auto pattern = gemmafit::kinematics::classify_movement(lm, angles);
    auto focus = gemmafit::kinematics::estimate_muscle_focus(pattern);
    check("has primary muscles", focus.primary.size() > 0);
    check("has limitation note", !focus.limitation_note.empty());
}

void test_body_segments() {
    std::cout << "\n-- Body Segments --\n";
    auto lm = make_standing_pose();
    auto segments = gemmafit::kinematics::compute_all_segments(lm);
    check("segments computed", segments.segments.size() > 5);
}

}  // anonymous namespace

int main() {
    test_joint_angles();
    test_confidence_gate();
    test_symmetry();
    test_movement_classifier();
    test_muscle_focus();
    test_body_segments();

    std::cout << "\nResult: " << passes << " PASS, " << fails << " FAIL\n";
    return fails > 0 ? 1 : 0;
}