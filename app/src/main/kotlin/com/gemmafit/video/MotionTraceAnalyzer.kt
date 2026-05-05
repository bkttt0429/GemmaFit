package com.gemmafit.video

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Rep-level motion trace evidence built from pose landmarks and temporal metrics.
 *
 * This class keeps only short-lived landmark windows. Long-term surfaces receive
 * [RepTraceSummary], never raw landmarks or video frames.
 */
class MotionTraceAnalyzer {
    data class Result(
        val repTraceSummary: RepTraceSummary? = null,
        val traceFlag: QualityFlag? = null,
        val envelope: PersonalTraceEnvelope? = null,
    )

    private data class FrameSample(
        val frameIndex: Int,
        val timestampMs: Long,
        val points: List<TracePoint>,
        val comX: Float,
        val comY: Float,
        val avgVisibility: Float,
        val velocityDegS: Float,
    )

    private val recentFrames = ArrayDeque<List<TracePoint>>()
    private val currentRepFrames = ArrayDeque<FrameSample>()
    private val envelopes = mutableMapOf<String, PersonalTraceEnvelope>()

    private var currentExercise = "unknown"

    fun reset() {
        recentFrames.clear()
        currentRepFrames.clear()
        currentExercise = "unknown"
    }

    fun envelopeFor(exercise: String): PersonalTraceEnvelope? = envelopes[exercise]

    fun addSample(
        frameIndex: Int,
        timestampMs: Long,
        exercise: String,
        landmarks: List<PoseLandmarkData>,
        temporal: TemporalMotionAnalyzer.Result,
        qualityFlags: List<QualityFlag>,
    ): Result {
        if (exercise != "unknown" && currentExercise != "unknown" && exercise != currentExercise) {
            reset()
        }
        if (exercise != "unknown") currentExercise = exercise

        val frame = frameSampleFrom(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            landmarks = landmarks,
            velocityDegS = temporal.smoothedVelocityDegS,
        ) ?: return Result()

        recentFrames.addLast(frame.points)
        while (recentFrames.size > LIVE_TRACE_WINDOW_FRAMES) recentFrames.removeFirst()

        if (exercise == "unknown") return Result()

        currentRepFrames.addLast(frame)
        while (currentRepFrames.size > MAX_REP_FRAMES) currentRepFrames.removeFirst()

        val completedRep = temporal.completedRep ?: return Result()
        val summary = summarizeRep(
            completedRep = completedRep,
            exercise = exercise,
            frames = currentRepFrames.toList(),
        )
        currentRepFrames.clear()

        val limited = isTraceLimited(qualityFlags, summary)
        val traceFlag = if (limited) {
            null
        } else {
            traceFlagFor(summary, qualityFlags)
        }

        val envelope = if (shouldUpdateEnvelope(summary, qualityFlags, traceFlag)) {
            updateEnvelope(summary)
        } else {
            envelopes[exercise]
        }

        return Result(
            repTraceSummary = summary,
            traceFlag = traceFlag,
            envelope = envelope,
        )
    }

    private fun frameSampleFrom(
        frameIndex: Int,
        timestampMs: Long,
        landmarks: List<PoseLandmarkData>,
        velocityDegS: Float,
    ): FrameSample? {
        if (landmarks.size < 33) return null

        val points = mutableListOf<TracePoint>()
        for (joint in TRACKED_JOINTS) {
            val landmark = landmarks.getOrNull(joint) ?: continue
            points.add(
                TracePoint(
                    frameIndex = frameIndex,
                    timestampMs = timestampMs,
                    jointId = joint,
                    x = landmark.x,
                    y = landmark.y,
                    visibility = landmark.visibility,
                )
            )
        }

        val visible = points.filter { it.visibility >= MIN_VISIBLE_JOINT_CONFIDENCE }
        if (visible.size < MIN_VISIBLE_TRACKED_JOINTS) return null

        val comX = visible.map { it.x }.average().toFloat()
        val comY = visible.map { it.y }.average().toFloat()
        val avgVisibility = points.map { it.visibility }.average().toFloat()
        val comPoint = TracePoint(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            jointId = COM_PROXY_JOINT_ID,
            x = comX,
            y = comY,
            visibility = avgVisibility,
        )

        return FrameSample(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            points = points + comPoint,
            comX = comX,
            comY = comY,
            avgVisibility = avgVisibility,
            velocityDegS = velocityDegS,
        )
    }

    private fun summarizeRep(
        completedRep: RepRecord,
        exercise: String,
        frames: List<FrameSample>,
    ): RepTraceSummary {
        val first = frames.firstOrNull()
        val last = frames.lastOrNull()
        val tempoSec = if (first != null && last != null) {
            ((last.timestampMs - first.timestampMs).coerceAtLeast(1L)) / 1000f
        } else {
            0f
        }

        val peakVelocity = frames.maxOfOrNull { abs(it.velocityDegS) } ?: 0f
        val confidenceCoverage = if (frames.isNotEmpty()) {
            frames.count { it.avgVisibility >= MIN_TRACE_CONFIDENCE }.toFloat() / frames.size
        } else {
            0f
        }
        val lateralSway = if (frames.isNotEmpty()) {
            frames.maxOf { it.comX } - frames.minOf { it.comX }
        } else {
            0f
        }
        val smoothness = smoothnessProxy(frames)
        val envelope = envelopes[exercise]
        val deviation = if (envelope != null && envelope.cleanRepCount >= MIN_BASELINE_REPS) {
            pathDeviationFromEnvelope(
                summaryTempoSec = tempoSec,
                summaryRomProxyDeg = completedRep.rangeOfMotionDeg,
                summarySway = lateralSway,
                summarySmoothness = smoothness,
                envelope = envelope,
            )
        } else {
            0f
        }

        return RepTraceSummary(
            repNumber = completedRep.repNumber,
            exercise = exercise,
            tempoSec = tempoSec,
            romProxyDeg = completedRep.rangeOfMotionDeg,
            peakVelocityDegS = peakVelocity,
            smoothnessProxy = smoothness,
            lateralSwayProxy = lateralSway,
            pathDeviationFromBaseline = deviation,
            confidenceCoverage = confidenceCoverage,
        )
    }

    private fun smoothnessProxy(frames: List<FrameSample>): Float {
        if (frames.size < 3) return 0f
        val stepDistances = frames.zipWithNext().map { (a, b) ->
            hypot((b.comX - a.comX).toDouble(), (b.comY - a.comY).toDouble()).toFloat()
        }
        if (stepDistances.size < 2) return stepDistances.firstOrNull() ?: 0f
        return stepDistances.zipWithNext().map { (a, b) -> abs(b - a) }.average().toFloat()
    }

    private fun pathDeviationFromEnvelope(
        summaryTempoSec: Float,
        summaryRomProxyDeg: Float,
        summarySway: Float,
        summarySmoothness: Float,
        envelope: PersonalTraceEnvelope,
    ): Float {
        val tempoDelta = normalizedDelta(summaryTempoSec, envelope.avgTempoSec)
        val romDelta = normalizedDelta(summaryRomProxyDeg, envelope.avgRomProxyDeg)
        val swayDelta = normalizedDelta(summarySway, envelope.avgLateralSwayProxy)
        val smoothnessDelta = normalizedDelta(summarySmoothness, envelope.avgSmoothnessProxy)
        return listOf(tempoDelta, romDelta, swayDelta, smoothnessDelta).average().toFloat()
    }

    private fun normalizedDelta(value: Float, baseline: Float): Float {
        if (baseline <= 0.0001f) return if (value <= 0.0001f) 0f else 1f
        return (abs(value - baseline) / max(abs(baseline), 0.0001f)).coerceIn(0f, 3f)
    }

    private fun isTraceLimited(
        qualityFlags: List<QualityFlag>,
        summary: RepTraceSummary,
    ): Boolean {
        return summary.confidenceCoverage < MIN_CONFIDENCE_COVERAGE ||
            qualityFlags.any { it.status == "LOW_CONFIDENCE" || it.status == "VIEW_LIMITED" }
    }

    private fun traceFlagFor(
        summary: RepTraceSummary,
        qualityFlags: List<QualityFlag>,
    ): QualityFlag? {
        if (summary.pathDeviationFromBaseline < PATH_DEVIATION_MONITOR_THRESHOLD) return null

        val hasReliableWarning = qualityFlags.any {
            it.status == "WARNING" || it.status == "CRITICAL"
        }
        val status = if (hasReliableWarning && summary.pathDeviationFromBaseline >= PATH_DEVIATION_WARNING_THRESHOLD) {
            "WARNING"
        } else {
            "MONITOR"
        }
        return QualityFlag(
            id = "trace_deviation",
            status = status,
            value = summary.pathDeviationFromBaseline,
            threshold = if (status == "WARNING") {
                PATH_DEVIATION_WARNING_THRESHOLD
            } else {
                PATH_DEVIATION_MONITOR_THRESHOLD
            },
            evidence = "rep_trace_summary",
            reason = "path_deviation_from_personal_envelope",
            rule = 0,
            joint = "center_of_mass",
        )
    }

    private fun shouldUpdateEnvelope(
        summary: RepTraceSummary,
        qualityFlags: List<QualityFlag>,
        traceFlag: QualityFlag?,
    ): Boolean {
        if (summary.confidenceCoverage < CLEAN_REP_CONFIDENCE_COVERAGE) return false
        if (traceFlag?.status == "WARNING") return false
        return qualityFlags.none {
            it.status == "WARNING" ||
                it.status == "CRITICAL" ||
                it.status == "LOW_CONFIDENCE" ||
                it.status == "VIEW_LIMITED"
        }
    }

    private fun updateEnvelope(summary: RepTraceSummary): PersonalTraceEnvelope {
        val current = envelopes[summary.exercise]
        val updated = if (current == null) {
            PersonalTraceEnvelope(
                exercise = summary.exercise,
                cleanRepCount = 1,
                avgTempoSec = summary.tempoSec,
                avgRomProxyDeg = summary.romProxyDeg,
                avgLateralSwayProxy = summary.lateralSwayProxy,
                avgSmoothnessProxy = summary.smoothnessProxy,
            )
        } else {
            val nextCount = current.cleanRepCount + 1
            current.copy(
                cleanRepCount = nextCount,
                avgTempoSec = runningAverage(current.avgTempoSec, summary.tempoSec, nextCount),
                avgRomProxyDeg = runningAverage(current.avgRomProxyDeg, summary.romProxyDeg, nextCount),
                avgLateralSwayProxy = runningAverage(
                    current.avgLateralSwayProxy,
                    summary.lateralSwayProxy,
                    nextCount,
                ),
                avgSmoothnessProxy = runningAverage(
                    current.avgSmoothnessProxy,
                    summary.smoothnessProxy,
                    nextCount,
                ),
            )
        }
        envelopes[summary.exercise] = updated
        return updated
    }

    private fun runningAverage(previousAverage: Float, newValue: Float, nextCount: Int): Float {
        return ((previousAverage * (nextCount - 1)) + newValue) / nextCount
    }

    private companion object {
        const val COM_PROXY_JOINT_ID = 100
        const val LIVE_TRACE_WINDOW_FRAMES = 30
        const val MAX_REP_FRAMES = 120
        const val MIN_VISIBLE_JOINT_CONFIDENCE = 0.2f
        const val MIN_VISIBLE_TRACKED_JOINTS = 5
        const val MIN_TRACE_CONFIDENCE = 0.6f
        const val MIN_CONFIDENCE_COVERAGE = 0.6f
        const val CLEAN_REP_CONFIDENCE_COVERAGE = 0.8f
        const val MIN_BASELINE_REPS = 1
        const val PATH_DEVIATION_MONITOR_THRESHOLD = 0.35f
        const val PATH_DEVIATION_WARNING_THRESHOLD = 0.35f

        val TRACKED_JOINTS = listOf(11, 12, 15, 16, 23, 24, 25, 26, 27, 28)
    }
}
