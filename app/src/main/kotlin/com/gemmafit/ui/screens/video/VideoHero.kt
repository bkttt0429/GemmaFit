package com.gemmafit.ui.screens.video

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.gemmafit.R
import com.gemmafit.ui.localization.LocalAppStrings
import com.gemmafit.ui.overlay.PoseCueMapper
import com.gemmafit.ui.overlay.PoseLandmark
import com.gemmafit.ui.overlay.PoseOverlay
import com.gemmafit.ui.overlay.PoseOverlayState
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.ReviewFrameStatus
import com.gemmafit.video.SubjectLockStatus
import kotlinx.coroutines.delay

private const val PLAYBACK_SYNC_INTERVAL_MS = 250L
private const val RENDERED_FRAME_SYNC_INTERVAL_MS = 180L
private const val MIN_HERO_DISPLAY_ASPECT = 1.18f
private const val MAX_HERO_DISPLAY_ASPECT = 1.78f

/**
 * Hero video card with embedded PoseOverlay and frame counter.
 * Maintains aspect ratio and provides a contained viewing area.
 */
@Composable
fun VideoHero(
    live: LiveWorkoutState,
    videoUri: Uri?,
    isPlaying: Boolean,
    isAnalyzing: Boolean = false,
    analysisProgress: Float = 0f,
    analysisFrameText: String = "",
    analysisStatusText: String = "",
    useLoopingAnalysisProgress: Boolean = false,
    maxPlaybackPositionMs: Long = Long.MAX_VALUE,
    videoAudioEnabled: Boolean = false,
    videoAudioVolume: Float = 0f,
    showTrajectory: Boolean = false,
    onPlaybackPosition: (Long) -> Unit,
    onPlaybackLimitReached: () -> Unit = {},
    onSubjectTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = LocalAppStrings.current
    val loopingAnalysisProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0.08f) }
    LaunchedEffect(isAnalyzing, useLoopingAnalysisProgress) {
        while (isAnalyzing && useLoopingAnalysisProgress) {
            loopingAnalysisProgress.floatValue = if (loopingAnalysisProgress.floatValue >= 0.9f) {
                0.08f
            } else {
                loopingAnalysisProgress.floatValue + 0.06f
            }
            delay(140L)
        }
        if (!isAnalyzing) {
            loopingAnalysisProgress.floatValue = 0.08f
        }
    }
    val frameAspect = if (live.videoPreviewWidth > 0 && live.videoPreviewHeight > 0) {
        live.videoPreviewWidth.toFloat() / live.videoPreviewHeight.toFloat()
    } else {
        16f / 9f
    }
    val displayedAnalysisProgress = if (isAnalyzing && useLoopingAnalysisProgress) {
        loopingAnalysisProgress.floatValue
    } else if (isAnalyzing) {
        analysisProgress.coerceIn(0f, 0.99f)
    } else if (live.totalFramesAnalyzed > 1) {
        live.currentFrameIndex.toFloat() / (live.totalFramesAnalyzed - 1).coerceAtLeast(1)
    } else {
        0f
    }
    val displayedAnalysisText = analysisStatusText
        .ifBlank { displayAnalysisStage(live.analysisStage) }
        .ifBlank { if (isAnalyzing) copy.analyzingVideo else "" }
    val hasPreviewBitmap = live.videoPreview != null && !live.videoPreview.isRecycled
    val shouldShowPlaybackPlayer = videoUri != null &&
            live.totalFramesAnalyzed > 0 &&
            isPlaying
    val shouldShowLoadingPlayer = videoUri != null && isAnalyzing && !hasPreviewBitmap
    val playbackFrameReady = remember(videoUri, shouldShowPlaybackPlayer, shouldShowLoadingPlayer) {
        mutableStateOf(false)
    }
    val heroShape = RoundedCornerShape(16.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(frameAspect.coerceIn(MIN_HERO_DISPLAY_ASPECT, MAX_HERO_DISPLAY_ASPECT))
            .clipToBounds(),
        color = SurfaceColor,
        shape = heroShape,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(heroShape)
                .clipToBounds()
                .pointerInput(live.videoPreviewWidth, live.videoPreviewHeight) {
                    detectTapGestures { tap ->
                        normalizedTapToContent(
                            tap = tap,
                            containerWidth = size.width.toFloat(),
                            containerHeight = size.height.toFloat(),
                            sourceWidth = live.videoPreviewWidth.toFloat(),
                            sourceHeight = live.videoPreviewHeight.toFloat(),
                        )?.let { (x, y) -> onSubjectTap(x, y) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // Video or bitmap content
            if (hasPreviewBitmap) {
                Image(
                    bitmap = live.videoPreview!!.asImageBitmap(),
                    contentDescription = "Video frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            if (shouldShowPlaybackPlayer || shouldShowLoadingPlayer) {
                SmoothVideoPlayer(
                    uri = videoUri!!,
                    isPlaying = isPlaying && live.totalFramesAnalyzed > 0,
                    targetPositionMs = if (shouldShowLoadingPlayer) 0L else live.currentFrameTimestampMs,
                    maxPlaybackPositionMs = maxPlaybackPositionMs,
                    videoVolume = if (videoAudioEnabled) videoAudioVolume.coerceIn(0f, 1f) else 0f,
                    onPlaybackPosition = onPlaybackPosition,
                    onPlaybackLimitReached = onPlaybackLimitReached,
                    onPlaybackFrameRendered = { playbackFrameReady.value = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                )
            }

            if ((shouldShowPlaybackPlayer || shouldShowLoadingPlayer) &&
                hasPreviewBitmap &&
                !playbackFrameReady.value
            ) {
                Image(
                    bitmap = live.videoPreview!!.asImageBitmap(),
                    contentDescription = "Video frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else if (!hasPreviewBitmap && !shouldShowPlaybackPlayer && !shouldShowLoadingPlayer && isAnalyzing) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Canvas(modifier = Modifier.size(42.dp)) {
                        val sw = 4.dp.toPx()
                        drawArc(
                            color = Green,
                            startAngle = -90f,
                            sweepAngle = 360f * displayedAnalysisProgress.coerceIn(0f, 1f),
                            useCenter = false,
                            style = Stroke(width = sw, cap = StrokeCap.Round),
                            topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = displayedAnalysisText,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Pose overlay
            val renderSecondarySkeletons = shouldRenderSecondarySkeletons(
                subjectLockStatus = live.subjectLockStatus,
                hasActiveSubject = live.poseLandmarks.isNotEmpty(),
            )
            if (live.poseLandmarks.isNotEmpty() || (renderSecondarySkeletons && live.poseCandidates.isNotEmpty())) {
                val secondarySubjects = if (renderSecondarySkeletons) {
                    live.poseCandidates.mapIndexedNotNull { index, candidate ->
                        if (index == live.activeSubjectIndex) {
                            null
                        } else {
                            candidate.landmarks.map { PoseLandmark(it.x, it.y, it.visibility) }
                        }
                    }
                } else {
                    emptyList()
                }
                val cueOverlay = PoseCueMapper.fromWarnings(live.activeWarnings)
                val overlayState = PoseOverlayState(
                    landmarks = live.poseLandmarks.map {
                        PoseLandmark(it.x, it.y, it.visibility)
                    },
                    secondarySubjects = secondarySubjects,
                    trajectoryFrames = if (showTrajectory) {
                        live.poseTrajectory.map { frame ->
                            frame.map { PoseLandmark(it.x, it.y, it.visibility) }
                        }
                    } else {
                        emptyList()
                    },
                    violationJoints = cueOverlay.safetyJoints,
                    violationSegments = cueOverlay.safetySegments,
                    watchJoints = cueOverlay.watchJoints,
                    watchSegments = cueOverlay.watchSegments,
                )
                PoseOverlay(
                    state = overlayState,
                    modifier = Modifier.fillMaxSize(),
                    width = live.videoPreviewWidth.toFloat(),
                    height = live.videoPreviewHeight.toFloat(),
                )
            }

            val subjectText = when (live.subjectLockStatus) {
                SubjectLockStatus.NEEDS_SELECTION -> {
                    when {
                        "AUTO_SELECTION_PENDING" in live.subjectTrustFlags -> copy.subjectAutoSelecting
                        live.poseCandidates.size > 1 ||
                            "MULTI_PERSON" in live.subjectTrustFlags ||
                            "NEEDS_SELECTION" in live.subjectTrustFlags -> copy.tapYourself
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
            val showCapabilityOverlay = shouldShowCapabilityOverlay(live)
            if (subjectText.isNotBlank() || showCapabilityOverlay) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    if (subjectText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = subjectText,
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    CapabilityContractOverlay(
                        live = live,
                        compact = true,
                    )
                }
            }

            val reviewNotice = reviewFrameNotice(live.reviewFrameStatus)
            if (reviewNotice.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(
                            start = 10.dp,
                            end = 10.dp,
                            bottom = if (displayedAnalysisText.isNotBlank() &&
                                live.analysisStage != "Full analysis complete"
                            ) {
                                44.dp
                            } else {
                                10.dp
                            },
                        )
                        .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = reviewNotice,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            if (displayedAnalysisText.isNotBlank() && live.analysisStage != "Full analysis complete") {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = displayedAnalysisText,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            RefusalMomentOverlay(
                live = live,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 18.dp),
            )

            // Frame counter / progress overlay
            if (isAnalyzing || live.totalFramesAnalyzed > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isPlaying || isAnalyzing) {
                            Canvas(modifier = Modifier.size(14.dp)) {
                                val sw = 2.dp.toPx()
                                drawArc(
                                    color = Green,
                                    startAngle = -90f,
                                    sweepAngle = 360f * displayedAnalysisProgress.coerceIn(0f, 1f),
                                    useCenter = false,
                                    style = Stroke(width = sw, cap = StrokeCap.Round),
                                    topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
                                    size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                                )
                            }
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            text = if (isAnalyzing) {
                                if (useLoopingAnalysisProgress) {
                                    analysisFrameText.ifBlank { displayedAnalysisText }
                                } else {
                                    val percent = (displayedAnalysisProgress * 100).toInt()
                                    if (analysisFrameText.isNotBlank()) "$percent%  $analysisFrameText" else "$percent%"
                                }
                            } else {
                                "${live.currentFrameIndex + 1}/${live.totalFramesAnalyzed}"
                            },
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

fun shouldRenderSecondarySkeletons(
    subjectLockStatus: SubjectLockStatus,
    hasActiveSubject: Boolean,
): Boolean {
    return hasActiveSubject &&
        subjectLockStatus != SubjectLockStatus.NEEDS_SELECTION &&
        subjectLockStatus != SubjectLockStatus.SUBJECT_LOST
}

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

@Composable
private fun displayAnalysisStage(stage: String): String {
    val copy = LocalAppStrings.current
    return when (stage) {
        "Preview analysis running" -> copy.previewAnalysis
        "Full analysis running" -> copy.fullAnalysis
        "Preview complete" -> copy.preparingPreview
        else -> stage
    }
}

private fun reviewFrameNotice(status: ReviewFrameStatus): String {
    return when {
        status.bitmapRestoring -> "Frame image restoring..."
        status.bitmapRestoreFailed -> "Frame image restore failed"
        status.noPoseReason == "multi_person_selection_required" ||
            status.noPoseReason == "multi_person_detector_guard" -> {
            "Multiple people detected. Tap yourself to start."
        }
        status.poseHiddenByQuality -> "Pose preview only: ${status.noPoseReason.ifBlank { "low confidence" }}"
        !status.poseAvailable && status.noPoseReason == "no_person_detected" -> {
            "No pose detected in this frame"
        }
        else -> ""
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun SmoothVideoPlayer(
    uri: Uri,
    isPlaying: Boolean,
    targetPositionMs: Long,
    maxPlaybackPositionMs: Long,
    videoVolume: Float,
    onPlaybackPosition: (Long) -> Unit,
    onPlaybackLimitReached: () -> Unit,
    onPlaybackFrameRendered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playbackPositionCallback = androidx.compose.runtime.rememberUpdatedState(onPlaybackPosition)
    val playbackLimitCallback = androidx.compose.runtime.rememberUpdatedState(onPlaybackLimitReached)
    val playbackFrameRenderedCallback = androidx.compose.runtime.rememberUpdatedState(onPlaybackFrameRendered)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val playerViewRef = remember(uri) { arrayOfNulls<PlayerView>(1) }
    val renderedFrameSyncRef = remember(uri) { longArrayOf(Long.MIN_VALUE, 0L) }
    val firstFrameRenderedRef = remember(uri) { booleanArrayOf(false) }
    val frameMetadataListener = remember(uri) {
        VideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            val renderedPositionMs = presentationTimeUs / 1_000L
            val nowMs = android.os.SystemClock.elapsedRealtime()
            if (!firstFrameRenderedRef[0]) {
                firstFrameRenderedRef[0] = true
                mainHandler.post {
                    playbackFrameRenderedCallback.value()
                }
            }
            val lastPositionMs = renderedFrameSyncRef[0]
            val lastCallbackMs = renderedFrameSyncRef[1]
            val movedEnough = kotlin.math.abs(renderedPositionMs - lastPositionMs) >= 16L
            val elapsedEnough = nowMs - lastCallbackMs >= RENDERED_FRAME_SYNC_INTERVAL_MS
            if (movedEnough && elapsedEnough) {
                renderedFrameSyncRef[0] = renderedPositionMs
                renderedFrameSyncRef[1] = nowMs
                mainHandler.post {
                    playbackPositionCallback.value(renderedPositionMs)
                }
            }
        }
    }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            setMediaItem(MediaItem.fromUri(uri))
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
            volume = videoVolume.coerceIn(0f, 1f)
            prepare()
            val initialPositionMs = if (maxPlaybackPositionMs in 1 until Long.MAX_VALUE) {
                targetPositionMs.coerceAtMost(maxPlaybackPositionMs)
            } else {
                targetPositionMs
            }
            if (initialPositionMs > 0L) seekTo(initialPositionMs)
        }
    }

    DisposableEffect(player) {
        player.setVideoFrameMetadataListener(frameMetadataListener)
        onDispose {
            player.clearVideoFrameMetadataListener(frameMetadataListener)
        }
    }

    LaunchedEffect(player, isPlaying, maxPlaybackPositionMs) {
        if (isPlaying) {
            player.play()
        } else {
            player.pause()
        }
    }

    LaunchedEffect(player, videoVolume) {
        player.volume = videoVolume.coerceIn(0f, 1f)
    }

    LaunchedEffect(player, targetPositionMs, maxPlaybackPositionMs, isPlaying) {
        val target = if (maxPlaybackPositionMs in 1 until Long.MAX_VALUE) {
            targetPositionMs.coerceAtMost(maxPlaybackPositionMs)
        } else {
            targetPositionMs
        }.coerceAtLeast(0L)
        val driftMs = kotlin.math.abs(player.currentPosition - target)
        if (!isPlaying || driftMs > 1_000L) {
            player.seekTo(target)
        }
    }

    DisposableEffect(player) {
        onDispose {
            playerViewRef[0]?.player = null
            player.release()
        }
    }

    LaunchedEffect(player, maxPlaybackPositionMs) {
        var limitNotified = false
        while (true) {
            val current = player.currentPosition
            val hasPlaybackLimit = maxPlaybackPositionMs in 1 until Long.MAX_VALUE
            if (hasPlaybackLimit && current >= maxPlaybackPositionMs) {
                if (!limitNotified) {
                    player.seekTo(maxPlaybackPositionMs)
                    player.pause()
                    playbackPositionCallback.value(maxPlaybackPositionMs)
                    limitNotified = true
                    playbackLimitCallback.value()
                } else {
                    playbackPositionCallback.value(maxPlaybackPositionMs)
                }
            } else {
                limitNotified = false
                playbackPositionCallback.value(current)
            }
            delay(PLAYBACK_SYNC_INTERVAL_MS)
        }
    }

    AndroidView(
        factory = { ctx ->
            (LayoutInflater.from(ctx).inflate(
                R.layout.gemmafit_review_player_view,
                null,
                false,
            ) as PlayerView).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setUseArtwork(false)
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
                playerViewRef[0] = this
            }
        },
        update = { playerView ->
            if (playerViewRef[0] !== playerView) {
                playerViewRef[0] = playerView
            }
            if (playerView.player !== player) {
                playerView.player = player
            }
        },
        modifier = modifier.clipToBounds(),
    )
}
