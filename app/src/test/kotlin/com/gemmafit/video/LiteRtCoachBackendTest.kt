package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class LiteRtCoachBackendTest {
    @Test
    fun parserAcceptsAllowedToolCall() {
        val result = LiteRtToolCallParser.parse(
            candidates = listOf(
                LiteRtToolCandidate(
                    name = "positive_reinforcement",
                    arguments = mapOf(
                        "pattern" to "squat",
                        "streak" to 30,
                        "selection_basis" to "clean rep",
                        "evidence_refs" to listOf("knee_angle", "hip_angle"),
                        "coach_cue" to "Clean squat rep with coordinated hip and knee motion.",
                        "next_focus" to "Keep the ascent tempo controlled.",
                    ),
                )
            ),
            backend = "litert-lm:gpu",
            modelInfoJson = "{}",
            rawResponse = "raw",
            inferenceTimeMs = 42.0,
        )

        assertTrue(result.success)
        assertEquals("positive_reinforcement", result.functionName)
        assertEquals("litert-lm:gpu", result.backend)
        assertEquals("clean rep", result.selectionBasis)
        assertEquals(listOf("knee_angle", "hip_angle"), result.evidenceRefs)
        val args = JSONObject(result.argsJson)
        assertEquals(
            "Clean squat rep with coordinated hip and knee motion.",
            args.getString("coach_cue"),
        )
        assertEquals("Keep the ascent tempo controlled.", args.getString("next_focus"))
    }

    @Test
    fun parserRejectsMissingToolCall() {
        val result = LiteRtToolCallParser.parse(
            candidates = emptyList(),
            backend = "litert-lm:cpu",
            modelInfoJson = "{}",
            rawResponse = "free text",
            inferenceTimeMs = 13.0,
        )

        assertFalse(result.success)
        assertEquals("litert_lm_no_valid_tool_call", result.errorMessage)
        assertEquals("litert-lm:cpu", result.backend)
    }

    @Test
    fun parserAcceptsMemoryToolCall() {
        val result = LiteRtToolCallParser.parse(
            candidates = listOf(
                LiteRtToolCandidate(
                    name = "read_memory",
                    arguments = mapOf(
                        "scope" to "TRENDS_7D",
                        "exercise" to "chair_sit_to_stand",
                    ),
                )
            ),
            backend = "litert-lm:gpu",
            modelInfoJson = "{}",
            rawResponse = "raw",
            inferenceTimeMs = 9.0,
        )

        assertTrue(result.success)
        assertEquals("read_memory", result.functionName)
    }

    @Test
    fun resolverSelectsFirstExistingLiteRtCandidate() {
        val selected = CoachModelResolver.firstExisting(
            candidates = listOf("missing.litertlm", "models/gemmafit-v2-fc.litertlm"),
            exists = { it.startsWith("models/") },
        )

        assertEquals("models/gemmafit-v2-fc.litertlm", selected)
    }

    @Test
    fun resolverReturnsNullWhenNoLiteRtCandidateExists() {
        val selected = CoachModelResolver.firstExisting(
            candidates = listOf("a.litertlm", "b.litertlm"),
            exists = { false },
        )

        assertNull(selected)
    }
}
