package com.gemmafit.ui.screens.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.ReviewCue
import kotlinx.coroutines.delay

private val ScrubberGray = Color(0xFF7A7A7A)
private val ScrubberBase = Color(0xFF272727)

/**
 * Processing-aware timeline:
 * red = processed frames that can be reviewed immediately,
 * gray = latest scanned/analyzed cursor,
 * dark = remaining video.
 */
@Composable
fun ProcessingPreviewScrubber(
    label: String,
    totalFrames: Int,
    processedFrames: Int,
    scannedFrame: Int,
    currentFrame: Int,
    timestampMs: Long,
    isPlaying: Boolean,
    isProcessing: Boolean,
    videoAudioEnabled: Boolean = false,
    videoAudioVolume: Float = 0f,
    reviewCues: List<ReviewCue> = emptyList(),
    onToggleVideoAudio: () -> Unit = {},
    onVideoAudioVolumeChange: (Float) -> Unit = {},
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onJumpToLatest: () -> Unit = {},
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showVolumeSlider by remember { mutableStateOf(false) }
    var ellipsisCount by remember { mutableStateOf(1) }
    val displayLabel = userVisibleScrubberLabel(label)
    val stageUi = scrubberStageUi(
        label = displayLabel,
        isProcessing = isProcessing,
        processedFrames = processedFrames,
        scannedFrame = scannedFrame,
        totalFrames = totalFrames,
    )
    val showProgressLabel = stageUi.busy
    val safeTotal = totalFrames.coerceAtLeast(processedFrames).coerceAtLeast(1)
    val seekableFrames = processedFrames.coerceAtLeast(0)
    val canSeek = seekableFrames > 1
    val processedProgress = (processedFrames.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
    val scannedProgress = if (isProcessing) {
        ((scannedFrame + 1).toFloat() / safeTotal.toFloat()).coerceIn(processedProgress, 1f)
    } else {
        processedProgress
    }
    val thumbProgress = if (processedFrames > 1) {
        (
            currentFrame.toFloat().coerceIn(0f, (processedFrames - 1).toFloat()) /
                (processedFrames - 1).toFloat()
            ) * processedProgress
    } else {
        processedProgress
    }
    val currentDisplayFrame = if (seekableFrames > 0) {
        currentFrame.coerceIn(0, seekableFrames - 1) + 1
    } else {
        0
    }
    val scannedDisplayFrame = if (isProcessing) {
        (scannedFrame + 1).coerceIn(0, safeTotal)
    } else {
        seekableFrames.coerceIn(0, safeTotal)
    }
    val processingText = if (isProcessing) {
        "Processing $scannedDisplayFrame / $safeTotal"
    } else {
        "Analyzed ${seekableFrames.coerceIn(0, safeTotal)} / $safeTotal"
    }
    val reviewText = if (seekableFrames > 0) {
        "Review $currentDisplayFrame / $seekableFrames"
    } else {
        "Review 0 / 0"
    }
    val showJumpToLatest = isProcessing && seekableFrames > 0 && currentFrame < seekableFrames - 1
    val effectiveVolume = if (videoAudioEnabled) videoAudioVolume.coerceIn(0f, 1f) else 0f
    val volumeLabel = if (effectiveVolume <= 0.01f) {
        "Video audio off"
    } else {
        "Video audio ${(effectiveVolume * 100).toInt()}%"
    }
    fun seekFrameForPosition(x: Float, width: Float): Int? {
        if (processedFrames <= 0 || processedProgress <= 0f || width <= 0f) return null
        val seekableEnd = processedProgress * width
        val progress = (x.coerceIn(0f, seekableEnd) / width).coerceIn(0f, processedProgress)
        val frame = ((progress / processedProgress) * (processedFrames - 1).coerceAtLeast(0)).toInt()
        return frame.coerceIn(0, (processedFrames - 1).coerceAtLeast(0))
    }
    LaunchedEffect(showProgressLabel) {
        ellipsisCount = 1
        while (showProgressLabel) {
            delay(420L)
            ellipsisCount = if (ellipsisCount >= 3) 1 else ellipsisCount + 1
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceColor.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (stageUi.busy) {
                        Box(
                            modifier = Modifier.size(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Green.copy(alpha = 0.16f)),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Green),
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (stageUi.busy) {
                                stageUi.title.trimEnd('.') + ".".repeat(ellipsisCount)
                            } else {
                                stageUi.title
                            },
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stageUi.detail,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = processingText,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (showJumpToLatest) {
                        Text(
                            text = "Latest",
                            color = Green,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Green.copy(alpha = 0.16f))
                                .clickable(onClick = onJumpToLatest)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    text = "$reviewText  ${formatTimestamp(timestampMs)}",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onTogglePlay,
                enabled = canSeek,
                modifier = Modifier.size(36.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(SurfaceColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (canSeek) Green else TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            IconButton(
                onClick = onPrev,
                enabled = canSeek,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous frame",
                    tint = if (canSeek) TextPrimary else TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .pointerInput(safeTotal, processedFrames, processedProgress) {
                        detectTapGestures { tap ->
                            seekFrameForPosition(tap.x, size.width.toFloat())?.let(onSeekFrame)
                        }
                    }
                    .pointerInput(safeTotal, processedFrames, processedProgress) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                seekFrameForPosition(offset.x, size.width.toFloat())?.let(onSeekFrame)
                            },
                            onDrag = { change, _ ->
                                seekFrameForPosition(change.position.x, size.width.toFloat())?.let(onSeekFrame)
                            },
                        )
                    },
            ) {
                val y = size.height / 2f
                val stroke = 4.dp.toPx()
                val start = Offset(0f, y)
                val end = Offset(size.width, y)
                drawLine(
                    color = ScrubberBase,
                    start = start,
                    end = end,
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = ScrubberGray,
                    start = start,
                    end = Offset(size.width * scannedProgress, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Green,
                    start = start,
                    end = Offset(size.width * processedProgress, y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                val markerTop = y - 8.dp.toPx()
                val markerBottom = y + 8.dp.toPx()
                val maxCueFrame = (processedFrames - 1).coerceAtLeast(1)
                reviewCues
                    .filter { it.frameIndex in 0 until processedFrames }
                    .forEach { cue ->
                        val cueProgress = (
                            cue.frameIndex.toFloat() / maxCueFrame.toFloat()
                            ).coerceIn(0f, 1f) * processedProgress
                        val cueX = size.width * cueProgress
                        val cueColor = reviewCueColor(cue.severity)
                        drawLine(
                            color = cueColor.copy(alpha = 0.78f),
                            start = Offset(cueX, markerTop),
                            end = Offset(cueX, markerBottom),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = cueColor,
                            radius = 3.5.dp.toPx(),
                            center = Offset(cueX, markerTop),
                        )
                    }
                drawCircle(
                    color = Green,
                    radius = 7.dp.toPx(),
                    center = Offset(size.width * thumbProgress, y),
                )
            }

            IconButton(
                onClick = onNext,
                enabled = canSeek,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next frame",
                    tint = if (canSeek) TextPrimary else TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }

            IconButton(
                onClick = { showVolumeSlider = !showVolumeSlider },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (effectiveVolume <= 0.01f) {
                        Icons.Filled.VolumeOff
                    } else {
                        Icons.Filled.VolumeUp
                    },
                    contentDescription = volumeLabel,
                    tint = if (effectiveVolume <= 0.01f) TextSecondary else Green,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (showVolumeSlider) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleVideoAudio,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (effectiveVolume <= 0.01f) {
                            Icons.Filled.VolumeOff
                        } else {
                            Icons.Filled.VolumeUp
                        },
                        contentDescription = volumeLabel,
                        tint = if (effectiveVolume <= 0.01f) TextSecondary else Green,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Slider(
                    value = effectiveVolume,
                    onValueChange = { onVideoAudioVolumeChange(it.coerceIn(0f, 1f)) },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (effectiveVolume <= 0.01f) "Off" else "${(effectiveVolume * 100).toInt()}%",
                    color = TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun reviewCueColor(severity: String): Color {
    return when (severity.lowercase()) {
        "critical" -> Red
        "warning" -> Orange
        else -> Blue
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms.coerceAtLeast(0L) / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

private fun userVisibleScrubberLabel(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return trimmed
    val normalized = trimmed
        .removePrefix("```json")
        .removePrefix("```")
        .trim()
    return when {
        looksLikeToolCallJson(normalized) -> "Local Gemma is preparing the coach summary."
        normalized.contains("Validating Local Gemma JSON", ignoreCase = true) ->
            "Checking Local Gemma response before showing it."
        normalized.contains("Local Gemma summary queued", ignoreCase = true) ->
            "Local Gemma is queued. You can keep reviewing frames."
        normalized.contains("Local Gemma is streaming", ignoreCase = true) ->
            "Local Gemma is writing the coach summary."
        normalized.contains("Local Gemma is writing the coach summary", ignoreCase = true) ->
            "Local Gemma is writing the coach summary."
        normalized.contains("Local Gemma summary is ready", ignoreCase = true) ->
            "Analysis complete"
        else -> normalized
    }
}

private fun looksLikeToolCallJson(text: String): Boolean {
    val compact = text.take(240).lowercase()
    return (
        compact.startsWith("{") ||
            compact.startsWith("[") ||
            compact.startsWith("\"") ||
            compact.startsWith("json")
        ) && (
        "create_care_activity_log" in compact ||
            "\"function\"" in compact ||
            "\"arguments\"" in compact
        )
}

private fun shouldShowScrubberProgress(label: String): Boolean {
    val lower = label.lowercase()
    return "local gemma" in lower &&
        "ready" !in lower &&
        "session summary" !in lower
}

private data class ScrubberStageUi(
    val title: String,
    val detail: String,
    val busy: Boolean,
)

private fun scrubberStageUi(
    label: String,
    isProcessing: Boolean,
    processedFrames: Int,
    scannedFrame: Int,
    totalFrames: Int,
): ScrubberStageUi {
    val lower = label.lowercase()
    val safeTotal = totalFrames.coerceAtLeast(processedFrames).coerceAtLeast(1)
    val scannedDisplayFrame = (scannedFrame + 1).coerceIn(0, safeTotal)
    val analyzedDisplayFrame = processedFrames.coerceIn(0, safeTotal)
    return when {
        isProcessing -> ScrubberStageUi(
            title = "Step 1 of 2 - Analyzing video",
            detail = "Scanning frame $scannedDisplayFrame / $safeTotal. You can review processed frames.",
            busy = true,
        )
        "local gemma" in lower || "coach summary" in lower -> ScrubberStageUi(
            title = "Step 2 of 2 - Writing AI summary",
            detail = "Video analysis is complete. Local Gemma is reading the evidence.",
            busy = true,
        )
        "analysis complete" in lower || "ready" in lower -> ScrubberStageUi(
            title = "Ready - Summary available",
            detail = "Analyzed $analyzedDisplayFrame / $safeTotal. Review frames or tap View Summary.",
            busy = false,
        )
        else -> ScrubberStageUi(
            title = label.ifBlank { "Preparing analysis" },
            detail = "Analyzed $analyzedDisplayFrame / $safeTotal.",
            busy = shouldShowScrubberProgress(label),
        )
    }
}
