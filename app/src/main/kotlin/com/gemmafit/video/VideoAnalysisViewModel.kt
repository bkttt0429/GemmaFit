package com.gemmafit.video

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemmafit.jni.KinematicsBridge
import com.gemmafit.voice.CoachVoice
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        private const val EVIDENCE_DEBOUNCE_FRAMES = 3
        private const val LLM_COOLDOWN_MS = 2_000L
        private const val MAX_POSE_CANDIDATES = 4
        private const val FULL_POSE_CANDIDATES = 1
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
    }

    // ── Live workout state (single flow for WorkoutScreen) ─────────────
    private val _live = MutableStateFlow(LiveWorkoutState())
    val live: StateFlow<LiveWorkoutState> = _live.asStateFlow()

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
    private var lastCoachMessage = ""
    private var currentFrameIdx = 0
    private val kinematicsMutex = Mutex()
    private var _lastCoachMessage: String? = null
    private var _lastCoachPriority: String? = null
    private var lastEvidenceKey: String? = null
    private var pendingEvidenceKey: String? = null
    private var pendingEvidenceCount = 0
    private var lastLlmTimestampMs = Long.MIN_VALUE
    private var lastEventCoachMessage: String? = null
    private var lastEventCoachPriority: String? = null
    private var manualSubjectLock = false
    private var pendingSubjectTap: Pair<Float, Float>? = null
    private var lockedSubject: PoseCandidate? = null
    private var lockedSubjectTrackId: Int? = null
    private var pendingAutoSubject: PoseCandidate? = null
    private var pendingAutoSubjectFrames = 0
    private var nextSubjectTrackId = 1
    private var lostSubjectFrames = 0
    private val temporalAnalyzer = TemporalMotionAnalyzer()
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

    fun initPoseLandmarker(landmarker: PoseLandmarker) {
        poseLandmarker = landmarker
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
                poseLandmarker = PoseLandmarker.createFromOptions(getApplication(), options)
                poseInitFailed = false
                Log.d(TAG, "PoseLandmarker initialized successfully (IMAGE mode)")
            } catch (e: Exception) {
                poseInitFailed = true
                Log.e(TAG, "PoseLandmarker init failed: ${e.message}", e)
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
            PoseLandmarker.createFromOptions(getApplication(), options(com.google.mediapipe.tasks.core.Delegate.GPU))
        } catch (gpuError: Exception) {
            Log.w(TAG, "VIDEO PoseLandmarker GPU failed, trying CPU: ${gpuError.message}")
            try {
                PoseLandmarker.createFromOptions(getApplication(), options(com.google.mediapipe.tasks.core.Delegate.CPU))
            } catch (cpuError: Exception) {
                Log.e(TAG, "VIDEO PoseLandmarker CPU failed: ${cpuError.message}", cpuError)
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
        if (poseLandmarker != null) return true
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
        _state.update {
            it.copy(source = VideoSource.VideoFile(uri.toString(), displayName), phase = VideoPhase.Idle)
        }
    }

    fun processVideo(uri: Uri) {
        processingJob?.cancel()
        sessionStartMs = System.currentTimeMillis()
        resetSessionData()

        processingJob = viewModelScope.launch {
            try {
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
                poseLandmarker?.close()
                poseLandmarker = previewLandmarker

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
                poseLandmarker?.close()
                poseLandmarker = fullLandmarker

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

                val finalFrameCount = processedFrames.size
                Log.d(TAG, "Video processing complete. Frames: $finalFrameCount, Landmarks present: ${_live.value.poseLandmarks.isNotEmpty()}")
                _state.update { s -> s.copy(phase = VideoPhase.Complete(finalFrameCount), subPhase = "complete", subPhaseProgress = 1f) }
                _live.value = _live.value.copy(
                    analysisStage = "Full analysis complete",
                    isPreviewData = false,
                    fullProgress = 1f,
                )
                _sessionSummary.value = buildSessionSummary().copy(isPreviewData = false)
            } catch (e: Exception) {
                Log.e(TAG, "Video processing error: ${e.message}", e)
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
            poseLandmarker = poseLandmarker,
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
    }

    fun onCameraFrame(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult) {
        val candidates = candidatesFromLandmarkerResult(result)
        val selection = resolveSubjectSelection(candidates)
        if (selection.candidate == null) {
            if (selection.status == SubjectLockStatus.LOCKED) {
                _live.value = _live.value.copy(
                    poseCandidates = candidates,
                    activeSubjectIndex = null,
                    activeSubjectTrackId = selection.trackId,
                    subjectLockStatus = selection.status,
                    subjectTrustFlags = selection.trustFlags,
                    currentFrameTimestampMs = System.currentTimeMillis() - sessionStartMs,
                )
                return
            }
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
            processLandmarks(
                floatArray = floatArray,
                frameIndex = totalFramesAnalyzed++,
                timestampMs = System.currentTimeMillis() - sessionStartMs,
                subjectTrustFlags = selection.trustFlags,
            )
        }
    }

    fun selectSubjectAt(normalizedX: Float, normalizedY: Float) {
        pendingSubjectTap = normalizedX.coerceIn(0f, 1f) to normalizedY.coerceIn(0f, 1f)
        manualSubjectLock = true

        if (processedFrames.isNotEmpty() && currentFrameIdx in processedFrames.indices) {
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
        processingJob?.cancel()
        resetSessionData()
        _state.update { VideoAnalysisState(source = VideoSource.Camera) }
        _live.value = LiveWorkoutState(source = VideoSource.Camera)
        coachVoice?.stop()
    }

    fun cancelProcessing() {
        processingJob?.cancel()
        _state.update { it.copy(phase = VideoPhase.Idle) }
    }

    fun selectVideo() {
        _state.update { it.copy(phase = VideoPhase.Selecting) }
    }

    // ── Frame navigation ────────────────────────────────────────────────

    fun goToNextFrame() {
        if (processedFrames.isEmpty()) return
        val nextIdx = (currentFrameIdx + 1) % processedFrames.size
        Log.d(TAG, "goToNextFrame: $currentFrameIdx → $nextIdx (${processedFrames.size} total)")
        showFrame(nextIdx)
    }

    fun goToPrevFrame() {
        if (processedFrames.isEmpty()) return
        val prevIdx = if (currentFrameIdx <= 0) processedFrames.size - 1 else currentFrameIdx - 1
        Log.d(TAG, "goToPrevFrame: $currentFrameIdx → $prevIdx")
        showFrame(prevIdx)
    }

    fun goToFrame(index: Int) {
        showFrame(index.coerceIn(0, (processedFrames.size - 1).coerceAtLeast(0)))
    }

    fun showFrameAtTimestamp(timestampMs: Long) {
        if (processedFrames.isEmpty()) return

        var low = 0
        var high = processedFrames.lastIndex
        var match = 0
        while (low <= high) {
            val mid = (low + high) / 2
            if (processedFrames[mid].timestampMs <= timestampMs) {
                match = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        if (match != currentFrameIdx) showFrame(match)
    }

    private fun showFrame(index: Int) {
        if (index !in processedFrames.indices) return
        currentFrameIdx = index
        val frame = processedFrames[index]
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

        val displayLandmarks = selection.candidate?.landmarks ?: frame.landmarks
        val displayStatus = selection.status
        val displayTrustFlags = selection.trustFlags
        if (selection.candidate != null && frame.poseCandidates.isNotEmpty()) {
            processedFrames[index] = frame.copy(
                landmarks = selection.candidate.landmarks,
                activeSubjectIndex = selection.activeIndex,
                activeSubjectTrackId = selection.trackId,
                subjectLockStatus = displayStatus,
                subjectTrustFlags = displayTrustFlags,
            )
        }
        _live.value = _live.value.copy(
            poseLandmarks = displayLandmarks,
            poseTrajectory = trajectoryFor(index),
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

        if (selection.candidate == null && frame.poseCandidates.isNotEmpty()) {
            publishSubjectGate(
                candidates = frame.poseCandidates,
                status = displayStatus,
                frameIndex = frame.frameIndex,
                timestampMs = frame.timestampMs,
                reason = selection.reason,
                trustFlags = displayTrustFlags,
            )
        } else if (selection.candidate != null && frame.exercise == "unknown" && frame.templateMetrics.isEmpty()) {
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
        val idx = processedFrames.indexOfFirst { it.frameIndex == frameIndex }
        if (idx < 0) return
        processedFrames[idx] = processedFrames[idx].copy(
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
            subjectTrustFlags = subjectTrustFlags.ifEmpty { processedFrames[idx].subjectTrustFlags },
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
            return
        }
        val candidates = candidatesFromVideoResult(result)
        if (candidates.isEmpty()) {
            Log.w(TAG, "Frame $frameIndex: no valid 33-landmark candidates")
            return
        }
        val selection = resolveSubjectSelection(candidates)
        val poseData = selection.candidate?.landmarks ?: candidates.first().landmarks
        // Store frame metadata (bitmap lives in LRU cache, not here)
        processedFrames.add(ProcessedFrame(
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
        ))
        // Sliding window: recycle oldest frame's bitmap when > 60 frames
        if (processedFrames.size > 60) {
            processedFrames[processedFrames.size - 61].bitmap?.let {
                it.recycle()
                processedFrames[processedFrames.size - 61] = processedFrames[processedFrames.size - 61].copy(bitmap = null)
            }
        }
        // Update live state with just the landmarks + bitmap for this frame
        _live.value = _live.value.copy(
            poseLandmarks = poseData,
            poseTrajectory = trajectoryFor(processedFrames.lastIndex),
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
            totalFramesAnalyzed = processedFrames.size,
        )
        if (selection.candidate == null) {
            if (selection.status == SubjectLockStatus.LOCKED) {
                _live.value = _live.value.copy(
                    poseCandidates = candidates,
                    activeSubjectIndex = null,
                    activeSubjectTrackId = selection.trackId,
                    subjectLockStatus = selection.status,
                    subjectTrustFlags = selection.trustFlags,
                    currentFrameTimestampMs = timestampMs,
                )
                return
            }
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
        if (processedFrames.isEmpty() || index !in processedFrames.indices) return emptyList()
        val start = (index - window + 1).coerceAtLeast(0)
        return processedFrames.subList(start, index + 1).map { it.landmarks }
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
                        visibility = it.visibility().orElse(1.0f),
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

    private fun candidateFromPoseData(
        landmarks: List<PoseLandmarkData>,
        trackScore: Float = 0f,
    ): PoseCandidate? {
        if (landmarks.size < 33) return null
        val visible = landmarks.filter { it.visibility > 0.15f }
        val points = visible.ifEmpty { landmarks }
        val minX = points.minOf { it.x }.coerceIn(0f, 1f)
        val minY = points.minOf { it.y }.coerceIn(0f, 1f)
        val maxX = points.maxOf { it.x }.coerceIn(0f, 1f)
        val maxY = points.maxOf { it.y }.coerceIn(0f, 1f)
        val bbox = PoseBoundingBox(minX, minY, maxX, maxY)
        val torso = listOf(11, 12, 23, 24).mapNotNull { landmarks.getOrNull(it) }
            .filter { it.visibility > 0.15f }
        val centerX = if (torso.isNotEmpty()) torso.map { it.x }.average().toFloat() else (bbox.minX + bbox.maxX) / 2f
        val centerY = if (torso.isNotEmpty()) torso.map { it.y }.average().toFloat() else (bbox.minY + bbox.maxY) / 2f
        val avgVisibility = landmarks.map { it.visibility }.average().toFloat()
        return PoseCandidate(
            landmarks = landmarks,
            bbox = bbox,
            centerX = centerX.coerceIn(0f, 1f),
            centerY = centerY.coerceIn(0f, 1f),
            avgVisibility = avgVisibility,
            trackScore = trackScore,
        )
    }

    private fun resolveSubjectSelection(candidates: List<PoseCandidate>): SubjectSelection {
        val visibleCandidates = candidates
            .take(MAX_POSE_CANDIDATES)
            .filter { it.avgVisibility >= SUBJECT_MIN_VISIBILITY || it.bbox.area > 0.01f }

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
            return SubjectSelection(
                candidate = null,
                activeIndex = null,
                trackId = lockedSubjectTrackId,
                status = SubjectLockStatus.SUBJECT_LOST,
                trustFlags = listOf("SUBJECT_LOST"),
                reason = "locked_subject_lost",
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
            return SubjectSelection(
                candidate = null,
                activeIndex = null,
                trackId = lockedSubjectTrackId,
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
        val flag = when (status) {
            SubjectLockStatus.SUBJECT_LOST -> QualityFlag(
                id = "subject_lost",
                status = "LOW_CONFIDENCE",
                value = lostSubjectFrames.toFloat(),
                threshold = SUBJECT_LOST_FRAMES.toFloat(),
                evidence = "subject_lock",
                reason = reason.ifBlank { "locked_subject_lost" },
            )
            else -> QualityFlag(
                id = "multi_person_selection",
                status = "VIEW_LIMITED",
                value = candidates.size.toFloat(),
                threshold = 1f,
                evidence = "subject_lock",
                reason = reason.ifBlank { "multi_person_selection_required" },
            )
        }
        val matrix = buildTrustMatrix(listOf(flag), emptyList())
        val card = EvidenceCard(
            verdict = flag.status,
            reason = flag.reason,
            evidence = listOf(
                EvidenceItem("people", candidates.size.toString()),
                EvidenceItem("subject", status.name.lowercase()),
            ),
            trustFlags = (listOf("${flag.status}:${flag.id}") + trustFlags).distinct(),
        )
        val message = if (status == SubjectLockStatus.SUBJECT_LOST) {
            "Locked subject lost. Keep your full body visible or tap again."
        } else {
            "Multiple people detected. Tap yourself to start."
        }

        val displayLandmarks = if (status == SubjectLockStatus.SUBJECT_LOST) {
            _live.value.poseLandmarks
        } else {
            emptyList()
        }
        _live.value = _live.value.copy(
            poseLandmarks = displayLandmarks,
            poseCandidates = candidates,
            activeSubjectIndex = null,
            activeSubjectTrackId = null,
            subjectLockStatus = status,
            subjectTrustFlags = trustFlags,
            activeWarnings = emptyList(),
            qualityFlags = listOf(flag),
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
                    val evidenceCard = buildEvidenceCard(
                        exercise = exercise,
                        exerciseConfidence = exConfidence,
                        metrics = templateMetrics,
                        qualityFlags = qualityFlags,
                        notApplicableFlags = notApplicableFlags,
                    ).withSubjectTrustFlags(subjectTrustFlags)
                    temporal.completedRep?.let { rep ->
                        if (repRecords.none { it.repNumber == rep.repNumber }) {
                            repRecords.add(
                                rep.copy(
                                    formQuality = score / 100f,
                                    hadViolations = warnings.isNotEmpty(),
                                )
                            )
                        }
                    }

                    // FrameHint is always available; Gemma coaching is event/key-driven.
                    val evidenceKey = buildEvidenceKey(
                        exercise = exercise,
                        movementPhase = movementPhase,
                        repCount = temporal.repCount,
                        metrics = templateMetrics,
                        qualityFlags = qualityFlags,
                        notApplicableFlags = notApplicableFlags,
                        exerciseConfidence = exConfidence,
                    )
                    val shouldCallLlm = shouldRefreshEventCoaching(
                        evidenceKey = evidenceKey,
                        timestampMs = timestampMs,
                        qualityFlags = qualityFlags,
                        notApplicableFlags = notApplicableFlags,
                    )
                    val (msg, priority) = if (shouldCallLlm) {
                        val generated = tryCallLLM(
                            pattern,
                            result.safetyJson,
                            muscle?.pattern ?: "",
                            exercise,
                            warnings,
                            qualityFlags,
                            notApplicableFlags,
                        )
                        recordEventCoaching(evidenceKey, timestampMs, generated.first, generated.second)
                        generated
                    } else {
                        lastEventCoachMessage?.let { it to (lastEventCoachPriority ?: "low") }
                            ?: _lastCoachMessage?.let { it to (_lastCoachPriority ?: "low") }
                            ?: generateMockCoachMessage(exercise, warnings, qualityFlags, notApplicableFlags)
                    }
                    coachMsg = msg
                    coachPriority = priority
                    _lastCoachMessage = msg
                    _lastCoachPriority = priority

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
                        qualityFlags = qualityFlags + notApplicableFlags,
                        trustMatrix = trustMatrix,
                        evidenceCard = evidenceCard,
                        subjectTrustFlags = subjectTrustFlags,
                    )

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
                    if (frameIndex % 30 == 0) {
                        _sessionSummary.value = buildSessionSummary()
                    }
                }
            } catch (e: Exception) {
                _live.value = _live.value.copy(activeWarnings = emptyList())
            }
            } finally {
                kinematicsMutex.unlock()
            }
        }
    }

    // ── Session summary ─────────────────────────────────────────────────

    private fun buildSessionSummary(): SessionSummary {
        val duration = if (sessionStartMs > 0) {
            ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()
        } else 0

        val avgScore = if (formScoreHistory.isNotEmpty()) {
            formScoreHistory.map { it.score }.average().toFloat()
        } else 100f

        val mainEx = exerciseDetectionCounts.maxByOrNull { it.value }?.key ?: "unknown"
        val mainConf = if (exerciseDetectionCounts.isNotEmpty()) {
            (exerciseDetectionCounts[mainEx] ?: 0).toFloat() / exerciseDetectionCounts.values.sum().coerceAtLeast(1)
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
            muscleFocusDistribution = muscleFocusCounts.toMap(),
            repHistory = repRecords.toList(),
            coachTips = coachTipsSet.toList(),
        )
    }

    private fun resetSessionData() {
        totalFramesAnalyzed = 0
        sessionStartMs = System.currentTimeMillis()
        formScoreHistory.clear()
        safetyEventLog.clear()
        exerciseDetectionCounts.clear()
        muscleFocusCounts.clear()
        coachTipsSet.clear()
        repRecords.clear()
        temporalAnalyzer.reset()
        lastCoachMessage = ""
        _lastCoachMessage = null
        _lastCoachPriority = null
        lastEvidenceKey = null
        pendingEvidenceKey = null
        pendingEvidenceCount = 0
        lastLlmTimestampMs = Long.MIN_VALUE
        lastEventCoachMessage = null
        lastEventCoachPriority = null
        manualSubjectLock = false
        pendingSubjectTap = null
        lockedSubject = null
        lockedSubjectTrackId = null
        pendingAutoSubject = null
        pendingAutoSubjectFrames = 0
        nextSubjectTrackId = 1
        lostSubjectFrames = 0
        currentFrameIdx = 0
        // Recycle all remaining bitmaps
        processedFrames.forEach { it.bitmap?.recycle() }
        processedFrames.clear()
        _live.value = LiveWorkoutState(source = _state.value.source)
        _sessionSummary.value = SessionSummary()
    }

    private fun resetSubjectTemporalState() {
        temporalAnalyzer.reset()
        repRecords.clear()
        lastEvidenceKey = null
        pendingEvidenceKey = null
        pendingEvidenceCount = 0
        lastLlmTimestampMs = Long.MIN_VALUE
        lastEventCoachMessage = null
        lastEventCoachPriority = null
        _lastCoachMessage = null
        _lastCoachPriority = null
        _live.value = _live.value.copy(
            repCount = 0,
            movementPhase = "unknown",
            templateMetrics = emptyMap(),
            activeWarnings = emptyList(),
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private data class ParsedMotionReport(
        val exercise: String = "unknown",
        val confidence: Float = 0f,
        val basis: List<String> = emptyList(),
        val metrics: Map<String, Float> = emptyMap(),
        val qualityFlags: List<QualityFlag> = emptyList(),
        val notApplicable: List<QualityFlag> = emptyList(),
    )

    private fun parseMotionReport(root: JSONObject): ParsedMotionReport {
        return ParsedMotionReport(
            exercise = root.optString("exercise", "unknown"),
            confidence = root.optDouble("exercise_confidence", 0.0).toFloat(),
            basis = parseStringArray(root.optJSONArray("exercise_basis")),
            metrics = parseFloatMap(root.optJSONObject("template_metrics")),
            qualityFlags = parseQualityFlags(root.optJSONArray("quality_flags")),
            notApplicable = parseQualityFlags(root.optJSONArray("not_applicable")),
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
            "exercise_template" -> "warn_poor_visibility"
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

    private fun mergeQualityFlags(
        staticFlags: List<QualityFlag>,
        temporalFlags: List<QualityFlag>,
    ): List<QualityFlag> {
        if (temporalFlags.isEmpty()) return staticFlags
        val temporalIds = temporalFlags.map { it.id }.toSet()
        return staticFlags.filterNot { it.id in temporalIds } + temporalFlags
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
    ): EvidenceCard {
        val active = qualityFlags.sortedWith(compareBy<QualityFlag> { statusPriority(it.status) })
        val top = active.firstOrNull()
        val verdict = top?.status ?: "OK"
        val reason = top?.reason?.ifBlank { top.id.replace("_", " ") }
            ?: "No active issue."

        val metricEvidence = metrics.entries.take(4).map { (key, value) ->
            EvidenceItem(
                label = key.replace("_", " "),
                value = "%.1f".format(value),
            )
        }
        val evidence = buildList {
            add(EvidenceItem("exercise", exercise.replace("_", " ")))
            add(EvidenceItem("confidence", "${(exerciseConfidence * 100).toInt()}%"))
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

    private fun buildEvidenceKey(
        exercise: String,
        movementPhase: String,
        repCount: Int,
        metrics: Map<String, Float>,
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
        exerciseConfidence: Float,
    ): String {
        val topFlag = qualityFlags
            .sortedWith(compareBy<QualityFlag> { statusPriority(it.status) }.thenBy { it.id })
            .firstOrNull()
        val verdict = topFlag?.status ?: "OK"
        val primaryMetric = topFlag?.id ?: metrics.keys.sorted().firstOrNull() ?: "none"
        val notApplicableSet = notApplicableFlags
            .map { it.id }
            .sorted()
            .joinToString("+")
            .ifBlank { "none" }

        return listOf(
            exercise.ifBlank { "unknown" },
            movementPhase.ifBlank { "unknown" },
            "rep$repCount",
            primaryMetric,
            severityBucket(topFlag),
            confidenceBucket(exerciseConfidence),
            topFlag?.id ?: "none",
            verdict,
            notApplicableSet,
            viewAngleBucket(qualityFlags, notApplicableFlags),
        ).joinToString("|")
    }

    private fun severityBucket(flag: QualityFlag?): String {
        if (flag == null) return "none"
        return when (flag.status) {
            "CRITICAL" -> "critical"
            "WARNING" -> {
                val ratio = if (flag.threshold != 0f) {
                    kotlin.math.abs(flag.value / flag.threshold)
                } else {
                    1f
                }
                if (ratio >= 1.5f) "high" else "medium"
            }
            "LOW_CONFIDENCE", "VIEW_LIMITED" -> "medium"
            "MONITOR" -> "low"
            else -> "low"
        }
    }

    private fun confidenceBucket(confidence: Float): String = when {
        confidence < 0.5f -> "conf_low"
        confidence < 0.8f -> "conf_mid"
        else -> "conf_high"
    }

    private fun viewAngleBucket(
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
    ): String {
        val allFlags = qualityFlags + notApplicableFlags
        return when {
            allFlags.any { it.status == "VIEW_LIMITED" || it.id.contains("view", ignoreCase = true) } -> "view_limited"
            allFlags.any {
                it.id.contains("knee_valgus", ignoreCase = true) ||
                    it.id.contains("fppa", ignoreCase = true)
            } -> "non_frontal_view"
            else -> "unknown_view"
        }
    }

    private fun shouldRefreshEventCoaching(
        evidenceKey: String,
        timestampMs: Long,
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
    ): Boolean {
        val hasEvent = qualityFlags.any {
            it.status == "CRITICAL" ||
                it.status == "WARNING" ||
                it.status == "MONITOR" ||
                it.status == "VIEW_LIMITED" ||
                it.status == "LOW_CONFIDENCE"
        } || notApplicableFlags.isNotEmpty()

        if (!hasEvent) return false

        if (pendingEvidenceKey == evidenceKey) {
            pendingEvidenceCount += 1
        } else {
            pendingEvidenceKey = evidenceKey
            pendingEvidenceCount = 1
        }

        if (pendingEvidenceCount < EVIDENCE_DEBOUNCE_FRAMES) return false
        if (lastEvidenceKey == evidenceKey) return false
        if (lastLlmTimestampMs != Long.MIN_VALUE && timestampMs - lastLlmTimestampMs < LLM_COOLDOWN_MS) {
            return false
        }
        return true
    }

    private fun recordEventCoaching(
        evidenceKey: String,
        timestampMs: Long,
        message: String,
        priority: String,
    ) {
        lastEvidenceKey = evidenceKey
        lastLlmTimestampMs = timestampMs
        lastEventCoachMessage = message
        lastEventCoachPriority = priority
    }

    private fun tryCallLLM(
        pattern: String,
        safetyJson: String,
        musclePattern: String,
        exercise: String,
        warnings: List<SafetyWarning>,
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
    ): Pair<String, String> {
        val modelPath = "/sdcard/Android/data/com.gemmafit/files/gemma4-e4b-q4.gguf"
        return try {
            if (!com.gemmafit.jni.LLMBridge.validateModel(modelPath)) {
                Log.d(TAG, "LLM model not found, using mock coach")
                return generateMockCoachMessage(exercise, warnings, qualityFlags, notApplicableFlags)
            }
            val jsonOutput = com.gemmafit.jni.LLMBridge.runInference(
                movementPatternJson = "{\"pattern\":\"$pattern\"}",
                safetyJson = safetyJson,
                muscleJson = "{\"pattern\":\"$musclePattern\"}",
                modelPath = modelPath,
            )
            val result = com.gemmafit.jni.LLMBridge.parseFunctionCall(jsonOutput)
            if (result.success && result.functionName.isNotEmpty()) {
                val msg = com.gemmafit.jni.LLMBridge.getCoachingMessage(result.functionName, result.argsJson)
                Log.d(TAG, "LLM coaching: ${result.functionName} in ${result.inferenceTimeMs}ms")
                msg to "medium"
            } else {
                generateMockCoachMessage(exercise, warnings, qualityFlags, notApplicableFlags)
            }
        } catch (e: Exception) {
            Log.w(TAG, "LLM inference failed: ${e.message}, falling back to mock")
            generateMockCoachMessage(exercise, warnings, qualityFlags, notApplicableFlags)
        }
    }

    private fun generateMockCoachMessage(
        exercise: String,
        warnings: List<SafetyWarning>,
        qualityFlags: List<QualityFlag>,
        notApplicableFlags: List<QualityFlag>,
    ): Pair<String, String> {
        val limited = qualityFlags.firstOrNull {
            it.status == "VIEW_LIMITED" || it.status == "LOW_CONFIDENCE"
        }
        if (limited != null) {
            return "View is limited, so I will avoid hard form judgments." to "medium"
        }

        if (warnings.isEmpty()) {
            val monitor = qualityFlags.firstOrNull { it.status == "MONITOR" }
            if (monitor != null) {
                val msg = when (monitor.id) {
                    "signed_distance_to_support", "com_offset" -> "Weight shift is being monitored. Re-center if it repeats."
                    "lunge_stability" -> "Lunge balance is uneven; keep the next rep controlled."
                    else -> "Movement quality looks usable, with one metric under watch."
                }
                return msg to "medium"
            }

            if (notApplicableFlags.isNotEmpty() && exercise == "unknown") {
                return "I need a clearer full-body view before selecting an exercise template." to "medium"
            }

            return when (exercise) {
                "squat" -> "Nice depth! Keep chest up." to "low"
                "push_up" -> "Good form! Keep body straight." to "low"
                "lunge" -> "Good lunge. Keep front knee over ankle." to "low"
                "deadlift" -> "Good hip hinge. Keep the bar close." to "low"
                else -> "Good movement quality!" to "low"
            }
        }
        val worst = warnings.maxByOrNull {
            if (it.severity == "high") 2 else 1
        } ?: return "Adjust your form." to "medium"

        val priority = if (worst.severity == "high") "high" else "medium"
        val msg = when (worst.functionName) {
            "correct_knee_alignment" -> "Push knees outward over toes."
            "correct_spinal_alignment" -> "Maintain a neutral spine."
            "correct_joint_angle" -> "Don't lock your joints."
            "correct_asymmetry" -> "Keep both sides balanced."
            "warn_com_offset" -> "Center your weight."
            "warn_rapid_movement" -> "Slow down, control the movement."
            "increase_range_of_motion" -> "Move through full range if comfortable."
            else -> "Check your form."
        }
        return msg to priority
    }

    override fun onCleared() {
        processedFrames.forEach { it.bitmap?.recycle() }
        super.onCleared()
        processingJob?.cancel()
        videoProcessor?.release()
        poseLandmarker?.close()
        coachVoice?.shutdown()
    }
}
