package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalResultValidatorTest {
    @Test
    fun acceptsAllowedFunctionWithPacketEvidenceRef() {
        val packet = packet()
        val validated = MultimodalResultValidator.validate(
            packet,
            result(
                functionName = "answer_evidence_question",
                argsJson = """{"answer":"The bottom frame shows the hips lowest in the strip.","evidence_refs":["metric.rep.bottom"]}""",
                evidenceRefs = listOf("metric.rep.bottom"),
            ),
        )

        assertTrue(validated.success)
        assertEquals(listOf("metric.rep.bottom"), validated.evidenceRefs)
    }

    @Test
    fun rejectsMissingEvidenceRefs() {
        val validated = MultimodalResultValidator.validate(
            packet(),
            result(
                functionName = "answer_evidence_question",
                argsJson = """{"answer":"The chair is visible.","evidence_refs":["fake.ref"]}""",
                evidenceRefs = listOf("fake.ref"),
            ),
        )

        assertEquals(false, validated.success)
        assertEquals("invalid_evidence_refs", validated.errorMessage)
    }

    @Test
    fun rejectsMedicalFallRiskSarcopeniaAndForceClaims() {
        val claims = listOf(
            "This shows a fall risk improvement.",
            "This indicates sarcopenia progress.",
            "The joint force increased during the ascent.",
            "Training load was high in this rep.",
        )

        claims.forEach { claim ->
            val validated = MultimodalResultValidator.validate(
                packet(),
                result(
                    functionName = "create_multimodal_session_summary",
                    argsJson = """{"summary":"$claim","evidence_refs":["metric.rep.bottom"]}""",
                    evidenceRefs = listOf("metric.rep.bottom"),
                ),
            )

            assertEquals("claim should be rejected: $claim", false, validated.success)
            assertEquals("forbidden_claim_detected", validated.errorMessage)
        }
    }

    @Test
    fun rejectsDeterministicVerdictChangeAndNewWarning() {
        val changedVerdict = MultimodalResultValidator.validate(
            packet(deterministicVerdict = "OK"),
            result(
                argsJson = """{"answer":"I would mark this as WARNING.","verdict":"WARNING","evidence_refs":["metric.rep.bottom"]}""",
                evidenceRefs = listOf("metric.rep.bottom"),
            ),
        )
        val newWarning = MultimodalResultValidator.validate(
            packet(),
            result(
                argsJson = """{"answer":"New warning added.","creates_warning":true,"evidence_refs":["metric.rep.bottom"]}""",
                evidenceRefs = listOf("metric.rep.bottom"),
            ),
        )

        assertEquals("deterministic_verdict_changed", changedVerdict.errorMessage)
        assertEquals("new_warning_not_allowed", newWarning.errorMessage)
    }

    @Test
    fun lowConfidencePacketRequiresUncertaintyOrBetterViewWording() {
        val lowPacket = packet(panelConfidence = FrameEvidenceSelector.CONFIDENCE_LOW)
        val overconfident = MultimodalResultValidator.validate(
            lowPacket,
            result(
                argsJson = """{"answer":"The scene clearly shows stable chair support.","evidence_refs":["metric.rep.bottom"]}""",
                evidenceRefs = listOf("metric.rep.bottom"),
            ),
        )
        val uncertain = MultimodalResultValidator.validate(
            lowPacket,
            result(
                argsJson = """{"answer":"The camera view is limited, so a better view is needed before explaining the scene.","evidence_refs":["metric.rep.bottom"]}""",
                evidenceRefs = listOf("metric.rep.bottom"),
            ),
        )

        assertEquals("low_confidence_requires_uncertainty", overconfident.errorMessage)
        assertTrue(uncertain.success)
    }

    private fun packet(
        deterministicVerdict: String = "OK",
        panelConfidence: String = FrameEvidenceSelector.CONFIDENCE_HIGH,
    ): MultimodalEvidencePacket {
        val selected = SelectedEvidenceFrames(
            sceneAnchor = candidate(1, "scene", listOf("frame.scene")),
            top = candidate(2, "top", listOf("metric.rep.top")),
            descent = candidate(3, "descent", listOf("metric.rep.descent")),
            bottom = candidate(4, "bottom", listOf("metric.rep.bottom")),
            ascent = candidate(5, "ascent", listOf("metric.rep.ascent")),
            warningFrame = candidate(6, "unknown", listOf("warning.knee_alignment"), true, listOf("knee_alignment")),
            panelConfidence = panelConfidence,
            selectionBasis = listOf("phase_aware_keyframes"),
        )
        return MultimodalEvidencePacket(
            trigger = ModelInvocationTrigger.SESSION_ENDED,
            selectedFrames = selected,
            deterministicVerdict = deterministicVerdict,
            deterministicReason = "Deterministic evidence card already completed.",
            evidenceRefs = listOf("metric.rep.bottom"),
            createdAtMs = 1L,
        )
    }

    private fun candidate(
        index: Int,
        phase: String,
        refs: List<String>,
        hasWarning: Boolean = false,
        warningIds: List<String> = emptyList(),
    ): FrameEvidenceCandidate {
        return FrameEvidenceCandidate(
            frameIndex = index,
            timestampMs = index * 100L,
            phase = phase,
            poseConfidence = 0.9f,
            fullBodyVisibility = 0.9f,
            subjectObserved = true,
            subjectStable = true,
            hipY = 0.5f,
            hipVelocityY = 0f,
            blurScore = 0.05f,
            hasWarning = hasWarning,
            warningIds = warningIds,
            evidenceRefs = refs,
        )
    }

    private fun result(
        functionName: String = "answer_evidence_question",
        argsJson: String,
        evidenceRefs: List<String>,
    ): LLMBridge.FunctionCallResult {
        return LLMBridge.FunctionCallResult(
            success = true,
            functionName = functionName,
            argsJson = argsJson,
            backend = "multimodal-test",
            selectionBasis = "",
            evidenceRefs = evidenceRefs,
            modelInfoJson = "{}",
            rawResponse = "",
            inferenceTimeMs = 0.0,
            errorMessage = "",
        )
    }
}
