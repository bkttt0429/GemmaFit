#include "subject_selector.h"

#include <algorithm>
#include <cmath>
#include <numeric>
#include <sstream>

namespace gemmafit::kinematics {

namespace {

constexpr std::array<int, 8> kMatchKeypoints = {
    11, 12, 23, 24, 25, 26, 27, 28,
};
constexpr std::array<int, 4> kTorsoKeypoints = {11, 12, 23, 24};

// Reference center for auto-selection scoring. Matches Kotlin (0.5, 0.55).
constexpr double kFrameCenterX = 0.50;
constexpr double kFrameCenterY = 0.55;

// Score normalization radii, matching Kotlin.
constexpr double kAutoCenterRadius = 0.75;
constexpr double kMatchCenterRadius = 0.45;
constexpr double kKeypointMatchRadius = 0.35;

double clamp01(double v) {
    if (v < 0.0) return 0.0;
    if (v > 1.0) return 1.0;
    return v;
}

double euclid(double ax, double ay, double bx, double by) {
    const double dx = ax - bx;
    const double dy = ay - by;
    return std::sqrt(dx * dx + dy * dy);
}

const char* status_name(SubjectLockStatus s) {
    switch (s) {
        case SubjectLockStatus::kLocked:        return "LOCKED";
        case SubjectLockStatus::kAutoLocked:    return "AUTO_LOCKED";
        case SubjectLockStatus::kSingleAuto:    return "SINGLE_AUTO";
        case SubjectLockStatus::kSubjectLost:   return "SUBJECT_LOST";
        case SubjectLockStatus::kNeedsSelection:return "NEEDS_SELECTION";
    }
    return "NEEDS_SELECTION";
}

}  // namespace

// ── SubjectSelector ──────────────────────────────────────────────────

SubjectSelector::SubjectSelector(SubjectSelectorConfig cfg) : cfg_(cfg) {}

std::optional<PoseCandidate> SubjectSelector::build_candidate(
    const LandmarkArray& landmarks, double keypoint_visibility_floor) {

    // Mirror Kotlin: bbox is built from "visible" landmarks if any exist,
    // otherwise from all landmarks. Visibility floor matches Kotlin (0.15).
    std::vector<int> visible_indices;
    visible_indices.reserve(kPoseLandmarkCount);
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        if (landmarks[i].z > keypoint_visibility_floor) {
            visible_indices.push_back(static_cast<int>(i));
        }
    }
    const auto& src = visible_indices;
    auto pick_indices = src.empty()
        ? [] {
            std::vector<int> all(kPoseLandmarkCount);
            std::iota(all.begin(), all.end(), 0);
            return all;
        }()
        : src;

    double min_x = 1.0, min_y = 1.0, max_x = 0.0, max_y = 0.0;
    for (int idx : pick_indices) {
        const auto& p = landmarks[idx];
        min_x = std::min(min_x, clamp01(p.x));
        min_y = std::min(min_y, clamp01(p.y));
        max_x = std::max(max_x, clamp01(p.x));
        max_y = std::max(max_y, clamp01(p.y));
    }

    PoseCandidate c;
    c.landmarks = landmarks;
    c.bbox = PoseBBox{min_x, min_y, max_x, max_y};

    // Center: average of torso landmarks above the visibility floor; fall
    // back to bbox center.
    double tx_sum = 0.0, ty_sum = 0.0;
    int    tcount = 0;
    for (int idx : kTorsoKeypoints) {
        const auto& p = landmarks[static_cast<std::size_t>(idx)];
        if (p.z > keypoint_visibility_floor) {
            tx_sum += p.x;
            ty_sum += p.y;
            tcount++;
        }
    }
    if (tcount > 0) {
        c.center_x = clamp01(tx_sum / tcount);
        c.center_y = clamp01(ty_sum / tcount);
    } else {
        c.center_x = clamp01((c.bbox.left + c.bbox.right) * 0.5);
        c.center_y = clamp01((c.bbox.top  + c.bbox.bottom) * 0.5);
    }

    // Mean visibility uses ALL 33 landmarks (matches Kotlin).
    double vis_sum = 0.0;
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        vis_sum += landmarks[i].z;
    }
    c.avg_visibility = vis_sum / static_cast<double>(kPoseLandmarkCount);
    c.track_score = 0.0;
    c.track_id    = -1;
    return c;
}

std::vector<PoseCandidate> SubjectSelector::candidates_from_float(
    const float* values, std::size_t n_candidates,
    double keypoint_visibility_floor) {

    std::vector<PoseCandidate> out;
    out.reserve(n_candidates);
    for (std::size_t k = 0; k < n_candidates; ++k) {
        LandmarkArray lm = landmarks_from_float99(
            values + k * 99, 99);
        auto built = build_candidate(lm, keypoint_visibility_floor);
        if (built) out.push_back(*built);
    }
    return out;
}

void SubjectSelector::request_tap(double x, double y) {
    pending_tap_ = std::make_pair(x, y);
}

void SubjectSelector::clear_lock() {
    locked_subject_.reset();
    locked_track_id_ = -1;
    manual_lock_ = false;
    lost_subject_frames_ = 0;
    reset_pending_auto();
}

void SubjectSelector::reset_pending_auto() {
    pending_auto_subject_.reset();
    pending_auto_subject_frames_ = 0;
}

double SubjectSelector::mean_keypoint_distance(
    const LandmarkArray& a, const LandmarkArray& b) const {
    double sum = 0.0;
    int    n   = 0;
    for (int idx : kMatchKeypoints) {
        const auto& la = a[static_cast<std::size_t>(idx)];
        const auto& lb = b[static_cast<std::size_t>(idx)];
        if (la.z > cfg_.keypoint_visibility_floor &&
            lb.z > cfg_.keypoint_visibility_floor) {
            sum += euclid(la.x, la.y, lb.x, lb.y);
            n++;
        }
    }
    if (n == 0) return 1.0;
    return sum / n;
}

double SubjectSelector::score_subject_match(
    const PoseCandidate& prev, const PoseCandidate& cur) const {
    const double center_dist = euclid(prev.center_x, prev.center_y,
                                      cur.center_x,  cur.center_y);
    const double center_score =
        clamp01(1.0 - center_dist / kMatchCenterRadius);
    const double area_base = std::max({prev.bbox.area(), cur.bbox.area(), 0.001});
    const double area_score =
        clamp01(1.0 - std::abs(prev.bbox.area() - cur.bbox.area()) / area_base);
    const double keypoint_score =
        clamp01(1.0 - mean_keypoint_distance(prev.landmarks, cur.landmarks)
                / kKeypointMatchRadius);
    const double visibility_score = clamp01(cur.avg_visibility);
    return 0.42 * center_score + 0.38 * keypoint_score
         + 0.12 * area_score   + 0.08 * visibility_score;
}

std::optional<std::pair<int, double>>
SubjectSelector::select_auto_candidate(
    const std::vector<PoseCandidate>& candidates) const {

    if (candidates.size() < 2) return std::nullopt;
    double max_area = 0.001;
    for (const auto& c : candidates) max_area = std::max(max_area, c.bbox.area());

    std::vector<std::pair<int, double>> scored;
    scored.reserve(candidates.size());
    for (int i = 0; i < static_cast<int>(candidates.size()); ++i) {
        const auto& c            = candidates[i];
        const double area_score   = clamp01(c.bbox.area() / max_area);
        const double center_dist  = euclid(c.center_x, c.center_y,
                                           kFrameCenterX, kFrameCenterY);
        const double center_score = clamp01(1.0 - center_dist / kAutoCenterRadius);
        const double vis_score    = clamp01(c.avg_visibility);
        const double s = 0.45 * area_score + 0.35 * center_score + 0.20 * vis_score;
        scored.emplace_back(i, s);
    }
    std::sort(scored.begin(), scored.end(),
        [](const auto& a, const auto& b) { return a.second > b.second; });

    const auto& best    = scored.front();
    const double second = scored.size() >= 2 ? scored[1].second : 0.0;
    if (best.second >= cfg_.auto_lock_min_score &&
        best.second - second >= cfg_.auto_lock_margin) {
        return best;
    }
    return std::nullopt;
}

int SubjectSelector::select_index_from_tap(
    const std::vector<PoseCandidate>& candidates, double x, double y) const {

    int best_idx = -1;
    double best_d2 = std::numeric_limits<double>::infinity();
    bool   any_containing = false;

    // Prefer candidates whose bbox contains the tap.
    for (int i = 0; i < static_cast<int>(candidates.size()); ++i) {
        if (candidates[i].bbox.contains(x, y)) any_containing = true;
    }
    for (int i = 0; i < static_cast<int>(candidates.size()); ++i) {
        const auto& c = candidates[i];
        if (any_containing && !c.bbox.contains(x, y)) continue;
        const double dx = c.center_x - x;
        const double dy = c.center_y - y;
        const double d2 = dx * dx + dy * dy;
        if (d2 < best_d2) {
            best_d2 = d2;
            best_idx = i;
        }
    }
    return best_idx;
}

std::vector<std::string> SubjectSelector::trust_flags_for(
    const std::vector<PoseCandidate>& candidates,
    SubjectLockStatus status) const {
    std::vector<std::string> flags;
    if (candidates.size() > 1) flags.emplace_back("other_people_detected");
    switch (status) {
        case SubjectLockStatus::kLocked:        flags.emplace_back("subject_locked"); break;
        case SubjectLockStatus::kAutoLocked:    flags.emplace_back("subject_auto_locked"); break;
        case SubjectLockStatus::kSingleAuto:    flags.emplace_back("single_subject_auto"); break;
        case SubjectLockStatus::kSubjectLost:   flags.emplace_back("SUBJECT_LOST"); break;
        case SubjectLockStatus::kNeedsSelection:flags.emplace_back("NEEDS_SELECTION"); break;
    }
    // Deduplicate while preserving order.
    std::vector<std::string> out;
    for (auto& s : flags) {
        if (std::find(out.begin(), out.end(), s) == out.end()) out.push_back(s);
    }
    return out;
}

SubjectSelection SubjectSelector::update(
    const std::vector<PoseCandidate>& candidates_in) {

    // 1. Take up to MAX and filter by visibility / non-empty bbox. Track the
    // mapping from visible-index back to source-index so the chosen
    // candidate can be reported with its position in the input.
    std::vector<PoseCandidate> visible;
    std::vector<int>           visible_to_source;
    const std::size_t take = std::min(candidates_in.size(), cfg_.max_candidates);
    visible.reserve(take);
    visible_to_source.reserve(take);
    for (std::size_t i = 0; i < take; ++i) {
        const auto& c = candidates_in[i];
        if (c.avg_visibility >= cfg_.subject_min_visibility ||
            c.bbox.area() > 0.01) {
            visible.push_back(c);
            visible_to_source.push_back(static_cast<int>(i));
        }
    }

    SubjectSelection sel;

    // 2. No visible candidates.
    if (visible.empty()) {
        reset_pending_auto();
        lost_subject_frames_++;
        if (manual_lock_ && locked_subject_ &&
            lost_subject_frames_ < cfg_.subject_lost_frames) {
            sel.status      = SubjectLockStatus::kLocked;
            sel.track_id    = locked_track_id_;
            sel.trust_flags = {"subject_locked", "subject_hold"};
            sel.reason      = "subject_temporarily_unmatched";
            return sel;
        }
        sel.status      = SubjectLockStatus::kSubjectLost;
        sel.track_id    = locked_track_id_;
        sel.trust_flags = {"SUBJECT_LOST"};
        sel.reason      = "locked_subject_lost";
        return sel;
    }

    // 3. Pending tap → manual lock.
    if (pending_tap_) {
        const auto [tx, ty] = *pending_tap_;
        pending_tap_.reset();
        const int chosen_idx = select_index_from_tap(visible, tx, ty);
        if (chosen_idx >= 0) {
            PoseCandidate chosen = visible[chosen_idx];
            manual_lock_     = true;
            const int trk_id = next_track_id_++;
            locked_track_id_ = trk_id;
            chosen.track_id  = trk_id;
            locked_subject_  = chosen;
            reset_pending_auto();
            lost_subject_frames_ = 0;

            sel.has_candidate = true;
            sel.candidate     = chosen;
            sel.active_index  = find_source_index(chosen);
            sel.track_id      = trk_id;
            sel.status        = SubjectLockStatus::kLocked;
            sel.trust_flags   = trust_flags_for(candidates_in, sel.status);
            return sel;
        }
    }

    // 4. Already locked: try to re-match.
    if (locked_subject_) {
        const PoseCandidate prev = *locked_subject_;
        PoseCandidate best_cand;
        double best_score = -1.0;
        for (const auto& c : visible) {
            const double s = score_subject_match(prev, c);
            if (s > best_score) { best_score = s; best_cand = c; }
        }
        if (best_score >= cfg_.subject_match_threshold) {
            const SubjectLockStatus status = manual_lock_
                ? SubjectLockStatus::kLocked
                : SubjectLockStatus::kAutoLocked;
            const int trk_id = locked_track_id_ >= 0
                ? locked_track_id_ : next_track_id_++;
            locked_track_id_ = trk_id;
            best_cand.track_id    = trk_id;
            best_cand.track_score = best_score;
            locked_subject_       = best_cand;
            reset_pending_auto();
            lost_subject_frames_  = 0;

            sel.has_candidate = true;
            sel.candidate     = best_cand;
            sel.active_index  = find_source_index(best_cand);
            sel.track_id      = trk_id;
            sel.status        = status;
            sel.trust_flags   = trust_flags_for(candidates_in, status);
            return sel;
        }
        if (!manual_lock_) {
            // Auto-lock lost the subject — fall through to re-select.
            locked_subject_.reset();
            locked_track_id_ = -1;
        } else {
            lost_subject_frames_++;
            if (lost_subject_frames_ < cfg_.subject_lost_frames) {
                sel.track_id    = locked_track_id_;
                sel.status      = SubjectLockStatus::kLocked;
                sel.trust_flags = {"subject_locked", "subject_hold"};
                sel.reason      = "subject_temporarily_unmatched";
                return sel;
            }
            sel.track_id    = locked_track_id_;
            sel.status      = SubjectLockStatus::kSubjectLost;
            sel.trust_flags = {"SUBJECT_LOST"};
            sel.reason      = "locked_subject_lost";
            return sel;
        }
    }

    // 5. Single visible → auto-pick.
    if (visible.size() == 1) {
        PoseCandidate chosen = visible.front();
        const int trk_id = locked_track_id_ >= 0
            ? locked_track_id_ : next_track_id_++;
        locked_track_id_ = trk_id;
        chosen.track_id  = trk_id;
        locked_subject_  = chosen;
        reset_pending_auto();
        lost_subject_frames_ = 0;

        sel.has_candidate = true;
        sel.candidate     = chosen;
        sel.active_index  = find_source_index(chosen);
        sel.track_id      = trk_id;
        sel.status        = SubjectLockStatus::kSingleAuto;
        sel.trust_flags   = trust_flags_for(candidates_in, sel.status);
        return sel;
    }

    // 6. Multiple visible → try auto-select with stability.
    const auto auto_pick = select_auto_candidate(visible);
    if (auto_pick) {
        PoseCandidate chosen = auto_pick->first;
        chosen.track_score = auto_pick->second;

        const bool stable = pending_auto_subject_ &&
            score_subject_match(*pending_auto_subject_, chosen)
                >= cfg_.subject_match_threshold;
        pending_auto_subject_frames_ = stable ? pending_auto_subject_frames_ + 1 : 1;
        pending_auto_subject_ = chosen;

        if (pending_auto_subject_frames_ < cfg_.auto_lock_stable_frames) {
            sel.status      = SubjectLockStatus::kNeedsSelection;
            sel.trust_flags = {"MULTI_PERSON", "AUTO_SELECTION_PENDING"};
            sel.reason      = "auto_subject_stabilizing";
            return sel;
        }

        const int trk_id = next_track_id_++;
        locked_track_id_ = trk_id;
        chosen.track_id  = trk_id;
        locked_subject_  = chosen;
        manual_lock_     = false;
        reset_pending_auto();
        lost_subject_frames_ = 0;

        sel.has_candidate = true;
        sel.candidate     = chosen;
        sel.active_index  = find_source_index(chosen);
        sel.track_id      = trk_id;
        sel.status        = SubjectLockStatus::kAutoLocked;
        sel.trust_flags   = trust_flags_for(candidates_in, sel.status);
        return sel;
    }

    // 7. Ambiguous → require explicit selection.
    sel.status      = SubjectLockStatus::kNeedsSelection;
    sel.trust_flags = {"MULTI_PERSON", "NEEDS_SELECTION"};
    sel.reason      = "multi_person_selection_required";
    return sel;
}

std::string to_json(const SubjectSelection& sel) {
    std::ostringstream o;
    o << "{"
      << "\"active_index\":" << sel.active_index
      << ",\"track_id\":"     << sel.track_id
      << ",\"status\":\""     << status_name(sel.status) << "\""
      << ",\"reason\":\""     << sel.reason << "\""
      << ",\"has_candidate\":" << (sel.has_candidate ? "true" : "false")
      << ",\"trust_flags\":[";
    for (std::size_t i = 0; i < sel.trust_flags.size(); ++i) {
        if (i) o << ",";
        o << "\"" << sel.trust_flags[i] << "\"";
    }
    o << "]";
    if (sel.has_candidate) {
        o << ",\"candidate\":{"
          << "\"center\":[" << sel.candidate.center_x << ","
                            << sel.candidate.center_y << "]"
          << ",\"bbox\":["  << sel.candidate.bbox.left << ","
                            << sel.candidate.bbox.top << ","
                            << sel.candidate.bbox.right << ","
                            << sel.candidate.bbox.bottom << "]"
          << ",\"area\":"   << sel.candidate.bbox.area()
          << ",\"avg_visibility\":" << sel.candidate.avg_visibility
          << ",\"track_score\":"    << sel.candidate.track_score
          << "}";
    }
    o << "}";
    return o.str();
}

}  // namespace gemmafit::kinematics
