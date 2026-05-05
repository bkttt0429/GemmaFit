package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTraceAnalyzerTest {
    @Test
    fun completedSquatRepProducesTraceSummary() {
        val trace = MotionTraceAnalyzer()
        val result = runSquatRep(trace)

        val summary = result.repTraceSummary
        assertNotNull(summary)
        requireNotNull(summary)
        assertEquals(1, summary.repNumber)
        assertEquals("squat", summary.exercise)
        assertTrue(summary.tempoSec > 0f)
        assertTrue(summary.romProxyDeg >= 60f)
        assertTrue(summary.confidenceCoverage >= 0.8f)
    }

    @Test
    fun lowVisibilityFramesDoNotUpdatePersonalEnvelope() {
        val trace = MotionTraceAnalyzer()
        val temporal = TemporalMotionAnalyzer()
        val angles = squatAngles()

        for (i in angles.indices) {
            val temporalResult = temporal.addSample(i, i * 200L, "squat", mapOf("knee_angle" to angles[i]))
            trace.addSample(
                frameIndex = i,
                timestampMs = i * 200L,
                exercise = "squat",
                landmarks = landmarks(centerX = 0.5f, visibility = 0.1f),
                temporal = temporalResult,
                qualityFlags = listOf(lowConfidenceFlag()),
            )
        }

        assertNull(trace.envelopeFor("squat"))
    }

    @Test
    fun pathDeviationFromEnvelopeProducesMonitorWithoutReliableWarning() {
        val trace = MotionTraceAnalyzer()
        runSquatRep(trace, centerXs = List(8) { 0.5f })

        val result = runSquatRep(
            trace = trace,
            startFrame = 20,
            centerXs = listOf(0.5f, 0.74f, 0.39f, 0.76f, 0.38f, 0.73f, 0.41f, 0.70f),
        )

        assertEquals("MONITOR", result.traceFlag?.status)
        assertEquals("trace_deviation", result.traceFlag?.id)
        assertTrue(requireNotNull(result.repTraceSummary).pathDeviationFromBaseline >= 0.35f)
    }

    @Test
    fun pathDeviationWithReliableWarningEscalatesToWarning() {
        val trace = MotionTraceAnalyzer()
        runSquatRep(trace, centerXs = List(8) { 0.5f })

        val result = runSquatRep(
            trace = trace,
            startFrame = 40,
            centerXs = listOf(0.5f, 0.74f, 0.39f, 0.76f, 0.38f, 0.73f, 0.41f, 0.70f),
            flags = listOf(rapidMovementWarning()),
        )

        assertEquals("WARNING", result.traceFlag?.status)
        assertEquals("trace_deviation", result.traceFlag?.id)
    }

    @Test
    fun exerciseSwitchResetsCurrentTraceSegment() {
        val trace = MotionTraceAnalyzer()
        val temporal = TemporalMotionAnalyzer()

        repeat(3) { i ->
            val temporalResult = temporal.addSample(i, i * 200L, "squat", mapOf("knee_angle" to 160f))
            trace.addSample(
                frameIndex = i,
                timestampMs = i * 200L,
                exercise = "squat",
                landmarks = landmarks(centerX = 0.5f),
                temporal = temporalResult,
                qualityFlags = emptyList(),
            )
        }

        var result = MotionTraceAnalyzer.Result()
        val pushUpAngles = floatArrayOf(175f, 150f, 120f, 95f, 80f, 105f, 135f, 170f)
        for (i in pushUpAngles.indices) {
            val frame = i + 10
            val temporalResult = temporal.addSample(
                frameIndex = frame,
                timestampMs = 2_000L + i * 200L,
                exercise = "push_up",
                metrics = mapOf("elbow_angle" to pushUpAngles[i]),
            )
            result = trace.addSample(
                frameIndex = frame,
                timestampMs = 2_000L + i * 200L,
                exercise = "push_up",
                landmarks = landmarks(centerX = 0.55f),
                temporal = temporalResult,
                qualityFlags = emptyList(),
            )
        }

        val summary = requireNotNull(result.repTraceSummary)
        assertEquals("push_up", summary.exercise)
        assertTrue(summary.tempoSec < 1.6f)
    }

    private fun runSquatRep(
        trace: MotionTraceAnalyzer,
        startFrame: Int = 0,
        centerXs: List<Float> = List(8) { 0.5f },
        flags: List<QualityFlag> = emptyList(),
    ): MotionTraceAnalyzer.Result {
        val temporal = TemporalMotionAnalyzer()
        var result = MotionTraceAnalyzer.Result()
        val angles = squatAngles()
        for (i in angles.indices) {
            val temporalResult = temporal.addSample(
                frameIndex = startFrame + i,
                timestampMs = startFrame * 100L + i * 200L,
                exercise = "squat",
                metrics = mapOf("knee_angle" to angles[i]),
            )
            result = trace.addSample(
                frameIndex = startFrame + i,
                timestampMs = startFrame * 100L + i * 200L,
                exercise = "squat",
                landmarks = landmarks(centerX = centerXs.getOrElse(i) { centerXs.last() }),
                temporal = temporalResult,
                qualityFlags = flags,
            )
        }
        return result
    }

    private fun squatAngles(): FloatArray {
        return floatArrayOf(175f, 160f, 130f, 100f, 85f, 105f, 135f, 170f)
    }

    private fun landmarks(centerX: Float, visibility: Float = 1f): List<PoseLandmarkData> {
        return List(33) { index ->
            val offset = when (index) {
                11, 23, 25, 27 -> -0.03f
                12, 24, 26, 28 -> 0.03f
                15 -> -0.08f
                16 -> 0.08f
                else -> 0f
            }
            PoseLandmarkData(
                x = centerX + offset,
                y = 0.45f + (index % 5) * 0.02f,
                z = 0f,
                visibility = visibility,
            )
        }
    }

    private fun lowConfidenceFlag(): QualityFlag {
        return QualityFlag(
            id = "pose_confidence",
            status = "LOW_CONFIDENCE",
            value = 0f,
            threshold = 0.6f,
            evidence = "confidence_gate",
        )
    }

    private fun rapidMovementWarning(): QualityFlag {
        return QualityFlag(
            id = "rapid_movement",
            status = "WARNING",
            value = 940f,
            threshold = 600f,
            evidence = "temporal_smoothed_angular_velocity",
            reason = "knee_angle_velocity",
            rule = 6,
            joint = "knee",
        )
    }
}
