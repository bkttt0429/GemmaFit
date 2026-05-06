"""LiteRT-LM preset tools for GemmaFit function-calling smoke tests."""

from __future__ import annotations

from typing import Any


system_instruction = (
    "You are GemmaFit's v3 evidence router. Select exactly one tool call "
    "from the 12 allowed functions using only capability_contract.can_judge "
    "and valid evidence_refs. Refuse unsupported, medical, injury, force, "
    "fall-risk, sarcopenia, and muscle-activation requests."
)


def correct_knee_alignment(
    side: str = "bilateral",
    ratio: float = 0.0,
    severity: str = "moderate",
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Coach knee tracking when reliable knee-alignment evidence is active."""
    return locals()


def correct_spinal_alignment(
    deviation: float = 0.0,
    region: str = "trunk",
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Coach trunk or neck alignment when supported by confidence and view."""
    return locals()


def correct_joint_angle(
    joint: str = "primary",
    current: float = 0.0,
    safe_range: str = "",
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Coach conservative joint control near an unsafe endpoint."""
    return locals()


def correct_asymmetry(
    joint: str = "primary",
    left: float = 0.0,
    right: float = 0.0,
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Coach left-right control only when symmetry applies."""
    return locals()


def warn_com_offset(
    direction: str = "unknown",
    distance: float = 0.0,
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Coach balance or center-of-mass drift."""
    return locals()


def warn_rapid_movement(
    joint: str = "primary",
    velocity: float = 0.0,
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Coach tempo when smoothed velocity crosses the rapid-movement gate."""
    return locals()


def increase_range_of_motion(
    joint: str = "primary",
    current: float = 0.0,
    target: float = 0.0,
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Coach ROM when the exercise template defines a supported ROM target."""
    return locals()


def positive_reinforcement(
    pattern: str = "unknown",
    streak: int = 0,
    primary_muscles: list[str] | None = None,
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Give evidence-aware positive coaching for a clean movement window."""
    return locals()


def read_memory(
    scope: str = "TRENDS_7D",
    exercise: str = "",
    session_id: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
) -> dict[str, Any]:
    """Request a closed-set local memory slice; the app chooses what is returned."""
    return locals()


def request_memory_update(
    request_id: str = "",
    type: str = "TREND_NOTE",
    proposed_value: dict[str, Any] | None = None,
    evidence_ids: list[str] | None = None,
    confidence: float = 0.0,
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
) -> dict[str, Any]:
    """Propose a structured memory write; the app validates before storing."""
    return locals()


def summarize_trend(
    scope: str = "TRENDS_7D",
    exercise: str = "squat",
    focus: str = "tempo",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Summarize app-provided local trend aggregates only."""
    return locals()


def refuse_unsupported_question(
    reason: str = "insufficient_evidence",
    safe_alternative: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 2,
    next_focus: str = "",
) -> dict[str, Any]:
    """Refuse medical, force, injury, fall-risk, sarcopenia, or insufficient-evidence requests."""
    return locals()


tools = [
    correct_knee_alignment,
    correct_spinal_alignment,
    correct_joint_angle,
    correct_asymmetry,
    warn_com_offset,
    warn_rapid_movement,
    increase_range_of_motion,
    positive_reinforcement,
    read_memory,
    request_memory_update,
    summarize_trend,
    refuse_unsupported_question,
]
