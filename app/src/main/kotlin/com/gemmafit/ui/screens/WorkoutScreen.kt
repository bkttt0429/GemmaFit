package com.gemmafit.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gemmafit.camera.CameraPreviewWithOverlay
import com.gemmafit.ui.components.ThreeDeeMini
import com.gemmafit.ui.overlay.PoseLandmark
import com.gemmafit.ui.overlay.PoseOverlay
import com.gemmafit.ui.overlay.PoseOverlayState
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.PurpleSecondary
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.EvidenceCard
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.QualityFlag
import com.gemmafit.video.SessionSummary
import com.gemmafit.video.VideoAnalysisViewModel
import com.gemmafit.video.VideoPhase
import com.gemmafit.video.VideoSource
import com.gemmafit.ui.components.VideoProcessingOverlay
import kotlinx.coroutines.delay

@Composable
fun WorkoutScreen(
    onViewSummary: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoAnalysisViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val live by viewModel.live.collectAsState()
    var isPaused by remember { mutableStateOf(false) }
    var cameraImageWidth by remember { mutableStateOf(1080) }
    var cameraImageHeight by remember { mutableStateOf(1920) }

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
    val playerShouldPlay = isVideoMode && !isPaused && !isProcessing && live.totalFramesAnalyzed > 0

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        if (isCameraMode && !isPaused) {
            // Camera live mode: full-screen camera with floating overlays
            // Build PoseOverlayState from ViewModel's live state
            val camPoseState = remember(live.poseLandmarks, live.activeWarnings) {
                val violationJoints = live.activeWarnings
                    .filter { it.joint.isNotEmpty() }
                    .mapNotNull { w -> jointIndexFromName(w.joint) }
                    .toSet()
                val violationSegments = mutableSetOf<Pair<Int, Int>>()
                for (w in live.activeWarnings) {
                    val prefixes = listOf("left_knee" to (25 to 27), "right_knee" to (26 to 28),
                        "left_hip" to (23 to 25), "right_hip" to (24 to 26),
                        "left_elbow" to (13 to 15), "right_elbow" to (14 to 16))
                    for ((prefix, seg) in prefixes) {
                        if (w.joint == prefix) { violationSegments.add(seg); break }
                        if (w.joint == prefix.replace("left_", "right_").replace("right_", "left_") 
                            && seg.first != 0) continue
                    }
                }
                PoseOverlayState(
                    landmarks = live.poseLandmarks.map { PoseLandmark(it.x, it.y, it.visibility) },
                    violationJoints = violationJoints,
                    violationSegments = violationSegments,
                    showConfidenceFade = true,
                )
            }
            CameraLiveLayout(
                live = live,
                onCameraFrame = { result, h, w ->
                    cameraImageHeight = h; cameraImageWidth = w
                    viewModel.onCameraFrame(result)
                },
                poseOverlayState = camPoseState,
                imageW = cameraImageWidth, imageH = cameraImageHeight,
                onPickVideo = { videoPickerLauncher.launch("*/*") },
                onTogglePause = { isPaused = !isPaused },
                onViewSummary = { onViewSummary(viewModel.sessionSummary.value) },
            )
        } else {
            // Video-file analysis mode: vertical scrolling dashboard
           VideoAnalysisLayout(
                live = live,
                isPaused = isPaused,
                isVideoMode = isVideoMode,
                videoUri = videoUri,
                playerShouldPlay = playerShouldPlay,
                onPickVideo = { videoPickerLauncher.launch("*/*") },
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
            )
        }

        // Top processing overlay (mp4 import progress)
        if (isProcessing) {
            VideoProcessingOverlay(
                phase = state.phase,
                progress = state.progress,
                currentFrame = state.currentFrame,
                totalFrames = state.totalFrames,
                fileName = (state.source as? VideoSource.VideoFile)?.displayName ?: "",
                etaSeconds = state.etaSeconds,
                processingFps = state.processingFps,
                poseHitRate = state.poseHitRate,
                subPhase = state.subPhase,
                subPhaseProgress = state.subPhaseProgress,
                onCancel = {
                    viewModel.cancelProcessing()
                    viewModel.resetToCamera()
                },
            )
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Video file analysis — vertical dashboard layout
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun VideoAnalysisLayout(
    live: LiveWorkoutState,
    isPaused: Boolean,
    isVideoMode: Boolean,
    videoUri: Uri?,
    playerShouldPlay: Boolean,
    onPickVideo: () -> Unit,
    onResetCamera: () -> Unit,
    onPrevFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onPlaybackPosition: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val haptic = LocalHapticFeedback.current

    // Haptic feedback on CRITICAL warnings (only on severity change, not every frame)
    val criticalCount = live.activeWarnings.count { it.severity == "critical" || it.severity == "high" }
    LaunchedEffect(criticalCount) {
        if (criticalCount > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // ─ Sticky top bar
        TopBar(
            isVideoMode = isVideoMode,
            onPickVideo = onPickVideo,
        )

        // ─ Scrollable content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            ExerciseBadge(
                exercise = live.detectedExercise,
                confidence = live.exerciseConfidence,
                rep = live.repCount,
                phase = derivePhaseLabel(live),
            )

            VerdictPill(
                evidence = live.evidenceCard,
                qualityFlags = live.qualityFlags,
            )

            VideoFrame(
                live = live,
                isVideoMode = isVideoMode,
                videoUri = videoUri,
                isPlaying = playerShouldPlay,
                onPlaybackPosition = onPlaybackPosition,
            )

            if (live.totalFramesAnalyzed > 1) {
                FrameTimeline(
                    current = live.currentFrameIndex,
                    total = live.totalFramesAnalyzed,
                    timestampMs = live.currentFrameTimestampMs,
                    onPrev = onPrevFrame,
                    onNext = onNextFrame,
                    onSeek = onSeekFrame,
                )
            }

            LiveMetricsRow(
                metrics = live.templateMetrics,
                qualityFlags = live.qualityFlags,
            )

            CoachCard(
                message = live.coachMessage,
                priority = live.coachPriority,
                source = if (live.coachMessage.isNotEmpty()) "mock_gemma_feedback" else "",
            )

            EvidenceSection(card = live.evidenceCard)
            CannotJudgeSection(card = live.evidenceCard, qualityFlags = live.qualityFlags)

            Spacer(Modifier.height(80.dp))   // bottom-bar breathing room
        }

        // ─ Sticky bottom bar
        BottomControlBar(
            isVideoMode = isVideoMode,
            isPaused = isPaused,
            onPickVideo = onPickVideo,
            onResetCamera = onResetCamera,
            onTogglePause = onTogglePause,
            onViewSummary = onViewSummary,
        )
    }
}


// ──────────────────────────────────────────────────────────────────────
// Camera live mode — preserves the floating overlay design
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun CameraLiveLayout(
    live: LiveWorkoutState,
    onCameraFrame: (com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult, Int, Int) -> Unit,
    poseOverlayState: PoseOverlayState,
    imageW: Int,
    imageH: Int,
    onPickVideo: () -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewWithOverlay(
            onPoseDetected = onCameraFrame,
            modifier = Modifier.fillMaxSize(),
        )

        // FPS / detection rate HUD
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 70.dp, end = 8.dp)
                .background(Color(0xAA000000), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "FPS · ${live.totalFramesAnalyzed}f",
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }

        PoseOverlay(
            state = poseOverlayState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp, bottom = 200.dp, start = 12.dp, end = 12.dp),
            width = imageW.toFloat(),
            height = imageH.toFloat(),
        )
        TopBar(
            isVideoMode = false,
            onPickVideo = onPickVideo,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        // Floating verdict pill at top
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 64.dp, start = 16.dp, end = 16.dp),
        ) {
            VerdictPill(
                evidence = live.evidenceCard,
                qualityFlags = live.qualityFlags,
            )
        }
        // Floating coach + bottom bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (live.coachMessage.isNotEmpty()) {
                CoachCard(
                    message = live.coachMessage,
                    priority = live.coachPriority,
                    source = "mock_gemma_feedback",
                )
            }
            BottomControlBar(
                isVideoMode = false,
                isPaused = false,
                onPickVideo = onPickVideo,
                onResetCamera = {},
                onTogglePause = onTogglePause,
                onViewSummary = onViewSummary,
            )
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Top bar
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    isVideoMode: Boolean,
    onPickVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xCC0F1512))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {}) {
            Icon(Icons.Filled.Menu, "Menu", tint = TextPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "GemmaFit",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (isVideoMode) "Video analysis" else "Live camera",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(
            modifier = Modifier
                .width(8.dp).height(8.dp)
                .background(Green, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onPickVideo) {
            Icon(Icons.Filled.Videocam, "Pick video",
                 tint = if (isVideoMode) Green else TextSecondary)
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Section 1 — Exercise badge
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun ExerciseBadge(
    exercise: String,
    confidence: Float,
    rep: Int,
    phase: String,
) {
    val (icon, label) = exerciseDisplay(exercise)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 32.sp, modifier = Modifier.padding(end = 12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Rep $rep · $phase",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        ConfidenceMeter(confidence = confidence)
    }
}

@Composable
private fun ConfidenceMeter(confidence: Float) {
    val pct = (confidence * 100).toInt().coerceIn(0, 100)
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("$pct%", color = Green, fontWeight = FontWeight.Bold,
                 style = MaterialTheme.typography.titleMedium)
            Text("confident", color = TextSecondary,
                 style = MaterialTheme.typography.labelSmall)
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Section 2 — Verdict pill (single big status indicator)
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun VerdictPill(
    evidence: EvidenceCard,
    qualityFlags: List<QualityFlag>,
) {
    val active = qualityFlags.firstOrNull { it.status != "OK" }
    val status = active?.status ?: evidence.verdict.ifBlank { "OK" }
    val color = statusColor(status)
    val icon = statusIcon(status)
    val title = statusTitle(status)
    val subtitle = active?.let {
        "${it.id.replace("rule_", "rule ").replace("_", " ")} — ${it.joint.ifBlank { "—" }}"
    } ?: evidence.reason.ifBlank { "Evidence-gated feedback" }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp).height(42.dp)
                    .clip(RoundedCornerShape(21.dp))
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = color, fontWeight = FontWeight.Bold,
                     fontSize = 16.sp)
                Text(subtitle, color = TextPrimary,
                     style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Section 3 — Video / skeleton frame (no text overlays)
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun VideoFrame(
    live: LiveWorkoutState,
    isVideoMode: Boolean,
    videoUri: Uri?,
    isPlaying: Boolean,
    onPlaybackPosition: (Long) -> Unit,
) {
    val frameAspect = if (live.videoPreviewWidth > 0 && live.videoPreviewHeight > 0) {
        live.videoPreviewWidth.toFloat() / live.videoPreviewHeight.toFloat()
    } else {
        16f / 9f
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(frameAspect.coerceIn(0.56f, 1.78f)),
        color = Color(0xFF0A0F0E),
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideoMode && videoUri != null && isPlaying) {
                // Auto-play: use ExoPlayer for smooth video
                SmoothVideoPlayer(
                    uri = videoUri,
                    isPlaying = true,
                    targetPositionMs = live.currentFrameTimestampMs,
                    onPlaybackPosition = onPlaybackPosition,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (live.videoPreview != null) {
                // Paused / frame-step: use analyzed bitmap (instant, guaranteed sync)
                Image(
                    bitmap = live.videoPreview!!.asImageBitmap(),
                    contentDescription = "Video frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            if (live.poseLandmarks.isNotEmpty()) {
                val overlayState = PoseOverlayState(
                    landmarks = live.poseLandmarks.map {
                        PoseLandmark(it.x, it.y, it.visibility)
                    },
                    trajectoryFrames = live.poseTrajectory.map { frame ->
                        frame.map { PoseLandmark(it.x, it.y, it.visibility) }
                    },
                    violationJoints = live.activeWarnings
                        .filter { it.joint.isNotEmpty() }
                        .mapNotNull { w ->
                            when {
                                w.joint.contains("knee") -> if (w.joint.contains("left")) 25 else 26
                                w.joint.contains("hip") -> if (w.joint.contains("left")) 23 else 24
                                w.joint.contains("elbow") -> if (w.joint.contains("left")) 13 else 14
                                w.joint.contains("shoulder") -> if (w.joint.contains("left")) 11 else 12
                                w.joint.contains("ankle") -> if (w.joint.contains("left")) 27 else 28
                                else -> null
                            }
                        }.toSet(),
                )
                PoseOverlay(
                    state = overlayState,
                    modifier = Modifier.fillMaxSize(),
                    width = live.videoPreviewWidth.toFloat(),
                    height = live.videoPreviewHeight.toFloat(),
                )
            }

            if (isPlaying || live.totalFramesAnalyzed > 1) {
                val progress = if (live.totalFramesAnalyzed > 1) {
                    live.currentFrameIndex.toFloat() / (live.totalFramesAnalyzed - 1).coerceAtLeast(1)
                } else 0f
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPlaying) {
                            Canvas(modifier = Modifier.size(14.dp)) {
                                val sw = 2.dp.toPx()
                                drawArc(
                                    color = Green,
                                    startAngle = -90f,
                                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                                    useCenter = false,
                                    style = Stroke(width = sw, cap = StrokeCap.Round),
                                    topLeft = Offset(sw / 2, sw / 2),
                                    size = Size(size.width - sw, size.height - sw),
                                )
                            }
                        } else {
                            Icon(Icons.Filled.PlayArrow, null, tint = Green, modifier = Modifier.size(14.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${live.currentFrameIndex + 1}/${live.totalFramesAnalyzed}",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmoothVideoPlayer(
    uri: Uri,
    isPlaying: Boolean,
    targetPositionMs: Long,
    onPlaybackPosition: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            prepare()
            if (targetPositionMs > 0L) seekTo(targetPositionMs)
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(player, isPlaying) {
        if (isPlaying) {
            player.play()
        } else {
            player.pause()
        }
    }

    LaunchedEffect(player, isPlaying, targetPositionMs) {
        if (!isPlaying && targetPositionMs >= 0L) {
            val driftMs = kotlin.math.abs(player.currentPosition - targetPositionMs)
            if (driftMs > 50L) {
                player.seekTo(targetPositionMs)
            }
        }
    }

    LaunchedEffect(player, isPlaying) {
        if (isPlaying) {
            while (true) {
                onPlaybackPosition(player.currentPosition)
                delay(33L)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
        },
        modifier = modifier,
    )
}

@Composable
private fun FrameTimeline(
    current: Int,
    total: Int,
    timestampMs: Long,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Timestamp label
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTimestamp(timestampMs), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
            Text("$total frames", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
        }
        // Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.PlayArrow, "Previous", tint = TextPrimary, modifier = Modifier.scale(-1f, 1f).size(16.dp))
            }
            Slider(
                value = current.toFloat().coerceIn(0f, (total - 1).coerceAtLeast(1).toFloat()),
                onValueChange = { onSeek(it.toInt()) },
                valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green),
            )
            IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.PlayArrow, "Next", tint = TextPrimary, modifier = Modifier.size(16.dp))
            }
        }
        // Frame counter
        Text(
            "${current + 1} / $total",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}


// ──────────────────────────────────────────────────────────────────────
// Section 4 — Live metric chips
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun LiveMetricsRow(
    metrics: Map<String, Float>,
    qualityFlags: List<QualityFlag>,
) {
    if (metrics.isEmpty()) return
    val flaggedKeys = qualityFlags
        .filter { it.status in setOf("WARNING", "CRITICAL", "MONITOR") }
        .map { it.id.lowercase() }
        .toSet()

    Column {
        Text(
            "Live metrics",
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            metrics.entries.take(4).forEach { (k, v) ->
                val isFlagged = flaggedKeys.any { it.contains(shortKey(k)) }
                MetricChip(
                    label = displayKey(k),
                    value = formatValue(k, v),
                    flagged = isFlagged,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MetricChip(
    label: String,
    value: String,
    flagged: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = if (flagged) Orange.copy(alpha = 0.18f) else Color(0xFF1A1A1A),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value,
                    color = if (flagged) Orange else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                if (flagged) {
                    Spacer(Modifier.width(4.dp))
                    Text("⚠", fontSize = 12.sp)
                }
            }
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Section 5 — GemmaFit Coach card
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun CoachCard(
    message: String,
    priority: String,
    source: String,
) {
    if (message.isBlank()) return
    val accent = when (priority) {
        "high" -> Red
        "medium" -> Orange
        else -> Green
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF15191F),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💬", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "GemmaFit Coach",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                if (source.isNotEmpty()) {
                    Text(
                        source,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = TextPrimary,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Pose-based feedback · not medical diagnosis",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Section 6 — Evidence (collapsible)
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun EvidenceSection(card: EvidenceCard) {
    var expanded by remember { mutableStateOf(false) }
    if (card.evidence.isEmpty() && card.trustFlags.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF12181F),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📋", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Evidence",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    "Toggle",
                    tint = TextSecondary,
                )
            }
            // Always-visible summary line
            val summary = card.evidence.firstOrNull()?.let { "${it.label} = ${it.value}" }
                ?: card.reason
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(summary, color = TextSecondary,
                     style = MaterialTheme.typography.bodySmall)
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                card.evidence.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(item.label, color = TextSecondary,
                             style = MaterialTheme.typography.labelMedium)
                        Text(item.value, color = TextPrimary,
                             fontWeight = FontWeight.Bold,
                             style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (card.trustFlags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Trust flags",
                         color = TextSecondary,
                         style = MaterialTheme.typography.labelSmall,
                         fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        card.trustFlags.take(3).forEach { flag ->
                            Surface(
                                color = Color(0xFF1E2530),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text(
                                    flag.replace("_", " "),
                                    color = Blue,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp,
                                                                vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Section 7 — Cannot judge (the "correct refusal" hero feature)
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun CannotJudgeSection(
    card: EvidenceCard,
    qualityFlags: List<QualityFlag>,
) {
    // Combine NOT_APPLICABLE flags + always-unsupported judgments
    val notApplicable = qualityFlags.filter {
        it.status in setOf("NOT_APPLICABLE", "VIEW_LIMITED", "LOW_CONFIDENCE")
    }
    val unsupported = card.unsupportedJudgments.ifEmpty {
        listOf("joint_force", "clinical_injury_risk", "medical_diagnosis")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1F1F),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚫", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Cannot judge from this view",
                    color = PurpleSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(10.dp))
            notApplicable.take(3).forEach { f ->
                CannotRow(
                    title = f.id.replace("rule_", "rule ").replace("_", " "),
                    reason = when (f.status) {
                        "NOT_APPLICABLE" -> "Not applicable to this exercise / view"
                        "VIEW_LIMITED"   -> "Camera angle limits this judgment"
                        "LOW_CONFIDENCE" -> "Pose tracking unstable"
                        else             -> f.reason.ifBlank { "Skipped" }
                    },
                )
            }
            unsupported.take(4).forEach { judg ->
                CannotRow(
                    title = displayJudgment(judg),
                    reason = whyUnsupported(judg),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                card.modelBoundary,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun CannotRow(title: String, reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("•", color = TextSecondary, modifier = Modifier.padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold,
                 style = MaterialTheme.typography.bodyMedium)
            Text(reason, color = TextSecondary,
                 style = MaterialTheme.typography.labelSmall)
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Bottom control bar
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun BottomControlBar(
    isVideoMode: Boolean,
    isPaused: Boolean,
    onPickVideo: () -> Unit,
    onResetCamera: () -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
) {
    Surface(
        color = Color(0xCC0F1512),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onTogglePause,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Icon(if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                     null, tint = TextPrimary)
            }
            Button(
                onClick = if (isVideoMode) onResetCamera else onPickVideo,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Icon(if (isVideoMode) Icons.Filled.Refresh else Icons.Filled.SlowMotionVideo,
                     null, tint = TextPrimary)
            }
            Button(
                onClick = onViewSummary,
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(2.4f).fillMaxHeight(),
            ) {
                Icon(Icons.Filled.BarChart, null, tint = TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text("Summary", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        }
    }
}


// ──────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────

private fun exerciseDisplay(exercise: String): Pair<String, String> = when (exercise) {
    "squat"    -> "🏋️" to "Squat"
    "push_up"  -> "💪"  to "Push-up"
    "lunge"    -> "🦵"  to "Lunge"
    "deadlift" -> "🔩"  to "Deadlift"
    else       -> "❓"  to "Detecting…"
}

private fun derivePhaseLabel(live: LiveWorkoutState): String {
    if (live.movementPhase != "unknown") return live.movementPhase
    val k = live.templateMetrics["left_knee_angle"] ?: live.templateMetrics["knee_angle_deg"]
    return when {
        k == null      -> "—"
        k > 160        -> "top"
        k < 100        -> "bottom"
        else           -> "transition"
    }
}

private fun statusColor(status: String): Color = when (status) {
    "CRITICAL"        -> Red
    "WARNING"         -> Orange
    "MONITOR"         -> Color(0xFFFFD700)
    "VIEW_LIMITED"    -> Blue
    "LOW_CONFIDENCE"  -> PurpleSecondary
    "NOT_APPLICABLE"  -> Color(0xFF7A7F87)
    "OK"              -> Green
    else              -> TextSecondary
}

private fun statusIcon(status: String): String = when (status) {
    "CRITICAL"        -> "🛑"
    "WARNING"         -> "⚠"
    "MONITOR"         -> "👀"
    "VIEW_LIMITED"    -> "📷"
    "LOW_CONFIDENCE"  -> "❓"
    "NOT_APPLICABLE"  -> "—"
    "OK"              -> "✅"
    else              -> "•"
}

private fun statusTitle(status: String): String = when (status) {
    "CRITICAL"        -> "CORRECT NOW"
    "WARNING"         -> "WARNING"
    "MONITOR"         -> "WATCH"
    "VIEW_LIMITED"    -> "VIEW LIMITED"
    "LOW_CONFIDENCE"  -> "LOW CONFIDENCE"
    "NOT_APPLICABLE"  -> "NOT APPLICABLE"
    "OK"              -> "CLEAN"
    else              -> status
}

private fun shortKey(k: String): String =
    k.lowercase().substringBefore("_angle").substringBefore("_deg").substringBefore("_pct")

private fun displayKey(k: String): String {
    val cleaned = k
        .replace("_angle_deg", "")
        .replace("_angle", "")
        .replace("_deg", "")
        .replace("_pct", "")
        .replace("_", " ")
    return cleaned.split(" ").joinToString(" ") {
        it.replaceFirstChar(Char::titlecase)
    }
}

private fun formatValue(k: String, v: Float): String {
    val unit = when {
        "dps" in k || "tempo" in k -> "°/s"
        "pct" in k                 -> "%"
        else                       -> "°"
    }
    return "${"%.0f".format(v)}$unit"
}

private fun displayJudgment(j: String): String = when (j) {
    "joint_force"                  -> "Joint force"
    "clinical_injury_risk"         -> "Injury risk"
    "medical_diagnosis"            -> "Medical diagnosis"
    "muscle_activation_percentage" -> "Muscle activation %"
    else                            -> j.replace("_", " ")
        .replaceFirstChar(Char::titlecase)
}

private fun whyUnsupported(j: String): String = when (j) {
    "joint_force"                  -> "Pose-based, not measured force"
    "clinical_injury_risk"         -> "Out of scope — not a clinical tool"
    "medical_diagnosis"            -> "Movement quality only, not diagnosis"
    "muscle_activation_percentage" -> "Pose-based estimate, not EMG"
    else                            -> "Out of scope for single-camera pose"
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
