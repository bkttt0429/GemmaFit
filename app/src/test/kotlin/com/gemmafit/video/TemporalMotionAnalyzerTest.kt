package com.gemmafit.video

import org.junit.Assert.assertEquals
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
}
