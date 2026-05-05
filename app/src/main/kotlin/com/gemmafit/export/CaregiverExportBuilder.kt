package com.gemmafit.export

import com.gemmafit.memory.CaregiverSummary
import com.gemmafit.memory.MemoryStore
import com.gemmafit.memory.SessionSummary
import com.gemmafit.memory.TrendNote
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds caregiver export artifacts. User-initiated, opt-in only.
 *
 * Two outputs per export (implementation_plan.md §11.6):
 *  1. `caregiver_export_v1.json`  — machine-readable canonical form
 *  2. human-readable summary      — shareable as `.txt` or `.html`
 *
 * Tone is care, not clinic. Every output carries the mandatory
 * `unsupported_judgments_acknowledged` block so caregivers cannot
 * misread the report as a medical assessment.
 */
class CaregiverExportBuilder(
    private val store: MemoryStore,
) {

    /** Build the complete export bundle for a date window. */
    suspend fun build(
        periodStart: String,    // ISO yyyy-MM-dd
        periodEnd: String,
        exercises: List<String>,
    ): Bundle {
        val sessions = exercises.flatMap { ex ->
            store.recentSessions(
                exercise = ex,
                limit = 200,
                sinceEpochMs = null,
            )
        }.filter { it.date in periodStart..periodEnd }

        val summary = CaregiverSummary(
            periodStart = periodStart,
            periodEnd = periodEnd,
            sessionsCompleted = sessions.size,
            commonCameraLimitations = collectCameraLimitations(sessions),
            commonCues = topCues(sessions),
            unsupportedJudgmentsAcknowledged = MANDATORY_UNSUPPORTED,
        )

        val json = canonicalJson(summary, sessions)
        val text = humanText(summary)
        val html = humanHtml(summary)
        return Bundle(json = json, text = text, html = html, summary = summary)
    }

    // ── Canonical JSON ───────────────────────────────────────────────

    private fun canonicalJson(s: CaregiverSummary, sessions: List<SessionSummary>): String {
        return JSONObject(mapOf(
            "schema_version" to "caregiver_export_v1",
            "period_start"   to s.periodStart,
            "period_end"     to s.periodEnd,
            "sessions_completed" to s.sessionsCompleted,
            "common_camera_limitations" to JSONArray(s.commonCameraLimitations),
            "common_cues" to JSONArray(s.commonCues),
            "unsupported_judgments_acknowledged"
                to JSONArray(s.unsupportedJudgmentsAcknowledged),
            "no_medical_diagnosis" to s.noMedicalDiagnosis,
            "sessions" to JSONArray(sessions.map { sessionJson(it) }),
        )).toString(2)
    }

    private fun sessionJson(s: SessionSummary): JSONObject = JSONObject(mapOf(
        "session_id" to s.sessionId,
        "date"       to s.date,
        "mode"       to s.mode.name,
        "exercise"   to s.exercise,
        "reps"       to s.reps,
        "duration_sec" to s.durationSec,
        "warnings_count" to s.warningsCount,
        "low_confidence_count" to s.lowConfidenceCount,
        "not_applicable_count" to s.notApplicableCount,
        "trend_notes" to JSONArray(s.trendNotes.map { it.name }),
    ))

    // ── Human-readable text ──────────────────────────────────────────

    private fun humanText(s: CaregiverSummary): String = buildString {
        appendLine("GemmaFit weekly summary")
        appendLine("Period: ${s.periodStart} → ${s.periodEnd}")
        appendLine()
        appendLine("Sessions completed: ${s.sessionsCompleted}")
        if (s.commonCues.isNotEmpty()) {
            appendLine()
            appendLine("Most common training cues:")
            s.commonCues.forEach { appendLine("  • $it") }
        }
        if (s.commonCameraLimitations.isNotEmpty()) {
            appendLine()
            appendLine("Camera observations to be aware of:")
            s.commonCameraLimitations.forEach { appendLine("  • $it") }
        }
        appendLine()
        appendLine("---")
        appendLine(DISCLAIMER_TEXT)
    }

    // ── Human-readable HTML (no PDF lib needed) ─────────────────────

    private fun humanHtml(s: CaregiverSummary): String = buildString {
        append("<!doctype html><html><head><meta charset='utf-8'>")
        append("<title>GemmaFit weekly summary</title>")
        append("<style>body{font-family:sans-serif;max-width:640px;margin:24px auto;padding:0 16px;}")
        append(".disclaimer{color:#666;font-size:0.9em;margin-top:24px;border-top:1px solid #ccc;padding-top:12px;}")
        append("</style></head><body>")
        append("<h1>GemmaFit weekly summary</h1>")
        append("<p><strong>Period:</strong> ${escape(s.periodStart)} - ${escape(s.periodEnd)}</p>")
        append("<p><strong>Sessions completed:</strong> ${s.sessionsCompleted}</p>")
        if (s.commonCues.isNotEmpty()) {
            append("<h2>Most common training cues</h2><ul>")
            s.commonCues.forEach { append("<li>${escape(it)}</li>") }
            append("</ul>")
        }
        if (s.commonCameraLimitations.isNotEmpty()) {
            append("<h2>Camera observations to be aware of</h2><ul>")
            s.commonCameraLimitations.forEach { append("<li>${escape(it)}</li>") }
            append("</ul>")
        }
        append("<div class='disclaimer'>${escape(DISCLAIMER_TEXT)}</div>")
        append("</body></html>")
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // ── Aggregation helpers ──────────────────────────────────────────

    private fun topCues(sessions: List<SessionSummary>): List<String> {
        // Map TrendNote → human cue. A future improvement is to also
        // consider EvidenceMemoryEntry metric ids, but for v1 trend
        // notes are sufficient.
        val counts = HashMap<TrendNote, Int>()
        sessions.forEach { s -> s.trendNotes.forEach { counts.merge(it, 1, Int::plus) } }
        return counts.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { humanizeTrendNote(it.key) }
    }

    private fun collectCameraLimitations(sessions: List<SessionSummary>): List<String> {
        val limitedSessions = sessions.count { it.lowConfidenceCount + it.notApplicableCount > 0 }
        if (limitedSessions == 0) return emptyList()
        return listOf("$limitedSessions session(s) had limited camera view")
    }

    private fun humanizeTrendNote(t: TrendNote): String = when (t) {
        TrendNote.TEMPO_SLOWING       -> "tempo getting slower"
        TrendNote.TEMPO_STABLE        -> "tempo steady"
        TrendNote.TEMPO_IMPROVING     -> "tempo improving"
        TrendNote.ROM_SHRINKING       -> "range of motion getting smaller"
        TrendNote.ROM_STABLE          -> "range of motion steady"
        TrendNote.ROM_IMPROVING       -> "range of motion improving"
        TrendNote.LOW_CONFIDENCE_RISING -> "camera view harder to read recently"
        TrendNote.VIEW_INCONSISTENT   -> "camera setup keeps changing"
    }

    // ── Bundle + constants ───────────────────────────────────────────

    data class Bundle(
        val json: String,
        val text: String,
        val html: String,
        val summary: CaregiverSummary,
    )

    companion object {
        /** Always present in every export's `unsupported_judgments_acknowledged`. */
        val MANDATORY_UNSUPPORTED: List<String> = listOf(
            "sarcopenia_diagnosis",
            "fall_risk_prediction",
            "rehabilitation_prescription",
            "muscle_mass_estimate",
            "clinical_improvement_claim",
            "medication_or_therapy_guidance",
        )

        /** Disclaimer block — required at the bottom of every human-readable export. */
        const val DISCLAIMER_TEXT = "This is pose-based movement quality feedback, " +
            "not a medical diagnosis. It does not assess sarcopenia, fall risk, " +
            "rehabilitation needs, or muscle mass. Single-camera estimates may be " +
            "limited by view angle, lighting, clothing, and occlusion."
    }
}
