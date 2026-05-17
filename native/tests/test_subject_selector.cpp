#include "../kinematics/subject_selector.h"

#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

using gemmafit::kinematics::LandmarkArray;
using gemmafit::kinematics::PoseCandidate;
using gemmafit::kinematics::SubjectLockStatus;
using gemmafit::kinematics::SubjectSelector;
using gemmafit::kinematics::SubjectSelectorConfig;
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

PoseCandidate synth_candidate(
    double cx,
    double cy,
    double half = 0.10,
    double visibility = 0.9) {

    LandmarkArray lm{};
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        const double dx = (static_cast<int>(i % 5) - 2) * half / 2.0;
        const double dy = (static_cast<int>((i / 5) % 5) - 2) * half / 2.0;
        lm[i] = {cx + dx, cy + dy, visibility};
    }

    lm[11] = {cx - half * 0.45, cy - half * 0.80, visibility};
    lm[12] = {cx + half * 0.45, cy - half * 0.80, visibility};
    lm[13] = {cx - half * 0.60, cy - half * 0.25, visibility};
    lm[14] = {cx + half * 0.60, cy - half * 0.25, visibility};
    lm[15] = {cx - half * 0.65, cy + half * 0.25, visibility};
    lm[16] = {cx + half * 0.65, cy + half * 0.25, visibility};
    lm[23] = {cx - half * 0.35, cy + half * 0.35, visibility};
    lm[24] = {cx + half * 0.35, cy + half * 0.35, visibility};
    lm[25] = {cx - half * 0.35, cy + half * 0.95, visibility};
    lm[26] = {cx + half * 0.35, cy + half * 0.95, visibility};
    lm[27] = {cx - half * 0.35, cy + half * 1.55, visibility};
    lm[28] = {cx + half * 0.35, cy + half * 1.55, visibility};

    return SubjectSelector::build_candidate(lm).value();
}

PoseCandidate shifted_motion(PoseCandidate candidate, double dx, double dy) {
    for (auto& p : candidate.landmarks) {
        p.x += dx;
        p.y += dy;
    }
    return SubjectSelector::build_candidate(candidate.landmarks).value();
}

LandmarkArray distributed_blank_landmarks() {
    LandmarkArray lm{};
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        lm[i] = {
            static_cast<double>(i % 6) / 5.0,
            static_cast<double>(i / 6) / 5.0,
            0.0,
        };
    }
    return lm;
}

PoseCandidate invalid_large_bbox_candidate() {
    PoseCandidate candidate;
    candidate.landmarks = distributed_blank_landmarks();
    candidate.bbox = {0.0, 0.0, 1.0, 1.0};
    candidate.center_x = 0.5;
    candidate.center_y = 0.5;
    candidate.avg_visibility = 1.0;
    return candidate;
}

}  // namespace

int main() {
    {
        SubjectSelector selector;
        const auto out = selector.update({synth_candidate(0.5, 0.55)});
        check("single candidate yields SINGLE_AUTO", out.status == SubjectLockStatus::kSingleAuto);
        check("single candidate assigns track_id", out.track_id > 0);
        check("single candidate active_index is 0", out.active_index == 0);
        check("single candidate selected", out.has_candidate);
    }

    {
        SubjectSelector selector;
        const auto out = selector.update({});
        check("empty frame yields SUBJECT_LOST", out.status == SubjectLockStatus::kSubjectLost);
        check("empty frame has no selected candidate", !out.has_candidate);
    }

    {
        SubjectSelector selector;
        const auto blank = distributed_blank_landmarks();
        check("distributed zero visibility is rejected", !SubjectSelector::build_candidate(blank).has_value());
    }

    {
        SubjectSelector selector;
        const auto out = selector.update({
            invalid_large_bbox_candidate(),
            synth_candidate(0.5, 0.55),
        });
        check("active_index maps back to source index", out.active_index == 1);
        check("filtered first candidate still selects visible source", out.has_candidate);
    }

    {
        SubjectSelector selector;
        std::vector<PoseCandidate> frame = {
            synth_candidate(0.50, 0.55, 0.20),
            synth_candidate(0.10, 0.10, 0.10),
        };
        const auto first = selector.update(frame);
        const auto second = selector.update(frame);
        check("first clear auto-pick stabilizes", first.status == SubjectLockStatus::kNeedsSelection);
        check("second clear auto-pick locks", second.status == SubjectLockStatus::kAutoLocked);
        check("clear auto-pick uses source index 0", second.active_index == 0);
    }

    {
        SubjectSelector selector;
        std::vector<PoseCandidate> frame = {
            synth_candidate(0.45, 0.55, 0.16),
            synth_candidate(0.55, 0.55, 0.16),
        };
        for (int i = 0; i < 4; ++i) {
            const auto out = selector.update(frame);
            check("ambiguous candidates require selection", out.status == SubjectLockStatus::kNeedsSelection);
        }
        check("ambiguous candidates do not assign lock", selector.locked_track_id() == -1);
    }

    {
        SubjectSelector selector;
        std::vector<PoseCandidate> frame = {
            synth_candidate(0.30, 0.50, 0.10),
            synth_candidate(0.70, 0.50, 0.10),
        };
        selector.request_tap(0.70, 0.50);
        const auto out = selector.update(frame);
        check("tap yields LOCKED", out.status == SubjectLockStatus::kLocked);
        check("tap selects tapped source index", out.active_index == 1);
        check("manual lock is tracked", selector.has_manual_lock());
    }

    {
        SubjectSelector selector;
        selector.request_tap(0.50, 0.55);
        selector.update({synth_candidate(0.50, 0.55, 0.14)});

        const auto out = selector.update({
            synth_candidate(0.49, 0.55, 0.14),
            synth_candidate(0.51, 0.55, 0.14),
        });
        check("locked ambiguous overlap holds instead of switching",
              out.status == SubjectLockStatus::kLocked && !out.has_candidate);
        check("locked ambiguous overlap reports occlusion",
              out.reason == "subject_temporarily_occluded");
    }

    {
        SubjectSelectorConfig cfg;
        cfg.auto_lock_stable_frames = 1;
        SubjectSelector selector(cfg);
        const auto left = synth_candidate(0.45, 0.55, 0.16);
        const auto right = synth_candidate(0.55, 0.55, 0.16);

        const auto first = selector.update({left, right});
        check("first symmetric frame is ambiguous", first.status == SubjectLockStatus::kNeedsSelection);

        const auto moved_right = shifted_motion(right, 0.05, 0.00);
        const auto second = selector.update({left, moved_right});
        check("motion energy can resolve moving subject", second.status == SubjectLockStatus::kAutoLocked);
        check("motion energy selects moving source index", second.active_index == 1);
        check("motion energy is reported", second.candidate.motion_energy > 0.1);
    }

    {
        SubjectSelector selector;
        const auto one = selector.update({synth_candidate(0.50, 0.55)});
        const int track_id = one.track_id;
        const auto two = selector.update({synth_candidate(0.52, 0.56)});
        check("locked subject keeps track id across drift", two.track_id == track_id);
    }

    {
        SubjectSelectorConfig cfg;
        cfg.subject_lost_frames = 3;
        SubjectSelector selector(cfg);
        selector.request_tap(0.50, 0.55);
        selector.update({synth_candidate(0.50, 0.55)});

        const auto miss1 = selector.update({});
        const auto miss2 = selector.update({});
        const auto lost = selector.update({});
        check("manual lock holds first miss", miss1.status == SubjectLockStatus::kLocked);
        check("manual lock holds second miss", miss2.status == SubjectLockStatus::kLocked);
        check("manual lock becomes lost on threshold", lost.status == SubjectLockStatus::kSubjectLost);
    }

    std::cout << "Result: " << passed << " PASS, " << failed << " FAIL\n";
    return failed == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}
