package com.gemmafit.senior

import com.gemmafit.video.Layer2ActivityHypothesis
import com.gemmafit.video.Layer2Event
import com.gemmafit.video.Layer2Output
import com.gemmafit.video.Layer2Phase
import com.gemmafit.video.Layer2RulePolicy
import com.gemmafit.video.PersonTrackingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeniorInteractionPolicyTest {
    @Test
    fun noResponseAfterCuePausesForSupport() {
        val policy = SeniorInteractionPolicy(
            noResponseTimeoutMs = 10_000L,
            repeatCueAfterMs = 5_000L,
        )

        val decision = policy.evaluate(
            SeniorInteractionInput(
                mode = SeniorUseMode.DEMENTIA_FRIENDLY_SELF_GUIDED,
                layer2Output = cleanRepOutput(),
                nowMs = 10_001L,
                lastCueAtMs = 0L,
                cueAwaitingResponse = true,
            ),
        )

        assertEquals(SeniorInteractionAction.PAUSE_FOR_SUPPORT, decision.action)
        assertEquals(SeniorSupportState.NO_RESPONSE_AFTER_CUE, decision.supportState)
        assertEquals("no_response_pause", decision.supportEventKey)
        assertFalse(decision.allowHardCoaching)
        assertFalse(decision.allowModelInvocation)
    }

    @Test
    fun cueRepeatsBeforeNoResponseTimeout() {
        val policy = SeniorInteractionPolicy(
            noResponseTimeoutMs = 10_000L,
            repeatCueAfterMs = 5_000L,
        )

        val decision = policy.evaluate(
            SeniorInteractionInput(
                mode = SeniorUseMode.DEMENTIA_FRIENDLY_SELF_GUIDED,
                layer2Output = cleanRepOutput(),
                nowMs = 5_001L,
                lastCueAtMs = 0L,
                cueAwaitingResponse = true,
                pendingCueKey = SeniorInteractionPolicy.Cue.REPEAT_SIMPLE,
            ),
        )

        assertEquals(SeniorInteractionAction.REPEAT_SIMPLE_CUE, decision.action)
        assertEquals(SeniorSupportState.READY, decision.supportState)
        assertEquals("repeated_cue", decision.supportEventKey)
        assertFalse(decision.allowHardCoaching)
    }

    @Test
    fun userLeftActivityAreaPausesWithoutModel() {
        val decision = SeniorInteractionPolicy().evaluate(
            SeniorInteractionInput(
                mode = SeniorUseMode.DEMENTIA_FRIENDLY_SELF_GUIDED,
                layer2Output = cleanRepOutput(),
                personTrackingState = PersonTrackingState.NO_PERSON,
                nowMs = 1_000L,
            ),
        )

        assertEquals(SeniorInteractionAction.PAUSE_FOR_SUPPORT, decision.action)
        assertEquals(SeniorSupportState.USER_LEFT_ACTIVITY_AREA, decision.supportState)
        assertEquals(SeniorInteractionPolicy.Cue.STEP_BACK_INTO_VIEW, decision.cueKey)
        assertFalse(decision.allowHardCoaching)
        assertFalse(decision.allowModelInvocation)
    }

    @Test
    fun multiPersonAmbiguityPausesForSetup() {
        val decision = SeniorInteractionPolicy().evaluate(
            SeniorInteractionInput(
                mode = SeniorUseMode.DEMENTIA_FRIENDLY_SELF_GUIDED,
                layer2Output = cleanRepOutput(),
                personTrackingState = PersonTrackingState.MULTI_PERSON_AMBIGUOUS,
                nowMs = 1_000L,
            ),
        )

        assertEquals(SeniorInteractionAction.PAUSE_FOR_SETUP, decision.action)
        assertEquals(SeniorSupportState.MULTI_PERSON_AMBIGUOUS, decision.supportState)
        assertEquals("multi_person_ambiguity_pause", decision.supportEventKey)
        assertFalse(decision.allowHardCoaching)
    }

    @Test
    fun cleanSitToStandCanContinue() {
        val decision = SeniorInteractionPolicy().evaluate(
            SeniorInteractionInput(
                mode = SeniorUseMode.DEMENTIA_FRIENDLY_SELF_GUIDED,
                layer2Output = cleanRepOutput(),
                personTrackingState = PersonTrackingState.OBSERVED,
                nowMs = 1_000L,
            ),
        )

        assertEquals(SeniorInteractionAction.CONTINUE_SESSION, decision.action)
        assertEquals(SeniorSupportState.READY, decision.supportState)
        assertTrue(decision.allowHardCoaching)
        assertTrue(decision.allowModelInvocation)
    }

    @Test
    fun layer2AbstainPausesForSetup() {
        val decision = SeniorInteractionPolicy().evaluate(
            SeniorInteractionInput(
                mode = SeniorUseMode.DEMENTIA_FRIENDLY_SELF_GUIDED,
                layer2Output = cleanRepOutput(
                    phase = Layer2Phase.ABSTAIN,
                    event = Layer2Event.ABSTAIN,
                    confidence = 0f,
                    abstainReason = "low_pose_confidence",
                ),
                personTrackingState = PersonTrackingState.OBSERVED,
                nowMs = 1_000L,
            ),
        )

        assertEquals(SeniorInteractionAction.PAUSE_FOR_SETUP, decision.action)
        assertEquals(SeniorSupportState.SETUP_NEEDED, decision.supportState)
        assertEquals("setup_needed_pause", decision.supportEventKey)
    }

    private fun cleanRepOutput(
        phase: Layer2Phase = Layer2Phase.STANDING_STABILIZED,
        event: Layer2Event = Layer2Event.REP_COMPLETED,
        confidence: Float = 0.9f,
        abstainReason: String? = null,
    ): Layer2Output {
        return Layer2Output(
            timestampMs = 1_000L,
            activityHypothesis = Layer2ActivityHypothesis(
                label = "chair_sit_to_stand",
                confidence = 0.92f,
                source = listOf("test"),
            ),
            phase = phase,
            event = event,
            confidence = confidence,
            abstainReason = abstainReason,
            repCount = 1,
            rulePolicy = if (event == Layer2Event.ABSTAIN) {
                Layer2RulePolicy.abstain(abstainReason ?: "test_abstain")
            } else {
                Layer2RulePolicy.controlledStrength()
            },
            evidenceRefs = listOf(
                "layer2.activity.chair_sit_to_stand",
                "layer2.event.${event.wireName}",
            ),
        )
    }
}
