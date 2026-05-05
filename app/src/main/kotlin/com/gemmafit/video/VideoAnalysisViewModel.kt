package com.gemmafit.video

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

class VideoAnalysisViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "GemmaFit.VM"
        private const val TARGET_ANALYSIS_INTERVAL_MS = 125L
        private const val PREVIEW_ANALYSIS_INTERVAL_MS = 250L
        private const val PREVIEW_LONG_SIDE = 384
        private const val FULL_LONG_SIDE = 512
        private const val MAX_POSE_CANDIDATES = 4
        private const val FULL_POSE_CANDIDATES = 4
        private const val SUBJECT_LOST_FRAMES = 5
        private const val SUBJECT_MIN_VISIBILITY = 0.35f
        private const val SUBJECT_MATCH_THRESHOLD = 0.25f
        private const val AUTO_LOCK_MIN_SCORE = 0.62f
        private const val AUTO_LOCK_MARGIN = 0.12f
        private const val AUTO_LOCK_STABLE_FRAMES = 2
    }

    // ── Core state ─────────────────────────────────────────────────────
    private val _state = MutableStateFlow(VideoAnalysisState())
    val state: StateFlow<VideoAnalysisState> = _state.asStateFlow()

    private var poseLandmarker: PoseLandmarker? = null
    private var videoProcessor: VideoProcessor? = null
    private var processingJob: Job? = null
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
    }

    // ── Live workout state (single flow for WorkoutScreen) ─────────────
    private val _live = MutableStateFlow(LiveWorkoutState())
    val live: StateFlow<LiveWorkoutState> = _live.asStateFlow()

    private val poseLandmarkerLock = Any()
    private val sessionDataLock = Any()
    private val processedFramesLock = Any()

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
    private var sessionCapabilityContract = CapabilityContract()
    private val sessionEvidenceRefs = linkedSetOf<String>()
    private var lastCoachMessage = ""
    private var currentFrameIdx = 0
    private val kinematicsMutex = Mutex()
    private val coachInferenceRouter = CoachInferenceRouter(application)
    private var activeCoachInferenceJob: Job? = null
    private var _lastCoachMessage: String? = null
    private var _lastCoachPriority: String? = null
    private var lastCoachInsight: CoachInsight = CoachInsight()
    private var cleanFrameStreak = 0
    private var manualSubjectLock = false
    private var pendingSubjectTap: Pair<Float, Float>? = null
    private var lockedSubject: PoseCandidate? = null
    private var lockedSubjectTrackId: Int? = null
    private var pendingAutoSubject: PoseCandidate? = null
    private var pendingAutoSubjectFrames = 0
    private var nextSubjectTrackId = 1
    private var lostSubjectFrames = 0
    private val temporalAnalyzer = TemporalMotionAnalyzer()
    private val motionTraceAnalyzer = MotionTraceAnalyzer()
    private val repRecords = mutableListOf<RepRecord>()

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
        val coachMessage: String = "",
        val coachPriority: String = "low",
    )
    private val processedFrames = mutableListOf<ProcessedFrame>()

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

    private fun processedFrameCount(): Int = synchronized(processedFramesLock) {
        processedFrames.size
    }

    private fun hasProcessedFrame(index: Int): Boolean = synchronized(processedFramesLock) {
        index in processedFrames.indices
    }

    private fun frameAtOrNull(index: Int): ProcessedFrame? = synchronized(processedFramesLock) {
        processedFrames.getOrNull(index)
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
        coachVoice?.configure(settings)
    }

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
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(getApplication(), uri)
            val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ((durationMs.toFloat() / intervalMs.coerceAtLeast(1L).toFloat()).toInt() + 1).coerceAtLeast(1)
        } catch (_: Exception) {
            100
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
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
        _state.update {
            it.copy(source = VideoSource.VideoFile(uri.toString(), displayName), phase = VideoPhase.Idle)
        }
    }

    fun processVideo(uri: Uri) {
        val previousJob = processingJob
        processingJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            sessionStartMs = System.currentTimeMillis()
            resetSessionData()

            try {
                val source = _state.value.source
                GemmaFitDebugApi.record(
                    category = "video",
                    message = "process_video_start",
                    data = mapOf(
                        "source" to when (source) {
                            is VideoSource.VideoFile -> source.displayName
                            VideoSource.Camera -> "camera"
                        },
                    ),
                )
                _state.update {
                    it.copy(
                        phase = VideoPhase.Processing(0f),
                        subPhase = "preview_loading",
                        subPhaseProgress = 0f,
                    )
                }

                val previewLandmarker = createVideoPoseLandmarker(MAX_POSE_CANDIDATES)
                if (previewLandmarker == null) {
                    _state.update { it.copy(phase = VideoPhase.Error("PoseLandmarker failed to initialize")) }
                    return@launch
                }
                replacePoseLandmarker(previewLandmarker)

                val previewFrames = estimateAnalyzedFrames(uri, PREVIEW_ANALYSIS_INTERVAL_MS)
                runVideoPass(
                    uri = uri,
                    pass = VideoAnalysisPass.PREVIEW,
                    estimatedTotalFrames = previewFrames,
                    intervalMs = PREVIEW_ANALYSIS_INTERVAL_MS,
                    longSide = PREVIEW_LONG_SIDE,
                    maxPoses = MAX_POSE_CANDIDATES,
                    runNativeMetrics = false,
                )

                // Preview complete — build early summary for instant feedback
                _live.value = _live.value.copy(
                    isPreviewData = true,
                    analysisStage = "Preview complete",
                )
                _sessionSummary.value = buildSessionSummary().copy(isPreviewData = true)

                resetForFullAnalysisPass()
                _state.update {
                    it.copy(
                        phase = VideoPhase.Analyzing(0, estimateAnalyzedFrames(uri, TARGET_ANALYSIS_INTERVAL_MS)),
                        subPhase = "full_analysis",
                        subPhaseProgress = 0f,
                    )
                }
                val fullLandmarker = createVideoPoseLandmarker(FULL_POSE_CANDIDATES)
                if (fullLandmarker == null) {
                    _state.update { it.copy(phase = VideoPhase.Error("Full analysis PoseLandmarker failed")) }
                    return@launch
                }
                replacePoseLandmarker(fullLandmarker)

                val fullFrames = estimateAnalyzedFrames(uri, TARGET_ANALYSIS_INTERVAL_MS)
                runVideoPass(
                    uri = uri,
                    pass = VideoAnalysisPass.FULL,
                    estimatedTotalFrames = fullFrames,
                    intervalMs = TARGET_ANALYSIS_INTERVAL_MS,
                    longSide = FULL_LONG_SIDE,
                    maxPoses = FULL_POSE_CANDIDATES,
                    runNativeMetrics = true,
                )

                val finalFrameCount = processedFrameCount()
                Log.d(TAG, "Video processing complete. Frames: $finalFrameCount, Landmarks present: ${_live.value.poseLandmarks.isNotEmpty()}")
                GemmaFitDebugApi.record(
                    category = "video",
                    message = "process_video_complete",
                    data = mapOf(
                        "frames" to finalFrameCount,
                        "pose_present" to _live.value.poseLandmarks.isNotEmpty(),
                        "total_reps" to _live.value.repCount,
                    ),
                )
                _state.update { s -> s.copy(phase = VideoPhase.Complete(finalFrameCount), subPhase = "complete", subPhaseProgress = 1f) }
                _live.value = _live.value.copy(
                    analysisStage = "Full analysis complete",
                    isPreviewData = false,
                    fullProgress = 1f,
                )
                val summaryContext = SessionCoachRenderer.contextFrom(buildSessionSummary())
                sessionCoachInsight = SessionCoachRenderer.render(summaryContext)
                val finalSummary = buildSessionSummary().copy(isPreviewData = false)
                _sessionSummary.value = finalSummary
                scheduleSessionCoachInference(
                    eventKey = "session_summary|$finalFrameCount|${finalSummary.totalReps}",
                    context = summaryContext,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Video processing error: ${e.message}", e)
                GemmaFitDebugApi.record(
                    category = "video",
                    message = "process_video_error",
                    data = mapOf("error" to (e.message ?: "unknown")),
                )
                _state.update { it.copy(phase = VideoPhase.Error(e.message ?: "Processing failed")) }
            }
        }
    }

    // ── Camera frame ────────────────────────────────────────────────────

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
        var poseHits = 0
        var poseMisses = 0
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

            val elapsed = (System.currentTimeMillis() - passStartMs) / 1000f
            val realProgress = (result.frameIndex.toFloat() / estimatedTotalFrames).coerceIn(0f, 0.99f)
            val fps = if (elapsed > 0.5f) (result.frameIndex + 1) / elapsed else 0f
            val eta = if (fps > 0f) ((estimatedTotalFrames - result.frameIndex) / fps).toInt() else 0

            if (result.landmarks != null && result.landmarks.landmarks.isNotEmpty()) {
                poseHits++
            } else {
                poseMisses++
            }
            val hitRate = if (poseHits + poseMisses > 0) {
                poseHits.toFloat() / (poseHits + poseMisses)
            } else 0f

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
            // During Full pass, broadcast progress so UI can show mini progress bar
            if (pass == VideoAnalysisPass.FULL) {
                _live.value = _live.value.copy(fullProgress = realProgress)
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
                "hit_rate" to if (poseHits + poseMisses > 0) {
                    poseHits.toFloat() / (poseHits + poseMisses)
                } else {
                    0f
                },
            ),
        )
    }

    fun onCameraFrame(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult) {
        val candidates = candidatesFromLandmarkerResult(result)
        val selection = resolveSubjectSelection(candidates)
        if (selection.candidate == null) {
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

        val floatArray = selection.candidate.landmarks.toFloat99()
        _live.value = _live.value.copy(
            poseLandmarks = selection.candidate.landmarks,
            poseCandidates = candidates,
            activeSubjectIndex = selection.activeIndex,
            activeSubjectTrackId = selection.trackId,
            subjectLockStatus = selection.status,
            subjectTrustFlags = selection.trustFlags,
        )
        viewModelScope.launch(Dispatchers.Default) {
            val (frameIndex, frameTimestampMs) = synchronized(sessionDataLock) {
                val nextFrameIndex = totalFramesAnalyzed++
                nextFrameIndex to (System.currentTimeMillis() - sessionStartMs)
            }
            processLandmarks(
                floatArray = floatArray,
                frameIndex = frameIndex,
                timestampMs = frameTimestampMs,
                subjectTrustFlags = selection.trustFlags,
            )
        }
    }

    fun selectSubjectAt(normalizedX: Float, normalizedY: Float) {
        pendingSubjectTap = normalizedX.coerceIn(0f, 1f) to normalizedY.coerceIn(0f, 1f)
        manualSubjectLock = true

        if (hasProcessedFrame(currentFrameIdx)) {
            showFrame(currentFrameIdx)
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
    }

    fun resetToCamera() {
        val previousJob = processingJob
        processingJob = null
        viewModelScope.launch {
            previousJob?.cancelAndJoin()
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
        val nextIdx = (currentFrameIdx + 1) % count
        Log.d(TAG, "goToNextFrame: $currentFrameIdx -> $nextIdx ($count total)")
        showFrame(nextIdx)
    }

    fun goToPrevFrame() {
        val count = processedFrameCount()
        if (count == 0) return
        val prevIdx = if (currentFrameIdx <= 0) count - 1 else currentFrameIdx - 1
        Log.d(TAG, "goToPrevFrame: $currentFrameIdx -> $prevIdx")
        showFrame(prevIdx)
    }

    fun goToFrame(index: Int) {
        val count = processedFrameCount()
        if (count == 0) return
        showFrame(index.coerceIn(0, count - 1))
    }

    fun showFrameAtTimestamp(timestampMs: Long) {
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
                candidate
            }
        } ?: return
        if (match != currentFrameIdx) showFrame(match)
    }

    private fun showFrame(index: Int) {
        val frame = frameAtOrNull(index) ?: return
        currentFrameIdx = index
        val selection = if (frame.poseCandidates.isNotEmpty()) {
            resolveSubjectSelection(frame.poseCandidates)
        } else {
            SubjectSelection(
                candidate = frame.landmarks.takeIf { it.isNotEmpty() }?.let { candidateFromPoseData(it) },
                activeIndex = frame.activeSubjectIndex,
                trackId = frame.activeSubjectTrackId,
                status = frame.subjectLockStatus,
                trustFlags = frame.subjectTrustFlags,
                reason = "",
            )
        }

        val displayLandmarks = selection.candidate?.landmarks ?: emptyList()
        val displayStatus = selection.status
        val displayTrustFlags = selection.trustFlags
        if (selection.candidate != null && frame.poseCandidates.isNotEmpty()) {
            updateProcessedFrameAt(index) {
                it.copy(
                    landmarks = selection.candidate.landmarks,
                    activeSubjectIndex = selection.activeIndex,
                    activeSubjectTrackId = selection.trackId,
                    subjectLockStatus = displayStatus,
                    subjectTrustFlags = displayTrustFlags,
                )
            }
        }
        _live.value = _live.value.copy(
            poseLandmarks = displayLandmarks,
            poseTrajectory = if (displayLandmarks.isNotEmpty()) trajectoryFor(index) else emptyList(),
            videoPreview = frame.bitmap ?: _live.value.videoPreview,
            videoPreviewWidth = frame.bitmapWidth,
            videoPreviewHeight = frame.bitmapHeight,
            currentFrameIndex = index,
            currentFrameTimestampMs = frame.timestampMs,
            poseCandidates = frame.poseCandidates,
            activeSubjectIndex = selection.activeIndex ?: frame.activeSubjectIndex,
            activeSubjectTrackId = selection.trackId ?: frame.activeSubjectTrackId,
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
            coachMessage = frame.coachMessage,
            coachPriority = frame.coachPriority,
        )

        if (selection.candidate == null) {
            publishSubjectGate(
                candidates = frame.poseCandidates,
                status = displayStatus,
                frameIndex = frame.frameIndex,
                timestampMs = frame.timestampMs,
                reason = selection.reason,
                trustFlags = displayTrustFlags,
            )
        } else if (frame.exercise == "unknown" && frame.templateMetrics.isEmpty()) {
            processLandmarks(
                floatArray = selection.candidate.landmarks.toFloat99(),
                frameIndex = frame.frameIndex,
                timestampMs = frame.timestampMs,
                subjectTrustFlags = displayTrustFlags,
            )
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
        subjectTrustFlags: List<String> = emptyList(),
    ) {
        updateProcessedFrameByFrameIndex(frameIndex) { frame ->
            frame.copy(
                exercise = exercise,
                exerciseConfidence = confidence,
                movementPhase = movementPhase,
                warnings = warnings,
                qualityFlags = flags,
                templateMetrics = metrics,
                trustMatrix = trustMatrix,
                evidenceCard = evidenceCard,
                coachMessage = coachMsg,
                coachPriority = coachPriority,
                subjectTrustFlags = subjectTrustFlags.ifEmpty { frame.subjectTrustFlags },
            )
        }
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
        val selection = resolveSubjectSelection(emptyList())
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
                subjectTrustFlags = selection.trustFlags,
            )
        )
        _live.value = _live.value.copy(
            poseLandmarks = emptyList(),
            poseTrajectory = emptyList(),
            poseCandidates = emptyList(),
            activeSubjectIndex = null,
            activeSubjectTrackId = selection.trackId,
            subjectLockStatus = selection.status,
            subjectTrustFlags = selection.trustFlags,
            analysisStage = if (pass == VideoAnalysisPass.PREVIEW) "Preview analysis running" else "Full analysis running",
            videoPreview = bitmap,
            videoPreviewWidth = bmpWidth,
            videoPreviewHeight = bmpHeight,
            currentFrameTimestampMs = timestampMs,
            totalFramesAnalyzed = frameCount,
        )
        publishSubjectGate(
            candidates = emptyList(),
            status = selection.status,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            reason = reason.ifBlank { selection.reason },
            trustFlags = selection.trustFlags,
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
        val candidates = candidatesFromVideoResult(result)
        if (candidates.isEmpty()) {
            Log.w(TAG, "Frame $frameIndex: no valid 33-landmark candidates")
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
        val selection = resolveSubjectSelection(candidates)
        val poseData = selection.candidate?.landmarks ?: emptyList()
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
                subjectTrustFlags = selection.trustFlags,
            )
        )
        // Update live state with just the landmarks + bitmap for this frame
        _live.value = _live.value.copy(
            poseLandmarks = poseData,
            poseTrajectory = if (poseData.isNotEmpty()) trajectoryFor(frameCount - 1) else emptyList(),
            poseCandidates = candidates,
            activeSubjectIndex = selection.activeIndex,
            activeSubjectTrackId = selection.trackId,
            subjectLockStatus = selection.status,
            subjectTrustFlags = selection.trustFlags,
            analysisStage = if (pass == VideoAnalysisPass.PREVIEW) "Preview analysis running" else "Full analysis running",
            videoPreview = bitmap,
            videoPreviewWidth = bmpWidth,
            videoPreviewHeight = bmpHeight,
            currentFrameTimestampMs = timestampMs,
            totalFramesAnalyzed = frameCount,
        )
        if (selection.candidate == null) {
            publishSubjectGate(
                candidates = candidates,
                status = selection.status,
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                reason = selection.reason,
                trustFlags = selection.trustFlags,
            )
            return
        }
        if (!runNativeMetrics) {
            return
        }
        processLandmarks(
            floatArray = selection.candidate.landmarks.toFloat99(),
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            subjectTrustFlags = selection.trustFlags,
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
                candidateFromPoseData(poseData)
            }
    }

    private fun candidatesFromVideoResult(result: VideoPoseResult): List<PoseCandidate> {
        return result.landmarks
            .take(MAX_POSE_CANDIDATES)
            .mapNotNull { landmarks ->
                val poseData = landmarks.map { lm ->
                    PoseLandmarkData(lm.x, lm.y, lm.z, lm.visibility)
                }
                candidateFromPoseData(poseData)
            }
    }

    private fun hasRenderablePose(landmarks: List<PoseLandmarkData>): Boolean {
        return PosePresenceGate.canRender(landmarks, { it.x }, { it.y }, { it.visibility })
    }

    private fun clearLostLockedSubject() {
        lockedSubject = null
        lockedSubjectTrackId = null
        manualSubjectLock = false
        pendingSubjectTap = null
        pendingAutoSubject = null
        pendingAutoSubjectFrames = 0
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

    private fun resolveSubjectSelection(candidates: List<PoseCandidate>): SubjectSelection {
        val visibleCandidates = candidates
            .take(MAX_POSE_CANDIDATES)
            .filter { hasRenderablePose(it.landmarks) }

        if (visibleCandidates.isEmpty()) {
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            lostSubjectFrames += 1
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
                lockedSubjectTrackId = trackId
                lockedSubject = selected.copy(trackId = trackId)
                pendingAutoSubject = null
                pendingAutoSubjectFrames = 0
                lostSubjectFrames = 0
                return SubjectSelection(
                    candidate = lockedSubject,
                    activeIndex = sourceIndex,
                    trackId = trackId,
                    status = SubjectLockStatus.LOCKED,
                    trustFlags = subjectTrustFlagsFor(candidates, SubjectLockStatus.LOCKED),
                    reason = "",
                )
            }
        }

        if (lockedSubject != null) {
            val scored = visibleCandidates.map { candidate ->
                candidate.copy(trackScore = scoreSubjectMatch(lockedSubject!!, candidate))
            }
            val best = scored.maxByOrNull { it.trackScore }
            if (best != null && best.trackScore >= SUBJECT_MATCH_THRESHOLD) {
                val sourceIndex = candidates.indexOfFirst { it.landmarks === best.landmarks }
                    .takeIf { it >= 0 }
                val status = if (manualSubjectLock) SubjectLockStatus.LOCKED else SubjectLockStatus.AUTO_LOCKED
                val trackId = lockedSubjectTrackId ?: nextSubjectTrackId++
                lockedSubjectTrackId = trackId
                lockedSubject = best.copy(trackId = trackId)
                pendingAutoSubject = null
                pendingAutoSubjectFrames = 0
                lostSubjectFrames = 0
                return SubjectSelection(
                    candidate = lockedSubject,
                    activeIndex = sourceIndex,
                    trackId = trackId,
                    status = status,
                    trustFlags = subjectTrustFlagsFor(candidates, status),
                    reason = "",
                )
            }
            if (!manualSubjectLock) {
                lockedSubject = null
                lockedSubjectTrackId = null
            } else {
                lostSubjectFrames += 1
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
            lockedSubjectTrackId = trackId
            lockedSubject = selected.copy(trackId = trackId)
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            lostSubjectFrames = 0
            return SubjectSelection(
                candidate = lockedSubject,
                activeIndex = candidates.indexOf(selected).takeIf { it >= 0 },
                trackId = trackId,
                status = SubjectLockStatus.SINGLE_AUTO,
                trustFlags = subjectTrustFlagsFor(candidates, SubjectLockStatus.SINGLE_AUTO),
                reason = "",
            )
        }

        val autoSelection = selectAutoCandidate(visibleCandidates)
        if (autoSelection != null) {
            val selected = autoSelection.first
            val sourceIndex = candidates.indexOf(selected).takeIf { it >= 0 }
            val stableCandidate = pendingAutoSubject?.let {
                scoreSubjectMatch(it, selected) >= SUBJECT_MATCH_THRESHOLD
            } == true
            pendingAutoSubjectFrames = if (stableCandidate) pendingAutoSubjectFrames + 1 else 1
            pendingAutoSubject = selected.copy(trackScore = autoSelection.second)
            if (pendingAutoSubjectFrames < AUTO_LOCK_STABLE_FRAMES) {
                return SubjectSelection(
                    candidate = null,
                    activeIndex = null,
                    trackId = null,
                    status = SubjectLockStatus.NEEDS_SELECTION,
                    trustFlags = listOf("MULTI_PERSON", "AUTO_SELECTION_PENDING"),
                    reason = "auto_subject_stabilizing",
                )
            }
            val trackId = nextSubjectTrackId++
            lockedSubjectTrackId = trackId
            lockedSubject = selected.copy(trackScore = autoSelection.second, trackId = trackId)
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            lostSubjectFrames = 0
            resetSubjectTemporalState()
            return SubjectSelection(
                candidate = lockedSubject,
                activeIndex = sourceIndex,
                trackId = trackId,
                status = SubjectLockStatus.AUTO_LOCKED,
                trustFlags = subjectTrustFlagsFor(candidates, SubjectLockStatus.AUTO_LOCKED),
                reason = "",
            )
        }

        return SubjectSelection(
            candidate = null,
            activeIndex = null,
            trackId = null,
            status = SubjectLockStatus.NEEDS_SELECTION,
            trustFlags = listOf("MULTI_PERSON", "NEEDS_SELECTION"),
            reason = "multi_person_selection_required",
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
        val centerDist = distance(previous.centerX, previous.centerY, candidate.centerX, candidate.centerY)
        val centerScore = (1f - centerDist / 0.45f).coerceIn(0f, 1f)
        val areaBase = maxOf(previous.bbox.area, candidate.bbox.area, 0.001f)
        val areaScore = (1f - kotlin.math.abs(previous.bbox.area - candidate.bbox.area) / areaBase)
            .coerceIn(0f, 1f)
        val keypointScore = (1f - meanKeypointDistance(previous.landmarks, candidate.landmarks) / 0.35f)
            .coerceIn(0f, 1f)
        val visibilityScore = candidate.avgVisibility.coerceIn(0f, 1f)
        return 0.42f * centerScore + 0.38f * keypointScore + 0.12f * areaScore + 0.08f * visibilityScore
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

        _live.value = _live.value.copy(
            poseLandmarks = emptyList(),
            poseTrajectory = emptyList(),
            poseCandidates = candidates.filter { hasRenderablePose(it.landmarks) },
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
        )
        coachVoice?.speakCue(flag.id)
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

    private fun processLandmarks(
        floatArray: FloatArray,
        frameIndex: Int = 0,
        timestampMs: Long = System.currentTimeMillis() - sessionStartMs,
        subjectTrustFlags: List<String> = emptyList(),
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!kinematicsMutex.tryLock()) return@launch
            try {
            try {
                val jsonOutput = KinematicsBridge.processFrame(floatArray, null, 0.6f)
                val result = KinematicsBridge.parseResult(jsonOutput)

                if (result.gateBlocked) {
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
                    val trustMatrix = buildTrustMatrix(
                        qualityFlags = listOf(
                            QualityFlag(
                                id = "pose_confidence",
                                status = "LOW_CONFIDENCE",
                                value = 0f,
                                threshold = 0.6f,
                                evidence = "confidence_gate",
                                reason = result.gateReason,
                            )
                        ),
                        notApplicableFlags = emptyList(),
                    )
                    val evidenceCard = EvidenceCard(
                        verdict = "LOW_CONFIDENCE",
                        reason = result.gateReason,
                        evidence = listOf(EvidenceItem("gate", "blocked")),
                        trustFlags = (listOf("LOW_CONFIDENCE") + subjectTrustFlags).distinct(),
                    )
                    _live.value = _live.value.copy(
                        activeWarnings = listOf(SafetyWarning(0, "warn_poor_visibility", result.gateReason, "low")),
                        formScore = 0,
                        trustMatrix = trustMatrix,
                        evidenceCard = evidenceCard,
                        subjectTrustFlags = subjectTrustFlags,
                    )
                    publishDebugAnalysisState(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        exercise = "unknown",
                        score = 0,
                        movementPhase = "unknown",
                        warnings = _live.value.activeWarnings,
                        qualityFlags = listOf(
                            QualityFlag(
                                id = "pose_confidence",
                                status = "LOW_CONFIDENCE",
                                value = 0f,
                                threshold = 0.6f,
                                evidence = "confidence_gate",
                                reason = result.gateReason,
                            )
                        ),
                        notApplicableFlags = emptyList(),
                        subjectTrustFlags = subjectTrustFlags,
                        coachInsight = lastCoachInsight,
                    )
                    coachVoice?.speakFunctionCall("warn_poor_visibility")
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
                            templateMetrics = parsedMotion.metrics
                            qualityFlags = parsedMotion.qualityFlags
                            notApplicableFlags = parsedMotion.notApplicable
                            capabilityContract = parsedMotion.capabilityContract
                            evidenceDag = parsedMotion.evidenceDag
                        } catch (_: Exception) {}
                    }

                    val temporal = temporalAnalyzer.addSample(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        exercise = exercise,
                        metrics = templateMetrics,
                    )
                    val movementPhase = temporal.movementPhase
                    val temporalFlags = listOfNotNull(temporal.rapidFlag)
                    templateMetrics = mergeTemporalMetrics(templateMetrics, temporal.temporalMetrics)
                    qualityFlags = mergeQualityFlags(qualityFlags, temporalFlags)

                    val trace = motionTraceAnalyzer.addSample(
                        frameIndex = frameIndex,
                        timestampMs = timestampMs,
                        exercise = exercise,
                        landmarks = floatArray.toPoseLandmarkData(),
                        temporal = temporal,
                        qualityFlags = qualityFlags + notApplicableFlags,
                    )
                    trace.repTraceSummary?.let { summary ->
                        templateMetrics = mergeTraceMetrics(templateMetrics, summary)
                    }
                    qualityFlags = mergeQualityFlags(qualityFlags, listOfNotNull(trace.traceFlag))

                    val activeQualityFlags = qualityFlags.filter {
                        it.status == "CRITICAL" || it.status == "WARNING"
                    }
                    val warnings = if (activeQualityFlags.isNotEmpty()) {
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

                    val score = formScoreFromQuality(qualityFlags, violations)
                    val trustMatrix = buildTrustMatrix(qualityFlags, notApplicableFlags)
                    val frameEvidenceRefs = evidenceDag.nodes.map { it.id }
                        .ifEmpty { capabilityContract.evidenceRefs }
                        .filter { it.isNotBlank() }
                        .distinct()
                    val evidenceCard = buildEvidenceCard(
                        exercise = exercise,
                        exerciseConfidence = exConfidence,
                        metrics = templateMetrics,
                        qualityFlags = qualityFlags,
                        notApplicableFlags = notApplicableFlags,
                        capabilityContract = capabilityContract,
                        evidenceRefs = frameEvidenceRefs,
                    ).withSubjectTrustFlags(subjectTrustFlags)
                    val limitedJudgment = qualityFlags.any {
                        it.status == "VIEW_LIMITED" || it.status == "LOW_CONFIDENCE"
                    }
                    cleanFrameStreak = if (warnings.isEmpty() && !limitedJudgment) {
                        cleanFrameStreak + 1
                    } else {
                        0
                    }
                    val repHistorySnapshot = synchronized(sessionDataLock) {
                        sessionCapabilityContract = mergeCapabilityContracts(
                            sessionCapabilityContract,
                            capabilityContract,
                        )
                        sessionEvidenceRefs.addAll(frameEvidenceRefs.take(48))
                        temporal.completedRep?.let { rep ->
                            if (repRecords.none { it.repNumber == rep.repNumber }) {
                                repRecords.add(
                                    rep.copy(
                                        formQuality = score / 100f,
                                        hadViolations = warnings.isNotEmpty(),
                                        traceSummary = trace.repTraceSummary,
                                    )
                                )
                            }
                        }
                        repRecords.toList()
                    }
                    val coachContext = CoachContext(
                        exercise = exercise,
                        movementPhase = movementPhase,
                        pattern = pattern,
                        repCount = temporal.repCount,
                        cleanStreak = cleanFrameStreak,
                        metrics = templateMetrics,
                        muscle = muscle,
                        warnings = warnings,
                        qualityFlags = qualityFlags,
                        notApplicableFlags = notApplicableFlags,
                        evidenceCard = evidenceCard,
                    )

                    // Live coaching remains deterministic; local Gemma is summary-only.
                    val coachInsight = CoachInsightRenderer.render(coachContext)
                    val msg = coachInsight.message
                    val priority = coachInsight.priority
                    coachMsg = msg
                    coachPriority = priority
                    _lastCoachMessage = msg
                    _lastCoachPriority = priority
                    lastCoachInsight = coachInsight

                    // Update live state
                    _live.value = _live.value.copy(
                        repCount = temporal.repCount,
                        formScore = score,
                        activeWarnings = warnings,
                        currentPattern = pattern,
                        movementPhase = movementPhase,
                        currentMuscleFocus = muscle,
                        detectedExercise = exercise,
                        exerciseConfidence = exConfidence,
                        exerciseBasis = exBasis,
                        templateMetrics = templateMetrics,
                        coachMessage = coachMsg,
                        coachPriority = coachPriority,
                        coachInsight = coachInsight,
                        qualityFlags = qualityFlags + notApplicableFlags,
                        trustMatrix = trustMatrix,
                        evidenceCard = evidenceCard,
                        subjectTrustFlags = subjectTrustFlags,
                        repHistory = repHistorySnapshot,
                    )
                    recordSessionQualityCounts(qualityFlags, notApplicableFlags)
                    if (
                        shouldSampleDebugFrame(frameIndex) ||
                        warnings.isNotEmpty() ||
                        qualityFlags.any { it.status == "VIEW_LIMITED" || it.status == "LOW_CONFIDENCE" }
                    ) {
                        publishDebugAnalysisState(
                            frameIndex = frameIndex,
                            timestampMs = timestampMs,
                            exercise = exercise,
                            score = score,
                            movementPhase = movementPhase,
                            warnings = warnings,
                            qualityFlags = qualityFlags,
                            notApplicableFlags = notApplicableFlags,
                            subjectTrustFlags = subjectTrustFlags,
                            coachInsight = coachInsight,
                            traceSummary = trace.repTraceSummary,
                        )
                    }

                    // Update the stored frame with kinematics results
                    updateProcessedFrame(
                        frameIndex = frameIndex,
                        exercise = exercise,
                        confidence = exConfidence,
                        warnings = warnings,
                        flags = qualityFlags + notApplicableFlags,
                        metrics = templateMetrics,
                        movementPhase = movementPhase,
                        trustMatrix = trustMatrix,
                        evidenceCard = evidenceCard,
                        coachMsg = coachMsg,
                        coachPriority = coachPriority,
                        subjectTrustFlags = subjectTrustFlags,
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

                        exerciseDetectionCounts[exercise] = (exerciseDetectionCounts[exercise] ?: 0) + 1
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
                    if (warnings.isNotEmpty()) {
                        val mostSevere = warnings.maxByOrNull { if (it.severity == "high") 2 else 1 }
                        mostSevere?.let { warning ->
                            coachVoice?.speakFunctionCall(warning.functionName)
                        }
                    } else if (frameIndex % 90 == 0) {
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
                _live.value = _live.value.copy(
                    activeWarnings = emptyList(),
                    qualityFlags = _live.value.qualityFlags + failureFlag,
                )
            }
            } finally {
                kinematicsMutex.unlock()
            }
        }
    }

    // ── Session summary ─────────────────────────────────────────────────

    private fun buildSessionSummary(): SessionSummary = synchronized(sessionDataLock) {
        val duration = if (sessionStartMs > 0) {
            ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()
        } else 0

        val avgScore = if (formScoreHistory.isNotEmpty()) {
            formScoreHistory.map { it.score }.average().toFloat()
        } else 100f

        val countedMainExercise = exerciseDetectionCounts.maxByOrNull { it.value }?.key ?: "unknown"
        val liveMainExercise = _live.value.detectedExercise.takeIf { it.isKnownExercise() }
        val mainEx = liveMainExercise ?: countedMainExercise
        val mainConf = if (exerciseDetectionCounts.isNotEmpty()) {
            (exerciseDetectionCounts[mainEx] ?: 0).toFloat() / exerciseDetectionCounts.values.sum().coerceAtLeast(1)
        } else if (liveMainExercise != null) {
            _live.value.exerciseConfidence
        } else 0f

        return SessionSummary(
            totalFrames = totalFramesAnalyzed,
            totalReps = _live.value.repCount,
            avgFormScore = avgScore,
            durationSeconds = duration,
            detection = ExerciseDetection(
                mainExercise = mainEx,
                confidence = mainConf,
                detectedExercises = exerciseDetectionCounts.toMap(),
            ),
            safetyEvents = safetyEventLog.toList(),
            formScores = formScoreHistory.toList(),
            viewLimitedCount = viewLimitedCount,
            lowConfidenceCount = lowConfidenceCount,
            notApplicableCounts = notApplicableCounts.toMap(),
            muscleFocusDistribution = muscleFocusCounts.toMap(),
            repHistory = repRecords.toList(),
            coachTips = coachTipsSet.toList(),
            aiInsights = coachInsights.toList(),
            sessionCoachInsight = sessionCoachInsight,
            capabilityContract = sessionCapabilityContract,
            evidenceRefs = sessionEvidenceRefs.toList(),
        )
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
            repRecords.clear()
            temporalAnalyzer.reset()
            motionTraceAnalyzer.reset()
            lastCoachMessage = ""
            cleanFrameStreak = 0
            activeCoachInferenceJob?.cancel()
            _lastCoachMessage = null
            _lastCoachPriority = null
            lastCoachInsight = CoachInsight()
            manualSubjectLock = false
            pendingSubjectTap = null
            lockedSubject = null
            lockedSubjectTrackId = null
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            nextSubjectTrackId = 1
            lostSubjectFrames = 0
            currentFrameIdx = 0
        }
        // Recycle all remaining bitmaps
        clearProcessedFrames()
        _live.value = LiveWorkoutState(source = _state.value.source)
        _sessionSummary.value = SessionSummary()
    }

    private fun resetForFullAnalysisPass() {
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
            repRecords.clear()
            temporalAnalyzer.reset()
            motionTraceAnalyzer.reset()
            lastCoachMessage = ""
            cleanFrameStreak = 0
            activeCoachInferenceJob?.cancel()
            _lastCoachMessage = null
            _lastCoachPriority = null
            lastCoachInsight = CoachInsight()
            manualSubjectLock = false
            pendingSubjectTap = null
            lockedSubject = null
            lockedSubjectTrackId = null
            pendingAutoSubject = null
            pendingAutoSubjectFrames = 0
            nextSubjectTrackId = 1
            lostSubjectFrames = 0
            currentFrameIdx = 0
        }
        clearProcessedFrames()
        _live.value = LiveWorkoutState(source = _state.value.source).copy(
            analysisStage = "Full analysis starting",
            isPreviewData = false,
        )
    }

    private fun resetSubjectTemporalState() {
        synchronized(sessionDataLock) {
            temporalAnalyzer.reset()
            motionTraceAnalyzer.reset()
            repRecords.clear()
            cleanFrameStreak = 0
            activeCoachInferenceJob?.cancel()
            lastCoachInsight = CoachInsight()
            _lastCoachMessage = null
            _lastCoachPriority = null
        }
        _live.value = _live.value.copy(
            repCount = 0,
            movementPhase = "unknown",
            templateMetrics = emptyMap(),
            activeWarnings = emptyList(),
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun shouldSampleDebugFrame(frameIndex: Int): Boolean {
        return frameIndex < 3 || frameIndex % 15 == 0
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
        score: Int,
        movementPhase: String,
        warnings: List<SafetyWarning>,
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
        subjectTrustFlags: List<String>,
        coachInsight: CoachInsight,
        traceSummary: RepTraceSummary? = null,
    ) {
        GemmaFitDebugApi.updateState(
            section = "video_analysis",
            data = mapOf(
                "frame" to frameIndex,
                "timestamp_ms" to timestampMs,
                "exercise" to exercise,
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
                "rep_trace_summary" to traceSummary?.toDebugMap(),
            ),
        )
    }

    private data class ParsedMotionReport(
        val exercise: String = "unknown",
        val confidence: Float = 0f,
        val basis: List<String> = emptyList(),
        val metrics: Map<String, Float> = emptyMap(),
        val qualityFlags: List<QualityFlag> = emptyList(),
        val notApplicable: List<QualityFlag> = emptyList(),
        val capabilityContract: CapabilityContract = CapabilityContract(),
        val evidenceDag: EvidenceDag = EvidenceDag(),
    )

    private fun parseMotionReport(root: JSONObject): ParsedMotionReport {
        return ParsedMotionReport(
            exercise = root.optString("exercise", "unknown"),
            confidence = root.optDouble("exercise_confidence", 0.0).toFloat(),
            basis = parseStringArray(root.optJSONArray("exercise_basis")),
            metrics = parseFloatMap(root.optJSONObject("template_metrics")),
            qualityFlags = parseQualityFlags(root.optJSONArray("quality_flags")),
            notApplicable = parseQualityFlags(root.optJSONArray("not_applicable")),
            capabilityContract = parseCapabilityContract(root.optJSONObject("capability_contract")),
            evidenceDag = parseEvidenceDag(root.optJSONObject("evidence_dag")),
        )
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val values = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            values.add(arr.optString(i))
        }
        return values
    }

    private fun parseFloatMap(obj: JSONObject?): Map<String, Float> {
        if (obj == null) return emptyMap()
        val values = mutableMapOf<String, Float>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            values[key] = obj.optDouble(key, 0.0).toFloat()
        }
        return values
    }

    private fun parseQualityFlags(arr: JSONArray?): List<QualityFlag> {
        if (arr == null) return emptyList()
        val flags = mutableListOf<QualityFlag>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            flags.add(
                QualityFlag(
                    id = obj.optString("id", "quality_gate"),
                    status = obj.optString("status", "MONITOR"),
                    value = obj.optDouble("value", 0.0).toFloat(),
                    threshold = obj.optDouble("threshold", 0.0).toFloat(),
                    evidence = obj.optString("evidence", "prototype_threshold"),
                    reason = obj.optString("reason", ""),
                    rule = obj.optInt("rule", 0),
                    joint = obj.optString("joint", ""),
                )
            )
        }
        return flags
    }

    private fun parseCapabilityContract(obj: JSONObject?): CapabilityContract {
        if (obj == null) return CapabilityContract()
        return CapabilityContract(
            canJudge = parseCapabilityItems(obj.optJSONArray("can_judge")),
            cannotJudge = parseCapabilityItems(obj.optJSONArray("cannot_judge")),
        )
    }

    private fun parseCapabilityItems(arr: JSONArray?): List<CapabilityJudgment> {
        if (arr == null) return emptyList()
        val items = mutableListOf<CapabilityJudgment>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            items.add(
                CapabilityJudgment(
                    metric = obj.optString("metric", ""),
                    reason = obj.optString("reason", ""),
                    confidenceCeiling = obj.optDouble("confidence_ceiling", 0.0).toFloat(),
                    requiredEvidence = parseStringArray(obj.optJSONArray("required_evidence")),
                    evidenceRefs = parseStringArray(obj.optJSONArray("evidence_refs")),
                )
            )
        }
        return items.filter { it.metric.isNotBlank() }
    }

    private fun parseEvidenceDag(obj: JSONObject?): EvidenceDag {
        if (obj == null) return EvidenceDag()
        return EvidenceDag(
            nodes = parseEvidenceDagNodes(obj.optJSONArray("nodes")),
            edges = parseEvidenceDagEdges(obj.optJSONArray("edges")),
        )
    }

    private fun parseEvidenceDagNodes(arr: JSONArray?): List<EvidenceDagNode> {
        if (arr == null) return emptyList()
        val nodes = mutableListOf<EvidenceDagNode>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            nodes.add(
                EvidenceDagNode(
                    id = obj.optString("id", ""),
                    type = obj.optString("type", ""),
                    label = obj.optString("label", ""),
                    metric = obj.optString("metric", ""),
                    value = obj.optDouble("value", 0.0).toFloat(),
                    unit = obj.optString("unit", ""),
                    confidence = obj.optDouble("confidence", 0.0).toFloat(),
                    status = obj.optString("status", ""),
                    sourceModule = obj.optString("source_module", ""),
                    sourceFunction = obj.optString("source_function", ""),
                    frameRange = obj.optString("frame_range", ""),
                    landmarkRefs = parseStringArray(obj.optJSONArray("landmark_refs")),
                )
            )
        }
        return nodes.filter { it.id.isNotBlank() }
    }

    private fun parseEvidenceDagEdges(arr: JSONArray?): List<EvidenceDagEdge> {
        if (arr == null) return emptyList()
        val edges = mutableListOf<EvidenceDagEdge>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            edges.add(
                EvidenceDagEdge(
                    from = obj.optString("from", ""),
                    to = obj.optString("to", ""),
                    relation = obj.optString("relation", ""),
                )
            )
        }
        return edges.filter { it.from.isNotBlank() && it.to.isNotBlank() }
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
    ) {
        if (!CoachTriggerPolicy.shouldTrigger(CoachTriggerEvent.FULL_ANALYSIS_COMPLETE)) return
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
                ),
            )
            val result = coachInferenceRouter.runSessionInference(context)
            val insight = SessionCoachRenderer.render(context, result)
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
                    "selection_basis" to insight.selectionBasis,
                    "evidence_refs" to insight.evidenceRefs,
                    "headline" to insight.headline,
                    "next_focus" to insight.nextFocus,
                ),
            )
            GemmaFitDebugApi.updateState(
                section = "coach_summary",
                data = mapOf(
                    "trigger" to CoachTriggerPolicy.MODE,
                    "event_key" to eventKey,
                    "backend" to insight.backend,
                    "function" to insight.functionName,
                    "fallback" to insight.fallback,
                    "error" to result.errorMessage,
                    "inference_time_ms" to result.inferenceTimeMs,
                    "selection_basis" to insight.selectionBasis,
                    "evidence_refs" to insight.evidenceRefs,
                    "headline" to insight.headline,
                    "what_i_saw" to insight.whatISaw,
                    "why_it_matters" to insight.whyItMatters,
                    "not_judged" to insight.notJudged,
                    "next_focus" to insight.nextFocus,
                ),
            )
            sessionCoachInsight = insight
            _sessionSummary.value = buildSessionSummary()
        }
    }

    private fun String.isKnownExercise(): Boolean {
        return isNotBlank() && this != "unknown"
    }

    override fun onCleared() {
        processingJob?.cancel()
        activeCoachInferenceJob?.cancel()
        videoProcessor?.release()
        closePoseLandmarker()
        clearProcessedFrames()
        coachInferenceRouter.close()
        coachVoice?.shutdown()
        super.onCleared()
    }
}
