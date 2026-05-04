#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "com_tracker.h"

namespace gemmafit::kinematics {

struct PoseBBox {
    double left = 0.0;
    double top = 0.0;
    double right = 0.0;
    double bottom = 0.0;

    double width() const { return std::max(0.0, right - left); }
    double height() const { return std::max(0.0, bottom - top); }
    double area() const { return width() * height(); }
    bool contains(double x, double y) const {
        return x >= left && x <= right && y >= top && y <= bottom;
    }
};

struct PoseCandidate {
    LandmarkArray landmarks{};
    PoseBBox bbox;
    double center_x = 0.0;
    double center_y = 0.0;
    double avg_visibility = 0.0;
    double track_score = 0.0;
    double motion_energy = 0.0;
    int track_id = -1;
};

enum class SubjectLockStatus {
    kNeedsSelection,
    kSingleAuto,
    kAutoLocked,
    kLocked,
    kSubjectLost,
};

struct SubjectSelection {
    int active_index = -1;
    int track_id = -1;
    SubjectLockStatus status = SubjectLockStatus::kNeedsSelection;
    std::vector<std::string> trust_flags;
    std::string reason;
    bool has_candidate = false;
    PoseCandidate candidate;
    std::size_t candidate_count = 0;
};

struct SubjectSelectorConfig {
    std::size_t max_candidates = 4;
    int subject_lost_frames = 5;
    double subject_min_visibility = 0.35;
    double subject_match_threshold = 0.25;
    double auto_lock_min_score = 0.62;
    double auto_lock_margin = 0.12;
    int auto_lock_stable_frames = 2;
    double keypoint_visibility_floor = 0.15;
    double motion_energy_radius = 0.08;
};

class SubjectSelector {
public:
    explicit SubjectSelector(SubjectSelectorConfig cfg = {});

    static std::optional<PoseCandidate> build_candidate(
        const LandmarkArray& landmarks,
        double keypoint_visibility_floor = 0.15);

    static std::vector<PoseCandidate> candidates_from_float(
        const float* values,
        std::size_t n_candidates,
        double keypoint_visibility_floor = 0.15);

    SubjectSelection update(const std::vector<PoseCandidate>& candidates);

    void request_tap(double x, double y);
    void clear_lock();
    void reset();

    bool has_manual_lock() const { return manual_lock_; }
    int locked_track_id() const { return locked_track_id_; }
    const SubjectSelectorConfig& config() const { return cfg_; }

private:
    struct CandidateRef {
        PoseCandidate candidate;
        int source_index = -1;
    };

    SubjectSelectorConfig cfg_;
    std::optional<PoseCandidate> locked_subject_;
    int locked_track_id_ = -1;
    int next_track_id_ = 1;
    int lost_subject_frames_ = 0;
    std::optional<PoseCandidate> pending_auto_subject_;
    int pending_auto_subject_frames_ = 0;
    bool manual_lock_ = false;
    std::optional<std::pair<double, double>> pending_tap_;
    std::vector<PoseCandidate> previous_candidates_;

    std::vector<CandidateRef> visible_refs(
        const std::vector<PoseCandidate>& candidates) const;
    void annotate_motion_energy(std::vector<CandidateRef>& refs) const;

    std::optional<std::pair<int, double>>
        select_auto_candidate(const std::vector<CandidateRef>& refs) const;

    double score_subject_match(
        const PoseCandidate& previous,
        const PoseCandidate& candidate) const;

    double mean_keypoint_distance(
        const LandmarkArray& a,
        const LandmarkArray& b) const;

    double motion_distance(
        const LandmarkArray& previous,
        const LandmarkArray& current) const;

    int select_ref_from_tap(
        const std::vector<CandidateRef>& refs,
        double x,
        double y) const;

    std::vector<std::string> trust_flags_for(
        std::size_t candidate_count,
        SubjectLockStatus status) const;

    SubjectSelection make_selection(
        const CandidateRef& ref,
        SubjectLockStatus status,
        int track_id,
        std::size_t candidate_count,
        const std::string& reason = "") const;

    void remember_previous(const std::vector<CandidateRef>& refs);
    void reset_pending_auto();
};

std::string to_json(const SubjectSelection& selection);
const char* status_name(SubjectLockStatus status);

}  // namespace gemmafit::kinematics
