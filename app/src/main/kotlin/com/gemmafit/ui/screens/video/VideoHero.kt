package com.gemmafit.ui.screens.video

import android.net.Uri
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.gemmafit.ui.overlay.PoseLandmark
import com.gemmafit.ui.overlay.PoseOverlay
import com.gemmafit.ui.overlay.PoseOverlayState
import com.gemmafit.ui.theme.BackgroundGradientEnd
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.SubjectLockStatus
import kotlinx.coroutines.delay

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
    showTrajectory: Boolean = false,
    onPlaybackPosition: (Long) -> Unit,
    onSubjectTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frameAspect = if (live.videoPreviewWidth > 0 && live.videoPreviewHeight > 0) {
        live.videoPreviewWidth.toFloat() / live.videoPreviewHeight.toFloat()
    } else {
        16f / 9f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(frameAspect.coerceIn(0.56f, 1.78f)),
        color = BackgroundGradientEnd,
        shape = RoundedCornerShape(16.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
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
            if (videoUri != null && isPlaying) {
                SmoothVideoPlayer(
                    uri = videoUri,
                    targetPositionMs = live.currentFrameTimestampMs,
                    onPlaybackPosition = onPlaybackPosition,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (live.videoPreview != null) {
                Image(
                    bitmap = live.videoPreview!!.asImageBitmap(),
                    contentDescription = "Video frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            // Pose overlay
            if (live.poseLandmarks.isNotEmpty() || live.poseCandidates.isNotEmpty()) {
                val secondarySubjects = live.poseCandidates.mapIndexedNotNull { index, candidate ->
                    if (index == live.activeSubjectIndex) {
                        null
                    } else {
                        candidate.landmarks.map { PoseLandmark(it.x, it.y, it.visibility) }
                    }
                }
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

            val subjectText = when (live.subjectLockStatus) {
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
            if (subjectText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
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

            if (live.analysisStage.isNotBlank() && live.analysisStage != "Full analysis complete") {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = live.analysisStage,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Frame counter / progress overlay
            if (isAnalyzing || live.totalFramesAnalyzed > 1) {
                val progress = if (isAnalyzing) {
                    analysisProgress.coerceIn(0f, 0.99f)
                } else if (live.totalFramesAnalyzed > 1) {
                    live.currentFrameIndex.toFloat() / (live.totalFramesAnalyzed - 1).coerceAtLeast(1)
                } else 0f

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
                                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                                    useCenter = false,
                                    style = Stroke(width = sw, cap = StrokeCap.Round),
                                    topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
                                    size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw),
                                )
                            }
                        } else {
                            Icon(
                                Icons.Filled.PlayArrow,
                                null,
                                tint = Green,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = if (isAnalyzing) {
                                val percent = (progress * 100).toInt()
                                if (analysisFrameText.isNotBlank()) "$percent%  $analysisFrameText" else "$percent%"
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
private fun SmoothVideoPlayer(
    uri: Uri,
    targetPositionMs: Long,
    onPlaybackPosition: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val textureViewRef = remember(uri) { arrayOfNulls<TextureView>(1) }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = true
            prepare()
            if (targetPositionMs > 0L) seekTo(targetPositionMs)
        }
    }

    DisposableEffect(player) {
        onDispose {
            textureViewRef[0]?.let { player.clearVideoTextureView(it) }
            player.release()
        }
    }

    LaunchedEffect(player, targetPositionMs) {
        val driftMs = kotlin.math.abs(player.currentPosition - targetPositionMs)
        if (driftMs > 50L) {
            player.seekTo(targetPositionMs)
        }
    }

    LaunchedEffect(player) {
        while (true) {
            onPlaybackPosition(player.currentPosition)
            delay(33L)
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                textureViewRef[0] = this
                player.setVideoTextureView(this)
            }
        },
        update = { textureView ->
            if (textureViewRef[0] !== textureView) {
                textureViewRef[0] = textureView
                player.setVideoTextureView(textureView)
            }
        },
        modifier = modifier,
    )
}
