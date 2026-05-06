"""
subject_selector.py — Python port of native/kinematics/subject_selector.cpp.

Behavior is bit-for-bit equivalent to the C++ reference and the Kotlin
`resolveSubjectSelection` in app/.../VideoAnalysisViewModel.kt:
  - same auto-pick weights (0.45·area + 0.35·center + 0.20·visibility)
  - same persistence weights (0.42·center + 0.38·keypoint + 0.12·area + 0.08·visibility)
  - same visibility floor (0.15) for keypoint distance and torso center
  - same thresholds (auto_lock_min_score=0.62, auto_lock_margin=0.12,
    subject_match_threshold=0.25, subject_lost_frames=5)
  - same closed status set: SINGLE_AUTO / AUTO_LOCKED / LOCKED /
    NEEDS_SELECTION / SUBJECT_LOST

The Python version is the iteration sandbox — Kalman filter, motion-energy
weighting, and other selector experiments live here first, then port back
to C++ once tuned.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from enum import Enum
from typing import List, Optional, Tuple

import numpy as np


# Indices into the 33-landmark MediaPipe array (matches Kotlin/C++).
_MATCH_KEYPOINTS: Tuple[int, ...] = (11, 12, 23, 24, 25, 26, 27, 28)
_TORSO_KEYPOINTS: Tuple[int, ...] = (11, 12, 23, 24)
_UPPER_BODY_KEYPOINTS: Tuple[int, ...] = (11, 12, 13, 14, 15, 16, 23, 24)
_PRESENCE_MIN_AVG_VISIBILITY = 0.18
_PRESENCE_VISIBILITY_FLOOR = 0.25
_PRESENCE_MIN_VISIBLE_KEYPOINTS = 8
_PRESENCE_MIN_BBOX_AREA = 0.01

# Matches kFrameCenterX / kFrameCenterY / kAutoCenterRadius / kMatchCenterRadius
# / kKeypointMatchRadius in subject_selector.cpp.
_FRAME_CENTER_X = 0.50
_FRAME_CENTER_Y = 0.55
_AUTO_CENTER_RADIUS = 0.75
_MATCH_CENTER_RADIUS = 0.45
_KEYPOINT_MATCH_RADIUS = 0.35
_IDENTITY_MOTION_WEIGHT = 0.45
_IDENTITY_KEYPOINT_WEIGHT = 0.25
_IDENTITY_APPEARANCE_WEIGHT = 0.25
_IDENTITY_AREA_VIS_WEIGHT = 0.05
_IDENTITY_AMBIGUITY_MARGIN = 0.08
_IDENTITY_OVERLAP_IOU = 0.20
_IDENTITY_CENTER_CLOSE = 0.08
_IDENTITY_APPEARANCE_ACCEPT = 0.42
_IDENTITY_APPEARANCE_STRONG = 0.55
_IDENTITY_DUPLICATE_IOU = 0.35
_IDENTITY_DUPLICATE_CENTER_CLOSE = 0.04
_IDENTITY_REACQUIRE_ACCEPT = 0.56
_IDENTITY_REACQUIRE_MARGIN = 0.10


class SubjectLockStatus(str, Enum):
    NEEDS_SELECTION = "NEEDS_SELECTION"
    SINGLE_AUTO     = "SINGLE_AUTO"
    AUTO_LOCKED     = "AUTO_LOCKED"
    LOCKED          = "LOCKED"
    SUBJECT_LOST    = "SUBJECT_LOST"


@dataclass
class PoseBBox:
    left: float = 0.0
    top: float = 0.0
    right: float = 0.0
    bottom: float = 0.0

    @property
    def width(self) -> float:  return max(0.0, self.right - self.left)
    @property
    def height(self) -> float: return max(0.0, self.bottom - self.top)
    @property
    def area(self) -> float:   return self.width * self.height

    def contains(self, x: float, y: float) -> bool:
        return self.left <= x <= self.right and self.top <= y <= self.bottom


@dataclass
class SubjectAppearanceSignature:
    histogram: np.ndarray = field(default_factory=lambda: np.zeros(0, dtype=np.float32))

    @property
    def is_valid(self) -> bool:
        return self.histogram.size > 0 and float(np.sum(self.histogram)) > 0.0

    def normalized(self) -> np.ndarray:
        if self.histogram.size == 0:
            return self.histogram.astype(np.float32)
        hist = np.nan_to_num(self.histogram.astype(np.float32), nan=0.0, posinf=0.0, neginf=0.0)
        hist = np.maximum(hist, 0.0)
        total = float(hist.sum())
        if total <= 0.0:
            return np.zeros_like(hist, dtype=np.float32)
        return hist / total

    def similarity(self, other: Optional["SubjectAppearanceSignature"]) -> float:
        if other is None or not self.is_valid or not other.is_valid:
            return 0.5
        a = self.normalized()
        b = other.normalized()
        if a.shape != b.shape:
            return 0.5
        return _clamp01(float(np.minimum(a, b).sum()))

    def blend(
        self,
        other: Optional["SubjectAppearanceSignature"],
        alpha: float = 0.12,
    ) -> "SubjectAppearanceSignature":
        if other is None or not other.is_valid:
            return self
        if not self.is_valid:
            return other
        a = self.normalized()
        b = other.normalized()
        if a.shape != b.shape:
            return self
        alpha = _clamp01(alpha)
        return SubjectAppearanceSignature(((1.0 - alpha) * a + alpha * b).astype(np.float32))


@dataclass
class PoseCandidate:
    landmarks: np.ndarray            # (33, 3) — (x, y, visibility)
    bbox: PoseBBox = field(default_factory=PoseBBox)
    center_x: float = 0.0
    center_y: float = 0.0
    avg_visibility: float = 0.0
    track_score: float = 0.0
    track_id: int = -1
    appearance: Optional[SubjectAppearanceSignature] = None
    appearance_score: float = 0.5
    match_margin: float = 0.0
    identity_score: float = 0.0


@dataclass
class SubjectSelectorConfig:
    max_candidates: int = 4
    subject_lost_frames: int = 5
    subject_min_visibility: float = 0.35
    subject_match_threshold: float = 0.25
    auto_lock_min_score: float = 0.62
    auto_lock_margin: float = 0.12
    auto_lock_stable_frames: int = 2
    keypoint_visibility_floor: float = 0.15
    # Kalman re-match (Algorithm A from the audit). When enabled, the
    # locked-subject re-match path predicts the next (cx, cy, area) from
    # a constant-velocity model and gates by Mahalanobis distance instead
    # of the heuristic 0.42·center + 0.38·keypoint + 0.12·area + 0.08·vis
    # score. Heuristic auto-pick (cold start) is unaffected.
    kalman_enabled: bool = False
    kalman_sigma_pos: float = 0.02       # image-normalized; ~one body width
    kalman_sigma_area: float = 0.005
    kalman_sigma_vel: float = 0.5        # /second
    kalman_sigma_area_vel: float = 0.05  # /second
    kalman_chi2_gate_3df: float = 7.815  # 95% χ² gate, 3 dof
    kalman_default_dt_s: float = 0.125   # 8 fps fallback when timestamps missing


@dataclass
class SubjectSelection:
    active_index: int = -1
    track_id: int = -1
    status: SubjectLockStatus = SubjectLockStatus.NEEDS_SELECTION
    trust_flags: List[str] = field(default_factory=list)
    reason: str = ""
    has_candidate: bool = False
    candidate: Optional[PoseCandidate] = None


@dataclass
class _IdentityMatch:
    index: int = -1
    score: float = -1.0
    hold: bool = False
    reason: str = ""


def _clamp01(v: float) -> float:
    return 0.0 if v < 0.0 else (1.0 if v > 1.0 else v)


def _euclid(ax: float, ay: float, bx: float, by: float) -> float:
    return math.hypot(ax - bx, ay - by)


def _bbox_iou(a: PoseBBox, b: PoseBBox) -> float:
    left = max(a.left, b.left)
    top = max(a.top, b.top)
    right = min(a.right, b.right)
    bottom = min(a.bottom, b.bottom)
    inter = max(0.0, right - left) * max(0.0, bottom - top)
    union = a.area + b.area - inter
    if union <= 0.0:
        return 0.0
    return _clamp01(inter / union)


def _candidate_passes_presence_gate(landmarks: np.ndarray) -> bool:
    if landmarks.shape != (33, 3):
        return False
    vis = np.nan_to_num(landmarks[:, 2], nan=0.0, posinf=0.0, neginf=0.0)
    vis = np.clip(vis, 0.0, 1.0)
    xy_finite = np.isfinite(landmarks[:, 0]) & np.isfinite(landmarks[:, 1])
    high_mask = (vis >= _PRESENCE_VISIBILITY_FLOOR) & xy_finite
    if float(vis.mean()) < _PRESENCE_MIN_AVG_VISIBILITY:
        return False
    if int(high_mask.sum()) < _PRESENCE_MIN_VISIBLE_KEYPOINTS:
        return False
    xs = np.clip(landmarks[high_mask, 0], 0.0, 1.0)
    ys = np.clip(landmarks[high_mask, 1], 0.0, 1.0)
    bbox_area = float((xs.max() - xs.min()) * (ys.max() - ys.min()))
    if bbox_area < _PRESENCE_MIN_BBOX_AREA:
        return False
    torso_visible = sum(bool(high_mask[i]) for i in _TORSO_KEYPOINTS)
    upper_visible = sum(bool(high_mask[i]) for i in _UPPER_BODY_KEYPOINTS)
    return torso_visible >= 2 or upper_visible >= 4


# ── Kalman re-match track ────────────────────────────────────────────
#
# Constant-velocity 6-state Kalman filter for the LOCKED subject.
# State: x = [cx, cy, area, vx, vy, va]
# Measurement: z = [cx, cy, area] (3 dof)
# Process noise Q assumes white-noise acceleration (continuous-discrete).

class _KalmanTrack:
    def __init__(self, cfg: "SubjectSelectorConfig", initial: PoseCandidate):
        self.cfg = cfg
        self.x = np.array([
            initial.center_x, initial.center_y, initial.bbox.area,
            0.0, 0.0, 0.0,
        ], dtype=np.float64)
        # Initial covariance: position uncertain ~ sigma_pos², velocity wide.
        sp2 = cfg.kalman_sigma_pos ** 2
        sa2 = cfg.kalman_sigma_area ** 2
        sv2 = cfg.kalman_sigma_vel ** 2
        sav2 = cfg.kalman_sigma_area_vel ** 2
        self.P = np.diag([sp2, sp2, sa2, sv2, sv2, sav2])

    def _F(self, dt: float) -> np.ndarray:
        F = np.eye(6, dtype=np.float64)
        F[0, 3] = dt
        F[1, 4] = dt
        F[2, 5] = dt
        return F

    def _Q(self, dt: float) -> np.ndarray:
        sp2  = self.cfg.kalman_sigma_pos ** 2
        sa2  = self.cfg.kalman_sigma_area ** 2
        sv2  = self.cfg.kalman_sigma_vel ** 2
        sav2 = self.cfg.kalman_sigma_area_vel ** 2
        # Discrete white-noise accel block per (pos, vel) pair.
        # Q_block(σ²) = σ² · [[dt³/3, dt²/2], [dt²/2, dt]]
        def block(sigma2_pos: float, sigma2_vel: float) -> Tuple[float, float, float, float]:
            # Use the larger of the two as accel intensity proxy.
            q = max(sigma2_pos, sigma2_vel)
            return (q * dt ** 3 / 3.0, q * dt ** 2 / 2.0,
                    q * dt ** 2 / 2.0, q * dt)

        Q = np.zeros((6, 6), dtype=np.float64)
        for i, (p2, v2) in enumerate(((sp2, sv2), (sp2, sv2), (sa2, sav2))):
            a, b, c, d = block(p2, v2)
            Q[i, i]         = a
            Q[i, i + 3]     = b
            Q[i + 3, i]     = c
            Q[i + 3, i + 3] = d
        return Q

    def predict(self, dt: float) -> None:
        F = self._F(dt)
        self.x = F @ self.x
        self.P = F @ self.P @ F.T + self._Q(dt)

    @staticmethod
    def _H() -> np.ndarray:
        H = np.zeros((3, 6), dtype=np.float64)
        H[0, 0] = 1.0
        H[1, 1] = 1.0
        H[2, 2] = 1.0
        return H

    def _measurement_R(self, candidate: PoseCandidate) -> np.ndarray:
        # Visibility-weighted: low visibility → trust prediction more.
        vis = max(candidate.avg_visibility, 0.10)
        scale = 1.0 / vis
        sp2 = (self.cfg.kalman_sigma_pos ** 2) * scale
        sa2 = (self.cfg.kalman_sigma_area ** 2) * scale
        return np.diag([sp2, sp2, sa2])

    def mahalanobis_d2(self, candidate: PoseCandidate) -> float:
        """χ² statistic for candidate; ≥ chi2_gate_3df → reject."""
        z = np.array([candidate.center_x, candidate.center_y, candidate.bbox.area])
        H = self._H()
        innovation = z - H @ self.x
        S = H @ self.P @ H.T + self._measurement_R(candidate)
        try:
            S_inv = np.linalg.inv(S)
        except np.linalg.LinAlgError:
            return float("inf")
        return float(innovation @ S_inv @ innovation)

    def update(self, candidate: PoseCandidate) -> None:
        z = np.array([candidate.center_x, candidate.center_y, candidate.bbox.area])
        H = self._H()
        R = self._measurement_R(candidate)
        S = H @ self.P @ H.T + R
        K = self.P @ H.T @ np.linalg.inv(S)
        innovation = z - H @ self.x
        self.x = self.x + K @ innovation
        self.P = (np.eye(6) - K @ H) @ self.P


def build_candidate(
    landmarks: np.ndarray,
    keypoint_visibility_floor: float = 0.15,
) -> Optional[PoseCandidate]:
    """Build a PoseCandidate from a (33, 3) landmark array. Mirrors C++ build_candidate."""
    if landmarks.shape != (33, 3):
        return None

    clean_landmarks = landmarks.copy()
    vis = np.nan_to_num(clean_landmarks[:, 2], nan=0.0, posinf=0.0, neginf=0.0)
    vis = np.clip(vis, 0.0, 1.0)
    clean_landmarks[:, 2] = vis
    xy_finite = np.isfinite(clean_landmarks[:, 0]) & np.isfinite(clean_landmarks[:, 1])
    visible_mask = (vis >= _PRESENCE_VISIBILITY_FLOOR) & xy_finite

    if float(vis.mean()) < _PRESENCE_MIN_AVG_VISIBILITY:
        return None
    if int(visible_mask.sum()) < _PRESENCE_MIN_VISIBLE_KEYPOINTS:
        return None

    xs = np.clip(clean_landmarks[visible_mask, 0], 0.0, 1.0)
    ys = np.clip(clean_landmarks[visible_mask, 1], 0.0, 1.0)
    bbox = PoseBBox(
        left   = float(xs.min()),
        top    = float(ys.min()),
        right  = float(xs.max()),
        bottom = float(ys.max()),
    )
    if bbox.area < _PRESENCE_MIN_BBOX_AREA:
        return None
    torso_visible = sum(bool(visible_mask[i]) for i in _TORSO_KEYPOINTS)
    upper_visible = sum(bool(visible_mask[i]) for i in _UPPER_BODY_KEYPOINTS)
    if torso_visible < 2 and upper_visible < 4:
        return None

    # Torso-center fallback to bbox center.
    torso_mask = visible_mask[list(_TORSO_KEYPOINTS)]
    if torso_mask.any():
        idx = [t for t, m in zip(_TORSO_KEYPOINTS, torso_mask) if m]
        cx = _clamp01(float(clean_landmarks[idx, 0].mean()))
        cy = _clamp01(float(clean_landmarks[idx, 1].mean()))
    else:
        cx = _clamp01(0.5 * (bbox.left + bbox.right))
        cy = _clamp01(0.5 * (bbox.top  + bbox.bottom))

    return PoseCandidate(
        landmarks      = clean_landmarks,
        bbox           = bbox,
        center_x       = cx,
        center_y       = cy,
        avg_visibility = float(vis.mean()),
    )


class SubjectSelector:
    """Stateful selector — instantiate once per video / session."""

    def __init__(self, cfg: Optional[SubjectSelectorConfig] = None):
        self.cfg = cfg or SubjectSelectorConfig()
        self._locked: Optional[PoseCandidate] = None
        self._locked_appearance: Optional[SubjectAppearanceSignature] = None
        self._kalman: Optional[_KalmanTrack] = None
        self._last_timestamp_ms: Optional[int] = None
        self._locked_track_id: int = -1
        self._next_track_id: int = 1
        self._lost_subject_frames: int = 0
        self._pending_auto: Optional[PoseCandidate] = None
        self._pending_auto_frames: int = 0
        self._manual_lock: bool = False
        self._pending_tap: Optional[Tuple[float, float]] = None
        self._identity_hold_frames: int = 0

    # ── public API ───────────────────────────────────────────────────

    def request_tap(self, x: float, y: float) -> None:
        self._pending_tap = (x, y)

    def clear_lock(self) -> None:
        self._locked = None
        self._locked_appearance = None
        self._kalman = None
        self._last_timestamp_ms = None
        self._locked_track_id = -1
        self._manual_lock = False
        self._lost_subject_frames = 0
        self._identity_hold_frames = 0
        self._reset_pending_auto()

    def _clear_lost_locked_subject(self) -> None:
        self._locked = None
        self._locked_appearance = None
        self._kalman = None
        self._locked_track_id = -1
        self._manual_lock = False
        self._pending_tap = None
        self._identity_hold_frames = 0
        self._reset_pending_auto()

    @property
    def has_manual_lock(self) -> bool: return self._manual_lock
    @property
    def locked_track_id(self) -> int:  return self._locked_track_id

    def update(
        self,
        candidates_in: List[PoseCandidate],
        timestamp_ms: Optional[int] = None,
    ) -> SubjectSelection:
        dt_s = self._resolve_dt_s(timestamp_ms)
        try:
            return self._update_impl(candidates_in, dt_s)
        finally:
            if timestamp_ms is not None:
                self._last_timestamp_ms = timestamp_ms

    def _update_impl(
        self,
        candidates_in: List[PoseCandidate],
        dt_s: float,
    ) -> SubjectSelection:
        cfg = self.cfg

        # 1. Filter by visibility / non-empty bbox; track source indices.
        visible: List[PoseCandidate] = []
        visible_to_source: List[int] = []
        for i, c in enumerate(candidates_in[: cfg.max_candidates]):
            if _candidate_passes_presence_gate(c.landmarks):
                visible.append(c)
                visible_to_source.append(i)

        sel = SubjectSelection()

        # 2. No visible candidates.
        if not visible:
            if cfg.kalman_enabled and self._kalman is not None:
                self._kalman.predict(dt_s)
            self._reset_pending_auto()
            self._lost_subject_frames += 1
            if (self._manual_lock and self._locked is not None
                    and self._lost_subject_frames < cfg.subject_lost_frames):
                sel.status = SubjectLockStatus.LOCKED
                sel.track_id = self._locked_track_id
                sel.trust_flags = ["subject_locked", "subject_hold"]
                sel.reason = "subject_temporarily_unmatched"
                return sel
            sel.status = SubjectLockStatus.SUBJECT_LOST
            sel.track_id = self._locked_track_id
            sel.trust_flags = ["SUBJECT_LOST"]
            sel.reason = "locked_subject_lost" if self._locked is not None or self._manual_lock else "no_person_detected"
            if self._locked is not None or self._manual_lock:
                track_id = self._locked_track_id
                self._clear_lost_locked_subject()
                sel.track_id = track_id
            return sel

        # 3. Pending tap → manual lock.
        if self._pending_tap is not None:
            tx, ty = self._pending_tap
            self._pending_tap = None
            chosen_visible_idx = self._select_index_from_tap(visible, tx, ty)
            if chosen_visible_idx >= 0:
                chosen = visible[chosen_visible_idx]
                self._manual_lock = True
                trk = self._next_track_id
                self._next_track_id += 1
                self._locked_track_id = trk
                chosen.track_id = trk
                self._locked = chosen
                self._locked_appearance = chosen.appearance
                self._reset_kalman(chosen)
                self._reset_pending_auto()
                self._lost_subject_frames = 0
                self._identity_hold_frames = 0
                return self._make_selection(
                    chosen, visible_to_source[chosen_visible_idx], trk,
                    SubjectLockStatus.LOCKED, candidates_in,
                )

        # 4. Already locked: re-match.
        if self._locked is not None:
            match = self._select_identity_match(visible, dt_s)
            if match.hold:
                self._reset_pending_auto()
                return self._make_hold_selection(match.reason, candidates_in)

            if match.index >= 0:
                status = SubjectLockStatus.LOCKED if self._manual_lock else SubjectLockStatus.AUTO_LOCKED
                trk = self._locked_track_id if self._locked_track_id >= 0 else self._next_track_id
                if self._locked_track_id < 0:
                    self._next_track_id += 1
                self._locked_track_id = trk
                best = visible[match.index]
                best.track_id = trk
                best.track_score = match.score
                best.identity_score = match.score
                self._locked = best
                if cfg.kalman_enabled:
                    if self._kalman is None:
                        self._reset_kalman(best)
                    else:
                        self._kalman.update(best)
                self._reset_pending_auto()
                self._lost_subject_frames = 0
                self._identity_hold_frames = 0
                return self._make_selection(
                    best, visible_to_source[match.index], trk, status, candidates_in,
                )
            if not self._manual_lock:
                # Auto-lock lost subject — drop and re-select below.
                self._locked = None
                self._locked_appearance = None
                self._kalman = None
                self._locked_track_id = -1
            else:
                self._lost_subject_frames += 1
                if self._lost_subject_frames < cfg.subject_lost_frames:
                    sel.status = SubjectLockStatus.LOCKED
                    sel.track_id = self._locked_track_id
                    sel.trust_flags = ["subject_locked", "subject_hold"]
                    sel.reason = "subject_temporarily_unmatched"
                    return sel
                sel.status = SubjectLockStatus.SUBJECT_LOST
                sel.track_id = self._locked_track_id
                sel.trust_flags = ["SUBJECT_LOST"]
                sel.reason = "locked_subject_lost"
                track_id = self._locked_track_id
                self._clear_lost_locked_subject()
                sel.track_id = track_id
                return sel

        # 5. Single visible → SINGLE_AUTO.
        if len(visible) == 1:
            chosen = visible[0]
            trk = self._locked_track_id if self._locked_track_id >= 0 else self._next_track_id
            if self._locked_track_id < 0:
                self._next_track_id += 1
            self._locked_track_id = trk
            chosen.track_id = trk
            self._locked = chosen
            self._locked_appearance = chosen.appearance
            self._reset_kalman(chosen)
            self._reset_pending_auto()
            self._lost_subject_frames = 0
            self._identity_hold_frames = 0
            return self._make_selection(
                chosen, visible_to_source[0], trk,
                SubjectLockStatus.SINGLE_AUTO, candidates_in,
            )

        # 6. Multiple visible → auto-pick with stability.
        auto = self._select_auto_candidate(visible)
        if auto is not None:
            visible_idx, score = auto
            chosen = visible[visible_idx]
            chosen.track_score = score

            stable = (
                self._pending_auto is not None
                and self._score_subject_match(self._pending_auto, chosen) >= cfg.subject_match_threshold
            )
            self._pending_auto_frames = (self._pending_auto_frames + 1) if stable else 1
            self._pending_auto = chosen

            if self._pending_auto_frames < cfg.auto_lock_stable_frames:
                sel.status = SubjectLockStatus.NEEDS_SELECTION
                sel.trust_flags = ["MULTI_PERSON", "AUTO_SELECTION_PENDING"]
                sel.reason = "auto_subject_stabilizing"
                return sel

            trk = self._next_track_id
            self._next_track_id += 1
            self._locked_track_id = trk
            chosen.track_id = trk
            self._locked = chosen
            self._locked_appearance = chosen.appearance
            self._reset_kalman(chosen)
            self._manual_lock = False
            self._reset_pending_auto()
            self._lost_subject_frames = 0
            self._identity_hold_frames = 0
            return self._make_selection(
                chosen, visible_to_source[visible_idx], trk,
                SubjectLockStatus.AUTO_LOCKED, candidates_in,
            )

        # 7. Ambiguous → require selection.
        sel.status = SubjectLockStatus.NEEDS_SELECTION
        sel.trust_flags = ["MULTI_PERSON", "NEEDS_SELECTION"]
        sel.reason = "multi_person_selection_required"
        return sel

    # ── internal helpers ─────────────────────────────────────────────

    def _resolve_dt_s(self, timestamp_ms: Optional[int]) -> float:
        if timestamp_ms is None or self._last_timestamp_ms is None:
            return self.cfg.kalman_default_dt_s
        dt = (timestamp_ms - self._last_timestamp_ms) / 1000.0
        if not math.isfinite(dt) or dt <= 0.0:
            return self.cfg.kalman_default_dt_s
        return min(dt, 1.0)

    def _reset_kalman(self, candidate: PoseCandidate) -> None:
        self._kalman = _KalmanTrack(self.cfg, candidate) if self.cfg.kalman_enabled else None

    def _reset_pending_auto(self) -> None:
        self._pending_auto = None
        self._pending_auto_frames = 0

    def _make_selection(
        self,
        chosen: PoseCandidate,
        active_index: int,
        track_id: int,
        status: SubjectLockStatus,
        candidates_in: List[PoseCandidate],
    ) -> SubjectSelection:
        return SubjectSelection(
            active_index=active_index,
            track_id=track_id,
            status=status,
            trust_flags=self._trust_flags_for(candidates_in, status),
            reason="",
            has_candidate=True,
            candidate=chosen,
        )

    def _make_hold_selection(
        self,
        reason: str,
        candidates_in: List[PoseCandidate],
    ) -> SubjectSelection:
        self._identity_hold_frames += 1
        status = SubjectLockStatus.LOCKED if self._manual_lock else SubjectLockStatus.AUTO_LOCKED
        flags = self._trust_flags_for(candidates_in, status)
        flags.append("subject_hold")
        flags.append(reason if reason == "subject_temporarily_occluded" else "subject_identity_uncertain")
        seen = set()
        out = []
        for flag in flags:
            if flag not in seen:
                seen.add(flag)
                out.append(flag)
        return SubjectSelection(
            active_index=-1,
            track_id=self._locked_track_id,
            status=status,
            trust_flags=out,
            reason=reason,
            has_candidate=False,
            candidate=None,
        )

    def _trust_flags_for(self, candidates: List[PoseCandidate], status: SubjectLockStatus) -> List[str]:
        flags: List[str] = []
        if len(candidates) > 1:
            flags.append("other_people_detected")
        flags.append({
            SubjectLockStatus.LOCKED:        "subject_locked",
            SubjectLockStatus.AUTO_LOCKED:   "subject_auto_locked",
            SubjectLockStatus.SINGLE_AUTO:   "single_subject_auto",
            SubjectLockStatus.SUBJECT_LOST:  "SUBJECT_LOST",
            SubjectLockStatus.NEEDS_SELECTION: "NEEDS_SELECTION",
        }[status])
        # de-dup preserving order
        seen = set()
        out = []
        for f in flags:
            if f not in seen:
                seen.add(f)
                out.append(f)
        return out

    def _center_match_score(self, prev: PoseCandidate, cur: PoseCandidate) -> float:
        center_dist = _euclid(prev.center_x, prev.center_y, cur.center_x, cur.center_y)
        return _clamp01(1.0 - center_dist / _MATCH_CENTER_RADIUS)

    def _appearance_similarity(self, prev: PoseCandidate, cur: PoseCandidate) -> float:
        anchor = self._locked_appearance if self._locked_appearance is not None else prev.appearance
        if anchor is None:
            return 0.5
        return anchor.similarity(cur.appearance)

    def _score_identity_match(
        self,
        prev: PoseCandidate,
        cur: PoseCandidate,
        motion_score: float,
    ) -> float:
        area_base = max(prev.bbox.area, cur.bbox.area, 0.001)
        area_score = _clamp01(1.0 - abs(prev.bbox.area - cur.bbox.area) / area_base)
        keypoint_score = _clamp01(
            1.0 - self._mean_keypoint_distance(prev.landmarks, cur.landmarks) /
            _KEYPOINT_MATCH_RADIUS
        )
        appearance_score = self._appearance_similarity(prev, cur)
        area_visibility_score = 0.60 * area_score + 0.40 * _clamp01(cur.avg_visibility)
        cur.appearance_score = appearance_score
        score = (
            _IDENTITY_MOTION_WEIGHT * _clamp01(motion_score) +
            _IDENTITY_KEYPOINT_WEIGHT * keypoint_score +
            _IDENTITY_APPEARANCE_WEIGHT * appearance_score +
            _IDENTITY_AREA_VIS_WEIGHT * area_visibility_score
        )
        cur.identity_score = _clamp01(score)
        return cur.identity_score

    def _select_identity_match(
        self,
        candidates: List[PoseCandidate],
        dt_s: float,
    ) -> _IdentityMatch:
        if not candidates or self._locked is None:
            return _IdentityMatch()

        predicted = False
        if self.cfg.kalman_enabled:
            if self._kalman is None:
                self._reset_kalman(self._locked)
            if self._kalman is not None:
                self._kalman.predict(dt_s)
                predicted = True

        scored: List[Tuple[int, float]] = []
        for i, candidate in enumerate(candidates):
            motion_score = self._center_match_score(self._locked, candidate)
            if predicted and self._kalman is not None:
                d2 = self._kalman.mahalanobis_d2(candidate)
                if math.isfinite(d2) and d2 <= self.cfg.kalman_chi2_gate_3df:
                    motion_score = _clamp01(1.0 - d2 / self.cfg.kalman_chi2_gate_3df)
                else:
                    # Geometry can jump during partial occlusion. Keep a weak
                    # motion score so appearance/keypoints can veto wrong IDs.
                    motion_score *= 0.50
            scored.append((i, self._score_identity_match(self._locked, candidate, motion_score)))

        scored.sort(key=lambda t: t[1], reverse=True)
        best_i, best_score = scored[0]
        second_score = scored[1][1] if len(scored) > 1 else -1.0
        margin = best_score - second_score if second_score >= 0.0 else 1.0
        candidates[best_i].match_margin = margin
        if len(scored) > 1:
            candidates[scored[1][0]].match_margin = margin

        anchor = self._locked_appearance if self._locked_appearance is not None else self._locked.appearance
        if anchor is not None and anchor.is_valid:
            if candidates[best_i].appearance_score < _IDENTITY_APPEARANCE_ACCEPT:
                return _IdentityMatch(
                    index=-1,
                    score=best_score,
                    hold=True,
                    reason="subject_appearance_mismatch",
                )

        if best_score < self.cfg.subject_match_threshold:
            return _IdentityMatch()

        if self._is_ambiguous_identity_match(candidates, scored, margin):
            return _IdentityMatch(
                index=-1,
                score=best_score,
                hold=True,
                reason="subject_temporarily_occluded",
            )

        if self._identity_hold_frames > 0 and anchor is not None and anchor.is_valid:
            reacquire_is_clear = (
                candidates[best_i].appearance_score >= _IDENTITY_REACQUIRE_ACCEPT and
                margin >= _IDENTITY_REACQUIRE_MARGIN
            )
            if not reacquire_is_clear:
                return _IdentityMatch(
                    index=-1,
                    score=best_score,
                    hold=True,
                    reason="subject_identity_reacquiring",
                )

        return _IdentityMatch(index=best_i, score=best_score)

    def _is_ambiguous_identity_match(
        self,
        candidates: List[PoseCandidate],
        scored: List[Tuple[int, float]],
        margin: float,
    ) -> bool:
        if len(scored) < 2:
            return False
        anchor = self._locked_appearance if self._locked_appearance is not None else self._locked.appearance if self._locked is not None else None
        if anchor is None or not anchor.is_valid:
            return False
        best = candidates[scored[0][0]]
        second = candidates[scored[1][0]]
        appearance_gap = best.appearance_score - second.appearance_score
        center_distance = _euclid(best.center_x, best.center_y, second.center_x, second.center_y)
        box_overlap = _bbox_iou(best.bbox, second.bbox)
        duplicate_same_subject = (
            box_overlap >= _IDENTITY_DUPLICATE_IOU and
            center_distance <= _IDENTITY_DUPLICATE_CENTER_CLOSE and
            best.appearance_score >= _IDENTITY_APPEARANCE_ACCEPT and
            second.appearance_score >= _IDENTITY_APPEARANCE_ACCEPT
        )
        if duplicate_same_subject:
            return False
        identity_is_clear = (
            best.appearance_score >= _IDENTITY_APPEARANCE_STRONG and
            appearance_gap >= 0.12
        )
        if identity_is_clear:
            return False
        center_close = center_distance <= _IDENTITY_CENTER_CLOSE
        boxes_overlap = box_overlap >= _IDENTITY_OVERLAP_IOU
        score_tie = margin < _IDENTITY_AMBIGUITY_MARGIN
        return score_tie or center_close or boxes_overlap

    def _select_auto_candidate(
        self, candidates: List[PoseCandidate],
    ) -> Optional[Tuple[int, float]]:
        if len(candidates) < 2:
            return None
        max_area = max(0.001, max(c.bbox.area for c in candidates))
        scored: List[Tuple[int, float]] = []
        for i, c in enumerate(candidates):
            area_score   = _clamp01(c.bbox.area / max_area)
            center_dist  = _euclid(c.center_x, c.center_y, _FRAME_CENTER_X, _FRAME_CENTER_Y)
            center_score = _clamp01(1.0 - center_dist / _AUTO_CENTER_RADIUS)
            vis_score    = _clamp01(c.avg_visibility)
            s = 0.45 * area_score + 0.35 * center_score + 0.20 * vis_score
            scored.append((i, s))
        scored.sort(key=lambda t: t[1], reverse=True)
        best_i, best_s = scored[0]
        second_s = scored[1][1] if len(scored) > 1 else 0.0
        if best_s >= self.cfg.auto_lock_min_score and (best_s - second_s) >= self.cfg.auto_lock_margin:
            return best_i, best_s
        return None

    def _score_subject_match(self, prev: PoseCandidate, cur: PoseCandidate) -> float:
        center_dist  = _euclid(prev.center_x, prev.center_y, cur.center_x, cur.center_y)
        center_score = _clamp01(1.0 - center_dist / _MATCH_CENTER_RADIUS)
        area_base = max(prev.bbox.area, cur.bbox.area, 0.001)
        area_score = _clamp01(1.0 - abs(prev.bbox.area - cur.bbox.area) / area_base)
        kp_dist = self._mean_keypoint_distance(prev.landmarks, cur.landmarks)
        keypoint_score = _clamp01(1.0 - kp_dist / _KEYPOINT_MATCH_RADIUS)
        vis_score = _clamp01(cur.avg_visibility)
        return 0.42 * center_score + 0.38 * keypoint_score + 0.12 * area_score + 0.08 * vis_score

    def _select_kalman_match(
        self,
        candidates: List[PoseCandidate],
        dt_s: float,
    ) -> Tuple[int, float]:
        if not candidates:
            return -1, -1.0
        if self._kalman is None:
            if self._locked is None:
                return -1, -1.0
            self._reset_kalman(self._locked)
        assert self._kalman is not None
        self._kalman.predict(dt_s)

        best_idx = -1
        best_d2 = math.inf
        for i, c in enumerate(candidates):
            d2 = self._kalman.mahalanobis_d2(c)
            if d2 < best_d2:
                best_d2 = d2
                best_idx = i

        if best_idx < 0 or best_d2 > self.cfg.kalman_chi2_gate_3df:
            if self._locked is not None:
                # Real MediaPipe multi-pose output can briefly split one body
                # into several plausible candidates or jump the torso center
                # outside the Kalman covariance. Fall back to the established
                # keypoint/area/visibility match instead of dropping the lock.
                fallback_idx = -1
                fallback_score = -1.0
                for i, c in enumerate(candidates):
                    heuristic_score = self._score_subject_match(self._locked, c)
                    if heuristic_score > fallback_score:
                        fallback_idx = i
                        fallback_score = heuristic_score
                if fallback_idx >= 0 and fallback_score >= self.cfg.subject_match_threshold:
                    return fallback_idx, 0.5 * fallback_score
            return -1, -1.0
        score = _clamp01(1.0 - best_d2 / self.cfg.kalman_chi2_gate_3df)
        return best_idx, score

    def _mean_keypoint_distance(self, a: np.ndarray, b: np.ndarray) -> float:
        floor = self.cfg.keypoint_visibility_floor
        idx = list(_MATCH_KEYPOINTS)
        ax, ay, av = a[idx, 0], a[idx, 1], a[idx, 2]
        bx, by, bv = b[idx, 0], b[idx, 1], b[idx, 2]
        mask = (av > floor) & (bv > floor)
        if not mask.any():
            return 1.0
        d = np.hypot(ax[mask] - bx[mask], ay[mask] - by[mask])
        return float(d.mean())

    def _select_index_from_tap(
        self, candidates: List[PoseCandidate], x: float, y: float,
    ) -> int:
        if not candidates:
            return -1
        any_containing = any(c.bbox.contains(x, y) for c in candidates)
        best_idx = -1
        best_d2 = math.inf
        for i, c in enumerate(candidates):
            if any_containing and not c.bbox.contains(x, y):
                continue
            dx = c.center_x - x
            dy = c.center_y - y
            d2 = dx * dx + dy * dy
            if d2 < best_d2:
                best_d2 = d2
                best_idx = i
        return best_idx
