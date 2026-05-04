#include "subject_selector.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <numeric>
#include <sstream>

namespace gemmafit::kinematics {

namespace {

constexpr std::array<int, 8> kMatchKeypoints = {
    11, 12, 23, 24, 25, 26, 27, 28,
};

constexpr std::array<int, 12> kMotionKeypoints = {
    11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28,
};

constexpr std::array<int, 4> kTorsoKeypoints = {11, 12, 23, 24};
constexpr double kFrameCenterX = 0.50;
constexpr double kFrameCenterY = 0.55;
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

std::string json_escape(const std::string& value) {
    std::ostringstream ss;
    for (char c : value) {
        switch (c) {
            case '\\': ss << "\\\\"; break;
            case '"': ss << "\\\""; break;
            case '\n': ss << "\\n"; break;
            case '\r': ss << "\\r"; break;
            case '\t': ss << "\\t"; break;
            default: ss << c; break;
        }
    }
    return ss.str();
}

bool valid_point(const Point3& p) {
    return std::isfinite(p.x) && std::isfinite(p.y);
}

}  // namespace

const char* status_name(SubjectLockStatus s) {
    switch (s) {
        case SubjectLockStatus::kLocked: return "LOCKED";
        case SubjectLockStatus::kAutoLocked: return "AUTO_LOCKED";
        case SubjectLockStatus::kSingleAuto: return "SINGLE_AUTO";
        case SubjectLockStatus::kSubjectLost: return "SUBJECT_LOST";
        case SubjectLockStatus::kNeedsSelection: return "NEEDS_SELECTION";
    }
    return "NEEDS_SELECTION";
}

SubjectSelector::SubjectSelector(SubjectSelectorConfig cfg) : cfg_(cfg) {}

std::optional<PoseCandidate> SubjectSelector::build_candidate(
    const LandmarkArray& landmarks,
    double keypoint_visibility_floor) {

    std::vector<int> visible_indices;
    visible_indices.reserve(kPoseLandmarkCount);
    for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
        if (valid_point(landmarks[i]) && landmarks[i].z > keypoint_visibility_floor) {
            visible_indices.push_back(static_cast<int>(i));
        }
    }

    std::vector<int> pick_indices = visible_indices;
    if (pick_indices.empty()) {
        for (std::size_t i = 0; i < kPoseLandmarkCount; ++i) {
            if (valid_point(landmarks[i])) pick_indices.push_back(static_cast<int>(i));
        }
    }
    if (pick_indices.empty()) return std::nullopt;

    double min_x = 1.0;
    double min_y = 1.0;
    double max_x = 0.0;
    double max_y = 0.0;
    for (int idx : pick_indices) {
        const auto& p = landmarks[static_cast<std::size_t>(idx)];
        min_x = std::min(min_x, clamp01(p.x));
        min_y = std::min(min_y, clamp01(p.y));
        max_x = std::max(max_x, clamp01(p.x));
        max_y = std::max(max_y, clamp01(p.y));
    }

    PoseCandidate candidate;
    candidate.landmarks = landmarks;
    candidate.bbox = PoseBBox{min_x, min_y, max_x, max_y};

    double tx_sum = 0.0;
    double ty_sum = 0.0;
    int torso_count = 0;
    for (int idx : kTorsoKeypoints) {
        const auto& p = landmarks[static_cast<std::size_t>(idx)];
        if (valid_point(p) && p.z > keypoint_visibility_floor) {
            tx_sum += p.x;
            ty_sum += p.y;
            torso_count++;
        }
    }
    if (torso_count > 0) {
        candidate.center_x = clamp01(tx_sum / torso_count);
        candidate.center_y = clamp01(ty_sum / torso_count);
    } else {
        candidate.center_x = clamp01((candidate.bbox.left + candidate.bbox.right) * 0.5);
        candidate.center_y = clamp01((candidate.bbox.top + candidate.bbox.bottom) * 0.5);
    }

    double visibility_sum = 0.0;
    for (const auto& p : landmarks) {
        visibility_sum += std::isfinite(p.z) ? p.z : 0.0;
    }
    candidate.avg_visibility = visibility_sum / static_cast<double>(kPoseLandmarkCount);
    return candidate;
}

std::vector<PoseCandidate> SubjectSelector::candidates_from_float(
    const float* values,
    std::size_t n_candidates,
    double keypoint_visibility_floor) {

    std::vector<PoseCandidate> out;
    if (values == nullptr) return out;
    out.reserve(n_candidates);
    for (std::size_t i = 0; i < n_candidates; ++i) {
        LandmarkArray lm = landmarks_from_float99(values + i * 99, 99);
        auto candidate = build_candidate(lm, keypoint_visibility_floor);
        if (candidate) out.push_back(*candidate);
    }
    return out;
}

void SubjectSelector::request_tap(double x, double y) {
    pending_tap_ = std::make_pair(clamp01(x), clamp01(y));
}

void SubjectSelector::clear_lock() {
    locked_subject_.reset();
    locked_track_id_ = -1;
    lost_subject_frames_ = 0;
    manual_lock_ = false;
    pending_tap_.reset();
    previous_candidates_.clear();
    reset_pending_auto();
}

void SubjectSelector::reset() {
    clear_lock();
    next_track_id_ = 1;
}

void SubjectSelector::reset_pending_auto() {
    pending_auto_subject_.reset();
    pending_auto_subject_frames_ = 0;
}

std::vector<SubjectSelector::CandidateRef> SubjectSelector::visible_refs(
    const std::vector<PoseCandidate>& candidates) const {

    std::vector<CandidateRef> refs;
    const std::size_t take = std::min(candidates.size(), cfg_.max_candidates);
    refs.reserve(take);
    for (std::size_t i = 0; i < take; ++i) {
        const auto& c = candidates[i];
        if (c.avg_visibility >= cfg_.subject_min_visibility ||
            c.bbox.area() > 0.01) {
            refs.push_back(CandidateRef{c, static_cast<int>(i)});
        }
    }
    return refs;
}

void SubjectSelector::annotate_motion_energy(std::vector<CandidateRef>& refs) const {
    if (previous_candidates_.empty()) {
        for (auto& ref : refs) ref.candidate.motion_energy = 0.0;
        return;
    }

    for (auto& ref : refs) {
        double best_distance = std::numeric_limits<double>::infinity();
        for (const auto& previous : previous_candidates_) {
            best_distance = std::min(
                best_distance,
                motion_distance(previous.landmarks, ref.candidate.landmarks));
        }
        if (!std::isfinite(best_distance)) best_distance = 0.0;
        ref.candidate.motion_energy = clamp01(best_distance / cfg_.motion_energy_radius);
    }
}

double SubjectSelector::mean_keypoint_distance(
    const LandmarkArray& a,
    const LandmarkArray& b) const {

    double sum = 0.0;
    int count = 0;
    for (int idx : kMatchKeypoints) {
        const auto& la = a[static_cast<std::size_t>(idx)];
        const auto& lb = b[static_cast<std::size_t>(idx)];
        if (valid_point(la) && valid_point(lb) &&
            la.z > cfg_.keypoint_visibility_floor &&
            lb.z > cfg_.keypoint_visibility_floor) {
            sum += euclid(la.x, la.y, lb.x, lb.y);
            count++;
        }
    }
    return count == 0 ? 1.0 : sum / count;
}

double SubjectSelector::motion_distance(
    const LandmarkArray& previous,
    const LandmarkArray& current) const {

    double sum = 0.0;
    int count = 0;
    for (int idx : kMotionKeypoints) {
        const auto& a = previous[static_cast<std::size_t>(idx)];
        const auto& b = current[static_cast<std::size_t>(idx)];
        if (valid_point(a) && valid_point(b) &&
            a.z > cfg_.keypoint_visibility_floor &&
            b.z > cfg_.keypoint_visibility_floor) {
            sum += euclid(a.x, a.y, b.x, b.y);
            count++;
        }
    }
    return count == 0 ? 0.0 : sum / count;
}

double SubjectSelector::score_subject_match(
    const PoseCandidate& previous,
    const PoseCandidate& candidate) const {

    const double center_dist = euclid(
        previous.center_x, previous.center_y,
        candidate.center_x, candidate.center_y);
    const double center_score = clamp01(1.0 - center_dist / kMatchCenterRadius);
    const double area_base = std::max({previous.bbox.area(), candidate.bbox.area(), 0.001});
    const double area_score = clamp01(
        1.0 - std::abs(previous.bbox.area() - candidate.bbox.area()) / area_base);
    const double keypoint_score = clamp01(
        1.0 - mean_keypoint_distance(previous.landmarks, candidate.landmarks) /
              kKeypointMatchRadius);
    const double visibility_score = clamp01(candidate.avg_visibility);
    return 0.42 * center_score + 0.38 * keypoint_score +
           0.12 * area_score + 0.08 * visibility_score;
}

std::optional<std::pair<int, double>> SubjectSelector::select_auto_candidate(
    const std::vector<CandidateRef>& refs) const {

    if (refs.size() < 2) return std::nullopt;

    double max_area = 0.001;
    for (const auto& ref : refs) {
        max_area = std::max(max_area, ref.candidate.bbox.area());
    }

    const bool has_motion_evidence = std::any_of(
        refs.begin(), refs.end(),
        [](const CandidateRef& ref) { return ref.candidate.motion_energy > 0.01; });

    std::vector<std::pair<int, double>> scored;
    scored.reserve(refs.size());
    for (int i = 0; i < static_cast<int>(refs.size()); ++i) {
        const auto& c = refs[static_cast<std::size_t>(i)].candidate;
        const double area_score = clamp01(c.bbox.area() / max_area);
        const double center_dist = euclid(c.center_x, c.center_y, kFrameCenterX, kFrameCenterY);
        const double center_score = clamp01(1.0 - center_dist / kAutoCenterRadius);
        const double visibility_score = clamp01(c.avg_visibility);
        const double motion_score = clamp01(c.motion_energy);

        const double score = has_motion_evidence
            ? 0.30 * area_score + 0.20 * center_score +
              0.15 * visibility_score + 0.35 * motion_score
            : 0.45 * area_score + 0.35 * center_score + 0.20 * visibility_score;
        scored.emplace_back(i, score);
    }

    std::sort(
        scored.begin(),
        scored.end(),
        [](const auto& a, const auto& b) { return a.second > b.second; });

    const auto best = scored.front();
    const double second = scored.size() >= 2 ? scored[1].second : 0.0;
    if (best.second >= cfg_.auto_lock_min_score &&
        best.second - second >= cfg_.auto_lock_margin) {
        return best;
    }
    return std::nullopt;
}

int SubjectSelector::select_ref_from_tap(
    const std::vector<CandidateRef>& refs,
    double x,
    double y) const {

    int best_ref = -1;
    double best_distance = std::numeric_limits<double>::infinity();
    bool any_containing = false;
    for (const auto& ref : refs) {
        if (ref.candidate.bbox.contains(x, y)) any_containing = true;
    }

    for (int i = 0; i < static_cast<int>(refs.size()); ++i) {
        const auto& c = refs[static_cast<std::size_t>(i)].candidate;
        if (any_containing && !c.bbox.contains(x, y)) continue;
        const double dx = c.center_x - x;
        const double dy = c.center_y - y;
        const double d2 = dx * dx + dy * dy;
        if (d2 < best_distance) {
            best_distance = d2;
            best_ref = i;
        }
    }
    return best_ref;
}

std::vector<std::string> SubjectSelector::trust_flags_for(
    std::size_t candidate_count,
    SubjectLockStatus status) const {

    std::vector<std::string> flags;
    if (candidate_count > 1) flags.emplace_back("other_people_detected");
    switch (status) {
        case SubjectLockStatus::kLocked:
            flags.emplace_back("subject_locked");
            break;
        case SubjectLockStatus::kAutoLocked:
            flags.emplace_back("subject_auto_locked");
            break;
        case SubjectLockStatus::kSingleAuto:
            flags.emplace_back("single_subject_auto");
            break;
        case SubjectLockStatus::kSubjectLost:
            flags.emplace_back("SUBJECT_LOST");
            break;
        case SubjectLockStatus::kNeedsSelection:
            flags.emplace_back("NEEDS_SELECTION");
            break;
    }

    std::vector<std::string> out;
    for (const auto& flag : flags) {
        if (std::find(out.begin(), out.end(), flag) == out.end()) out.push_back(flag);
    }
    return out;
}

SubjectSelection SubjectSelector::make_selection(
    const CandidateRef& ref,
    SubjectLockStatus status,
    int track_id,
    std::size_t candidate_count,
    const std::string& reason) const {

    SubjectSelection selection;
    selection.active_index = ref.source_index;
    selection.track_id = track_id;
    selection.status = status;
    selection.trust_flags = trust_flags_for(candidate_count, status);
    selection.reason = reason;
    selection.has_candidate = true;
    selection.candidate = ref.candidate;
    selection.candidate.track_id = track_id;
    selection.candidate_count = candidate_count;
    return selection;
}

void SubjectSelector::remember_previous(const std::vector<CandidateRef>& refs) {
    previous_candidates_.clear();
    previous_candidates_.reserve(refs.size());
    for (const auto& ref : refs) {
        previous_candidates_.push_back(ref.candidate);
    }
}

SubjectSelection SubjectSelector::update(const std::vector<PoseCandidate>& candidates) {
    std::vector<CandidateRef> refs = visible_refs(candidates);
    annotate_motion_energy(refs);

    auto finish = [this, &refs](SubjectSelection selection) {
        remember_previous(refs);
        return selection;
    };

    SubjectSelection selection;
    selection.candidate_count = candidates.size();

    if (refs.empty()) {
        reset_pending_auto();
        lost_subject_frames_++;
        if (manual_lock_ && locked_subject_ &&
            lost_subject_frames_ < cfg_.subject_lost_frames) {
            selection.status = SubjectLockStatus::kLocked;
            selection.track_id = locked_track_id_;
            selection.reason = "subject_temporarily_unmatched";
            selection.trust_flags = {"subject_locked", "subject_hold"};
            return finish(selection);
        }
        selection.status = SubjectLockStatus::kSubjectLost;
        selection.track_id = locked_track_id_;
        selection.reason = locked_subject_ ? "locked_subject_lost" : "no_subject_detected";
        selection.trust_flags = {"SUBJECT_LOST"};
        return finish(selection);
    }

    if (pending_tap_) {
        const auto [tap_x, tap_y] = *pending_tap_;
        pending_tap_.reset();
        const int ref_index = select_ref_from_tap(refs, tap_x, tap_y);
        if (ref_index >= 0) {
            manual_lock_ = true;
            const int track_id = next_track_id_++;
            locked_track_id_ = track_id;
            PoseCandidate locked = refs[static_cast<std::size_t>(ref_index)].candidate;
            locked.track_id = track_id;
            locked_subject_ = locked;
            reset_pending_auto();
            lost_subject_frames_ = 0;
            return finish(make_selection(
                refs[static_cast<std::size_t>(ref_index)],
                SubjectLockStatus::kLocked,
                track_id,
                candidates.size()));
        }
    }

    if (locked_subject_) {
        int best_ref = -1;
        double best_score = -1.0;
        for (int i = 0; i < static_cast<int>(refs.size()); ++i) {
            const double score = score_subject_match(
                *locked_subject_,
                refs[static_cast<std::size_t>(i)].candidate);
            if (score > best_score) {
                best_score = score;
                best_ref = i;
            }
        }

        if (best_ref >= 0 && best_score >= cfg_.subject_match_threshold) {
            const int track_id = locked_track_id_ >= 0 ? locked_track_id_ : next_track_id_++;
            locked_track_id_ = track_id;
            PoseCandidate locked = refs[static_cast<std::size_t>(best_ref)].candidate;
            locked.track_id = track_id;
            locked.track_score = best_score;
            locked_subject_ = locked;
            reset_pending_auto();
            lost_subject_frames_ = 0;

            CandidateRef ref = refs[static_cast<std::size_t>(best_ref)];
            ref.candidate.track_score = best_score;
            return finish(make_selection(
                ref,
                manual_lock_ ? SubjectLockStatus::kLocked : SubjectLockStatus::kAutoLocked,
                track_id,
                candidates.size()));
        }

        if (!manual_lock_) {
            locked_subject_.reset();
            locked_track_id_ = -1;
        } else {
            lost_subject_frames_++;
            if (lost_subject_frames_ < cfg_.subject_lost_frames) {
                selection.status = SubjectLockStatus::kLocked;
                selection.track_id = locked_track_id_;
                selection.reason = "subject_temporarily_unmatched";
                selection.trust_flags = {"subject_locked", "subject_hold"};
                return finish(selection);
            }
            selection.status = SubjectLockStatus::kSubjectLost;
            selection.track_id = locked_track_id_;
            selection.reason = "locked_subject_lost";
            selection.trust_flags = {"SUBJECT_LOST"};
            return finish(selection);
        }
    }

    if (refs.size() == 1) {
        const int track_id = locked_track_id_ >= 0 ? locked_track_id_ : next_track_id_++;
        locked_track_id_ = track_id;
        PoseCandidate locked = refs.front().candidate;
        locked.track_id = track_id;
        locked_subject_ = locked;
        reset_pending_auto();
        lost_subject_frames_ = 0;
        return finish(make_selection(
            refs.front(),
            SubjectLockStatus::kSingleAuto,
            track_id,
            candidates.size()));
    }

    const auto auto_pick = select_auto_candidate(refs);
    if (auto_pick) {
        const int ref_index = auto_pick->first;
        CandidateRef chosen_ref = refs[static_cast<std::size_t>(ref_index)];
        chosen_ref.candidate.track_score = auto_pick->second;

        const bool stable = pending_auto_subject_ &&
            score_subject_match(*pending_auto_subject_, chosen_ref.candidate) >=
                cfg_.subject_match_threshold;
        pending_auto_subject_frames_ = stable ? pending_auto_subject_frames_ + 1 : 1;
        pending_auto_subject_ = chosen_ref.candidate;

        if (pending_auto_subject_frames_ < cfg_.auto_lock_stable_frames) {
            selection.status = SubjectLockStatus::kNeedsSelection;
            selection.trust_flags = {"MULTI_PERSON", "AUTO_SELECTION_PENDING"};
            selection.reason = "auto_subject_stabilizing";
            return finish(selection);
        }

        const int track_id = next_track_id_++;
        locked_track_id_ = track_id;
        PoseCandidate locked = chosen_ref.candidate;
        locked.track_id = track_id;
        locked_subject_ = locked;
        manual_lock_ = false;
        reset_pending_auto();
        lost_subject_frames_ = 0;
        return finish(make_selection(
            chosen_ref,
            SubjectLockStatus::kAutoLocked,
            track_id,
            candidates.size()));
    }

    selection.status = SubjectLockStatus::kNeedsSelection;
    selection.trust_flags = {"MULTI_PERSON", "NEEDS_SELECTION"};
    selection.reason = "multi_person_selection_required";
    return finish(selection);
}

std::string to_json(const SubjectSelection& selection) {
    std::ostringstream ss;
    ss << "{"
       << "\"active_index\":" << selection.active_index
       << ",\"track_id\":" << selection.track_id
       << ",\"status\":\"" << status_name(selection.status) << "\""
       << ",\"reason\":\"" << json_escape(selection.reason) << "\""
       << ",\"has_candidate\":" << (selection.has_candidate ? "true" : "false")
       << ",\"candidate_count\":" << selection.candidate_count
       << ",\"score\":" << (selection.has_candidate ? selection.candidate.track_score : 0.0)
       << ",\"motion_energy\":" << (selection.has_candidate ? selection.candidate.motion_energy : 0.0)
       << ",\"trust_flags\":[";

    for (std::size_t i = 0; i < selection.trust_flags.size(); ++i) {
        if (i > 0) ss << ",";
        ss << "\"" << json_escape(selection.trust_flags[i]) << "\"";
    }
    ss << "]";

    if (selection.has_candidate) {
        ss << ",\"candidate\":{"
           << "\"center\":[" << selection.candidate.center_x << ","
                              << selection.candidate.center_y << "]"
           << ",\"bbox\":[" << selection.candidate.bbox.left << ","
                            << selection.candidate.bbox.top << ","
                            << selection.candidate.bbox.right << ","
                            << selection.candidate.bbox.bottom << "]"
           << ",\"area\":" << selection.candidate.bbox.area()
           << ",\"avg_visibility\":" << selection.candidate.avg_visibility
           << ",\"track_score\":" << selection.candidate.track_score
           << ",\"motion_energy\":" << selection.candidate.motion_energy
           << "}";
    }

    ss << "}";
    return ss.str();
}

}  // namespace gemmafit::kinematics
