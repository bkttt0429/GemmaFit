package com.gemmafit.video

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemmafit.debug.GemmaFitDebugApi
import com.gemmafit.jni.KinematicsBridge
import com.gemmafit.pose.PosePresenceGate
import com.gemmafit.settings.AppSettings
import com.gemmafit.voice.CoachVoice
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

class VideoAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "GemmaFit.VM"
        private const val TARGET_ANALYSIS_INTERVAL_MS = 200L
        private const val PREVIEW_PROBE_MAX_FRAMES = 12
        private const val PREVIEW_LONG_SIDE = 256
        private const val FULL_LONG_SIDE = 512
        private const val REVIEW_RECOVERY_LONG_SIDE = 768
        private const val ENABLE_REVIEW_POSE_RECOVERY = false
        private const val POSE_TIMELINE_DEBUG_FRAME_LIMIT = 80
        private const val ENABLE_NO_POSE_ISLAND_RETRY = true
        private const val NO_POSE_RETRY_LONG_SIDE = 768
        private const val NO_POSE_RETRY_EXPAND_MS = 600L
        private const val NO_POSE_RETRY_MIN_FRAMES = 2
        private const val NO_POSE_RETRY_MAX_ISLANDS = 6
        private const val MAX_POSE_CANDIDATES = 4
        private const val PREVIEW_POSE_CANDIDATES = 1
        private const val FULL_POSE_CANDIDATES = 4
        private const val VIDEO_PROGRESS_UPDATE_INTERVAL_MS = 250L
        private const val PREVIEW_EARLY_POSE_HITS = 2
        private const val SUBJECT_LOST_FRAMES = 5
        private const val SUBJECT_MIN_VISIBILITY = 0.35f
        private const val SUBJECT_MATCH_THRESHOLD = 0.25f
        private const val AUTO_LOCK_MIN_SCORE = 0.62f
        private const val AUTO_LOCK_MARGIN = 0.12f
        private const val AUTO_LOCK_STABLE_FRAMES = 2
        private const val MULTI_PERSON_DETECTOR_INTERVAL_FRAMES = 15
        private const val MULTI_PERSON_DETECTOR_HOLD_FRAMES = 20
        private const val MULTI_PERSON_MIN_PROPOSALS = 2
        private const val MAX_NATIVE_TEMPORAL_DT_MS = 800L
        private const val LIVE_OVERLAY_POSE_CONFIDENCE_FLOOR = 0.6f
        private const val ENABLE_NATIVE_SESSION_SUMMARY = true
        private const val ENABLE_SESSION_VISUAL_SIDECAR = true
        private const val ENABLE_ASYNC_LIVE_VISUAL_SIDECAR = true
        private const val SESSION_SUMMARY_PREWARM_PROGRESS = 0.70f
        private const val SESSION_VISUAL_SIDECAR_TIMEOUT_MS = 12_000L
        private const val SESSION_VISUAL_SCENE_LONG_SIDE = 512
        private const val SESSION_VISUAL_PANEL_LONG_SIDE = 768
        private const val SESSION_VISUAL_COMPOSITE_LONG_SIDE = 768
        private const val SESSION_VISUAL_MODE_MOTIONZIP_PANEL = "motionzip_panel"
        private const val SESSION_VISUAL_MODE_SCENE_ONLY = "scene_only"
        private const val EARLY_VIDEO_VISUAL_CONTEXT_TIMEOUT_MS = 12_000L
        private const val EARLY_VIDEO_VISUAL_LONG_SIDE = 384
        private const val EARLY_VIDEO_VISUAL_MAX_FRAME_INDEX = 10
        private const val LIVE_VISUAL_SIDECAR_TIMEOUT_MS = 8_000L
        private const val LIVE_VISUAL_SCENE_LONG_SIDE = 384
        private const val LIVE_VISUAL_MODE_KEY_FRAME = "live_key_frame"
        private const val SENIOR_HERO_PRIMARY_VIDEO_DEMO = true
        private const val LIVE_CUE_MODEL_TIMEOUT_MS = 1_200L
        private const val REVIEW_CUE_COOLDOWN_MS = 2_500L
        private const val REVIEW_CUE_MAX_ITEMS = 10
        private val REVIEW_CUE_QUALITY_STATUSES = setOf(
            "CRITICAL",
            "WARNING",
            "VIEW_LIMITED",
            "LOW_CONFIDENCE",
            "NOT_APPLICABLE",
            "MONITOR",
        )
    }

    // ── Core state ─────────────────────────────────────────────────────
    private val _state = MutableStateFlow(VideoAnalysisState())
    val state: StateFlow<VideoAnalysisState> = _state.asStateFlow()

    private var poseLandmarker: PoseLandmarker? = null
    private var videoProcessor: VideoProcessor? = null
    private var processingJob: Job? = null
    private var reviewRecoveryJob: Job? = null
    private var reviewBitmapRestoreJob: Job? = null
    private var coachVoice: CoachVoice? = null
    private var poseInitFailed = false

    init {
        coachVoice = CoachVoice(application)
        GemmaFitDebugApi.initialize(application)
        GemmaFitDebugApi.record(
            category = "video_vm",
            message = "viewmodel_created",
            data = mapOf("target_interval_ms" to TARGET_ANALYSIS_INTERVAL_MS),
        )
        publishModelReadinessDebug(
            reason = "viewmodel_created",
            backend = CoachInsight().backend,
            fallback = CoachInsight().fallback,
            fallbackReason = CoachInsight().selectionBasis,
        )
    }

    // ── Live workout state (single flow for WorkoutScreen) ─────────────
    private val _live = MutableStateFlow(LiveWorkoutState())
    val live: StateFlow<LiveWorkoutState> = _live.asStateFlow()

    private val poseLandmarkerLock = Any()
    private val sessionDataLock = Any()
    private val processedFramesLock = Any()
    private val reviewRecoveryAttemptedFrameIndexes = mutableSetOf<Int>()
    private val reviewBitmapRestoredFrameIndexes = mutableSetOf<Int>()
    private val cameraPresenceEpoch = AtomicLong(0L)

    // ── Session-level data (accumulated across frames) ─────────────────
    private val _sessionSummary = MutableStateFlow(SessionSummary())
    val sessionSummary: StateFlow<SessionSummary> = _sessionSummary.asStateFlow()

    private var totalFramesAnalyzed = 0
    private var sessionStartMs = 0L
    private val formScoreHistory = mutableListOf<FormScorePoint>()
    private val safetyEventLog = mutableListOf<SafetyEvent>()
    private val exerciseDetectionCounts = mutableMapOf<String, Int>()
    private val muscleFocusCounts = mutableMapOf<String, Int>()
    private val coachTipsSet = mutableSetOf<String>()
    private val coachInsights = mutableListOf<CoachInsight>()
    private val notApplicableCounts = mutableMapOf<String, Int>()
    private var viewLimitedCount = 0
    private var lowConfidenceCount = 0
    private var sessionCoachInsight = SessionCoachInsight()
    private var seniorHeroMode = SENIOR_HERO_PRIMARY_VIDEO_DEMO
    private var sessionCapabilityContract = CapabilityContract()
    private val sessionEvidenceRefs = linkedSetOf<String>()
    private var lastCoachMessage = ""
    private var currentFrameIdx = 0
    private val kinematicsMutex = Mutex()
    private val coachInferenceRouter = CoachInferenceRouter(application)
    private var activeCoachInferenceJob: Job? = null
    private val sessionCoachInferenceDedupGuard = SessionCoachInferenceDedupGuard()
    private var analysisRunId: Long = 0L
    private var sessionSummaryPrewarmStartedForRun: Long = -1L
    private var earlyVideoVisualContextStartedForRun: Long = -1L
    private var activeEarlyVideoVisualContextJob: Job? = null
    private var activeLiveCueInferenceJob: Job? = null
    private var activeLiveVisionSidecarJob: Job? = null
    private val liveCueInferenceEventKeys = linkedSetOf<String>()
    private val asyncVisionSidecarGate = AsyncVisionSidecarGate()
    private var _lastCoachMessage: String? = null
    private var _lastCoachPriority: String? = null
    private var lastCoachInsight: CoachInsight = CoachInsight()
    private var reviewFramePinned = false
    private var cleanFrameStreak = 0
    private var manualSubjectLock = false
    private var pendingSubjectTap: Pair<Float, Float>? = null
    private var selectedTargetReanalysisSeed: ManualTargetReanalysisSeed? = null
    private var activeTargetReanalysisSeed: ManualTargetReanalysisSeed? = null
    private var lockedSubject: PoseCandidate? = null
    private var lockedSubjectAppearance: SubjectAppearanceSignature? = null
    private var lockedSubjectTrackId: Int? = null
    private var previousNativeLandmarks: FloatArray? = null
    private var previousNativeTimestampMs: Long? = null
    private var previousNativeTrackId: Int? = null
    private var pendingAutoSubject: PoseCandidate? = null
    private var pendingAutoSubjectFrames = 0
    private var nextSubjectTrackId = 1
    private var lostSubjectFrames = 0
    private var identityHoldFrames = 0
    private var multiPersonDetectorHoldUntilFrame = -1
    private var multiPersonDetectorProposalCount = 0
    private val subjectMotionTracker = SubjectMotionTracker()
    private val subjectRelocalizationPolicy = SubjectRelocalizationPolicy()
    private val personDetector: PersonDetector by lazy { PersonDetectorFactory.create(getApplication()) }
    private val liveCuePlanner = LiveCuePlanner()
    private val temporalAnalyzer = TemporalMotionAnalyzer()
    private val layer2Interpreter = Layer2TemporalInterpreter()
    private val activityContextTracker = ActivityContextTracker()
    private val motionTraceAnalyzer = MotionTraceAnalyzer()
    private val cameraLandmarkStabilizer = LandmarkStabilizer(alpha = 0.45f, deadband = 0.0025f)
    private val videoLandmarkStabilizer = LandmarkStabilizer(alpha = 0.35f, deadband = 0.0035f)
    private var cameraStabilizerTrackId: Int? = null
    private var videoStabilizerTrackId: Int? = null
    private var videoStabilizerPass: VideoAnalysisPass? = null
    private val repRecords = mutableListOf<RepRecord>()
    private val motionZipPackets = mutableListOf<MotionZipPacket>()
    private var sessionVisualContext = SessionVisualContext.unknown()
    private val liveVisionSnapshotLock = Any()
    private var latestLiveVisionSnapshot: LiveVisionFrameSnapshot? = null

    // Video frame storage for navigation (updated during processing)
    private data class ProcessedFrame(
        val frameIndex: Int,
        val timestampMs: Long,
        val bitmap: Bitmap? = null,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val landmarks: List<PoseLandmarkData>,
        val poseCandidates: List<PoseCandidate> = emptyList(),
        val activeSubjectIndex: Int? = null,
        val activeSubjectTrackId: Int? = null,
        val subjectLockStatus: SubjectLockStatus = SubjectLockStatus.NEEDS_SELECTION,
        val subjectTrustFlags: List<String> = emptyList(),
        val exercise: String = "unknown",
        val exerciseConfidence: Float = 0f,
        val movementPhase: String = "unknown",
        val warnings: List<SafetyWarning> = emptyList(),
        val qualityFlags: List<QualityFlag> = emptyList(),
        val templateMetrics: Map<String, Float> = emptyMap(),
        val trustMatrix: List<TrustMatrixItem> = defaultTrustMatrix(),
        val evidenceCard: EvidenceCard = EvidenceCard(),
        val capabilityContract: CapabilityContract = CapabilityContract(),
        val coachMessage: String = "",
        val coachPriority: String = "low",
    )

    private data class LiveVisionFrameSnapshot(
        val bitmap: Bitmap,
        val frameIndex: Int,
        val timestampMs: Long,
        val phase: String,
        val poseConfidence: Float,
        val fullBodyVisibility: Float,
        val subjectObserved: Boolean,
        val subjectStable: Boolean,
        val evidenceRefs: List<String>,
    )

    private data class CompletionFrameCandidate(
        val index: Int,
        val score: Int,
        val hiddenByQuality: Boolean,
        val lowConfidencePreview: Boolean,
        val subjectBlocked: Boolean,
    )

    private data class ManualTargetReanalysisSeed(
        val candidate: PoseCandidate,
        val appearance: SubjectAppearanceSignature?,
        val sourceFrameIndex: Int,
        val sourceTimestampMs: Long,
        val tapX: Float,
        val tapY: Float,
        val trackId: Int,
    )

    private val processedFrames = mutableListOf<ProcessedFrame>()

    private data class NoPoseIsland(
        val startFrameIndex: Int,
        val endFrameIndex: Int,
        val startTimestampMs: Long,
        val endTimestampMs: Long,
        val frameCount: Int,
    ) {
        fun toDebugMap(): Map<String, Any> = mapOf(
            "start_frame" to startFrameIndex,
            "end_frame" to endFrameIndex,
            "start_timestamp_ms" to startTimestampMs,
            "end_timestamp_ms" to endTimestampMs,
            "frame_count" to frameCount,
        )
    }

    private data class NoPoseRetrySummary(
        val enabled: Boolean,
        val attempted: Boolean,
        val islandCount: Int = 0,
        val targetFrameCount: Int = 0,
        val recoveredCount: Int = 0,
        val unresolvedCount: Int = 0,
        val elapsedMs: Long = 0L,
        val reason: String = "",
        val islands: List<NoPoseIsland> = emptyList(),
    ) {
        fun toDebugMap(): Map<String, Any> = mapOf(
            "enabled" to enabled,
            "attempted" to attempted,
            "island_count" to islandCount,
            "target_frame_count" to targetFrameCount,
            "recovered_count" to recoveredCount,
            "unresolved_count" to unresolvedCount,
            "elapsed_ms" to elapsedMs,
            "reason" to reason,
            "islands" to islands.map { it.toDebugMap() },
        )
    }

    private data class NoPoseRetryTarget(
        val processedIndex: Int,
        val frame: ProcessedFrame,
    )

    // Fixed-size bitmap window: oldest frame's bitmap recycled when > 60

    private fun currentPoseLandmarker(): PoseLandmarker? = synchronized(poseLandmarkerLock) {
        poseLandmarker
    }

    private fun hasPoseLandmarker(): Boolean = synchronized(poseLandmarkerLock) {
        poseLandmarker != null
    }

    private fun replacePoseLandmarker(landmarker: PoseLandmarker?) {
        var previous: PoseLandmarker? = null
        synchronized(poseLandmarkerLock) {
            if (poseLandmarker !== landmarker) {
                previous = poseLandmarker
                poseLandmarker = landmarker
            }
        }
        previous?.let { old ->
            runCatching { old.close() }
                .onFailure { Log.w(TAG, "PoseLandmarker close failed: ${it.message}", it) }
        }
    }

    private fun closePoseLandmarker() {
        replacePoseLandmarker(null)
    }

    private fun invalidateCameraPoseWrites() {
        cameraPresenceEpoch.incrementAndGet()
    }

    private fun isCurrentCameraPoseWrite(epoch: Long?): Boolean {
        if (epoch == null) return true
        return _state.value.source is VideoSource.Camera && cameraPresenceEpoch.get() == epoch
    }

    private fun isVideoProcessingPhase(): Boolean {
        val phase = _state.value.phase
        return phase is VideoPhase.Processing || phase is VideoPhase.Analyzing
    }

    private fun shouldPreservePinnedReviewFrame(): Boolean {
        return reviewFramePinned && isVideoProcessingPhase()
    }

    private fun safeBitmapCopy(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return null
        return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    }

    private fun replaceLatestLiveVisionSnapshot(snapshot: LiveVisionFrameSnapshot?) {
        var previous: LiveVisionFrameSnapshot? = null
        synchronized(liveVisionSnapshotLock) {
            previous = latestLiveVisionSnapshot
            latestLiveVisionSnapshot = snapshot
        }
        previous?.bitmap?.recycleIfAlive()
    }

    private fun copyLatestLiveVisionSnapshot(): LiveVisionFrameSnapshot? {
        return synchronized(liveVisionSnapshotLock) {
            val current = latestLiveVisionSnapshot ?: return@synchronized null
            val bitmapCopy = safeBitmapCopy(current.bitmap) ?: return@synchronized null
            current.copy(bitmap = bitmapCopy)
        }
    }

    private fun currentVideoUri(): Uri? {
        return (_state.value.source as? VideoSource.VideoFile)?.uri?.let { Uri.parse(it) }
    }

    private fun restoredReviewBitmap(
        frame: ProcessedFrame,
        longSide: Int = FULL_LONG_SIDE,
    ): Bitmap? {
        val uri = currentVideoUri() ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication(), uri)
            analysisScaledFrameAtTime(retriever, frame.timestampMs, longSide)
        } catch (e: Exception) {
            Log.w(TAG, "Review frame restore failed at ${frame.timestampMs}ms: ${e.message}")
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun processedFrameCount(): Int = synchronized(processedFramesLock) {
        processedFrames.size
    }

    private fun hasProcessedFrame(index: Int): Boolean = synchronized(processedFramesLock) {
        index in processedFrames.indices
    }

    private fun frameAtOrNull(index: Int): ProcessedFrame? = synchronized(processedFramesLock) {
        processedFrames.getOrNull(index)
    }

    private fun processedFrameForFrameIndex(frameIndex: Int): ProcessedFrame? = synchronized(processedFramesLock) {
        processedFrames.firstOrNull { it.frameIndex == frameIndex }
    }

    private fun completionReviewFrameSelection(): Pair<Int, String> = synchronized(processedFramesLock) {
        if (processedFrames.isEmpty()) {
            return@synchronized 0 to "no_processed_frames"
        }

        val candidates = processedFrames.indices.mapNotNull { index ->
            val frame = processedFrames[index]
            val landmarks = completionDisplayLandmarks(frame) ?: return@mapNotNull null
            val hiddenByQuality = OverlayPoseQualityGate.shouldHideSkeleton(
                landmarks = landmarks,
                qualityFlags = frame.qualityFlags,
                evidenceCard = frame.evidenceCard,
            )
            val lowConfidencePreview = shouldMarkPosePreviewLowConfidence(frame.qualityFlags)
            val subjectBlocked = shouldHideSelectedSkeleton(
                subjectTrustFlags = frame.subjectTrustFlags,
                subjectLockStatus = frame.subjectLockStatus,
            )
            var score = index
            if (!hiddenByQuality) score += 1_000
            if (!lowConfidencePreview) score += 160
            if (!subjectBlocked) score += 140
            if (frame.subjectLockStatus in setOf(
                    SubjectLockStatus.LOCKED,
                    SubjectLockStatus.AUTO_LOCKED,
                    SubjectLockStatus.SINGLE_AUTO,
                )
            ) score += 70
            if (frame.warnings.isNotEmpty()) score += 80
            if (frame.coachMessage.isNotBlank()) score += 55
            if (frame.templateMetrics.isNotEmpty()) score += 40
            if (frame.exercise != "unknown") score += 35
            CompletionFrameCandidate(
                index = index,
                score = score,
                hiddenByQuality = hiddenByQuality,
                lowConfidencePreview = lowConfidencePreview,
                subjectBlocked = subjectBlocked,
            )
        }

        val selected = candidates
            .filter { !it.hiddenByQuality && !it.lowConfidencePreview && !it.subjectBlocked }
            .maxWithOrNull(compareBy<CompletionFrameCandidate> { it.score }.thenBy { it.index })
        if (selected != null) {
            return@synchronized selected.index to "best_judgeable_renderable_frame"
        }

        val preview = candidates
            .filter { !it.hiddenByQuality && !it.subjectBlocked }
            .maxWithOrNull(compareBy<CompletionFrameCandidate> { it.score }.thenBy { it.index })
        if (preview != null) {
            return@synchronized preview.index to "best_renderable_preview_frame"
        }

        val renderable = candidates
            .filter { !it.hiddenByQuality }
            .maxWithOrNull(compareBy<CompletionFrameCandidate> { it.score }.thenBy { it.index })
        if (renderable != null) {
            return@synchronized renderable.index to "best_renderable_subject_limited_frame"
        }

        val fallbackIndex = currentFrameIdx.coerceIn(0, processedFrames.lastIndex)
        fallbackIndex to "fallback_current_review_frame"
    }

    private fun completionDisplayLandmarks(frame: ProcessedFrame): List<PoseLandmarkData>? {
        if (frame.landmarks.isNotEmpty() && hasRenderablePose(frame.landmarks)) {
            return frame.landmarks
        }
        val storedSubject = frame.poseCandidates.withIndex().firstOrNull { (candidateIndex, candidate) ->
            val matchesTrack = frame.activeSubjectTrackId != null &&
                candidate.trackId == frame.activeSubjectTrackId
            val matchesIndex = frame.activeSubjectIndex != null &&
                candidateIndex == frame.activeSubjectIndex
            (matchesTrack || matchesIndex) && hasRenderablePose(candidate.landmarks)
        }?.value
        if (storedSubject != null) return storedSubject.landmarks
        return frame.poseCandidates.firstOrNull { hasRenderablePose(it.landmarks) }?.landmarks
    }

    private fun processedIndexForFrameIndex(frameIndex: Int): Int? = synchronized(processedFramesLock) {
        processedFrames.indexOfFirst { it.frameIndex == frameIndex }.takeIf { it >= 0 }
    }

    private fun shouldSuppressPinnedReviewDisplayWrite(frameIndex: Int, cameraEpoch: Long?): Boolean {
        if (cameraEpoch != null) return false
        if (!shouldPreservePinnedReviewFrame()) return false
        return processedIndexForFrameIndex(frameIndex) != currentFrameIdx
    }

    private fun hasRenderableFramePose(frame: ProcessedFrame): Boolean {
        if (frame.landmarks.isNotEmpty() && hasRenderablePose(frame.landmarks)) return true
        return frame.poseCandidates.any { hasRenderablePose(it.landmarks) }
    }

    private fun noPoseTrustFlags(frame: ProcessedFrame): List<String> {
        return (frame.subjectTrustFlags + "SUBJECT_LOST" + "no_pose_frame").distinct()
    }

    private fun lowConfidencePosePreviewTrustFlags(frame: ProcessedFrame): List<String> {
        return (frame.subjectTrustFlags +
            "low_confidence" +
            "pose_preview_low_confidence" +
            "judgment_blocked_low_confidence").distinct()
    }

    private fun shouldMarkPosePreviewLowConfidence(qualityFlags: List<QualityFlag>): Boolean {
        return qualityFlags.any { it.status == "LOW_CONFIDENCE" }
    }

    private fun lowConfidencePoseReason(qualityFlags: List<QualityFlag>): String {
        return qualityFlags.firstOrNull { it.status == "LOW_CONFIDENCE" }
            ?.let { flag -> flag.reason.ifBlank { flag.id.ifBlank { "low_confidence" } } }
            ?: "low_confidence"
    }

    private fun shouldHideLiveOverlayBeforeNative(
        landmarks: List<PoseLandmarkData>,
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus,
    ): Boolean {
        if (landmarks.isEmpty()) return false
        if (shouldHideSelectedSkeleton(subjectTrustFlags, subjectLockStatus)) return true
        if (poseConfidenceFloor(landmarks) < LIVE_OVERLAY_POSE_CONFIDENCE_FLOOR) return true
        return OverlayPoseQualityGate.shouldHideSkeleton(
            landmarks = landmarks,
            qualityFlags = emptyList(),
            evidenceCard = EvidenceCard(),
        )
    }

    private fun overlayHiddenPoseTrustFlags(frame: ProcessedFrame): List<String> {
        return (frame.subjectTrustFlags +
            "pose_hidden_by_quality" +
            "pose_preview_keypoint_limited" +
            "judgment_blocked_keypoint_visibility").distinct()
    }

    private fun collectNoPoseIslands(): List<NoPoseIsland> = synchronized(processedFramesLock) {
        val islands = mutableListOf<NoPoseIsland>()
        var start: ProcessedFrame? = null
        var end: ProcessedFrame? = null
        var count = 0

        fun flushIsland() {
            val islandStart = start
            val islandEnd = end
            if (islandStart != null && islandEnd != null && count >= NO_POSE_RETRY_MIN_FRAMES) {
                islands += NoPoseIsland(
                    startFrameIndex = islandStart.frameIndex,
                    endFrameIndex = islandEnd.frameIndex,
                    startTimestampMs = islandStart.timestampMs,
                    endTimestampMs = islandEnd.timestampMs,
                    frameCount = count,
                )
            }
            start = null
            end = null
            count = 0
        }

        processedFrames.forEach { frame ->
            val missingPose = !hasRenderableFramePose(frame)
            if (missingPose) {
                if (start == null) start = frame
                end = frame
                count += 1
            } else {
                flushIsland()
            }
        }
        flushIsland()
        islands.take(NO_POSE_RETRY_MAX_ISLANDS)
    }

    private fun framesForNoPoseRetry(islands: List<NoPoseIsland>): List<NoPoseRetryTarget> = synchronized(processedFramesLock) {
        if (islands.isEmpty()) return@synchronized emptyList()
        val retryWindows = islands.map { island ->
            (island.startTimestampMs - NO_POSE_RETRY_EXPAND_MS).coerceAtLeast(0L) to
                (island.endTimestampMs + NO_POSE_RETRY_EXPAND_MS)
        }
        processedFrames
            .mapIndexedNotNull { index, frame ->
                val inRetryWindow = retryWindows.any { (startMs, endMs) ->
                    frame.timestampMs in startMs..endMs
                }
                if (!hasRenderableFramePose(frame) && inRetryWindow) {
                    NoPoseRetryTarget(processedIndex = index, frame = frame)
                } else {
                    null
                }
            }
            .distinctBy { it.processedIndex }
    }

    private fun publishReviewFrameState(status: ReviewFrameStatus) {
        GemmaFitDebugApi.updateState(
            section = "review_frame",
            data = mapOf(
                "frame" to status.frameIndex,
                "timestamp_ms" to status.timestampMs,
                "bitmap_cached" to status.bitmapCached,
                "bitmap_restoring" to status.bitmapRestoring,
                "bitmap_restored" to status.bitmapRestored,
                "bitmap_restore_failed" to status.bitmapRestoreFailed,
                "restore_latency_ms" to status.restoreLatencyMs,
                "landmark_count" to status.landmarkCount,
                "selected_landmark_count" to status.selectedLandmarkCount,
                "candidate_count" to status.candidateCount,
                "pose_available" to status.poseAvailable,
                "pose_recovery_enabled" to ENABLE_REVIEW_POSE_RECOVERY,
                "pose_hidden_by_quality" to status.poseHiddenByQuality,
                "no_pose_reason" to status.noPoseReason,
                "preview_width" to status.previewWidth,
                "preview_height" to status.previewHeight,
            ),
        )
    }

    private fun appendProcessedFrame(frame: ProcessedFrame): Int {
        var bitmapToRecycle: Bitmap? = null
        val count = synchronized(processedFramesLock) {
            processedFrames.add(frame)
            if (processedFrames.size > 60) {
                val recycleIndex = processedFrames.size - 61
                val oldFrame = processedFrames[recycleIndex]
                bitmapToRecycle = oldFrame.bitmap
                if (oldFrame.bitmap != null) {
                    processedFrames[recycleIndex] = oldFrame.copy(bitmap = null)
                }
            }
            processedFrames.size
        }
        bitmapToRecycle?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        return count
    }

    private fun updateProcessedFrameAt(index: Int, transform: (ProcessedFrame) -> ProcessedFrame): Boolean {
        return synchronized(processedFramesLock) {
            val frame = processedFrames.getOrNull(index) ?: return@synchronized false
            processedFrames[index] = transform(frame)
            true
        }
    }

    private fun updateProcessedFrameByFrameIndex(
        frameIndex: Int,
        transform: (ProcessedFrame) -> ProcessedFrame,
    ): Boolean {
        return synchronized(processedFramesLock) {
            val idx = processedFrames.indexOfFirst { it.frameIndex == frameIndex }
            if (idx < 0) {
                false
            } else {
                processedFrames[idx] = transform(processedFrames[idx])
                true
            }
        }
    }

    private fun clearProcessedFrames(): Int {
        val bitmaps = synchronized(processedFramesLock) {
            val frames = processedFrames.toList()
            processedFrames.clear()
            reviewRecoveryAttemptedFrameIndexes.clear()
            reviewBitmapRestoredFrameIndexes.clear()
            currentFrameIdx = 0
            frames.mapNotNull { it.bitmap }
        }
        bitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        return bitmaps.size
    }

    fun configureCoachVoice(settings: AppSettings) {
        seniorHeroMode = SENIOR_HERO_PRIMARY_VIDEO_DEMO || settings.assistedMode
        sessionLocale = com.gemmafit.settings.ResolvedLocale.resolve(settings.language)
        coachVoice?.configure(settings)
    }

    /**
     * Resolved user locale at session-summary time. Updated from
     * [configureCoachVoice] whenever AppSettings changes, then injected into
     * [SessionCoachRenderer.contextFrom] so the E2B prompt's `locale` field
     * tells Gemma which language to emit care log wording in.
     */
    private var sessionLocale: com.gemmafit.settings.ResolvedLocale =
        com.gemmafit.settings.ResolvedLocale.EN_US

    fun initPoseLandmarker(landmarker: PoseLandmarker) {
        replacePoseLandmarker(landmarker)
        poseInitFailed = false
        Log.d(TAG, "PoseLandmarker injected externally")
    }

    private fun initPoseLandmarkerAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing PoseLandmarker (IMAGE mode)...")
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(
                        com.google.mediapipe.tasks.core.BaseOptions.builder()
                            .setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                            .setModelAssetPath("pose_landmarker_lite.task")
                            .build()
                    )
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setNumPoses(MAX_POSE_CANDIDATES)
                    .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                    .build()
                replacePoseLandmarker(PoseLandmarker.createFromOptions(getApplication(), options))
                poseInitFailed = false
                Log.d(TAG, "PoseLandmarker initialized successfully (IMAGE mode)")
                GemmaFitDebugApi.record(
                    category = "pose",
                    message = "image_landmarker_ready",
                    data = mapOf("delegate" to "GPU", "max_poses" to MAX_POSE_CANDIDATES),
                )
            } catch (e: Exception) {
                poseInitFailed = true
                Log.e(TAG, "PoseLandmarker init failed: ${e.message}", e)
                GemmaFitDebugApi.record(
                    category = "pose",
                    message = "image_landmarker_failed",
                    data = mapOf("error" to (e.message ?: "unknown")),
                )
                _state.update { it.copy(phase = VideoPhase.Error("PoseLandmarker init failed: ${e.message}")) }
            }
        }
    }

    private suspend fun createVideoPoseLandmarker(maxPoses: Int): PoseLandmarker? = withContext(Dispatchers.IO) {
        val runningMode = com.google.mediapipe.tasks.vision.core.RunningMode.VIDEO
        fun options(delegate: com.google.mediapipe.tasks.core.Delegate): PoseLandmarker.PoseLandmarkerOptions {
            return PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                        .setDelegate(delegate)
                        .setModelAssetPath("pose_landmarker_lite.task")
                        .build()
                )
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumPoses(maxPoses.coerceAtLeast(1))
                .setRunningMode(runningMode)
                .build()
        }

        try {
            Log.d(TAG, "Initializing VIDEO PoseLandmarker maxPoses=$maxPoses with GPU")
            val landmarker = PoseLandmarker.createFromOptions(getApplication(), options(com.google.mediapipe.tasks.core.Delegate.GPU))
            GemmaFitDebugApi.record(
                category = "pose",
                message = "video_landmarker_ready",
                data = mapOf("delegate" to "GPU", "max_poses" to maxPoses),
            )
            landmarker
        } catch (gpuError: Exception) {
            Log.w(TAG, "VIDEO PoseLandmarker GPU failed, trying CPU: ${gpuError.message}")
            GemmaFitDebugApi.record(
                category = "pose",
                message = "video_landmarker_gpu_failed",
                data = mapOf("max_poses" to maxPoses, "error" to (gpuError.message ?: "unknown")),
            )
            try {
                val landmarker = PoseLandmarker.createFromOptions(getApplication(), options(com.google.mediapipe.tasks.core.Delegate.CPU))
                GemmaFitDebugApi.record(
                    category = "pose",
                    message = "video_landmarker_ready",
                    data = mapOf("delegate" to "CPU", "max_poses" to maxPoses),
                )
                landmarker
            } catch (cpuError: Exception) {
                Log.e(TAG, "VIDEO PoseLandmarker CPU failed: ${cpuError.message}", cpuError)
                GemmaFitDebugApi.record(
                    category = "pose",
                    message = "video_landmarker_failed",
                    data = mapOf("max_poses" to maxPoses, "error" to (cpuError.message ?: "unknown")),
                )
                null
            }
        }
    }

    private suspend fun estimateAnalyzedFrames(
        uri: Uri,
        intervalMs: Long,
    ): Int = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication(), uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ((durationMs.toFloat() / intervalMs.coerceAtLeast(1L).toFloat()).toInt() + 1).coerceAtLeast(1)
        } catch (_: Exception) {
            100
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private data class PreviewProbeResult(
        val sampledFrames: Int,
        val poseHits: Int,
        val elapsedMs: Long,
    )

    private suspend fun runFastPreviewProbe(
        uri: Uri,
        estimatedTotalFrames: Int,
        landmarker: PoseLandmarker,
    ): PreviewProbeResult {
        val probeStartMs = System.currentTimeMillis()
        var sampledFrames = 0
        var poseHits = 0
        var consecutivePoseHits = 0
        var retriever: MediaMetadataRetriever? = null
        try {
            val metadata = withContext(Dispatchers.IO) {
                val opened = MediaMetadataRetriever()
                retriever = opened
                opened.setDataSource(getApplication(), uri)
                val durationMs = opened.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                val targetSize = previewTargetSize(opened, PREVIEW_LONG_SIDE)
                durationMs to targetSize
            }
            val durationMs = metadata.first
            val targetSize = metadata.second
            val probeTimesMs = previewProbeTimesMs(durationMs)
            GemmaFitDebugApi.record(
                category = "video",
                message = "preview_probe_start",
                data = mapOf(
                    "duration_ms" to durationMs,
                    "probe_frames" to probeTimesMs.size,
                    "long_side" to PREVIEW_LONG_SIDE,
                    "max_poses" to PREVIEW_POSE_CANDIDATES,
                ),
            )
            _state.update {
                it.copy(
                    phase = VideoPhase.Processing(0.12f),
                    totalFrames = probeTimesMs.size.coerceAtLeast(estimatedTotalFrames),
                    subPhase = "preview_analysis",
                    subPhaseProgress = 0.12f,
                )
            }
            _live.value = _live.value.copy(analysisStage = "Preview analysis running")

            for ((probeIndex, timeMs) in probeTimesMs.withIndex()) {
                val frameBitmap = try {
                    withContext(Dispatchers.IO) {
                        val opened = retriever ?: return@withContext null
                        scaledPreviewFrame(
                            retriever = opened,
                            timeMs = timeMs,
                            targetWidth = targetSize.first,
                            targetHeight = targetSize.second,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Preview frame extract failed at ${timeMs}ms: ${e.message}")
                    null
                } ?: continue

                var storedByFrameCache = false
                try {
                    val poseResult = try {
                        withContext(Dispatchers.Default) {
                            detectPreviewPose(landmarker, frameBitmap, timeMs)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Preview pose detect failed at ${timeMs}ms: ${e.message}")
                        VideoPoseResult(emptyList(), null)
                    }
                    if (poseResult.landmarks.isNotEmpty()) {
                        poseHits++
                        consecutivePoseHits++
                    } else {
                        consecutivePoseHits = 0
                    }
                    processLandmarks(
                        result = poseResult,
                        frameIndex = sampledFrames,
                        timestampMs = timeMs,
                        bitmap = frameBitmap,
                        bmpWidth = frameBitmap.width,
                        bmpHeight = frameBitmap.height,
                        runNativeMetrics = false,
                        pass = VideoAnalysisPass.PREVIEW,
                    )
                    storedByFrameCache = true
                    sampledFrames++
                    val progress = (0.12f + (0.18f * ((probeIndex + 1).toFloat() / probeTimesMs.size.coerceAtLeast(1))))
                        .coerceIn(0.12f, 0.30f)
                    _state.update { state ->
                        state.copy(
                            phase = VideoPhase.Processing(progress),
                            progress = progress,
                            currentFrame = sampledFrames,
                            totalFrames = probeTimesMs.size,
                            subPhase = "preview_analysis",
                            subPhaseProgress = progress,
                            poseHitRate = if (sampledFrames > 0) poseHits.toFloat() / sampledFrames else 0f,
                            poseHits = poseHits,
                            poseMisses = sampledFrames - poseHits,
                        )
                    }
                    if (consecutivePoseHits >= PREVIEW_EARLY_POSE_HITS) {
                        break
                    }
                } finally {
                    if (!storedByFrameCache && !frameBitmap.isRecycled) {
                        frameBitmap.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Preview probe failed: ${e.message}", e)
            GemmaFitDebugApi.record(
                category = "video",
                message = "preview_probe_failed",
                data = mapOf("error" to (e.message ?: "unknown")),
            )
        } finally {
            withContext(Dispatchers.IO) {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }

        val elapsedMs = System.currentTimeMillis() - probeStartMs
        GemmaFitDebugApi.record(
            category = "video",
            message = "preview_probe_complete",
            data = mapOf(
                "sampled_frames" to sampledFrames,
                "pose_hits" to poseHits,
                "consecutive_pose_hits" to consecutivePoseHits,
                "elapsed_ms" to elapsedMs,
            ),
        )
        return PreviewProbeResult(sampledFrames, poseHits, elapsedMs)
    }

    private fun previewProbeTimesMs(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return listOf(0L)
        val startMs = minOf(500L, (durationMs - 1L).coerceAtLeast(0L))
        val spanMs = (durationMs - startMs).coerceAtLeast(1L)
        return (0 until PREVIEW_PROBE_MAX_FRAMES)
            .map { index -> startMs + ((spanMs * index) / PREVIEW_PROBE_MAX_FRAMES) }
            .map { it.coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L)) }
            .distinct()
    }

    private fun previewTargetSize(
        retriever: MediaMetadataRetriever,
        longSide: Int,
    ): Pair<Int, Int> {
        val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?: longSide
        val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?: longSide
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: 0
        val sourceWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val sourceHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        val scale = minOf(1f, longSide.toFloat() / maxOf(sourceWidth, sourceHeight).coerceAtLeast(1).toFloat())
        val targetWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }

    private fun scaledPreviewFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        return scaledFrameAtTime(
            retriever = retriever,
            timeMs = timeMs,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        )
    }

    private fun scaledFrameAtTime(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        targetWidth: Int,
        targetHeight: Int,
        option: Int,
    ): Bitmap? {
        val timeUs = timeMs.coerceAtLeast(0L) * 1000L
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                option,
                targetWidth,
                targetHeight,
            )
        } else {
            val raw = retriever.getFrameAtTime(timeUs, option) ?: return null
            if (raw.width == targetWidth && raw.height == targetHeight) {
                raw
            } else {
                val scaled = Bitmap.createScaledBitmap(raw, targetWidth, targetHeight, true)
                raw.recycle()
                scaled
            }
        }
    }

    private fun analysisScaledFrameAtTime(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        longSide: Int,
    ): Bitmap? {
        val raw = retriever.getFrameAtTime(
            timeMs.coerceAtLeast(0L) * 1000L,
            MediaMetadataRetriever.OPTION_CLOSEST,
        ) ?: return null
        val maxSide = maxOf(raw.width, raw.height).coerceAtLeast(1)
        if (maxSide <= longSide) {
            return raw
        }
        val scale = longSide.toFloat() / maxSide.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            raw,
            (raw.width * scale).toInt().coerceAtLeast(1),
            (raw.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        raw.recycle()
        return scaled
    }

    private fun detectPreviewPose(
        landmarker: PoseLandmarker,
        bitmap: Bitmap,
        timestampMs: Long,
    ): VideoPoseResult {
        val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
        val result = landmarker.detectForVideo(mpImage, timestampMs)
        val landmarks = if (result.landmarks().isNotEmpty()) {
            result.landmarks().map { lmList ->
                lmList.map { lm ->
                    NormalizedLandmark(
                        x = lm.x(),
                        y = lm.y(),
                        z = lm.z(),
                        visibility = lm.visibility().orElse(0.0f),
                    )
                }
            }
        } else {
            emptyList()
        }
        return VideoPoseResult(landmarks, null)
    }

    private fun ensurePoseLandmarker(): Boolean {
        if (hasPoseLandmarker()) return true
        if (poseInitFailed) {
            Log.w(TAG, "PoseLandmarker previously failed, retrying init...")
            poseInitFailed = false
            initPoseLandmarkerAsync()
        }
        return false
    }

    // ── Video processing ────────────────────────────────────────────────

    fun setVideoSource(uri: Uri, displayName: String) {
        invalidateCameraPoseWrites()
        sessionStartMs = System.currentTimeMillis()
        resetSessionData()
        GemmaFitDebugApi.record(
            category = "video",
            message = "source_selected",
            data = mapOf("display_name" to displayName),
        )
        GemmaFitDebugApi.updateState(
            section = "video_source",
            data = mapOf("type" to "video_file", "display_name" to displayName),
        )
        resetVideoDebugSections(reason = "source_selected", displayName = displayName)
        _state.update {
            it.copy(
                source = VideoSource.VideoFile(uri.toString(), displayName),
                phase = VideoPhase.Processing(0f),
                progress = 0.01f,
                currentFrame = 0,
                totalFrames = 0,
                errorMessage = null,
                processingFps = 0f,
                etaSeconds = 0,
                poseHitRate = 0f,
                poseHits = 0,
                poseMisses = 0,
                subPhase = "video_loading",
                subPhaseProgress = 0.01f,
            )
        }
    }

    fun selectVideoForAnalysis(uri: Uri, displayName: String) {
        setVideoSource(uri, displayName)
        processVideo(uri)
    }

    fun processVideo(uri: Uri) {
        processVideoInternal(uri, reanalysisSeed = null)
    }

    private fun processVideoInternal(uri: Uri, reanalysisSeed: ManualTargetReanalysisSeed?) {
        if (reanalysisSeed == null) {
            selectedTargetReanalysisSeed = null
        }
        activeTargetReanalysisSeed = reanalysisSeed
        val previousJob = processingJob
        processingJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            analysisRunId += 1L
            sessionCoachInferenceDedupGuard.reset()
            sessionSummaryPrewarmStartedForRun = -1L
            sessionStartMs = System.currentTimeMillis()
            resetSessionData()
            if (reanalysisSeed != null) {
                applyManualTargetSeedForAnalysis(reanalysisSeed, reason = "preview_reanalysis_start")
            }
            val sourceDisplayName = (_state.value.source as? VideoSource.VideoFile)?.displayName
            seedVideoSourceVisualContext(sourceDisplayName)
            resetVideoDebugSections(
                reason = if (reanalysisSeed != null) "target_reanalysis_start" else "process_video_start",
                displayName = sourceDisplayName,
            )

            try {
                val source = _state.value.source
                GemmaFitDebugApi.record(
                    category = "video",
                    message = if (reanalysisSeed != null) "target_reanalysis_start" else "process_video_start",
                    data = mapOf(
                        "source" to when (source) {
                            is VideoSource.VideoFile -> source.displayName
                            VideoSource.Camera -> "camera"
                        },
                        "manual_target_seed" to (reanalysisSeed != null),
                    ),
                )
                _state.update {
                    it.copy(
                        phase = VideoPhase.Processing(0f),
                        progress = 0.03f,
                        currentFrame = 0,
                        totalFrames = 0,
                        subPhase = "video_loading",
                        subPhaseProgress = 0.03f,
                    )
                }

                val previewFrames = PREVIEW_PROBE_MAX_FRAMES
                _state.update {
                    it.copy(
                        phase = VideoPhase.Processing(0.06f),
                        progress = 0.06f,
                        totalFrames = previewFrames,
                        subPhase = "loading_model",
                        subPhaseProgress = 0.06f,
                    )
                }
                val previewLandmarker = createVideoPoseLandmarker(PREVIEW_POSE_CANDIDATES)
                if (previewLandmarker == null) {
                    _state.update { it.copy(phase = VideoPhase.Error("PoseLandmarker failed to initialize")) }
                    return@launch
                }
                replacePoseLandmarker(previewLandmarker)
                _state.update {
                    it.copy(
                        phase = VideoPhase.Processing(0.12f),
                        progress = 0.12f,
                        subPhase = "preview_loading",
                        subPhaseProgress = 0.12f,
                    )
                }
                val previewProbe = runFastPreviewProbe(
                    uri = uri,
                    estimatedTotalFrames = previewFrames,
                    landmarker = previewLandmarker,
                )

                // Preview complete — build early summary for instant feedback
                if (previewProbe.poseHits == 0) {
                    Log.d(TAG, "Preview probe found no pose; continuing with full analysis")
                }
                _live.value = _live.value.copy(
                    isPreviewData = true,
                    analysisStage = "Preview complete",
                )
                _sessionSummary.value = buildSessionSummary().copy(isPreviewData = true)

                resetForFullAnalysisPass()
                seedVideoSourceVisualContext(sourceDisplayName)
                if (reanalysisSeed != null) {
                    applyManualTargetSeedForAnalysis(reanalysisSeed, reason = "full_reanalysis_start")
                }
                val fullFrames = estimateAnalyzedFrames(uri, TARGET_ANALYSIS_INTERVAL_MS)
                _state.update {
                    it.copy(
                        phase = VideoPhase.Analyzing(0, fullFrames),
                        progress = 0.04f,
                        currentFrame = 0,
                        totalFrames = fullFrames,
                        subPhase = "loading_model",
                        subPhaseProgress = 0.04f,
                    )
                }
                val fullLandmarker = createVideoPoseLandmarker(FULL_POSE_CANDIDATES)
                if (fullLandmarker == null) {
                    _state.update { it.copy(phase = VideoPhase.Error("Full analysis PoseLandmarker failed")) }
                    return@launch
                }
                replacePoseLandmarker(fullLandmarker)
                _state.update {
                    it.copy(
                        phase = VideoPhase.Analyzing(0, fullFrames),
                        progress = 0.08f,
                        subPhase = "full_analysis",
                        subPhaseProgress = 0.08f,
                    )
                }
                runVideoPass(
                    uri = uri,
                    pass = VideoAnalysisPass.FULL,
                    estimatedTotalFrames = fullFrames,
                    intervalMs = TARGET_ANALYSIS_INTERVAL_MS,
                    longSide = FULL_LONG_SIDE,
                    maxPoses = FULL_POSE_CANDIDATES,
                    runNativeMetrics = true,
                )
                val noPoseRetrySummary = runNoPoseIslandRetryPass(uri)
                val sessionMotionZipPacket = publishSessionMotionZipPacket(reason = "full_analysis_complete")

                val finalFrameCount = processedFrameCount()
                Log.d(TAG, "Video processing complete. Frames: $finalFrameCount, Landmarks present: ${_live.value.poseLandmarks.isNotEmpty()}")
                GemmaFitDebugApi.record(
                    category = "video",
                    message = "process_video_complete",
                    data = mapOf(
                        "frames" to finalFrameCount,
                        "pose_present" to _live.value.poseLandmarks.isNotEmpty(),
                        "total_reps" to _live.value.repCount,
                        "no_pose_retry" to noPoseRetrySummary.toDebugMap(),
                        "motion_zip_blocks" to (sessionMotionZipPacket?.compressedSparseBlocks?.size ?: 0),
                        "motion_zip_output_state" to
                            (sessionMotionZipPacket?.heavilyCompressedSummary?.outputState ?: "none"),
                    ),
                )
                _state.update { s -> s.copy(phase = VideoPhase.Complete(finalFrameCount), subPhase = "complete", subPhaseProgress = 1f) }
                if (finalFrameCount > 0) {
                    val displayFrameIndex = 0
                    val displayReason = "first_frame_after_analysis"
                    reviewFramePinned = false
                    GemmaFitDebugApi.record(
                        category = "video",
                        message = "completion_review_frame_selected",
                        data = mapOf(
                            "selected_index" to displayFrameIndex,
                            "previous_index" to currentFrameIdx,
                            "reason" to displayReason,
                        ),
                    )
                    showFrame(displayFrameIndex.coerceIn(0, finalFrameCount - 1), pinReview = true)
                }
                _live.value = _live.value.copy(
                    analysisStage = "Full analysis complete",
                    isPreviewData = false,
                    fullProgress = 1f,
                    reviewTargetChangedAfterAnalysis = false,
                    targetReanalysisAvailable = false,
                    targetReanalysisActive = false,
                )
                activeTargetReanalysisSeed = null
                val summaryBase = buildSessionSummary().copy(isPreviewData = false)
                val summaryContext = SessionCoachRenderer.contextFrom(
                    summary = summaryBase,
                    seniorHeroMode = seniorHeroMode,
                    locale = sessionLocale,
                )
                sessionCoachInsight = SessionCoachRenderer.render(summaryContext)
                val finalSummary = buildSessionSummary().copy(isPreviewData = false)
                _live.value = _live.value.copy(
                    sessionStatus = sessionStatusSnapshotFrom(finalSummary),
                    reviewCues = finalSummary.reviewCues,
                )
                _sessionSummary.value = finalSummary
                scheduleSessionCoachInference(
                    eventKey = "session_summary|$finalFrameCount|${finalSummary.totalReps}",
                    context = summaryContext,
                    sessionMotionZipPacket = sessionMotionZipPacket,
                )
            } catch (e: CancellationException) {
                Log.d(TAG, "Video processing cancelled")
                activeTargetReanalysisSeed = null
                _live.value = _live.value.copy(targetReanalysisActive = false)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Video processing error: ${e.message}", e)
                GemmaFitDebugApi.record(
                    category = "video",
                    message = "process_video_error",
                    data = mapOf("error" to (e.message ?: "unknown")),
                )
                _state.update { it.copy(phase = VideoPhase.Error(e.message ?: "Processing failed")) }
                activeTargetReanalysisSeed = null
                _live.value = _live.value.copy(targetReanalysisActive = false)
            }
        }
    }

    // ── Camera frame ────────────────────────────────────────────────────

    private fun resetVideoDebugSections(reason: String, displayName: String? = null) {
        val payload = buildMap<String, Any> {
            put("status", "reset")
            put("reason", reason)
            displayName?.let { put("display_name", it) }
        }
        GemmaFitDebugApi.updateState("video_analysis", payload)
        GemmaFitDebugApi.updateState("motion_feature_window", payload)
        GemmaFitDebugApi.updateState("model_invocation", payload)
        GemmaFitDebugApi.updateState("coach_summary", payload)
        GemmaFitDebugApi.updateState("motion_zip_packet", payload)
        GemmaFitDebugApi.updateState("session_visual_context", payload)
        GemmaFitDebugApi.updateState("live_visual_context", payload)
        GemmaFitDebugApi.updateState("litert_visual_context_infer", payload)
    }

    private suspend fun runVideoPass(
        uri: Uri,
        pass: VideoAnalysisPass,
        estimatedTotalFrames: Int,
        intervalMs: Long,
        longSide: Int,
        maxPoses: Int,
        runNativeMetrics: Boolean,
    ) {
        val passStartMs = System.currentTimeMillis()
        var firstResultLogged = false
        var lastProgressUpdateMs = 0L
        var poseHits = 0
        var poseMisses = 0
        var firstPoseFrame: Int? = null
        var firstPoseTimestampMs: Long? = null
        var first60PoseHits = 0
        var first60PoseMisses = 0
        val poseTimeline = mutableListOf<Map<String, Any?>>()
        GemmaFitDebugApi.record(
            category = "video",
            message = "analysis_pass_start",
            data = mapOf(
                "pass" to pass.name,
                "estimated_frames" to estimatedTotalFrames,
                "interval_ms" to intervalMs,
                "long_side" to longSide,
                "max_poses" to maxPoses,
                "native_metrics" to runNativeMetrics,
            ),
        )

        _state.update {
            it.copy(
                phase = VideoPhase.Analyzing(0, estimatedTotalFrames),
                totalFrames = estimatedTotalFrames,
                subPhase = if (pass == VideoAnalysisPass.PREVIEW) "preview_analysis" else "full_analysis",
                subPhaseProgress = 0f,
            )
        }
        _live.value = _live.value.copy(
            analysisStage = if (pass == VideoAnalysisPass.PREVIEW) {
                "Preview analysis running"
            } else {
                "Full analysis running"
            },
        )

        val processor = VideoProcessor(
            context = getApplication(),
            poseLandmarker = currentPoseLandmarker(),
            sampleEveryNFrames = 1,
            maxDimension = longSide,
            targetAnalysisIntervalMs = intervalMs,
            pass = pass,
        )
        videoProcessor = processor

        processor.processVideo(uri).collect { result ->
            if (!firstResultLogged) {
                firstResultLogged = true
                Log.d(TAG, "First ${pass.name.lowercase()} result in ${System.currentTimeMillis() - passStartMs}ms")
            }

            val nowMs = System.currentTimeMillis()
            val elapsed = (nowMs - passStartMs) / 1000f
            val loadingFloor = if (pass == VideoAnalysisPass.PREVIEW) 0.12f else 0.08f
            val realProgress = ((result.frameIndex + 1).toFloat() / estimatedTotalFrames)
                .coerceAtLeast(loadingFloor)
                .coerceIn(0f, 0.99f)
            val fps = if (elapsed > 0.5f) (result.frameIndex + 1) / elapsed else 0f
            val eta = if (fps > 0f) ((estimatedTotalFrames - result.frameIndex) / fps).toInt() else 0

            val poseCount = result.landmarks?.landmarks?.size ?: 0
            if (poseCount > 0) {
                poseHits++
                if (firstPoseFrame == null) {
                    firstPoseFrame = result.frameIndex
                    firstPoseTimestampMs = result.timestampMs
                }
            } else {
                poseMisses++
            }
            if (result.frameIndex < 60) {
                if (poseCount > 0) {
                    first60PoseHits++
                } else {
                    first60PoseMisses++
                }
            }
            if (poseTimeline.size < POSE_TIMELINE_DEBUG_FRAME_LIMIT) {
                poseTimeline += mapOf(
                    "frame" to result.frameIndex,
                    "timestamp_ms" to result.timestampMs,
                    "pose_count" to poseCount,
                    "bitmap_available" to (result.bitmap != null),
                )
            }
            val hitRate = if (poseHits + poseMisses > 0) {
                poseHits.toFloat() / (poseHits + poseMisses)
            } else 0f

            val shouldPublishProgress =
                result.frameIndex == 0 ||
                    nowMs - lastProgressUpdateMs >= VIDEO_PROGRESS_UPDATE_INTERVAL_MS ||
                    result.frameIndex + 1 >= estimatedTotalFrames
            if (shouldPublishProgress) {
                lastProgressUpdateMs = nowMs
                _state.update { s ->
                    s.copy(
                        phase = VideoPhase.Analyzing(result.frameIndex, estimatedTotalFrames),
                        progress = realProgress,
                        currentFrame = result.frameIndex,
                        totalFrames = estimatedTotalFrames,
                        elapsedSeconds = elapsed,
                        processingFps = fps,
                        etaSeconds = eta,
                        poseHitRate = hitRate,
                        poseHits = poseHits,
                        poseMisses = poseMisses,
                        subPhase = if (pass == VideoAnalysisPass.PREVIEW) "preview_analysis" else "full_analysis",
                        subPhaseProgress = realProgress,
                    )
                }
                // During Full pass, broadcast progress so UI can show mini progress bar.
                if (pass == VideoAnalysisPass.FULL) {
                    _live.value = _live.value.copy(fullProgress = realProgress)
                    maybePrewarmSessionSummaryEngine(realProgress)
                }
            }

            if (result.landmarks != null && result.bitmap != null) {
                processLandmarks(
                    result = result.landmarks,
                    frameIndex = result.frameIndex,
                    timestampMs = result.timestampMs,
                    bitmap = result.bitmap,
                    bmpWidth = result.bitmapWidth,
                    bmpHeight = result.bitmapHeight,
                    runNativeMetrics = runNativeMetrics,
                    pass = pass,
                )
            }
        }

        Log.d(
            TAG,
            "${pass.name} pass complete in ${System.currentTimeMillis() - passStartMs}ms, " +
                "hitRate=${if (poseHits + poseMisses > 0) poseHits.toFloat() / (poseHits + poseMisses) else 0f}",
        )
        GemmaFitDebugApi.record(
            category = "video",
            message = "analysis_pass_complete",
            data = mapOf(
                "pass" to pass.name,
                "elapsed_ms" to (System.currentTimeMillis() - passStartMs),
                "pose_hits" to poseHits,
                "pose_misses" to poseMisses,
                "first_pose_frame" to firstPoseFrame,
                "first_pose_timestamp_ms" to firstPoseTimestampMs,
                "first_60_pose_hits" to first60PoseHits,
                "first_60_pose_misses" to first60PoseMisses,
                "hit_rate" to if (poseHits + poseMisses > 0) {
                    poseHits.toFloat() / (poseHits + poseMisses)
                } else {
                    0f
                },
            ),
        )
        GemmaFitDebugApi.updateState(
            section = "pose_detection_timeline",
            data = mapOf(
                "pass" to pass.name,
                "sample_interval_ms" to intervalMs,
                "debug_frame_limit" to POSE_TIMELINE_DEBUG_FRAME_LIMIT,
                "pose_hits" to poseHits,
                "pose_misses" to poseMisses,
                "first_pose_frame" to firstPoseFrame,
                "first_pose_timestamp_ms" to firstPoseTimestampMs,
                "first_60_pose_hits" to first60PoseHits,
                "first_60_pose_misses" to first60PoseMisses,
                "first_frames" to poseTimeline,
            ),
        )
    }

    private fun maybePrewarmSessionSummaryEngine(progress: Float) {
        if (!ENABLE_NATIVE_SESSION_SUMMARY) return
        if (progress < SESSION_SUMMARY_PREWARM_PROGRESS) return
        val runId = analysisRunId
        if (sessionSummaryPrewarmStartedForRun == runId) return
        val app = getApplication<Application>()
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app) ?: return
        sessionSummaryPrewarmStartedForRun = runId
        GemmaFitDebugApi.record(
            category = "coach_summary",
            message = "litert_prewarm_requested",
            data = mapOf(
                "analysis_run_id" to runId,
                "progress" to progress,
                "model_path" to modelPath,
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val uri = Uri.Builder()
                .scheme("content")
                .authority("com.gemmafit.debug")
                .appendPath("litert_prewarm")
                .appendQueryParameter(
                    "max_num_images",
                    if (ENABLE_SESSION_VISUAL_SIDECAR) "1" else "0",
                )
                .build()
            runCatching {
                withTimeout(60_000L) {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("litert_prewarm_empty_response")
                }
            }.onSuccess { payloadText ->
                val payload = runCatching { JSONObject(payloadText) }.getOrNull()
                GemmaFitDebugApi.record(
                    category = "coach_summary",
                    message = if (payload?.optBoolean("success", false) == true) {
                        "litert_prewarm_ready"
                    } else {
                        "litert_prewarm_failed"
                    },
                    data = mapOf(
                        "analysis_run_id" to runId,
                        "backend" to payload?.optString("backend").orEmpty(),
                        "engine_initialize_ms" to (payload?.optLong("engine_initialize_ms", -1L) ?: -1L),
                        "reused_engine" to (payload?.optBoolean("reused_engine", false) ?: false),
                        "max_num_images" to (payload?.optLong("max_num_images", -1L) ?: -1L),
                        "error" to payload?.optString("error").orEmpty(),
                    ),
                )
            }.onFailure { error ->
                GemmaFitDebugApi.record(
                    category = "coach_summary",
                    message = "litert_prewarm_failed",
                    data = mapOf(
                        "analysis_run_id" to runId,
                        "error_type" to error::class.java.name,
                        "error" to (error.message ?: "unknown"),
                    ),
                )
            }
        }
    }

    fun onCameraFrame(
        result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
        frameBitmap: Bitmap? = null,
    ) {
        var liveVisionBitmap: Bitmap? = null
        try {
            if (_state.value.source !is VideoSource.Camera) return
            liveVisionBitmap = if (ENABLE_ASYNC_LIVE_VISUAL_SIDECAR) {
                scaledBitmapCopy(frameBitmap, LIVE_VISUAL_SCENE_LONG_SIDE)
            } else {
                null
            }
            val candidates = candidatesFromLandmarkerResult(result, frameBitmap)
            val selection = resolveSubjectSelection(candidates)
            if (selection.candidate == null) {
                liveVisionBitmap?.recycleIfAlive()
                liveVisionBitmap = null
                resetCameraLandmarkStability()
                resetNativeTemporalLandmarks()
                invalidateCameraPoseWrites()
                publishSubjectGate(
                    candidates = candidates,
                    status = selection.status,
                    frameIndex = totalFramesAnalyzed,
                    timestampMs = System.currentTimeMillis() - sessionStartMs,
                    reason = selection.reason,
                    trustFlags = selection.trustFlags,
                )
                return
            }

            val cameraEpoch = cameraPresenceEpoch.get()
            val stableLandmarks = stabilizeCameraLandmarks(selection.candidate.landmarks, selection.trackId)
            val floatArray = stableLandmarks.toFloat99()
            _live.value = _live.value.copy(
                poseLandmarks = stableLandmarks,
                poseCandidates = candidates,
                activeSubjectIndex = selection.activeIndex,
                activeSubjectTrackId = selection.trackId,
                subjectLockStatus = selection.status,
                subjectTrustFlags = selection.trustFlags,
            )
            val liveVisionSnapshotBitmap = liveVisionBitmap
            liveVisionBitmap = null
            viewModelScope.launch(Dispatchers.Default) {
                val (frameIndex, frameTimestampMs) = synchronized(sessionDataLock) {
                    val nextFrameIndex = totalFramesAnalyzed++
                    nextFrameIndex to (System.currentTimeMillis() - sessionStartMs)
                }
                if (liveVisionSnapshotBitmap != null && isCurrentCameraPoseWrite(cameraEpoch)) {
                    val stats = PosePresenceGate.evaluate(
                        stableLandmarks,
                        { it.x },
                        { it.y },
                        { it.visibility },
                    )
                    replaceLatestLiveVisionSnapshot(
                        LiveVisionFrameSnapshot(
                            bitmap = liveVisionSnapshotBitmap,
                            frameIndex = frameIndex,
                            timestampMs = frameTimestampMs,
                            phase = _live.value.movementPhase,
                            poseConfidence = poseConfidenceFloor(stableLandmarks),
                            fullBodyVisibility = stats.avgVisibility,
                            subjectObserved = stableLandmarks.isNotEmpty(),
                            subjectStable = selection.status in setOf(
                                SubjectLockStatus.LOCKED,
                                SubjectLockStatus.AUTO_LOCKED,
                                SubjectLockStatus.SINGLE_AUTO,
                            ),
                            evidenceRefs = emptyList(),
                        )
                    )
                } else {
                    liveVisionSnapshotBitmap?.recycleIfAlive()
                }
                processLandmarks(
                    floatArray = floatArray,
                    frameIndex = frameIndex,
                    timestampMs = frameTimestampMs,
                    subjectTrustFlags = selection.trustFlags,
                    subjectLockStatus = selection.status,
                    subjectTrackId = selection.trackId,
                    cameraEpoch = cameraEpoch,
                )
            }
        } finally {
            liveVisionBitmap?.recycleIfAlive()
            frameBitmap?.recycle()
        }
    }

    fun selectSubjectAt(normalizedX: Float, normalizedY: Float) {
        val tapX = normalizedX.coerceIn(0f, 1f)
        val tapY = normalizedY.coerceIn(0f, 1f)
        pendingSubjectTap = tapX to tapY
        manualSubjectLock = true
        val marksVideoAnalysisStale = _state.value.source is VideoSource.VideoFile &&
            processedFrameCount() > 0

        if (hasProcessedFrame(currentFrameIdx)) {
            showFrame(currentFrameIdx, pinReview = true, resolveSelection = true)
        } else if (_live.value.poseCandidates.isNotEmpty()) {
            val selection = resolveSubjectSelection(_live.value.poseCandidates)
            selection.candidate?.let { candidate ->
                _live.value = _live.value.copy(
                    poseLandmarks = candidate.landmarks,
                    activeSubjectIndex = selection.activeIndex,
                    activeSubjectTrackId = selection.trackId,
                    subjectLockStatus = selection.status,
                    subjectTrustFlags = selection.trustFlags,
                )
            }
        }
        if (marksVideoAnalysisStale) {
            selectedTargetReanalysisSeed = captureManualTargetReanalysisSeed(tapX, tapY)
            markReviewTargetChangedAfterAnalysis(
                reason = "manual_subject_tap",
                normalizedX = tapX,
                normalizedY = tapY,
                seedAvailable = selectedTargetReanalysisSeed != null,
            )
        }
    }

    private fun markReviewTargetChangedAfterAnalysis(
        reason: String,
        normalizedX: Float,
        normalizedY: Float,
        seedAvailable: Boolean,
    ) {
        _live.value = _live.value.copy(
            reviewTargetChangedAfterAnalysis = true,
            targetReanalysisAvailable = seedAvailable,
            targetReanalysisActive = false,
        )
        GemmaFitDebugApi.updateState(
            section = "analysis_target",
            data = mapOf(
                "stale_for_selected_subject" to true,
                "reanalyze_available" to seedAvailable,
                "reason" to reason,
                "frame" to currentFrameIdx,
                "timestamp_ms" to _live.value.currentFrameTimestampMs,
                "tap_x" to normalizedX,
                "tap_y" to normalizedY,
                "message" to "Review target changed; analysis summary was not recomputed for the selected subject.",
            ),
        )
        GemmaFitDebugApi.record(
            category = "subject",
            message = "review_target_changed_after_analysis",
            data = mapOf(
                "reason" to reason,
                "reanalyze_available" to seedAvailable,
                "frame" to currentFrameIdx,
                "timestamp_ms" to _live.value.currentFrameTimestampMs,
                "tap_x" to normalizedX,
                "tap_y" to normalizedY,
            ),
        )
    }

    fun reanalyzeSelectedSubject() {
        val uri = currentVideoUri()
        val seed = selectedTargetReanalysisSeed ?: captureManualTargetReanalysisSeed(
            tapX = 0f,
            tapY = 0f,
        )
        if (uri == null || seed == null) {
            GemmaFitDebugApi.record(
                category = "subject_reanalysis",
                message = "reanalyze_selected_subject_unavailable",
                data = mapOf(
                    "has_uri" to (uri != null),
                    "has_seed" to (seed != null),
                    "frame" to currentFrameIdx,
                ),
            )
            _live.value = _live.value.copy(
                targetReanalysisAvailable = false,
                targetReanalysisActive = false,
            )
            return
        }
        GemmaFitDebugApi.record(
            category = "subject_reanalysis",
            message = "reanalyze_selected_subject_start",
            data = mapOf(
                "source_frame" to seed.sourceFrameIndex,
                "source_timestamp_ms" to seed.sourceTimestampMs,
                "track_id" to seed.trackId,
                "tap_x" to seed.tapX,
                "tap_y" to seed.tapY,
            ),
        )
        selectedTargetReanalysisSeed = seed
        _live.value = _live.value.copy(
            analysisStage = "Reanalyzing selected subject",
            reviewTargetChangedAfterAnalysis = false,
            targetReanalysisAvailable = false,
            targetReanalysisActive = true,
        )
        processVideoInternal(uri, reanalysisSeed = seed)
    }

    private fun captureManualTargetReanalysisSeed(
        tapX: Float,
        tapY: Float,
    ): ManualTargetReanalysisSeed? {
        val candidate = lockedSubject ?: return null
        val trackId = lockedSubjectTrackId ?: candidate.trackId.takeIf { it > 0 } ?: nextSubjectTrackId++
        val appearance = lockedSubjectAppearance ?: candidate.appearance
        return ManualTargetReanalysisSeed(
            candidate = candidate.copy(trackId = trackId, appearance = appearance),
            appearance = appearance,
            sourceFrameIndex = currentFrameIdx,
            sourceTimestampMs = _live.value.currentFrameTimestampMs,
            tapX = tapX,
            tapY = tapY,
            trackId = trackId,
        )
    }

    private fun applyManualTargetSeedForAnalysis(
        seed: ManualTargetReanalysisSeed,
        reason: String,
    ) {
        synchronized(sessionDataLock) {
            activeTargetReanalysisSeed = seed
            manualSubjectLock = true
            pendingSubjectTap = null
            val candidate = seed.candidate.copy(
                trackId = seed.trackId,
                appearance = seed.appearance ?: seed.candidate.appearance,
            )
            lockedSubject = candidate
            lockedSubjectAppearance = candidate.appearance
            lockedSubjectTrackId = seed.trackId
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            lostSubjectFrames = 0
            identityHoldFrames = 0
            subjectMotionTracker.reset()
        }
        _live.value = _live.value.copy(
            subjectLockStatus = SubjectLockStatus.LOCKED,
            activeSubjectTrackId = seed.trackId,
            subjectTrustFlags = listOf("subject_locked", "manual_reanalysis_seed"),
            reviewTargetChangedAfterAnalysis = false,
            targetReanalysisAvailable = false,
            targetReanalysisActive = true,
            analysisStage = "Reanalyzing selected subject",
        )
        GemmaFitDebugApi.updateState(
            section = "analysis_target",
            data = mapOf(
                "stale_for_selected_subject" to false,
                "reanalysis_active" to true,
                "reason" to reason,
                "source_frame" to seed.sourceFrameIndex,
                "source_timestamp_ms" to seed.sourceTimestampMs,
                "track_id" to seed.trackId,
                "tap_x" to seed.tapX,
                "tap_y" to seed.tapY,
                "message" to "Analysis is being rebuilt from frame 0 using the manually selected subject seed.",
            ),
        )
    }

    fun onCameraLensSwitch() {
        if (_state.value.source !is VideoSource.Camera) return
        invalidateCameraPoseWrites()
        GemmaFitDebugApi.record(
            category = "camera",
            message = "lens_switch",
            data = mapOf("previous_frames" to totalFramesAnalyzed),
        )
        synchronized(sessionDataLock) {
            temporalAnalyzer.reset()
            layer2Interpreter.reset()
            activityContextTracker.reset()
            motionTraceAnalyzer.reset()
            liveCuePlanner.reset()
            cleanFrameStreak = 0
            manualSubjectLock = false
            pendingSubjectTap = null
            lockedSubject = null
            lockedSubjectAppearance = null
            lockedSubjectTrackId = null
            activeTargetReanalysisSeed = null
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            lostSubjectFrames = 0
            identityHoldFrames = 0
            subjectMotionTracker.reset()
            resetCameraLandmarkStability()
            resetNativeTemporalLandmarks()
            activeCoachInferenceJob?.cancel()
            _lastCoachMessage = null
            _lastCoachPriority = null
            lastCoachInsight = CoachInsight()
        }
        _live.value = _live.value.copy(
            poseLandmarks = emptyList(),
            poseTrajectory = emptyList(),
            poseCandidates = emptyList(),
            activeSubjectIndex = null,
            activeSubjectTrackId = null,
            subjectLockStatus = SubjectLockStatus.NEEDS_SELECTION,
            subjectTrustFlags = listOf("camera_switch"),
            activeWarnings = emptyList(),
            currentPattern = "unknown",
            movementPhase = "unknown",
            currentMuscleFocus = null,
            detectedExercise = "unknown",
            exerciseConfidence = 0f,
            exerciseBasis = emptyList(),
            templateMetrics = emptyMap(),
            coachMessage = "",
            coachPriority = "low",
            coachInsight = CoachInsight(),
            qualityFlags = emptyList(),
            trustMatrix = defaultTrustMatrix(),
            evidenceCard = EvidenceCard(),
            motionZipStatus = MotionZipUiState(),
            activityContext = ActivityContext.unknown(),
            visualContext = SessionVisualContext.unknown(),
            sessionStatus = SessionStatusSnapshot(),
        )
        coachVoice?.stop()
    }

    fun resetToCamera() {
        val previousJob = processingJob
        processingJob = null
        viewModelScope.launch {
            previousJob?.cancelAndJoin()
            selectedTargetReanalysisSeed = null
            activeTargetReanalysisSeed = null
            resetSessionData()
            _state.update { VideoAnalysisState(source = VideoSource.Camera) }
            _live.value = LiveWorkoutState(source = VideoSource.Camera)
            coachVoice?.stop()
        }
    }

    fun cancelProcessing() {
        val previousJob = processingJob
        processingJob = null
        viewModelScope.launch {
            previousJob?.cancelAndJoin()
            _state.update { it.copy(phase = VideoPhase.Idle) }
        }
    }

    fun selectVideo() {
        _state.update { it.copy(phase = VideoPhase.Selecting) }
    }

    // ── Frame navigation ────────────────────────────────────────────────

    fun goToNextFrame() {
        val count = processedFrameCount()
        if (count == 0) return
        val nextIdx = if (isVideoProcessingPhase()) {
            (currentFrameIdx + 1).coerceAtMost(count - 1)
        } else {
            (currentFrameIdx + 1) % count
        }
        Log.d(TAG, "goToNextFrame: $currentFrameIdx -> $nextIdx ($count total)")
        showFrame(nextIdx, pinReview = true)
    }

    fun goToPrevFrame() {
        val count = processedFrameCount()
        if (count == 0) return
        val prevIdx = if (isVideoProcessingPhase()) {
            (currentFrameIdx - 1).coerceAtLeast(0)
        } else if (currentFrameIdx <= 0) {
            count - 1
        } else {
            currentFrameIdx - 1
        }
        Log.d(TAG, "goToPrevFrame: $currentFrameIdx -> $prevIdx")
        showFrame(prevIdx, pinReview = true)
    }

    fun goToFrame(index: Int) {
        val count = processedFrameCount()
        if (count == 0) return
        showFrame(index.coerceIn(0, count - 1), pinReview = true)
    }

    fun goToLatestProcessedFrame(force: Boolean = true) {
        val count = processedFrameCount()
        if (count == 0) return
        if (!force && shouldPreservePinnedReviewFrame()) {
            return
        }
        val latestIdx = count - 1
        reviewFramePinned = false
        Log.d(TAG, "goToLatestProcessedFrame: $currentFrameIdx -> $latestIdx ($count total)")
        showFrame(latestIdx, pinReview = false)
    }

    fun pinCurrentFrameForReview() {
        val count = processedFrameCount()
        if (count == 0) return
        showFrame(currentFrameIdx.coerceIn(0, count - 1), pinReview = true)
    }

    fun showFrameAtTimestamp(timestampMs: Long) {
        if (reviewFramePinned && isVideoProcessingPhase()) {
            return
        }
        val match = synchronized(processedFramesLock) {
            if (processedFrames.isEmpty()) {
                null
            } else {
                var low = 0
                var high = processedFrames.lastIndex
                var candidate = 0
                while (low <= high) {
                    val mid = (low + high) / 2
                    if (processedFrames[mid].timestampMs <= timestampMs) {
                        candidate = mid
                        low = mid + 1
                    } else {
                        high = mid - 1
                    }
                }
                val next = (candidate + 1).takeIf { it in processedFrames.indices }
                if (next != null) {
                    val floorDelta = kotlin.math.abs(timestampMs - processedFrames[candidate].timestampMs)
                    val nextDelta = kotlin.math.abs(processedFrames[next].timestampMs - timestampMs)
                    if (nextDelta < floorDelta) next else candidate
                } else {
                    candidate
                }
            }
        } ?: return
        val latestIdx = processedFrameCount() - 1
        if (match >= latestIdx) {
            reviewFramePinned = false
        }
        if (match != currentFrameIdx) {
            showFrame(match, pinReview = false, updatePreview = false)
        }
    }

    private fun showFrame(
        index: Int,
        pinReview: Boolean,
        updatePreview: Boolean = true,
        resolveSelection: Boolean = false,
    ) {
        val frame = frameAtOrNull(index) ?: return
        currentFrameIdx = index
        if (pinReview && isVideoProcessingPhase()) {
            reviewFramePinned = true
        }
        val sourceHasRenderableFramePose = hasRenderableFramePose(frame)
        val markPosePreviewLowConfidence = sourceHasRenderableFramePose &&
            shouldMarkPosePreviewLowConfidence(frame.qualityFlags)
        val hasRenderableFramePose = sourceHasRenderableFramePose
        val shouldResolveSelection = frame.poseCandidates.isNotEmpty() &&
            (resolveSelection || manualSubjectLock)
        val selection = if (shouldResolveSelection) {
            resolveSubjectSelection(frame.poseCandidates, frame.timestampMs)
        } else {
            storedSubjectSelection(frame)
        }

        val selectedLandmarks = if (hasRenderableFramePose) selection.candidate?.landmarks else null
        val displayLandmarks = when {
            selectedLandmarks == null -> emptyList()
            frame.landmarks.isNotEmpty() &&
                (selection.trackId == frame.activeSubjectTrackId ||
                    selection.activeIndex == frame.activeSubjectIndex) -> frame.landmarks
            else -> selectedLandmarks
        }
        val hideFramePoseForQuality = sourceHasRenderableFramePose &&
            OverlayPoseQualityGate.shouldHideSkeleton(
                landmarks = displayLandmarks,
                qualityFlags = frame.qualityFlags,
                evidenceCard = frame.evidenceCard,
            )
        val suppressCandidateSkeletons = shouldSuppressCandidateSkeletonsForSubjectGate(
            trustFlags = selection.trustFlags,
            reason = selection.reason,
        )
        val displayCandidates = if (hasRenderableFramePose && !suppressCandidateSkeletons) {
            frame.poseCandidates
        } else {
            emptyList()
        }
        val displayStatus = if (hasRenderableFramePose) selection.status else SubjectLockStatus.SUBJECT_LOST
        val displayTrustFlags = when {
            hasRenderableFramePose && hideFramePoseForQuality -> overlayHiddenPoseTrustFlags(frame)
            hasRenderableFramePose && markPosePreviewLowConfidence -> lowConfidencePosePreviewTrustFlags(frame)
            hasRenderableFramePose -> selection.trustFlags
            else -> noPoseTrustFlags(frame)
        }
        val frameJudgmentBlocked = hideFramePoseForQuality ||
            markPosePreviewLowConfidence ||
            shouldHideSelectedSkeleton(
                subjectTrustFlags = displayTrustFlags,
                subjectLockStatus = displayStatus,
            )
        if (hasRenderableFramePose && selection.candidate != null && frame.poseCandidates.isNotEmpty()) {
            updateProcessedFrameAt(index) {
                it.copy(
                    landmarks = displayLandmarks,
                    activeSubjectIndex = selection.activeIndex,
                    activeSubjectTrackId = selection.trackId,
                    subjectLockStatus = displayStatus,
                    subjectTrustFlags = displayTrustFlags,
                )
            }
        } else if (!sourceHasRenderableFramePose) {
            updateProcessedFrameAt(index) {
                it.copy(
                    activeSubjectIndex = null,
                    activeSubjectTrackId = null,
                    subjectLockStatus = SubjectLockStatus.SUBJECT_LOST,
                    subjectTrustFlags = displayTrustFlags,
                )
            }
        }
        val frameBitmap = frame.bitmap?.takeIf { !it.isRecycled }
        val needsBitmapRestore = updatePreview && frameBitmap == null
        if (needsBitmapRestore) {
            scheduleReviewBitmapRestore(index, frame)
        }
        val reviewBitmap = frameBitmap
        val previewBitmap = when {
            !updatePreview -> _live.value.videoPreview
            pinReview -> safeBitmapCopy(reviewBitmap) ?: reviewBitmap ?: _live.value.videoPreview
            else -> reviewBitmap ?: _live.value.videoPreview
        }
        val previewWidth = if (updatePreview) {
            reviewBitmap?.width ?: frame.bitmapWidth
        } else {
            _live.value.videoPreviewWidth
        }
        val previewHeight = if (updatePreview) {
            reviewBitmap?.height ?: frame.bitmapHeight
        } else {
            _live.value.videoPreviewHeight
        }
        val suppressOverlayForPendingBitmap = needsBitmapRestore && updatePreview && !pinReview
        val suppressOverlayForPoseQuality = hideFramePoseForQuality
        val liveDisplayLandmarks = if (suppressOverlayForPendingBitmap || suppressOverlayForPoseQuality) {
            emptyList()
        } else {
            displayLandmarks
        }
        val liveDisplayCandidates = if (suppressOverlayForPendingBitmap || suppressOverlayForPoseQuality) {
            emptyList()
        } else {
            displayCandidates
        }
        val liveTrajectory = if (liveDisplayLandmarks.isNotEmpty()) trajectoryFor(index) else emptyList()
        val noPoseReason = when {
            needsBitmapRestore -> "frame_image_restoring"
            hideFramePoseForQuality -> OverlayPoseQualityGate.hideReason(
                landmarks = displayLandmarks,
                qualityFlags = frame.qualityFlags,
                evidenceCard = frame.evidenceCard,
            )
            markPosePreviewLowConfidence -> lowConfidencePoseReason(frame.qualityFlags)
            sourceHasRenderableFramePose && selection.candidate == null -> selection.reason
            !sourceHasRenderableFramePose -> "no_person_detected"
            else -> ""
        }
        val bitmapRestored = synchronized(processedFramesLock) {
            frame.frameIndex in reviewBitmapRestoredFrameIndexes
        }
        val reviewStatus = ReviewFrameStatus(
            frameIndex = frame.frameIndex,
            timestampMs = frame.timestampMs,
            bitmapCached = frameBitmap != null,
            bitmapRestoring = needsBitmapRestore,
            bitmapRestored = bitmapRestored,
            landmarkCount = frame.landmarks.size,
            selectedLandmarkCount = displayLandmarks.size,
            candidateCount = frame.poseCandidates.size,
            poseAvailable = sourceHasRenderableFramePose,
            poseHiddenByQuality = hideFramePoseForQuality,
            noPoseReason = noPoseReason,
            previewWidth = previewWidth,
            previewHeight = previewHeight,
        )
        val sessionCoach = sessionReviewCoachInsight()
        val displayCoachMessage = sessionCoach?.first ?: frame.coachMessage
        val displayCoachPriority = sessionCoach?.second ?: frame.coachPriority
        val displayCoachInsight = sessionCoach?.third ?: _live.value.coachInsight
        _live.value = _live.value.copy(
            poseLandmarks = liveDisplayLandmarks,
            poseTrajectory = liveTrajectory,
            videoPreview = previewBitmap,
            videoPreviewWidth = previewWidth,
            videoPreviewHeight = previewHeight,
            currentFrameIndex = index,
            currentFrameTimestampMs = frame.timestampMs,
            poseCandidates = liveDisplayCandidates,
            activeSubjectIndex = if (hasRenderableFramePose) {
                selection.activeIndex ?: frame.activeSubjectIndex
            } else {
                null
            },
            activeSubjectTrackId = if (hasRenderableFramePose) {
                selection.trackId ?: frame.activeSubjectTrackId
            } else {
                null
            },
            subjectLockStatus = displayStatus,
            subjectTrustFlags = displayTrustFlags,
            detectedExercise = frame.exercise,
            exerciseConfidence = frame.exerciseConfidence,
            movementPhase = frame.movementPhase,
            activeWarnings = frame.warnings,
            qualityFlags = frame.qualityFlags,
            templateMetrics = frame.templateMetrics,
            trustMatrix = frame.trustMatrix,
            evidenceCard = frame.evidenceCard,
            capabilityContract = frame.capabilityContract,
            coachMessage = displayCoachMessage,
            coachPriority = displayCoachPriority,
            coachInsight = displayCoachInsight,
            reviewFrameStatus = reviewStatus,
        )
        publishReviewFrameState(reviewStatus)

        if (!hasRenderableFramePose || selection.candidate == null) {
            publishSubjectGate(
                candidates = frame.poseCandidates,
                status = displayStatus,
                frameIndex = frame.frameIndex,
                timestampMs = frame.timestampMs,
                reason = when {
                    hideFramePoseForQuality -> OverlayPoseQualityGate.hideReason(
                        landmarks = displayLandmarks,
                        qualityFlags = frame.qualityFlags,
                        evidenceCard = frame.evidenceCard,
                    )
                    markPosePreviewLowConfidence -> lowConfidencePoseReason(frame.qualityFlags)
                    sourceHasRenderableFramePose -> selection.reason
                    else -> "no_person_detected"
                },
                trustFlags = displayTrustFlags,
            )
            if (ENABLE_REVIEW_POSE_RECOVERY &&
                !sourceHasRenderableFramePose &&
                "pose_hidden_low_confidence" !in displayTrustFlags &&
                pinReview
            ) {
                scheduleReviewPoseRecovery(index, frame)
            } else if (!sourceHasRenderableFramePose && pinReview) {
                GemmaFitDebugApi.record(
                    category = "pose",
                    message = "review_recovery_skipped",
                    data = mapOf(
                        "frame" to frame.frameIndex,
                        "timestamp_ms" to frame.timestampMs,
                        "reason" to "review_recovery_disabled",
                    ),
                )
            }
        } else if (frameJudgmentBlocked) {
            publishSubjectGate(
                candidates = frame.poseCandidates,
                status = displayStatus,
                frameIndex = frame.frameIndex,
                timestampMs = frame.timestampMs,
                reason = when {
                    hideFramePoseForQuality -> OverlayPoseQualityGate.hideReason(
                        landmarks = displayLandmarks,
                        qualityFlags = frame.qualityFlags,
                        evidenceCard = frame.evidenceCard,
                    )
                    markPosePreviewLowConfidence -> lowConfidencePoseReason(frame.qualityFlags)
                    else -> "pose_preview_only"
                },
                trustFlags = displayTrustFlags,
            )
        } else if (
            "review_only" !in displayTrustFlags &&
            "overlay_only" !in displayTrustFlags &&
            "no_pose_retry_recovered" !in displayTrustFlags &&
            frame.exercise == "unknown" &&
            frame.templateMetrics.isEmpty()
        ) {
            processLandmarks(
                floatArray = displayLandmarks.toFloat99(),
                frameIndex = frame.frameIndex,
                timestampMs = frame.timestampMs,
                subjectTrustFlags = displayTrustFlags,
                subjectLockStatus = displayStatus,
            )
        }
    }

    private fun sessionReviewCoachInsight(): Triple<String, String, CoachInsight>? {
        if (_state.value.phase !is VideoPhase.Complete) return null
        val insight = sessionCoachInsight
        val message = insight.headline.ifBlank {
            insight.whatISaw.ifBlank {
                insight.nextFocus
            }
        }
        if (message.isBlank()) return null
        val priority = if (!insight.fallback) "high" else "medium"
        return Triple(
            message,
            priority,
            CoachInsight(
                message = message,
                priority = priority,
                backend = insight.backend,
                functionName = insight.functionName,
                selectionBasis = insight.selectionBasis,
                evidenceRefs = insight.evidenceRefs,
                summaryNarrative = listOf(
                    insight.whatISaw,
                    insight.whyItMatters,
                    insight.notJudged,
                    insight.nextFocus,
                ).filter { it.isNotBlank() }.joinToString(" "),
                fallback = insight.fallback,
            ),
        )
    }

    private fun scheduleReviewBitmapRestore(index: Int, frame: ProcessedFrame) {
        val timestampMs = frame.timestampMs
        val frameIndex = frame.frameIndex
        reviewBitmapRestoreJob?.cancel()
        reviewBitmapRestoreJob = viewModelScope.launch(Dispatchers.Default) {
            val startedAtMs = System.currentTimeMillis()
            val restoredBitmap = withContext(Dispatchers.IO) {
                restoredReviewBitmap(frame)
            }
            val latencyMs = System.currentTimeMillis() - startedAtMs
            if (restoredBitmap == null) {
                val failedStatus = _live.value.reviewFrameStatus.copy(
                    frameIndex = frameIndex,
                    timestampMs = timestampMs,
                    bitmapRestoring = false,
                    bitmapRestoreFailed = true,
                    restoreLatencyMs = latencyMs,
                    noPoseReason = "frame_image_restore_failed",
                )
                if (currentFrameIdx == index) {
                    _live.value = _live.value.copy(reviewFrameStatus = failedStatus)
                    publishReviewFrameState(failedStatus)
                }
                GemmaFitDebugApi.record(
                    category = "review_frame",
                    message = "bitmap_restore_failed",
                    data = mapOf(
                        "frame" to frameIndex,
                        "timestamp_ms" to timestampMs,
                        "latency_ms" to latencyMs,
                    ),
                )
                return@launch
            }

            val stored = updateProcessedFrameAt(index) {
                it.copy(
                    bitmap = restoredBitmap,
                    bitmapWidth = restoredBitmap.width,
                    bitmapHeight = restoredBitmap.height,
                )
            }
            if (!stored) {
                if (!restoredBitmap.isRecycled) {
                    restoredBitmap.recycle()
                }
                return@launch
            }
            synchronized(processedFramesLock) {
                reviewBitmapRestoredFrameIndexes.add(frameIndex)
            }
            GemmaFitDebugApi.record(
                category = "review_frame",
                message = "bitmap_restored",
                data = mapOf(
                    "frame" to frameIndex,
                    "timestamp_ms" to timestampMs,
                    "latency_ms" to latencyMs,
                    "width" to restoredBitmap.width,
                    "height" to restoredBitmap.height,
                ),
            )
            if (currentFrameIdx == index) {
                showFrame(index, pinReview = true, updatePreview = true)
            }
        }
    }

    private fun scheduleReviewPoseRecovery(index: Int, frame: ProcessedFrame) {
        if (!ENABLE_REVIEW_POSE_RECOVERY) {
            GemmaFitDebugApi.record(
                category = "pose",
                message = "review_recovery_disabled",
                data = mapOf(
                    "frame" to frame.frameIndex,
                    "timestamp_ms" to frame.timestampMs,
                ),
            )
            return
        }
        val shouldAttempt = synchronized(processedFramesLock) {
            reviewRecoveryAttemptedFrameIndexes.add(frame.frameIndex)
        }
        if (!shouldAttempt) return

        val timestampMs = frame.timestampMs
        reviewRecoveryJob?.cancel()
        reviewRecoveryJob = viewModelScope.launch(Dispatchers.Default) {
            val recoveryBitmap = withContext(Dispatchers.IO) {
                restoredReviewBitmap(frame, REVIEW_RECOVERY_LONG_SIDE)
            }
            if (recoveryBitmap == null) {
                GemmaFitDebugApi.record(
                    category = "pose",
                    message = "review_recovery_frame_unavailable",
                    data = mapOf("frame" to frame.frameIndex, "timestamp_ms" to timestampMs),
                )
                return@launch
            }

            try {
                val candidates = runCatching { recoverPoseCandidatesForReview(recoveryBitmap) }
                    .onFailure { error ->
                        Log.w(TAG, "Review pose recovery failed at $timestampMs ms: ${error.message}", error)
                        GemmaFitDebugApi.record(
                            category = "pose",
                            message = "review_recovery_failed",
                            data = mapOf(
                                "frame" to frame.frameIndex,
                                "timestamp_ms" to timestampMs,
                                "error" to (error.message ?: "unknown"),
                            ),
                        )
                    }
                    .getOrElse { emptyList() }
                val selected = candidates.firstOrNull { hasRenderablePose(it.landmarks) }
                if (selected == null) {
                    GemmaFitDebugApi.record(
                        category = "pose",
                        message = "review_recovery_missed",
                        data = mapOf("frame" to frame.frameIndex, "timestamp_ms" to timestampMs),
                    )
                    return@launch
                }

                val recoveryFlag = QualityFlag(
                    id = "review_pose_recovered",
                    status = "VIEW_LIMITED",
                    value = 1f,
                    threshold = 1f,
                    evidence = "review_recovery",
                    reason = "pose_recovered_for_review_only",
                )
                val evidenceCard = EvidenceCard(
                    verdict = "VIEW_LIMITED",
                    reason = "pose_recovered_for_review_only",
                    evidence = listOf(
                        EvidenceItem("review_recovery", "pose"),
                        EvidenceItem("frame", frame.frameIndex.toString()),
                    ),
                    trustFlags = listOf("VIEW_LIMITED:review_pose_recovered", "review_only"),
                )
                val updated = updateProcessedFrameAt(index) {
                    it.copy(
                        landmarks = selected.landmarks,
                        poseCandidates = candidates,
                        activeSubjectIndex = 0,
                        activeSubjectTrackId = null,
                        subjectLockStatus = SubjectLockStatus.SINGLE_AUTO,
                        subjectTrustFlags = listOf("review_pose_recovered", "review_only"),
                        qualityFlags = listOf(recoveryFlag),
                        trustMatrix = buildTrustMatrix(listOf(recoveryFlag), emptyList()),
                        evidenceCard = evidenceCard,
                        coachMessage = "Pose recovered for review only. This frame was not used for form judgment.",
                        coachPriority = "low",
                    )
                }
                GemmaFitDebugApi.record(
                    category = "pose",
                    message = "review_recovery_recovered",
                    data = mapOf(
                        "frame" to frame.frameIndex,
                        "timestamp_ms" to timestampMs,
                        "candidate_count" to candidates.size,
                        "stored" to updated,
                    ),
                )
                if (updated && currentFrameIdx == index) {
                    showFrame(index, pinReview = true, updatePreview = false)
                }
            } finally {
                if (!recoveryBitmap.isRecycled) {
                    recoveryBitmap.recycle()
                }
            }
        }
    }

    private fun recoverPoseCandidatesForReview(bitmap: Bitmap): List<PoseCandidate> {
        fun options(delegate: com.google.mediapipe.tasks.core.Delegate): PoseLandmarker.PoseLandmarkerOptions {
            return PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                        .setDelegate(delegate)
                        .setModelAssetPath("pose_landmarker_lite.task")
                        .build()
                )
                .setMinPoseDetectionConfidence(0.35f)
                .setMinPosePresenceConfidence(0.35f)
                .setMinTrackingConfidence(0.35f)
                .setNumPoses(FULL_POSE_CANDIDATES)
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
                .build()
        }

        val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
        val landmarker = try {
            PoseLandmarker.createFromOptions(
                getApplication(),
                options(com.google.mediapipe.tasks.core.Delegate.GPU),
            )
        } catch (gpuError: Exception) {
            Log.w(TAG, "Review recovery GPU failed, trying CPU: ${gpuError.message}")
            PoseLandmarker.createFromOptions(
                getApplication(),
                options(com.google.mediapipe.tasks.core.Delegate.CPU),
            )
        }
        return try {
            candidatesFromLandmarkerResult(landmarker.detect(mpImage))
        } finally {
            runCatching { landmarker.close() }
        }
    }

    private fun createNoPoseRetryLandmarker(): PoseLandmarker {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                com.google.mediapipe.tasks.core.BaseOptions.builder()
                    .setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                    .setModelAssetPath("pose_landmarker_lite.task")
                    .build()
            )
            .setMinPoseDetectionConfidence(0.35f)
            .setMinPosePresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .setNumPoses(FULL_POSE_CANDIDATES)
            .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.IMAGE)
            .build()
        return PoseLandmarker.createFromOptions(getApplication(), options)
    }

    private fun recoverPoseCandidatesForNoPoseRetry(
        landmarker: PoseLandmarker,
        bitmap: Bitmap,
    ): List<PoseCandidate> {
        val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
        return candidatesFromLandmarkerResult(landmarker.detect(mpImage))
    }

    private fun storeNoPoseRetryRecovery(
        processedIndex: Int,
        frame: ProcessedFrame,
        candidates: List<PoseCandidate>,
    ): Boolean {
        val selected = candidates.firstOrNull { hasRenderablePose(it.landmarks) } ?: return false
        val recoveryFlag = QualityFlag(
            id = "no_pose_retry_recovered",
            status = "VIEW_LIMITED",
            value = 1f,
            threshold = 1f,
            evidence = "no_pose_retry",
            reason = "pose_recovered_for_overlay_only",
        )
        val evidenceCard = EvidenceCard(
            verdict = "VIEW_LIMITED",
            reason = "pose_recovered_for_overlay_only",
            evidence = listOf(
                EvidenceItem("no_pose_retry", "pose"),
                EvidenceItem("frame", frame.frameIndex.toString()),
            ),
            trustFlags = listOf("VIEW_LIMITED:no_pose_retry_recovered", "overlay_only"),
        )
        return updateProcessedFrameAt(processedIndex) {
            it.copy(
                landmarks = selected.landmarks,
                poseCandidates = candidates,
                activeSubjectIndex = 0,
                activeSubjectTrackId = null,
                subjectLockStatus = SubjectLockStatus.SINGLE_AUTO,
                subjectTrustFlags = listOf("no_pose_retry_recovered", "overlay_only"),
                qualityFlags = listOf(recoveryFlag),
                trustMatrix = buildTrustMatrix(listOf(recoveryFlag), emptyList()),
                evidenceCard = evidenceCard,
                coachMessage = "Pose recovered for overlay only. This frame was not used for form judgment.",
                coachPriority = "low",
            )
        }
    }

    private suspend fun runNoPoseIslandRetryPass(uri: Uri): NoPoseRetrySummary {
        if (!ENABLE_NO_POSE_ISLAND_RETRY) {
            return NoPoseRetrySummary(
                enabled = false,
                attempted = false,
                reason = "disabled",
            )
        }
        val islands = collectNoPoseIslands()
        if (islands.isEmpty()) {
            val summary = NoPoseRetrySummary(
                enabled = true,
                attempted = false,
                reason = "no_no_pose_islands",
            )
            GemmaFitDebugApi.updateState("no_pose_retry", summary.toDebugMap())
            return summary
        }
        val targetFrames = framesForNoPoseRetry(islands)
        if (targetFrames.isEmpty()) {
            val summary = NoPoseRetrySummary(
                enabled = true,
                attempted = false,
                islandCount = islands.size,
                reason = "no_target_frames",
                islands = islands,
            )
            GemmaFitDebugApi.updateState("no_pose_retry", summary.toDebugMap())
            return summary
        }

        val startedAt = System.currentTimeMillis()
        GemmaFitDebugApi.record(
            category = "pose",
            message = "no_pose_retry_start",
            data = mapOf(
                "island_count" to islands.size,
                "target_frame_count" to targetFrames.size,
                "long_side" to NO_POSE_RETRY_LONG_SIDE,
                "expand_ms" to NO_POSE_RETRY_EXPAND_MS,
            ),
        )

        return withContext(Dispatchers.Default) {
            val retriever = MediaMetadataRetriever()
            var landmarker: PoseLandmarker? = null
            var recovered = 0
            var unresolved = 0
            val recoveredFrames = mutableListOf<Int>()
            val unresolvedFrames = mutableListOf<Int>()
            try {
                retriever.setDataSource(getApplication(), uri)
                landmarker = createNoPoseRetryLandmarker()
                targetFrames.forEach { target ->
                    val frame = target.frame
                    val bitmap = analysisScaledFrameAtTime(
                        retriever = retriever,
                        timeMs = frame.timestampMs,
                        longSide = NO_POSE_RETRY_LONG_SIDE,
                    )
                    if (bitmap == null) {
                        unresolved += 1
                        unresolvedFrames += frame.frameIndex
                        return@forEach
                    }
                    try {
                        val candidates = recoverPoseCandidatesForNoPoseRetry(landmarker, bitmap)
                        if (candidates.isNotEmpty() &&
                            storeNoPoseRetryRecovery(target.processedIndex, frame, candidates)
                        ) {
                            recovered += 1
                            recoveredFrames += frame.frameIndex
                        } else {
                            unresolved += 1
                            unresolvedFrames += frame.frameIndex
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "No-pose retry failed at frame ${frame.frameIndex}: ${error.message}", error)
                        unresolved += 1
                        unresolvedFrames += frame.frameIndex
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "No-pose retry pass failed: ${error.message}", error)
                unresolved += targetFrames.size - recovered - unresolved
            } finally {
                runCatching { landmarker?.close() }
                runCatching { retriever.release() }
            }

            val summary = NoPoseRetrySummary(
                enabled = true,
                attempted = true,
                islandCount = islands.size,
                targetFrameCount = targetFrames.size,
                recoveredCount = recovered,
                unresolvedCount = unresolved,
                elapsedMs = System.currentTimeMillis() - startedAt,
                reason = "completed",
                islands = islands,
            )
            val debugMap = summary.toDebugMap() + mapOf(
                "recovered_frames" to recoveredFrames,
                "unresolved_frames" to unresolvedFrames,
                "overlay_only" to true,
            )
            GemmaFitDebugApi.updateState("no_pose_retry", debugMap)
            GemmaFitDebugApi.record(
                category = "pose",
                message = "no_pose_retry_complete",
                data = debugMap,
            )
            summary
        }
    }

    private fun storedSubjectSelection(frame: ProcessedFrame): SubjectSelection {
        val storedReason = storedSubjectReason(frame.subjectLockStatus, frame.subjectTrustFlags)
        return SubjectSelection(
            candidate = frame.landmarks.takeIf { it.isNotEmpty() }?.let { candidateFromPoseData(it) },
            activeIndex = frame.activeSubjectIndex,
            trackId = frame.activeSubjectTrackId,
            status = frame.subjectLockStatus,
            trustFlags = frame.subjectTrustFlags,
            reason = storedReason,
        )
    }

    private fun storedSubjectReason(
        status: SubjectLockStatus,
        trustFlags: List<String>,
    ): String {
        return when {
            status == SubjectLockStatus.NEEDS_SELECTION &&
                ("person_detector_multi_person" in trustFlags || "MULTI_PERSON" in trustFlags) -> {
                "multi_person_selection_required"
            }
            status == SubjectLockStatus.NEEDS_SELECTION -> "subject_selection_required"
            "subject_identity_uncertain" in trustFlags -> "subject_identity_uncertain"
            "subject_hold" in trustFlags -> "subject_tracking_hold"
            status == SubjectLockStatus.SUBJECT_LOST -> "no_person_detected"
            else -> ""
        }
    }

    private fun updateProcessedFrame(
        frameIndex: Int,
        exercise: String,
        confidence: Float,
        warnings: List<SafetyWarning>,
        flags: List<QualityFlag>,
        metrics: Map<String, Float>,
        movementPhase: String,
        trustMatrix: List<TrustMatrixItem>,
        evidenceCard: EvidenceCard,
        coachMsg: String,
        coachPriority: String,
        capabilityContract: CapabilityContract = CapabilityContract(),
        subjectTrustFlags: List<String> = emptyList(),
        subjectLockStatus: SubjectLockStatus? = null,
    ) {
        updateProcessedFrameByFrameIndex(frameIndex) { frame ->
            val hideForOverlayQuality = OverlayPoseQualityGate.shouldHideSkeleton(
                landmarks = frame.landmarks,
                qualityFlags = flags,
                evidenceCard = evidenceCard,
            )
            val markLowConfidencePreview = shouldMarkPosePreviewLowConfidence(flags)
            val subjectBlocksHardJudgment = shouldHideSelectedSkeleton(
                subjectTrustFlags = subjectTrustFlags,
                subjectLockStatus = subjectLockStatus,
            )
            val storedSubjectTrustFlags = when {
                hideForOverlayQuality -> {
                    (subjectTrustFlags +
                        "pose_hidden_by_quality" +
                        "pose_preview_keypoint_limited" +
                        "judgment_blocked_keypoint_visibility").distinct()
                }
                markLowConfidencePreview -> {
                    (subjectTrustFlags +
                        "low_confidence" +
                        "pose_preview_low_confidence" +
                        "judgment_blocked_low_confidence").distinct()
                }
                subjectBlocksHardJudgment -> {
                    (subjectTrustFlags +
                        "pose_preview_subject_limited" +
                        "judgment_blocked_subject").distinct()
                }
                else -> subjectTrustFlags
            }
            frame.copy(
                landmarks = frame.landmarks,
                poseCandidates = frame.poseCandidates,
                activeSubjectIndex = frame.activeSubjectIndex,
                activeSubjectTrackId = frame.activeSubjectTrackId,
                subjectLockStatus = subjectLockStatus ?: frame.subjectLockStatus,
                exercise = exercise,
                exerciseConfidence = confidence,
                movementPhase = movementPhase,
                warnings = warnings,
                qualityFlags = flags,
                templateMetrics = metrics,
                trustMatrix = trustMatrix,
                evidenceCard = evidenceCard,
                capabilityContract = capabilityContract,
                coachMessage = coachMsg,
                coachPriority = coachPriority,
                subjectTrustFlags = storedSubjectTrustFlags.ifEmpty { frame.subjectTrustFlags },
            )
        }
    }

    private data class RelocalizationBurstResult(
        val decision: SubjectRelocalizationDecision,
        val detection: PersonDetectionResult? = null,
        val proposals: List<PersonProposal> = emptyList(),
        val trustFlags: List<String> = emptyList(),
    )

    private data class MultiPersonDetectorGuard(
        val active: Boolean = false,
        val proposalCount: Int = 0,
        val trustFlags: List<String> = emptyList(),
        val reason: String = "",
    )

    private fun evaluateMultiPersonDetectorGuard(
        bitmap: Bitmap,
        timestampMs: Long,
        frameIndex: Int,
        candidates: List<PoseCandidate>,
    ): MultiPersonDetectorGuard {
        if (manualSubjectLock) return MultiPersonDetectorGuard()
        if (candidates.size > 1) {
            multiPersonDetectorHoldUntilFrame = frameIndex + MULTI_PERSON_DETECTOR_HOLD_FRAMES
            multiPersonDetectorProposalCount = candidates.size
            return MultiPersonDetectorGuard(
                active = true,
                proposalCount = candidates.size,
                trustFlags = multiPersonGuardFlags(candidates.size, "mediapipe_multi_pose"),
                reason = "multi_person_selection_required",
            )
        }

        val shouldProbeDetector = personDetector.isAvailable &&
            candidates.size <= 1 &&
            frameIndex % MULTI_PERSON_DETECTOR_INTERVAL_FRAMES == 0

        if (shouldProbeDetector) {
            val detection = personDetector.detect(
                bitmap = bitmap,
                timestampMs = timestampMs,
                predictedBbox = subjectMotionTracker.predict(timestampMs)?.bbox ?: lockedSubject?.bbox,
            )
            val proposalCount = detection.proposals
                .count { it.score >= PersonProposalFusion.MIN_PROPOSAL_SCORE && it.bbox.area > 0f }
            val detectorFlags = buildList {
                add("person_detector_multi_person_guard")
                add("person_detector_requested")
                if (detection.available) {
                    add("person_detector_${detection.source}")
                    if (proposalCount >= MULTI_PERSON_MIN_PROPOSALS) add("person_detector_multi_person")
                } else {
                    add("person_detector_unavailable")
                }
                if (detection.error.isNotBlank()) add("person_detector_error")
            }.distinct()
            GemmaFitDebugApi.record(
                category = "person_detector",
                message = "multi_person_guard",
                data = mapOf(
                    "frame" to frameIndex,
                    "timestamp_ms" to timestampMs,
                    "available" to detection.available,
                    "source" to detection.source,
                    "proposal_count" to proposalCount,
                    "raw_proposal_count" to detection.proposals.size,
                    "latency_ms" to detection.latencyMs,
                    "error" to detection.error,
                ),
            )
            GemmaFitDebugApi.updateState(
                section = "person_detector",
                data = mapOf(
                    "frame" to frameIndex,
                    "timestamp_ms" to timestampMs,
                    "mode" to "MULTI_PERSON_GUARD",
                    "reason" to "single_pose_detector_cross_check",
                    "available" to detection.available,
                    "source" to detection.source,
                    "proposal_count" to proposalCount,
                    "raw_proposal_count" to detection.proposals.size,
                    "latency_ms" to detection.latencyMs,
                    "trust_flags" to detectorFlags,
                ),
            )
            if (proposalCount >= MULTI_PERSON_MIN_PROPOSALS) {
                multiPersonDetectorHoldUntilFrame = frameIndex + MULTI_PERSON_DETECTOR_HOLD_FRAMES
                multiPersonDetectorProposalCount = proposalCount
            }
        }

        return if (frameIndex <= multiPersonDetectorHoldUntilFrame &&
            multiPersonDetectorProposalCount >= MULTI_PERSON_MIN_PROPOSALS
        ) {
            MultiPersonDetectorGuard(
                active = true,
                proposalCount = multiPersonDetectorProposalCount,
                trustFlags = multiPersonGuardFlags(multiPersonDetectorProposalCount, "person_detector_multi_person"),
                reason = "multi_person_detector_guard",
            )
        } else {
            MultiPersonDetectorGuard()
        }
    }

    private fun multiPersonGuardFlags(proposalCount: Int, source: String): List<String> {
        return listOf(
            "MULTI_PERSON",
            "NEEDS_SELECTION",
            "subject_hold",
            "subject_identity_uncertain",
            "person_detector_multi_person",
            source,
            "person_proposals_$proposalCount",
        ).distinct()
    }

    private fun clearAutomaticSubjectLockForMultiPerson() {
        if (manualSubjectLock) return
        lockedSubject = null
        lockedSubjectAppearance = null
        lockedSubjectTrackId = null
        pendingAutoSubject = null
        pendingAutoSubjectFrames = 0
        lostSubjectFrames = 0
        identityHoldFrames = 0
        subjectMotionTracker.reset()
        resetVideoLandmarkStability()
    }

    private fun resetMultiPersonDetectorGuard() {
        multiPersonDetectorHoldUntilFrame = -1
        multiPersonDetectorProposalCount = 0
    }

    private fun multiPersonGuardSelection(
        guard: MultiPersonDetectorGuard,
        timestampMs: Long,
    ): SubjectSelection {
        clearAutomaticSubjectLockForMultiPerson()
        subjectMotionTracker.markHold(timestampMs)
        return SubjectSelection(
            candidate = null,
            activeIndex = null,
            trackId = null,
            status = SubjectLockStatus.NEEDS_SELECTION,
            trustFlags = guard.trustFlags,
            reason = guard.reason.ifBlank { "multi_person_selection_required" },
        )
    }

    private fun evaluateSubjectRelocalization(
        bitmap: Bitmap,
        timestampMs: Long,
        frameIndex: Int,
        candidates: List<PoseCandidate>,
        selection: SubjectSelection,
    ): RelocalizationBurstResult {
        val decision = subjectRelocalizationPolicy.update(
            status = selection.status,
            hasActiveSubject = selection.candidate != null,
            candidateCount = candidates.size,
            reason = selection.reason,
            timestampMs = timestampMs,
        )
        if (!decision.shouldRequestBurst) {
            return RelocalizationBurstResult(
                decision = decision,
                trustFlags = decision.trustFlags,
            )
        }

        val detector = personDetector
        val detection = if (detector.isAvailable) {
            detector.detect(
                bitmap = bitmap,
                timestampMs = timestampMs,
                predictedBbox = subjectMotionTracker.predict(timestampMs)?.bbox ?: lockedSubject?.bbox,
            )
        } else {
            detector.detect(bitmap, timestampMs, null)
        }
        val detectorFlags = buildList {
            addAll(decision.trustFlags)
            add("person_detector_requested")
            if (detection.available) {
                add("person_detector_${detection.source}")
                if (detection.proposals.isEmpty()) add("person_detector_no_person")
            } else {
                add("person_detector_unavailable")
            }
            if (detection.error.isNotBlank()) add("person_detector_error")
        }.distinct()

        GemmaFitDebugApi.record(
            category = "person_detector",
            message = "burst_result",
            data = mapOf(
                "frame" to frameIndex,
                "timestamp_ms" to timestampMs,
                "mode" to decision.mode.name,
                "reason" to decision.reason,
                "available" to detection.available,
                "source" to detection.source,
                "proposal_count" to detection.proposals.size,
                "latency_ms" to detection.latencyMs,
                "error" to detection.error,
            ),
        )
        GemmaFitDebugApi.updateState(
            section = "person_detector",
            data = mapOf(
                "frame" to frameIndex,
                "timestamp_ms" to timestampMs,
                "mode" to decision.mode.name,
                "reason" to decision.reason,
                "available" to detection.available,
                "source" to detection.source,
                "proposal_count" to detection.proposals.size,
                "latency_ms" to detection.latencyMs,
                "trust_flags" to detectorFlags,
            ),
        )

        return RelocalizationBurstResult(
            decision = decision,
            detection = detection,
            proposals = detection.proposals,
            trustFlags = detectorFlags,
        )
    }

    private fun recoverSelectionFromPersonProposals(
        candidates: List<PoseCandidate>,
        proposals: List<PersonProposal>,
        timestampMs: Long,
    ): SubjectSelection? {
        val previous = lockedSubject ?: return null
        val predictedBbox = subjectMotionTracker.predict(timestampMs)?.bbox ?: previous.bbox
        val proposal = PersonProposalFusion.bestProposalForAnchor(proposals, predictedBbox) ?: return null
        val candidatePool = PersonProposalFusion.candidatesNearProposal(candidates, proposal)
            .ifEmpty { candidates }
        val match = SubjectIdentityMatcher.select(
            previous = previous,
            appearanceAnchor = lockedSubjectAppearance,
            candidates = candidatePool,
            threshold = SUBJECT_MATCH_THRESHOLD,
            recoveringFromHold = identityHoldFrames > 0,
            predictedBbox = proposal.bbox,
        )
        val best = match.candidate ?: return null
        val sourceIndex = candidates.indexOfFirst { it.landmarks === best.landmarks }.takeIf { it >= 0 }
        val status = if (manualSubjectLock) SubjectLockStatus.LOCKED else SubjectLockStatus.AUTO_LOCKED
        val trackId = lockedSubjectTrackId ?: nextSubjectTrackId++
        val trackedBest = best.copy(trackId = trackId)
        val selection = ownedSubjectSelection(
            candidate = trackedBest,
            candidates = candidates,
            activeIndex = sourceIndex,
            trackId = trackId,
            status = status,
            trustFlags = (
                subjectTrustFlagsFor(candidates, status) +
                    "subject_relocalized" +
                    "person_detector_reacquired"
                ).distinct(),
            reason = "person_detector_reacquired",
            targetBbox = proposal.bbox,
            timestampMs = timestampMs,
        )
        if (selection.candidate != null) {
            lockedSubjectTrackId = trackId
            lockedSubject = selection.candidate
            lostSubjectFrames = 0
            identityHoldFrames = 0
        }
        return selection
    }

    private fun publishNoPoseFrame(
        frameIndex: Int,
        timestampMs: Long,
        bitmap: Bitmap,
        bmpWidth: Int,
        bmpHeight: Int,
        pass: VideoAnalysisPass,
        reason: String,
    ) {
        val selection = resolveSubjectSelection(emptyList(), timestampMs)
        val relocalization = evaluateSubjectRelocalization(
            bitmap = bitmap,
            timestampMs = timestampMs,
            frameIndex = frameIndex,
            candidates = emptyList(),
            selection = selection,
        )
        val subjectTrustFlags = (selection.trustFlags + relocalization.trustFlags).distinct()
        val frameCount = appendProcessedFrame(
            ProcessedFrame(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                bitmap = bitmap,
                bitmapWidth = bmpWidth,
                bitmapHeight = bmpHeight,
                landmarks = emptyList(),
                poseCandidates = emptyList(),
                activeSubjectIndex = null,
                activeSubjectTrackId = selection.trackId,
                subjectLockStatus = selection.status,
                subjectTrustFlags = subjectTrustFlags,
            )
        )
        val shouldKeepPinnedReview = shouldPreservePinnedReviewFrame()
        val shouldShowFirstFullFrame = pass == VideoAnalysisPass.FULL && frameCount == 1 && currentFrameIdx == 0
        if (shouldShowFirstFullFrame) {
            reviewFramePinned = false
        }
        if (!shouldKeepPinnedReview) {
            currentFrameIdx = frameCount - 1
        }
        val analysisStage = if (pass == VideoAnalysisPass.PREVIEW) "Preview analysis running" else "Full analysis running"
        if (shouldKeepPinnedReview && !shouldShowFirstFullFrame) {
            _live.value = _live.value.copy(
                analysisStage = analysisStage,
                isPreviewData = pass == VideoAnalysisPass.PREVIEW,
                latestProcessedTimestampMs = timestampMs,
                totalFramesAnalyzed = frameCount,
            )
            publishSubjectGate(
                candidates = emptyList(),
                status = selection.status,
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                reason = reason.ifBlank { selection.reason },
                trustFlags = subjectTrustFlags,
            )
            return
        }
        _live.value = _live.value.copy(
            poseLandmarks = emptyList(),
            poseTrajectory = emptyList(),
            poseCandidates = emptyList(),
            activeSubjectIndex = null,
            activeSubjectTrackId = selection.trackId,
            subjectLockStatus = selection.status,
            subjectTrustFlags = subjectTrustFlags,
            analysisStage = analysisStage,
            isPreviewData = pass == VideoAnalysisPass.PREVIEW,
            videoPreview = bitmap,
            videoPreviewWidth = bmpWidth,
            videoPreviewHeight = bmpHeight,
            currentFrameIndex = frameCount - 1,
            currentFrameTimestampMs = timestampMs,
            latestProcessedTimestampMs = timestampMs,
            totalFramesAnalyzed = frameCount,
        )
        publishSubjectGate(
            candidates = emptyList(),
            status = selection.status,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            reason = reason.ifBlank { selection.reason },
            trustFlags = subjectTrustFlags,
        )
    }

    // ── Landmark processing (video) ─────────────────────────────────────

    private fun processLandmarks(
        result: VideoPoseResult,
        frameIndex: Int,
        timestampMs: Long,
        bitmap: Bitmap,
        bmpWidth: Int,
        bmpHeight: Int,
        runNativeMetrics: Boolean = true,
        pass: VideoAnalysisPass = VideoAnalysisPass.FULL,
    ) {
        if (result.landmarks.isEmpty()) {
            Log.w(TAG, "Frame $frameIndex: no pose landmarks detected")
            resetVideoLandmarkStability()
            publishNoPoseFrame(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                bitmap = bitmap,
                bmpWidth = bmpWidth,
                bmpHeight = bmpHeight,
                pass = pass,
                reason = "no_person_detected",
            )
            return
        }
        val candidates = candidatesFromVideoResult(result, bitmap)
        if (candidates.isEmpty()) {
            Log.w(TAG, "Frame $frameIndex: no valid 33-landmark candidates")
            resetVideoLandmarkStability()
            publishNoPoseFrame(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                bitmap = bitmap,
                bmpWidth = bmpWidth,
                bmpHeight = bmpHeight,
                pass = pass,
                reason = "no_person_detected",
            )
            return
        }
        val multiPersonGuard = evaluateMultiPersonDetectorGuard(
            bitmap = bitmap,
            timestampMs = timestampMs,
            frameIndex = frameIndex,
            candidates = candidates,
        )
        var selection = if (multiPersonGuard.active) {
            multiPersonGuardSelection(multiPersonGuard, timestampMs)
        } else {
            resolveSubjectSelection(candidates, timestampMs)
        }
        val relocalization = if (multiPersonGuard.active) {
            RelocalizationBurstResult(
                decision = SubjectRelocalizationDecision(
                    mode = SubjectRelocalizationMode.TRACKING,
                    reason = multiPersonGuard.reason,
                    shouldRequestBurst = false,
                    trustFlags = multiPersonGuard.trustFlags,
                ),
                trustFlags = multiPersonGuard.trustFlags,
            )
        } else {
            evaluateSubjectRelocalization(
                bitmap = bitmap,
                timestampMs = timestampMs,
                frameIndex = frameIndex,
                candidates = candidates,
                selection = selection,
            )
        }
        if (!multiPersonGuard.active && selection.candidate == null && relocalization.proposals.isNotEmpty()) {
            recoverSelectionFromPersonProposals(
                candidates = candidates,
                proposals = relocalization.proposals,
                timestampMs = timestampMs,
            )?.let { recovered ->
                selection = recovered
            }
        }
        val subjectTrustFlags = (selection.trustFlags + relocalization.trustFlags).distinct()
        val poseData = selection.candidate?.landmarks
            ?.let { stabilizeVideoLandmarks(it, selection.trackId, pass) }
            ?: emptyList()
        val hidePendingOverlayForPoseQuality = shouldHideLiveOverlayBeforeNative(
            landmarks = poseData,
            subjectTrustFlags = subjectTrustFlags,
            subjectLockStatus = selection.status,
        )
        val livePoseData = if (hidePendingOverlayForPoseQuality) emptyList() else poseData
        val livePoseCandidates = if (hidePendingOverlayForPoseQuality) emptyList() else candidates
        val livePendingSubjectTrustFlags = if (hidePendingOverlayForPoseQuality) {
            (subjectTrustFlags +
                "pose_hidden_pending_quality" +
                "pose_preview_keypoint_limited").distinct()
        } else {
            subjectTrustFlags
        }
        // Store frame metadata (bitmap lives in LRU cache, not here)
        val frameCount = appendProcessedFrame(
            ProcessedFrame(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                bitmap = bitmap,
                bitmapWidth = bmpWidth,
                bitmapHeight = bmpHeight,
                landmarks = poseData,
                poseCandidates = candidates,
                activeSubjectIndex = selection.activeIndex,
                activeSubjectTrackId = selection.trackId,
                subjectLockStatus = selection.status,
                subjectTrustFlags = subjectTrustFlags,
            )
        )
        maybeScheduleEarlyVideoVisualContextProbe(
            pass = pass,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            bitmap = bitmap,
            poseData = poseData,
            subjectTrustFlags = subjectTrustFlags,
            subjectLockStatus = selection.status,
        )
        val shouldKeepPinnedReview = shouldPreservePinnedReviewFrame()
        val shouldShowFirstFullFrame = pass == VideoAnalysisPass.FULL && frameCount == 1 && currentFrameIdx == 0
        if (shouldShowFirstFullFrame) {
            reviewFramePinned = false
        }
        if (!shouldKeepPinnedReview) {
            currentFrameIdx = frameCount - 1
        }
        val analysisStage = if (pass == VideoAnalysisPass.PREVIEW) "Preview analysis running" else "Full analysis running"
        if (shouldKeepPinnedReview && !shouldShowFirstFullFrame) {
            _live.value = _live.value.copy(
                analysisStage = analysisStage,
                isPreviewData = pass == VideoAnalysisPass.PREVIEW,
                latestProcessedTimestampMs = timestampMs,
                totalFramesAnalyzed = frameCount,
            )
            if (selection.candidate == null) {
                publishSubjectGate(
                    candidates = candidates,
                    status = selection.status,
                    frameIndex = frameIndex,
                    timestampMs = timestampMs,
                    reason = selection.reason,
                    trustFlags = subjectTrustFlags,
                )
            }
            if (selection.candidate == null || !runNativeMetrics) {
                return
            }
            processLandmarks(
                floatArray = poseData.toFloat99(),
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                subjectTrustFlags = subjectTrustFlags,
                subjectLockStatus = selection.status,
                subjectTrackId = selection.trackId,
            )
            return
        }
        // Update live state with just the landmarks + bitmap for this frame
        _live.value = _live.value.copy(
            poseLandmarks = livePoseData,
            poseTrajectory = if (livePoseData.isNotEmpty()) trajectoryFor(frameCount - 1) else emptyList(),
            poseCandidates = livePoseCandidates,
            activeSubjectIndex = if (hidePendingOverlayForPoseQuality) null else selection.activeIndex,
            activeSubjectTrackId = if (hidePendingOverlayForPoseQuality) null else selection.trackId,
            subjectLockStatus = selection.status,
            subjectTrustFlags = livePendingSubjectTrustFlags,
            analysisStage = analysisStage,
            isPreviewData = pass == VideoAnalysisPass.PREVIEW,
            videoPreview = bitmap,
            videoPreviewWidth = bmpWidth,
            videoPreviewHeight = bmpHeight,
            currentFrameIndex = frameCount - 1,
            currentFrameTimestampMs = timestampMs,
            latestProcessedTimestampMs = timestampMs,
            totalFramesAnalyzed = frameCount,
        )
        if (selection.candidate == null) {
            publishSubjectGate(
                candidates = candidates,
                status = selection.status,
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                reason = selection.reason,
                trustFlags = subjectTrustFlags,
            )
            return
        }
        if (!runNativeMetrics) {
            return
        }
        processLandmarks(
            floatArray = poseData.toFloat99(),
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            subjectTrustFlags = subjectTrustFlags,
            subjectLockStatus = selection.status,
            subjectTrackId = selection.trackId,
        )
    }

    // ── Core processing (called for both camera and video) ──────────────

    private fun trajectoryFor(index: Int, window: Int = 18): List<List<PoseLandmarkData>> {
        return synchronized(processedFramesLock) {
            if (processedFrames.isEmpty() || index !in processedFrames.indices) {
                emptyList()
            } else {
                val start = (index - window + 1).coerceAtLeast(0)
                processedFrames.subList(start, index + 1).map { it.landmarks }
            }
        }
    }

    private data class SubjectSelection(
        val candidate: PoseCandidate?,
        val activeIndex: Int?,
        val trackId: Int?,
        val status: SubjectLockStatus,
        val trustFlags: List<String>,
        val reason: String,
    )

    private fun candidatesFromLandmarkerResult(
        result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult,
        bitmap: Bitmap? = null,
    ): List<PoseCandidate> {
        return result.landmarks()
            .take(MAX_POSE_CANDIDATES)
            .mapNotNull { landmarks ->
                val poseData = landmarks.map {
                    PoseLandmarkData(
                        x = it.x(),
                        y = it.y(),
                        z = 0f,
                        visibility = it.visibility().orElse(0.0f),
                    )
                }
                val candidate = candidateFromPoseData(poseData)
                if (candidate != null && bitmap != null) {
                    candidate.copy(appearance = appearanceSignatureFromBitmap(bitmap, candidate))
                } else {
                    candidate
                }
            }
    }

    private fun candidatesFromVideoResult(result: VideoPoseResult, bitmap: Bitmap? = null): List<PoseCandidate> {
        return result.landmarks
            .take(MAX_POSE_CANDIDATES)
            .mapNotNull { landmarks ->
                val poseData = landmarks.map { lm ->
                    PoseLandmarkData(lm.x, lm.y, lm.z, lm.visibility)
                }
                val candidate = candidateFromPoseData(poseData)
                if (candidate != null && bitmap != null) {
                    candidate.copy(appearance = appearanceSignatureFromBitmap(bitmap, candidate))
                } else {
                    candidate
                }
            }
    }

    private fun hasRenderablePose(landmarks: List<PoseLandmarkData>): Boolean {
        return PosePresenceGate.canRender(landmarks, { it.x }, { it.y }, { it.visibility })
    }

    private fun stabilizeCameraLandmarks(
        landmarks: List<PoseLandmarkData>,
        trackId: Int?,
    ): List<PoseLandmarkData> {
        if (landmarks.isEmpty()) {
            resetCameraLandmarkStability()
            return emptyList()
        }
        if (cameraStabilizerTrackId != trackId) {
            cameraLandmarkStabilizer.reset()
            cameraStabilizerTrackId = trackId
        }
        return cameraLandmarkStabilizer.apply(landmarks)
    }

    private fun stabilizeVideoLandmarks(
        landmarks: List<PoseLandmarkData>,
        trackId: Int?,
        pass: VideoAnalysisPass,
    ): List<PoseLandmarkData> {
        if (landmarks.isEmpty()) {
            resetVideoLandmarkStability()
            return emptyList()
        }
        if (videoStabilizerTrackId != trackId || videoStabilizerPass != pass) {
            videoLandmarkStabilizer.reset()
            videoStabilizerTrackId = trackId
            videoStabilizerPass = pass
        }
        return videoLandmarkStabilizer.apply(landmarks)
    }

    private fun resetCameraLandmarkStability() {
        cameraLandmarkStabilizer.reset()
        cameraStabilizerTrackId = null
    }

    private fun resetVideoLandmarkStability() {
        videoLandmarkStabilizer.reset()
        videoStabilizerTrackId = null
        videoStabilizerPass = null
    }

    private fun resetAllLandmarkStability() {
        resetCameraLandmarkStability()
        resetVideoLandmarkStability()
    }

    private fun clearLostLockedSubject() {
        if (activeTargetReanalysisSeed != null) {
            pendingSubjectTap = null
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            identityHoldFrames = 0
            subjectMotionTracker.reset()
            return
        }
        resetNativeTemporalLandmarks()
        lockedSubject = null
        lockedSubjectAppearance = null
        lockedSubjectTrackId = null
        manualSubjectLock = false
        pendingSubjectTap = null
        pendingAutoSubject = null
        pendingAutoSubjectFrames = 0
        identityHoldFrames = 0
        subjectMotionTracker.reset()
    }

    private fun candidateFromPoseData(
        landmarks: List<PoseLandmarkData>,
        trackScore: Float = 0f,
    ): PoseCandidate? {
        if (landmarks.size < 33) return null
        val stats = PosePresenceGate.evaluate(landmarks, { it.x }, { it.y }, { it.visibility })
        if (!stats.canRender) return null
        val bbox = PoseBoundingBox(stats.minX, stats.minY, stats.maxX, stats.maxY)
        val torso = listOf(11, 12, 23, 24).mapNotNull { landmarks.getOrNull(it) }
            .filter { it.visibility >= PosePresenceGate.HIGH_VISIBILITY_THRESHOLD }
        val centerX = if (torso.isNotEmpty()) torso.map { it.x }.average().toFloat() else (bbox.minX + bbox.maxX) / 2f
        val centerY = if (torso.isNotEmpty()) torso.map { it.y }.average().toFloat() else (bbox.minY + bbox.maxY) / 2f
        return PoseCandidate(
            landmarks = landmarks,
            bbox = bbox,
            centerX = centerX.coerceIn(0f, 1f),
            centerY = centerY.coerceIn(0f, 1f),
            avgVisibility = stats.avgVisibility,
            trackScore = trackScore,
        )
    }

    private fun appearanceSignatureFromBitmap(
        bitmap: Bitmap,
        candidate: PoseCandidate,
    ): SubjectAppearanceSignature? {
        if (bitmap.width <= 1 || bitmap.height <= 1) return null

        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        var hasVisibleUpperBody = false
        for (index in intArrayOf(11, 12, 13, 14, 15, 16, 23, 24)) {
            val landmark = candidate.landmarks.getOrNull(index) ?: continue
            if (landmark.visibility < PosePresenceGate.HIGH_VISIBILITY_THRESHOLD) continue
            minX = minOf(minX, landmark.x)
            minY = minOf(minY, landmark.y)
            maxX = maxOf(maxX, landmark.x)
            maxY = maxOf(maxY, landmark.y)
            hasVisibleUpperBody = true
        }

        val leftNorm: Float
        val rightNorm: Float
        val topNorm: Float
        val bottomNorm: Float
        if (hasVisibleUpperBody) {
            val padX = maxOf(0.02f, candidate.bbox.width * 0.16f)
            val padY = maxOf(0.02f, candidate.bbox.height * 0.12f)
            leftNorm = (minX - padX).coerceIn(0f, 1f)
            rightNorm = (maxX + padX).coerceIn(0f, 1f)
            topNorm = (minY - padY).coerceIn(0f, 1f)
            bottomNorm = (maxY + padY).coerceIn(0f, 1f)
        } else {
            leftNorm = candidate.bbox.minX.coerceIn(0f, 1f)
            rightNorm = candidate.bbox.maxX.coerceIn(0f, 1f)
            topNorm = candidate.bbox.minY.coerceIn(0f, 1f)
            bottomNorm = (candidate.bbox.minY + candidate.bbox.height * 0.65f).coerceIn(0f, 1f)
        }

        val left = (leftNorm * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val right = (rightNorm * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val top = (topNorm * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val bottom = (bottomNorm * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        if (right - left < 4 || bottom - top < 4) return null

        val hist = FloatArray(12 * 6 * 4)
        val hsv = FloatArray(3)
        val sampleCols = 16
        val sampleRows = 16
        for (row in 0 until sampleRows) {
            val y = (top + ((row + 0.5f) / sampleRows) * (bottom - top))
                .toInt()
                .coerceIn(0, bitmap.height - 1)
            for (col in 0 until sampleCols) {
                val x = (left + ((col + 0.5f) / sampleCols) * (right - left))
                    .toInt()
                    .coerceIn(0, bitmap.width - 1)
                val pixel = bitmap.getPixel(x, y)
                Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
                val hBin = (hsv[0] / 180f * 12f).toInt().coerceIn(0, 11)
                val sBin = (hsv[1] * 6f).toInt().coerceIn(0, 5)
                val vBin = (hsv[2] * 4f).toInt().coerceIn(0, 3)
                hist[(hBin * 6 + sBin) * 4 + vBin] += 1f
            }
        }
        var total = 0f
        for (value in hist) total += value
        if (total <= 0f) return null
        for (i in hist.indices) hist[i] /= total
        return SubjectAppearanceSignature(hist)
    }

    private fun resolveSubjectSelection(
        candidates: List<PoseCandidate>,
        timestampMs: Long = System.currentTimeMillis() - sessionStartMs,
    ): SubjectSelection {
        val visibleCandidates = candidates
            .take(MAX_POSE_CANDIDATES)
            .filter { hasRenderablePose(it.landmarks) }

        if (visibleCandidates.isEmpty()) {
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            lostSubjectFrames += 1
            subjectMotionTracker.markHold(timestampMs)
            if (manualSubjectLock && lockedSubject != null && lostSubjectFrames < SUBJECT_LOST_FRAMES) {
                return SubjectSelection(
                    candidate = null,
                    activeIndex = null,
                    trackId = lockedSubjectTrackId,
                    status = SubjectLockStatus.LOCKED,
                    trustFlags = listOf("subject_locked", "subject_hold"),
                    reason = "subject_temporarily_unmatched",
                )
            }
            val trackId = lockedSubjectTrackId
            val lostReason = if (lockedSubject != null || manualSubjectLock) "locked_subject_lost" else "no_person_detected"
            if (lockedSubject != null || manualSubjectLock) {
                clearLostLockedSubject()
            }
            return SubjectSelection(
                candidate = null,
                activeIndex = null,
                trackId = trackId,
                status = SubjectLockStatus.SUBJECT_LOST,
                trustFlags = listOf("SUBJECT_LOST"),
                reason = lostReason,
            )
        }

        pendingSubjectTap?.let { tap ->
            pendingSubjectTap = null
            val selected = selectCandidateFromTap(visibleCandidates, tap.first, tap.second)
            if (selected != null) {
                val sourceIndex = candidates.indexOf(selected).takeIf { it >= 0 }
                if (lockedSubject == null || sourceIndex != _live.value.activeSubjectIndex || !manualSubjectLock) {
                    resetSubjectTemporalState()
                }
                manualSubjectLock = true
                val trackId = nextSubjectTrackId++
                subjectMotionTracker.reset()
                lockedSubjectTrackId = trackId
                val locked = selected.copy(trackId = trackId)
                lockedSubject = locked
                lockedSubjectAppearance = selected.appearance
                pendingAutoSubject = null
                pendingAutoSubjectFrames = 0
                lostSubjectFrames = 0
                identityHoldFrames = 0
                return ownedSubjectSelection(
                    candidate = locked,
                    candidates = candidates,
                    activeIndex = sourceIndex,
                    trackId = trackId,
                    status = SubjectLockStatus.LOCKED,
                    trustFlags = subjectTrustFlagsFor(candidates, SubjectLockStatus.LOCKED),
                    reason = "",
                    timestampMs = timestampMs,
                )
            }
        }

        if (lockedSubject != null) {
            val predictedSubject = subjectMotionTracker.predict(timestampMs)
            val match = SubjectIdentityMatcher.select(
                previous = lockedSubject!!,
                appearanceAnchor = lockedSubjectAppearance,
                candidates = visibleCandidates,
                threshold = SUBJECT_MATCH_THRESHOLD,
                recoveringFromHold = identityHoldFrames > 0,
                predictedBbox = predictedSubject?.bbox,
            )
            if (match.hold) {
                pendingAutoSubject = null
                pendingAutoSubjectFrames = 0
                identityHoldFrames += 1
                subjectMotionTracker.markHold(timestampMs)
                val status = if (manualSubjectLock) SubjectLockStatus.LOCKED else SubjectLockStatus.AUTO_LOCKED
                return SubjectSelection(
                    candidate = null,
                    activeIndex = null,
                    trackId = lockedSubjectTrackId,
                    status = status,
                    trustFlags = subjectHoldTrustFlags(candidates, status, match.reason),
                    reason = match.reason,
                )
            }
            val best = match.candidate
            if (best != null && match.index != null) {
                val sourceIndex = candidates.indexOfFirst { it.landmarks === best.landmarks }
                    .takeIf { it >= 0 }
                val status = if (manualSubjectLock) SubjectLockStatus.LOCKED else SubjectLockStatus.AUTO_LOCKED
                val trackId = lockedSubjectTrackId ?: nextSubjectTrackId++
                val trackedBest = best.copy(trackId = trackId)
                pendingAutoSubject = null
                pendingAutoSubjectFrames = 0
                lostSubjectFrames = 0
                identityHoldFrames = 0
                val selection = ownedSubjectSelection(
                    candidate = trackedBest,
                    candidates = candidates,
                    activeIndex = sourceIndex,
                    trackId = trackId,
                    status = status,
                    trustFlags = subjectTrustFlagsFor(candidates, status),
                    reason = "",
                    targetBbox = predictedSubject?.bbox ?: best.bbox,
                    timestampMs = timestampMs,
                )
                if (selection.candidate != null) {
                    lockedSubjectTrackId = trackId
                    lockedSubject = selection.candidate
                } else {
                    identityHoldFrames += 1
                }
                return selection
            }
            if (!manualSubjectLock) {
                lockedSubject = null
                lockedSubjectAppearance = null
                lockedSubjectTrackId = null
                identityHoldFrames = 0
                subjectMotionTracker.reset()
            } else {
                lostSubjectFrames += 1
                subjectMotionTracker.markHold(timestampMs)
                if (lostSubjectFrames < SUBJECT_LOST_FRAMES) {
                    return SubjectSelection(
                        candidate = null,
                        activeIndex = null,
                        trackId = lockedSubjectTrackId,
                        status = SubjectLockStatus.LOCKED,
                        trustFlags = listOf("subject_locked", "subject_hold"),
                        reason = "subject_temporarily_unmatched",
                    )
                }
                val trackId = lockedSubjectTrackId
                clearLostLockedSubject()
                return SubjectSelection(
                    candidate = null,
                    activeIndex = null,
                    trackId = trackId,
                    status = SubjectLockStatus.SUBJECT_LOST,
                    trustFlags = listOf("SUBJECT_LOST"),
                    reason = "locked_subject_lost",
                )
            }
        }

        if (visibleCandidates.size == 1) {
            val selected = visibleCandidates.first()
            val trackId = lockedSubjectTrackId ?: nextSubjectTrackId++
            subjectMotionTracker.reset()
            lockedSubjectTrackId = trackId
            val locked = selected.copy(trackId = trackId)
            lockedSubject = locked
            lockedSubjectAppearance = selected.appearance
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            lostSubjectFrames = 0
            identityHoldFrames = 0
            return ownedSubjectSelection(
                candidate = locked,
                candidates = candidates,
                activeIndex = candidates.indexOf(selected).takeIf { it >= 0 },
                trackId = trackId,
                status = SubjectLockStatus.SINGLE_AUTO,
                trustFlags = subjectTrustFlagsFor(candidates, SubjectLockStatus.SINGLE_AUTO),
                reason = "",
                timestampMs = timestampMs,
            )
        }

        selectAutoCandidate(visibleCandidates)?.let { (autoCandidate, _) ->
            val samePendingSubject = pendingAutoSubject?.let { previous ->
                val match = SubjectIdentityMatcher.select(
                    previous = previous,
                    appearanceAnchor = previous.appearance,
                    candidates = listOf(autoCandidate),
                    threshold = SUBJECT_MATCH_THRESHOLD,
                )
                !match.hold && match.candidate != null
            } ?: false
            pendingAutoSubjectFrames = if (samePendingSubject) {
                pendingAutoSubjectFrames + 1
            } else {
                1
            }
            pendingAutoSubject = autoCandidate

            if (pendingAutoSubjectFrames >= AUTO_LOCK_STABLE_FRAMES) {
                val sourceIndex = candidates.indexOfFirst { it.landmarks === autoCandidate.landmarks }
                    .takeIf { it >= 0 }
                val trackId = lockedSubjectTrackId ?: nextSubjectTrackId++
                subjectMotionTracker.reset()
                lockedSubjectTrackId = trackId
                val locked = autoCandidate.copy(trackId = trackId)
                lockedSubject = locked
                lockedSubjectAppearance = autoCandidate.appearance
                pendingAutoSubject = null
                pendingAutoSubjectFrames = 0
                lostSubjectFrames = 0
                identityHoldFrames = 0
                return ownedSubjectSelection(
                    candidate = locked,
                    candidates = candidates,
                    activeIndex = sourceIndex,
                    trackId = trackId,
                    status = SubjectLockStatus.AUTO_LOCKED,
                    trustFlags = (
                        subjectTrustFlagsFor(candidates, SubjectLockStatus.AUTO_LOCKED) +
                            "auto_selection_stable"
                        ).distinct(),
                    reason = "",
                    timestampMs = timestampMs,
                )
            }

            return SubjectSelection(
                candidate = null,
                activeIndex = null,
                trackId = null,
                status = SubjectLockStatus.NEEDS_SELECTION,
                trustFlags = listOf("MULTI_PERSON", "NEEDS_SELECTION", "AUTO_SELECTION_PENDING"),
                reason = "auto_selection_pending",
            )
        }

        pendingAutoSubject = null
        pendingAutoSubjectFrames = 0

        return SubjectSelection(
            candidate = null,
            activeIndex = null,
            trackId = null,
            status = SubjectLockStatus.NEEDS_SELECTION,
            trustFlags = listOf("MULTI_PERSON", "NEEDS_SELECTION"),
            reason = "multi_person_selection_required",
        )
    }

    private fun ownedSubjectSelection(
        candidate: PoseCandidate?,
        candidates: List<PoseCandidate>,
        activeIndex: Int?,
        trackId: Int?,
        status: SubjectLockStatus,
        trustFlags: List<String>,
        reason: String,
        targetBbox: PoseBoundingBox? = candidate?.bbox,
        timestampMs: Long,
    ): SubjectSelection {
        if (candidate == null) {
            subjectMotionTracker.markHold(timestampMs)
            return SubjectSelection(
                candidate = null,
                activeIndex = null,
                trackId = trackId,
                status = status,
                trustFlags = trustFlags,
                reason = reason,
            )
        }
        val ownership = PoseOwnershipGate.evaluate(
            candidate = candidate,
            targetBbox = targetBbox ?: candidate.bbox,
            otherCandidates = candidates.filter { it.landmarks !== candidate.landmarks },
        )
        if (!ownership.canDrawSkeleton || !ownership.canUseForJudgment) {
            subjectMotionTracker.markHold(timestampMs)
            return SubjectSelection(
                candidate = null,
                activeIndex = null,
                trackId = trackId,
                status = status,
                trustFlags = (trustFlags + ownership.trustFlags + "subject_hold").distinct(),
                reason = ownership.reason,
            )
        }
        subjectMotionTracker.update(candidate.bbox, timestampMs)
        return SubjectSelection(
            candidate = candidate,
            activeIndex = activeIndex,
            trackId = trackId,
            status = status,
            trustFlags = (trustFlags + ownership.trustFlags).distinct(),
            reason = reason.ifBlank { ownership.reason },
        )
    }

    private fun selectCandidateFromTap(
        candidates: List<PoseCandidate>,
        x: Float,
        y: Float,
    ): PoseCandidate? {
        val containing = candidates.filter { it.bbox.contains(x, y) }
        val pool = containing.ifEmpty { candidates }
        return pool.minByOrNull { candidate ->
            val dx = candidate.centerX - x
            val dy = candidate.centerY - y
            dx * dx + dy * dy
        }
    }

    private fun selectAutoCandidate(candidates: List<PoseCandidate>): Pair<PoseCandidate, Float>? {
        if (candidates.size < 2) return null
        val maxArea = candidates.maxOfOrNull { it.bbox.area }?.coerceAtLeast(0.001f) ?: return null
        val scored = candidates.map { candidate ->
            val areaScore = (candidate.bbox.area / maxArea).coerceIn(0f, 1f)
            val centerDist = distance(candidate.centerX, candidate.centerY, 0.5f, 0.55f)
            val centerScore = (1f - centerDist / 0.75f).coerceIn(0f, 1f)
            val visibilityScore = candidate.avgVisibility.coerceIn(0f, 1f)
            val score = 0.45f * areaScore + 0.35f * centerScore + 0.20f * visibilityScore
            candidate to score
        }.sortedByDescending { it.second }

        val best = scored.first()
        val secondScore = scored.getOrNull(1)?.second ?: 0f
        if (best.second >= AUTO_LOCK_MIN_SCORE && best.second - secondScore >= AUTO_LOCK_MARGIN) {
            return best
        }
        return null
    }

    private fun scoreSubjectMatch(previous: PoseCandidate, candidate: PoseCandidate): Float {
        return SubjectIdentityMatcher.scoreCandidate(previous, previous.appearance, candidate).trackScore
    }

    private fun meanKeypointDistance(
        a: List<PoseLandmarkData>,
        b: List<PoseLandmarkData>,
    ): Float {
        val keypoints = listOf(11, 12, 23, 24, 25, 26, 27, 28)
        val distances = keypoints.mapNotNull { index ->
            val la = a.getOrNull(index)
            val lb = b.getOrNull(index)
            if (la != null && lb != null && la.visibility > 0.15f && lb.visibility > 0.15f) {
                distance(la.x, la.y, lb.x, lb.y)
            } else {
                null
            }
        }
        return if (distances.isEmpty()) 1f else distances.average().toFloat()
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun subjectTrustFlagsFor(
        candidates: List<PoseCandidate>,
        status: SubjectLockStatus,
    ): List<String> {
        return buildList {
            if (candidates.size > 1) add("other_people_detected")
            when (status) {
                SubjectLockStatus.LOCKED -> add("subject_locked")
                SubjectLockStatus.AUTO_LOCKED -> add("subject_auto_locked")
                SubjectLockStatus.SINGLE_AUTO -> add("single_subject_auto")
                SubjectLockStatus.SUBJECT_LOST -> add("SUBJECT_LOST")
                SubjectLockStatus.NEEDS_SELECTION -> add("NEEDS_SELECTION")
            }
        }.distinct()
    }

    private fun subjectHoldTrustFlags(
        candidates: List<PoseCandidate>,
        status: SubjectLockStatus,
        reason: String,
    ): List<String> {
        return buildList {
            addAll(subjectTrustFlagsFor(candidates, status))
            add("subject_hold")
            add(if (reason == "subject_temporarily_occluded") reason else "subject_identity_uncertain")
        }.distinct()
    }

    private fun shouldSuppressCandidateSkeletonsForSubjectGate(
        trustFlags: List<String>,
        reason: String,
    ): Boolean {
        if (reason.startsWith("pose_")) return true
        return trustFlags.any { flag ->
            when (flag.lowercase()) {
                "pose_ownership_blocked",
                "subject_hold",
                "subject_identity_uncertain",
                "subject_temporarily_occluded",
                "subject_identity_reacquiring",
                "needs_selection",
                "auto_selection_pending" -> true
                else -> false
            }
        }
    }

    private fun publishSubjectGate(
        candidates: List<PoseCandidate>,
        status: SubjectLockStatus,
        frameIndex: Int,
        timestampMs: Long,
        reason: String,
        trustFlags: List<String>,
    ) {
        val noPersonGate = candidates.isEmpty() ||
            status == SubjectLockStatus.SUBJECT_LOST ||
            (status == SubjectLockStatus.LOCKED && candidates.none { hasRenderablePose(it.landmarks) })
        val flag = if (noPersonGate) {
            QualityFlag(
                id = "no_person_detected",
                status = "LOW_CONFIDENCE",
                value = candidates.size.toFloat(),
                threshold = 1f,
                evidence = "pose_presence_gate",
                reason = reason.ifBlank { "no_person_detected" },
            )
        } else {
            QualityFlag(
                id = "multi_person_selection",
                status = "VIEW_LIMITED",
                value = candidates.size.toFloat(),
                threshold = 1f,
                evidence = "subject_lock",
                reason = reason.ifBlank { "multi_person_selection_required" },
            )
        }
        if (shouldSampleDebugFrame(frameIndex)) {
            GemmaFitDebugApi.record(
                category = "subject",
                message = "subject_gate_blocked",
                data = mapOf(
                    "frame" to frameIndex,
                    "timestamp_ms" to timestampMs,
                    "candidate_count" to candidates.size,
                    "status" to status.name,
                    "reason" to flag.reason,
                    "trust_flags" to trustFlags,
                ),
            )
        }
        val matrix = buildTrustMatrix(listOf(flag), emptyList())
        val card = EvidenceCard(
            verdict = flag.status,
            reason = flag.reason,
            evidence = listOf(
                EvidenceItem("candidates", candidates.size.toString()),
                EvidenceItem("subject", status.name.lowercase()),
            ),
            trustFlags = (listOf("${flag.status}:${flag.id}") + trustFlags).distinct(),
        )
        val message = if (noPersonGate) {
            if (status == SubjectLockStatus.SUBJECT_LOST || reason == "locked_subject_lost") {
                "Tracking lost. Step back into frame or tap again."
            } else {
                "No person detected. Step into frame and keep your body visible."
            }
        } else {
            "Multiple people detected. Tap yourself to start."
        }

        if (!shouldSuppressPinnedReviewDisplayWrite(frameIndex, cameraEpoch = null)) {
            val suppressCandidateSkeletons = shouldSuppressCandidateSkeletonsForSubjectGate(trustFlags, flag.reason)
            _live.value = _live.value.copy(
                poseLandmarks = emptyList(),
                poseTrajectory = emptyList(),
                poseCandidates = if (noPersonGate || suppressCandidateSkeletons) {
                    emptyList()
                } else {
                    candidates.filter { hasRenderablePose(it.landmarks) }
                },
                activeSubjectIndex = null,
                activeSubjectTrackId = null,
                subjectLockStatus = status,
                subjectTrustFlags = trustFlags,
                activeWarnings = emptyList(),
                qualityFlags = listOf(flag),
                detectedExercise = "unknown",
                exerciseConfidence = 0f,
                templateMetrics = emptyMap(),
                trustMatrix = matrix,
                evidenceCard = card,
                coachMessage = message,
                coachPriority = "medium",
                movementPhase = "unknown",
                currentFrameTimestampMs = timestampMs,
            )
        }
        updateProcessedFrame(
            frameIndex = frameIndex,
            exercise = "unknown",
            confidence = 0f,
            warnings = emptyList(),
            flags = listOf(flag),
            metrics = emptyMap(),
            movementPhase = "unknown",
            trustMatrix = matrix,
            evidenceCard = card,
            coachMsg = message,
            coachPriority = "medium",
            subjectTrustFlags = trustFlags,
            subjectLockStatus = status,
        )
    }

    private fun List<PoseLandmarkData>.toFloat99(): FloatArray {
        val floatArray = FloatArray(99)
        for (i in 0 until minOf(size, 33)) {
            floatArray[i * 3] = this[i].x
            floatArray[i * 3 + 1] = this[i].y
            floatArray[i * 3 + 2] = this[i].visibility
        }
        return floatArray
    }

    private fun FloatArray.toPoseLandmarkData(): List<PoseLandmarkData> {
        val landmarks = mutableListOf<PoseLandmarkData>()
        for (i in 0 until minOf(size / 3, 33)) {
            landmarks.add(
                PoseLandmarkData(
                    x = this[i * 3],
                    y = this[i * 3 + 1],
                    z = 0f,
                    visibility = this[i * 3 + 2],
                )
            )
        }
        return landmarks
    }

    private fun poseConfidenceFloor(landmarks: List<PoseLandmarkData>): Float {
        val tracked = listOf(11, 12, 23, 24, 25, 26, 27, 28).mapNotNull { landmarks.getOrNull(it) }
        if (tracked.isEmpty()) return 0f

        val torsoConfidence = visibleAverageConfidence(landmarks, listOf(11, 12, 23, 24), minCount = 2)
        val leftLowerChain = visibleAverageConfidence(landmarks, listOf(23, 25, 27), minCount = 2)
        val rightLowerChain = visibleAverageConfidence(landmarks, listOf(24, 26, 28), minCount = 2)
        val bestLowerChain = maxOf(leftLowerChain, rightLowerChain)
        if (torsoConfidence > 0f && bestLowerChain > 0f) {
            return minOf(torsoConfidence, bestLowerChain).coerceIn(0f, 1f)
        }

        return tracked.minOf { it.visibility }.coerceIn(0f, 1f)
    }

    private fun visibleAverageConfidence(
        landmarks: List<PoseLandmarkData>,
        indices: List<Int>,
        minCount: Int,
        minVisibility: Float = 0.25f,
    ): Float {
        val visible = indices
            .mapNotNull { index -> landmarks.getOrNull(index)?.visibility }
            .filter { it.isFinite() && it >= minVisibility }
        if (visible.size < minCount) return 0f
        return visible.average().toFloat().coerceIn(0f, 1f)
    }

    private data class NativeTemporalInput(
        val prevLandmarks: FloatArray?,
        val dtMs: Long?,
        val temporalAllowed: Boolean,
        val reason: String,
    )

    private fun nativeTemporalInputFor(
        currentLandmarks: FloatArray,
        timestampMs: Long,
        subjectTrackId: Int?,
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus?,
    ): NativeTemporalInput {
        val hardBlocked = subjectTrustFlags.any { blocksHardJudgmentForSubject(it) } ||
            subjectLockStatus?.let { blocksHardJudgmentForSubjectStatus(it) } == true
        if (subjectTrackId == null) {
            resetNativeTemporalLandmarks()
            return NativeTemporalInput(null, null, false, "missing_subject_track")
        }
        if (hardBlocked) {
            resetNativeTemporalLandmarks()
            return NativeTemporalInput(null, null, false, "subject_tracking_limited")
        }

        val previous = previousNativeLandmarks
        val previousTimestamp = previousNativeTimestampMs
        val previousTrack = previousNativeTrackId
        val dtMs = if (previousTimestamp != null) timestampMs - previousTimestamp else null
        val validPrevious = previous != null &&
            previousTrack == subjectTrackId &&
            dtMs != null &&
            dtMs in 1L..MAX_NATIVE_TEMPORAL_DT_MS

        return if (validPrevious) {
            NativeTemporalInput(previous, dtMs, true, "same_track_contiguous")
        } else {
            NativeTemporalInput(null, dtMs, true, "no_contiguous_previous_frame")
        }.also {
            previousNativeLandmarks = currentLandmarks.copyOf()
            previousNativeTimestampMs = timestampMs
            previousNativeTrackId = subjectTrackId
        }
    }

    private fun resetNativeTemporalLandmarks() {
        previousNativeLandmarks = null
        previousNativeTimestampMs = null
        previousNativeTrackId = null
    }

    private fun processLandmarks(
        floatArray: FloatArray,
        frameIndex: Int = 0,
        timestampMs: Long = System.currentTimeMillis() - sessionStartMs,
        subjectTrustFlags: List<String> = emptyList(),
        subjectLockStatus: SubjectLockStatus? = null,
        subjectTrackId: Int? = null,
        cameraEpoch: Long? = null,
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!isCurrentCameraPoseWrite(cameraEpoch)) return@launch
            if (!kinematicsMutex.tryLock()) return@launch
            try {
            try {
                val nativeTemporalInput = synchronized(sessionDataLock) {
                    nativeTemporalInputFor(
                        currentLandmarks = floatArray,
                        timestampMs = timestampMs,
                        subjectTrackId = subjectTrackId,
                        subjectTrustFlags = subjectTrustFlags,
                        subjectLockStatus = subjectLockStatus,
                    )
                }
                if (shouldSampleDebugFrame(frameIndex)) {
                    GemmaFitDebugApi.record(
                        category = "native",
                        message = "temporal_input",
                        data = mapOf(
                            "frame" to frameIndex,
                            "timestamp_ms" to timestampMs,
                            "track_id" to subjectTrackId,
                            "has_prev_landmarks" to (nativeTemporalInput.prevLandmarks != null),
                            "dt_ms" to nativeTemporalInput.dtMs,
                            "temporal_allowed" to nativeTemporalInput.temporalAllowed,
                            "reason" to nativeTemporalInput.reason,
                        ),
                    )
                }
                val jsonOutput = KinematicsBridge.processFrame(floatArray, nativeTemporalInput.prevLandmarks, 0.6f)
                val result = KinematicsBridge.parseResult(jsonOutput)
                if (!isCurrentCameraPoseWrite(cameraEpoch)) return@launch

                if (result.gateBlocked) {
                    val suppressPinnedReviewDisplayWrite =
                        shouldSuppressPinnedReviewDisplayWrite(frameIndex, cameraEpoch)
                    Log.d(TAG, "Frame $frameIndex: confidence gate blocked - ${result.gateReason}")
                    synchronized(sessionDataLock) {
                        lowConfidenceCount += 1
                    }
                    if (shouldSampleDebugFrame(frameIndex)) {
                        GemmaFitDebugApi.record(
                            category = "native",
                            message = "confidence_gate_blocked",
                            data = mapOf(
                                "frame" to frameIndex,
                                "timestamp_ms" to timestampMs,
                                "reason" to result.gateReason,
                                "subject_trust_flags" to subjectTrustFlags,
                            ),
                        )
                    }
                    val lowConfidenceFlag = QualityFlag(
                        id = "pose_confidence",
                        status = "LOW_CONFIDENCE",
                        value = 0f,
                        threshold = 0.6f,
                        evidence = "confidence_gate",
                        reason = result.gateReason,
                    )
                    val trustMatrix = buildTrustMatrix(
                        qualityFlags = listOf(lowConfidenceFlag),
                        notApplicableFlags = emptyList(),
                    )
                    val evidenceCard = EvidenceCard(
                        verdict = "LOW_CONFIDENCE",
                        reason = result.gateReason,
                        evidence = listOf(EvidenceItem("gate", "blocked")),
                        trustFlags = (listOf("LOW_CONFIDENCE") + subjectTrustFlags).distinct(),
                    )
                    val lowConfidenceMessage =
                        "Tracking is limited, so I am not making a form judgment on this frame."
                    updateProcessedFrame(
                        frameIndex = frameIndex,
                        exercise = "unknown",
                        confidence = 0f,
                        warnings = emptyList(),
                        flags = listOf(lowConfidenceFlag),
                        metrics = emptyMap(),
                        movementPhase = "unknown",
                        trustMatrix = trustMatrix,
                        evidenceCard = evidenceCard,
                        coachMsg = lowConfidenceMessage,
                        coachPriority = "medium",
                        subjectTrustFlags = subjectTrustFlags,
                    )
                    if (!suppressPinnedReviewDisplayWrite) {
                        if (!isCurrentCameraPoseWrite(cameraEpoch)) return@launch
                        _live.value = _live.value.copy(
                            poseLandmarks = emptyList(),
                            poseTrajectory = emptyList(),
                            poseCandidates = emptyList(),
                            activeSubjectIndex = null,
                            activeSubjectTrackId = null,
                            activeWarnings = emptyList(),
                            formScore = 0,
                            qualityFlags = listOf(lowConfidenceFlag),
                            trustMatrix = trustMatrix,
                            evidenceCard = evidenceCard,
                            subjectTrustFlags = subjectTrustFlags,
                            coachMessage = lowConfidenceMessage,
                            coachPriority = "medium",
                            currentFrameTimestampMs = timestampMs,
                        )
                    }
                    publishDebugAnalysisState(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        exercise = "unknown",
                        score = 0,
                        movementPhase = "unknown",
                        warnings = _live.value.activeWarnings,
                        qualityFlags = listOf(lowConfidenceFlag),
                        notApplicableFlags = emptyList(),
                        subjectTrustFlags = subjectTrustFlags,
                        coachInsight = lastCoachInsight,
                    )
                } else {
                    val violations = KinematicsBridge.extractViolations(result.safetyJson)
                    val legacyWarnings = violations.map { v ->
                        SafetyWarning(
                            rule = v.rule,
                            functionName = functionNameForRule(v.rule),
                            message = v.description,
                            severity = if (v.severity >= 0.9f) "high" else "medium",
                            joint = v.joint,
                        )
                    }

                    var pattern = "unknown"
                    var muscle: MuscleFocusResult? = null
                    var exercise = "unknown"
                    var exConfidence = 0f
                    var exBasis = emptyList<String>()
                    var exCandidateScores = emptyMap<String, Float>()
                    var templateMetrics = emptyMap<String, Float>()
                    var qualityFlags = emptyList<QualityFlag>()
                    var notApplicableFlags = emptyList<QualityFlag>()
                    var capabilityContract = CapabilityContract()
                    var evidenceDag = EvidenceDag()
                    var coachMsg = ""
                    var coachPriority = "low"

                    try {
                        val patternObj = JSONObject(result.patternJson)
                        pattern = patternObj.optString("pattern_label", "unknown")
                    } catch (_: Exception) {}

                    try {
                        val combined = JSONObject(result.combinedJson)
                        val muscleObj = combined.optJSONObject("muscle")

                        if (muscleObj != null) {
                            val primaryArr = muscleObj.optJSONArray("estimated_primary")
                            val primary = mutableListOf<String>()
                            for (i in 0 until (primaryArr?.length() ?: 0)) {
                                primary.add(primaryArr!!.getString(i))
                            }
                            val secondaryArr = muscleObj.optJSONArray("estimated_secondary")
                            val secondary = mutableListOf<String>()
                            for (i in 0 until (secondaryArr?.length() ?: 0)) {
                                secondary.add(secondaryArr!!.getString(i))
                            }
                            muscle = MuscleFocusResult(
                                primary = primary,
                                secondary = secondary,
                                pattern = pattern,
                                confidence = muscleObj.optString("confidence", "medium"),
                            )
                        }

                        val motionObj = combined.optJSONObject("motion_report")
                        if (motionObj != null) {
                            val parsedMotion = parseMotionReport(motionObj)
                            exercise = parsedMotion.exercise
                            exConfidence = parsedMotion.confidence
                            exBasis = parsedMotion.basis
                            exCandidateScores = parsedMotion.candidateScores
                            templateMetrics = parsedMotion.metrics
                            qualityFlags = parsedMotion.qualityFlags
                            notApplicableFlags = parsedMotion.notApplicable
                            capabilityContract = parsedMotion.capabilityContract
                            evidenceDag = parsedMotion.evidenceDag
                        }
                    } catch (_: Exception) {}

                    if (exercise == "unknown" && result.motionReportJson.isNotBlank()) {
                        try {
                            val parsedMotion = parseMotionReport(JSONObject(result.motionReportJson))
                            exercise = parsedMotion.exercise
                            exConfidence = parsedMotion.confidence
                            exBasis = parsedMotion.basis
                            exCandidateScores = parsedMotion.candidateScores
                            templateMetrics = parsedMotion.metrics
                            qualityFlags = parsedMotion.qualityFlags
                            notApplicableFlags = parsedMotion.notApplicable
                            capabilityContract = parsedMotion.capabilityContract
                            evidenceDag = parsedMotion.evidenceDag
                        } catch (_: Exception) {}
                    }

                    val layer2ExerciseCandidate = resolveLayer2ExerciseCandidate(
                        exercise = exercise,
                        pattern = pattern,
                        basis = exBasis,
                        candidateScores = exCandidateScores,
                        metrics = templateMetrics,
                    )
                    if (layer2ExerciseCandidate != exercise) {
                        exercise = layer2ExerciseCandidate
                        exConfidence = maxOf(
                            exConfidence,
                            exCandidateScores["squat"] ?: 0f,
                            0.62f,
                        ).coerceIn(0f, 1f)
                        exBasis = (
                            exBasis +
                                listOf("layer2_candidate.$layer2ExerciseCandidate")
                            ).distinct()
                        exCandidateScores = exCandidateScores + (layer2ExerciseCandidate to exConfidence)
                    }

                    val poseLandmarkData = floatArray.toPoseLandmarkData()
                    val currentPoseConfidenceFloor = poseConfidenceFloor(poseLandmarkData)
                    val temporal = temporalAnalyzer.addSample(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        exercise = exercise,
                        metrics = templateMetrics,
                        confidenceFloor = currentPoseConfidenceFloor,
                    )
                    val movementPhase = temporal.movementPhase
                    val temporalFlags = listOfNotNull(temporal.rapidFlag)
                    templateMetrics = mergeTemporalMetrics(templateMetrics, temporal.temporalMetrics)
                    qualityFlags = mergeQualityFlags(qualityFlags, temporalFlags)
                    temporal.motionFeatureWindow?.let { window ->
                        synchronized(sessionDataLock) {
                            sessionEvidenceRefs.addAll(window.evidenceRefs)
                        }
                    }

                    val trace = motionTraceAnalyzer.addSample(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        exercise = exercise,
                        landmarks = poseLandmarkData,
                        temporal = temporal,
                        qualityFlags = qualityFlags + notApplicableFlags,
                    )
                    trace.repTraceSummary?.let { summary ->
                        templateMetrics = mergeTraceMetrics(templateMetrics, summary)
                    }
                    qualityFlags = mergeQualityFlags(qualityFlags, listOfNotNull(trace.traceFlag))

                    val liveSubjectLockStatus = if (cameraEpoch != null) {
                        _live.value.subjectLockStatus
                    } else {
                        null
                    }
                    val effectiveSubjectLockStatus = when {
                        subjectLockStatus?.let { blocksHardJudgmentForSubjectStatus(it) } == true -> subjectLockStatus
                        liveSubjectLockStatus?.let { blocksHardJudgmentForSubjectStatus(it) } == true -> liveSubjectLockStatus
                        else -> subjectLockStatus
                    }
                    val analysisSubjectTrustFlags = (
                        subjectTrustFlags +
                            listOfNotNull(
                                subjectTrustFlagForStatus(subjectLockStatus),
                                subjectTrustFlagForStatus(liveSubjectLockStatus),
                            )
                        ).distinct()
                    val hardJudgmentBlocked = shouldBlockHardJudgment(
                        qualityFlags = qualityFlags,
                        subjectTrustFlags = analysisSubjectTrustFlags,
                        subjectLockStatus = effectiveSubjectLockStatus,
                    )
                    val hideSelectedSkeleton = shouldHideSelectedSkeleton(
                        subjectTrustFlags = analysisSubjectTrustFlags,
                        subjectLockStatus = effectiveSubjectLockStatus,
                    )
                    val analysisQualityFlags = if (hardJudgmentBlocked) {
                        limitedQualityFlags(
                            qualityFlags = qualityFlags,
                            subjectTrustFlags = analysisSubjectTrustFlags,
                            subjectLockStatus = effectiveSubjectLockStatus,
                        )
                    } else {
                        qualityFlags
                    }
                    val analysisNotApplicableFlags = if (hardJudgmentBlocked) {
                        emptyList()
                    } else {
                        notApplicableFlags
                    }
                    val analysisExercise = if (hardJudgmentBlocked) "unknown" else exercise
                    val analysisExerciseConfidence = if (hardJudgmentBlocked) 0f else exConfidence
                    val analysisExerciseBasis = if (hardJudgmentBlocked) emptyList() else exBasis
                    val analysisCandidateScores = if (hardJudgmentBlocked) emptyMap() else exCandidateScores
                    val analysisTemplateMetrics = if (hardJudgmentBlocked) emptyMap() else templateMetrics
                    val analysisCapabilityContract = capabilityContractForAnalysis(
                        base = capabilityContract,
                        hardJudgmentBlocked = hardJudgmentBlocked,
                        qualityFlags = analysisQualityFlags,
                        subjectTrustFlags = analysisSubjectTrustFlags,
                        subjectLockStatus = effectiveSubjectLockStatus,
                    )
                    val analysisEvidenceDag = evidenceDag
                    val layer2Output = layer2Interpreter.update(
                        Layer2FrameFeatures(
                            timestampMs = timestampMs,
                            activityHint = layer2ActivityHint(analysisExercise, pattern),
                            activityConfidence = analysisExerciseConfidence,
                            poseConfidence = currentPoseConfidenceFloor,
                            personTrackingState = personTrackingStateFor(
                                subjectLockStatus = effectiveSubjectLockStatus,
                                subjectTrustFlags = analysisSubjectTrustFlags,
                            ),
                            judgmentAllowed = !hardJudgmentBlocked,
                            metrics = analysisTemplateMetrics,
                        )
                    )
                    val layer2QualityFlags = applyLayer2RulePolicy(analysisQualityFlags, layer2Output.rulePolicy)
                    val layer2BlocksHardJudgment = !layer2Output.rulePolicy.allowHardJudgment
                    if (
                        layer2Output.event != Layer2Event.NONE ||
                        shouldSampleDebugFrame(frameIndex) ||
                        layer2Output.abstainReason != null
                    ) {
                        val payload = mapOf(
                            "frame" to frameIndex,
                            "timestamp_ms" to timestampMs,
                            "layer2_output" to layer2Output.toDebugMap(),
                        )
                        GemmaFitDebugApi.updateState("layer2_event", payload)
                        GemmaFitDebugApi.record("layer2_event", "frame_interpreted", payload)
                    }
                    val activeQualityFlags = if (hardJudgmentBlocked || layer2BlocksHardJudgment) {
                        emptyList()
                    } else {
                        layer2QualityFlags.filter {
                            it.status == "CRITICAL" || it.status == "WARNING"
                        }
                    }
                    val warnings = if (hardJudgmentBlocked) {
                        emptyList()
                    } else if (activeQualityFlags.isNotEmpty()) {
                        activeQualityFlags.map { flag ->
                            SafetyWarning(
                                rule = flag.rule,
                                functionName = functionNameForQualityFlag(flag),
                                message = flag.reason.ifBlank { flag.id.replace("_", " ") },
                                severity = if (flag.status == "CRITICAL") "high" else "medium",
                                joint = flag.joint,
                            )
                        }
                    } else {
                        legacyWarnings
                    }

                    val limitedJudgment = hardJudgmentBlocked || layer2BlocksHardJudgment || layer2QualityFlags.any {
                        it.status == "VIEW_LIMITED" || it.status == "LOW_CONFIDENCE"
                    }
                    val repModelInvocationRequest = temporal.motionFeatureWindow?.let { window ->
                        ModelInvocationRequest(
                            trigger = ModelInvocationTrigger.REP_COMPLETED,
                            personTrackingState = personTrackingStateFor(
                                subjectLockStatus = effectiveSubjectLockStatus,
                                subjectTrustFlags = analysisSubjectTrustFlags,
                            ),
                            confidenceFloor = minOf(window.features.confidenceFloor, currentPoseConfidenceFloor),
                            capabilityJudgmentAllowed = !hardJudgmentBlocked && !layer2BlocksHardJudgment,
                            hasCriticalOrWarningEvidence = activeQualityFlags.isNotEmpty(),
                            needsLanguageExplanation = activeQualityFlags.isNotEmpty(),
                        )
                    }
                    val repModelInvocationPlan = repModelInvocationRequest?.let(ModelInvocationScheduler::plan)
                    if (temporal.motionFeatureWindow != null && repModelInvocationRequest != null && repModelInvocationPlan != null) {
                        val activityContext = activityContextTracker.update(
                            ActivityContextObservation.from(
                                motionFeatureWindow = temporal.motionFeatureWindow,
                                layer2Output = layer2Output,
                            )
                        )
                        val motionZipPacket = MotionZipPacketBuilder.fromRepEvent(
                            motionFeatureWindow = temporal.motionFeatureWindow,
                            layer2Output = layer2Output,
                            activityContext = activityContext,
                        )
                        val sessionMotionZipPacket = recordMotionZipPacket(motionZipPacket)
                        val payload = mapOf(
                            "event" to "rep_completed",
                            "frame" to frameIndex,
                            "timestamp_ms" to timestampMs,
                            "request" to repModelInvocationRequest.toDebugMap(),
                            "plan" to repModelInvocationPlan.toDebugMap(),
                            "motion_feature_window" to temporal.motionFeatureWindow.toDebugMap(),
                            "motion_zip_packet" to motionZipPacket.toDebugMap(),
                            "motion_zip_session_packet" to sessionMotionZipPacket?.toDebugMap(),
                            "bounded_e2b_prompt" to MotionZipPacketBuilder.toBoundedE2BPrompt(motionZipPacket),
                            "layer2_output" to layer2Output.toDebugMap(),
                            "activity_context" to activityContext.toDebugMap(),
                        )
                        GemmaFitDebugApi.updateState(
                            section = "model_invocation",
                            data = payload,
                        )
                        GemmaFitDebugApi.record(
                            category = "model_invocation",
                            message = "rep_event_plan",
                            data = payload,
                        )
                    }
                    val score = formScoreFromQuality(
                        layer2QualityFlags,
                        if (hardJudgmentBlocked) emptyList() else violations,
                    )
                    val trustMatrix = buildTrustMatrix(layer2QualityFlags, analysisNotApplicableFlags)
                    val currentVisualContext = synchronized(sessionDataLock) { sessionVisualContext }
                    val preRepActivityContext = maybeUpdatePreRepChairActivityContext(
                        timestampMs = timestampMs,
                        visualContext = currentVisualContext,
                        layer2Output = layer2Output,
                        movementPhase = movementPhase,
                        poseConfidence = currentPoseConfidenceFloor,
                        hasRepEventWindow = temporal.motionFeatureWindow != null,
                    )
                    val currentActivityContext = preRepActivityContext ?: activityContextTracker.peek()
                    val frameEvidenceRefs = analysisEvidenceDag.nodes.map { it.id }
                        .ifEmpty { analysisCapabilityContract.evidenceRefs }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val evidenceCard = buildEvidenceCard(
                        exercise = analysisExercise,
                        exerciseConfidence = analysisExerciseConfidence,
                        metrics = analysisTemplateMetrics,
                        qualityFlags = layer2QualityFlags,
                        notApplicableFlags = analysisNotApplicableFlags,
                        capabilityContract = analysisCapabilityContract,
                        evidenceRefs = frameEvidenceRefs,
                    ).withSubjectTrustFlags(analysisSubjectTrustFlags)
                    val hideOverlayForPoseQuality = OverlayPoseQualityGate.shouldHideSkeleton(
                        qualityFlags = layer2QualityFlags + analysisNotApplicableFlags,
                        evidenceCard = evidenceCard,
                    )
                    val hideLiveOverlaySkeleton = hideSelectedSkeleton || hideOverlayForPoseQuality
                    val liveSubjectTrustFlags = when {
                        hideOverlayForPoseQuality -> {
                            (analysisSubjectTrustFlags +
                                "pose_hidden_by_quality" +
                                "pose_preview_keypoint_limited" +
                                "judgment_blocked_keypoint_visibility").distinct()
                        }
                        hideSelectedSkeleton -> {
                            (analysisSubjectTrustFlags +
                                "pose_preview_subject_limited" +
                                "judgment_blocked_subject").distinct()
                        }
                        else -> analysisSubjectTrustFlags
                    }
                    cleanFrameStreak = if (warnings.isEmpty() && !limitedJudgment) {
                        cleanFrameStreak + 1
                    } else {
                        0
                    }
                    val repHistorySnapshot = synchronized(sessionDataLock) {
                        sessionCapabilityContract = mergeCapabilityContracts(
                            sessionCapabilityContract,
                            analysisCapabilityContract,
                        )
                        sessionEvidenceRefs.addAll(frameEvidenceRefs.take(48))
                        temporal.completedRep?.takeUnless { limitedJudgment }?.let { rep ->
                            if (repRecords.none { it.repNumber == rep.repNumber }) {
                                repRecords.add(
                                    rep.copy(
                                        formQuality = score / 100f,
                                        hadViolations = warnings.isNotEmpty(),
                                        traceSummary = trace.repTraceSummary,
                                        warningNames = warnings.map { it.functionName }.filter { it.isNotBlank() }.distinct(),
                                    )
                                )
                            }
                        }
                        repRecords.toList()
                    }
                    val coachContext = CoachContext(
                        exercise = analysisExercise,
                        movementPhase = movementPhase,
                        pattern = pattern,
                        repCount = temporal.repCount,
                        cleanStreak = cleanFrameStreak,
                        metrics = analysisTemplateMetrics,
                        muscle = muscle,
                        warnings = warnings,
                        qualityFlags = layer2QualityFlags,
                        notApplicableFlags = analysisNotApplicableFlags,
                        evidenceCard = evidenceCard,
                    )

                    val deterministicCoachInsight = CoachInsightRenderer.render(coachContext)
                    val liveCuePlan = if (cameraEpoch != null) {
                        liveCuePlanner.plan(
                            context = coachContext,
                            deterministic = deterministicCoachInsight,
                            timestampMs = timestampMs,
                        )
                    } else {
                        LiveCuePlan(
                            insight = deterministicCoachInsight,
                            outcome = "video_deterministic",
                            variantId = "deterministic",
                        )
                    }
                    val coachInsight = liveCuePlan.insight
                    publishLiveCueRewritePlan(
                        cuePlan = liveCuePlan,
                        context = coachContext,
                        cameraEpoch = cameraEpoch,
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        personTrackingState = personTrackingStateFor(
                            subjectLockStatus = effectiveSubjectLockStatus,
                            subjectTrustFlags = analysisSubjectTrustFlags,
                        ),
                        confidenceFloor = currentPoseConfidenceFloor,
                        capabilityJudgmentAllowed = !hardJudgmentBlocked && !layer2BlocksHardJudgment,
                        hasCriticalOrWarningEvidence = activeQualityFlags.isNotEmpty(),
                    )
                    val msg = coachInsight.message
                    val priority = coachInsight.priority
                    coachMsg = msg
                    coachPriority = priority
                    _lastCoachMessage = msg
                    _lastCoachPriority = priority
                    lastCoachInsight = coachInsight

                    val suppressPinnedReviewDisplayWrite =
                        shouldSuppressPinnedReviewDisplayWrite(frameIndex, cameraEpoch)
                    if (!suppressPinnedReviewDisplayWrite) {
                        if (!isCurrentCameraPoseWrite(cameraEpoch)) return@launch
                        val liveSnapshot = _live.value
                        val overlayFrameIndex = if (cameraEpoch == null) {
                            processedIndexForFrameIndex(frameIndex)
                        } else {
                            null
                        }
                        val canUpdateOverlayForFrame =
                            cameraEpoch != null || overlayFrameIndex == liveSnapshot.currentFrameIndex
                        val overlayFrame = if (canUpdateOverlayForFrame && overlayFrameIndex != null) {
                            frameAtOrNull(overlayFrameIndex)
                        } else {
                            null
                        }
                        val nextPoseLandmarks = when {
                            !canUpdateOverlayForFrame -> liveSnapshot.poseLandmarks
                            hideLiveOverlaySkeleton -> emptyList()
                            overlayFrame != null -> overlayFrame.landmarks
                            else -> liveSnapshot.poseLandmarks
                        }
                        val nextPoseTrajectory = when {
                            !canUpdateOverlayForFrame -> liveSnapshot.poseTrajectory
                            hideLiveOverlaySkeleton -> emptyList()
                            overlayFrame != null && overlayFrame.landmarks.isNotEmpty() ->
                                trajectoryFor(overlayFrameIndex ?: liveSnapshot.currentFrameIndex)
                            else -> liveSnapshot.poseTrajectory
                        }
                        val nextPoseCandidates = when {
                            !canUpdateOverlayForFrame -> liveSnapshot.poseCandidates
                            hideLiveOverlaySkeleton -> emptyList()
                            overlayFrame != null -> overlayFrame.poseCandidates
                            else -> liveSnapshot.poseCandidates
                        }
                        val nextActiveSubjectIndex = when {
                            !canUpdateOverlayForFrame -> liveSnapshot.activeSubjectIndex
                            hideLiveOverlaySkeleton -> null
                            overlayFrame != null -> overlayFrame.activeSubjectIndex
                            else -> liveSnapshot.activeSubjectIndex
                        }
                        val nextActiveSubjectTrackId = when {
                            !canUpdateOverlayForFrame -> liveSnapshot.activeSubjectTrackId
                            hideLiveOverlaySkeleton -> null
                            overlayFrame != null -> overlayFrame.activeSubjectTrackId
                            else -> liveSnapshot.activeSubjectTrackId
                        }
                        // Update live state
                        _live.value = liveSnapshot.copy(
                            poseLandmarks = nextPoseLandmarks,
                            poseTrajectory = nextPoseTrajectory,
                            poseCandidates = nextPoseCandidates,
                            activeSubjectIndex = nextActiveSubjectIndex,
                            activeSubjectTrackId = nextActiveSubjectTrackId,
                            subjectLockStatus = effectiveSubjectLockStatus ?: liveSnapshot.subjectLockStatus,
                            repCount = temporal.repCount,
                            formScore = score,
                            activeWarnings = warnings,
                            currentPattern = pattern,
                            movementPhase = movementPhase,
                            currentMuscleFocus = muscle,
                            detectedExercise = analysisExercise,
                            exerciseConfidence = analysisExerciseConfidence,
                            exerciseBasis = analysisExerciseBasis,
                            templateMetrics = analysisTemplateMetrics,
                            coachMessage = coachMsg,
                            coachPriority = coachPriority,
                            coachInsight = coachInsight,
                            qualityFlags = layer2QualityFlags + analysisNotApplicableFlags,
                            trustMatrix = trustMatrix,
                            evidenceCard = evidenceCard,
                            capabilityContract = analysisCapabilityContract,
                            activityContext = currentActivityContext,
                            visualContext = currentVisualContext,
                            subjectTrustFlags = liveSubjectTrustFlags,
                            repHistory = repHistorySnapshot,
                        )
                    }
                    recordSessionQualityCounts(layer2QualityFlags, notApplicableFlags)
                    if (
                        shouldSampleDebugFrame(frameIndex) ||
                        warnings.isNotEmpty() ||
                        layer2QualityFlags.any { it.status == "VIEW_LIMITED" || it.status == "LOW_CONFIDENCE" }
                    ) {
                        publishDebugAnalysisState(
                            frameIndex = frameIndex,
                            timestampMs = timestampMs,
                            exercise = analysisExercise,
                            exerciseBasis = analysisExerciseBasis,
                            candidateScores = analysisCandidateScores,
                            score = score,
                            movementPhase = movementPhase,
                            warnings = warnings,
                            qualityFlags = layer2QualityFlags,
                            notApplicableFlags = analysisNotApplicableFlags,
                            subjectTrustFlags = analysisSubjectTrustFlags,
                            coachInsight = coachInsight,
                            capabilityContract = analysisCapabilityContract,
                            evidenceDag = analysisEvidenceDag,
                            traceSummary = trace.repTraceSummary,
                        )
                    }

                    // Update the stored frame with kinematics results
                    updateProcessedFrame(
                        frameIndex = frameIndex,
                        exercise = analysisExercise,
                        confidence = analysisExerciseConfidence,
                        warnings = warnings,
                        flags = layer2QualityFlags + analysisNotApplicableFlags,
                        metrics = analysisTemplateMetrics,
                        movementPhase = movementPhase,
                        trustMatrix = trustMatrix,
                        evidenceCard = evidenceCard,
                        coachMsg = coachMsg,
                        coachPriority = coachPriority,
                        capabilityContract = analysisCapabilityContract,
                        subjectTrustFlags = liveSubjectTrustFlags,
                        subjectLockStatus = effectiveSubjectLockStatus,
                    )

                    val shouldPublishSummary = synchronized(sessionDataLock) {
                        // Track session data
                        totalFramesAnalyzed++
                        formScoreHistory.add(FormScorePoint(
                            frameIndex = frameIndex,
                            score = score,
                            timestampSeconds = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt(),
                        ))

                        warnings.forEach { warning ->
                            safetyEventLog.add(SafetyEvent(
                                rule = warning.rule,
                                functionName = warning.functionName,
                                description = warning.message,
                                severity = warning.severity,
                                joint = warning.joint,
                                frameIndex = frameIndex,
                                timestampSeconds = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt(),
                            ))
                        }

                        if (!limitedJudgment && analysisExercise.isKnownExercise()) {
                            exerciseDetectionCounts[analysisExercise] =
                                (exerciseDetectionCounts[analysisExercise] ?: 0) + 1
                        }
                        muscle?.primary?.forEach { m ->
                            muscleFocusCounts[m] = (muscleFocusCounts[m] ?: 0) + 1
                        }

                        if (coachMsg.isNotEmpty() && coachMsg != lastCoachMessage) {
                            coachTipsSet.add(coachMsg)
                            lastCoachMessage = coachMsg
                            coachInsights.add(coachInsight)
                            while (coachInsights.size > 12) {
                                coachInsights.removeAt(0)
                            }
                        }
                        frameIndex % 30 == 0
                    }

                    // TTS
                    if (!limitedJudgment && warnings.isNotEmpty()) {
                        val mostSevere = warnings.maxByOrNull { if (it.severity == "high") 2 else 1 }
                        mostSevere?.let { warning ->
                            coachVoice?.speakFunctionCall(warning.functionName)
                        }
                    } else if (!limitedJudgment && frameIndex % 90 == 0) {
                        coachVoice?.speakFunctionCall("positive_reinforcement")
                    }

                    // Update session summary periodically
                    if (shouldPublishSummary) {
                        _sessionSummary.value = buildSessionSummary()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kinematics processing failed on frame $frameIndex: ${e.message}", e)
                GemmaFitDebugApi.record(
                    category = "native",
                    message = "kinematics_processing_error",
                    data = mapOf(
                        "frame" to frameIndex,
                        "timestamp_ms" to timestampMs,
                        "error" to (e.message ?: "unknown"),
                    ),
                )
                val failureFlag = QualityFlag(
                    id = "analysis_exception",
                    status = "LOW_CONFIDENCE",
                    value = 0f,
                    threshold = 1f,
                    evidence = "kinematics_exception",
                    reason = e.message ?: "native processing failed",
                )
                if (!shouldSuppressPinnedReviewDisplayWrite(frameIndex, cameraEpoch)) {
                    if (!isCurrentCameraPoseWrite(cameraEpoch)) return@launch
                    _live.value = _live.value.copy(
                        activeWarnings = emptyList(),
                        qualityFlags = _live.value.qualityFlags + failureFlag,
                    )
                }
            }
            } finally {
                kinematicsMutex.unlock()
            }
        }
    }

    // ── Session summary ─────────────────────────────────────────────────

    private fun buildSessionSummary(): SessionSummary {
        val reviewCues = buildReviewCues()
        return synchronized(sessionDataLock) {
            val duration = if (sessionStartMs > 0) {
                ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()
            } else 0

            val avgScore = if (formScoreHistory.isNotEmpty()) {
                formScoreHistory.map { it.score }.average().toFloat()
            } else 100f

            val motionZipActivityContext = ActivityContext.aggregate(motionZipPackets.map { it.activityContext })
            val sessionActivityContext = if (motionZipActivityContext.state != ActivityContextState.UNKNOWN) {
                motionZipActivityContext
            } else {
                activityContextTracker.peek()
            }
            val activityResolution = resolveUserFacingActivity(
                sessionActivityContext = sessionActivityContext,
                repCount = _live.value.repCount,
                motionZipPacketCount = motionZipPackets.size,
                countedExercises = exerciseDetectionCounts,
                liveExercise = _live.value.detectedExercise,
                liveExerciseConfidence = _live.value.exerciseConfidence,
            )
            val visibleDetectedExercises = if (activityResolution.hasJudgeableActivity) {
                exerciseDetectionCounts.toMap()
            } else {
                emptyMap()
            }

            SessionSummary(
                totalFrames = totalFramesAnalyzed,
                totalReps = _live.value.repCount,
                avgFormScore = avgScore,
                durationSeconds = duration,
                detection = ExerciseDetection(
                    mainExercise = activityResolution.exercise,
                    confidence = activityResolution.confidence,
                    detectedExercises = visibleDetectedExercises,
                ),
                safetyEvents = safetyEventLog.toList(),
                formScores = formScoreHistory.toList(),
                viewLimitedCount = viewLimitedCount,
                lowConfidenceCount = lowConfidenceCount,
                notApplicableCounts = notApplicableCounts.toMap(),
                muscleFocusDistribution = muscleFocusCounts.toMap(),
                repHistory = repRecords.toList(),
                personalTraceEnvelope = motionTraceAnalyzer.envelopeFor(activityResolution.exercise),
                coachTips = coachTipsSet.toList(),
                aiInsights = coachInsights.toList(),
                sessionCoachInsight = sessionCoachInsight,
                capabilityContract = sessionCapabilityContract,
                evidenceRefs = sessionEvidenceRefs.toList(),
                activityContext = sessionActivityContext,
                visualContext = sessionVisualContext,
                reviewCues = reviewCues,
            )
        }
    }

    private fun buildReviewCues(): List<ReviewCue> {
        val frames = synchronized(processedFramesLock) {
            processedFrames.toList()
        }
        if (frames.isEmpty()) return emptyList()
        val rawCues = frames.mapIndexedNotNull { processedIndex, frame ->
            reviewCueForFrame(processedIndex, frame)
        }
        if (rawCues.isEmpty()) return emptyList()

        val deduped = mutableListOf<ReviewCue>()
        rawCues.sortedBy { it.timestampMs }.forEach { cue ->
            val duplicateIndex = deduped.indexOfLast {
                it.kind == cue.kind &&
                    cue.timestampMs - it.timestampMs <= REVIEW_CUE_COOLDOWN_MS
            }
            if (duplicateIndex >= 0) {
                val existing = deduped[duplicateIndex]
                if (reviewCueSeverityRank(cue.severity) > reviewCueSeverityRank(existing.severity)) {
                    deduped[duplicateIndex] = cue
                }
            } else {
                deduped.add(cue)
            }
        }
        return deduped
            .sortedWith(
                compareByDescending<ReviewCue> { reviewCueSeverityRank(it.severity) }
                    .thenBy { it.timestampMs },
            )
            .take(REVIEW_CUE_MAX_ITEMS)
            .sortedBy { it.timestampMs }
    }

    private fun reviewCueForFrame(processedIndex: Int, frame: ProcessedFrame): ReviewCue? {
        val warning = frame.warnings.sortedWith(
            compareBy<SafetyWarning> { reviewCueSeverityRank(warningCueSeverity(it)) }
                .thenBy { it.functionName },
        ).lastOrNull()
        if (warning != null) {
            val kind = warningCueKind(warning)
            return ReviewCue(
                frameIndex = processedIndex,
                timestampMs = frame.timestampMs,
                severity = warningCueSeverity(warning),
                kind = kind,
                title = warningCueTitle(warning),
                suggestion = warningCueSuggestion(warning),
                evidenceRef = "frame.${frame.frameIndex}.warning.$kind",
            )
        }

        val flag = frame.qualityFlags
            .filter { it.status in REVIEW_CUE_QUALITY_STATUSES }
            .sortedWith(compareBy<QualityFlag> { statusPriority(it.status) }.thenBy { it.id })
            .firstOrNull()
            ?: return null
        return ReviewCue(
            frameIndex = processedIndex,
            timestampMs = frame.timestampMs,
            severity = qualityCueSeverity(flag),
            kind = qualityCueKind(flag),
            title = qualityCueTitle(flag),
            suggestion = qualityCueSuggestion(flag),
            evidenceRef = flag.evidenceId.ifBlank { "frame.${frame.frameIndex}.quality.${flag.id}" },
        )
    }

    private fun warningCueSeverity(warning: SafetyWarning): String {
        return when (warning.severity.lowercase()) {
            "critical", "high" -> "critical"
            else -> "warning"
        }
    }

    private fun qualityCueSeverity(flag: QualityFlag): String {
        return when (flag.status) {
            "CRITICAL" -> "critical"
            "WARNING" -> "warning"
            else -> "watch"
        }
    }

    private fun warningCueKind(warning: SafetyWarning): String {
        val fn = warning.functionName.lowercase()
        val joint = warning.joint.lowercase()
        return when {
            "knee" in fn || "knee" in joint -> "knee_alignment"
            "spinal" in fn || "neck" in fn || "spine" in joint || "neck" in joint -> "posture"
            "asymmetry" in fn -> "asymmetry"
            "com" in fn || "center" in warning.message.lowercase() -> "center_of_mass"
            "rapid" in fn -> "tempo"
            "range" in fn || "rom" in fn -> "range_of_motion"
            else -> warning.functionName.ifBlank { "movement" }
        }
    }

    private fun warningCueTitle(warning: SafetyWarning): String {
        return when (warningCueKind(warning)) {
            "knee_alignment" -> "Knee alignment cue"
            "posture" -> "Posture cue"
            "asymmetry" -> "Balance cue"
            "center_of_mass" -> "Stability cue"
            "tempo" -> "Tempo cue"
            "range_of_motion" -> "Range cue"
            else -> "Movement cue"
        }
    }

    private fun warningCueSuggestion(warning: SafetyWarning): String {
        return when (warningCueKind(warning)) {
            "knee_alignment" -> "Keep the knee tracking over the middle toes and slow the next rep."
            "posture" -> "Keep the torso tall and the neck neutral before repeating."
            "asymmetry" -> "Move slower and keep both sides level through the phase."
            "center_of_mass" -> "Keep weight centered over the support area before continuing."
            "tempo" -> "Reduce speed and pause briefly at the end of the phase."
            "range_of_motion" -> "Use a controlled, comfortable range instead of forcing depth."
            else -> warning.message.ifBlank { "Reset posture and repeat one slow controlled rep." }
        }
    }

    private fun qualityCueKind(flag: QualityFlag): String {
        return when (flag.status) {
            "VIEW_LIMITED" -> "view_limited"
            "LOW_CONFIDENCE" -> "low_confidence"
            "NOT_APPLICABLE" -> "not_applicable"
            "MONITOR" -> "monitor_only"
            else -> flag.id.ifBlank { "quality" }
        }
    }

    private fun qualityCueTitle(flag: QualityFlag): String {
        return when (flag.status) {
            "VIEW_LIMITED" -> "Visibility limited"
            "LOW_CONFIDENCE" -> "Tracking confidence low"
            "NOT_APPLICABLE" -> "Rule skipped"
            "MONITOR" -> "Monitor cue"
            "WARNING" -> "Movement warning"
            "CRITICAL" -> "Critical movement cue"
            else -> "Review cue"
        }
    }

    private fun qualityCueSuggestion(flag: QualityFlag): String {
        return when (flag.status) {
            "VIEW_LIMITED" -> "Keep the full body and support object inside the camera frame."
            "LOW_CONFIDENCE" -> "Use a clearer angle and repeat the movement slowly."
            "NOT_APPLICABLE" -> "This rule was skipped here; review a fuller movement phase."
            "MONITOR" -> flag.reason.ifBlank { flag.evidence.ifBlank { "Use this frame as an observation cue." } }
            "WARNING", "CRITICAL" -> flag.reason.ifBlank { flag.evidence.ifBlank { "Reset posture before repeating." } }
            else -> flag.reason.ifBlank { "Review this frame before the next attempt." }
        }
    }

    private fun reviewCueSeverityRank(severity: String): Int = when (severity.lowercase()) {
        "critical" -> 3
        "warning" -> 2
        else -> 1
    }

    private fun sessionStatusSnapshotFrom(summary: SessionSummary): SessionStatusSnapshot {
        val noJudgeableActivity = summary.totalReps == 0 &&
            summary.detection.mainExercise == "unknown" &&
            summary.activityContext.state == ActivityContextState.UNKNOWN
        val activityPhase = when (summary.activityContext.state) {
            ActivityContextState.AMBIGUOUS -> "activity ambiguous"
            ActivityContextState.CALIBRATING -> if (summary.activityContext.taskLabel.isNullOrBlank()) {
                "monitor only"
            } else {
                "calibrating"
            }
            ActivityContextState.LOCKED -> summary.activityContext.taskLabel
                ?.replace("_", " ")
                ?.ifBlank { null }
                ?: "monitor only"
            else -> if (noJudgeableActivity) "monitor only" else "complete"
        }
        return SessionStatusSnapshot(
            ready = summary.totalFrames > 0,
            exercise = summary.detection.mainExercise,
            formScore = summary.avgFormScore.toInt().coerceIn(0, 100),
            repCount = summary.totalReps,
            phase = activityPhase,
            activityContext = summary.activityContext,
            visualContext = summary.visualContext,
        )
    }

    private data class UserFacingActivityResolution(
        val exercise: String,
        val confidence: Float,
        val source: String,
        val hasJudgeableActivity: Boolean,
    )

    private fun resolveUserFacingActivity(
        sessionActivityContext: ActivityContext,
        repCount: Int,
        motionZipPacketCount: Int,
        countedExercises: Map<String, Int>,
        liveExercise: String,
        liveExerciseConfidence: Float,
    ): UserFacingActivityResolution {
        val contextExercise = sessionActivityContext.taskLabel
            ?.takeIf { it.isKnownExercise() && sessionActivityContext.state.isUserFacingActivityState() }
        if (contextExercise != null) {
            return UserFacingActivityResolution(
                exercise = contextExercise,
                confidence = sessionActivityContext.confidence.coerceIn(0f, 1f),
                source = "activity_context",
                hasJudgeableActivity = true,
            )
        }

        val hasEventEvidence = repCount > 0 || motionZipPacketCount > 0
        if (!hasEventEvidence) {
            return UserFacingActivityResolution(
                exercise = "unknown",
                confidence = 0f,
                source = "layer2_unconfirmed",
                hasJudgeableActivity = false,
            )
        }

        val countedMainExercise = countedExercises.maxByOrNull { it.value }?.key
        val totalCount = countedExercises.values.sum().coerceAtLeast(1)
        val countedConfidence = countedMainExercise
            ?.let { (countedExercises[it] ?: 0).toFloat() / totalCount }
            ?: 0f
        return when {
            liveExercise.isKnownExercise() -> UserFacingActivityResolution(
                exercise = liveExercise,
                confidence = liveExerciseConfidence.coerceIn(0f, 1f),
                source = "event_live_template",
                hasJudgeableActivity = true,
            )
            countedMainExercise?.isKnownExercise() == true -> UserFacingActivityResolution(
                exercise = countedMainExercise,
                confidence = countedConfidence.coerceIn(0f, 1f),
                source = "event_counted_template",
                hasJudgeableActivity = true,
            )
            else -> UserFacingActivityResolution(
                exercise = "unknown",
                confidence = 0f,
                source = "event_unclassified",
                hasJudgeableActivity = false,
            )
        }
    }

    private fun ActivityContextState.isUserFacingActivityState(): Boolean {
        return this == ActivityContextState.CALIBRATING ||
            this == ActivityContextState.LOCKED ||
            this == ActivityContextState.SUSPECT_SWITCH
    }

    private fun seedVideoSourceVisualContext(displayName: String?) {
        val visualContext = sourceNameVisualContext(displayName) ?: return
        val applied = synchronized(sessionDataLock) {
            sessionVisualContext = mergeSessionVisualContext(sessionVisualContext, visualContext)
            sessionVisualContext
        }
        val activityContext = maybeUpdatePreRepChairActivityContext(
            timestampMs = 0L,
            visualContext = applied,
            layer2Output = null,
            movementPhase = "setup",
            poseConfidence = 0.68f,
            hasRepEventWindow = false,
        )
        _live.value = _live.value.copy(
            visualContext = applied,
            activityContext = activityContext ?: activityContextTracker.peek(),
        )
        GemmaFitDebugApi.updateState(
            section = "early_video_visual_context",
            data = mapOf(
                "status" to "source_hint_applied",
                "display_name" to displayName.orEmpty(),
                "visual_context" to applied.toJson().toString(),
                "activity_context" to (activityContext ?: activityContextTracker.peek()).toDebugMap(),
            ),
        )
    }

    private fun sourceNameVisualContext(displayName: String?): SessionVisualContext? {
        val normalized = displayName
            ?.lowercase()
            ?.replace('-', '_')
            ?.replace(' ', '_')
            ?: return null
        val tokens = normalized
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
        val chairHint = listOf(
            "chair",
            "sit_to_stand",
            "sit2stand",
            "supported_chair",
        ).any { token -> normalized.contains(token) } || tokens.contains("sts")
        if (!chairHint) return null
        return SessionVisualContext(
            support = SessionVisualContext.SUPPORT_CHAIR,
            person = SessionVisualContext.PERSON_UNKNOWN,
            source = "video_source_hint",
            evidenceRefs = listOf(
                "video_source.display_name",
                SessionVisualContext.REF_SUPPORT,
            ),
        )
    }

    private fun maybeScheduleEarlyVideoVisualContextProbe(
        pass: VideoAnalysisPass,
        frameIndex: Int,
        timestampMs: Long,
        bitmap: Bitmap,
        poseData: List<PoseLandmarkData>,
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus,
    ) {
        if (!ENABLE_SESSION_VISUAL_SIDECAR) return
        if (pass != VideoAnalysisPass.FULL) return
        if (frameIndex > EARLY_VIDEO_VISUAL_MAX_FRAME_INDEX) return
        if (poseData.isEmpty()) return
        if (
            subjectLockStatus == SubjectLockStatus.SUBJECT_LOST ||
            subjectTrustFlags.any { it.contains("multi_person", ignoreCase = true) }
        ) {
            return
        }
        val poseConfidence = poseConfidenceFloor(poseData)
        if (poseConfidence < 0.55f) return
        val runId = analysisRunId
        if (earlyVideoVisualContextStartedForRun == runId) return
        if (synchronized(sessionDataLock) { sessionVisualContext.support == SessionVisualContext.SUPPORT_CHAIR }) {
            return
        }
        val frameBitmap = scaledBitmapCopy(bitmap, EARLY_VIDEO_VISUAL_LONG_SIDE) ?: return
        earlyVideoVisualContextStartedForRun = runId
        activeEarlyVideoVisualContextJob?.cancel()
        activeEarlyVideoVisualContextJob = viewModelScope.launch(Dispatchers.IO) {
            var imageFile: File? = null
            try {
                val savedImageFile = saveDebugBitmap(
                    bitmap = frameBitmap,
                    fileName = "early_video_${runId}_${frameIndex}_visual_context.jpg",
                    format = Bitmap.CompressFormat.JPEG,
                    quality = 82,
                )
                if (savedImageFile == null) {
                    GemmaFitDebugApi.updateState(
                        section = "early_video_visual_context",
                        data = mapOf(
                            "status" to "image_unavailable",
                            "run_id" to runId,
                            "frame" to frameIndex,
                        ),
                    )
                    return@launch
                }
                imageFile = savedImageFile
                GemmaFitDebugApi.updateState(
                    section = "early_video_visual_context",
                    data = mapOf(
                        "status" to "queued",
                        "run_id" to runId,
                        "frame" to frameIndex,
                        "timestamp_ms" to timestampMs,
                        "pose_confidence" to poseConfidence,
                        "image_file" to savedImageFile.absolutePath,
                    ),
                )
                val root = runVisualContextInference(
                    imageFile = savedImageFile,
                    timeoutMs = EARLY_VIDEO_VISUAL_CONTEXT_TIMEOUT_MS,
                )
                if (runId != analysisRunId) {
                    GemmaFitDebugApi.updateState(
                        section = "early_video_visual_context",
                        data = mapOf(
                            "status" to "stale_result_dropped",
                            "run_id" to runId,
                            "current_run_id" to analysisRunId,
                            "frame" to frameIndex,
                        ),
                    )
                    return@launch
                }
                val raw = root.optString("raw_response")
                val visualContext = if (root.optBoolean("success", false) && raw.isNotBlank()) {
                    SessionVisualContextParser.parse(raw)
                } else {
                    SessionVisualContext.unknown(SessionVisualContext.SOURCE_LITERT_VISION)
                }
                val applied = synchronized(sessionDataLock) {
                    sessionVisualContext = mergeSessionVisualContext(sessionVisualContext, visualContext)
                    sessionVisualContext
                }
                val activityContext = maybeUpdatePreRepChairActivityContext(
                    timestampMs = timestampMs,
                    visualContext = applied,
                    layer2Output = null,
                    movementPhase = _live.value.movementPhase,
                    poseConfidence = poseConfidence,
                    hasRepEventWindow = false,
                )
                val updatedSummary = buildSessionSummary()
                _live.value = _live.value.copy(
                    visualContext = applied,
                    activityContext = activityContext ?: activityContextTracker.peek(),
                    sessionStatus = if (_live.value.sessionStatus.ready) {
                        sessionStatusSnapshotFrom(updatedSummary)
                    } else {
                        _live.value.sessionStatus
                    },
                )
                _sessionSummary.value = updatedSummary
                GemmaFitDebugApi.updateState(
                    section = "early_video_visual_context",
                    data = mapOf(
                        "status" to if (visualContext.available) "complete" else "no_visual_context",
                        "run_id" to runId,
                        "frame" to frameIndex,
                        "timestamp_ms" to timestampMs,
                        "success" to root.optBoolean("success", false),
                        "backend" to root.optString("backend"),
                        "elapsed_ms" to root.optLong("elapsed_ms"),
                        "generate_content_ms" to root.optLong("generate_content_ms"),
                        "visual_context" to applied.toJson().toString(),
                        "activity_context" to (activityContext ?: activityContextTracker.peek()).toDebugMap(),
                        "raw_response" to raw.take(1_000),
                        "error" to root.optString("error"),
                    ),
                )
            } catch (error: Throwable) {
                GemmaFitDebugApi.updateState(
                    section = "early_video_visual_context",
                    data = mapOf(
                        "status" to "failed",
                        "run_id" to runId,
                        "frame" to frameIndex,
                        "error" to (error.message ?: "unknown"),
                    ),
                )
            } finally {
                frameBitmap.recycleIfAlive()
                activeEarlyVideoVisualContextJob = null
            }
        }
    }

    private fun mergeSessionVisualContext(
        current: SessionVisualContext,
        update: SessionVisualContext,
    ): SessionVisualContext {
        if (!current.available) return update
        if (!update.available) return current
        return SessionVisualContext(
            env = if (current.env != SessionVisualContext.ENV_UNKNOWN) current.env else update.env,
            support = if (current.support != SessionVisualContext.SUPPORT_UNKNOWN) current.support else update.support,
            person = if (current.person != SessionVisualContext.PERSON_UNKNOWN) current.person else update.person,
            overlayReadable = current.overlayReadable ?: update.overlayReadable,
            limited = current.limited ?: update.limited,
            source = listOf(current.source, update.source)
                .filter { it.isNotBlank() && it != SessionVisualContext.SOURCE_NONE }
                .distinct()
                .joinToString("+")
                .ifBlank { update.source },
            evidenceRefs = (current.evidenceRefs + update.evidenceRefs).filter { it.isNotBlank() }.distinct(),
            rawResponse = update.rawResponse.ifBlank { current.rawResponse },
        )
    }

    private fun maybeUpdatePreRepChairActivityContext(
        timestampMs: Long,
        visualContext: SessionVisualContext,
        layer2Output: Layer2Output?,
        movementPhase: String,
        poseConfidence: Float,
        hasRepEventWindow: Boolean,
    ): ActivityContext? {
        if (hasRepEventWindow) return null
        if (_live.value.repCount > 0) return null
        if (visualContext.support != SessionVisualContext.SUPPORT_CHAIR) return null
        if (
            visualContext.person == SessionVisualContext.PERSON_NOT_VISIBLE ||
            visualContext.person == SessionVisualContext.PERSON_MULTIPLE
        ) {
            return null
        }
        if (activityContextTracker.peek().state == ActivityContextState.LOCKED) return null

        val phaseSequence = buildList {
            movementPhase
                .takeIf { it.isNotBlank() && it != "unknown" && it != "complete" }
                ?.let { add(it) }
            layer2Output?.phase?.wireName
                ?.takeIf { it.isNotBlank() && it != "unknown" }
                ?.let { add(it) }
            add("standing_stabilized")
        }.distinct()
        val layer2Label = layer2Output?.activityHypothesis?.label
            ?.takeIf { it == "chair_sit_to_stand" || it == "setup_transition" }
            ?: "setup_transition"
        val context = activityContextTracker.observePreRepCandidate(
            ActivityContextObservation(
                timestampMs = timestampMs,
                layer2Label = layer2Label,
                confidence = poseConfidence.coerceIn(0f, 1f),
                supportPattern = "chair",
                phaseSequence = phaseSequence,
                evidenceRefs = (
                    visualContext.evidenceRefs +
                        listOf(SessionVisualContext.REF_SUPPORT, "activity_context.pre_rep_candidate") +
                        (layer2Output?.evidenceRefs ?: emptyList())
                    )
                    .filter { it.isNotBlank() }
                    .distinct(),
            )
        ) ?: return null

        if (shouldSampleDebugFrame(_live.value.currentFrameIndex) || context.taskLabel == "chair_sit_to_stand") {
            GemmaFitDebugApi.updateState(
                section = "activity_context",
                data = mapOf(
                    "source" to "pre_rep_chair_setup",
                    "visual_context" to visualContext.toJson().toString(),
                    "activity_context" to context.toDebugMap(),
                ),
            )
        }
        return context
    }

    private fun recordMotionZipPacket(packet: MotionZipPacket): MotionZipPacket? {
        val sessionPacket = synchronized(sessionDataLock) {
            motionZipPackets.add(packet)
            while (motionZipPackets.size > 24) {
                motionZipPackets.removeAt(0)
            }
            MotionZipPacketBuilder.fromSessionPackets(
                windowId = "video.session.motionzip",
                packets = motionZipPackets.toList(),
            )
        }
        publishMotionZipPacket(sessionPacket, latestPacket = packet, reason = "rep_event")
        return sessionPacket
    }

    private fun publishSessionMotionZipPacket(reason: String): MotionZipPacket? {
        val packet = synchronized(sessionDataLock) {
            MotionZipPacketBuilder.fromSessionPackets(
                windowId = "video.session.motionzip",
                packets = motionZipPackets.toList(),
            )
        }
        publishMotionZipPacket(packet, latestPacket = null, reason = reason)
        return packet
    }

    private fun publishMotionZipPacket(
        packet: MotionZipPacket?,
        latestPacket: MotionZipPacket?,
        reason: String,
    ) {
        val status = MotionZipPacketBuilder.statusForPacket(packet, source = reason)
        _live.value = _live.value.copy(motionZipStatus = status)
        val payload = buildMap<String, Any?> {
            put("reason", reason)
            put("status", status.toDebugMap())
            packet?.let { put("packet", it.toDebugMap()) }
            packet?.let { put("bounded_e2b_prompt", MotionZipPacketBuilder.toBoundedE2BPrompt(it)) }
            latestPacket?.let { put("latest_packet", it.toDebugMap()) }
        }
        GemmaFitDebugApi.updateState("motion_zip_packet", payload)
        GemmaFitDebugApi.record(
            category = "motion_zip_packet",
            message = "updated",
            data = status.toDebugMap(),
        )
    }

    private data class SessionVisualSidecarAssets(
        val selectedFrames: SelectedEvidenceFrames,
        val imageFile: File,
        val panelConfidence: String,
        val mode: String,
    )

    private fun scheduleAsyncLiveVisionSidecar(
        trigger: ModelInvocationTrigger,
        baseEventKey: String,
        context: CoachContext,
        cameraEpoch: Long,
        frameIndex: Int,
        timestampMs: Long,
        personTrackingState: PersonTrackingState,
        confidenceFloor: Float,
        hasCriticalOrWarningEvidence: Boolean,
    ) {
        if (!ENABLE_ASYNC_LIVE_VISUAL_SIDECAR) return
        if (trigger != ModelInvocationTrigger.WARNING_PERSISTED) return
        val app = getApplication<Application>()
        val officialModelPath = CoachModelResolver.resolveLiteRtModelPath(app, "official")
        val request = ModelInvocationRequest(
            trigger = trigger,
            personTrackingState = personTrackingState,
            confidenceFloor = confidenceFloor,
            capabilityJudgmentAllowed = true,
            hasCriticalOrWarningEvidence = hasCriticalOrWarningEvidence,
            needsLanguageExplanation = true,
            multimodalEvidencePanelEnabled = true,
            multimodalBackendAvailable = officialModelPath != null,
        )
        val schedulerPlan = ModelInvocationScheduler.plan(request)
        val visualPlan = schedulerPlan.multimodalEvidencePlan
        val eventKey = "live_vision|$baseEventKey"
        val nowMs = System.currentTimeMillis()
        val gateDecision = asyncVisionSidecarGate.tryStart(
            eventKey = eventKey,
            trigger = trigger,
            plan = visualPlan,
            nowMs = nowMs,
        )
        val basePayload = mapOf(
            "event_key" to eventKey,
            "frame" to frameIndex,
            "timestamp_ms" to timestampMs,
            "request" to request.toDebugMap(),
            "scheduler_plan" to schedulerPlan.toDebugMap(),
            "visual_plan" to visualPlan.toDebugMap(),
            "gate" to gateDecision.toDebugMap(),
            "deterministic_verdict" to context.evidenceCard.verdict,
            "deterministic_reason" to context.evidenceCard.reason,
            "warning_ids" to context.warnings.map { it.functionName }.filter { it.isNotBlank() },
        )
        if (!gateDecision.accepted) {
            GemmaFitDebugApi.updateState(
                "live_visual_context",
                basePayload + mapOf("status" to "skipped", "reason" to gateDecision.reason),
            )
            return
        }

        activeLiveVisionSidecarJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                GemmaFitDebugApi.updateState(
                    "live_visual_context",
                    basePayload + mapOf("status" to "queued"),
                )
                val assets = withContext(Dispatchers.Default) {
                    buildLiveVisualSidecarAssets(
                        trigger = trigger,
                        context = context,
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                    )
                }
                if (assets == null) {
                    GemmaFitDebugApi.updateState(
                        "live_visual_context",
                        basePayload + mapOf(
                            "status" to "panel_unavailable",
                            "reason" to "no_live_key_frame_snapshot",
                        ),
                    )
                    return@launch
                }
                GemmaFitDebugApi.updateState(
                    "live_visual_context",
                    basePayload + mapOf(
                        "status" to if (gateDecision.callBackend) "panel_ready" else "panel_only",
                        "panel_confidence" to assets.panelConfidence,
                        "mode" to assets.mode,
                        "image_file" to assets.imageFile.absolutePath,
                        "selected_frames" to assets.selectedFrames.toDebugMap(),
                    ),
                )
                if (!gateDecision.callBackend) return@launch

                val root = runVisualContextInference(
                    imageFile = assets.imageFile,
                    timeoutMs = LIVE_VISUAL_SIDECAR_TIMEOUT_MS,
                )
                val raw = root.optString("raw_response")
                val visualContext = if (root.optBoolean("success", false) && raw.isNotBlank()) {
                    SessionVisualContextParser.parse(raw)
                } else {
                    SessionVisualContext.unknown(SessionVisualContext.SOURCE_LITERT_VISION)
                }
                val stale = System.currentTimeMillis() > gateDecision.expiresAtMs ||
                    !isCurrentCameraPoseWrite(cameraEpoch)
                val status = when {
                    stale -> "stale_result_dropped"
                    visualContext.available -> "complete"
                    else -> "no_visual_context"
                }
                if (!stale && visualContext.available) {
                    _live.value = _live.value.copy(visualContext = visualContext)
                }
                val resultPayload = basePayload + mapOf(
                    "status" to status,
                    "success" to root.optBoolean("success", false),
                    "backend" to root.optString("backend"),
                    "elapsed_ms" to root.optLong("elapsed_ms"),
                    "generate_content_ms" to root.optLong("generate_content_ms"),
                    "panel_confidence" to assets.panelConfidence,
                    "mode" to assets.mode,
                    "visual_context" to visualContext.toJson().toString(),
                    "raw_response" to raw.take(1_000),
                    "error" to root.optString("error"),
                )
                GemmaFitDebugApi.updateState("live_visual_context", resultPayload)
                GemmaFitDebugApi.record(
                    category = "live_visual_context",
                    message = "async_vision_sidecar_result",
                    data = resultPayload,
                )
            } catch (error: Throwable) {
                GemmaFitDebugApi.updateState(
                    "live_visual_context",
                    basePayload + mapOf(
                        "status" to "backend_failed",
                        "error" to (error.message ?: "unknown"),
                    ),
                )
                GemmaFitDebugApi.record(
                    category = "live_visual_context",
                    message = "async_vision_sidecar_failed",
                    data = basePayload + mapOf("error" to (error.message ?: "unknown")),
                )
            } finally {
                asyncVisionSidecarGate.complete(eventKey)
                activeLiveVisionSidecarJob = null
            }
        }
    }

    private fun buildLiveVisualSidecarAssets(
        trigger: ModelInvocationTrigger,
        context: CoachContext,
        frameIndex: Int,
        timestampMs: Long,
    ): SessionVisualSidecarAssets? {
        val snapshot = copyLatestLiveVisionSnapshot() ?: return null
        return try {
            val evidenceRefs = (
                context.evidenceCard.evidenceRefs +
                    context.qualityFlags.map { flag -> flag.evidenceId.ifBlank { flag.id } } +
                    context.warnings.map { "warning.${it.functionName}" } +
                    snapshot.evidenceRefs
                ).filter { it.isNotBlank() }.distinct().take(8)
            val candidate = FrameEvidenceCandidate(
                frameIndex = snapshot.frameIndex,
                timestampMs = snapshot.timestampMs,
                phase = frameEvidencePhase(snapshot.phase),
                poseConfidence = snapshot.poseConfidence,
                fullBodyVisibility = snapshot.fullBodyVisibility,
                subjectObserved = snapshot.subjectObserved,
                subjectStable = snapshot.subjectStable,
                hipY = null,
                hipVelocityY = null,
                hasWarning = context.warnings.isNotEmpty(),
                warningIds = context.warnings.map { it.functionName }.filter { it.isNotBlank() }.distinct(),
                evidenceRefs = evidenceRefs,
            )
            val confidence = if (
                snapshot.poseConfidence >= 0.6f &&
                snapshot.fullBodyVisibility >= 0.5f &&
                context.evidenceCard.verdict !in setOf("LOW_CONFIDENCE", "VIEW_LIMITED")
            ) {
                FrameEvidenceSelector.CONFIDENCE_HIGH
            } else {
                FrameEvidenceSelector.CONFIDENCE_LOW
            }
            val selected = SelectedEvidenceFrames(
                sceneAnchor = candidate,
                top = null,
                descent = null,
                bottom = null,
                ascent = null,
                warningFrame = candidate.takeIf { context.warnings.isNotEmpty() },
                panelConfidence = confidence,
                selectionBasis = listOf(
                    "async_live_vision_sidecar",
                    trigger.name.lowercase(),
                    "single_key_frame",
                ),
            )
            val imageFile = saveDebugBitmap(
                bitmap = snapshot.bitmap,
                fileName = "live_${analysisRunId}_${trigger.name.lowercase()}_${frameIndex}_visual_context.jpg",
                format = Bitmap.CompressFormat.JPEG,
                quality = 82,
            ) ?: return null
            SessionVisualSidecarAssets(
                selectedFrames = selected,
                imageFile = imageFile,
                panelConfidence = confidence,
                mode = LIVE_VISUAL_MODE_KEY_FRAME,
            )
        } finally {
            snapshot.bitmap.recycleIfAlive()
        }
    }

    private suspend fun runVisualContextInference(
        imageFile: File,
        timeoutMs: Long,
    ): JSONObject {
        val app = getApplication<Application>()
        val uri = Uri.Builder()
            .scheme("content")
            .authority("com.gemmafit.debug")
            .appendPath("litert_visual_context_infer")
            .appendQueryParameter("image", imageFile.name)
            .appendQueryParameter("model", "official")
            .appendQueryParameter("timeout_ms", timeoutMs.toString())
            .build()
        val payloadText = withContext(Dispatchers.IO) {
            withTimeout(timeoutMs + 1_000L) {
                app.contentResolver.openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            }
        }.orEmpty()
        return JSONObject(payloadText)
    }

    private suspend fun runSessionVisualSidecar(
        eventKey: String,
        sessionMotionZipPacket: MotionZipPacket?,
    ): SessionVisualContext {
        val unavailableContext = SessionVisualContext.unknown(SessionVisualContext.SOURCE_LITERT_VISION)
        if (!ENABLE_SESSION_VISUAL_SIDECAR) return unavailableContext
        val app = getApplication<Application>()
        val officialModelPath = CoachModelResolver.resolveLiteRtModelPath(app, "official")
        val request = ModelInvocationRequest(
            trigger = ModelInvocationTrigger.SESSION_ENDED,
            personTrackingState = PersonTrackingState.OBSERVED,
            confidenceFloor = 1f,
            capabilityJudgmentAllowed = true,
            hasCriticalOrWarningEvidence = safetyEventLog.isNotEmpty(),
            needsLanguageExplanation = true,
            multimodalEvidencePanelEnabled = true,
            multimodalBackendAvailable = officialModelPath != null,
        )
        val plan = ModelInvocationScheduler.plan(request).multimodalEvidencePlan
        val basePayload = mapOf(
            "event_key" to eventKey,
            "request" to request.toDebugMap(),
            "plan" to plan.toDebugMap(),
            "motion_zip_available" to (sessionMotionZipPacket != null),
            "visual_frame_candidates_available" to hasSessionVisualFrameCandidates(),
        )
        val budgetDecision = sessionVisualSidecarBudgetDecision(sessionMotionZipPacket)
        if (!budgetDecision.first) {
            GemmaFitDebugApi.updateState(
                "session_visual_context",
                basePayload + mapOf(
                    "status" to "skipped_budget_gate",
                    "budget_reason" to budgetDecision.second,
                ),
            )
            return unavailableContext
        }
        if (!plan.buildPanel) {
            GemmaFitDebugApi.updateState("session_visual_context", basePayload + ("status" to "skipped"))
            return unavailableContext
        }

        val assets = withContext(Dispatchers.Default) {
            buildSessionVisualSidecarAssets(sessionMotionZipPacket)
        }
        if (assets == null) {
            GemmaFitDebugApi.updateState(
                "session_visual_context",
                basePayload + mapOf(
                    "status" to "panel_unavailable",
                    "reason" to "no_selected_visual_frames",
                ),
            )
            return unavailableContext
        }
        GemmaFitDebugApi.updateState(
            "session_visual_context",
            basePayload + mapOf(
                "status" to if (plan.callBackend) "panel_ready" else "panel_only",
                "panel_confidence" to assets.panelConfidence,
                "mode" to assets.mode,
                "image_file" to assets.imageFile.absolutePath,
                "budget_reason" to budgetDecision.second,
                "selected_frames" to assets.selectedFrames.toDebugMap(),
            ),
        )
        if (!plan.callBackend) return unavailableContext

        val uri = Uri.Builder()
            .scheme("content")
            .authority("com.gemmafit.debug")
            .appendPath("litert_visual_context_infer")
            .appendQueryParameter("image", assets.imageFile.name)
            .appendQueryParameter("model", "official")
            .appendQueryParameter("timeout_ms", "8000")
            .build()
        val payloadText = try {
            withContext(Dispatchers.IO) {
                withTimeout(SESSION_VISUAL_SIDECAR_TIMEOUT_MS) {
                    app.contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }
            }
        } catch (error: Throwable) {
            GemmaFitDebugApi.record(
                category = "session_visual_context",
                message = "vision_sidecar_failed",
                data = basePayload + mapOf("error" to (error.message ?: "unknown")),
            )
            GemmaFitDebugApi.updateState(
                "session_visual_context",
                basePayload + mapOf(
                    "status" to "backend_failed",
                    "error" to (error.message ?: "unknown"),
                    "panel_confidence" to assets.panelConfidence,
                    "mode" to assets.mode,
                ),
            )
            return unavailableContext
        }
        val root = runCatching { JSONObject(payloadText ?: "") }.getOrElse { error ->
            GemmaFitDebugApi.updateState(
                "session_visual_context",
                basePayload + mapOf(
                    "status" to "invalid_payload",
                    "error" to (error.message ?: "unknown"),
                    "raw_payload" to payloadText.orEmpty().take(1_000),
                ),
            )
            return unavailableContext
        }
        val raw = root.optString("raw_response")
        val visualContext = if (root.optBoolean("success", false) && raw.isNotBlank()) {
            SessionVisualContextParser.parse(raw)
        } else {
            SessionVisualContext.unknown(SessionVisualContext.SOURCE_LITERT_VISION)
        }
        if (visualContext.available) {
            synchronized(sessionDataLock) {
                sessionVisualContext = visualContext
            }
            maybeUpdatePreRepChairActivityContext(
                timestampMs = _live.value.currentFrameTimestampMs,
                visualContext = visualContext,
                layer2Output = null,
                movementPhase = _live.value.movementPhase,
                poseConfidence = 0.68f,
                hasRepEventWindow = false,
            )
            val updatedSummary = buildSessionSummary()
            _live.value = _live.value.copy(
                activityContext = activityContextTracker.peek(),
                visualContext = visualContext,
                sessionStatus = sessionStatusSnapshotFrom(updatedSummary),
            )
            _sessionSummary.value = updatedSummary
        }
        val resultPayload = basePayload + mapOf(
            "status" to if (visualContext.available) "complete" else "no_visual_context",
            "success" to root.optBoolean("success", false),
            "backend" to root.optString("backend"),
            "elapsed_ms" to root.optLong("elapsed_ms"),
            "generate_content_ms" to root.optLong("generate_content_ms"),
            "panel_confidence" to assets.panelConfidence,
            "mode" to assets.mode,
            "budget_reason" to budgetDecision.second,
            "visual_context" to visualContext.toJson().toString(),
            "raw_response" to raw.take(1_000),
            "error" to root.optString("error"),
        )
        GemmaFitDebugApi.updateState("session_visual_context", resultPayload)
        GemmaFitDebugApi.record(
            category = "session_visual_context",
            message = "vision_sidecar_result",
            data = resultPayload,
        )
        return visualContext
    }

    private fun sessionVisualSidecarBudgetDecision(
        sessionMotionZipPacket: MotionZipPacket?,
    ): Pair<Boolean, String> {
        val packet = sessionMotionZipPacket ?: return if (hasSessionVisualFrameCandidates()) {
            true to "scene_only_fallback:motion_zip_unavailable"
        } else {
            false to "visual_frames_unavailable"
        }
        val activityState = packet.activityContext.state
        val taskLabel = packet.activityContext.taskLabel.orEmpty().lowercase()
        val outputState = packet.heavilyCompressedSummary.outputState.lowercase()
        val ambiguityNote = packet.activityContext.ambiguityNote.orEmpty()
        val viewOrConfidenceLimited = synchronized(sessionDataLock) {
            viewLimitedCount > 0 || lowConfidenceCount > 0
        } || outputState.contains("monitor") || outputState.contains("abstain")
        val needsSupportContext = taskLabel.contains("chair") ||
            taskLabel.contains("support") ||
            packet.evidenceRefs.any { ref ->
                val normalized = ref.lowercase()
                normalized.contains("chair") || normalized.contains("support")
            }
        return when {
            activityState == ActivityContextState.AMBIGUOUS ->
                true to "activity_context_ambiguous:${ambiguityNote.ifBlank { "unknown" }}"
            activityState == ActivityContextState.SUSPECT_SWITCH ->
                true to "activity_context_suspect_switch"
            needsSupportContext ->
                true to "support_context_needed"
            viewOrConfidenceLimited ->
                true to "view_or_confidence_limited"
            else -> false to "deterministic_context_sufficient"
        }
    }

    private fun buildSessionVisualSidecarAssets(
        sessionMotionZipPacket: MotionZipPacket?,
    ): SessionVisualSidecarAssets? {
        val candidates = frameEvidenceCandidatesForSession()
        if (candidates.isEmpty()) return null
        val sceneOnlyFallback = sessionMotionZipPacket == null
        val selected = FrameEvidenceSelector.select(candidates).let { strictSelection ->
            if (strictSelection.allSelectedFrames().isNotEmpty() || !sceneOnlyFallback) {
                strictSelection
            } else {
                sceneOnlyFallbackSelection(candidates) ?: strictSelection
            }
        }
        if (selected.allSelectedFrames().isEmpty()) return null

        val frameByIndex = synchronized(processedFramesLock) {
            processedFrames.associateBy { it.frameIndex }
        }
        val tempBitmaps = mutableListOf<Bitmap>()
        val frameBitmaps = mutableMapOf<Int, Bitmap?>()
        selected.allSelectedFrames().forEach { candidate ->
            val frame = frameByIndex[candidate.frameIndex] ?: return@forEach
            val bitmap = safeBitmapCopy(frame.bitmap)
                ?: restoredReviewBitmap(frame, SESSION_VISUAL_SCENE_LONG_SIDE)
            if (bitmap != null) {
                tempBitmaps += bitmap
                frameBitmaps[candidate.frameIndex] = bitmap
            }
        }

        val tags = buildList {
            add(EvidencePanelTag("confidence", selected.panelConfidence))
            sessionMotionZipPacket?.heavilyCompressedSummary?.outputState
                ?.takeIf { it.isNotBlank() }
                ?.let { add(EvidencePanelTag("state", it)) }
            sessionMotionZipPacket?.activityContext?.state?.wireName
                ?.takeIf { it != ActivityContextState.UNKNOWN.wireName }
                ?.let { add(EvidencePanelTag("activity", it)) }
            if (sceneOnlyFallback) add(EvidencePanelTag("mode", SESSION_VISUAL_MODE_SCENE_ONLY))
        }
        val panel = if (sceneOnlyFallback) {
            null
        } else {
            EvidencePanelBuilder.buildPanel(
                selectedFrames = selected,
                frameBitmaps = frameBitmaps,
                tags = tags,
                maxLongSide = SESSION_VISUAL_PANEL_LONG_SIDE,
            )
        }
        if (!sceneOnlyFallback && panel == null) {
            tempBitmaps.forEach { it.recycleIfAlive() }
            return null
        }
        val sceneSource = selected.sceneAnchor
            ?.let { frameBitmaps[it.frameIndex] }
            ?: frameBitmaps.values.filterNotNull().firstOrNull()
            ?: panel
        val composite = if (sceneOnlyFallback) {
            scaledBitmapCopy(sceneSource, SESSION_VISUAL_COMPOSITE_LONG_SIDE)
        } else {
            buildSessionVisualComposite(sceneSource, panel ?: return null)
        }
        val imageFile = composite?.let {
            saveDebugBitmap(
                bitmap = it,
                fileName = "session_${analysisRunId}_visual_context.jpg",
                format = Bitmap.CompressFormat.JPEG,
                quality = 84,
            )
        }
        val result = if (imageFile != null) {
            SessionVisualSidecarAssets(
                selectedFrames = selected,
                imageFile = imageFile,
                panelConfidence = selected.panelConfidence,
                mode = if (sceneOnlyFallback) SESSION_VISUAL_MODE_SCENE_ONLY else SESSION_VISUAL_MODE_MOTIONZIP_PANEL,
            )
        } else {
            null
        }
        tempBitmaps.forEach { it.recycleIfAlive() }
        composite?.recycleIfAlive()
        panel?.recycleIfAlive()
        return result
    }

    private fun hasSessionVisualFrameCandidates(): Boolean {
        val frames = synchronized(processedFramesLock) { processedFrames.toList() }
        return frames.any { frame ->
            val hasStoredBitmap = frame.bitmap?.let { bitmap ->
                !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0
            } == true
            frame.landmarks.isNotEmpty() &&
                frame.subjectLockStatus != SubjectLockStatus.SUBJECT_LOST &&
                (hasStoredBitmap || (frame.bitmapWidth > 0 && frame.bitmapHeight > 0))
        }
    }

    private fun sceneOnlyFallbackSelection(
        candidates: List<FrameEvidenceCandidate>,
    ): SelectedEvidenceFrames? {
        val fallback = candidates
            .filter { it.subjectObserved && it.poseConfidence >= 0.2f }
            .maxByOrNull { it.poseConfidence + it.fullBodyVisibility }
            ?: candidates
                .filter { it.subjectObserved }
                .maxByOrNull { it.fullBodyVisibility }
            ?: return null
        return SelectedEvidenceFrames(
            sceneAnchor = fallback,
            top = null,
            descent = null,
            bottom = null,
            ascent = null,
            warningFrame = null,
            panelConfidence = FrameEvidenceSelector.CONFIDENCE_LOW,
            selectionBasis = listOf(
                "scene_only_fallback",
                "motion_zip_unavailable",
            ),
        )
    }

    private fun buildSessionVisualComposite(scene: Bitmap?, panel: Bitmap): Bitmap? {
        val scaledScene = scaledBitmapCopy(scene, SESSION_VISUAL_SCENE_LONG_SIDE)
        val scaledPanel = scaledBitmapCopy(panel, SESSION_VISUAL_PANEL_LONG_SIDE) ?: return scaledScene
        if (scaledScene == null) return scaledPanel
        val gapPx = 12
        val width = maxOf(scaledScene.width, scaledPanel.width)
        val height = scaledScene.height + gapPx + scaledPanel.height
        val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(scaledScene, ((width - scaledScene.width) / 2f), 0f, paint)
        canvas.drawBitmap(scaledPanel, ((width - scaledPanel.width) / 2f), (scaledScene.height + gapPx).toFloat(), paint)
        val result = scaledBitmapCopy(combined, SESSION_VISUAL_COMPOSITE_LONG_SIDE)
        scaledScene.recycleIfAlive()
        scaledPanel.recycleIfAlive()
        combined.recycleIfAlive()
        return result
    }

    private fun frameEvidenceCandidatesForSession(): List<FrameEvidenceCandidate> {
        val frames = synchronized(processedFramesLock) { processedFrames.toList() }
        var previousHipY: Float? = null
        var previousTimestampMs: Long? = null
        return frames.mapNotNull { frame ->
            val landmarks = frame.landmarks
            val stats = PosePresenceGate.evaluate(landmarks, { it.x }, { it.y }, { it.visibility })
            val hipY = averageLandmarkY(landmarks, 23, 24)
            val hipVelocityY = if (hipY != null && previousHipY != null && previousTimestampMs != null) {
                val dtSec = ((frame.timestampMs - previousTimestampMs!!).coerceAtLeast(1L)).toFloat() / 1000f
                (hipY - previousHipY!!) / dtSec
            } else {
                null
            }
            if (hipY != null) {
                previousHipY = hipY
                previousTimestampMs = frame.timestampMs
            }
            val poseConfidence = if (landmarks.isEmpty()) 0f else poseConfidenceFloor(landmarks)
            val fullBodyVisibility = when {
                stats.canRender -> maxOf(stats.avgVisibility, 0.75f)
                else -> stats.avgVisibility
            }
            val subjectObserved = landmarks.isNotEmpty() &&
                frame.subjectLockStatus != SubjectLockStatus.SUBJECT_LOST
            val subjectStable = frame.subjectLockStatus in setOf(
                SubjectLockStatus.LOCKED,
                SubjectLockStatus.AUTO_LOCKED,
                SubjectLockStatus.SINGLE_AUTO,
            ) && frame.subjectTrustFlags.none { it.contains("ambiguous") || it.contains("lost") }
            FrameEvidenceCandidate(
                frameIndex = frame.frameIndex,
                timestampMs = frame.timestampMs,
                phase = frameEvidencePhase(frame.movementPhase),
                poseConfidence = poseConfidence,
                fullBodyVisibility = fullBodyVisibility,
                subjectObserved = subjectObserved,
                subjectStable = subjectStable,
                hipY = hipY,
                hipVelocityY = hipVelocityY,
                hasWarning = frame.warnings.isNotEmpty(),
                warningIds = frame.warnings.map { it.functionName }.filter { it.isNotBlank() }.distinct(),
                evidenceRefs = (
                    frame.evidenceCard.evidenceRefs +
                        frame.warnings.map { "warning.${it.functionName}" }
                    ).filter { it.isNotBlank() }.distinct().take(8),
            )
        }
    }

    private fun averageLandmarkY(landmarks: List<PoseLandmarkData>, vararg indices: Int): Float? {
        val values = indices.asSequence()
            .mapNotNull { index -> landmarks.getOrNull(index) }
            .filter { it.visibility >= 0.15f && it.y.isFinite() }
            .map { it.y }
            .toList()
        if (values.isEmpty()) return null
        return values.average().toFloat().coerceIn(0f, 1f)
    }

    private fun frameEvidencePhase(phase: String): String {
        val normalized = phase.lowercase()
        return when {
            "standing" in normalized || "top" in normalized ->
                FrameEvidenceCandidate.PHASE_TOP
            "descent" in normalized || "descending" in normalized || "step_or_descent" in normalized ->
                FrameEvidenceCandidate.PHASE_DESCENT
            "bottom" in normalized || "seated_low" in normalized || "low" in normalized ->
                FrameEvidenceCandidate.PHASE_BOTTOM
            "rising" in normalized || "ascent" in normalized || "return" in normalized ->
                FrameEvidenceCandidate.PHASE_ASCENT
            else -> FrameEvidenceCandidate.PHASE_UNKNOWN
        }
    }

    private fun scaledBitmapCopy(bitmap: Bitmap?, maxLongSide: Int): Bitmap? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= maxLongSide) {
            return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val scale = maxLongSide.toFloat() / longSide.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun saveDebugBitmap(
        bitmap: Bitmap,
        fileName: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 92,
    ): File? {
        val safeName = File(fileName).name
        val dir = File(getApplication<Application>().filesDir, "debug").apply { mkdirs() }
        val file = File(dir, safeName)
        return runCatching {
            FileOutputStream(file).use { output ->
                bitmap.compress(format, quality, output)
            }
            file.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    private fun Bitmap.recycleIfAlive() {
        if (!isRecycled) recycle()
    }

    private fun SelectedEvidenceFrames.toDebugMap(): Map<String, Any?> {
        fun frame(candidate: FrameEvidenceCandidate?): Map<String, Any?>? {
            candidate ?: return null
            return mapOf(
                "frame_index" to candidate.frameIndex,
                "timestamp_ms" to candidate.timestampMs,
                "phase" to candidate.phase,
                "pose_confidence" to candidate.poseConfidence,
                "full_body_visibility" to candidate.fullBodyVisibility,
                "has_warning" to candidate.hasWarning,
            )
        }
        return mapOf(
            "panel_confidence" to panelConfidence,
            "scene_anchor" to frame(sceneAnchor),
            "top" to frame(top),
            "descent" to frame(descent),
            "bottom" to frame(bottom),
            "ascent" to frame(ascent),
            "warning_frame" to frame(warningFrame),
            "selection_basis" to selectionBasis,
        )
    }

    private fun publishLiveCueRewritePlan(
        cuePlan: LiveCuePlan,
        context: CoachContext,
        cameraEpoch: Long?,
        frameIndex: Int,
        timestampMs: Long,
        personTrackingState: PersonTrackingState,
        confidenceFloor: Float,
        capabilityJudgmentAllowed: Boolean,
        hasCriticalOrWarningEvidence: Boolean,
    ) {
        if (cameraEpoch == null) return
        val rewriteRequest = cuePlan.rewriteRequest ?: return
        val trigger = LiveCueModelPolicy.triggerFor(rewriteRequest.event) ?: return
        val invocationRequest = ModelInvocationRequest(
            trigger = trigger,
            personTrackingState = personTrackingState,
            confidenceFloor = confidenceFloor,
            capabilityJudgmentAllowed = capabilityJudgmentAllowed,
            hasCriticalOrWarningEvidence = hasCriticalOrWarningEvidence ||
                rewriteRequest.event == LiveCueRewriteEvent.WARNING_PERSISTED,
            needsLanguageExplanation = true,
            modelBackendAvailable = true,
        )
        val invocationPlan = ModelInvocationScheduler.plan(invocationRequest)
        val eventKey = LiveCueModelPolicy.eventKey(cameraEpoch, rewriteRequest)
        // The shipped FC router is not a free-text rewrite backend. Keep the
        // deterministic cue as the spoken output, then let the bounded router
        // replace it only when a low-frequency model call succeeds quickly.
        val payload = mapOf(
            "frame" to frameIndex,
            "timestamp_ms" to timestampMs,
            "outcome" to cuePlan.outcome,
            "variant_id" to cuePlan.variantId,
            "event_key" to eventKey,
            "request" to invocationRequest.toDebugMap(),
            "plan" to invocationPlan.toDebugMap(),
            "rewrite_request" to rewriteRequest.toDebugJson(),
            "timeout_ms" to LIVE_CUE_MODEL_TIMEOUT_MS,
            "status" to "planned",
        )
        GemmaFitDebugApi.updateState("live_cue_rewrite", payload)
        GemmaFitDebugApi.record(
            category = "live_cue_rewrite",
            message = "rewrite_plan",
            data = payload,
        )
        scheduleAsyncLiveVisionSidecar(
            trigger = trigger,
            baseEventKey = eventKey,
            context = context,
            cameraEpoch = cameraEpoch,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            personTrackingState = personTrackingState,
            confidenceFloor = confidenceFloor,
            hasCriticalOrWarningEvidence = hasCriticalOrWarningEvidence ||
                rewriteRequest.event == LiveCueRewriteEvent.WARNING_PERSISTED,
        )
        if (invocationPlan.decision != ModelInvocationDecision.CALL_E2B_NOW) {
            publishLiveCueRewriteStatus(payload, "deterministic_fallback_${invocationPlan.reason}")
            return
        }
        if (eventKey in liveCueInferenceEventKeys) {
            publishLiveCueRewriteStatus(payload, "duplicate_event_skipped")
            return
        }
        if (activeLiveCueInferenceJob?.isActive == true) {
            publishLiveCueRewriteStatus(payload, "in_flight_skipped")
            return
        }

        rememberLiveCueEventKey(eventKey)
        val safetyJson = buildLiveCueModelSafetyJson(
            rewriteRequest = rewriteRequest,
            personTrackingState = personTrackingState,
            confidenceFloor = confidenceFloor,
            invocationPlan = invocationPlan,
        )
        activeLiveCueInferenceJob = viewModelScope.launch(Dispatchers.IO) {
            GemmaFitDebugApi.record(
                category = "live_cue_rewrite",
                message = "model_inference_start",
                data = payload + mapOf("status" to "model_inference_start"),
            )
            val result = try {
                withTimeout(LIVE_CUE_MODEL_TIMEOUT_MS) {
                    coachInferenceRouter.runInference(
                        context = context,
                        safetyJson = safetyJson,
                        reasoningMode = invocationPlan.reasoningMode,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                unavailable(
                    backend = "fallback",
                    error = "live_cue_model_timeout",
                    selectionBasis = "Live cue model call exceeded ${LIVE_CUE_MODEL_TIMEOUT_MS}ms.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                unavailable(
                    backend = "fallback",
                    error = e.message ?: "live_cue_model_failed",
                    selectionBasis = "Live cue model call failed; deterministic cue remains active.",
                )
            }
            val accepted = validateLiveCueModelResult(context, result)
            val modelInsight = if (accepted) CoachInsightRenderer.render(context, result) else null
            if (modelInsight != null && isCurrentCameraPoseWrite(cameraEpoch)) {
                _lastCoachMessage = modelInsight.message
                _lastCoachPriority = modelInsight.priority
                lastCoachInsight = modelInsight
                _live.update { current ->
                    current.copy(
                        coachMessage = modelInsight.message,
                        coachPriority = modelInsight.priority,
                        coachInsight = modelInsight,
                    )
                }
            }
            val status = when {
                modelInsight != null -> "model_rewrite_applied"
                result.errorMessage == "live_cue_model_timeout" -> "timeout_deterministic_retained"
                result.success -> "model_rewrite_rejected"
                else -> "model_failed_deterministic_retained"
            }
            val resultPayload = payload + mapOf(
                "status" to status,
                "backend" to result.backend,
                "function" to result.functionName,
                "fallback" to (modelInsight?.fallback ?: true),
                "error" to result.errorMessage,
                "inference_time_ms" to result.inferenceTimeMs,
                "selection_basis" to (modelInsight?.selectionBasis ?: result.selectionBasis),
                "evidence_refs" to (modelInsight?.evidenceRefs ?: result.evidenceRefs),
            )
            GemmaFitDebugApi.updateState("live_cue_rewrite", resultPayload)
            GemmaFitDebugApi.record(
                category = "live_cue_rewrite",
                message = "model_inference_result",
                data = resultPayload,
            )
        }
    }

    private fun publishLiveCueRewriteStatus(payload: Map<String, Any>, status: String) {
        val updated = payload + mapOf("status" to status)
        GemmaFitDebugApi.updateState("live_cue_rewrite", updated)
        GemmaFitDebugApi.record(
            category = "live_cue_rewrite",
            message = "rewrite_status",
            data = updated,
        )
    }

    private fun rememberLiveCueEventKey(eventKey: String) {
        liveCueInferenceEventKeys += eventKey
        while (liveCueInferenceEventKeys.size > 80) {
            val iterator = liveCueInferenceEventKeys.iterator()
            if (!iterator.hasNext()) return
            iterator.next()
            iterator.remove()
        }
    }

    private fun buildLiveCueModelSafetyJson(
        rewriteRequest: LiveCueRewriteRequest,
        personTrackingState: PersonTrackingState,
        confidenceFloor: Float,
        invocationPlan: ModelInvocationPlan,
    ): String {
        val judgmentAllowed = invocationPlan.allowedJudgment &&
            !PersonTrackingPolicy.blocksHardJudgment(personTrackingState)
        val requiredFunction = if (judgmentAllowed && rewriteRequest.intent in LiteRtToolCallParser.allowed) {
            rewriteRequest.intent
        } else {
            "refuse_unsupported_question"
        }
        val requiredArgs = LiteRtOutputContract.requiredArgs(requiredFunction)
        return JSONObject()
            .put("trigger", rewriteRequest.event.name)
            .put("required_response_policy", if (judgmentAllowed) "bounded_tool_call" else "refuse_monitor_only")
            .put(
                "person_tracking_state",
                JSONObject()
                    .put("schema_version", "person_tracking_v1")
                    .put("state", PersonTrackingPolicy.wireState(personTrackingState))
                    .put("pose_confidence", confidenceFloor.toDouble())
                    .put("judgment_allowed", judgmentAllowed)
                    .put("hard_judgment_allowed", judgmentAllowed)
                    .put("reason", invocationPlan.reason),
            )
            .put(
                "router_contract",
                JSONObject()
                    .put("required_function", requiredFunction)
                    .put("event", rewriteRequest.event.name)
                    .put("variant_id", rewriteRequest.variantId),
            )
            .put(
                "output_contract",
                JSONObject()
                    .put("required_function", requiredFunction)
                    .put("allowed_function_names", org.json.JSONArray(listOf(requiredFunction)))
                    .put("required_args", org.json.JSONArray(requiredArgs))
                    .put("json_only", true)
                    .put("first_char", "{")
                    .put("first_key", "function"),
            )
            .put("live_cue_rewrite_request", JSONObject(rewriteRequest.toDebugJson()))
            .toString()
    }

    private fun validateLiveCueModelResult(
        context: CoachContext,
        result: com.gemmafit.jni.LLMBridge.FunctionCallResult,
    ): Boolean {
        if (!result.success) return false
        val allowedRefs = liveAllowedEvidenceRefs(context)
        val citedRefs = result.evidenceRefs.filter { it.isNotBlank() }
        val hardFunction = result.functionName in LiveCueModelPolicy.hardCoachingFunctions
        if (hardFunction && (allowedRefs.isEmpty() || citedRefs.isEmpty())) return false
        if (allowedRefs.isEmpty() && citedRefs.isNotEmpty()) return false
        return citedRefs.all { it in allowedRefs }
    }

    private fun liveAllowedEvidenceRefs(context: CoachContext): Set<String> {
        val refs = linkedSetOf<String>()
        refs += context.evidenceCard.evidenceRefs
        refs += context.qualityFlags.map { flag -> flag.evidenceId.ifBlank { flag.id } }
        refs += context.metrics.keys.map { "metric.${context.exercise.ifBlank { context.pattern }}.$it" }
        return refs.filter { it.isNotBlank() }.toSet()
    }

    private fun resetAsyncLiveVisionSidecar() {
        activeLiveVisionSidecarJob?.cancel()
        activeLiveVisionSidecarJob = null
        asyncVisionSidecarGate.reset()
        replaceLatestLiveVisionSnapshot(null)
    }

    private fun resetSessionData() {
        GemmaFitDebugApi.record(
            category = "session",
            message = "session_reset",
            data = mapOf("previous_frames" to totalFramesAnalyzed),
        )
        synchronized(sessionDataLock) {
            totalFramesAnalyzed = 0
            sessionStartMs = System.currentTimeMillis()
            formScoreHistory.clear()
            safetyEventLog.clear()
            exerciseDetectionCounts.clear()
            muscleFocusCounts.clear()
            coachTipsSet.clear()
            coachInsights.clear()
            notApplicableCounts.clear()
            viewLimitedCount = 0
            lowConfidenceCount = 0
            sessionCoachInsight = SessionCoachInsight()
            sessionCapabilityContract = CapabilityContract()
            sessionEvidenceRefs.clear()
            sessionVisualContext = SessionVisualContext.unknown()
            repRecords.clear()
            motionZipPackets.clear()
            temporalAnalyzer.reset()
            layer2Interpreter.reset()
            activityContextTracker.reset()
            motionTraceAnalyzer.reset()
            liveCuePlanner.reset()
            lastCoachMessage = ""
            cleanFrameStreak = 0
            activeCoachInferenceJob?.cancel()
            sessionCoachInferenceDedupGuard.reset()
            sessionSummaryPrewarmStartedForRun = -1L
            earlyVideoVisualContextStartedForRun = -1L
            activeEarlyVideoVisualContextJob?.cancel()
            activeEarlyVideoVisualContextJob = null
            activeLiveCueInferenceJob?.cancel()
            liveCueInferenceEventKeys.clear()
            reviewRecoveryJob?.cancel()
            reviewBitmapRestoreJob?.cancel()
            _lastCoachMessage = null
            _lastCoachPriority = null
            lastCoachInsight = CoachInsight()
            reviewFramePinned = false
            manualSubjectLock = false
            pendingSubjectTap = null
            lockedSubject = null
            lockedSubjectAppearance = null
            lockedSubjectTrackId = null
            activeTargetReanalysisSeed = null
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            nextSubjectTrackId = 1
            lostSubjectFrames = 0
            identityHoldFrames = 0
            resetMultiPersonDetectorGuard()
            subjectMotionTracker.reset()
            subjectRelocalizationPolicy.reset()
            currentFrameIdx = 0
            resetAllLandmarkStability()
            resetNativeTemporalLandmarks()
        }
        resetAsyncLiveVisionSidecar()
        // Recycle all remaining bitmaps
        clearProcessedFrames()
        _live.value = LiveWorkoutState(source = _state.value.source)
        _sessionSummary.value = SessionSummary()
    }

    private fun resetForFullAnalysisPass() {
        val previewSurface = _live.value
        val retainedPreviewBitmap = previewSurface.videoPreview
            ?.takeIf { !it.isRecycled }
            ?.let { bitmap -> bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false) }
        GemmaFitDebugApi.record(
            category = "session",
            message = "full_analysis_reset",
            data = mapOf(
                "preview_frames" to totalFramesAnalyzed,
                "preview_processed_frames" to processedFrameCount(),
            ),
        )
        synchronized(sessionDataLock) {
            totalFramesAnalyzed = 0
            sessionStartMs = System.currentTimeMillis()
            formScoreHistory.clear()
            safetyEventLog.clear()
            exerciseDetectionCounts.clear()
            muscleFocusCounts.clear()
            coachTipsSet.clear()
            coachInsights.clear()
            notApplicableCounts.clear()
            viewLimitedCount = 0
            lowConfidenceCount = 0
            sessionCoachInsight = SessionCoachInsight()
            sessionCapabilityContract = CapabilityContract()
            sessionEvidenceRefs.clear()
            sessionVisualContext = SessionVisualContext.unknown()
            repRecords.clear()
            motionZipPackets.clear()
            temporalAnalyzer.reset()
            layer2Interpreter.reset()
            activityContextTracker.reset()
            motionTraceAnalyzer.reset()
            lastCoachMessage = ""
            cleanFrameStreak = 0
            activeCoachInferenceJob?.cancel()
            reviewBitmapRestoreJob?.cancel()
            earlyVideoVisualContextStartedForRun = -1L
            activeEarlyVideoVisualContextJob?.cancel()
            activeEarlyVideoVisualContextJob = null
            _lastCoachMessage = null
            _lastCoachPriority = null
            lastCoachInsight = CoachInsight()
            reviewFramePinned = true
            manualSubjectLock = false
            pendingSubjectTap = null
            lockedSubject = null
            lockedSubjectAppearance = null
            lockedSubjectTrackId = null
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            nextSubjectTrackId = 1
            lostSubjectFrames = 0
            identityHoldFrames = 0
            resetMultiPersonDetectorGuard()
            subjectMotionTracker.reset()
            subjectRelocalizationPolicy.reset()
            currentFrameIdx = 0
            resetVideoLandmarkStability()
            resetNativeTemporalLandmarks()
        }
        resetAsyncLiveVisionSidecar()
        clearProcessedFrames()
        _live.value = previewSurface.copy(
            source = _state.value.source,
            analysisStage = "Full analysis starting",
            isPreviewData = true,
            fullProgress = 0f,
            videoPreview = retainedPreviewBitmap,
            poseTrajectory = emptyList(),
            currentFrameIndex = 0,
            currentFrameTimestampMs = 0L,
            latestProcessedTimestampMs = 0L,
            totalFramesAnalyzed = 0,
            motionZipStatus = MotionZipUiState(),
            activityContext = ActivityContext.unknown(),
            visualContext = SessionVisualContext.unknown(),
            sessionStatus = SessionStatusSnapshot(),
            reviewTargetChangedAfterAnalysis = false,
            targetReanalysisAvailable = false,
            targetReanalysisActive = false,
        )
    }

    private fun resetSubjectTemporalState() {
        synchronized(sessionDataLock) {
            temporalAnalyzer.reset()
            layer2Interpreter.reset()
            activityContextTracker.reset()
            motionTraceAnalyzer.reset()
            sessionVisualContext = SessionVisualContext.unknown()
            repRecords.clear()
            motionZipPackets.clear()
            cleanFrameStreak = 0
            resetMultiPersonDetectorGuard()
            subjectMotionTracker.reset()
            subjectRelocalizationPolicy.reset()
            activeCoachInferenceJob?.cancel()
            reviewBitmapRestoreJob?.cancel()
            earlyVideoVisualContextStartedForRun = -1L
            activeEarlyVideoVisualContextJob?.cancel()
            activeEarlyVideoVisualContextJob = null
            lastCoachInsight = CoachInsight()
            _lastCoachMessage = null
            _lastCoachPriority = null
            resetNativeTemporalLandmarks()
        }
        resetAsyncLiveVisionSidecar()
        _live.value = _live.value.copy(
            repCount = 0,
            movementPhase = "unknown",
            templateMetrics = emptyMap(),
            activeWarnings = emptyList(),
            motionZipStatus = MotionZipUiState(),
            activityContext = ActivityContext.unknown(),
            visualContext = SessionVisualContext.unknown(),
            sessionStatus = SessionStatusSnapshot(),
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun shouldSampleDebugFrame(frameIndex: Int): Boolean {
        return frameIndex < 3 || frameIndex % 15 == 0
    }

    private fun shouldBlockHardJudgment(
        qualityFlags: List<QualityFlag>,
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus? = null,
    ): Boolean {
        return qualityFlags.any { isLimitedQualityStatus(it.status) } ||
            PersonTrackingPolicy.blocksHardJudgment(subjectLockStatus, subjectTrustFlags)
    }

    private fun limitedQualityFlags(
        qualityFlags: List<QualityFlag>,
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus? = null,
    ): List<QualityFlag> {
        val limitedFlags = qualityFlags.filter { isLimitedQualityStatus(it.status) }
        if (limitedFlags.isNotEmpty()) return limitedFlags
        return listOf(
            QualityFlag(
                id = "subject_tracking_limited",
                status = "VIEW_LIMITED",
                value = 1f,
                threshold = 1f,
                evidence = "subject_lock",
                reason = subjectLimitedReason(subjectTrustFlags, subjectLockStatus),
            )
        )
    }

    private fun isLimitedQualityStatus(status: String): Boolean {
        return status == "LOW_CONFIDENCE" || status == "VIEW_LIMITED"
    }

    private fun blocksHardJudgmentForSubject(flag: String): Boolean {
        val normalized = flag.lowercase()
        return normalized in setOf(
            "subject_hold",
            "subject_identity_uncertain",
            "subject_identity_reacquiring",
            "subject_temporarily_occluded",
            "subject_temporarily_unmatched",
            "subject_lost",
            "needs_selection",
            "low_confidence",
        )
    }

    private fun blocksHardJudgmentForSubjectStatus(status: SubjectLockStatus): Boolean {
        return status == SubjectLockStatus.SUBJECT_LOST ||
            status == SubjectLockStatus.NEEDS_SELECTION
    }

    private fun shouldHideSelectedSkeleton(
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus? = null,
    ): Boolean {
        if (subjectLockStatus?.let { blocksHardJudgmentForSubjectStatus(it) } == true) return true
        val normalized = subjectTrustFlags.map { it.lowercase() }.toSet()
        // Low-confidence frames still get a preview skeleton; the hard
        // judgment path is blocked separately by shouldBlockHardJudgment.
        return normalized.any {
            it in setOf(
                "subject_hold",
                "subject_identity_uncertain",
                "subject_identity_reacquiring",
                "subject_temporarily_occluded",
                "subject_temporarily_unmatched",
                "subject_lost",
                "needs_selection",
                "pose_preview_subject_limited",
                "judgment_blocked_subject",
            )
        }
    }

    private fun subjectTrustFlagForStatus(status: SubjectLockStatus?): String? {
        return when (status) {
            SubjectLockStatus.SUBJECT_LOST -> "subject_lost"
            SubjectLockStatus.NEEDS_SELECTION -> "needs_selection"
            else -> null
        }
    }

    private fun personTrackingStateFor(
        subjectLockStatus: SubjectLockStatus?,
        subjectTrustFlags: List<String>,
    ): PersonTrackingState = PersonTrackingPolicy.stateFor(subjectLockStatus, subjectTrustFlags)

    private fun resolveLayer2ExerciseCandidate(
        exercise: String,
        pattern: String,
        basis: List<String>,
        candidateScores: Map<String, Float>,
        metrics: Map<String, Float>,
    ): String {
        val normalizedExercise = exercise.lowercase()
        if (normalizedExercise in setOf(
                "chair_sit_to_stand",
                "supported_squat",
                "bodyweight_or_goblet_squat",
                "balance_hold",
                "setup_transition",
            )
        ) {
            return normalizedExercise
        }

        val supportProxy = metrics["support_contact_proxy"]
            ?: metrics["support_contact"]
            ?: metrics["chair_contact_proxy"]
            ?: metrics["hand_support_proxy"]
        if (supportProxy != null && supportProxy >= 0.5f) {
            return "supported_squat"
        }

        val textEvidence = (
            listOf(exercise, pattern) + basis
            )
            .joinToString(" ")
            .lowercase()
        val squatScore = candidateScores["squat"] ?: 0f
        val lungeScore = candidateScores["lunge"] ?: 0f
        val kneeAngle = metrics["knee_angle"]
            ?: metrics["front_knee_angle"]
            ?: metrics["left_knee_angle"]
            ?: metrics["right_knee_angle"]
        val hipHinge = metrics["hip_hinge"] ?: metrics["hip_angle"]
        val hasSquatCandidate = squatScore >= 0.5f
        val hasKneeDominantPattern = "knee_dominant" in textEvidence ||
            "knee dominant" in textEvidence ||
            "squat" in textEvidence
        val hasGobletLikeUpperBody = "elbow_flexion" in textEvidence ||
            "goblet" in textEvidence ||
            "dumbbell" in textEvidence ||
            "wrist_chest" in textEvidence
        val hasDeepKneeFlexion = kneeAngle != null && kneeAngle <= 125f
        val hasSquatGeometry = hasDeepKneeFlexion &&
            (hipHinge == null || hipHinge <= 75f || hasGobletLikeUpperBody)
        val likelyUnilateral = lungeScore >= 0.75f && lungeScore > squatScore + 0.15f

        return if (!likelyUnilateral && (hasSquatCandidate || hasKneeDominantPattern || hasSquatGeometry)) {
            "bodyweight_or_goblet_squat"
        } else {
            exercise
        }
    }

    private fun layer2ActivityHint(exercise: String, pattern: String): String {
        val candidates = listOf(exercise, pattern)
            .map { it.lowercase() }
            .filter { it.isNotBlank() && it != "unknown" }
        if (candidates.isEmpty()) return "unknown"
        return when {
            candidates.any { "chair_sit_to_stand" in it || "sit_to_stand" in it } -> "chair_sit_to_stand"
            candidates.any { "supported_squat" in it } -> "supported_squat"
            candidates.any { "bodyweight_or_goblet_squat" in it || "goblet_squat" in it || "bodyweight_squat" in it || it == "squat" } ->
                "bodyweight_or_goblet_squat"
            candidates.any { "balance_hold" in it || "single_leg_balance" in it || "supported_balance" in it } -> "balance_hold"
            candidates.any { "setup_transition" in it || it == "setup" || it == "transition" } -> "setup_transition"
            else -> "unknown"
        }
    }

    private fun applyLayer2RulePolicy(
        qualityFlags: List<QualityFlag>,
        rulePolicy: Layer2RulePolicy,
    ): List<QualityFlag> {
        return qualityFlags.map { flag ->
            val isHardStatus = flag.status == "WARNING" || flag.status == "CRITICAL"
            if (!isHardStatus) return@map flag

            val blockedByPolicy = when {
                !rulePolicy.allowHardJudgment -> true
                flag.rule == 4 && !rulePolicy.allowBilateralSymmetryHardWarning -> true
                flag.rule in rulePolicy.blockedRules -> true
                !rulePolicy.allowStrengthTemplateHardWarnings && flag.rule in setOf(4, 6, 7) -> true
                else -> false
            }
            if (!blockedByPolicy) return@map flag

            flag.copy(
                status = "MONITOR",
                reason = listOf(flag.reason, "layer2_${rulePolicy.outputState}")
                    .filter { it.isNotBlank() }
                    .joinToString("; "),
            )
        }
    }

    private fun subjectLimitedReason(
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus? = null,
    ): String {
        val normalized = subjectTrustFlags.map { it.lowercase() }.toSet()
        return when {
            subjectLockStatus == SubjectLockStatus.SUBJECT_LOST -> "subject_lost"
            subjectLockStatus == SubjectLockStatus.NEEDS_SELECTION -> "subject_selection_required"
            "subject_hold" in normalized -> "subject_tracking_hold"
            "subject_identity_uncertain" in normalized -> "subject_identity_uncertain"
            "subject_identity_reacquiring" in normalized -> "subject_identity_reacquiring"
            "subject_temporarily_occluded" in normalized -> "subject_temporarily_occluded"
            "subject_lost" in normalized -> "subject_lost"
            "needs_selection" in normalized -> "subject_selection_required"
            else -> "pose_tracking_limited"
        }
    }

    private fun capabilityContractForAnalysis(
        base: CapabilityContract,
        hardJudgmentBlocked: Boolean,
        qualityFlags: List<QualityFlag>,
        subjectTrustFlags: List<String>,
        subjectLockStatus: SubjectLockStatus?,
    ): CapabilityContract {
        if (!hardJudgmentBlocked) return base
        val reason = subjectLimitedReason(subjectTrustFlags, subjectLockStatus)
        val refs = (
            qualityFlags.map { it.evidenceId.ifBlank { it.id } } +
                base.evidenceRefs
            )
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("subject_lock") }
        val blocked = listOf(
            CapabilityJudgment(
                metric = "hard_form_judgment",
                reason = reason,
                confidenceCeiling = 0f,
                requiredEvidence = listOf("observable_subject", "stable_subject_identity", "sufficient_pose_confidence"),
                evidenceRefs = refs,
            ),
            CapabilityJudgment(
                metric = "warning_level_form_correction",
                reason = reason,
                confidenceCeiling = 0f,
                requiredEvidence = listOf("stable_subject_identity", "applicable_phase"),
                evidenceRefs = refs,
            ),
        )
        return CapabilityContract(
            canJudge = emptyList(),
            cannotJudge = mergeCapabilityItems(base.cannotJudge, blocked),
        )
    }

    private fun recordSessionQualityCounts(
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
    ) {
        synchronized(sessionDataLock) {
            if (qualityFlags.any { it.status == "VIEW_LIMITED" }) {
                viewLimitedCount += 1
            }
            if (qualityFlags.any { it.status == "LOW_CONFIDENCE" }) {
                lowConfidenceCount += 1
            }
            notApplicableFlags.forEach { flag ->
                val key = flag.id.ifBlank { "not_applicable" }
                notApplicableCounts[key] = (notApplicableCounts[key] ?: 0) + 1
            }
        }
    }

    private fun publishDebugAnalysisState(
        frameIndex: Int,
        timestampMs: Long,
        exercise: String,
        exerciseBasis: List<String> = emptyList(),
        candidateScores: Map<String, Float> = emptyMap(),
        score: Int,
        movementPhase: String,
        warnings: List<SafetyWarning>,
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
        subjectTrustFlags: List<String>,
        coachInsight: CoachInsight,
        capabilityContract: CapabilityContract = CapabilityContract(),
        evidenceDag: EvidenceDag = EvidenceDag(),
        traceSummary: RepTraceSummary? = null,
    ) {
        GemmaFitDebugApi.updateState(
            section = "video_analysis",
            data = mapOf(
                "frame" to frameIndex,
                "timestamp_ms" to timestampMs,
                "exercise" to exercise,
                "exercise_basis" to exerciseBasis,
                "candidate_scores" to candidateScores,
                "form_score" to score,
                "phase" to movementPhase,
                "rep_count" to _live.value.repCount,
                "active_subject_index" to _live.value.activeSubjectIndex,
                "active_subject_track_id" to _live.value.activeSubjectTrackId,
                "subject_status" to _live.value.subjectLockStatus.name,
                "subject_trust_flags" to subjectTrustFlags,
                "warnings" to warnings.map { warning ->
                    mapOf(
                        "rule" to warning.rule,
                        "function" to warning.functionName,
                        "severity" to warning.severity,
                        "joint" to warning.joint,
                        "message" to warning.message,
                    )
                },
                "quality_flags" to (qualityFlags + notApplicableFlags).map { flag ->
                    mapOf(
                        "id" to flag.id,
                        "evidence_id" to flag.evidenceId,
                        "status" to flag.status,
                        "value" to flag.value,
                        "threshold" to flag.threshold,
                        "evidence" to flag.evidence,
                        "reason" to flag.reason,
                        "rule" to flag.rule,
                        "joint" to flag.joint,
                    )
                },
                "coach" to mapOf(
                    "message" to coachInsight.message,
                    "priority" to coachInsight.priority,
                    "backend" to coachInsight.backend,
                    "function" to coachInsight.functionName,
                    "fallback" to coachInsight.fallback,
                ),
                "capability_contract" to capabilityContract.toDebugMap(),
                "evidence_dag" to evidenceDag.toDebugMap(),
                "rep_trace_summary" to traceSummary?.toDebugMap(),
            ),
        )
    }

    private fun parseMotionReport(root: JSONObject): ParsedMotionReport {
        return MotionReportParser.parse(root)
    }

    private fun functionNameForRule(rule: Int): String = when (rule) {
        1 -> "correct_knee_alignment"
        2 -> "correct_spinal_alignment"
        3 -> "correct_joint_angle"
        4 -> "correct_asymmetry"
        5 -> "warn_com_offset"
        6 -> "warn_rapid_movement"
        7 -> "increase_range_of_motion"
        8 -> "correct_spinal_alignment"
        else -> "unknown"
    }

    private fun functionNameForQualityFlag(flag: QualityFlag): String {
        if (flag.rule > 0) return functionNameForRule(flag.rule)
        return when (flag.id) {
            "body_line" -> "correct_spinal_alignment"
            "lunge_stability" -> "correct_asymmetry"
            "exercise_template" -> "refuse_unsupported_question"
            "trace_deviation" -> "warn_com_offset"
            else -> "unknown"
        }
    }

    private fun mergeTemporalMetrics(
        staticMetrics: Map<String, Float>,
        temporalMetrics: Map<String, Float>,
    ): Map<String, Float> {
        if (temporalMetrics.isEmpty()) return staticMetrics
        return staticMetrics.toMutableMap().apply {
            putAll(temporalMetrics)
        }
    }

    private fun mergeTraceMetrics(
        metrics: Map<String, Float>,
        traceSummary: RepTraceSummary,
    ): Map<String, Float> {
        return metrics.toMutableMap().apply {
            put("tempo_sec", traceSummary.tempoSec)
            put("rom_proxy_deg", traceSummary.romProxyDeg)
            put("peak_velocity_deg_s", traceSummary.peakVelocityDegS)
            put("smoothness_proxy", traceSummary.smoothnessProxy)
            put("lateral_sway_proxy", traceSummary.lateralSwayProxy)
            put("path_deviation_from_baseline", traceSummary.pathDeviationFromBaseline)
            put("confidence_coverage", traceSummary.confidenceCoverage)
        }
    }

    private fun RepTraceSummary.toDebugMap(): Map<String, Any> {
        return mapOf(
            "rep_number" to repNumber,
            "exercise" to exercise,
            "tempo_sec" to tempoSec,
            "rom_proxy_deg" to romProxyDeg,
            "peak_velocity_deg_s" to peakVelocityDegS,
            "smoothness_proxy" to smoothnessProxy,
            "lateral_sway_proxy" to lateralSwayProxy,
            "path_deviation_from_baseline" to pathDeviationFromBaseline,
            "confidence_coverage" to confidenceCoverage,
        )
    }

    private fun CapabilityContract.toDebugMap(): Map<String, Any> {
        return mapOf(
            "can_judge" to canJudge.map { it.toDebugMap() },
            "cannot_judge" to cannotJudge.map { it.toDebugMap() },
        )
    }

    private fun CapabilityJudgment.toDebugMap(): Map<String, Any> {
        return mapOf(
            "metric" to metric,
            "reason" to reason,
            "confidence_ceiling" to confidenceCeiling,
            "required_evidence" to requiredEvidence,
            "evidence_refs" to evidenceRefs,
        )
    }

    private fun EvidenceDag.toDebugMap(): Map<String, Any> {
        return mapOf(
            "node_count" to nodes.size,
            "edge_count" to edges.size,
            "nodes" to nodes.take(24).map { it.toDebugMap() },
            "edges" to edges.take(32).map { it.toDebugMap() },
        )
    }

    private fun EvidenceDagNode.toDebugMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "type" to type,
            "metric" to metric,
            "value" to value,
            "confidence" to confidence,
            "status" to status,
            "source" to listOf(sourceModule, sourceFunction)
                .filter { it.isNotBlank() }
                .joinToString("#"),
            "frame_range" to frameRange,
            "evidence_level" to evidenceLevel,
            "reason" to reason,
        )
    }

    private fun EvidenceDagEdge.toDebugMap(): Map<String, Any> {
        return mapOf(
            "from" to from,
            "to" to to,
            "relation" to relation,
        )
    }

    private fun mergeQualityFlags(
        staticFlags: List<QualityFlag>,
        temporalFlags: List<QualityFlag>,
    ): List<QualityFlag> {
        if (temporalFlags.isEmpty()) return staticFlags
        val temporalIds = temporalFlags.map { it.id }.toSet()
        return staticFlags.filterNot { it.id in temporalIds } + temporalFlags
    }

    private fun mergeCapabilityContracts(
        current: CapabilityContract,
        next: CapabilityContract,
    ): CapabilityContract {
        if (next.canJudge.isEmpty() && next.cannotJudge.isEmpty()) return current
        return CapabilityContract(
            canJudge = mergeCapabilityItems(current.canJudge, next.canJudge),
            cannotJudge = mergeCapabilityItems(current.cannotJudge, next.cannotJudge),
        )
    }

    private fun mergeCapabilityItems(
        current: List<CapabilityJudgment>,
        next: List<CapabilityJudgment>,
    ): List<CapabilityJudgment> {
        val byMetric = linkedMapOf<String, CapabilityJudgment>()
        (current + next).forEach { item ->
            val existing = byMetric[item.metric]
            byMetric[item.metric] = if (existing == null) {
                item
            } else {
                existing.copy(
                    reason = existing.reason.ifBlank { item.reason },
                    confidenceCeiling = maxOf(existing.confidenceCeiling, item.confidenceCeiling),
                    requiredEvidence = (existing.requiredEvidence + item.requiredEvidence).distinct(),
                    evidenceRefs = (existing.evidenceRefs + item.evidenceRefs).distinct(),
                )
            }
        }
        return byMetric.values.toList()
    }

    private fun formScoreFromQuality(
        qualityFlags: List<QualityFlag>,
        legacyViolations: List<KinematicsBridge.SafetyViolation>,
    ): Int {
        if (qualityFlags.isNotEmpty()) {
            return when {
                qualityFlags.any { it.status == "CRITICAL" } -> 30
                qualityFlags.any { it.status == "WARNING" } -> 60
                qualityFlags.any { it.status == "VIEW_LIMITED" || it.status == "LOW_CONFIDENCE" } -> 50
                qualityFlags.any { it.status == "MONITOR" } -> 85
                else -> 100
            }
        }

        val violationCount = legacyViolations.size
        val maxSeverity = legacyViolations.maxOfOrNull { it.severity } ?: 0f
        return when {
            violationCount == 0 -> 100
            maxSeverity >= 0.9f -> 30
            maxSeverity >= 0.5f -> 60
            else -> 80
        }
    }

    private fun buildTrustMatrix(
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
    ): List<TrustMatrixItem> {
        val statuses = qualityFlags.map { it.status }.toSet()
        val hasActiveIssue = qualityFlags.any {
            it.status == "CRITICAL" ||
                it.status == "WARNING" ||
                it.status == "MONITOR" ||
                it.status == "VIEW_LIMITED" ||
                it.status == "LOW_CONFIDENCE"
        }
        return listOf(
            TrustMatrixItem(
                status = "OK",
                label = "OK",
                active = !hasActiveIssue,
                description = "Evidence supports normal movement-quality feedback.",
            ),
            TrustMatrixItem(
                status = "VIEW_LIMITED",
                label = "View",
                active = statuses.contains("VIEW_LIMITED"),
                description = "Camera angle or crop limits this judgment.",
            ),
            TrustMatrixItem(
                status = "LOW_CONFIDENCE",
                label = "Low conf",
                active = statuses.contains("LOW_CONFIDENCE"),
                description = "Pose tracking is not stable enough for a risk grade.",
            ),
            TrustMatrixItem(
                status = "NOT_APPLICABLE",
                label = "N/A",
                active = notApplicableFlags.isNotEmpty(),
                description = "Some judgments are intentionally skipped.",
            ),
            TrustMatrixItem(
                status = "MONITOR",
                label = "Watch",
                active = statuses.contains("MONITOR"),
                description = "Proxy metric is observable but not a hard warning.",
            ),
            TrustMatrixItem(
                status = "WARNING",
                label = "Warn",
                active = statuses.contains("WARNING"),
                description = "Reliable evidence crossed a prototype threshold.",
            ),
            TrustMatrixItem(
                status = "CRITICAL",
                label = "Reset",
                active = statuses.contains("CRITICAL"),
                description = "Severe evidence asks the user to stop and reset.",
            ),
        )
    }

    private fun buildEvidenceCard(
        exercise: String,
        exerciseConfidence: Float,
        metrics: Map<String, Float>,
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
        capabilityContract: CapabilityContract = CapabilityContract(),
        evidenceRefs: List<String> = emptyList(),
    ): EvidenceCard {
        val active = qualityFlags.sortedWith(compareBy<QualityFlag> { statusPriority(it.status) })
        val top = active.firstOrNull()
        val verdict = top?.status ?: "OK"
        val reason = top?.reason?.ifBlank { top.id.replace("_", " ") }
            ?: "No active issue."

        val traceMetricKeys = listOf(
            "tempo_sec",
            "rom_proxy_deg",
            "lateral_sway_proxy",
            "path_deviation_from_baseline",
            "confidence_coverage",
        )
        val traceEvidence = traceMetricKeys.mapNotNull { key ->
            metrics[key]?.let { value ->
                EvidenceItem(
                    label = key.replace("_", " "),
                    value = "%.2f".format(value),
                )
            }
        }
        val metricEvidence = metrics.entries
            .filterNot { (key, _) -> key in traceMetricKeys }
            .take(4 - traceEvidence.size.coerceAtMost(3))
            .map { (key, value) ->
            EvidenceItem(
                label = key.replace("_", " "),
                value = "%.1f".format(value),
            )
        }
        val evidence = buildList {
            add(EvidenceItem("exercise", exercise.replace("_", " ")))
            add(EvidenceItem("confidence", "${(exerciseConfidence * 100).toInt()}%"))
            addAll(traceEvidence)
            addAll(metricEvidence)
            top?.let { flag ->
                add(EvidenceItem(flag.id.replace("_", " "), "%.1f / %.1f".format(flag.value, flag.threshold)))
            }
        }

        val trustFlags = buildList {
            addAll(qualityFlags.map { "${it.status}:${it.id}" })
            addAll(notApplicableFlags.map { "NOT_APPLICABLE:${it.id}" })
            if (qualityFlags.isEmpty()) add("OK")
        }.distinct()

        val unsupported = buildList {
            add("fall_risk_prediction")
            add("joint_force")
            add("clinical_injury_risk")
            add("medical_diagnosis")
            add("muscle_activation_percentage")
            addAll(notApplicableFlags.map { it.id })
        }.distinct()

        return EvidenceCard(
            verdict = verdict,
            reason = reason,
            evidence = evidence,
            trustFlags = trustFlags,
            evidenceRefs = evidenceRefs,
            capabilityCanJudge = capabilityContract.canJudge.map { it.metric },
            capabilityCannotJudge = capabilityContract.cannotJudge.map { it.metric },
            unsupportedJudgments = unsupported,
            modelBoundary = "Movement quality feedback only, not medical diagnosis.",
        )
    }

    private fun statusPriority(status: String): Int = when (status) {
        "CRITICAL" -> 0
        "WARNING" -> 1
        "VIEW_LIMITED" -> 2
        "LOW_CONFIDENCE" -> 3
        "MONITOR" -> 4
        "NOT_APPLICABLE" -> 5
        else -> 9
    }

    private fun EvidenceCard.withSubjectTrustFlags(flags: List<String>): EvidenceCard {
        if (flags.isEmpty()) return this
        return copy(trustFlags = (trustFlags + flags).distinct())
    }

    private fun scheduleSessionCoachInference(
        eventKey: String,
        context: SessionCoachContext,
        sessionMotionZipPacket: MotionZipPacket? = null,
    ) {
        if (!CoachTriggerPolicy.shouldTrigger(CoachTriggerEvent.FULL_ANALYSIS_COMPLETE)) return
        val invocationRequest = ModelInvocationRequest(
            trigger = ModelInvocationTrigger.SESSION_ENDED,
            personTrackingState = PersonTrackingState.OBSERVED,
            confidenceFloor = 1f,
            capabilityJudgmentAllowed = true,
            hasCriticalOrWarningEvidence = context.safetyEvents.isNotEmpty(),
            needsLanguageExplanation = true,
            deviceState = ModelDeviceState(modelDisabled = !ENABLE_NATIVE_SESSION_SUMMARY),
            multimodalEvidencePanelEnabled = ENABLE_SESSION_VISUAL_SIDECAR,
            multimodalBackendAvailable = CoachModelResolver.resolveLiteRtModelPath(
                getApplication<Application>(),
                "official",
            ) != null,
        )
        val invocationPlan = ModelInvocationScheduler.plan(invocationRequest)
        val invocationPayload = mapOf(
            "event" to "session_summary",
            "event_key" to eventKey,
            "request" to invocationRequest.toDebugMap(),
            "plan" to invocationPlan.toDebugMap(),
            "total_reps" to context.totalReps,
            "total_frames" to context.totalFrames,
            "evidence_refs" to context.evidenceRefs,
        )
        val modelPathForDedup = CoachModelResolver
            .resolveLiteRtModelPath(getApplication<Application>())
            ?: "no_model"
        val dedupKey = "$analysisRunId|$eventKey|$modelPathForDedup"
        GemmaFitDebugApi.record(
            category = "model_invocation",
            message = "session_summary_plan",
            data = invocationPayload,
        )
        GemmaFitDebugApi.updateState(
            section = "model_invocation",
            data = invocationPayload,
        )
        if (!ENABLE_NATIVE_SESSION_SUMMARY) {
            val insight = SessionCoachRenderer.render(context)
            Log.w(TAG, "Native session summary disabled; using deterministic summary for $eventKey")
            GemmaFitDebugApi.record(
                category = "coach_summary",
                message = "summary_inference_skipped",
                data = mapOf(
                    "event_key" to eventKey,
                    "trigger" to CoachTriggerPolicy.MODE,
                    "backend" to insight.backend,
                    "error" to "native_session_summary_disabled",
                    "reason" to "litert_lm_native_crash_guard",
                ),
            )
            publishSessionCoachState(
                eventKey = eventKey,
                insight = insight,
                error = "native_session_summary_disabled",
            )
            return
        }
        if (invocationPlan.decision != ModelInvocationDecision.CALL_E2B_NOW) {
            val insight = SessionCoachRenderer.render(context)
            publishSessionCoachState(
                eventKey = eventKey,
                insight = insight,
                error = invocationPlan.reason,
            )
            return
        }
        if (!sessionCoachInferenceDedupGuard.shouldFire(dedupKey)) {
            GemmaFitDebugApi.record(
                category = "coach_summary",
                message = "summary_inference_skipped_dedup",
                data = mapOf(
                    "event_key" to eventKey,
                    "dedup_key" to dedupKey,
                    "model_path" to modelPathForDedup,
                ),
            )
            return
        }
        val pendingInsight = SessionCoachRenderer.render(context).copy(
            modelStatus = SessionCoachModelStatus.PENDING,
            streamingPhase = SessionCoachStreamPhase.QUEUED,
            constrainedDecoding = true,
            fallback = true,
        )
        publishSessionCoachState(
            eventKey = eventKey,
            insight = pendingInsight,
            error = "",
        )
        activeCoachInferenceJob?.cancel()
        activeCoachInferenceJob = viewModelScope.launch(Dispatchers.IO) {
            GemmaFitDebugApi.record(
                category = "coach_summary",
                message = "summary_inference_start",
                data = mapOf(
                    "event_key" to eventKey,
                    "trigger" to CoachTriggerPolicy.MODE,
                    "exercise" to context.mainExercise,
                    "total_reps" to context.totalReps,
                    "total_frames" to context.totalFrames,
                    "view_limited_count" to context.viewLimitedCount,
                    "low_confidence_count" to context.lowConfidenceCount,
                    "reasoning_mode" to invocationPlan.reasoningMode.wireName,
                ),
            )
            val streamObserver = CoachInferenceStreamObserver { update ->
                publishSessionCoachStreamUpdate(
                    eventKey = eventKey,
                    pendingInsight = pendingInsight,
                    update = update,
                )
            }
            val visualContext = if (ENABLE_SESSION_VISUAL_SIDECAR && !context.visualContext.available) {
                runSessionVisualSidecar(
                    eventKey = "session_visual|${context.totalFrames}|${context.totalReps}",
                    sessionMotionZipPacket = sessionMotionZipPacket,
                )
            } else {
                context.visualContext
            }
            val inferenceContext = if (visualContext.available && visualContext != context.visualContext) {
                SessionCoachRenderer.contextFrom(
                    summary = buildSessionSummary().copy(isPreviewData = false),
                    seniorHeroMode = seniorHeroMode,
                    locale = sessionLocale,
                )
            } else {
                context
            }
            val result = coachInferenceRouter.runSessionInference(
                context = inferenceContext,
                reasoningMode = invocationPlan.reasoningMode,
                streamObserver = streamObserver,
            )
            val insight = SessionCoachRenderer.render(inferenceContext, result)
            if (!insight.fallback && result.success && result.errorMessage.isBlank()) {
                sessionCoachInferenceDedupGuard.recordSuccess(dedupKey)
            } else {
                sessionCoachInferenceDedupGuard.recordFailure(dedupKey)
            }
            Log.d(
                TAG,
                "Session coach inference: backend=${insight.backend}, function=${insight.functionName}, " +
                    "fallback=${insight.fallback}, error=${result.errorMessage}"
            )
            GemmaFitDebugApi.record(
                category = "coach_summary",
                message = "summary_inference_result",
                data = mapOf(
                    "event_key" to eventKey,
                    "backend" to insight.backend,
                    "function" to insight.functionName,
                    "fallback" to insight.fallback,
                    "error" to result.errorMessage,
                    "inference_time_ms" to result.inferenceTimeMs,
                    "reasoning_mode" to invocationPlan.reasoningMode.wireName,
                    "selection_basis" to insight.selectionBasis,
                    "evidence_refs" to insight.evidenceRefs,
                    "headline" to insight.headline,
                    "next_focus" to insight.nextFocus,
                ),
            )
            GemmaFitDebugApi.updateState(
                section = "coach_summary",
                data = sessionCoachStateMap(eventKey, insight, result.errorMessage),
            )
            publishModelReadinessDebug(
                reason = "summary_inference_result",
                backend = insight.backend,
                fallback = insight.fallback,
                fallbackReason = result.errorMessage.ifBlank { insight.selectionBasis },
            )
            sessionCoachInsight = insight
            publishSessionCoachInsightToLive(insight, result.errorMessage)
            _sessionSummary.value = buildSessionSummary()
        }
    }

    private fun publishSessionCoachState(
        eventKey: String,
        insight: SessionCoachInsight,
        error: String,
    ) {
        GemmaFitDebugApi.updateState(
            section = "coach_summary",
            data = sessionCoachStateMap(eventKey, insight, error),
        )
        publishModelReadinessDebug(
            reason = "summary_state_published",
            backend = insight.backend,
            fallback = insight.fallback,
            fallbackReason = error.ifBlank { insight.selectionBasis },
        )
        sessionCoachInsight = insight
        publishSessionCoachInsightToLive(insight, error)
        _sessionSummary.value = buildSessionSummary()
    }

    private fun publishSessionCoachStreamUpdate(
        eventKey: String,
        pendingInsight: SessionCoachInsight,
        update: CoachInferenceStreamUpdate,
    ) {
        if (update.phase == SessionCoachStreamPhase.IDLE) return
        val liveInsight = pendingInsight.copy(
            backend = update.backend.ifBlank { pendingInsight.backend },
            modelStatus = SessionCoachModelStatus.PENDING,
            streamingPhase = update.phase,
            streamingText = update.partialText.takeIf { update.phase == SessionCoachStreamPhase.STREAMING }.orEmpty(),
            streamTokenCount = update.tokenCount,
            firstTokenTimeMs = update.firstTokenTimeMs,
            constrainedDecoding = update.constrainedDecoding,
            fallback = true,
        )
        GemmaFitDebugApi.updateState(
            section = "coach_summary",
            data = sessionCoachStateMap(eventKey, liveInsight, update.error),
        )
        sessionCoachInsight = liveInsight
        publishSessionCoachInsightToLive(liveInsight, update.error)
        _sessionSummary.value = buildSessionSummary()
    }

    private fun publishSessionCoachInsightToLive(
        insight: SessionCoachInsight,
        error: String,
    ) {
        val pendingMessage = if (insight.modelStatus == SessionCoachModelStatus.PENDING) {
            streamingStatusText(insight)
        } else {
            ""
        }
        val message = pendingMessage.ifBlank {
            insight.headline.ifBlank {
                insight.whatISaw.ifBlank {
                    insight.nextFocus
                }
            }
        }
        if (message.isBlank() && insight.modelStatus != SessionCoachModelStatus.PENDING) return
        val priority = if (!insight.fallback && error.isBlank()) "high" else "medium"
        _live.value = _live.value.copy(
            coachMessage = message.ifBlank { "Local Gemma is generating your session summary." },
            coachPriority = priority,
            coachInsight = CoachInsight(
                message = message.ifBlank { "Local Gemma is generating your session summary." },
                priority = priority,
                backend = insight.backend,
                functionName = insight.functionName,
                selectionBasis = error.ifBlank { insight.selectionBasis },
                evidenceRefs = insight.evidenceRefs,
                summaryNarrative = if (insight.modelStatus == SessionCoachModelStatus.PENDING) {
                    listOf(streamingStatusText(insight), insight.whatISaw, insight.nextFocus)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                } else {
                    listOf(
                        insight.whatISaw,
                        insight.whyItMatters,
                        insight.notJudged,
                        insight.nextFocus,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                },
                modelStatus = insight.modelStatus,
                fallback = insight.fallback,
            ),
        )
    }

    private fun streamingStatusText(insight: SessionCoachInsight): String {
        return when (insight.streamingPhase) {
            SessionCoachStreamPhase.QUEUED -> "Local Gemma is queued. You can keep reviewing frames."
            SessionCoachStreamPhase.PREFILL -> "Local Gemma is reading the compact evidence."
            SessionCoachStreamPhase.STREAMING -> {
                "Local Gemma is writing the coach summary."
            }
            SessionCoachStreamPhase.VALIDATING -> "Checking Local Gemma response before showing it."
            SessionCoachStreamPhase.COMPLETE -> "Local Gemma summary is ready."
            SessionCoachStreamPhase.IDLE -> "Local Gemma is generating your session summary."
        }
    }

    private fun publishModelReadinessDebug(
        reason: String,
        backend: String,
        fallback: Boolean,
        fallbackReason: String,
    ) {
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(getApplication())
        val snapshot = ModelReadinessSnapshot.from(
            liteRtModelPath = modelPath,
            backend = backend,
            fallback = fallback,
            fallbackReason = fallbackReason,
        )
        GemmaFitDebugApi.updateState(
            section = "model_readiness",
            data = mapOf(
                "reason" to reason,
                "status" to snapshot.status.name.lowercase(),
                "label" to snapshot.label,
                "model_path" to (modelPath ?: ""),
                "model_file_name" to snapshot.modelFileName,
                "model_size_bytes" to snapshot.modelSizeBytes,
                "backend" to snapshot.backend,
                "fallback_reason" to snapshot.fallbackReason,
            ),
        )
    }

    private fun sessionCoachStateMap(
        eventKey: String,
        insight: SessionCoachInsight,
        error: String,
    ): Map<String, Any> {
        return mapOf(
            "trigger" to CoachTriggerPolicy.MODE,
            "event_key" to eventKey,
            "backend" to insight.backend,
            "function" to insight.functionName,
            "model_status" to insight.modelStatus.name.lowercase(),
            "fallback" to insight.fallback,
            "error" to error,
            "inference_time_ms" to insight.inferenceTimeMs,
            "selection_basis" to insight.selectionBasis,
            "evidence_refs" to insight.evidenceRefs,
            "headline" to insight.headline,
            "what_i_saw" to insight.whatISaw,
            "why_it_matters" to insight.whyItMatters,
            "not_judged" to insight.notJudged,
            "next_focus" to insight.nextFocus,
            "streaming_phase" to insight.streamingPhase.name.lowercase(),
            "streaming_text" to insight.streamingText,
            "stream_token_count" to insight.streamTokenCount,
            "first_token_time_ms" to (insight.firstTokenTimeMs ?: 0L),
            "constrained_decoding" to insight.constrainedDecoding,
        )
    }

    private fun String.isKnownExercise(): Boolean {
        return isNotBlank() && this != "unknown"
    }

    override fun onCleared() {
        processingJob?.cancel()
        activeCoachInferenceJob?.cancel()
        activeEarlyVideoVisualContextJob?.cancel()
        activeLiveCueInferenceJob?.cancel()
        resetAsyncLiveVisionSidecar()
        reviewRecoveryJob?.cancel()
        reviewBitmapRestoreJob?.cancel()
        videoProcessor?.release()
        closePoseLandmarker()
        clearProcessedFrames()
        personDetector.close()
        coachInferenceRouter.close()
        coachVoice?.shutdown()
        super.onCleared()
    }
}
