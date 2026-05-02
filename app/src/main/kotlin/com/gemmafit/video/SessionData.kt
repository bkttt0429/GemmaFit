package com.gemmafit.video

import android.graphics.Bitmap
import com.gemmafit.jni.KinematicsBridge

/**
 * Session-level data collected across all analyzed frames.
 * Used by SummaryScreen for post-workout review.
 */
data class SessionSummary(
    val totalFrames: Int = 0,
    val totalReps: Int = 0,
    val avgFormScore: Float = 100f,
    val durationSeconds: Int = 0,
    val detection: ExerciseDetection = ExerciseDetection(),
    val safetyEvents: List<SafetyEvent> = emptyList(),
    val formScores: List<FormScorePoint> = emptyList(),
    val muscleFocusDistribution: Map<String, Int> = emptyMap(),
    val repHistory: List<RepRecord> = emptyList(),
    val coachTips: List<String> = emptyList(),
)

/**
 * Exercise auto-detection result for the session.
 */
data class ExerciseDetection(
    val mainExercise: String = "unknown",
    val confidence: Float = 0f,
    val detectedExercises: Map<String, Int> = emptyMap(),
)

/**
 * A single safety event logged during the workout.
 */
data class SafetyEvent(
    val rule: Int,
    val functionName: String,
    val description: String,
    val severity: String,
    val joint: String = "",
    val frameIndex: Int = 0,
    val timestampSeconds: Int = 0,
)

/**
 * Form score sample point for trend chart.
 */
data class FormScorePoint(
    val frameIndex: Int,
    val score: Int,
    val timestampSeconds: Int,
)

/**
 * A completed rep record.
 */
data class RepRecord(
    val repNumber: Int,
    val formQuality: Float,
    val rangeOfMotionDeg: Float,
    val hadViolations: Boolean,
)

/**
 * Live workout state exposed to WorkoutScreen.
 */
data class LiveWorkoutState(
    val source: VideoSource = VideoSource.Camera,
    val phase: VideoPhase = VideoPhase.Idle,
    val repCount: Int = 0,
    val formScore: Int = 100,
    val symmetryScore: Float = 1f,
    val activeWarnings: List<SafetyWarning> = emptyList(),
    val currentPattern: String = "unknown",
    val currentMuscleFocus: MuscleFocusResult? = null,
    val detectedExercise: String = "unknown",
    val exerciseConfidence: Float = 0f,
    val exerciseBasis: List<String> = emptyList(),
    val templateMetrics: Map<String, Float> = emptyMap(),
    val coachMessage: String = "",
    val coachPriority: String = "low",
    val qualityFlags: List<QualityFlag> = emptyList(),
    val trustMatrix: List<TrustMatrixItem> = defaultTrustMatrix(),
    val evidenceCard: EvidenceCard = EvidenceCard(),
    // Latest detected landmarks for skeleton overlay
    val poseLandmarks: List<PoseLandmarkData> = emptyList(),
    val poseTrajectory: List<List<PoseLandmarkData>> = emptyList(),
    val videoPreview: Bitmap? = null,
    val videoPreviewWidth: Int = 0,
    val videoPreviewHeight: Int = 0,
    val imageWidth: Int = 1080,
    val imageHeight: Int = 1920,
    // Frame navigation
    val currentFrameIndex: Int = 0,
    val currentFrameTimestampMs: Long = 0L,
    val totalFramesAnalyzed: Int = 0,
)

data class PoseLandmarkData(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
)

data class SafetyWarning(
    val rule: Int,
    val functionName: String,
    val message: String,
    val severity: String,
    val joint: String = "",
)

data class MuscleFocusResult(
    val primary: List<String>,
    val secondary: List<String>,
    val pattern: String,
    val confidence: String,
)

data class QualityFlag(
    val id: String,
    val status: String,  // OK, MONITOR, WARNING, CRITICAL, NOT_APPLICABLE, LOW_CONFIDENCE, VIEW_LIMITED
    val value: Float,
    val threshold: Float,
    val evidence: String,
    val reason: String = "",
    val rule: Int = 0,
    val joint: String = "",
)

data class TrustMatrixItem(
    val status: String,
    val label: String,
    val active: Boolean,
    val description: String,
)

data class EvidenceItem(
    val label: String,
    val value: String,
)

data class EvidenceCard(
    val verdict: String = "OK",
    val reason: String = "No active issue.",
    val evidence: List<EvidenceItem> = emptyList(),
    val trustFlags: List<String> = emptyList(),
    val unsupportedJudgments: List<String> = listOf(
        "joint_force",
        "clinical_injury_risk",
        "medical_diagnosis",
    ),
    val modelBoundary: String = "Movement quality feedback only, not medical diagnosis.",
)

fun defaultTrustMatrix(): List<TrustMatrixItem> = listOf(
    TrustMatrixItem("OK", "OK", true, "Evidence supports normal movement-quality feedback."),
    TrustMatrixItem("VIEW_LIMITED", "View", false, "Camera angle or crop limits this judgment."),
    TrustMatrixItem("LOW_CONFIDENCE", "Low conf", false, "Pose tracking is not stable enough for a risk grade."),
    TrustMatrixItem("NOT_APPLICABLE", "N/A", false, "The rule does not apply to this exercise or view."),
    TrustMatrixItem("MONITOR", "Watch", false, "Proxy metric is observable but not a hard warning."),
    TrustMatrixItem("WARNING", "Warn", false, "Reliable evidence crossed a prototype threshold."),
    TrustMatrixItem("CRITICAL", "Reset", false, "Severe or repeated evidence asks the user to stop and reset."),
)

data class VideoAnalysisState(
    val source: VideoSource = VideoSource.Camera,
    val phase: VideoPhase = VideoPhase.Idle,
    val progress: Float = 0f,
    val currentFrame: Int = 0,
    val totalFrames: Int = 0,
    val errorMessage: String? = null,
)

sealed class VideoSource {
    data object Camera : VideoSource()
    data class VideoFile(val uri: String, val displayName: String) : VideoSource()
}

sealed class VideoPhase {
    data object Idle : VideoPhase()
    data object Selecting : VideoPhase()
    data class Processing(val progress: Float) : VideoPhase()
    data class Analyzing(val frameIndex: Int, val totalFrames: Int) : VideoPhase()
    data class Complete(val frameCount: Int) : VideoPhase()
    data class Error(val message: String) : VideoPhase()
    data object Paused : VideoPhase()
}
