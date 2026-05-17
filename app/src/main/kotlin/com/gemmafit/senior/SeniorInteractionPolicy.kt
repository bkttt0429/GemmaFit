package com.gemmafit.senior

import androidx.annotation.Keep
import com.gemmafit.video.Layer2Event
import com.gemmafit.video.Layer2Output
import com.gemmafit.video.Layer2Phase
import com.gemmafit.video.PersonTrackingState

/**
 * Deterministic interaction policy for senior self-guided flows.
 *
 * This layer never infers cognition, confusion, wandering, diagnosis, or risk.
 * It only maps observable app state to simple UI/TTS actions.
 */
class SeniorInteractionPolicy(
    private val noResponseTimeoutMs: Long = DEFAULT_NO_RESPONSE_TIMEOUT_MS,
    private val repeatCueAfterMs: Long = DEFAULT_REPEAT_CUE_AFTER_MS,
) {
    fun evaluate(input: SeniorInteractionInput): SeniorInteractionDecision {
        if (input.userEndedSession) {
            return decision(
                action = SeniorInteractionAction.END_SESSION_SUMMARY,
                state = SeniorSupportState.READY,
                cueKey = Cue.SESSION_SUMMARY,
                reason = "user_ended_session",
                allowModelInvocation = true,
            )
        }

        if (input.mode != SeniorUseMode.DEMENTIA_FRIENDLY_SELF_GUIDED) {
            return decision(
                action = SeniorInteractionAction.CONTINUE_SESSION,
                state = SeniorSupportState.READY,
                cueKey = Cue.CONTINUE,
                reason = "standard_senior_mode",
                allowModelInvocation = true,
            )
        }

        trackingSupportDecision(input)?.let { return it }
        setupDecision(input)?.let { return it }
        responseTimingDecision(input)?.let { return it }

        return decision(
            action = SeniorInteractionAction.CONTINUE_SESSION,
            state = SeniorSupportState.READY,
            cueKey = Cue.CONTINUE,
            reason = "ready",
            allowModelInvocation = true,
        )
    }

    private fun trackingSupportDecision(input: SeniorInteractionInput): SeniorInteractionDecision? {
        return when (input.personTrackingState) {
            PersonTrackingState.NO_PERSON,
            PersonTrackingState.LOST,
            -> decision(
                action = SeniorInteractionAction.PAUSE_FOR_SUPPORT,
                state = SeniorSupportState.USER_LEFT_ACTIVITY_AREA,
                cueKey = Cue.STEP_BACK_INTO_VIEW,
                reason = "user_left_activity_area",
            )
            PersonTrackingState.MULTI_PERSON_AMBIGUOUS,
            PersonTrackingState.NEEDS_SELECTION,
            -> decision(
                action = SeniorInteractionAction.PAUSE_FOR_SETUP,
                state = SeniorSupportState.MULTI_PERSON_AMBIGUOUS,
                cueKey = Cue.ONE_PERSON_ONLY,
                reason = "multi_person_ambiguous",
            )
            PersonTrackingState.PREDICTED,
            PersonTrackingState.HOLD,
            -> decision(
                action = SeniorInteractionAction.PAUSE_FOR_SETUP,
                state = SeniorSupportState.SETUP_NEEDED,
                cueKey = Cue.SETUP_CHECK,
                reason = "tracking_not_observed",
            )
            PersonTrackingState.OBSERVED,
            PersonTrackingState.SINGLE_PERSON,
            PersonTrackingState.AUTO_LOCKED,
            -> null
        }
    }

    private fun setupDecision(input: SeniorInteractionInput): SeniorInteractionDecision? {
        val layer2 = input.layer2Output ?: return null
        val blocked = layer2.event == Layer2Event.ABSTAIN ||
            layer2.phase == Layer2Phase.ABSTAIN ||
            layer2.confidence < MIN_READY_CONFIDENCE
        if (!blocked) return null

        return decision(
            action = SeniorInteractionAction.PAUSE_FOR_SETUP,
            state = SeniorSupportState.SETUP_NEEDED,
            cueKey = Cue.SETUP_CHECK,
            reason = layer2.abstainReason ?: "evidence_not_ready",
        )
    }

    private fun responseTimingDecision(input: SeniorInteractionInput): SeniorInteractionDecision? {
        if (!input.cueAwaitingResponse) return null
        val lastCueAtMs = input.lastCueAtMs ?: return null
        if (input.lastUserResponseAtMs != null && input.lastUserResponseAtMs >= lastCueAtMs) {
            return null
        }

        val elapsed = input.nowMs - lastCueAtMs
        if (elapsed >= noResponseTimeoutMs) {
            return decision(
                action = SeniorInteractionAction.PAUSE_FOR_SUPPORT,
                state = SeniorSupportState.NO_RESPONSE_AFTER_CUE,
                cueKey = Cue.PAUSE_FOR_SUPPORT,
                reason = "no_response_after_cue",
            )
        }
        if (elapsed >= repeatCueAfterMs) {
            return decision(
                action = SeniorInteractionAction.REPEAT_SIMPLE_CUE,
                state = SeniorSupportState.READY,
                cueKey = input.pendingCueKey ?: Cue.REPEAT_SIMPLE,
                reason = "repeat_simple_cue",
                allowHardCoaching = false,
            )
        }
        return null
    }

    private fun decision(
        action: SeniorInteractionAction,
        state: SeniorSupportState,
        cueKey: String,
        reason: String,
        allowHardCoaching: Boolean = action == SeniorInteractionAction.CONTINUE_SESSION,
        allowModelInvocation: Boolean = false,
    ): SeniorInteractionDecision {
        return SeniorInteractionDecision(
            action = action,
            supportState = state,
            cueKey = cueKey,
            reason = reason,
            allowHardCoaching = allowHardCoaching,
            allowModelInvocation = allowModelInvocation,
            supportEventKey = supportEventKeyFor(state, action),
        )
    }

    private fun supportEventKeyFor(
        state: SeniorSupportState,
        action: SeniorInteractionAction,
    ): String? {
        return when {
            action == SeniorInteractionAction.REPEAT_SIMPLE_CUE -> "repeated_cue"
            state == SeniorSupportState.SETUP_NEEDED -> "setup_needed_pause"
            state == SeniorSupportState.USER_LEFT_ACTIVITY_AREA -> "left_activity_area_pause"
            state == SeniorSupportState.NO_RESPONSE_AFTER_CUE -> "no_response_pause"
            state == SeniorSupportState.MULTI_PERSON_AMBIGUOUS -> "multi_person_ambiguity_pause"
            state == SeniorSupportState.SESSION_PAUSED_FOR_SUPPORT -> "session_paused_for_support"
            else -> null
        }
    }

    object Cue {
        const val CONTINUE = "senior_continue"
        const val REPEAT_SIMPLE = "senior_repeat_simple_cue"
        const val SETUP_CHECK = "senior_setup_check"
        const val STEP_BACK_INTO_VIEW = "senior_step_back_into_view"
        const val ONE_PERSON_ONLY = "senior_one_person_only"
        const val PAUSE_FOR_SUPPORT = "senior_pause_for_support"
        const val SESSION_SUMMARY = "senior_session_summary"
    }

    companion object {
        const val DEFAULT_REPEAT_CUE_AFTER_MS = 8_000L
        const val DEFAULT_NO_RESPONSE_TIMEOUT_MS = 15_000L
        private const val MIN_READY_CONFIDENCE = 0.55f
    }
}

@Keep
data class SeniorInteractionInput(
    val mode: SeniorUseMode = SeniorUseMode.STANDARD_SELF_GUIDED,
    val layer2Output: Layer2Output? = null,
    val personTrackingState: PersonTrackingState = PersonTrackingState.OBSERVED,
    val nowMs: Long,
    val lastCueAtMs: Long? = null,
    val lastUserResponseAtMs: Long? = null,
    val cueAwaitingResponse: Boolean = false,
    val pendingCueKey: String? = null,
    val userEndedSession: Boolean = false,
)

@Keep
data class SeniorInteractionDecision(
    val action: SeniorInteractionAction,
    val supportState: SeniorSupportState,
    val cueKey: String,
    val reason: String,
    val allowHardCoaching: Boolean,
    val allowModelInvocation: Boolean,
    val supportEventKey: String? = null,
)
