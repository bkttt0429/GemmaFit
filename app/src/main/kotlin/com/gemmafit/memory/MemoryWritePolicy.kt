package com.gemmafit.memory

import org.json.JSONObject

/**
 * The trust core: decides whether a Gemma-proposed memory write is
 * accepted, rejected, or needs human review.
 *
 * Gemma never bypasses this — every state mutation goes through
 * [MemoryWritePolicy.evaluate]. Audit entries are written for both
 * accepted and rejected outcomes (see [MemoryStore.audit]).
 *
 * Validation steps (implementation_plan.md §11.6):
 *   1. Schema validation
 *   2. Provenance: TREND_NOTE requires ≥ 1 evidence_id
 *   3. Refusal regex via [RefusalValidator]
 *   4. Confidence floor (TREND_NOTE: ≥ 0.6)
 *   5. Idempotency
 *   6. Audit (caller responsibility — pass result to MemoryStore.audit)
 */
class MemoryWritePolicy(
    private val seenRequestIds: MutableSet<String> = mutableSetOf(),
    private val confidenceFloorTrend: Double = 0.6,
) {

    /**
     * Pure decision function. Side effects (storing, auditing) are the
     * caller's responsibility — this keeps the policy testable.
     */
    fun evaluate(req: MemoryUpdateRequest): Decision {
        // Step 1: schema validation
        val schemaError = validateSchema(req)
        if (schemaError != null) {
            return Decision.Rejected("schema:$schemaError")
        }

        // Step 2: provenance
        if (req.type.requiresEvidenceIds() && req.evidenceIds.isEmpty()) {
            return Decision.Rejected("provenance:${req.type.name.lowercase()}_requires_evidence")
        }

        // Step 3: refusal regex over the stringified payload
        val payloadStr = JSONObject(req.proposedValue).toString()
        val deny = RefusalValidator.firstDenyMatch(payloadStr)
        if (deny != null) {
            return Decision.Rejected("refusal:$deny")
        }

        // Step 4: confidence floor (TREND_NOTE only)
        if (req.type == MemoryUpdateType.TREND_NOTE && req.confidence < confidenceFloorTrend) {
            return Decision.Rejected(
                "confidence_floor:${"%.2f".format(req.confidence)}<$confidenceFloorTrend",
            )
        }

        // Step 5: idempotency
        if (req.requestId.isBlank()) {
            return Decision.Rejected("idempotency:missing_request_id")
        }
        if (req.requestId in seenRequestIds) {
            return Decision.Idempotent
        }
        seenRequestIds.add(req.requestId)

        // CALIBRATION updates with > 15% delta from stored baseline must
        // be flagged for the user — that branch is owned by
        // AdaptiveRecalibration.proposeBaselineCandidate(...). The
        // MemoryWritePolicy itself can still accept the request; the
        // calibration manager will surface a confirmation prompt.
        return Decision.Accepted
    }

    /** Reset the idempotency set — used at session boundaries / tests. */
    fun resetSeenRequestIds() {
        seenRequestIds.clear()
    }

    // ── Internal validation ──────────────────────────────────────────

    private fun validateSchema(req: MemoryUpdateRequest): String? {
        if (req.confidence !in 0.0..1.0) return "confidence_out_of_range"
        return when (req.type) {
            MemoryUpdateType.PROFILE -> validateProfilePayload(req.proposedValue)
            MemoryUpdateType.CALIBRATION -> validateCalibrationPayload(req.proposedValue)
            MemoryUpdateType.TREND_NOTE -> validateTrendPayload(req.proposedValue)
            MemoryUpdateType.CARE_ACTIVITY_LOG -> validateCareLogPayload(req.proposedValue)
            MemoryUpdateType.DUAL_TASK_RESULT -> validateDualTaskPayload(req.proposedValue)
        }
    }

    private fun validateProfilePayload(p: Map<String, Any?>): String? {
        // Only allow keys that exist on UserProfileMemory.
        val allowed = setOf(
            "language", "voice_speed", "font_scale",
            "assisted_mode", "cue_preference",
        )
        val unknown = p.keys - allowed
        if (unknown.isNotEmpty()) return "unknown_keys:${unknown.joinToString(",")}"
        return null
    }

    private fun validateCalibrationPayload(p: Map<String, Any?>): String? {
        val required = setOf("exercise")
        val missing = required - p.keys
        if (missing.isNotEmpty()) return "missing_keys:${missing.joinToString(",")}"
        // baseline_rom_proxy and baseline_tempo_sec are optional; if
        // present they must be numeric and within plausible bounds.
        (p["baseline_rom_proxy"] as? Number)?.let {
            if (it.toDouble() !in 0.0..1.5) return "baseline_rom_proxy_out_of_range"
        }
        (p["baseline_tempo_sec"] as? Number)?.let {
            if (it.toDouble() !in 0.5..30.0) return "baseline_tempo_sec_out_of_range"
        }
        return null
    }

    private fun validateTrendPayload(p: Map<String, Any?>): String? {
        val noteStr = p["trend_note"] as? String
            ?: return "missing_trend_note"
        // Must be one of the closed-enum TrendNote values.
        return try {
            TrendNote.valueOf(noteStr)
            null
        } catch (e: IllegalArgumentException) {
            "unknown_trend_note:$noteStr"
        }
    }

    private fun validateCareLogPayload(p: Map<String, Any?>): String? {
        val required = setOf(
            "session_id",
            "activity",
            "headline",
            "what_was_completed",
            "observations",
            "not_judged",
            "next_session_focus",
        )
        val missing = required - p.keys
        if (missing.isNotEmpty()) return "missing_keys:${missing.joinToString(",")}"
        return null
    }

    private fun validateDualTaskPayload(p: Map<String, Any?>): String? {
        val required = setOf("prompt_id", "response_mode", "answer_matched", "movement_completed")
        val missing = required - p.keys
        if (missing.isNotEmpty()) return "missing_keys:${missing.joinToString(",")}"
        val mode = p["response_mode"] as? String ?: return "response_mode_not_string"
        if (mode !in setOf("gesture", "voice")) return "unsupported_response_mode:$mode"
        return null
    }

    // ── Decision sum type ────────────────────────────────────────────

    sealed class Decision {
        data object Accepted : Decision()
        data object Idempotent : Decision()           // already seen requestId — no-op, no audit
        data class Rejected(val reason: String) : Decision()
    }
}

private fun MemoryUpdateType.requiresEvidenceIds(): Boolean {
    return this == MemoryUpdateType.TREND_NOTE ||
        this == MemoryUpdateType.CARE_ACTIVITY_LOG ||
        this == MemoryUpdateType.DUAL_TASK_RESULT
}
