package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInvocationSchedulerTest {
    @Test
    fun liveFramesStayOnDeterministicPath() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(trigger = ModelInvocationTrigger.LIVE_FRAME)
        )

        assertEquals(ModelInvocationDecision.SKIP_DETERMINISTIC, plan.decision)
        assertEquals(ModelContextBudget.NONE, plan.contextBudget)
        assertTrue(plan.allowedJudgment)
        assertEquals(ModelReasoningMode.OFF, plan.reasoningMode)
    }

    @Test
    fun liveFramesNeverTriggerMultimodalPanelOrBackend() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.LIVE_FRAME,
                multimodalEvidencePanelEnabled = true,
                multimodalBackendAvailable = true,
            )
        )

        assertEquals(ModelInvocationDecision.SKIP_DETERMINISTIC, plan.decision)
        assertEquals(MultimodalEvidenceAction.SKIP, plan.multimodalEvidencePlan.action)
        assertFalse(plan.multimodalEvidencePlan.buildPanel)
        assertFalse(plan.multimodalEvidencePlan.callBackend)
        assertEquals("live_frame_never_uses_multimodal", plan.multimodalEvidencePlan.reason)
    }

    @Test
    fun setupCheckDoesNotUseMultimodalBackendInV1() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.SETUP_CHECK,
                multimodalEvidencePanelEnabled = true,
                multimodalBackendAvailable = true,
            )
        )

        assertEquals(ModelInvocationDecision.SKIP_DETERMINISTIC, plan.decision)
        assertFalse(plan.allowedJudgment)
        assertEquals("setup_check_uses_deterministic_setup_ui", plan.reason)
        assertEquals(MultimodalEvidenceAction.SKIP, plan.multimodalEvidencePlan.action)
        assertFalse(plan.multimodalEvidencePlan.buildPanel)
        assertFalse(plan.multimodalEvidencePlan.callBackend)
        assertEquals("setup_check_not_multimodal_backend_trigger", plan.multimodalEvidencePlan.reason)
    }

    @Test
    fun warningPersistedCanUseOptionalMultimodalSidecar() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.WARNING_PERSISTED,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
                multimodalEvidencePanelEnabled = true,
                multimodalBackendAvailable = true,
            )
        )

        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, plan.decision)
        assertEquals(MultimodalEvidenceAction.CALL_BACKEND, plan.multimodalEvidencePlan.action)
        assertEquals("warning_persisted_optional_sidecar", plan.multimodalEvidencePlan.reason)
    }

    @Test
    fun sessionEndedCanBuildMultimodalPanelWithoutBackend() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.SESSION_ENDED,
                multimodalEvidencePanelEnabled = true,
                multimodalBackendAvailable = false,
            )
        )

        assertEquals(MultimodalEvidenceAction.BUILD_PANEL_ONLY, plan.multimodalEvidencePlan.action)
        assertTrue(plan.multimodalEvidencePlan.buildPanel)
        assertFalse(plan.multimodalEvidencePlan.callBackend)
    }

    @Test
    fun cleanRepIsDeferredToSessionSummary() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(trigger = ModelInvocationTrigger.REP_COMPLETED)
        )

        assertEquals(ModelInvocationDecision.DEFER_TO_SESSION_END, plan.decision)
        assertEquals(ModelContextBudget.EVENT_COMPACT, plan.contextBudget)
        assertEquals(ModelReasoningMode.OFF, plan.reasoningMode)
    }

    @Test
    fun warningRepCanCallE2BWithCompactContext() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.REP_COMPLETED,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
            )
        )

        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, plan.decision)
        assertEquals(BackendPreference.LITERT_E2B, plan.backendPreference)
        assertEquals(ModelContextBudget.EVENT_COMPACT, plan.contextBudget)
        assertEquals(ModelReasoningMode.OFF, plan.reasoningMode)
    }

    @Test
    fun predictedTrackingBlocksHardJudgmentAndModelCall() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.REP_COMPLETED,
                personTrackingState = PersonTrackingState.PREDICTED,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
            )
        )

        assertEquals(ModelInvocationDecision.SKIP_DETERMINISTIC, plan.decision)
        assertFalse(plan.allowedJudgment)
        assertEquals("tracking_predicted_monitor_only", plan.reason)
    }

    @Test
    fun sessionEndUsesSessionCompactContext() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(trigger = ModelInvocationTrigger.SESSION_ENDED)
        )

        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, plan.decision)
        assertEquals(ModelContextBudget.SESSION_COMPACT, plan.contextBudget)
        assertEquals(ModelReasoningMode.SUMMARY_OPTIONAL, plan.reasoningMode)
    }

    @Test
    fun caregiverExportCanUseSummaryOptionalReasoning() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(trigger = ModelInvocationTrigger.CAREGIVER_EXPORT)
        )

        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, plan.decision)
        assertEquals(ModelContextBudget.CAREGIVER_EXPORT, plan.contextBudget)
        assertEquals(ModelReasoningMode.SUMMARY_OPTIONAL, plan.reasoningMode)
    }

    @Test
    fun unsupportedQuestionUsesModelForBoundedRefusalWhenAvailable() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.USER_QUESTION,
                userQuestionType = UserQuestionType.UNSUPPORTED_MEDICAL_OR_FORCE,
            )
        )

        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, plan.decision)
        assertEquals("unsupported_question_refusal_wording", plan.reason)
        assertEquals(ModelReasoningMode.OFF, plan.reasoningMode)
    }

    @Test
    fun unsupportedCognitiveQuestionUsesBoundedRefusalWhenAvailable() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.USER_QUESTION,
                userQuestionType = UserQuestionType.UNSUPPORTED_COGNITIVE_OR_DEMENTIA,
            )
        )

        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, plan.decision)
        assertEquals("unsupported_question_refusal_wording", plan.reason)
        assertEquals(ModelReasoningMode.OFF, plan.reasoningMode)
    }

    @Test
    fun dementiaFriendlySupportTriggersStayDeterministic() {
        val triggers = listOf(
            ModelInvocationTrigger.USER_LEFT_ACTIVITY_AREA,
            ModelInvocationTrigger.NO_RESPONSE_AFTER_CUE,
            ModelInvocationTrigger.MULTI_PERSON_AMBIGUOUS,
        )

        triggers.forEach { trigger ->
            val plan = ModelInvocationScheduler.plan(
                ModelInvocationRequest(
                    trigger = trigger,
                    hasCriticalOrWarningEvidence = true,
                    needsLanguageExplanation = true,
                )
            )

            assertEquals(ModelInvocationDecision.SKIP_DETERMINISTIC, plan.decision)
            assertEquals(ModelContextBudget.NONE, plan.contextBudget)
            assertFalse(plan.allowedJudgment)
            assertEquals(ModelReasoningMode.OFF, plan.reasoningMode)
        }
    }

    @Test
    fun lowBatteryDisablesSummaryReasoningThroughFallback() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.SESSION_ENDED,
                deviceState = ModelDeviceState(lowBattery = true),
            )
        )

        assertEquals(ModelInvocationDecision.FALLBACK_ONLY, plan.decision)
        assertEquals(ModelReasoningMode.OFF, plan.reasoningMode)
    }

    @Test
    fun disabledModelFallsBackWithoutContext() {
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.SESSION_ENDED,
                modelBackendAvailable = false,
            )
        )

        assertEquals(ModelInvocationDecision.FALLBACK_ONLY, plan.decision)
        assertEquals(ModelContextBudget.NONE, plan.contextBudget)
        assertFalse(plan.allowedJudgment)
    }
}
