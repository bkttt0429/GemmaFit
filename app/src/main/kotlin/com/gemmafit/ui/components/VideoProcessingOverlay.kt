package com.gemmafit.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.TextHint
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.VideoPhase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun VideoProcessingOverlay(
    phase: VideoPhase,
    progress: Float,
    currentFrame: Int,
    totalFrames: Int,
    fileName: String,
    onCancel: () -> Unit,
    etaSeconds: Int = 0,
    processingFps: Float = 0f,
    poseHitRate: Float = 0f,
    subPhase: String = "",
    subPhaseProgress: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "processing_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xEE0F1724)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
                .animateContentSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (phase) {
                is VideoPhase.Processing -> ProcessingAnimation(pulse = pulse)
                is VideoPhase.Analyzing -> AnalyzingAnimation(progress = progress, pulse = pulse)
                is VideoPhase.Error -> ErrorIcon()
                is VideoPhase.Complete -> SuccessIcon()
                else -> ProcessingAnimation(pulse = pulse)
            }

            Spacer(modifier = Modifier.height(32.dp))

            val isInitPhase = subPhase == "loading_model"

            Text(
                text = when {
                    subPhase == "loading_model" -> "Loading Model"
                    subPhase == "decoding" -> "Decoding Frames"
                    phase is VideoPhase.Analyzing -> "Analyzing Movement"
                    phase is VideoPhase.Error -> "Processing Failed"
                    phase is VideoPhase.Complete -> "Analysis Complete"
                    else -> "Preparing..."
                },
                style = MaterialTheme.typography.headlineMedium,
                color = when (phase) {
                    is VideoPhase.Error -> Red
                    is VideoPhase.Complete -> Green
                    else -> TextPrimary
                },
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when {
                    subPhase == "loading_model" -> "Initializing MediaPipe Pose..."
                    subPhase == "decoding" -> "Extracting video frames..."
                    phase is VideoPhase.Analyzing -> buildString {
                        append("Frame $currentFrame / $totalFrames")
                        if (poseHitRate > 0f) append("  ·  ${(poseHitRate * 100).toInt()}% detected")
                    }
                    phase is VideoPhase.Error -> (phase as VideoPhase.Error).message
                    phase is VideoPhase.Complete -> "Ready to view results"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (fileName.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .alpha(0.7f)
                        .background(Color(0x22FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (phase !is VideoPhase.Error && phase !is VideoPhase.Complete) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.8f),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0x33FFFFFF)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .background(
                                    Brush.horizontalGradient(listOf(Blue, Green)),
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )

                    if (progress > 0.05f && phase is VideoPhase.Analyzing) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = if (etaSeconds > 0) "~${formatEta(etaSeconds)} remaining" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextHint,
                            )
                            if (processingFps > 0f) {
                                Text(
                                    text = " ·  ${processingFps.toInt()} fps",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextHint,
                                )
                            }
                        }
                    } else if (phase is VideoPhase.Processing) {
                        Text(
                            text = when {
                                isInitPhase -> "Loading..."
                                subPhase == "decoding" -> "Decoding frames..."
                                else -> "Processing..."
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = TextHint,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0x22FFFFFF)),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.height(40.dp),
                ) {
                    Icon(Icons.Filled.Cancel, contentDescription = "Cancel", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Cancel", color = TextSecondary, fontSize = 14.sp)
                }
            }

            if (phase is VideoPhase.Error) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text("Retry", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ProcessingAnimation(pulse: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(1500, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "rotation",
    )
    Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pulse * 0.4f }) {
            drawCircle(color = Blue, radius = size.minDimension / 2, style = Stroke(width = 4.dp.toPx()))
        }
        Canvas(modifier = Modifier.fillMaxSize().rotate(rotation)) {
            val sw = 3.dp.toPx()
            drawArc(color = Green, startAngle = 0f, sweepAngle = 270f, useCenter = false,
                style = Stroke(width = sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2, sw / 2),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw))
        }
        Icon(Icons.Filled.Movie, contentDescription = null, tint = TextPrimary.copy(alpha = 0.6f), modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun AnalyzingAnimation(progress: Float, pulse: Float) {
    val infiniteTransition = rememberInfiniteTransition(label = "analyzing_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(2000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "rotation",
    )
    Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sw = 4.dp.toPx()
            drawArc(color = Color(0x33FFFFFF), startAngle = 0f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2, sw / 2),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw))
        }
        Canvas(modifier = Modifier.fillMaxSize().rotate(-90f)) {
            val sw = 4.dp.toPx()
            drawArc(color = Green, startAngle = 0f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                style = Stroke(width = sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2, sw / 2),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw))
        }
        Canvas(modifier = Modifier.size(16.dp).graphicsLayer { alpha = pulse }) {
            drawCircle(color = Green, radius = size.minDimension / 2)
        }
        Text(
            text = "${(progress * 100).toInt()}",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary, fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(0.5f),
        )
    }
}

@Composable
private fun SuccessIcon() {
    Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Green, modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun ErrorIcon() {
    Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Error, contentDescription = null, tint = Red, modifier = Modifier.size(56.dp))
    }
}

private fun formatEta(seconds: Int): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}
