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
        initPoseLandmarkerAsync()

        processingJob = viewModelScope.launch {
            _state.update { it.copy(phase = VideoPhase.Processing(0f), subPhase = "loading_model", subPhaseProgress = 0f) }

            // Estimate total frames from video duration
            var estimatedTotalFrames = 0
            withContext(Dispatchers.IO) {
                var retries = 0
                while (poseLandmarker == null && retries < 30) {
                    if (poseInitFailed) {
                        _state.update { it.copy(phase = VideoPhase.Error("PoseLandmarker failed to initialize")) }
                        return@withContext
                    }
                    _state.update { it.copy(phase = VideoPhase.Processing(retries / 30f * 0.1f), subPhase = "loading_model", subPhaseProgress = retries / 30f) }
                    kotlinx.coroutines.delay(100)
                    retries++
                }
                if (poseLandmarker == null) {
                    _state.update { it.copy(phase = VideoPhase.Error("PoseLandmarker not ready after 3s")) }
                    return@withContext
                }
                // Quick scan to estimate frame count
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(getApplication(), uri)
                    val durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    // 30fps video, sampled every 3rd frame = ~10 extracted frames per second
                    estimatedTotalFrames = ((durationMs / 1000f) * 10f).toInt().coerceAtLeast(1)
                } catch (_: Exception) {
                    estimatedTotalFrames = 100
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }

            _state.update { it.copy(totalFrames = estimatedTotalFrames, progress = 0.1f, subPhase = "decoding", subPhaseProgress = 0f) }

            Log.d(TAG, "Starting video processing, estimated $estimatedTotalFrames frames")
            val processor = VideoProcessor(
                context = getApplication(),
                poseLandmarker = poseLandmarker,
                sampleEveryNFrames = 3,
            )
            videoProcessor = processor

            val processingStartMs = System.currentTimeMillis()
            var poseHits = 0
            var poseMisses = 0

            try {
                processor.processVideo(uri).collect { result ->
                    val elapsed = (System.currentTimeMillis() - processingStartMs) / 1000f
                    val realProgress = (result.frameIndex.toFloat() / estimatedTotalFrames).coerceIn(0f, 0.99f)
                    val fps = if (elapsed > 0.5f) result.frameIndex / elapsed else 0f
                    val eta = if (fps > 0) ((estimatedTotalFrames - result.frameIndex) / fps).toInt() else 0

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
                            subPhase = "detecting",
                            subPhaseProgress = realProgress,
                        )
                    }
                    if (result.landmarks != null && result.bitmap != null) {
                        processLandmarks(
                            result.landmarks,
                            result.frameIndex,
                            result.timestampMs,
                            result.bitmap,
                            result.bitmapWidth,
                            result.bitmapHeight,
                        )
                    }
                }
                val finalFrameCount = _state.value.totalFrames
                Log.d(TAG, "Video processing complete. Frames: $finalFrameCount, Landmarks present: ${_live.value.poseLandmarks.isNotEmpty()}")

                // Show first frame 
                if (processedFrames.isNotEmpty()) {
                    showFrame(0)
                }

                _state.update { s -> s.copy(phase = VideoPhase.Complete(finalFrameCount)) }
                _sessionSummary.value = buildSessionSummary()

                // No need for separate thumbnail anymore; processedFrames has all bitmaps
            } catch (e: Exception) {
                Log.e(TAG, "Video processing error: ${e.message}", e)
                _state.update { it.copy(phase = VideoPhase.Error(e.message ?: "Processing failed")) }
            }
        }
    }

    // ── Camera frame ────────────────────────────────────────────────────

    fun onCameraFrame(result: com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult) {
        if (result.landmarks().isEmpty()) return
        val landmarks = result.landmarks()[0]
        if (landmarks.size != 33) return
        val floatArray = FloatArray(99)
        val poseData = mutableListOf<PoseLandmarkData>()
        for (i in 0 until 33) {
            floatArray[i * 3] = landmarks[i].x()
            floatArray[i * 3 + 1] = landmarks[i].y()
            floatArray[i * 3 + 2] = landmarks[i].visibility().orElse(1.0f)
            poseData.add(PoseLandmarkData(
                landmarks[i].x(), landmarks[i].y(), 0f,
                landmarks[i].visibility().orElse(1.0f)
            ))
        }
        _live.value = _live.value.copy(poseLandmarks = poseData)
        viewModelScope.launch(Dispatchers.Default) {
            processLandmarks(
                floatArray = floatArray,
                frameIndex = totalFramesAnalyzed++,
                timestampMs = System.currentTimeMillis() - sessionStartMs,
            )
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
        _live.value = _live.value.copy(
            poseLandmarks = frame.landmarks,
            poseTrajectory = trajectoryFor(index),
            videoPreview = frame.bitmap ?: _live.value.videoPreview,
            videoPreviewWidth = frame.bitmapWidth,
            videoPreviewHeight = frame.bitmapHeight,
            currentFrameIndex = index,
            currentFrameTimestampMs = frame.timestampMs,
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
    ) {
        if (result.landmarks.isEmpty()) {
            Log.w(TAG, "Frame $frameIndex: no pose landmarks detected")
            return
        }
        val first = result.landmarks.firstOrNull() ?: return
        if (first.size != 33) {
            Log.w(TAG, "Frame $frameIndex: expected 33 landmarks, got ${first.size}")
            return
        }
        val floatArray = FloatArray(99)
        val poseData = first.mapIndexed { i, lm ->
            floatArray[i * 3] = lm.x
            floatArray[i * 3 + 1] = lm.y
            floatArray[i * 3 + 2] = lm.visibility
            PoseLandmarkData(lm.x, lm.y, lm.z, lm.visibility)
        }
        // Store frame metadata (bitmap lives in LRU cache, not here)
        processedFrames.add(ProcessedFrame(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            bitmap = bitmap,
            bitmapWidth = bmpWidth,
            bitmapHeight = bmpHeight,
            landmarks = poseData,
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
            videoPreview = bitmap,
            videoPreviewWidth = bmpWidth,
            videoPreviewHeight = bmpHeight,
            currentFrameTimestampMs = timestampMs,
            totalFramesAnalyzed = processedFrames.size,
        )
        processLandmarks(floatArray, frameIndex, timestampMs)
    }

    // ── Core processing (called for both camera and video) ──────────────

    private fun trajectoryFor(index: Int, window: Int = 18): List<List<PoseLandmarkData>> {
        if (processedFrames.isEmpty() || index !in processedFrames.indices) return emptyList()
        val start = (index - window + 1).coerceAtLeast(0)
        return processedFrames.subList(start, index + 1).map { it.landmarks }
    }

    private fun processLandmarks(
        floatArray: FloatArray,
        frameIndex: Int = 0,
        timestampMs: Long = System.currentTimeMillis() - sessionStartMs,
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
                        trustFlags = listOf("LOW_CONFIDENCE"),
                    )
                    _live.value = _live.value.copy(
                        activeWarnings = listOf(SafetyWarning(0, "warn_poor_visibility", result.gateReason, "low")),
                        formScore = 0,
                        trustMatrix = trustMatrix,
                        evidenceCard = evidenceCard,
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
                    )
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

                    // Generate coach message (LLM if available, mock fallback)
                    val (msg, priority) = if (frameIndex % 30 == 0) {
                        tryCallLLM(pattern, result.safetyJson, muscle?.pattern ?: "", exercise, warnings, qualityFlags, notApplicableFlags)
                    } else {
                        _lastCoachMessage?.let { it to (_lastCoachPriority ?: "low") }
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
        currentFrameIdx = 0
        // Recycle all remaining bitmaps
        processedFrames.forEach { it.bitmap?.recycle() }
        processedFrames.clear()
        _live.value = LiveWorkoutState(source = _state.value.source)
        _sessionSummary.value = SessionSummary()
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
