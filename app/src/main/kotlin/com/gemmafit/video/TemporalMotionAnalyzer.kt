package com.gemmafit.video

import kotlin.math.abs
import kotlin.math.max

/**
 * Online temporal metrics for analyzed pose frames.
 *
 * Native motion_quality currently emits static template metrics per frame. This
 * analyzer adds the missing time-series layer: smoothed angular velocity,
 * rapid-movement evidence, movement phase, and a conservative rep counter.
 */
class TemporalMotionAnalyzer {
    private data class Sample(
        val frameIndex: Int,
        val timestampMs: Long,
        val exercise: String,
        val primaryMetric: String,
        val rawAngleDeg: Float,
    )

    data class Result(
        val repCount: Int = 0,
        val movementPhase: String = "unknown",
        val primaryMetric: String = "",
        val primaryAngleDeg: Float = 0f,
        val rangeOfMotionDeg: Float = 0f,
        val smoothedVelocityDegS: Float = 0f,
        val rapidFlag: QualityFlag? = null,
        val completedRep: RepRecord? = null,
        val temporalMetrics: Map<String, Float> = emptyMap(),
    )

    private val samples = ArrayDeque<Sample>()
    private val maxWindowMs = 1800L
    private val maxWindowSize = 12

    private var currentExercise = "unknown"
    private var repCount = 0
    private var movementPhase = "unknown"
    private var repActive = false
    private var sawBottom = false
    private var repMinBend = Float.POSITIVE_INFINITY
    private var repMaxBend = 0f
    private var lastSmoothedAngle: Float? = null
    private var lastTimestampMs: Long? = null
    private var rapidRun = 0

    fun reset() {
        samples.clear()
        currentExercise = "unknown"
        repCount = 0
        movementPhase = "unknown"
        repActive = false
        sawBottom = false
        repMinBend = Float.POSITIVE_INFINITY
        repMaxBend = 0f
        lastSmoothedAngle = null
        lastTimestampMs = null
        rapidRun = 0
    }

    fun addSample(
        frameIndex: Int,
        timestampMs: Long,
        exercise: String,
        metrics: Map<String, Float>,
    ): Result {
        val primaryMetric = primaryMetricFor(exercise, metrics) ?: return Result(repCount = repCount)
        val rawAngle = metrics[primaryMetric] ?: return Result(repCount = repCount)

        if (exercise != "unknown" && currentExercise != "unknown" && exercise != currentExercise) {
            resetTemporalStateForExerciseSwitch()
        }
        if (exercise != "unknown") currentExercise = exercise

        samples.addLast(Sample(frameIndex, timestampMs, exercise, primaryMetric, rawAngle))
        trimWindow(timestampMs)

        val smoothedAngle = smoothLatestAngle()
        val lastAngle = lastSmoothedAngle
        val lastTime = lastTimestampMs
        val dtSec = if (lastTime != null) {
            val dtMs = timestampMs - lastTime
            if (dtMs > 0L) dtMs / 1000f else 0f
        } else {
            0f
        }
        val signedVelocity = if (lastAngle != null && dtSec > 0f) {
            (smoothedAngle - lastAngle) / dtSec
        } else {
            0f
        }
        val velocityAbs = abs(signedVelocity)
        lastSmoothedAngle = smoothedAngle
        lastTimestampMs = timestampMs

        val bend = bendFromAngle(rawAngle)
        val bendVelocity = -signedVelocity
        val thresholds = thresholdsFor(exercise)
        val completedRep = updateRepState(bend, bendVelocity, thresholds)
        val rapidFlag = rapidMovementFlag(velocityAbs, primaryMetric)
        val range = if (repActive || sawBottom) {
            (repMaxBend - repMinBend).coerceAtLeast(0f)
        } else {
            completedRep?.rangeOfMotionDeg ?: 0f
        }

        return Result(
            repCount = repCount,
            movementPhase = movementPhase,
            primaryMetric = primaryMetric,
            primaryAngleDeg = smoothedAngle,
            rangeOfMotionDeg = range,
            smoothedVelocityDegS = velocityAbs,
            rapidFlag = rapidFlag,
            completedRep = completedRep,
            temporalMetrics = mapOf(
                "primary_angle_deg" to smoothedAngle,
                "tempo_deg_s" to velocityAbs,
                "range_of_motion_deg" to range,
            ),
        )
    }

    private fun resetTemporalStateForExerciseSwitch() {
        samples.clear()
        movementPhase = "unknown"
        repActive = false
        sawBottom = false
        repMinBend = Float.POSITIVE_INFINITY
        repMaxBend = 0f
        lastSmoothedAngle = null
        lastTimestampMs = null
        rapidRun = 0
    }

    private fun primaryMetricFor(exercise: String, metrics: Map<String, Float>): String? {
        val preferred = when (exercise) {
            "squat" -> listOf("knee_angle", "hip_angle")
            "push_up" -> listOf("elbow_angle", "shoulder_angle")
            "lunge" -> listOf("front_knee_angle", "knee_angle")
            "deadlift" -> listOf("hip_hinge", "knee_angle")
            else -> listOf("knee_angle", "elbow_angle", "front_knee_angle", "hip_hinge", "hip_angle")
        }
        return preferred.firstOrNull { metrics.containsKey(it) }
    }

    private fun trimWindow(nowMs: Long) {
        while (samples.size > maxWindowSize) samples.removeFirst()
        while (samples.isNotEmpty() && nowMs - samples.first().timestampMs > maxWindowMs) {
            samples.removeFirst()
        }
    }

    private fun smoothLatestAngle(): Float {
        if (samples.size < 5) return samples.last().rawAngleDeg
        val lastFive = samples.takeLast(5)
        // Savitzky-Golay smoothing, window=5, polynomial=2.
        val weights = floatArrayOf(-3f, 12f, 17f, 12f, -3f)
        var sum = 0f
        for (i in weights.indices) {
            sum += weights[i] * lastFive[i].rawAngleDeg
        }
        return sum / 35f
    }

    private fun bendFromAngle(angleDeg: Float): Float = (180f - angleDeg).coerceAtLeast(0f)

    private data class Thresholds(
        val startBend: Float,
        val bottomBend: Float,
        val finishBend: Float,
    )

    private fun thresholdsFor(exercise: String): Thresholds = when (exercise) {
        "push_up" -> Thresholds(startBend = 25f, bottomBend = 75f, finishBend = 22f)
        "deadlift" -> Thresholds(startBend = 22f, bottomBend = 60f, finishBend = 20f)
        "lunge" -> Thresholds(startBend = 28f, bottomBend = 70f, finishBend = 24f)
        else -> Thresholds(startBend = 28f, bottomBend = 75f, finishBend = 24f)
    }

    private fun updateRepState(
        bend: Float,
        bendVelocityDegS: Float,
        thresholds: Thresholds,
    ): RepRecord? {
        movementPhase = when {
            bend <= thresholds.finishBend -> "top"
            bend >= thresholds.bottomBend && abs(bendVelocityDegS) < 20f -> "bottom"
            bendVelocityDegS > 25f -> "descent"
            bendVelocityDegS < -25f -> "ascent"
            else -> movementPhase.ifBlank { "transition" }
        }

        if (!repActive && bend >= thresholds.startBend) {
            repActive = true
            sawBottom = false
            repMinBend = bend
            repMaxBend = bend
        }

        if (!repActive) return null

        repMinBend = minOf(repMinBend, bend)
        repMaxBend = max(repMaxBend, bend)
        if (bend >= thresholds.bottomBend) sawBottom = true

        if (sawBottom && bend <= thresholds.finishBend) {
            repCount += 1
            val rom = (repMaxBend - repMinBend).coerceAtLeast(0f)
            repActive = false
            sawBottom = false
            repMinBend = Float.POSITIVE_INFINITY
            repMaxBend = 0f
            movementPhase = "top"
            return RepRecord(
                repNumber = repCount,
                formQuality = 1f,
                rangeOfMotionDeg = rom,
                hadViolations = false,
            )
        }

        return null
    }

    private fun rapidMovementFlag(velocityAbs: Float, primaryMetric: String): QualityFlag? {
        if (velocityAbs > RAPID_THRESHOLD_DEG_S) {
            rapidRun += 1
        } else {
            rapidRun = 0
        }
        if (rapidRun < REQUIRED_RAPID_FRAMES) return null

        val status = if (velocityAbs > RAPID_WARNING_DEG_S) "WARNING" else "MONITOR"
        val joint = primaryMetric
            .removeSuffix("_angle")
            .removeSuffix("_deg")
            .ifBlank { primaryMetric }
        return QualityFlag(
            id = "rapid_movement",
            status = status,
            value = velocityAbs,
            threshold = RAPID_THRESHOLD_DEG_S,
            evidence = "temporal_smoothed_angular_velocity",
            reason = "${primaryMetric}_velocity",
            rule = 6,
            joint = joint,
        )
    }

    private companion object {
        const val RAPID_THRESHOLD_DEG_S = 600f
        const val RAPID_WARNING_DEG_S = 900f
        const val REQUIRED_RAPID_FRAMES = 2
    }
}
