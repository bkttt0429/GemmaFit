package com.gemmafit.ui.screens.video

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.VideoPhase

/**
 * Video file analysis dashboard.
 *
 * Layout architecture:
 *   ┌─ CompactTopBar (fixed, minimal) ─┐
 *   ├─ Hero Section (~45% height) ─────┤  Video + StatusHero
 *   ├─ Scrollable Detail (~55%) ───────┤  Scrubber, Metrics, Coach, Evidence
 *   └─ MinimalBottomBar (fixed) ───────┘
 */
@Composable
fun VideoAnalysisLayout(
    live: LiveWorkoutState,
    isPaused: Boolean,
    isProcessing: Boolean,
    videoUri: Uri?,
    analysisProgress: Float = 0f,
    analysisCurrentFrame: Int = 0,
    analysisTotalFrames: Int = 0,
    analysisLabel: String = "",
    showTrajectory: Boolean = false,
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTrajectory: () -> Unit = {},
    onResetCamera: () -> Unit,
    onPrevFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onSeekFrame: (Int) -> Unit,
    onPlaybackPosition: (Long) -> Unit,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
    onSubjectTap: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val scrollState = rememberScrollState()

    // Haptic feedback on critical warnings
    val criticalCount = live.activeWarnings.count {
        it.severity == "critical" || it.severity == "high"
    }
    androidx.compose.runtime.LaunchedEffect(criticalCount) {
        if (criticalCount > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        // ── Compact top bar ─────────────────────────────────────────
        CompactTopBar(
            onPickVideo = onPickVideo,
            onOpenSettings = onOpenSettings,
            showTrajectory = showTrajectory,
            onToggleTrajectory = onToggleTrajectory,
            onResetCamera = onResetCamera,
        )

        // ── Hero section (fixed ~45%) ───────────────────────────────
        Column(
            modifier = Modifier.weight(0.45f),
        ) {
            VideoHero(
                live = live,
                videoUri = videoUri,
                isPlaying = !isPaused && !isProcessing && live.totalFramesAnalyzed > 0,
                isAnalyzing = isProcessing,
                analysisProgress = analysisProgress,
                analysisFrameText = analysisFrameText(analysisCurrentFrame, analysisTotalFrames),
                showTrajectory = showTrajectory,
                onPlaybackPosition = onPlaybackPosition,
                onSubjectTap = onSubjectTap,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            StatusHero(
                live = live,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── Scrollable detail section (~55%) ────────────────────────
        val isPlaying = !isPaused && !isProcessing && live.totalFramesAnalyzed > 1
        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            if (isProcessing) {
                AnalysisProgressBanner(
                    label = analysisLabelFor(analysisLabel),
                    progress = analysisProgress,
                    frameText = analysisFrameText(analysisCurrentFrame, analysisTotalFrames),
                )
            } else if (live.totalFramesAnalyzed > 1) {
                FrameScrubber(
                    current = live.currentFrameIndex,
                    total = live.totalFramesAnalyzed,
                    timestampMs = live.currentFrameTimestampMs,
                    isPlaying = isPlaying,
                    onPrev = onPrevFrame,
                    onNext = onNextFrame,
                    onSeek = onSeekFrame,
                    onTogglePlay = onTogglePause,
                )
            }

            // Live Metrics (always visible, values are smoothed via animation)
            MetricsGrid(
                metrics = live.templateMetrics,
                qualityFlags = live.qualityFlags,
            )

            // CoachPanel: filter low-priority messages when playing to reduce noise
            val effectiveCoachMessage = if (isPlaying && live.coachPriority !in setOf("high", "critical")) {
                ""
            } else {
                live.coachMessage
            }
            CoachPanel(
                message = effectiveCoachMessage,
                priority = live.coachPriority,
                source = if (live.coachMessage.isNotEmpty()) live.coachInsight.backend else "",
            )

            // Evidence (always visible, collapsed by default inside)
            EvidencePanel(
                card = live.evidenceCard,
                qualityFlags = live.qualityFlags,
            )

            Spacer(Modifier.height(72.dp)) // breathing room for bottom bar
        }

        // ── Minimal bottom bar ──────────────────────────────────────
        MinimalBottomBar(
            isPaused = isPaused,
            summaryLabel = if (live.analysisStage == "Full analysis running") {
                "Partial Summary"
            } else {
                "View Summary"
            },
            onTogglePause = onTogglePause,
            onViewSummary = onViewSummary,
        )
    }
}

// ── CompactTopBar ───────────────────────────────────────────────────

@Composable
private fun CompactTopBar(
    onPickVideo: () -> Unit,
    onOpenSettings: () -> Unit,
    showTrajectory: Boolean,
    onToggleTrajectory: () -> Unit,
    onResetCamera: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(SurfaceColor.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Live indicator
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Green),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Video Analysis",
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
            onClick = onResetCamera,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Back to camera",
                tint = TextSecondary,
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

// ── MinimalBottomBar ────────────────────────────────────────────────

@Composable
private fun MinimalBottomBar(
    isPaused: Boolean,
    summaryLabel: String,
    onTogglePause: () -> Unit,
    onViewSummary: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceColor.copy(alpha = 0.85f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Play/Pause
        Button(
            onClick = onTogglePause,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) {
            Icon(
                if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (isPaused) "Play" else "Pause",
                tint = TextPrimary,
            )
        }

        // Summary (primary action)
        Button(
            onClick = onViewSummary,
            colors = ButtonDefaults.buttonColors(containerColor = Blue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .weight(2.5f)
                .height(48.dp),
        ) {
            Icon(Icons.Filled.BarChart, null, tint = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                summaryLabel,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun AnalysisProgressBanner(
    label: String,
    progress: Float,
    frameText: String,
) {
    val safeProgress = progress.coerceIn(0f, 0.99f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceColor)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${(safeProgress * 100).toInt()}%",
                color = Green,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safeProgress)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Blue),
            )
        }
        Text(
            text = frameText.ifBlank { "Preparing frames..." },
            color = TextSecondary,
            fontSize = 12.sp,
        )
    }
}

private fun analysisLabelFor(label: String): String {
    return when (label) {
        "preview_loading" -> "Preparing preview"
        "preview_analysis" -> "Preview analysis"
        "full_analysis" -> "Full analysis"
        "complete" -> "Analysis complete"
        else -> label.replace("_", " ").ifBlank { "Analyzing video" }
    }
}

private fun analysisFrameText(currentFrame: Int, totalFrames: Int): String {
    return if (totalFrames > 0) {
        "Frame ${currentFrame.coerceAtLeast(0)} / $totalFrames"
    } else {
        ""
    }
}
