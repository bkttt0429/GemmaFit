package com.gemmafit.video

/**
 * Central policy for deciding when a local model is worth invoking.
 *
 * The scheduler does not choose function names, rewrite evidence, or override
 * deterministic gates. It only decides whether a compact model call is allowed
 * for the current event.
 */
object ModelInvocationScheduler {
    fun plan(request: ModelInvocationRequest): ModelInvocationPlan {
        val multimodalEvidencePlan = MultimodalEvidenceTriggerPolicy.plan(request)

        fun ModelInvocationPlan.withMultimodalEvidencePlan(): ModelInvocationPlan {
            return copy(multimodalEvidencePlan = multimodalEvidencePlan)
        }

        if (!request.modelBackendAvailable || request.deviceState.modelDisabled) {
            return fallbackOnly("model_backend_unavailable", "deterministic_only")
                .withMultimodalEvidencePlan()
        }
        if (request.deviceState.lowBattery || request.deviceState.highThermalLoad) {
            return fallbackOnly("device_budget_limited", "deterministic_only")
                .withMultimodalEvidencePlan()
        }

        val trackingBlock = trackingBlockReason(request.personTrackingState)
        if (trackingBlock != null) {
            return skip(
                reason = trackingBlock,
                contextBudget = ModelContextBudget.NONE,
                allowedJudgment = false,
            ).withMultimodalEvidencePlan()
        }
        if (!request.capabilityJudgmentAllowed) {
            return skip(
                reason = "capability_contract_blocks_judgment",
                contextBudget = ModelContextBudget.NONE,
                allowedJudgment = false,
            ).withMultimodalEvidencePlan()
        }
        if (request.confidenceFloor < MIN_CONFIDENCE_FOR_MODEL_CONTEXT) {
            return skip(
                reason = "pose_confidence_below_model_context_threshold",
                contextBudget = ModelContextBudget.NONE,
                allowedJudgment = false,
            ).withMultimodalEvidencePlan()
        }

        return when (request.trigger) {
            ModelInvocationTrigger.LIVE_FRAME -> skip("live_frame_deterministic_path")
            ModelInvocationTrigger.SETUP_CHECK -> skip(
                reason = "setup_check_uses_deterministic_setup_ui",
                allowedJudgment = false,
            )
            ModelInvocationTrigger.REP_COMPLETED -> repCompletedPlan(request)
            ModelInvocationTrigger.WARNING_PERSISTED -> warningPlan(request)
            ModelInvocationTrigger.IMPROVEMENT_AFTER_WARNING -> callNow(
                reason = "improvement_after_warning_wording",
                contextBudget = ModelContextBudget.EVENT_COMPACT,
            )
            ModelInvocationTrigger.SESSION_MICRO_SUMMARY -> callNow(
                reason = "session_micro_summary_wording",
                contextBudget = ModelContextBudget.EVENT_COMPACT,
            )
            ModelInvocationTrigger.SUBJECT_LOST -> skip(
                reason = "subject_lost_uses_deterministic_reacquire_prompt",
                allowedJudgment = false,
            )
            ModelInvocationTrigger.USER_LEFT_ACTIVITY_AREA -> skip(
                reason = "user_left_activity_area_uses_deterministic_pause",
                allowedJudgment = false,
            )
            ModelInvocationTrigger.NO_RESPONSE_AFTER_CUE -> skip(
                reason = "no_response_after_cue_uses_deterministic_pause",
                allowedJudgment = false,
            )
            ModelInvocationTrigger.MULTI_PERSON_AMBIGUOUS -> skip(
                reason = "multi_person_ambiguous_uses_deterministic_pause",
                allowedJudgment = false,
            )
            ModelInvocationTrigger.SESSION_ENDED -> callNow(
                reason = "session_summary_event",
                contextBudget = ModelContextBudget.SESSION_COMPACT,
                reasoningMode = SummaryReasoningPolicy.select(
                    trigger = request.trigger,
                    contextBudget = ModelContextBudget.SESSION_COMPACT,
                    deviceState = request.deviceState,
                    userQuestionType = request.userQuestionType,
                ),
            )
            ModelInvocationTrigger.CAREGIVER_EXPORT -> callNow(
                reason = "caregiver_export_event",
                contextBudget = ModelContextBudget.CAREGIVER_EXPORT,
                reasoningMode = SummaryReasoningPolicy.select(
                    trigger = request.trigger,
                    contextBudget = ModelContextBudget.CAREGIVER_EXPORT,
                    deviceState = request.deviceState,
                    userQuestionType = request.userQuestionType,
                ),
            )
            ModelInvocationTrigger.USER_QUESTION -> userQuestionPlan(request)
        }.withMultimodalEvidencePlan()
    }

    private fun repCompletedPlan(request: ModelInvocationRequest): ModelInvocationPlan {
        if (request.hasCriticalOrWarningEvidence && request.needsLanguageExplanation) {
            return callNow(
                reason = "rep_completed_with_explainable_warning",
                contextBudget = ModelContextBudget.EVENT_COMPACT,
            )
        }
        return defer(
            reason = "clean_rep_summary_deferred",
            contextBudget = ModelContextBudget.EVENT_COMPACT,
        )
    }

    private fun warningPlan(request: ModelInvocationRequest): ModelInvocationPlan {
        if (!request.hasCriticalOrWarningEvidence) {
            return skip("warning_trigger_without_reliable_warning")
        }
        if (!request.needsLanguageExplanation) {
            return skip("warning_handled_by_deterministic_ui")
        }
        return callNow(
            reason = "persistent_warning_needs_explanation",
            contextBudget = ModelContextBudget.EVENT_COMPACT,
        )
    }

    private fun userQuestionPlan(request: ModelInvocationRequest): ModelInvocationPlan {
        if (
            request.userQuestionType == UserQuestionType.UNSUPPORTED_MEDICAL_OR_FORCE ||
            request.userQuestionType == UserQuestionType.UNSUPPORTED_COGNITIVE_OR_DEMENTIA
        ) {
            return callNow(
                reason = "unsupported_question_refusal_wording",
                contextBudget = ModelContextBudget.EVENT_COMPACT,
            )
        }
        return callNow(
            reason = "bounded_user_question",
            contextBudget = ModelContextBudget.SESSION_COMPACT,
        )
    }

    private fun trackingBlockReason(state: PersonTrackingState): String? {
        return when (state) {
            PersonTrackingState.OBSERVED,
            PersonTrackingState.AUTO_LOCKED,
            PersonTrackingState.SINGLE_PERSON,
            -> null
            PersonTrackingState.PREDICTED -> "tracking_predicted_monitor_only"
            PersonTrackingState.HOLD -> "tracking_hold_no_hard_judgment"
            PersonTrackingState.LOST -> "tracking_lost"
            PersonTrackingState.NO_PERSON -> "no_person_detected"
            PersonTrackingState.MULTI_PERSON_AMBIGUOUS -> "multi_person_ambiguous"
            PersonTrackingState.NEEDS_SELECTION -> "subject_selection_required"
        }
    }

    private fun skip(
        reason: String,
        contextBudget: ModelContextBudget = ModelContextBudget.NONE,
        allowedJudgment: Boolean = true,
    ): ModelInvocationPlan {
        return ModelInvocationPlan(
            decision = ModelInvocationDecision.SKIP_DETERMINISTIC,
            backendPreference = BackendPreference.DETERMINISTIC_ONLY,
            contextBudget = contextBudget,
            reason = reason,
            allowedJudgment = allowedJudgment,
        )
    }

    private fun defer(
        reason: String,
        contextBudget: ModelContextBudget,
    ): ModelInvocationPlan {
        return ModelInvocationPlan(
            decision = ModelInvocationDecision.DEFER_TO_SESSION_END,
            backendPreference = BackendPreference.DETERMINISTIC_ONLY,
            contextBudget = contextBudget,
            reason = reason,
            allowedJudgment = true,
        )
    }

    private fun callNow(
        reason: String,
        contextBudget: ModelContextBudget,
        reasoningMode: ModelReasoningMode = ModelReasoningMode.OFF,
    ): ModelInvocationPlan {
        return ModelInvocationPlan(
            decision = ModelInvocationDecision.CALL_E2B_NOW,
            backendPreference = BackendPreference.LITERT_E2B,
            contextBudget = contextBudget,
            reason = reason,
            allowedJudgment = true,
            reasoningMode = reasoningMode,
        )
    }

    private fun fallbackOnly(reason: String, backend: String): ModelInvocationPlan {
        return ModelInvocationPlan(
            decision = ModelInvocationDecision.FALLBACK_ONLY,
            backendPreference = BackendPreference.DETERMINISTIC_ONLY,
            contextBudget = ModelContextBudget.NONE,
            reason = reason,
            allowedJudgment = false,
            fallbackBackend = backend,
        )
    }

    private const val MIN_CONFIDENCE_FOR_MODEL_CONTEXT = 0.6f
}

data class ModelInvocationRequest(
    val trigger: ModelInvocationTrigger,
    val personTrackingState: PersonTrackingState = PersonTrackingState.OBSERVED,
    val confidenceFloor: Float = 1f,
    val capabilityJudgmentAllowed: Boolean = true,
    val hasCriticalOrWarningEvidence: Boolean = false,
    val needsLanguageExplanation: Boolean = false,
    val modelBackendAvailable: Boolean = true,
    val deviceState: ModelDeviceState = ModelDeviceState(),
    val userQuestionType: UserQuestionType = UserQuestionType.NONE,
    val multimodalEvidencePanelEnabled: Boolean = false,
    val multimodalBackendAvailable: Boolean = false,
    val debugRepMultimodalPanel: Boolean = false,
)

data class ModelDeviceState(
    val lowBattery: Boolean = false,
    val highThermalLoad: Boolean = false,
    val modelDisabled: Boolean = false,
)

data class ModelInvocationPlan(
    val decision: ModelInvocationDecision,
    val backendPreference: BackendPreference,
    val contextBudget: ModelContextBudget,
    val reason: String,
    val allowedJudgment: Boolean,
    val fallbackBackend: String = "",
    val reasoningMode: ModelReasoningMode = ModelReasoningMode.OFF,
    val multimodalEvidencePlan: MultimodalEvidencePlan = MultimodalEvidencePlan.disabled(),
)

object SummaryReasoningPolicy {
    fun select(
        trigger: ModelInvocationTrigger,
        contextBudget: ModelContextBudget,
        deviceState: ModelDeviceState,
        userQuestionType: UserQuestionType = UserQuestionType.NONE,
    ): ModelReasoningMode {
        if (deviceState.lowBattery || deviceState.highThermalLoad || deviceState.modelDisabled) {
            return ModelReasoningMode.OFF
        }
        if (
            userQuestionType == UserQuestionType.UNSUPPORTED_MEDICAL_OR_FORCE ||
            userQuestionType == UserQuestionType.UNSUPPORTED_COGNITIVE_OR_DEMENTIA
        ) {
            return ModelReasoningMode.OFF
        }
        return when {
            trigger == ModelInvocationTrigger.SESSION_ENDED &&
                contextBudget == ModelContextBudget.SESSION_COMPACT -> ModelReasoningMode.SUMMARY_OPTIONAL
            trigger == ModelInvocationTrigger.CAREGIVER_EXPORT &&
                contextBudget == ModelContextBudget.CAREGIVER_EXPORT -> ModelReasoningMode.SUMMARY_OPTIONAL
            contextBudget == ModelContextBudget.PROFESSIONAL_SHARE -> ModelReasoningMode.SUMMARY_OPTIONAL
            else -> ModelReasoningMode.OFF
        }
    }

    fun promptPolicy(mode: ModelReasoningMode): Map<String, Any> {
        return when (mode) {
            ModelReasoningMode.OFF -> mapOf(
                "mode" to mode.wireName,
                "allowed_scope" to "none",
                "final_output" to "single_tool_call_only",
                "instruction" to "Do not emit reasoning text. Select one tool call from app evidence.",
            )
            ModelReasoningMode.SUMMARY_OPTIONAL -> mapOf(
                "mode" to mode.wireName,
                "allowed_scope" to "session_summary_wording_only",
                "final_output" to "single_tool_call_only",
                "instruction" to (
                    "You may use internal reasoning only to organize the final summary wording. " +
                        "Do not change deterministic gates, do not include thoughts, and output only one tool call."
                    ),
            )
        }
    }
}

enum class ModelReasoningMode(val wireName: String) {
    OFF("off"),
    SUMMARY_OPTIONAL("summary_optional"),
}

enum class ModelInvocationTrigger {
    LIVE_FRAME,
    SETUP_CHECK,
    REP_COMPLETED,
    WARNING_PERSISTED,
    IMPROVEMENT_AFTER_WARNING,
    SESSION_MICRO_SUMMARY,
    SUBJECT_LOST,
    USER_LEFT_ACTIVITY_AREA,
    NO_RESPONSE_AFTER_CUE,
    MULTI_PERSON_AMBIGUOUS,
    SESSION_ENDED,
    CAREGIVER_EXPORT,
    USER_QUESTION,
}

enum class ModelInvocationDecision {
    SKIP_DETERMINISTIC,
    CALL_E2B_NOW,
    DEFER_TO_SESSION_END,
    FALLBACK_ONLY,
}

enum class BackendPreference {
    DETERMINISTIC_ONLY,
    LITERT_E2B,
}

enum class ModelContextBudget {
    NONE,
    EVENT_COMPACT,
    SESSION_COMPACT,
    CAREGIVER_EXPORT,
    PROFESSIONAL_SHARE,
}

enum class UserQuestionType {
    NONE,
    BOUNDED_COACHING,
    UNSUPPORTED_MEDICAL_OR_FORCE,
    UNSUPPORTED_COGNITIVE_OR_DEMENTIA,
}

enum class PersonTrackingState {
    OBSERVED,
    SINGLE_PERSON,
    AUTO_LOCKED,
    PREDICTED,
    HOLD,
    LOST,
    NO_PERSON,
    MULTI_PERSON_AMBIGUOUS,
    NEEDS_SELECTION,
}

internal object PersonTrackingPolicy {
    fun stateFor(
        subjectLockStatus: SubjectLockStatus?,
        subjectTrustFlags: List<String>,
    ): PersonTrackingState {
        val normalized = subjectTrustFlags.map { it.lowercase() }.toSet()
        return when {
            "no_person" in normalized -> PersonTrackingState.NO_PERSON
            "subject_lost" in normalized -> PersonTrackingState.LOST
            "multi_person_ambiguous" in normalized -> PersonTrackingState.MULTI_PERSON_AMBIGUOUS
            "needs_selection" in normalized -> PersonTrackingState.NEEDS_SELECTION
            "subject_hold" in normalized -> PersonTrackingState.HOLD
            "subject_identity_reacquiring" in normalized -> PersonTrackingState.PREDICTED
            "subject_identity_uncertain" in normalized -> PersonTrackingState.PREDICTED
            "subject_temporarily_occluded" in normalized -> PersonTrackingState.PREDICTED
            "subject_temporarily_unmatched" in normalized -> PersonTrackingState.PREDICTED
            subjectLockStatus != null -> subjectLockStatus.toPersonTrackingState()
            else -> PersonTrackingState.OBSERVED
        }
    }

    fun blocksHardJudgment(state: PersonTrackingState): Boolean {
        return state !in setOf(
            PersonTrackingState.OBSERVED,
            PersonTrackingState.AUTO_LOCKED,
            PersonTrackingState.SINGLE_PERSON,
        )
    }

    fun blocksHardJudgment(
        subjectLockStatus: SubjectLockStatus?,
        subjectTrustFlags: List<String>,
    ): Boolean = blocksHardJudgment(stateFor(subjectLockStatus, subjectTrustFlags))

    fun wireState(state: PersonTrackingState): String {
        return when (state) {
            PersonTrackingState.OBSERVED,
            PersonTrackingState.AUTO_LOCKED,
            PersonTrackingState.SINGLE_PERSON,
            -> "observed"
            PersonTrackingState.PREDICTED -> "predicted"
            PersonTrackingState.HOLD -> "hold"
            PersonTrackingState.LOST -> "lost"
            PersonTrackingState.NO_PERSON -> "no_person"
            PersonTrackingState.MULTI_PERSON_AMBIGUOUS -> "multi_person_ambiguous"
            PersonTrackingState.NEEDS_SELECTION -> "needs_selection"
        }
    }
}

fun SubjectLockStatus.toPersonTrackingState(): PersonTrackingState {
    return when (this) {
        SubjectLockStatus.LOCKED -> PersonTrackingState.OBSERVED
        SubjectLockStatus.AUTO_LOCKED -> PersonTrackingState.AUTO_LOCKED
        SubjectLockStatus.SINGLE_AUTO -> PersonTrackingState.SINGLE_PERSON
        SubjectLockStatus.SUBJECT_LOST -> PersonTrackingState.LOST
        SubjectLockStatus.NEEDS_SELECTION -> PersonTrackingState.NEEDS_SELECTION
    }
}

fun ModelInvocationPlan.toDebugMap(): Map<String, Any> {
    return mapOf(
        "decision" to decision.name,
        "backend_preference" to backendPreference.name,
        "context_budget" to contextBudget.name,
        "reason" to reason,
        "allowed_judgment" to allowedJudgment,
        "fallback_backend" to fallbackBackend,
        "reasoning_mode" to reasoningMode.wireName,
        "multimodal_evidence_plan" to multimodalEvidencePlan.toDebugMap(),
    )
}

fun ModelInvocationRequest.toDebugMap(): Map<String, Any> {
    return mapOf(
        "trigger" to trigger.name,
        "person_tracking_state" to personTrackingState.name,
        "confidence_floor" to confidenceFloor,
        "capability_judgment_allowed" to capabilityJudgmentAllowed,
        "has_critical_or_warning_evidence" to hasCriticalOrWarningEvidence,
        "needs_language_explanation" to needsLanguageExplanation,
        "model_backend_available" to modelBackendAvailable,
        "device_low_battery" to deviceState.lowBattery,
        "device_high_thermal_load" to deviceState.highThermalLoad,
        "model_disabled" to deviceState.modelDisabled,
        "user_question_type" to userQuestionType.name,
        "multimodal_evidence_panel_enabled" to multimodalEvidencePanelEnabled,
        "multimodal_backend_available" to multimodalBackendAvailable,
        "debug_rep_multimodal_panel" to debugRepMultimodalPanel,
    )
}
