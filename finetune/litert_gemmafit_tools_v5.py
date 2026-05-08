"""LiteRT-LM preset tools for GemmaFit v5 E2B evidence-router smoke tests."""

from __future__ import annotations

from typing import Any


system_instruction = (
    "You are GemmaFit's v5 E2B evidence router. Select exactly one tool call "
    "using only app-provided activity_context, person_tracking_state, "
    "motion_feature_window, visual_summary, capability_contract, and valid "
    "evidence_refs. Refuse unsupported medical, force, GRF, EMG, heart-rate, "
    "fall-risk, sarcopenia, rehabilitation, clinical, or missing-evidence "
    "requests. Predicted or lost tracking state cannot support hard judgment. "
    "Only call a function from the registered tools list; capability_contract "
    "and evidence_ledger are input sections, not callable tools."
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
    """Coach knee tracking only when reliable knee evidence is judgeable."""
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
    """Coach conservative joint control near a supported endpoint."""
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
    """Coach a visible COM proxy without claiming clinical balance risk."""
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
    """Coach tempo when smoothed velocity proxy is judgeable."""
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
    """Coach ROM when the activity template defines a supported ROM target."""
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
    """Give evidence-aware positive coaching for a clean observed movement."""
    return locals()


def create_care_activity_log(
    headline: str = "",
    what_was_completed: str = "",
    observations: str = "",
    not_judged: str = "",
    next_session_focus: str = "",
    caregiver_note: str = "",
    evidence_refs: list[str] | None = None,
    selection_basis: str = "",
) -> dict[str, Any]:
    """Generate a non-diagnostic caregiver activity log from evidence refs."""
    return locals()


def ask_subjective_checkin(
    prompt_keys: list[str] | None = None,
    input_modes: list[str] | None = None,
    response_schema: dict[str, Any] | None = None,
    evidence_refs: list[str] | None = None,
    selection_basis: str = "",
) -> dict[str, Any]:
    """Ask bounded post-session self-report questions."""
    return locals()


def record_subjective_checkin(
    rpe_0_10: int = 0,
    breathlessness: str = "none",
    leg_soreness: str = "none",
    needed_rest: bool = False,
    discomfort_reported: bool = False,
    evidence_refs: list[str] | None = None,
    selection_basis: str = "",
) -> dict[str, Any]:
    """Record bounded self-report evidence without clinical interpretation."""
    return locals()


def create_persona_activity_report(
    persona: str = "caregiver",
    report_text: str = "",
    objective_evidence_refs: list[str] | None = None,
    subjective_evidence_refs: list[str] | None = None,
    boundary_note: str = "",
    selection_basis: str = "",
) -> dict[str, Any]:
    """Generate senior, caregiver, or professional-share activity wording."""
    return locals()


def select_dual_task_prompt(
    prompt_text_key: str = "",
    prompt_args: dict[str, Any] | None = None,
    expected_response_modes: list[str] | None = None,
    expected_movement: str = "",
    coach_cue: str = "",
    evidence_refs: list[str] | None = None,
    selection_basis: str = "",
    next_focus: str = "",
) -> dict[str, Any]:
    """Select a bounded low-impact dual-task prompt."""
    return locals()


def record_dual_task_result(
    prompt_id: str = "",
    response_mode: str = "gesture",
    answer_matched: bool = False,
    movement_completed: bool = False,
    fallback_reason: str = "",
    evidence_refs: list[str] | None = None,
    selection_basis: str = "",
) -> dict[str, Any]:
    """Record bounded dual-task outcome without cognitive diagnosis."""
    return locals()


def read_memory(
    scope: str = "TRENDS_7D",
    exercise: str = "",
    session_id: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
) -> dict[str, Any]:
    """Request a closed-set local memory slice."""
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
    """Propose a structured memory write that the app must validate."""
    return locals()


def summarize_trend(
    scope: str = "TRENDS_7D",
    exercise: str = "chair_sit_to_stand",
    focus: str = "tempo",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 0,
    next_focus: str = "",
) -> dict[str, Any]:
    """Summarize app-provided local aggregates only."""
    return locals()


def refuse_unsupported_question(
    reason: str = "insufficient_evidence",
    safe_alternative: str = "",
    selection_basis: str = "",
    evidence_refs: list[str] | None = None,
    refusal_level: int = 2,
    next_focus: str = "",
) -> dict[str, Any]:
    """Refuse unsupported, medical, sensor-only, or insufficient-evidence requests."""
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
    create_care_activity_log,
    ask_subjective_checkin,
    record_subjective_checkin,
    create_persona_activity_report,
    select_dual_task_prompt,
    record_dual_task_result,
    read_memory,
    request_memory_update,
    summarize_trend,
    refuse_unsupported_question,
]
