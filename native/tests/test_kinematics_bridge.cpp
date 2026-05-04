#include "../bridge/kinematics_bridge.h"

#include <array>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

using gemmafit::kinematics::LandmarkArray;
using gemmafit::kinematics::kPoseLandmarkCount;

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

LandmarkArray make_pose(double cx, double cy, double half = 0.10, double visibility = 0.9) {
    LandmarkArray lm{};
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        lm[i] = {cx, cy, visibility};
    }

    lm[0] = {cx, cy - half * 2.4, visibility};
    lm[7] = {cx - half * 0.4, cy - half * 2.2, visibility};
    lm[8] = {cx + half * 0.4, cy - half * 2.2, visibility};
    lm[11] = {cx - half * 0.55, cy - half * 1.5, visibility};
    lm[12] = {cx + half * 0.55, cy - half * 1.5, visibility};
    lm[13] = {cx - half * 0.70, cy - half * 0.5, visibility};
    lm[14] = {cx + half * 0.70, cy - half * 0.5, visibility};
    lm[15] = {cx - half * 0.80, cy + half * 0.3, visibility};
    lm[16] = {cx + half * 0.80, cy + half * 0.3, visibility};
    lm[23] = {cx - half * 0.35, cy + half * 0.2, visibility};
    lm[24] = {cx + half * 0.35, cy + half * 0.2, visibility};
    lm[25] = {cx - half * 0.35, cy + half * 1.2, visibility};
    lm[26] = {cx + half * 0.35, cy + half * 1.2, visibility};
    lm[27] = {cx - half * 0.35, cy + half * 2.2, visibility};
    lm[28] = {cx + half * 0.35, cy + half * 2.2, visibility};
    lm[29] = {cx - half * 0.35, cy + half * 2.3, visibility};
    lm[30] = {cx + half * 0.35, cy + half * 2.3, visibility};
    lm[31] = {cx - half * 0.35, cy + half * 2.4, visibility};
    lm[32] = {cx + half * 0.35, cy + half * 2.4, visibility};
    return lm;
}

std::array<float, 99> flatten(const LandmarkArray& lm) {
    std::array<float, 99> out{};
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        out[i * 3] = static_cast<float>(lm[i].x);
        out[i * 3 + 1] = static_cast<float>(lm[i].y);
        out[i * 3 + 2] = static_cast<float>(lm[i].z);
    }
    return out;
}

std::vector<float> flatten_candidates(const std::vector<LandmarkArray>& poses) {
    std::vector<float> out;
    out.reserve(poses.size() * 99);
    for (const auto& pose : poses) {
        const auto flat = flatten(pose);
        out.insert(out.end(), flat.begin(), flat.end());
    }
    return out;
}

bool contains(const std::string& text, const std::string& needle) {
    return text.find(needle) != std::string::npos;
}

}  // namespace

int main() {
    {
        const auto pose = flatten(make_pose(0.5, 0.45, 0.10));
        const auto out = gemmafit::bridge::run_biomechanics_pipeline(pose.data(), nullptr, 0.5);
        check("legacy single-person pipeline succeeds", out.success);
        check("legacy output contains motion_report", contains(out.combined_json, "\"motion_report\""));
    }

    {
        gemmafit::bridge::reset_subject_selector();
        const auto candidates = flatten_candidates({
            make_pose(0.50, 0.45, 0.14),
            make_pose(0.12, 0.12, 0.05),
        });

        const auto first = gemmafit::bridge::run_biomechanics_pipeline_candidates(
            candidates.data(), 2, 0.5, 0, false, -1.0, -1.0, false);
        const auto second = gemmafit::bridge::run_biomechanics_pipeline_candidates(
            candidates.data(), 2, 0.5, 33, false, -1.0, -1.0, false);

        check("first multi frame blocks while stabilizing", contains(first.combined_json, "\"gate\":\"blocked\""));
        check("second multi frame succeeds", second.success && !contains(second.combined_json, "\"gate\":\"blocked\""));
        check("success output includes subject_selection", contains(second.combined_json, "\"subject_selection\""));
        check("success output includes biomechanics report", contains(second.combined_json, "\"motion_report\""));
    }

    {
        gemmafit::bridge::reset_subject_selector();
        const auto candidates = flatten_candidates({
            make_pose(0.45, 0.45, 0.12),
            make_pose(0.55, 0.45, 0.12),
        });
        const auto out = gemmafit::bridge::run_biomechanics_pipeline_candidates(
            candidates.data(), 2, 0.5, 0, false, -1.0, -1.0, false);
        check("ambiguous multi frame blocks", contains(out.combined_json, "\"gate\":\"blocked\""));
        check("ambiguous block includes selection reason", contains(out.combined_json, "multi_person_selection_required"));
    }

    std::cout << "Result: " << passed << " PASS, " << failed << " FAIL\n";
    return failed == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
