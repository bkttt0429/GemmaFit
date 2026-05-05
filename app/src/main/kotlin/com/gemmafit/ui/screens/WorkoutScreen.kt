package com.gemmafit.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemmafit.camera.CameraPreviewWithOverlay
import com.gemmafit.settings.AppSettings
import com.gemmafit.ui.overlay.PoseLandmark
import com.gemmafit.ui.overlay.PoseOverlay
import com.gemmafit.ui.overlay.PoseOverlayState
import com.gemmafit.ui.screens.video.CoachPanel
import com.gemmafit.ui.screens.video.VideoAnalysisLayout
import com.gemmafit.ui.screens.video.VideoEmptyState
import com.gemmafit.ui.screens.video.VideoErrorState
import com.gemmafit.ui.screens.video.VideoLoadingState
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.SubjectLockStatus
import com.gemmafit.video.VideoAnalysisViewModel
import com.gemmafit.video.VideoPhase
import com.gemmafit.video.VideoSource

@Composable
fun WorkoutScreen(
    onViewSummary: (com.gemmafit.video.SessionSummary) -> Unit,
    onOpenSettings: () -> Unit = {},
    settings: AppSettings = AppSettings(),
    modifier: Modifier = Modifier,
    viewModel: VideoAnalysisViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val live by viewModel.live.collectAsState()
    var isPaused by remember { mutableStateOf(false) }
    var cameraImageWidth by remember { mutableStateOf(1080) }
    var cameraImageHeight by remember { mutableStateOf(1920) }
    var showTrajectory by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings) {
        viewModel.configureCoachVoice(settings)
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            viewModel.setVideoSource(it, it.toString())
            viewModel.processVideo(it)
        }
    }

    val isVideoMode = state.source is VideoSource.VideoFile
    val isCameraMode = state.source is VideoSource.Camera
    val isProcessing = state.phase is VideoPhase.Processing || state.phase is VideoPhase.Analyzing
    val videoUri = (state.source as? VideoSource.VideoFile)?.let { Uri.parse(it.uri) }

    // Reset pause state when switching video sources to prevent old data flashing
    LaunchedEffect(state.source) {
        if (state.source is VideoSource.VideoFile) {
            isPaused = true
        }
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
                    onCameraFrame = { result, h, w ->
                        cameraImageHeight = h
                        cameraImageWidth = w
                        viewModel.onCameraFrame(result)
                    },
                    poseOverlayState = rememberCameraPoseOverlayState(live, showTrajectory),
                    imageW = cameraImageWidth,
                    imageH = cameraImageHeight,
                    onPickVideo = { videoPickerLauncher.launch("*/*") },
                    onOpenSettings = onOpenSettings,
                    showTrajectory = showTrajectory,
                    onToggleTrajectory = { showTrajectory = !showTrajectory },
                    onTogglePause = { isPaused = !isPaused },
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
                    onPickVideo = { videoPickerLauncher.launch("*/*") },
                    onOpenSettings = onOpenSettings,
                    showTrajectory = showTrajectory,
                    onToggleTrajectory = { showTrajectory = !showTrajectory },
                    onResetCamera = { viewModel.resetToCamera() },
                    onPrevFrame = {
                        isPaused = true
                        viewModel.goToPrevFrame()
                    },
                    onNextFrame = {
                        isPaused = true
                        viewModel.goToNextFrame()
                    },
                    onSeekFrame = {
                        isPaused = true
                        viewModel.goToFrame(it)
                    },
                    onPlaybackPosition = { viewModel.showFrameAtTimestamp(it) },
                    onTogglePause = { isPaused = !isPaused },
                    onViewSummary = { onViewSummary(viewModel.sessionSummary.value) },
                    onSubjectTap = { x, y -> viewModel.selectSubjectAt(x, y) },
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
    onPrevFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onPlaybackPosition: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    onSubjectTap: (Float, Float) -> Unit,
    onRetry: () -> Unit,
) {
    when (state.phase) {
        is VideoPhase.Idle,
        is VideoPhase.Selecting -> {
            VideoEmptyState(
                onPickVideo = onPickVideo,
            )
        }

        is VideoPhase.Processing,
        is VideoPhase.Analyzing -> {
            if (live.totalFramesAnalyzed > 0) {
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
                    onPrevFrame = onPrevFrame,
                    onNextFrame = onNextFrame,
                    onSeekFrame = onSeekFrame,
                    onPlaybackPosition = onPlaybackPosition,
                    onTogglePause = onTogglePause,
                    onViewSummary = onViewSummary,
                    onSubjectTap = onSubjectTap,
                    modifier = Modifier,
                )
            } else {
                VideoLoadingState(
                    currentFrame = state.currentFrame,
                    totalFrames = state.totalFrames,
                    processingFps = state.processingFps,
                    poseHitRate = state.poseHitRate,
                    subPhase = state.subPhase,
                    subPhaseProgress = state.subPhaseProgress,
                )
            }
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
                    onPrevFrame = onPrevFrame,
                    onNextFrame = onNextFrame,
                    onSeekFrame = onSeekFrame,
                    onPlaybackPosition = onPlaybackPosition,
                    onTogglePause = onTogglePause,
                    onViewSummary = onViewSummary,
                    onSubjectTap = onSubjectTap,
                    modifier = Modifier,
                )
            }
        }
    }
}

// ── Camera Live Layout (unchanged functionality) ────────────────────

@Composable
private fun CameraLiveLayout(
    live: LiveWorkoutState,
    onCameraFrame: (com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult, Int, Int) -> Unit,
    poseOverlayState: PoseOverlayState,
    imageW: Int,
    imageH: Int,
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    showTrajectory: Boolean,
    onToggleTrajectory: () -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    onSubjectTap: (Float, Float) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-screen camera
        CameraPreviewWithOverlay(
            onPoseDetected = onCameraFrame,
            modifier = Modifier.fillMaxSize(),
        )

        // Pose overlay (with breathing room for bottom sheet)
        PoseOverlay(
            state = poseOverlayState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 52.dp, bottom = 220.dp, start = 12.dp, end = 12.dp)
                .pointerInput(imageW, imageH) {
                    detectTapGestures { tap ->
                        normalizedTapToContent(
                            tap = tap,
                            containerWidth = size.width.toFloat(),
                            containerHeight = size.height.toFloat(),
                            sourceWidth = imageW.toFloat(),
                            sourceHeight = imageH.toFloat(),
                        )?.let { (x, y) -> onSubjectTap(x, y) }
                    }
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

        // Compact top bar (same style as Video mode)
        CameraCompactTopBar(
            onPickVideo = onPickVideo,
            onOpenSettings = onOpenSettings,
            showTrajectory = showTrajectory,
            onToggleTrajectory = onToggleTrajectory,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // FPS HUD
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 12.dp)
                .background(Color(0xAA000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = "${live.totalFramesAnalyzed}f",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // Bottom sheet panel
        CameraBottomSheet(
            live = live,
            onTogglePause = onTogglePause,
            onViewSummary = onViewSummary,
            onPickVideo = onPickVideo,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun rememberCameraPoseOverlayState(
    live: LiveWorkoutState,
    showTrajectory: Boolean,
): PoseOverlayState {
    return remember(
        live.poseLandmarks,
        live.poseTrajectory,
        live.poseCandidates,
        live.activeSubjectIndex,
        live.activeWarnings,
        showTrajectory,
    ) {
        val violationJoints = live.activeWarnings
            .filter { it.joint.isNotEmpty() }
            .mapNotNull { w -> jointIndexFromName(w.joint) }
            .toSet()
        val secondarySubjects = live.poseCandidates.mapIndexedNotNull { index, candidate ->
            if (index == live.activeSubjectIndex) {
                null
            } else {
                candidate.landmarks.map { PoseLandmark(it.x, it.y, it.visibility) }
            }
        }
        PoseOverlayState(
            landmarks = live.poseLandmarks.map { PoseLandmark(it.x, it.y, it.visibility) },
            secondarySubjects = secondarySubjects,
            trajectoryFrames = if (showTrajectory) {
                live.poseTrajectory.map { frame ->
                    frame.map { PoseLandmark(it.x, it.y, it.visibility) }
                }
            } else {
                emptyList()
            },
            violationJoints = violationJoints,
            violationSegments = buildViolationSegments(live.activeWarnings),
            showConfidenceFade = true,
        )
    }
}

private fun buildViolationSegments(
    warnings: List<com.gemmafit.video.SafetyWarning>,
): Set<Pair<Int, Int>> {
    val result = mutableSetOf<Pair<Int, Int>>()
    val prefixes = listOf(
        "left_knee" to (25 to 27), "right_knee" to (26 to 28),
        "left_hip" to (23 to 25), "right_hip" to (24 to 26),
        "left_elbow" to (13 to 15), "right_elbow" to (14 to 16),
    )
    for (w in warnings) {
        for ((prefix, seg) in prefixes) {
            if (w.joint == prefix) {
                result.add(seg)
                break
            }
        }
    }
    return result
}

// ── Camera sub-components ───────────────────────────────────────────

@Composable
private fun SubjectLockChip(
    live: LiveWorkoutState,
    modifier: Modifier = Modifier,
) {
    val text = when (live.subjectLockStatus) {
        SubjectLockStatus.NEEDS_SELECTION -> {
            when {
                "AUTO_SELECTION_PENDING" in live.subjectTrustFlags -> "Auto-selecting subject..."
                live.poseCandidates.size > 1 -> "Tap yourself to start"
                else -> ""
            }
        }
        SubjectLockStatus.LOCKED -> {
            if ("subject_hold" in live.subjectTrustFlags) "Subject hold" else "Subject locked"
        }
        SubjectLockStatus.AUTO_LOCKED -> "Auto-selected subject - tap to change"
        SubjectLockStatus.SUBJECT_LOST -> "Subject lost"
        SubjectLockStatus.SINGLE_AUTO -> {
            if (live.poseCandidates.size == 1) "Single subject" else ""
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(SurfaceColor.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Green),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Live Camera",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = onPickVideo,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = "Pick video",
                tint = Green,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(
            onClick = onToggleTrajectory,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.SlowMotionVideo,
                contentDescription = if (showTrajectory) "Hide trajectory" else "Show trajectory",
                tint = if (showTrajectory) Green else TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun CameraBottomSheet(
    live: LiveWorkoutState,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    onPickVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Floating status pill (like Video mode's StatusHero)
        androidx.compose.animation.AnimatedVisibility(
            visible = status != "OK" || live.coachMessage.isNotBlank(),
            enter = androidx.compose.animation.slideInVertically { it },
            exit = androidx.compose.animation.slideOutVertically { it },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when (status) {
                        "CRITICAL" -> "CORRECT NOW"
                        "WARNING" -> "WARNING"
                        "MONITOR" -> "WATCH"
                        else -> live.coachMessage.take(40)
                    },
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }

        // Bottom sheet surface
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xE60F1512),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // Handle bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextSecondary.copy(alpha = 0.4f)),
                    )
                }

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CameraStat(label = "REPS", value = "${live.repCount}")
                    CameraStat(label = "FORM", value = "${live.formScore}", accent = statusColor)
                    CameraStat(label = "PHASE", value = live.movementPhase.replaceFirstChar { it.uppercase() })
                }

                Spacer(Modifier.height(12.dp))

                // Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onTogglePause,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = "Pause",
                            tint = TextPrimary,
                        )
                    }
                    Button(
                        onClick = onPickVideo,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(
                            Icons.Filled.SlowMotionVideo,
                            contentDescription = "Pick video",
                            tint = TextPrimary,
                        )
                    }
                    Button(
                        onClick = onViewSummary,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(2.5f).height(48.dp),
                    ) {
                        Icon(Icons.Filled.BarChart, contentDescription = "Summary", tint = TextPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Summary",
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
