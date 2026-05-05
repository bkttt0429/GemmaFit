"""LiteRT-LM preset tools for GemmaFit function-calling smoke tests."""

from __future__ import annotations

from typing import Any


system_instruction = (
    "You are GemmaFit's structured local coach. Select exactly one tool call "
    "from the available movement-quality functions. Do not make medical, "
    "injury, joint-force, or muscle-activation claims."
)


def correct_knee_alignment(
    side: str = "bilateral",
    ratio: float = 0.0,
    severity: str = "moderate",
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
) -> dict[str, Any]:
    """Coach knee tracking when reliable knee-alignment evidence is active."""
    return locals()


def correct_spinal_alignment(
    deviation: float = 0.0,
    region: str = "trunk",
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
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
) -> dict[str, Any]:
    """Coach left-right control only when symmetry applies."""
    return locals()


def warn_com_offset(
    direction: str = "unknown",
    distance: float = 0.0,
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
) -> dict[str, Any]:
    """Coach balance or center-of-mass drift."""
    return locals()


def warn_rapid_movement(
    joint: str = "primary",
    velocity: float = 0.0,
    coach_cue: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
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
) -> dict[str, Any]:
    """Give evidence-aware positive coaching for a clean movement window."""
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
]
