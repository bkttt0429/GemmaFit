#include "../kinematics/subject_selector.h"

#include <cmath>
#include <iostream>
#include <vector>

using gemmafit::kinematics::LandmarkArray;
using gemmafit::kinematics::Point3;
using gemmafit::kinematics::PoseCandidate;
using gemmafit::kinematics::SubjectLockStatus;
using gemmafit::kinematics::SubjectSelector;
using gemmafit::kinematics::SubjectSelectorConfig;
using gemmafit::kinematics::kPoseLandmarkCount;

namespace {

int passed = 0;
int failed = 0;

void check(const char* name, bool condition) {
    if (condition) { ++passed; std::cout << "[PASS] " << name << "\n"; }
    else           { ++failed; std::cout << "[FAIL] " << name << "\n"; }
}

// Synthesize a uniformly-visible pose centered at (cx, cy) with bbox half-size.
PoseCandidate synth_candidate(double cx, double cy, double half = 0.10,
                              double visibility = 0.9) {
    LandmarkArray lm{};
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        lm[i].x = cx + (((i % 5) - 2) * half / 4.0);
        lm[i].y = cy + ((((i / 5) % 5) - 2) * half / 4.0);
        lm[i].z = visibility;
    }
    auto built = SubjectSelector::build_candidate(lm);
    return built.value();
}

}  // namespace

int main() {
    // ── 1. Single visible candidate → SINGLE_AUTO with track_id assigned ──
    {
        SubjectSelector sel;
        const auto sub = sel.update({synth_candidate(0.5, 0.55)});
        check("single candidate yields SINGLE_AUTO",
              sub.status == SubjectLockStatus::kSingleAuto);
        check("single candidate assigns track_id",      sub.track_id > 0);
        check("single candidate has chosen candidate",  sub.has_candidate);
        check("single candidate active_index is 0",     sub.active_index == 0);
    }

    // ── 2. Empty input → SUBJECT_LOST (no prior lock) ─────────────────────
    {
        SubjectSelector sel;
        const auto sub = sel.update({});
        check("no candidates yields SUBJECT_LOST",
              sub.status == SubjectLockStatus::kSubjectLost);
    }

    // ── 3. Two candidates with clear winner → AUTO_LOCKED after stability ─
    {
        SubjectSelector sel;
        // Big subject near frame center, small subject in the corner.
        std::vector<PoseCandidate> frame = {
            synth_candidate(0.50, 0.55, 0.20),  // big & centered
            synth_candidate(0.10, 0.10, 0.05),  // small & off-center
        };
        const auto first = sel.update(frame);
        check("first frame of stable auto-pick is NEEDS_SELECTION (stabilizing)",
              first.status == SubjectLockStatus::kNeedsSelection);
        const auto second = sel.update(frame);
        check("second frame promotes to AUTO_LOCKED",
              second.status == SubjectLockStatus::kAutoLocked);
        check("auto-locked picks the central candidate (idx 0)",
              second.active_index == 0);
        check("auto-locked sets has_candidate", second.has_candidate);
    }

    // ── 4. Two candidates too close in score → NEEDS_SELECTION ────────────
    {
        SubjectSelector sel;
        std::vector<PoseCandidate> frame = {
            synth_candidate(0.45, 0.55, 0.18),
            synth_candidate(0.55, 0.55, 0.18),
        };
        // Hammer 5 frames; should never lock because margin is too small.
        for (int i = 0; i < 5; ++i) {
            const auto sub = sel.update(frame);
            if (sub.status == SubjectLockStatus::kAutoLocked) {
                check("ambiguous candidates never auto-lock", false);
                break;
            }
        }
        check("ambiguous candidates produce NEEDS_SELECTION",
              sel.has_manual_lock() == false &&
              sel.locked_track_id() == -1);
    }

    // ── 5. Tap-driven manual lock ─────────────────────────────────────────
    {
        SubjectSelector sel;
        std::vector<PoseCandidate> frame = {
            synth_candidate(0.30, 0.50, 0.10),
            synth_candidate(0.70, 0.50, 0.10),
        };
        sel.request_tap(0.70, 0.50);
        const auto sub = sel.update(frame);
        check("tap yields LOCKED",
              sub.status == SubjectLockStatus::kLocked);
        check("tap selects candidate at tap location (idx 1)",
              sub.active_index == 1);
        check("manual lock is set after tap", sel.has_manual_lock());
    }

    // ── 6. Lock persists with same track_id when subject moves slightly ───
    {
        SubjectSelector sel;
        sel.update({synth_candidate(0.50, 0.55)});
        const int trk = sel.locked_track_id();
        // Subject drifts a bit.
        const auto sub2 = sel.update({synth_candidate(0.52, 0.56)});
        check("re-matched subject keeps same track_id",
              sub2.track_id == trk && sub2.has_candidate);
    }

    // ── 7. Auto-lock subject disappears → drops without persisting ────────
    {
        SubjectSelector sel;
        sel.update({synth_candidate(0.50, 0.55)});  // SINGLE_AUTO sets locked
        // But locked from SINGLE_AUTO sets manual_lock_? No — only tap & promoted
        // auto-lock retain manual_lock=false; when subject disappears, auto lock
        // should release (per Kotlin lines 869-871).
        const auto sub = sel.update({});  // empty frame
        check("empty frame after auto lock yields SUBJECT_LOST",
              sub.status == SubjectLockStatus::kSubjectLost);
    }

    // ── 8. Manual lock survives N lost frames then turns into SUBJECT_LOST ─
    {
        SubjectSelectorConfig cfg;
        cfg.subject_lost_frames = 3;
        SubjectSelector sel(cfg);
        sel.request_tap(0.50, 0.55);
        sel.update({synth_candidate(0.50, 0.55)});
        check("manual lock active after tap", sel.has_manual_lock());

        // Frames 1, 2 → still LOCKED with subject_hold reason.
        for (int i = 0; i < 2; ++i) {
            const auto sub = sel.update({});
            check("manual lock holds during transient miss",
                  sub.status == SubjectLockStatus::kLocked);
        }
        // Frame 3 reaches subject_lost_frames → SUBJECT_LOST.
        const auto lost = sel.update({});
        check("manual lock surrenders after subject_lost_frames",
              lost.status == SubjectLockStatus::kSubjectLost);
    }

    std::cout << "Result: " << passed << " PASS, " << failed << " FAIL\n";
    return failed == 0 ? 0 : 1;
}
