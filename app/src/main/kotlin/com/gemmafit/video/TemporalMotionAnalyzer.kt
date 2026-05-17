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
        val motionFeatureWindow: MotionFeatureWindow? = null,
        val temporalMetrics: Map<String, Float> = emptyMap(),
    )

    private data class RepUpdate(
        val completedRep: RepRecord? = null,
        val motionFeatureWindow: MotionFeatureWindow? = null,
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
    private var repStartTimestampMs: Long? = null
    private var repStartFrameIndex: Int? = null
    private var repAngleMin = Float.POSITIVE_INFINITY
    private var repAngleMax = Float.NEGATIVE_INFINITY
    private var repPeakVelocityDegS = 0f
    private var repConfidenceFloor = 1f
    private val repPhaseSequence = mutableListOf<String>()
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
        resetRepFeatureState()
        lastSmoothedAngle = null
        lastTimestampMs = null
        rapidRun = 0
    }

    @JvmOverloads
    fun addSample(
        frameIndex: Int,
        timestampMs: Long,
        exercise: String,
        metrics: Map<String, Float>,
        confidenceFloor: Float = 1f,
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
        val repUpdate = updateRepState(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            exercise = exercise,
            primaryMetric = primaryMetric,
            rawAngleDeg = rawAngle,
            confidenceFloor = confidenceFloor,
            bend = bend,
            bendVelocityDegS = bendVelocity,
            velocityAbsDegS = velocityAbs,
            thresholds = thresholds,
        )
        val completedRep = repUpdate.completedRep
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
            motionFeatureWindow = repUpdate.motionFeatureWindow,
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
        resetRepFeatureState()
        lastSmoothedAngle = null
        lastTimestampMs = null
        rapidRun = 0
    }

    private fun primaryMetricFor(exercise: String, metrics: Map<String, Float>): String? {
        val preferred = when (exercise) {
            "squat", "bodyweight_or_goblet_squat", "bodyweight_squat", "goblet_squat" ->
                listOf("knee_angle", "hip_angle")
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
        frameIndex: Int,
        timestampMs: Long,
        exercise: String,
        primaryMetric: String,
        rawAngleDeg: Float,
        confidenceFloor: Float,
        bend: Float,
        bendVelocityDegS: Float,
        velocityAbsDegS: Float,
        thresholds: Thresholds,
    ): RepUpdate {
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
            repStartTimestampMs = timestampMs
            repStartFrameIndex = frameIndex
            repAngleMin = rawAngleDeg
            repAngleMax = rawAngleDeg
            repPeakVelocityDegS = velocityAbsDegS
            repConfidenceFloor = confidenceFloor.coerceIn(0f, 1f)
            repPhaseSequence.clear()
        }

        if (!repActive) return RepUpdate()

        repMinBend = minOf(repMinBend, bend)
        repMaxBend = max(repMaxBend, bend)
        repAngleMin = minOf(repAngleMin, rawAngleDeg)
        repAngleMax = max(repAngleMax, rawAngleDeg)
        repPeakVelocityDegS = max(repPeakVelocityDegS, velocityAbsDegS)
        repConfidenceFloor = minOf(repConfidenceFloor, confidenceFloor.coerceIn(0f, 1f))
        appendRepPhase(movementPhase)
        if (bend >= thresholds.bottomBend) sawBottom = true

        if (sawBottom && bend <= thresholds.finishBend) {
            repCount += 1
            val rom = (repMaxBend - repMinBend).coerceAtLeast(0f)
            val completedRep = RepRecord(
                repNumber = repCount,
                formQuality = 1f,
                rangeOfMotionDeg = rom,
                hadViolations = false,
            )
            val motionFeatureWindow = buildMotionFeatureWindow(
                completedRep = completedRep,
                exercise = exercise,
                primaryMetric = primaryMetric,
                finishFrameIndex = frameIndex,
                finishTimestampMs = timestampMs,
            )
            repActive = false
            sawBottom = false
            repMinBend = Float.POSITIVE_INFINITY
            repMaxBend = 0f
            movementPhase = "top"
            resetRepFeatureState()
            return RepUpdate(
                completedRep = completedRep,
                motionFeatureWindow = motionFeatureWindow,
            )
        }

        return RepUpdate()
    }

    private fun appendRepPhase(phase: String) {
        if (phase.isBlank() || phase == "unknown") return
        if (repPhaseSequence.lastOrNull() != phase) {
            repPhaseSequence.add(phase)
        }
    }

    private fun resetRepFeatureState() {
        repStartTimestampMs = null
        repStartFrameIndex = null
        repAngleMin = Float.POSITIVE_INFINITY
        repAngleMax = Float.NEGATIVE_INFINITY
        repPeakVelocityDegS = 0f
        repConfidenceFloor = 1f
        repPhaseSequence.clear()
    }

    private fun buildMotionFeatureWindow(
        completedRep: RepRecord,
        exercise: String,
        primaryMetric: String,
        finishFrameIndex: Int,
        finishTimestampMs: Long,
    ): MotionFeatureWindow {
        val startMs = repStartTimestampMs ?: finishTimestampMs
        val startFrame = repStartFrameIndex ?: finishFrameIndex
        val durationMs = (finishTimestampMs - startMs).coerceAtLeast(1L)
        val angleMin = if (repAngleMin.isFinite()) repAngleMin else 0f
        val angleMax = if (repAngleMax.isFinite()) repAngleMax else angleMin
        val primaryIsKnee = primaryMetric.contains("knee")

        return MotionFeatureWindow(
            windowId = "motion.rep.${completedRep.repNumber}",
            trigger = "REP_COMPLETED",
            windowMs = durationMs,
            exercise = exercise,
            source = listOf("temporal_motion_analyzer", "pose_sequence"),
            features = MotionFeatureValues(
                kneeAngleMin = if (primaryIsKnee) angleMin else null,
                kneeAngleMax = if (primaryIsKnee) angleMax else null,
                primaryAngleMin = angleMin,
                primaryAngleMax = angleMax,
                rangeOfMotionDeg = completedRep.rangeOfMotionDeg,
                repDurationMs = durationMs,
                peakVelocityDegS = repPeakVelocityDegS,
                velocityPeak = velocityPeakLabel(repPeakVelocityDegS),
                confidenceFloor = repConfidenceFloor,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = tempoBand(durationMs),
                phaseSequenceEstimate = repPhaseSequence.ifEmpty {
                    listOf("movement_start", "movement_end")
                },
                repCompleted = true,
            ),
            evidenceRefs = listOf(
                "metric.motion.rep_duration",
                "metric.motion.rom",
                "metric.motion.peak_velocity",
                "metric.motion.confidence_floor",
            ),
            limits = listOf(
                "derived_from_single_camera_pose",
                "no_force_or_grf",
                "no_emg_or_muscle_activation",
            ),
        )
    }

    private fun tempoBand(durationMs: Long): String = when {
        durationMs >= 2_000L -> "controlled"
        durationMs >= 1_000L -> "brisk"
        else -> "rapid"
    }

    private fun velocityPeakLabel(peakVelocityDegS: Float): String = when {
        peakVelocityDegS >= RAPID_WARNING_DEG_S -> "high"
        peakVelocityDegS >= RAPID_THRESHOLD_DEG_S -> "moderate"
        peakVelocityDegS > 0f -> "low"
        else -> "unknown"
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
