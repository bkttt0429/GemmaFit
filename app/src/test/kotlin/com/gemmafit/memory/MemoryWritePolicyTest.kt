package com.gemmafit.memory

import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWritePolicyTest {
    @Test
    fun rejects_medical_claim() {
        val decision = MemoryWritePolicy().evaluate(
            trendRequest(
                proposedValue = mapOf(
                    "trend_note" to TrendNote.TEMPO_STABLE.name,
                    "note" to "possible sarcopenia pattern",
                ),
            ),
        )

        assertRejected(decision, "refusal:")
    }

    @Test
    fun rejects_cognitive_or_dementia_claim() {
        val decision = MemoryWritePolicy().evaluate(
            trendRequest(
                proposedValue = mapOf(
                    "trend_note" to TrendNote.TEMPO_STABLE.name,
                    "note" to "possible cognitive decline pattern",
                ),
            ),
        )

        assertRejected(decision, "refusal:")
    }

    @Test
    fun rejects_trend_note_without_evidence_ids() {
        val decision = MemoryWritePolicy().evaluate(
            trendRequest(evidenceIds = emptyList()),
        )

        assertRejected(decision, "provenance:trend_note_requires_evidence")
    }

    @Test
    fun rejects_low_confidence_trend_note() {
        val decision = MemoryWritePolicy().evaluate(
            trendRequest(confidence = 0.59),
        )

        assertRejected(decision, "confidence_floor:")
    }

    @Test
    fun accepts_well_formed_profile_update() {
        val decision = MemoryWritePolicy().evaluate(
            MemoryUpdateRequest(
                requestId = "profile-1",
                type = MemoryUpdateType.PROFILE,
                proposedValue = mapOf("language" to "zh-TW", "font_scale" to 2.0),
                evidenceIds = emptyList(),
                confidence = 0.8,
            ),
        )

        assertTrue(decision is MemoryWritePolicy.Decision.Accepted)
    }

    @Test
    fun accepts_care_activity_log_with_evidence_and_boundary_language() {
        val decision = MemoryWritePolicy().evaluate(
            MemoryUpdateRequest(
                requestId = "care-log-1",
                type = MemoryUpdateType.CARE_ACTIVITY_LOG,
                proposedValue = mapOf(
                    "session_id" to "session-1",
                    "activity" to "chair_sit_to_stand",
                    "headline" to "Completed chair session",
                    "what_was_completed" to "Completed 12 reps.",
                    "observations" to "Tempo was steady.",
                    "not_judged" to "This does not assess fall risk or sarcopenia.",
                    "next_session_focus" to "Use the same controlled pace.",
                ),
                evidenceIds = listOf("metric.senior.reps"),
                confidence = 0.82,
            ),
        )

        assertTrue(decision is MemoryWritePolicy.Decision.Accepted)
    }

    @Test
    fun rejects_dual_task_result_without_evidence() {
        val decision = MemoryWritePolicy().evaluate(
            MemoryUpdateRequest(
                requestId = "dual-task-1",
                type = MemoryUpdateType.DUAL_TASK_RESULT,
                proposedValue = mapOf(
                    "prompt_id" to "prompt-1",
                    "response_mode" to "gesture",
                    "answer_matched" to true,
                    "movement_completed" to true,
                ),
                evidenceIds = emptyList(),
                confidence = 0.9,
            ),
        )

        assertRejected(decision, "provenance:dual_task_result_requires_evidence")
    }

    @Test
    fun idempotent_request_id_returns_no_op() {
        val policy = MemoryWritePolicy()
        val first = policy.evaluate(trendRequest(requestId = "same-id"))
        val second = policy.evaluate(trendRequest(requestId = "same-id"))

        assertTrue(first is MemoryWritePolicy.Decision.Accepted)
        assertTrue(second is MemoryWritePolicy.Decision.Idempotent)
    }

    private fun trendRequest(
        requestId: String = "trend-1",
        proposedValue: Map<String, Any?> = mapOf("trend_note" to TrendNote.TEMPO_STABLE.name),
        evidenceIds: List<String> = listOf("ev-1"),
        confidence: Double = 0.8,
    ) = MemoryUpdateRequest(
        requestId = requestId,
        type = MemoryUpdateType.TREND_NOTE,
        proposedValue = proposedValue,
        evidenceIds = evidenceIds,
        confidence = confidence,
    )

    private fun assertRejected(decision: MemoryWritePolicy.Decision, reasonPrefix: String) {
        assertTrue(decision is MemoryWritePolicy.Decision.Rejected)
        decision as MemoryWritePolicy.Decision.Rejected
        assertTrue(decision.reason.startsWith(reasonPrefix))
    }
}
