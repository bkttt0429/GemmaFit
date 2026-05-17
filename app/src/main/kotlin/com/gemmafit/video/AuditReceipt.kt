package com.gemmafit.video

data class AuditReceiptClaim(
    val text: String,
    val evidenceRefs: List<String>,
    val source: String,
)

data class AuditReceipt(
    val title: String = "Evidence Receipt",
    val sourceSummary: String,
    val backend: String,
    val functionName: String,
    val fallback: Boolean,
    val claims: List<AuditReceiptClaim>,
    val basedOnEvidenceRefs: List<String>,
    val notJudged: List<String>,
    val confidenceNotes: List<String>,
    val boundaryNote: String,
)

object AuditReceiptBuilder {
    fun from(session: SessionSummary): AuditReceipt {
        val insight = session.sessionCoachInsight
        val evidenceRefs = (
            insight.evidenceRefs +
                session.evidenceRefs +
                session.capabilityContract.evidenceRefs
            )
            .filter { it.isNotBlank() }
            .distinct()
        val deterministicRefs = evidenceRefs.take(6)
        val sourceSummary = sourceSummary(insight)
        val claims = buildList {
            if (session.totalReps > 0) {
                add(
                    AuditReceiptClaim(
                        text = "Completed ${session.totalReps} reps.",
                        evidenceRefs = refsFor(evidenceRefs, "rep", deterministicRefs),
                        source = "Pose rules",
                    )
                )
            }
            if (session.totalFrames > 0) {
                add(
                    AuditReceiptClaim(
                        text = "Analyzed ${session.totalFrames} frames with average form score ${session.avgFormScore.toInt()}%.",
                        evidenceRefs = refsFor(evidenceRefs, "form", deterministicRefs),
                        source = "Pose rules",
                    )
                )
            }
            if (session.safetyEvents.isEmpty()) {
                add(
                    AuditReceiptClaim(
                        text = "No safety events were logged by the current pose rules.",
                        evidenceRefs = deterministicRefs,
                        source = "Pose rules",
                    )
                )
            } else {
                add(
                    AuditReceiptClaim(
                        text = "Logged ${session.safetyEvents.size} movement-quality safety events.",
                        evidenceRefs = refsFor(evidenceRefs, "rule", deterministicRefs),
                        source = "Pose rules",
                    )
                )
            }
            if (insight.whatISaw.isNotBlank()) {
                add(
                    AuditReceiptClaim(
                        text = insight.whatISaw,
                        evidenceRefs = insight.evidenceRefs.ifEmpty { deterministicRefs },
                        source = if (insight.fallback) "Template fallback" else "Local Gemma",
                    )
                )
            }
        }

        return AuditReceipt(
            sourceSummary = sourceSummary,
            backend = insight.backend.ifBlank { if (insight.fallback) "fallback" else "pose-rules" },
            functionName = insight.functionName.ifBlank { "deterministic_summary" },
            fallback = insight.fallback,
            claims = claims,
            basedOnEvidenceRefs = evidenceRefs,
            notJudged = notJudged(session),
            confidenceNotes = confidenceNotes(session),
            boundaryNote = "Movement-quality activity feedback only. This is not a medical diagnosis or injury-risk prediction.",
        )
    }

    private fun sourceSummary(insight: SessionCoachInsight): String {
        return when {
            !insight.fallback && isLocalBackend(insight.backend) -> "Pose rules + Local Gemma"
            insight.fallback -> "Pose rules + Template fallback"
            else -> "Pose rules"
        }
    }

    private fun refsFor(
        refs: List<String>,
        keyword: String,
        fallbackRefs: List<String>,
    ): List<String> {
        return refs.filter { it.contains(keyword, ignoreCase = true) }.take(4).ifEmpty { fallbackRefs }
    }

    private fun notJudged(session: SessionSummary): List<String> {
        val blockedFromContract = session.capabilityContract.cannotJudge
            .map { it.metric.replace("_", " ") }
        return (
            blockedFromContract +
                listOf(
                    "fall risk prediction",
                    "sarcopenia detection",
                    "joint force / GRF",
                    "heart rate",
                    "medical diagnosis",
                    "rehabilitation progress",
                )
            )
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun confidenceNotes(session: SessionSummary): List<String> {
        return buildList {
            if (session.isPreviewData) add("This receipt is based on preview data, not final full analysis.")
            if (session.lowConfidenceCount > 0) add("${session.lowConfidenceCount} frames had low pose confidence.")
            if (session.viewLimitedCount > 0) add("${session.viewLimitedCount} frames had view-limited judgments.")
            if (session.notApplicableCounts.isNotEmpty()) {
                add("${session.notApplicableCounts.values.sum()} rule checks were not applicable to the current view or phase.")
            }
            if (session.sessionCoachInsight.fallback) {
                add("Coach wording used deterministic/template fallback.")
            }
            if (isEmpty()) add("No major confidence limitation was recorded in the session summary.")
        }
    }

    private fun isLocalBackend(backend: String): Boolean {
        return backend.startsWith("litert-lm") ||
            backend.startsWith("llama.cpp") ||
            backend.contains("local", ignoreCase = true)
    }
}
