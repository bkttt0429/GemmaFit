package com.gemmafit.video

import kotlin.math.round
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds compact, deterministic narrative evidence for session-summary LLM prompts.
 *
 * This layer gives the model rep-level context without sending raw video, raw skeleton,
 * or unbounded text. The LLM still generates only a bounded function call.
 */
internal object CoachNarrativePacketBuilder {
    private const val MAX_REP_SUMMARIES = 4
    private const val MIN_BASELINE_REPS = 3

    fun build(
        repHistory: List<RepRecord>,
        envelope: PersonalTraceEnvelope?,
    ): JSONObject? {
        val reps = repHistory
            .filter { it.repNumber > 0 }
            .sortedBy { it.repNumber }
        if (reps.isEmpty()) return null

        val selected = selectReps(reps)
        val narrative = JSONObject()
            .put("rep_summaries", JSONArray(selected.map { repSummary(it) }))
            .put("session_trend", sessionTrend(reps))
            .put("video_quality_cues", videoQualityCues(reps))

        baselineComparison(reps, envelope)?.let { narrative.put("baseline_comparison", it) }
        return narrative
    }

    private fun repSummary(rep: RepRecord): JSONObject {
        val trace = rep.traceSummary
        return JSONObject()
            .put("rep", rep.repNumber)
            .put("quality_note", qualityNote(rep))
            .put("tempo_band", tempoBand(trace?.tempoSec))
            .put("duration_ms", ((trace?.tempoSec ?: 0f) * 1000f).toInt().coerceAtLeast(0))
            .put("rom_deg", roundOne(rep.rangeOfMotionDeg.toDouble()))
            .put("peak_velocity_deg_s", roundOne((trace?.peakVelocityDegS ?: 0f).toDouble()))
            .put("smoothness_proxy", roundTwo((trace?.smoothnessProxy ?: 0f).toDouble()))
            .put("warning_names", JSONArray(rep.warningNames.distinct().take(4)))
            .put("evidence_ref", "rep.${rep.repNumber}.trace")
    }

    private fun sessionTrend(reps: List<RepRecord>): JSONObject {
        val early = reps.take(2)
        val late = reps.takeLast(2)
        val earlyForm = averageForm(early)
        val lateForm = averageForm(late)
        val confidence = reps
            .mapNotNull { it.traceSummary?.confidenceCoverage?.toDouble() }
            .average()
            .takeIf { it.isFinite() }
            ?: averageForm(reps)

        val improving = mutableListOf<String>()
        val degrading = mutableListOf<String>()
        addDeltaMetric(
            name = "overall_form",
            early = earlyForm,
            late = lateForm,
            higherIsBetter = true,
            improving = improving,
            degrading = degrading,
        )
        addDeltaMetric(
            name = "rom",
            early = averageRom(early),
            late = averageRom(late),
            higherIsBetter = true,
            improving = improving,
            degrading = degrading,
        )
        addDeltaMetric(
            name = "tempo",
            early = averageTempo(early),
            late = averageTempo(late),
            higherIsBetter = false,
            improving = improving,
            degrading = degrading,
        )

        return JSONObject()
            .put("early_session", earlySessionLabel(earlyForm, early.size))
            .put("late_session", lateSessionLabel(earlyForm, lateForm, late.size))
            .put("improving_metrics", JSONArray(improving.distinct()))
            .put("degrading_metrics", JSONArray(degrading.distinct()))
            .put("confidence", roundTwo(confidence.coerceIn(0.0, 1.0)))
    }

    private fun videoQualityCues(reps: List<RepRecord>): JSONObject {
        val best = reps
            .sortedWith(compareByDescending<RepRecord> { bestRepScore(it) }.thenBy { it.repNumber })
            .first()
        val watch = reps
            .filter { needsAttention(it) }
            .sortedWith(compareByDescending<RepRecord> { signalScore(it) }.thenBy { it.repNumber })
            .firstOrNull()

        return JSONObject()
            .put("best_rep", cueRep(best, reason = "strongest_visible_rep"))
            .put("primary_focus", primaryFocus(watch))
            .apply {
                watch?.let {
                    put("watch_rep", cueRep(it, reason = qualityNote(it)))
                }
            }
    }

    private fun cueRep(rep: RepRecord, reason: String): JSONObject {
        return JSONObject()
            .put("rep", rep.repNumber)
            .put("reason", reason)
            .put("evidence_ref", "rep.${rep.repNumber}.trace")
    }

    private fun baselineComparison(
        reps: List<RepRecord>,
        envelope: PersonalTraceEnvelope?,
    ): JSONObject? {
        if (envelope == null || envelope.cleanRepCount < MIN_BASELINE_REPS) return null
        val avgTempoMs = averageTempo(reps).takeIf { it > 0.0 }?.let { it * 1000.0 } ?: return null
        val avgRom = averageRom(reps).takeIf { it > 0.0 } ?: return null
        val baselineTempoMs = envelope.avgTempoSec.toDouble() * 1000.0
        val baselineRom = envelope.avgRomProxyDeg.toDouble()
        return JSONObject()
            .put("baseline_tempo_ms", roundOne(baselineTempoMs))
            .put("baseline_rom_deg", roundOne(baselineRom))
            .put(
                "relative_to_baseline",
                relativeToBaseline(
                    sessionTempoMs = avgTempoMs,
                    baselineTempoMs = baselineTempoMs,
                    sessionRomDeg = avgRom,
                    baselineRomDeg = baselineRom,
                ),
            )
    }

    private fun selectReps(reps: List<RepRecord>): List<RepRecord> {
        if (reps.size <= MAX_REP_SUMMARIES) return reps
        val selected = linkedMapOf<Int, RepRecord>()
        selected[reps.first().repNumber] = reps.first()
        selected[reps.last().repNumber] = reps.last()
        reps
            .drop(1)
            .dropLast(1)
            .sortedWith(compareByDescending<RepRecord> { signalScore(it) }.thenBy { it.repNumber })
            .take(MAX_REP_SUMMARIES - selected.size)
            .forEach { selected[it.repNumber] = it }
        return selected.values.sortedBy { it.repNumber }
    }

    private fun signalScore(rep: RepRecord): Double {
        return when (qualityNote(rep)) {
            "watch" -> 5.0
            "unsteady" -> 4.0
            "needs_focus" -> 3.0
            "supported" -> 2.0
            else -> 1.0
        } + rep.warningNames.size + (rep.traceSummary?.lateralSwayProxy?.toDouble() ?: 0.0)
    }

    private fun bestRepScore(rep: RepRecord): Double {
        val trace = rep.traceSummary
        return normalizedForm(rep.formQuality) * 3.0 +
            (trace?.smoothnessProxy?.toDouble() ?: 0.0) -
            ((trace?.lateralSwayProxy?.toDouble() ?: 0.0) * 2.0) -
            (if (rep.hadViolations) 0.8 else 0.0) -
            (rep.warningNames.size * 0.2)
    }

    private fun needsAttention(rep: RepRecord): Boolean {
        val note = qualityNote(rep)
        return note in setOf("watch", "unsteady", "needs_focus") ||
            rep.hadViolations ||
            rep.warningNames.isNotEmpty()
    }

    private fun primaryFocus(watch: RepRecord?): String {
        if (watch == null) return "repeat_best_rep_pattern"
        val warningFocus = watch.warningNames
            .asSequence()
            .mapNotNull(::focusFromWarningName)
            .firstOrNull()
        if (warningFocus != null) return warningFocus
        val trace = watch.traceSummary
        return when {
            (trace?.lateralSwayProxy ?: 0f) >= 0.18f -> "steady_support_control"
            (trace?.tempoSec ?: 0f) < 1.0f -> "controlled_tempo"
            watch.rangeOfMotionDeg < 55f -> "comfortable_range_of_motion"
            else -> "controlled_setup_and_finish"
        }
    }

    private fun focusFromWarningName(name: String): String? {
        return when (name) {
            "correct_spinal_alignment" -> "upright_trunk_control"
            "correct_knee_alignment" -> "knee_alignment"
            "warn_rapid_movement" -> "controlled_tempo"
            "warn_com_offset" -> "center_of_mass_control"
            "correct_asymmetry" -> "even_left_right_control"
            "increase_range_of_motion" -> "comfortable_range_of_motion"
            "correct_joint_angle" -> "joint_angle_control"
            else -> null
        }
    }

    private fun qualityNote(rep: RepRecord): String {
        val form = normalizedForm(rep.formQuality)
        val sway = rep.traceSummary?.lateralSwayProxy ?: 0f
        return when {
            sway >= 0.18f -> "unsteady"
            rep.hadViolations || rep.warningNames.isNotEmpty() -> "watch"
            form >= 0.85 -> "steady"
            form >= 0.70 -> "controlled"
            form >= 0.60 -> "supported"
            else -> "needs_focus"
        }
    }

    private fun tempoBand(tempoSec: Float?): String {
        return when {
            tempoSec == null || tempoSec <= 0f -> "controlled"
            tempoSec < 1.0f -> "quick"
            tempoSec <= 2.5f -> "controlled"
            else -> "slow"
        }
    }

    private fun earlySessionLabel(earlyForm: Double, count: Int): String {
        if (count == 0) return "insufficient_data"
        return if (earlyForm >= 0.70) "steady" else "variable"
    }

    private fun lateSessionLabel(earlyForm: Double, lateForm: Double, count: Int): String {
        if (count == 0) return "insufficient_data"
        return when {
            lateForm > earlyForm + 0.05 -> "improving"
            lateForm < earlyForm - 0.05 -> "slower_but_controlled"
            else -> "steady"
        }
    }

    private fun addDeltaMetric(
        name: String,
        early: Double,
        late: Double,
        higherIsBetter: Boolean,
        improving: MutableList<String>,
        degrading: MutableList<String>,
    ) {
        if (!early.isFinite() || !late.isFinite() || early <= 0.0 || late <= 0.0) return
        val delta = late - early
        if (kotlin.math.abs(delta) <= 0.05) return
        val isImproving = if (higherIsBetter) delta > 0.0 else delta < 0.0
        if (isImproving) improving += name else degrading += name
    }

    private fun relativeToBaseline(
        sessionTempoMs: Double,
        baselineTempoMs: Double,
        sessionRomDeg: Double,
        baselineRomDeg: Double,
    ): String {
        return when {
            baselineTempoMs > 0.0 && sessionTempoMs > baselineTempoMs * 1.15 -> "slower"
            baselineTempoMs > 0.0 && sessionTempoMs < baselineTempoMs * 0.85 -> "faster"
            baselineRomDeg > 0.0 && sessionRomDeg > baselineRomDeg + 5.0 -> "deeper"
            baselineRomDeg > 0.0 && sessionRomDeg < baselineRomDeg - 5.0 -> "shallower"
            else -> "similar"
        }
    }

    private fun averageForm(reps: List<RepRecord>): Double {
        return reps.map { normalizedForm(it.formQuality) }.average().takeIf { it.isFinite() } ?: 0.0
    }

    private fun averageRom(reps: List<RepRecord>): Double {
        return reps.map { it.rangeOfMotionDeg.toDouble() }.average().takeIf { it.isFinite() } ?: 0.0
    }

    private fun averageTempo(reps: List<RepRecord>): Double {
        return reps.mapNotNull { it.traceSummary?.tempoSec?.toDouble()?.takeIf { value -> value > 0.0 } }
            .average()
            .takeIf { it.isFinite() }
            ?: 0.0
    }

    private fun normalizedForm(value: Float): Double {
        val raw = value.toDouble()
        return if (raw > 1.0) (raw / 100.0).coerceIn(0.0, 1.0) else raw.coerceIn(0.0, 1.0)
    }

    private fun roundOne(value: Double): Double = round(value * 10.0) / 10.0

    private fun roundTwo(value: Double): Double = round(value * 100.0) / 100.0
}
