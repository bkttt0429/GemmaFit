package com.gemmafit.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachNarrativePacketBuilderTest {
    @Test
    fun producesQualityNoteSteadyForCleanReps() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(rep(1, formQuality = 0.9f, hadViolations = false, tempoSec = 1.8f)),
                envelope = null,
            )
        )

        val item = packet.getJSONArray("rep_summaries").getJSONObject(0)
        assertEquals("steady", item.getString("quality_note"))
        assertEquals("controlled", item.getString("tempo_band"))
    }

    @Test
    fun producesQualityNoteWatchForRepWithViolations() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(
                        1,
                        formQuality = 0.78f,
                        hadViolations = true,
                        tempoSec = 1.7f,
                        warningNames = listOf("correct_knee_alignment"),
                    )
                ),
                envelope = null,
            )
        )

        val item = packet.getJSONArray("rep_summaries").getJSONObject(0)
        assertEquals("watch", item.getString("quality_note"))
        assertEquals("correct_knee_alignment", item.getJSONArray("warning_names").getString(0))
    }

    @Test
    fun producesQualityNoteUnsteadyForHighSway() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(rep(1, formQuality = 0.88f, hadViolations = false, tempoSec = 2.0f, lateralSway = 0.22f)),
                envelope = null,
            )
        )

        assertEquals("unsteady", packet.getJSONArray("rep_summaries").getJSONObject(0).getString("quality_note"))
    }

    @Test
    fun tempoBandFromTempoSec() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(1, formQuality = 0.8f, hadViolations = false, tempoSec = 0.8f),
                    rep(2, formQuality = 0.8f, hadViolations = false, tempoSec = 1.7f),
                    rep(3, formQuality = 0.8f, hadViolations = false, tempoSec = 2.8f),
                ),
                envelope = null,
            )
        )
        val reps = packet.getJSONArray("rep_summaries")
        assertEquals("quick", reps.getJSONObject(0).getString("tempo_band"))
        assertEquals("controlled", reps.getJSONObject(1).getString("tempo_band"))
        assertEquals("slow", reps.getJSONObject(2).getString("tempo_band"))
    }

    @Test
    fun sessionTrendIdentifiesFatigueWhenLateRepsSlower() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(1, formQuality = 0.91f, hadViolations = false, tempoSec = 1.8f),
                    rep(2, formQuality = 0.88f, hadViolations = false, tempoSec = 1.9f),
                    rep(3, formQuality = 0.64f, hadViolations = false, tempoSec = 2.8f),
                    rep(4, formQuality = 0.62f, hadViolations = false, tempoSec = 3.0f),
                ),
                envelope = null,
            )
        )

        val trend = packet.getJSONObject("session_trend")
        assertEquals("slower_but_controlled", trend.getString("late_session"))
        assertTrue(trend.getJSONArray("degrading_metrics").length() > 0)
    }

    @Test
    fun sessionTrendIdentifiesImprovementWhenLateRepsBetter() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(1, formQuality = 0.62f, hadViolations = false, tempoSec = 2.8f),
                    rep(2, formQuality = 0.68f, hadViolations = false, tempoSec = 2.4f),
                    rep(3, formQuality = 0.82f, hadViolations = false, tempoSec = 1.9f),
                    rep(4, formQuality = 0.86f, hadViolations = false, tempoSec = 1.8f),
                ),
                envelope = null,
            )
        )

        val trend = packet.getJSONObject("session_trend")
        assertEquals("improving", trend.getString("late_session"))
        assertTrue(trend.getJSONArray("improving_metrics").length() > 0)
    }

    @Test
    fun baselineComparisonNullWhenEnvelopeMissing() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(rep(1, formQuality = 0.8f, hadViolations = false, tempoSec = 1.8f)),
                envelope = null,
            )
        )

        assertFalse(packet.has("baseline_comparison"))
    }

    @Test
    fun baselineComparisonRelativeToBaselineSlowerWhenSessionTempoLonger() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(1, formQuality = 0.8f, hadViolations = false, tempoSec = 2.6f),
                    rep(2, formQuality = 0.8f, hadViolations = false, tempoSec = 2.8f),
                ),
                envelope = PersonalTraceEnvelope(
                    exercise = "supported_chair_squat",
                    cleanRepCount = 3,
                    avgTempoSec = 1.8f,
                    avgRomProxyDeg = 70f,
                ),
            )
        )

        assertEquals("slower", packet.getJSONObject("baseline_comparison").getString("relative_to_baseline"))
    }

    @Test
    fun repSelectionTakesAllWhenFour() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = (1..4).map { rep(it, formQuality = 0.8f, hadViolations = false, tempoSec = 1.8f) },
                envelope = null,
            )
        )
        assertEquals(4, packet.getJSONArray("rep_summaries").length())
    }

    @Test
    fun repSelectionTakesFirstLastAndHighSignalWhenManyReps() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(1, formQuality = 0.9f, hadViolations = false, tempoSec = 1.8f),
                    rep(2, formQuality = 0.9f, hadViolations = false, tempoSec = 1.8f),
                    rep(3, formQuality = 0.7f, hadViolations = true, tempoSec = 1.8f, warningNames = listOf("warn_rapid_movement")),
                    rep(4, formQuality = 0.6f, hadViolations = false, tempoSec = 1.8f, lateralSway = 0.23f),
                    rep(5, formQuality = 0.9f, hadViolations = false, tempoSec = 1.8f),
                    rep(6, formQuality = 0.9f, hadViolations = false, tempoSec = 1.8f),
                ),
                envelope = null,
            )
        )

        val reps = packet.getJSONArray("rep_summaries")
        val selected = (0 until reps.length()).map { reps.getJSONObject(it).getInt("rep") }
        assertEquals(listOf(1, 3, 4, 6), selected)
    }

    @Test
    fun videoQualityCuesExposeBestRepWatchRepAndPrimaryFocus() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(1, formQuality = 0.92f, hadViolations = false, tempoSec = 1.8f),
                    rep(
                        2,
                        formQuality = 0.7f,
                        hadViolations = true,
                        tempoSec = 0.8f,
                        warningNames = listOf("warn_rapid_movement"),
                    ),
                    rep(3, formQuality = 0.86f, hadViolations = false, tempoSec = 2.0f),
                ),
                envelope = null,
            )
        )

        val cues = packet.getJSONObject("video_quality_cues")
        assertEquals(1, cues.getJSONObject("best_rep").getInt("rep"))
        assertEquals("rep.1.trace", cues.getJSONObject("best_rep").getString("evidence_ref"))
        assertEquals(2, cues.getJSONObject("watch_rep").getInt("rep"))
        assertEquals("controlled_tempo", cues.getString("primary_focus"))
    }

    @Test
    fun videoQualityCuesOmitWatchRepWhenAllVisibleRepsAreClean() {
        val packet = requireNotNull(
            CoachNarrativePacketBuilder.build(
                repHistory = listOf(
                    rep(1, formQuality = 0.91f, hadViolations = false, tempoSec = 1.8f),
                    rep(2, formQuality = 0.88f, hadViolations = false, tempoSec = 1.9f),
                ),
                envelope = null,
            )
        )

        val cues = packet.getJSONObject("video_quality_cues")
        assertFalse(cues.has("watch_rep"))
        assertEquals("repeat_best_rep_pattern", cues.getString("primary_focus"))
    }

    @Test
    fun warningNamesDefaultsEmpty() {
        val record = RepRecord(
            repNumber = 1,
            formQuality = 0.8f,
            rangeOfMotionDeg = 70f,
            hadViolations = false,
        )
        assertTrue(record.warningNames.isEmpty())
    }

    private fun rep(
        number: Int,
        formQuality: Float,
        hadViolations: Boolean,
        tempoSec: Float,
        lateralSway: Float = 0.04f,
        warningNames: List<String> = emptyList(),
        rangeOfMotionDeg: Float = 70f,
        smoothness: Float = 0.8f,
    ): RepRecord {
        return RepRecord(
            repNumber = number,
            formQuality = formQuality,
            rangeOfMotionDeg = rangeOfMotionDeg,
            hadViolations = hadViolations,
            traceSummary = RepTraceSummary(
                repNumber = number,
                exercise = "supported_chair_squat",
                tempoSec = tempoSec,
                romProxyDeg = rangeOfMotionDeg,
                peakVelocityDegS = 120f,
                smoothnessProxy = smoothness,
                lateralSwayProxy = lateralSway,
                pathDeviationFromBaseline = 0.0f,
                confidenceCoverage = 0.86f,
            ),
            warningNames = warningNames,
        )
    }
}
