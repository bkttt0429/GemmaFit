package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TemporalMotionAnalyzerBugfixTest {
    @Test
    fun analyze_usesFivePointSavitzkyGolaySmoothing() {
        val analyzer = TemporalMotionAnalyzer()
        val angles = listOf(10f, 20f, 30f, 40f, 50f)

        val result = angles.mapIndexed { index, angle ->
            analyzer.addSample(
                frameIndex = index,
                timestampMs = index * 100L,
                exercise = "squat",
                metrics = mapOf("knee_angle" to angle),
            )
        }.last()

        assertEquals(30f, result.primaryAngleDeg, 0.001f)
    }

    @Test
    fun analyze_sameTimestampDoesNotAmplifyVelocity() {
        val analyzer = TemporalMotionAnalyzer()
        analyzer.addSample(
            frameIndex = 0,
            timestampMs = 1_000L,
            exercise = "squat",
            metrics = mapOf("knee_angle" to 100f),
        )

        val result = analyzer.addSample(
            frameIndex = 1,
            timestampMs = 1_000L,
            exercise = "squat",
            metrics = mapOf("knee_angle" to 110f),
        )

        assertEquals(0f, result.smoothedVelocityDegS, 0.001f)
    }

    @Test
    fun completedRepProducesMotionFeatureWindow() {
        val analyzer = TemporalMotionAnalyzer()
        val angles = floatArrayOf(175f, 160f, 130f, 100f, 85f, 105f, 135f, 170f)
        var result = TemporalMotionAnalyzer.Result()

        for (i in angles.indices) {
            result = analyzer.addSample(
                frameIndex = i,
                timestampMs = i * 400L,
                exercise = "squat",
                metrics = mapOf("knee_angle" to angles[i]),
                confidenceFloor = 0.82f,
            )
        }

        val window = result.motionFeatureWindow
        assertNotNull(window)
        requireNotNull(window)
        assertEquals("motion_feature_window_v1", window.schemaVersion)
        assertEquals("REP_COMPLETED", window.trigger)
        assertEquals("squat", window.exercise)
        assertEquals("controlled", window.derivedLabels.tempoBand)
        assertEquals(true, window.derivedLabels.repCompleted)
        assertEquals(85f, window.features.kneeAngleMin ?: 0f, 0.001f)
        assertEquals(170f, window.features.kneeAngleMax ?: 0f, 0.001f)
        assertEquals(0.82f, window.features.confidenceFloor, 0.001f)
    }

    @Test
    fun bodyweightOrGobletSquatUsesKneeAngleForCompletedRep() {
        val analyzer = TemporalMotionAnalyzer()
        val angles = floatArrayOf(175f, 160f, 130f, 100f, 85f, 105f, 135f, 170f)
        var result = TemporalMotionAnalyzer.Result()

        for (i in angles.indices) {
            result = analyzer.addSample(
                frameIndex = i,
                timestampMs = i * 400L,
                exercise = "bodyweight_or_goblet_squat",
                metrics = mapOf("knee_angle" to angles[i], "hip_hinge" to 48f),
                confidenceFloor = 0.84f,
            )
        }

        val window = result.motionFeatureWindow
        assertNotNull(window)
        requireNotNull(window)
        assertEquals("bodyweight_or_goblet_squat", window.exercise)
        assertEquals("REP_COMPLETED", window.trigger)
        assertEquals(1, result.repCount)
        assertEquals(85f, window.features.kneeAngleMin ?: 0f, 0.001f)
    }
}
