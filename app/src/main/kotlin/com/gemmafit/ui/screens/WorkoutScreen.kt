package com.gemmafit.ui.screens

import android.net.Uri
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemmafit.camera.CameraPreviewWithOverlay
import com.gemmafit.settings.AppSettings
import com.gemmafit.ui.localization.LocalAppStrings
import com.gemmafit.ui.overlay.PoseCueMapper
import com.gemmafit.ui.overlay.PoseLandmark
import com.gemmafit.ui.overlay.PoseOverlay
import com.gemmafit.ui.overlay.PoseOverlayState
import com.gemmafit.ui.screens.video.CoachPanel
import com.gemmafit.ui.screens.video.VideoAnalysisLayout
import com.gemmafit.ui.screens.video.VideoEmptyState
import com.gemmafit.ui.screens.video.VideoErrorState
import com.gemmafit.ui.screens.video.VideoLoadingState
import com.gemmafit.ui.screens.video.shouldRenderSecondarySkeletons
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.PoseLandmarkData
import com.gemmafit.video.SubjectLockStatus
import com.gemmafit.video.VideoAnalysisViewModel
import com.gemmafit.video.VideoPhase
import com.gemmafit.video.VideoSource
import kotlinx.coroutines.delay

@Composable
fun WorkoutScreen(
    onViewSummary: (com.gemmafit.video.SessionSummary) -> Unit,
    onOpenSettings: () -> Unit = {},
    settings: AppSettings = AppSettings(),
    initialVideoUri: Uri? = null,
    initialVideoLabel: String? = null,
    openVideoPickerOnStart: Boolean = false,
    onVideoPickerStartConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: VideoAnalysisViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val live by viewModel.live.collectAsState()
    var isPaused by remember { mutableStateOf(false) }
    var cameraImageWidth by remember { mutableStateOf(1080) }
    var cameraImageHeight by remember { mutableStateOf(1920) }
    var showTrajectory by rememberSaveable { mutableStateOf(false) }
    var videoAudioEnabled by rememberSaveable { mutableStateOf(false) }
    var videoAudioVolume by rememberSaveable { mutableStateOf(0.6f) }
    var videoPickerInFlight by remember { mutableStateOf(false) }
    var cameraLensFacing by rememberSaveable { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var consumedInitialVideoKey by rememberSaveable { mutableStateOf<String?>(null) }
    var completionReviewResetKey by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(settings) {
        viewModel.configureCoachVoice(settings)
    }

    LaunchedEffect(initialVideoUri) {
        val uri = initialVideoUri ?: return@LaunchedEffect
        val initialVideoKey = uri.toString()
        if (consumedInitialVideoKey == initialVideoKey) return@LaunchedEffect
        consumedInitialVideoKey = initialVideoKey
        isPaused = true
        viewModel.selectVideoForAnalysis(uri, initialVideoLabel ?: uri.toString())
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        videoPickerInFlight = false
        uri?.let {
            isPaused = true
            viewModel.selectVideoForAnalysis(it, it.toString())
        }
    }
    val launchVideoPicker = {
        if (!videoPickerInFlight) {
            videoPickerInFlight = true
            videoPickerLauncher.launch("video/*")
        }
    }

    LaunchedEffect(openVideoPickerOnStart) {
        if (!openVideoPickerOnStart) return@LaunchedEffect
        onVideoPickerStartConsumed()
        isPaused = true
        launchVideoPicker()
    }

    val isVideoMode = state.source is VideoSource.VideoFile
    val isCameraMode = state.source is VideoSource.Camera
    val isProcessing = state.phase is VideoPhase.Processing || state.phase is VideoPhase.Analyzing
    val videoUri = (state.source as? VideoSource.VideoFile)?.let { Uri.parse(it.uri) }
    val completedVideoFrameCount = (state.phase as? VideoPhase.Complete)?.frameCount ?: 0

    // Reset pause state when switching video sources to prevent old data flashing
    LaunchedEffect(state.source) {
        if (state.source is VideoSource.VideoFile) {
            isPaused = true
        }
    }

    LaunchedEffect(videoUri, completedVideoFrameCount) {
        if (videoUri == null || completedVideoFrameCount <= 0) return@LaunchedEffect
        val resetKey = "${videoUri}|$completedVideoFrameCount"
        if (completionReviewResetKey == resetKey) return@LaunchedEffect
        completionReviewResetKey = resetKey
        isPaused = true
        viewModel.goToFrame(0)
        delay(450L)
        isPaused = true
        viewModel.goToFrame(0)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        when {
            isCameraMode && !isPaused -> {
                // ── Camera live mode ──────────────────────────────────
                CameraLiveLayout(
                    live = live,
                    lensFacing = cameraLensFacing,
                    onCameraFrame = { result, h, w, frameLensFacing, frameBitmap ->
                        if (frameLensFacing == cameraLensFacing) {
                            cameraImageHeight = h
                            cameraImageWidth = w
                            viewModel.onCameraFrame(result, frameBitmap)
                        } else {
                            frameBitmap?.recycle()
                        }
                    },
                    poseOverlayState = rememberCameraPoseOverlayState(live, showTrajectory, cameraLensFacing),
                    imageW = cameraImageWidth,
                    imageH = cameraImageHeight,
                    onPickVideo = launchVideoPicker,
                    onOpenSettings = onOpenSettings,
                    showTrajectory = showTrajectory,
                    onToggleTrajectory = { showTrajectory = !showTrajectory },
                    onSwitchCamera = {
                        cameraLensFacing = nextCameraLensFacing(cameraLensFacing)
                        cameraImageWidth = 1080
                        cameraImageHeight = 1920
                        viewModel.onCameraLensSwitch()
                    },
                    onTogglePause = {
                        val willPause = !isPaused
                        if (willPause) {
                            viewModel.pinCurrentFrameForReview()
                        }
                        isPaused = willPause
                    },
                    onViewSummary = { onViewSummary(viewModel.sessionSummary.value) },
                    onSubjectTap = { x, y -> viewModel.selectSubjectAt(x, y) },
                )
            }

            else -> {
                // ── Video mode content router ─────────────────────────
                VideoModeContent(
                    state = state,
                    live = live,
                    isPaused = isPaused,
                    isProcessing = isProcessing,
                    videoUri = videoUri,
                    onPickVideo = launchVideoPicker,
                    onOpenSettings = onOpenSettings,
                    showTrajectory = showTrajectory,
                    onToggleTrajectory = { showTrajectory = !showTrajectory },
                    onResetCamera = { viewModel.resetToCamera() },
                    videoAudioEnabled = videoAudioEnabled,
                    videoAudioVolume = videoAudioVolume,
                    onToggleVideoAudio = {
                        if (videoAudioEnabled) {
                            videoAudioEnabled = false
                        } else {
                            if (videoAudioVolume <= 0.01f) {
                                videoAudioVolume = 0.6f
                            }
                            videoAudioEnabled = true
                        }
                    },
                    onVideoAudioVolumeChange = { value ->
                        val clamped = value.coerceIn(0f, 1f)
                        videoAudioVolume = clamped
                        videoAudioEnabled = clamped > 0.01f
                    },
                    onPrevFrame = {
                        isPaused = true
                        viewModel.goToPrevFrame()
                    },
                    onNextFrame = {
                        isPaused = true
                        viewModel.goToNextFrame()
                    },
                    onSeekFrame = { frame ->
                        val latestFrame = live.totalFramesAnalyzed - 1
                        if (isProcessing && latestFrame >= 0 && frame >= latestFrame) {
                            viewModel.goToLatestProcessedFrame()
                        } else {
                            isPaused = true
                            viewModel.goToFrame(frame)
                        }
                    },
                    onPlaybackPosition = { positionMs ->
                        if (!isPaused) {
                            viewModel.showFrameAtTimestamp(positionMs)
                        }
                    },
                    onPlaybackLimitReached = {
                        if (isPaused) {
                            viewModel.pinCurrentFrameForReview()
                        } else if (isProcessing) {
                            viewModel.goToLatestProcessedFrame(force = false)
                        } else {
                            viewModel.pinCurrentFrameForReview()
                            isPaused = true
                        }
                    },
                    onTogglePause = {
                        val willPause = !isPaused
                        if (willPause) {
                            viewModel.pinCurrentFrameForReview()
                        } else if (isProcessing) {
                            viewModel.goToLatestProcessedFrame()
                        } else {
                            viewModel.pinCurrentFrameForReview()
                        }
                        isPaused = willPause
                    },
                    onJumpToLatestFrame = {
                        viewModel.goToLatestProcessedFrame()
                        if (isProcessing) {
                            isPaused = false
                        }
                    },
                    onViewSummary = { onViewSummary(viewModel.sessionSummary.value) },
                    onSubjectTap = { x, y -> viewModel.selectSubjectAt(x, y) },
                    onReanalyzeSelectedSubject = { viewModel.reanalyzeSelectedSubject() },
                    onRetry = {
                        videoUri?.let { viewModel.processVideo(it) }
                    },
                )
            }
        }
    }
}

// ── Video Mode Router ───────────────────────────────────────────────

@Composable
private fun VideoModeContent(
    state: com.gemmafit.video.VideoAnalysisState,
    live: LiveWorkoutState,
    isPaused: Boolean,
    isProcessing: Boolean,
    videoUri: Uri?,
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    showTrajectory: Boolean,
    onToggleTrajectory: () -> Unit,
    onResetCamera: () -> Unit,
    videoAudioEnabled: Boolean,
    videoAudioVolume: Float,
    onToggleVideoAudio: () -> Unit,
    onVideoAudioVolumeChange: (Float) -> Unit,
    onPrevFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onJumpToLatestFrame: () -> Unit,
    onPlaybackPosition: (Long) -> Unit,
    onPlaybackLimitReached: () -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    onSubjectTap: (Float, Float) -> Unit,
    onReanalyzeSelectedSubject: () -> Unit,
    onRetry: () -> Unit,
) {
    when (state.phase) {
        is VideoPhase.Idle,
        is VideoPhase.Selecting -> {
            if (videoUri != null) {
                VideoLoadingState(
                    currentFrame = state.currentFrame,
                    totalFrames = state.totalFrames,
                    processingFps = state.processingFps,
                    poseHitRate = state.poseHitRate,
                    subPhase = state.subPhase.ifBlank { "video_loading" },
                    subPhaseProgress = state.subPhaseProgress.takeIf { it > 0f } ?: 0.03f,
                )
            } else {
                VideoEmptyState(
                    onPickVideo = onPickVideo,
                )
            }
        }

        is VideoPhase.Processing,
        is VideoPhase.Analyzing -> {
            VideoAnalysisLayout(
                live = live,
                isPaused = isPaused,
                isProcessing = isProcessing,
                videoUri = videoUri,
                analysisProgress = state.progress,
                analysisCurrentFrame = state.currentFrame,
                analysisTotalFrames = state.totalFrames,
                analysisLabel = state.subPhase,
                onPickVideo = onPickVideo,
                onOpenSettings = onOpenSettings,
                showTrajectory = showTrajectory,
                onToggleTrajectory = onToggleTrajectory,
                onResetCamera = onResetCamera,
                videoAudioEnabled = videoAudioEnabled,
                videoAudioVolume = videoAudioVolume,
                onToggleVideoAudio = onToggleVideoAudio,
                onVideoAudioVolumeChange = onVideoAudioVolumeChange,
                onPrevFrame = onPrevFrame,
                onNextFrame = onNextFrame,
                onSeekFrame = onSeekFrame,
                onJumpToLatestFrame = onJumpToLatestFrame,
                onPlaybackPosition = onPlaybackPosition,
                onPlaybackLimitReached = onPlaybackLimitReached,
                onTogglePause = onTogglePause,
                onViewSummary = onViewSummary,
                onSubjectTap = onSubjectTap,
                onReanalyzeSelectedSubject = onReanalyzeSelectedSubject,
                modifier = Modifier,
            )
        }

        is VideoPhase.Error -> {
            VideoErrorState(
                message = (state.phase as VideoPhase.Error).message,
                onRetry = onRetry,
                onResetCamera = onResetCamera,
            )
        }

        is VideoPhase.Complete,
        is VideoPhase.Paused -> {
            // Use AnimatedContent with videoUri as key to smoothly transition
            // when switching between different videos
            AnimatedContent(
                targetState = videoUri,
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith
                    fadeOut(animationSpec = tween(200))
                },
                label = "video_switch",
            ) { targetUri ->
                VideoAnalysisLayout(
                    live = live,
                    isPaused = isPaused,
                    isProcessing = isProcessing,
                    videoUri = targetUri,
                    onPickVideo = onPickVideo,
                    onOpenSettings = onOpenSettings,
                    showTrajectory = showTrajectory,
                    onToggleTrajectory = onToggleTrajectory,
                    onResetCamera = onResetCamera,
                    videoAudioEnabled = videoAudioEnabled,
                    videoAudioVolume = videoAudioVolume,
                    onToggleVideoAudio = onToggleVideoAudio,
                    onVideoAudioVolumeChange = onVideoAudioVolumeChange,
                    onPrevFrame = onPrevFrame,
                    onNextFrame = onNextFrame,
                    onSeekFrame = onSeekFrame,
                    onJumpToLatestFrame = onJumpToLatestFrame,
                    onPlaybackPosition = onPlaybackPosition,
                    onPlaybackLimitReached = onPlaybackLimitReached,
                    onTogglePause = onTogglePause,
                    onViewSummary = onViewSummary,
                    onSubjectTap = onSubjectTap,
                    onReanalyzeSelectedSubject = onReanalyzeSelectedSubject,
                    modifier = Modifier,
                )
            }
        }
    }
}

// ── Camera Live Layout (unchanged functionality) ────────────────────

internal fun shouldShowVideoAnalysisSurface(
    analyzedFrames: Int,
    hasVideoPreview: Boolean,
): Boolean = analyzedFrames > 0 || hasVideoPreview

@Composable
private fun CameraLiveLayout(
    live: LiveWorkoutState,
    lensFacing: Int,
    onCameraFrame: (com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult, Int, Int, Int, Bitmap?) -> Unit,
    poseOverlayState: PoseOverlayState,
    imageW: Int,
    imageH: Int,
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    showTrajectory: Boolean,
    onToggleTrajectory: () -> Unit,
    onSwitchCamera: () -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    onSubjectTap: (Float, Float) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen camera
        CameraPreviewWithOverlay(
            onPoseDetected = onCameraFrame,
            modifier = Modifier.fillMaxSize(),
            lensFacing = lensFacing,
        )

        // Pose overlay (with breathing room for bottom sheet)
        PoseOverlay(
            state = poseOverlayState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 52.dp, bottom = 220.dp, start = 12.dp, end = 12.dp)
                .pointerInput(imageW, imageH, lensFacing) {
                    detectTapGestures(
                        onDoubleTap = { onSwitchCamera() },
                        onTap = { tap ->
                            normalizedTapToContent(
                                tap = tap,
                                containerWidth = size.width.toFloat(),
                                containerHeight = size.height.toFloat(),
                                sourceWidth = imageW.toFloat(),
                                sourceHeight = imageH.toFloat(),
                            )?.let { (x, y) -> onSubjectTap(mirrorNormalizedXForLens(x, lensFacing), y) }
                        },
                    )
                },
            width = imageW.toFloat(),
            height = imageH.toFloat(),
            heroMode = true,
        )

        SubjectLockChip(
            live = live,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 64.dp, start = 12.dp),
        )

        // Floating camera controls
        CameraCompactTopBar(
            onPickVideo = onPickVideo,
            onOpenSettings = onOpenSettings,
            showTrajectory = showTrajectory,
            onToggleTrajectory = onToggleTrajectory,
            lensFacing = lensFacing,
            onSwitchCamera = onSwitchCamera,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // Bottom sheet panel
        CameraBottomSheet(
            live = live,
            onTogglePause = onTogglePause,
            onViewSummary = onViewSummary,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun rememberCameraPoseOverlayState(
    live: LiveWorkoutState,
    showTrajectory: Boolean,
    lensFacing: Int,
): PoseOverlayState {
    val mirrorHorizontally = shouldMirrorCameraOverlay(lensFacing)
    return remember(
        live.poseLandmarks,
        live.poseTrajectory,
        live.poseCandidates,
        live.activeSubjectIndex,
        live.subjectLockStatus,
        live.activeWarnings,
        showTrajectory,
        mirrorHorizontally,
    ) {
        val cueOverlay = PoseCueMapper.fromWarnings(live.activeWarnings)
        val renderSecondarySkeletons = shouldRenderSecondarySkeletons(
            subjectLockStatus = live.subjectLockStatus,
            hasActiveSubject = live.poseLandmarks.isNotEmpty(),
        )
        val secondarySubjects = if (renderSecondarySkeletons) {
            live.poseCandidates.mapIndexedNotNull { index, candidate ->
                if (index == live.activeSubjectIndex) {
                    null
                } else {
                    candidate.landmarks.map { it.toOverlayLandmark(mirrorHorizontally) }
                }
            }
        } else {
            emptyList()
        }
        PoseOverlayState(
            landmarks = live.poseLandmarks.map { it.toOverlayLandmark(mirrorHorizontally) },
            secondarySubjects = secondarySubjects,
            trajectoryFrames = if (showTrajectory) {
                live.poseTrajectory.map { frame ->
                    frame.map { it.toOverlayLandmark(mirrorHorizontally) }
                }
            } else {
                emptyList()
            },
            violationJoints = cueOverlay.safetyJoints,
            violationSegments = cueOverlay.safetySegments,
            watchJoints = cueOverlay.watchJoints,
            watchSegments = cueOverlay.watchSegments,
            showConfidenceFade = true,
        )
    }
}

// ── Camera sub-components ───────────────────────────────────────────

@Composable
private fun SubjectLockChip(
    live: LiveWorkoutState,
    modifier: Modifier = Modifier,
) {
    val copy = LocalAppStrings.current
    val text = when (live.subjectLockStatus) {
        SubjectLockStatus.NEEDS_SELECTION -> {
            when {
                "AUTO_SELECTION_PENDING" in live.subjectTrustFlags -> copy.subjectAutoSelecting
                live.poseCandidates.size > 1 -> copy.tapYourself
                else -> ""
            }
        }
        SubjectLockStatus.LOCKED -> {
            if ("subject_hold" in live.subjectTrustFlags) copy.subjectHold else copy.subjectLocked
        }
        SubjectLockStatus.AUTO_LOCKED -> copy.autoSelectedSubject
        SubjectLockStatus.SUBJECT_LOST -> copy.subjectLost
        SubjectLockStatus.SINGLE_AUTO -> {
            if (live.poseCandidates.size == 1) copy.singleSubject else ""
        }
    }
    if (text.isBlank()) return

    Box(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CameraCompactTopBar(
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    showTrajectory: Boolean,
    onToggleTrajectory: () -> Unit,
    lensFacing: Int,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = LocalAppStrings.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, start = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xB0000000))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Green),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = copy.liveCamera,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraOverlayIconButton(
                icon = Icons.Filled.FlipCameraAndroid,
                contentDescription = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    "Switch to front camera"
                } else {
                    "Switch to rear camera"
                },
                active = lensFacing == CameraSelector.LENS_FACING_FRONT,
                onClick = onSwitchCamera,
            )
            CameraOverlayIconButton(
                icon = Icons.Filled.Videocam,
                contentDescription = copy.pickVideo,
                active = false,
                onClick = onPickVideo,
            )
            CameraOverlayIconButton(
                icon = Icons.Filled.SlowMotionVideo,
                contentDescription = if (showTrajectory) copy.hideTrajectory else copy.showTrajectory,
                active = showTrajectory,
                onClick = onToggleTrajectory,
            )
            CameraOverlayIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = copy.settings,
                active = false,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun CameraOverlayIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (active) {
                    Green.copy(alpha = 0.18f)
                } else {
                    Color(0xAA000000)
                },
            ),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) Green else TextPrimary,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun CameraBottomSheet(
    live: LiveWorkoutState,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = LocalAppStrings.current
    val active = live.qualityFlags.firstOrNull { it.status != "OK" }
    val status = active?.status ?: "OK"
    val statusColor = when (status) {
        "CRITICAL" -> Red
        "WARNING" -> Orange
        "MONITOR" -> Color(0xFFFFD700)
        else -> Green
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = status != "OK" || live.coachMessage.isNotBlank(),
            enter = androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.slideOutVertically { it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when (status) {
                        "CRITICAL" -> copy.statusTitle("CRITICAL")
                        "WARNING" -> copy.statusTitle("WARNING")
                        "MONITOR" -> copy.statusTitle("MONITOR")
                        else -> live.coachMessage.take(44)
                    },
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xD90F1512),
            shape = RoundedCornerShape(18.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CameraStat(label = copy.reps, value = "${live.repCount}")
                    CameraStat(label = copy.form, value = "${live.formScore}", accent = statusColor)
                    CameraStat(label = copy.phase, value = live.movementPhase.replaceFirstChar { it.uppercase() })
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onTogglePause,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(44.dp),
                    ) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = copy.pause,
                            tint = TextPrimary,
                        )
                    }
                    Button(
                        onClick = onViewSummary,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(2.2f).height(44.dp),
                    ) {
                        Icon(Icons.Filled.BarChart, contentDescription = copy.summary, tint = TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            copy.summary,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraStat(
    label: String,
    value: String,
    accent: Color = TextPrimary,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = accent,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

private fun normalizedTapToContent(
    tap: Offset,
    containerWidth: Float,
    containerHeight: Float,
    sourceWidth: Float,
    sourceHeight: Float,
): Pair<Float, Float>? {
    if (containerWidth <= 0f || containerHeight <= 0f) return null
    if (sourceWidth <= 0f || sourceHeight <= 0f) {
        return (tap.x / containerWidth).coerceIn(0f, 1f) to
                (tap.y / containerHeight).coerceIn(0f, 1f)
    }

    val scale = minOf(containerWidth / sourceWidth, containerHeight / sourceHeight)
    val contentW = sourceWidth * scale
    val contentH = sourceHeight * scale
    if (contentW <= 0f || contentH <= 0f) return null

    val offsetX = (containerWidth - contentW) / 2f
    val offsetY = (containerHeight - contentH) / 2f
    val x = (tap.x - offsetX) / contentW
    val y = (tap.y - offsetY) / contentH
    if (x !in 0f..1f || y !in 0f..1f) return null
    return x to y
}

internal fun nextCameraLensFacing(current: Int): Int {
    return if (current == CameraSelector.LENS_FACING_BACK) {
        CameraSelector.LENS_FACING_FRONT
    } else {
        CameraSelector.LENS_FACING_BACK
    }
}

internal fun shouldMirrorCameraOverlay(lensFacing: Int): Boolean {
    return lensFacing == CameraSelector.LENS_FACING_FRONT
}

internal fun mirrorNormalizedXForLens(x: Float, lensFacing: Int): Float {
    val clamped = x.coerceIn(0f, 1f)
    return if (shouldMirrorCameraOverlay(lensFacing)) 1f - clamped else clamped
}

private fun PoseLandmarkData.toOverlayLandmark(mirrorHorizontally: Boolean): PoseLandmark {
    val overlayX = if (mirrorHorizontally) 1f - x else x
    return PoseLandmark(
        x = overlayX.coerceIn(0f, 1f),
        y = y,
        visibility = visibility,
    )
}

private fun jointIndexFromName(name: String): Int? = when {
    name.contains("left_knee") || name == "knee" && name.startsWith("left") -> 25
    name.contains("right_knee") -> 26
    name.contains("left_hip") -> 23
    name.contains("right_hip") -> 24
    name.contains("left_elbow") -> 13
    name.contains("right_elbow") -> 14
    name.contains("left_shoulder") -> 11
    name.contains("right_shoulder") -> 12
    name.contains("left_ankle") -> 27
    name.contains("right_ankle") -> 28
    else -> null
}
