"""
Layer 2 deterministic temporal interpreter.

This module is the baseline before training any Layer 2 sequence model. It
turns derived pose features into bounded activity, phase, event, and abstain
signals. It does not call Gemma/E2B and it does not make safety verdicts.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional


class Layer2Activity(str, Enum):
    CHAIR_SIT_TO_STAND = "chair_sit_to_stand"
    SUPPORTED_SQUAT = "supported_squat"
    BALANCE_HOLD = "balance_hold"
    UNKNOWN = "unknown"


class Layer2Phase(str, Enum):
    IDLE = "idle"
    SIT_LOW = "sit_low"
    DESCENDING = "descending"
    SQUAT_BOTTOM = "squat_bottom"
    RISING = "rising"
    STANDING_STABILIZED = "standing_stabilized"
    BALANCE_HOLDING = "balance_holding"
    BALANCE_UNSTABLE = "balance_unstable"
    MONITOR_ONLY = "monitor_only"
    ABSTAIN = "abstain"


class Layer2Event(str, Enum):
    NONE = "NONE"
    REP_STARTED = "REP_STARTED"
    REP_COMPLETED = "REP_COMPLETED"
    BALANCE_HOLD_STARTED = "BALANCE_HOLD_STARTED"
    BALANCE_HOLD_COMPLETED = "BALANCE_HOLD_COMPLETED"
    MONITOR_ONLY = "MONITOR_ONLY"
    ABSTAIN = "ABSTAIN"


@dataclass
class Layer2Features:
    """Derived frame/window features, never raw video."""

    timestamp_ms: int
    activity_hint: str = Layer2Activity.UNKNOWN.value
    pose_confidence: float = 1.0
    person_state: str = "observed"
    judgment_allowed: bool = True
    knee_angle_deg: Optional[float] = None
    hip_y: Optional[float] = None
    trunk_angle_deg: Optional[float] = None
    sway_norm: Optional[float] = None
    support_contact: Optional[bool] = None
    extra: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        data: Dict[str, Any] = {
            "timestamp_ms": self.timestamp_ms,
            "activity_hint": self.activity_hint,
            "pose_confidence": self.pose_confidence,
            "person_state": self.person_state,
            "judgment_allowed": self.judgment_allowed,
        }
        optional = {
            "knee_angle_deg": self.knee_angle_deg,
            "hip_y": self.hip_y,
            "trunk_angle_deg": self.trunk_angle_deg,
            "sway_norm": self.sway_norm,
            "support_contact": self.support_contact,
        }
        data.update({k: v for k, v in optional.items() if v is not None})
        if self.extra:
            data["extra"] = self.extra
        return data


@dataclass
class Layer2Output:
    timestamp_ms: int
    activity_hypothesis: str
    phase: str
    event: str = Layer2Event.NONE.value
    confidence: float = 0.0
    abstain_reason: Optional[str] = None
    rep_count: int = 0
    hold_duration_ms: int = 0
    evidence_refs: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        data: Dict[str, Any] = {
            "timestamp_ms": self.timestamp_ms,
            "activity_hypothesis": self.activity_hypothesis,
            "phase": self.phase,
            "event": self.event,
            "confidence": round(float(self.confidence), 4),
            "rep_count": self.rep_count,
            "hold_duration_ms": self.hold_duration_ms,
            "evidence_refs": list(self.evidence_refs),
        }
        if self.abstain_reason:
            data["abstain_reason"] = self.abstain_reason
        return data


@dataclass
class Layer2Config:
    min_pose_confidence: float = 0.55
    standing_knee_angle_deg: float = 158.0
    low_knee_angle_deg: float = 118.0
    standing_hip_y: float = 0.46
    low_hip_y: float = 0.58
    min_rep_rom_deg: float = 28.0
    stable_top_frames: int = 2
    stable_balance_sway: float = 0.045
    unstable_balance_sway: float = 0.10
    balance_hold_target_ms: int = 5000


class Layer2FSM:
    """Small deterministic Layer 2 baseline for senior-mode temporal events."""

    def __init__(
        self,
        activity: str = Layer2Activity.CHAIR_SIT_TO_STAND.value,
        config: Optional[Layer2Config] = None,
    ) -> None:
        self.activity = self._normalize_activity(activity)
        self.config = config or Layer2Config()
        self.phase = Layer2Phase.IDLE
        self.rep_count = 0
        self._prev_features: Optional[Layer2Features] = None
        self._seen_low = False
        self._seen_rising = False
        self._rep_min_knee: Optional[float] = None
        self._rep_max_knee: Optional[float] = None
        self._top_streak = 0
        self._balance_start_ms: Optional[int] = None
        self._balance_completed = False

    def reset(self) -> None:
        activity = self.activity
        config = self.config
        self.__init__(activity=activity.value, config=config)

    def update(self, features: Layer2Features) -> Layer2Output:
        gate_reason = self._gate_reason(features)
        if gate_reason:
            self.phase = Layer2Phase.ABSTAIN
            return self._output(
                features,
                phase=Layer2Phase.ABSTAIN,
                event=Layer2Event.ABSTAIN,
                confidence=0.0,
                abstain_reason=gate_reason,
            )

        if self.activity == Layer2Activity.BALANCE_HOLD:
            output = self._update_balance(features)
        elif self.activity in {
            Layer2Activity.CHAIR_SIT_TO_STAND,
            Layer2Activity.SUPPORTED_SQUAT,
        }:
            output = self._update_rep_activity(features)
        else:
            self.phase = Layer2Phase.MONITOR_ONLY
            output = self._output(
                features,
                phase=Layer2Phase.MONITOR_ONLY,
                event=Layer2Event.MONITOR_ONLY,
                confidence=min(features.pose_confidence, 0.45),
                abstain_reason="unsupported_activity",
            )

        self._prev_features = features
        return output

    def _update_rep_activity(self, features: Layer2Features) -> Layer2Output:
        raw_phase = self._estimate_rep_phase(features)
        confidence = self._confidence(features, raw_phase)

        if raw_phase == Layer2Phase.MONITOR_ONLY:
            self.phase = raw_phase
            return self._output(
                features,
                phase=raw_phase,
                event=Layer2Event.MONITOR_ONLY,
                confidence=confidence,
                abstain_reason="insufficient_rep_features",
            )

        event = Layer2Event.NONE
        prev_phase = self.phase
        self.phase = raw_phase

        if self._is_rep_start(raw_phase, prev_phase):
            event = Layer2Event.REP_STARTED
            self._seed_rep_window_from_previous()

        knee = features.knee_angle_deg
        if knee is not None and raw_phase != Layer2Phase.STANDING_STABILIZED:
            self._rep_min_knee = knee if self._rep_min_knee is None else min(self._rep_min_knee, knee)
            self._rep_max_knee = knee if self._rep_max_knee is None else max(self._rep_max_knee, knee)

        if raw_phase in {Layer2Phase.SIT_LOW, Layer2Phase.SQUAT_BOTTOM}:
            self._seen_low = True
            self._top_streak = 0

        if raw_phase == Layer2Phase.RISING:
            self._seen_rising = True
            self._top_streak = 0

        if raw_phase == Layer2Phase.STANDING_STABILIZED:
            self._top_streak += 1
        else:
            self._top_streak = 0

        if (
            self._seen_low
            and self._seen_rising
            and raw_phase == Layer2Phase.STANDING_STABILIZED
            and self._top_streak >= self.config.stable_top_frames
            and self._rep_rom_ok(features)
        ):
            self.rep_count += 1
            event = Layer2Event.REP_COMPLETED
            self._reset_rep_window(keep_top_streak=True)

        return self._output(features, phase=raw_phase, event=event, confidence=confidence)

    def _update_balance(self, features: Layer2Features) -> Layer2Output:
        sway = features.sway_norm
        if sway is None:
            self.phase = Layer2Phase.MONITOR_ONLY
            return self._output(
                features,
                phase=Layer2Phase.MONITOR_ONLY,
                event=Layer2Event.MONITOR_ONLY,
                confidence=min(features.pose_confidence, 0.5),
                abstain_reason="missing_sway_feature",
            )

        if sway > self.config.unstable_balance_sway:
            self._balance_start_ms = None
            self._balance_completed = False
            self.phase = Layer2Phase.BALANCE_UNSTABLE
            return self._output(
                features,
                phase=Layer2Phase.BALANCE_UNSTABLE,
                event=Layer2Event.MONITOR_ONLY,
                confidence=min(features.pose_confidence, 0.65),
                abstain_reason="sway_above_monitor_threshold",
            )

        if sway <= self.config.stable_balance_sway:
            event = Layer2Event.NONE
            if self._balance_start_ms is None:
                self._balance_start_ms = features.timestamp_ms
                self._balance_completed = False
                event = Layer2Event.BALANCE_HOLD_STARTED

            hold_ms = max(0, features.timestamp_ms - self._balance_start_ms)
            if (
                hold_ms >= self.config.balance_hold_target_ms
                and not self._balance_completed
            ):
                event = Layer2Event.BALANCE_HOLD_COMPLETED
                self._balance_completed = True

            self.phase = Layer2Phase.BALANCE_HOLDING
            return self._output(
                features,
                phase=Layer2Phase.BALANCE_HOLDING,
                event=event,
                confidence=min(0.98, features.pose_confidence),
                hold_duration_ms=hold_ms,
            )

        self.phase = Layer2Phase.MONITOR_ONLY
        return self._output(
            features,
            phase=Layer2Phase.MONITOR_ONLY,
            event=Layer2Event.MONITOR_ONLY,
            confidence=min(features.pose_confidence, 0.7),
            abstain_reason="sway_needs_monitoring",
        )

    def _estimate_rep_phase(self, features: Layer2Features) -> Layer2Phase:
        knee = features.knee_angle_deg
        if knee is not None:
            if knee >= self.config.standing_knee_angle_deg:
                return Layer2Phase.STANDING_STABILIZED
            if knee <= self.config.low_knee_angle_deg:
                return (
                    Layer2Phase.SIT_LOW
                    if self.activity == Layer2Activity.CHAIR_SIT_TO_STAND
                    else Layer2Phase.SQUAT_BOTTOM
                )

            prev_knee = self._prev_features.knee_angle_deg if self._prev_features else None
            if prev_knee is not None:
                if knee > prev_knee + 1.0:
                    return Layer2Phase.RISING
                if knee < prev_knee - 1.0:
                    return Layer2Phase.DESCENDING
            return self.phase if self.phase != Layer2Phase.IDLE else Layer2Phase.MONITOR_ONLY

        hip_y = features.hip_y
        if hip_y is None:
            return Layer2Phase.MONITOR_ONLY

        if hip_y <= self.config.standing_hip_y:
            return Layer2Phase.STANDING_STABILIZED
        if hip_y >= self.config.low_hip_y:
            return (
                Layer2Phase.SIT_LOW
                if self.activity == Layer2Activity.CHAIR_SIT_TO_STAND
                else Layer2Phase.SQUAT_BOTTOM
            )

        prev_hip_y = self._prev_features.hip_y if self._prev_features else None
        if prev_hip_y is not None:
            # Normalized image y grows downward: smaller hip_y means rising.
            if hip_y < prev_hip_y - 0.004:
                return Layer2Phase.RISING
            if hip_y > prev_hip_y + 0.004:
                return Layer2Phase.DESCENDING
        return self.phase if self.phase != Layer2Phase.IDLE else Layer2Phase.MONITOR_ONLY

    def _gate_reason(self, features: Layer2Features) -> Optional[str]:
        if features.person_state not in {"observed", "single_auto", "auto_locked", "manual_locked"}:
            return "person_not_observed"
        if not features.judgment_allowed:
            return "judgment_not_allowed"
        if features.pose_confidence < self.config.min_pose_confidence:
            return "low_pose_confidence"
        return None

    def _rep_rom_ok(self, features: Layer2Features) -> bool:
        if self._rep_min_knee is not None and self._rep_max_knee is not None:
            return (self._rep_max_knee - self._rep_min_knee) >= self.config.min_rep_rom_deg

        # Hip-only windows are accepted if they reached a clear low phase and top.
        return features.hip_y is not None

    def _is_rep_start(self, raw_phase: Layer2Phase, prev_phase: Layer2Phase) -> bool:
        if self._seen_low or self._seen_rising:
            return False
        return raw_phase in {
            Layer2Phase.DESCENDING,
            Layer2Phase.SIT_LOW,
            Layer2Phase.SQUAT_BOTTOM,
        } and prev_phase in {
            Layer2Phase.IDLE,
            Layer2Phase.STANDING_STABILIZED,
            Layer2Phase.MONITOR_ONLY,
        }

    def _seed_rep_window_from_previous(self) -> None:
        if self._prev_features is None:
            return

        previous_knee = self._prev_features.knee_angle_deg
        if previous_knee is None:
            return

        self._rep_min_knee = (
            previous_knee
            if self._rep_min_knee is None
            else min(self._rep_min_knee, previous_knee)
        )
        self._rep_max_knee = (
            previous_knee
            if self._rep_max_knee is None
            else max(self._rep_max_knee, previous_knee)
        )

    def _reset_rep_window(self, keep_top_streak: bool = False) -> None:
        top_streak = self._top_streak if keep_top_streak else 0
        self._seen_low = False
        self._seen_rising = False
        self._rep_min_knee = None
        self._rep_max_knee = None
        self._top_streak = top_streak

    def _confidence(self, features: Layer2Features, phase: Layer2Phase) -> float:
        if phase == Layer2Phase.MONITOR_ONLY:
            return min(features.pose_confidence, 0.55)

        confidence = features.pose_confidence
        if self._activity_hint_matches(features.activity_hint):
            confidence = min(1.0, confidence + 0.05)
        if features.knee_angle_deg is None and features.hip_y is None:
            confidence = min(confidence, 0.45)
        return max(0.0, min(1.0, confidence))

    def _activity_hint_matches(self, activity_hint: str) -> bool:
        return self._normalize_activity(activity_hint) == self.activity

    def _output(
        self,
        features: Layer2Features,
        phase: Layer2Phase,
        event: Layer2Event = Layer2Event.NONE,
        confidence: float = 0.0,
        abstain_reason: Optional[str] = None,
        hold_duration_ms: Optional[int] = None,
    ) -> Layer2Output:
        refs = [
            f"layer2.activity.{self.activity.value}",
            f"layer2.phase.{phase.value}",
        ]
        if event != Layer2Event.NONE:
            refs.append(f"layer2.event.{event.value.lower()}")

        return Layer2Output(
            timestamp_ms=features.timestamp_ms,
            activity_hypothesis=self.activity.value,
            phase=phase.value,
            event=event.value,
            confidence=confidence,
            abstain_reason=abstain_reason,
            rep_count=self.rep_count,
            hold_duration_ms=(
                hold_duration_ms
                if hold_duration_ms is not None
                else self._current_hold_duration(features)
            ),
            evidence_refs=refs,
        )

    def _current_hold_duration(self, features: Layer2Features) -> int:
        if self._balance_start_ms is None:
            return 0
        return max(0, features.timestamp_ms - self._balance_start_ms)

    @staticmethod
    def _normalize_activity(activity: str) -> Layer2Activity:
        try:
            return Layer2Activity(activity)
        except ValueError:
            return Layer2Activity.UNKNOWN


def run_layer2_sequence(
    features: List[Layer2Features],
    activity: str,
    config: Optional[Layer2Config] = None,
) -> List[Layer2Output]:
    fsm = Layer2FSM(activity=activity, config=config)
    return [fsm.update(frame) for frame in features]
