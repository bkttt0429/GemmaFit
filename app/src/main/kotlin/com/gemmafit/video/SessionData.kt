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
    val isPreviewData: Boolean = false,
    val detection: ExerciseDetection = ExerciseDetection(),
    val safetyEvents: List<SafetyEvent> = emptyList(),
    val formScores: List<FormScorePoint> = emptyList(),
    val viewLimitedCount: Int = 0,
    val lowConfidenceCount: Int = 0,
    val notApplicableCounts: Map<String, Int> = emptyMap(),
    val muscleFocusDistribution: Map<String, Int> = emptyMap(),
    val repHistory: List<RepRecord> = emptyList(),
    val personalTraceEnvelope: PersonalTraceEnvelope? = null,
    val coachTips: List<String> = emptyList(),
    val aiInsights: List<CoachInsight> = emptyList(),
    val sessionCoachInsight: SessionCoachInsight = SessionCoachInsight(),
    val capabilityContract: CapabilityContract = CapabilityContract(),
    val evidenceRefs: List<String> = emptyList(),
    val activityContext: ActivityContext = ActivityContext.unknown(),
    val visualContext: SessionVisualContext = SessionVisualContext.unknown(),
    val reviewCues: List<ReviewCue> = emptyList(),
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
 * Deterministic review-time cue anchored to a processed frame.
 * The model may explain these cues later, but it must not invent their time or frame.
 */
data class ReviewCue(
    val frameIndex: Int = 0,
    val timestampMs: Long = 0L,
    val severity: String = "watch",
    val kind: String = "movement",
    val title: String = "",
    val suggestion: String = "",
    val evidenceRef: String = "",
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
 * A single tracked point used by the short-lived motion trace layer.
 * These points are runtime evidence only; they are not written to long-term memory.
 */
data class TracePoint(
    val frameIndex: Int,
    val timestampMs: Long,
    val jointId: Int,
    val x: Float,
    val y: Float,
    val visibility: Float,
)

/**
 * Rep-level trace evidence safe for Evidence Cards, memory summaries, and Gemma prompts.
 * It intentionally stores derived movement evidence, not raw video or full landmark history.
 */
data class RepTraceSummary(
    val repNumber: Int,
    val exercise: String,
    val tempoSec: Float,
    val romProxyDeg: Float,
    val peakVelocityDegS: Float,
    val smoothnessProxy: Float,
    val lateralSwayProxy: Float,
    val pathDeviationFromBaseline: Float,
    val confidenceCoverage: Float,
)

/**
 * Runtime personal trace envelope learned only from high-confidence clean reps.
 */
data class PersonalTraceEnvelope(
    val exercise: String,
    val cleanRepCount: Int = 0,
    val avgTempoSec: Float = 0f,
    val avgRomProxyDeg: Float = 0f,
    val avgLateralSwayProxy: Float = 0f,
    val avgSmoothnessProxy: Float = 0f,
)

/**
 * A completed rep record.
 */
data class RepRecord(
    val repNumber: Int,
    val formQuality: Float,
    val rangeOfMotionDeg: Float,
    val hadViolations: Boolean,
    val traceSummary: RepTraceSummary? = null,
    val warningNames: List<String> = emptyList(),
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
    val movementPhase: String = "unknown",
    val currentMuscleFocus: MuscleFocusResult? = null,
    val detectedExercise: String = "unknown",
    val exerciseConfidence: Float = 0f,
    val exerciseBasis: List<String> = emptyList(),
    val templateMetrics: Map<String, Float> = emptyMap(),
    val coachMessage: String = "",
    val coachPriority: String = "low",
    val coachInsight: CoachInsight = CoachInsight(),
    val qualityFlags: List<QualityFlag> = emptyList(),
    val trustMatrix: List<TrustMatrixItem> = defaultTrustMatrix(),
    val evidenceCard: EvidenceCard = EvidenceCard(),
    val capabilityContract: CapabilityContract = CapabilityContract(),
    val motionZipStatus: MotionZipUiState = MotionZipUiState(),
    // Latest detected landmarks for skeleton overlay
    val poseLandmarks: List<PoseLandmarkData> = emptyList(),
    val poseTrajectory: List<List<PoseLandmarkData>> = emptyList(),
    val poseCandidates: List<PoseCandidate> = emptyList(),
    val activeSubjectIndex: Int? = null,
    val activeSubjectTrackId: Int? = null,
    val subjectLockStatus: SubjectLockStatus = SubjectLockStatus.NEEDS_SELECTION,
    val subjectTrustFlags: List<String> = emptyList(),
    val analysisStage: String = "",
    val isPreviewData: Boolean = false,
    val fullProgress: Float = 0f,
    val videoPreview: Bitmap? = null,
    val videoPreviewWidth: Int = 0,
    val videoPreviewHeight: Int = 0,
    val imageWidth: Int = 1080,
    val imageHeight: Int = 1920,
    val repHistory: List<RepRecord> = emptyList(),
    val activityContext: ActivityContext = ActivityContext.unknown(),
    val visualContext: SessionVisualContext = SessionVisualContext.unknown(),
    val sessionStatus: SessionStatusSnapshot = SessionStatusSnapshot(),
    val reviewCues: List<ReviewCue> = emptyList(),
    // Frame navigation
    val currentFrameIndex: Int = 0,
    val currentFrameTimestampMs: Long = 0L,
    val latestProcessedTimestampMs: Long = 0L,
    val totalFramesAnalyzed: Int = 0,
    val reviewFrameStatus: ReviewFrameStatus = ReviewFrameStatus(),
    val reviewTargetChangedAfterAnalysis: Boolean = false,
    val targetReanalysisAvailable: Boolean = false,
    val targetReanalysisActive: Boolean = false,
)

data class SessionStatusSnapshot(
    val ready: Boolean = false,
    val exercise: String = "unknown",
    val formScore: Int = 0,
    val repCount: Int = 0,
    val phase: String = "complete",
    val activityContext: ActivityContext = ActivityContext.unknown(),
    val visualContext: SessionVisualContext = SessionVisualContext.unknown(),
)

enum class SubjectLockStatus {
    NEEDS_SELECTION,
    LOCKED,
    AUTO_LOCKED,
    SUBJECT_LOST,
    SINGLE_AUTO,
}

data class ReviewFrameStatus(
    val frameIndex: Int = 0,
    val timestampMs: Long = 0L,
    val bitmapCached: Boolean = false,
    val bitmapRestoring: Boolean = false,
    val bitmapRestored: Boolean = false,
    val bitmapRestoreFailed: Boolean = false,
    val restoreLatencyMs: Long? = null,
    val landmarkCount: Int = 0,
    val selectedLandmarkCount: Int = 0,
    val candidateCount: Int = 0,
    val poseAvailable: Boolean = false,
    val poseHiddenByQuality: Boolean = false,
    val noPoseReason: String = "",
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
)

data class PoseLandmarkData(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
)

data class PoseBoundingBox(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val width: Float get() = (maxX - minX).coerceAtLeast(0f)
    val height: Float get() = (maxY - minY).coerceAtLeast(0f)
    val area: Float get() = width * height

    fun contains(x: Float, y: Float): Boolean {
        return x in minX..maxX && y in minY..maxY
    }
}

data class PoseCandidate(
    val landmarks: List<PoseLandmarkData>,
    val bbox: PoseBoundingBox,
    val centerX: Float,
    val centerY: Float,
    val avgVisibility: Float,
    val trackScore: Float = 0f,
    val trackId: Int = 0,
    val appearance: SubjectAppearanceSignature? = null,
    val appearanceScore: Float = 0.5f,
    val matchMargin: Float = 0f,
    val identityScore: Float = 0f,
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

data class CoachInsight(
    val message: String = "",
    val priority: String = "low",
    val localizationKey: String = "",
    val backend: String = "fallback",
    val functionName: String = "",
    val argsJson: String = "{}",
    val selectionBasis: String = "",
    val evidenceRefs: List<String> = emptyList(),
    val summaryNarrative: String = "",
    val modelInfo: String = "{}",
    val modelStatus: SessionCoachModelStatus = SessionCoachModelStatus.FALLBACK,
    val fallback: Boolean = true,
)

data class SessionCoachContext(
    val totalFrames: Int,
    val totalReps: Int,
    val avgFormScore: Float,
    val durationSeconds: Int,
    val mainExercise: String,
    val exerciseConfidence: Float,
    val detectedExercises: Map<String, Int>,
    val safetyEvents: List<SafetyEvent>,
    val formScores: List<FormScorePoint>,
    val viewLimitedCount: Int,
    val lowConfidenceCount: Int,
    val notApplicableCounts: Map<String, Int>,
    val muscleFocusDistribution: Map<String, Int>,
    val repHistory: List<RepRecord>,
    val personalTraceEnvelope: PersonalTraceEnvelope? = null,
    val capabilityContract: CapabilityContract = CapabilityContract(),
    val evidenceRefs: List<String> = emptyList(),
    val seniorHeroMode: Boolean = false,
    val activityContext: ActivityContext = ActivityContext.unknown(),
    val visualContext: SessionVisualContext = SessionVisualContext.unknown(),
    /**
     * User's resolved locale at session-summary time. Threaded into the E2B
     * prompt's `locale` field so the model emits care log wording in the
     * matching language, and surfaced in the deterministic fallback renderer
     * so template strings match too. Defaults to en-US to keep behavior
     * stable for any caller not yet updated to inject the user's choice.
     */
    val locale: com.gemmafit.settings.ResolvedLocale = com.gemmafit.settings.ResolvedLocale.EN_US,
)

enum class SessionCoachModelStatus {
    PENDING,
    MODEL,
    FALLBACK,
}

enum class SessionCoachStreamPhase {
    IDLE,
    QUEUED,
    PREFILL,
    STREAMING,
    VALIDATING,
    COMPLETE,
}

data class CoachInferenceStreamUpdate(
    val phase: SessionCoachStreamPhase,
    val backend: String = "",
    val partialText: String = "",
    val tokenCount: Int = 0,
    val firstTokenTimeMs: Long? = null,
    val constrainedDecoding: Boolean = false,
    val error: String = "",
)

fun interface CoachInferenceStreamObserver {
    fun onUpdate(update: CoachInferenceStreamUpdate)
}

data class SessionCoachInsight(
    val headline: String = "",
    val whatISaw: String = "",
    val whyItMatters: String = "",
    val notJudged: String = "",
    val nextFocus: String = "",
    val backend: String = "fallback",
    val functionName: String = "",
    val evidenceRefs: List<String> = emptyList(),
    val selectionBasis: String = "",
    val inferenceTimeMs: Double = 0.0,
    val modelInfo: String = "{}",
    val modelFileName: String = "",
    val modelPath: String = "",
    val initTimeMs: Long? = null,
    val attemptCount: Int = 0,
    val firstError: String = "",
    val retryError: String = "",
    val streamingPhase: SessionCoachStreamPhase = SessionCoachStreamPhase.IDLE,
    val streamingText: String = "",
    val streamTokenCount: Int = 0,
    val firstTokenTimeMs: Long? = null,
    val constrainedDecoding: Boolean = false,
    val modelStatus: SessionCoachModelStatus = SessionCoachModelStatus.FALLBACK,
    val fallback: Boolean = true,
)

enum class CoachTriggerEvent {
    WARNING_CHANGED,
    REP_COMPLETED,
    FULL_ANALYSIS_COMPLETE,
}

object CoachTriggerPolicy {
    const val MODE = "SUMMARY_ONLY"

    fun shouldTrigger(event: CoachTriggerEvent): Boolean {
        return event == CoachTriggerEvent.FULL_ANALYSIS_COMPLETE
    }
}

data class QualityFlag(
    val id: String,
    val evidenceId: String = "",
    val status: String,  // OK, MONITOR, WARNING, CRITICAL, NOT_APPLICABLE, LOW_CONFIDENCE, VIEW_LIMITED
    val value: Float,
    val threshold: Float,
    val evidence: String,
    val reason: String = "",
    val rule: Int = 0,
    val joint: String = "",
)

data class EvidenceDagNode(
    val id: String,
    val type: String,
    val label: String,
    val metric: String,
    val value: Float,
    val unit: String,
    val confidence: Float,
    val status: String,
    val sourceModule: String,
    val sourceFunction: String,
    val frameRange: String,
    val evidenceLevel: String = "",
    val reason: String = "",
    val landmarkRefs: List<String> = emptyList(),
)

data class EvidenceDagEdge(
    val from: String,
    val to: String,
    val relation: String,
)

data class EvidenceDag(
    val nodes: List<EvidenceDagNode> = emptyList(),
    val edges: List<EvidenceDagEdge> = emptyList(),
) {
    val ids: Set<String> get() = nodes.map { it.id }.toSet()
}

data class CapabilityJudgment(
    val metric: String,
    val reason: String = "",
    val confidenceCeiling: Float = 0f,
    val requiredEvidence: List<String> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
)

data class CapabilityContract(
    val canJudge: List<CapabilityJudgment> = emptyList(),
    val cannotJudge: List<CapabilityJudgment> = emptyList(),
) {
    val evidenceRefs: List<String>
        get() = (canJudge.flatMap { it.evidenceRefs } + cannotJudge.flatMap { it.evidenceRefs })
            .filter { it.isNotBlank() }
            .distinct()
}

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
    val evidenceRefs: List<String> = emptyList(),
    val capabilityCanJudge: List<String> = emptyList(),
    val capabilityCannotJudge: List<String> = emptyList(),
    val unsupportedJudgments: List<String> = listOf(
        "fall_risk_prediction",
        "joint_force",
        "clinical_injury_risk",
        "medical_diagnosis",
        "muscle_activation_percentage",
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
    val elapsedSeconds: Float = 0f,
    val processingFps: Float = 0f,
    val etaSeconds: Int = 0,
    val poseHitRate: Float = 0f,
    val poseHits: Int = 0,
    val poseMisses: Int = 0,
    val subPhase: String = "",
    val subPhaseProgress: Float = 0f,
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
