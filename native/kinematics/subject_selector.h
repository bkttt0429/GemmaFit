#pragma once

#include <array>
#include <cstddef>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "com_tracker.h"

namespace gemmafit::kinematics {

// ── Multi-person subject selection ───────────────────────────────────
//
// Mirrors the Kotlin `resolveSubjectSelection` pipeline in
// app/src/main/kotlin/com/gemmafit/video/VideoAnalysisViewModel.kt so the
// three layers (Android Kotlin, native C++, Python prototype) agree on
// who the active subject is.
//
// Coordinate convention: landmarks are image-normalized, x ∈ [0,1] from
// left, y ∈ [0,1] from top. We re-use the project-wide Point3 with
// z = visibility (see confidence_gate.cpp), since the MediaPipe world-z
// coordinate is dropped at the bridge layer.

struct PoseBBox {
    double left   = 0.0;
    double top    = 0.0;
    double right  = 0.0;
    double bottom = 0.0;

    double width()  const { return std::max(0.0, right  - left); }
    double height() const { return std::max(0.0, bottom - top); }
    double area()   const { return width() * height(); }
    bool   contains(double x, double y) const {
        return x >= left && x <= right && y >= top && y <= bottom;
    }
};

struct PoseCandidate {
    LandmarkArray landmarks{};
    PoseBBox      bbox;
    double        center_x        = 0.0;
    double        center_y        = 0.0;
    double        avg_visibility  = 0.0;
    double        track_score     = 0.0;
    int           track_id        = -1;   // -1 = not yet assigned
};

enum class SubjectLockStatus {
    kNeedsSelection,
    kSingleAuto,
    kAutoLocked,
    kLocked,
    kSubjectLost,
};

struct SubjectSelection {
    int                       active_index = -1;     // index into the input candidates list
    int                       track_id     = -1;
    SubjectLockStatus         status       = SubjectLockStatus::kNeedsSelection;
    std::vector<std::string>  trust_flags;
    std::string               reason;
    bool                      has_candidate = false;
    PoseCandidate             candidate;             // copy of the chosen candidate
};

struct SubjectSelectorConfig {
    std::size_t max_candidates           = 4;
    int         subject_lost_frames      = 5;
    double      subject_min_visibility   = 0.35;
    double      subject_match_threshold  = 0.25;
    double      auto_lock_min_score      = 0.62;
    double      auto_lock_margin         = 0.12;
    int         auto_lock_stable_frames  = 2;
    // Visibility floor for per-keypoint distance contribution.
    double      keypoint_visibility_floor = 0.15;
};

// Stateful selector — instantiate once per session and call update() per frame.
class SubjectSelector {
public:
    explicit SubjectSelector(SubjectSelectorConfig cfg = {});

    // Build a candidate from raw [33 × Point3] landmarks. Returns std::nullopt
    // if the array is malformed (this matches the Kotlin `<33` guard).
    static std::optional<PoseCandidate> build_candidate(
        const LandmarkArray& landmarks,
        double keypoint_visibility_floor = 0.15);

    // Build N candidates from a flat float array of shape [n × 99].
    // Each block of 99 = 33 landmarks × (x, y, visibility).
    static std::vector<PoseCandidate> candidates_from_float(
        const float* values, std::size_t n_candidates,
        double keypoint_visibility_floor = 0.15);

    // Process a new frame. The returned selection.active_index, if ≥ 0, is
    // an index into the input vector. The selector keeps its own copy of
    // the locked subject so callers may discard `candidates` after this.
    SubjectSelection update(const std::vector<PoseCandidate>& candidates);

    // Defer a tap-driven lock to the next update() call. (x, y) are
    // image-normalized.
    void request_tap(double x, double y);

    // Drop manual lock; subsequent update()s revert to auto-selection.
    void clear_lock();

    bool                has_manual_lock() const { return manual_lock_; }
    int                 locked_track_id() const { return locked_track_id_; }
    const SubjectSelectorConfig& config()  const { return cfg_; }

private:
    SubjectSelectorConfig          cfg_;
    std::optional<PoseCandidate>   locked_subject_;
    int                            locked_track_id_         = -1;
    int                            next_track_id_           = 1;
    int                            lost_subject_frames_     = 0;
    std::optional<PoseCandidate>   pending_auto_subject_;
    int                            pending_auto_subject_frames_ = 0;
    bool                           manual_lock_             = false;
    std::optional<std::pair<double, double>> pending_tap_;

    // Returns (index into `candidates`, score). std::nullopt if no clear winner.
    std::optional<std::pair<int, double>>
        select_auto_candidate(const std::vector<PoseCandidate>& candidates) const;

    double score_subject_match(
        const PoseCandidate& previous, const PoseCandidate& candidate) const;

    double mean_keypoint_distance(
        const LandmarkArray& a, const LandmarkArray& b) const;

    int select_index_from_tap(
        const std::vector<PoseCandidate>& candidates,
        double x, double y) const;

    std::vector<std::string> trust_flags_for(
        const std::vector<PoseCandidate>& candidates,
        SubjectLockStatus status) const;

    void reset_pending_auto();
};

std::string to_json(const SubjectSelection& selection);

}  // namespace gemmafit::kinematics
