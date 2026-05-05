package com.gemmafit.ui.screens.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import kotlinx.coroutines.delay

// Empty State

@Composable
fun VideoEmptyState(
    onPickVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(SurfaceColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(56.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Upload a video to analyze",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Select a workout video and get real-time\nform analysis and coaching feedback.",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onPickVideo,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            shape = RoundedCornerShape(12.dp),
        ) {
            Icon(Icons.Filled.Videocam, null, tint = Background)
            Spacer(Modifier.width(8.dp))
            Text(
                "Choose Video",
                color = Background,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

// Loading State

@Composable
fun VideoLoadingState(
    currentFrame: Int,
    totalFrames: Int,
    processingFps: Float,
    poseHitRate: Float,
    subPhase: String,
    subPhaseProgress: Float,
    modifier: Modifier = Modifier,
) {
    val progress = when {
        subPhaseProgress > 0f -> subPhaseProgress
        totalFrames > 0 -> currentFrame.toFloat() / totalFrames
        else -> 0f
    }
    val waitingForFirstFrame = currentFrame <= 0 && progress <= 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "loading_progress",
    )
    var waitingProgress by remember { mutableFloatStateOf(0.08f) }
    LaunchedEffect(waitingForFirstFrame) {
        while (waitingForFirstFrame) {
            waitingProgress = if (waitingProgress >= 0.9f) 0.08f else waitingProgress + 0.06f
            delay(140L)
        }
        waitingProgress = 0.08f
    }
    val displayedProgress = if (waitingForFirstFrame) waitingProgress else animatedProgress

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = displayedProgress,
                modifier = Modifier.fillMaxSize(),
                color = Green,
                strokeWidth = 4.dp,
                trackColor = SurfaceColor,
            )
            Text(
                text = if (waitingForFirstFrame) "..." else "${(animatedProgress * 100).toInt()}",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = loadingLabelFor(subPhase),
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Frame $currentFrame / $totalFrames",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LoadingMetricChip(label = "FPS", value = "${processingFps.toInt()}")
            LoadingMetricChip(label = "Pose Hit", value = "${(poseHitRate * 100).toInt()}%")
        }

        // Sub-phase progress bar
        AnimatedVisibility(
            visible = subPhaseProgress > 0f && subPhaseProgress < 1f,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(SurfaceColor),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(subPhaseProgress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Blue),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingMetricChip(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceColor)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = TextSecondary, fontSize = 11.sp)
    }
}

// Error State

@Composable
fun VideoErrorState(
    message: String,
    onRetry: () -> Unit,
    onResetCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Red.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = Red,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Analysis Failed",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )

        Spacer(Modifier.height(32.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onResetCamera,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Back to Camera", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Retry", color = Background, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun loadingLabelFor(subPhase: String): String {
    return when (subPhase) {
        "preview_loading" -> "Preparing preview"
        "preview_analysis" -> "Preview analysis"
        "full_analysis" -> "Full analysis"
        "complete" -> "Analysis complete"
        else -> subPhase.replace("_", " ").ifBlank { "Analyzing video" }
    }
}
