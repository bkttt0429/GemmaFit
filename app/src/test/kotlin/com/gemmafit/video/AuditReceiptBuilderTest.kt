package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditReceiptBuilderTest {
    @Test
    fun localGemmaSessionBuildsReadableReceipt() {
        val receipt = AuditReceiptBuilder.from(
            SessionSummary(
                totalFrames = 100,
                totalReps = 12,
                avgFormScore = 86f,
                evidenceRefs = listOf("metric.motion.rep.completed", "metric.motion.form_score"),
                sessionCoachInsight = SessionCoachInsight(
                    whatISaw = "Tempo stayed controlled.",
                    backend = "litert-lm:cpu",
                    functionName = "create_persona_activity_report",
                    evidenceRefs = listOf("metric.motion.rep.completed"),
                    fallback = false,
                ),
            )
        )

        assertEquals("Pose rules + Local Gemma", receipt.sourceSummary)
        assertEquals("litert-lm:cpu", receipt.backend)
        assertFalse(receipt.fallback)
        assertTrue(receipt.claims.any { it.text.contains("Completed 12 reps") })
        assertTrue(receipt.basedOnEvidenceRefs.contains("metric.motion.rep.completed"))
    }

    @Test
    fun fallbackSessionIncludesBoundariesAndConfidenceNotes() {
        val receipt = AuditReceiptBuilder.from(
            SessionSummary(
                totalFrames = 10,
                lowConfidenceCount = 2,
                viewLimitedCount = 1,
                sessionCoachInsight = SessionCoachInsight(
                    backend = "fallback",
                    fallback = true,
                ),
            )
        )

        assertEquals("Pose rules + Template fallback", receipt.sourceSummary)
        assertTrue(receipt.fallback)
        assertTrue(receipt.notJudged.contains("medical diagnosis"))
        assertTrue(receipt.notJudged.contains("fall risk prediction"))
        assertTrue(receipt.confidenceNotes.any { it.contains("low pose confidence") })
        assertTrue(receipt.boundaryNote.contains("not a medical diagnosis"))
    }
}
