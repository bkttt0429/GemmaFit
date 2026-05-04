package com.gemmafit.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemmafit.ui.components.HorizontalMetricBar
import com.gemmafit.ui.components.StatCard
import com.gemmafit.ui.screens.summary.FormScoreTrendChart
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextHint
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.SessionSummary

@Composable
fun SummaryScreen(
    session: SessionSummary,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detection = session.detection
    val safetyEvents = session.safetyEvents
    val formScores = session.formScores
    val muscles = session.muscleFocusDistribution
    val tips = session.coachTips

    val criticalCount = safetyEvents.count { it.severity == "high" }
    val warningCount = safetyEvents.count { it.severity == "medium" }
    val cleanPercent = if (session.totalFrames > 0) {
        ((session.totalFrames - safetyEvents.size).coerceAtLeast(0) * 100 / session.totalFrames)
    } else 100

    val durationStr = String.format("%02d:%02d", session.durationSeconds / 60, session.durationSeconds % 60)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Background,
                        Color(0xFF0E1210),
                        Background,
                    ),
                ),
            ),
    ) {
        SummaryTopBar(onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top stats
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    title = "Total reps",
                    value = "${session.totalReps}",
                    helper = detection.mainExercise.replace("_", " ").replaceFirstChar { it.uppercase() },
                    accent = Green,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Form score",
                    value = "${session.avgFormScore.toInt()}%",
                    helper = "$cleanPercent% clean",
                    accent = Green,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "Duration",
                    value = durationStr,
                    helper = "${session.totalFrames} frames",
                    accent = Blue,
                    modifier = Modifier.weight(1f),
                )
            }

            // Form score trend with safety markers
            if (formScores.isNotEmpty()) {
                SummaryPanel(title = "Form Score Trend") {
                    FormScoreTrendChart(
                        scores = formScores,
                        safetyEvents = safetyEvents,
                        totalFrames = session.totalFrames,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                }
            }

            // Safety events + exercise distribution
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPanel(title = "Safety Events", modifier = Modifier.weight(1f)) {
                    if (safetyEvents.isNotEmpty()) {
                        val byRule = safetyEvents.groupBy { it.functionName }
                        byRule.entries.take(4).forEach { (funcName, events) ->
                            val sev = if (events.any { it.severity == "high" }) Red else Orange
                            SafetyEventRow(
                                label = funcName.replace("_", " ").replaceFirstChar { it.uppercase() },
                                count = events.size,
                                color = sev,
                            )
                        }
                    } else {
                        Text(
                            text = "No safety events — great form!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Green,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Critical: $criticalCount | Warning: $warningCount | Clean: $cleanPercent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }

                SummaryPanel(title = "Movement", modifier = Modifier.weight(1f)) {
                    detection.detectedExercises.entries
                        .sortedByDescending { it.value }
                        .take(3)
                        .forEach { (ex, count) ->
                            val pct = if (session.totalFrames > 0) {
                                count * 100 / session.totalFrames
                            } else 0
                            HorizontalMetricBar(
                                label = ex.replace("_", " ").replaceFirstChar { it.uppercase() },
                                value = pct,
                                color = when (ex) {
                                    "squat" -> Green
                                    "push_up" -> Blue
                                    "deadlift" -> Orange
                                    "lunge" -> Orange
                                    else -> TextSecondary
                                },
                            )
                        }
                }
            }

            // Muscle focus
            if (muscles.isNotEmpty()) {
                SummaryPanel(title = "Muscle Focus") {
                    muscles.entries
                        .sortedByDescending { it.value }
                        .take(4)
                        .forEach { (muscle, count) ->
                            val pct = if (session.totalFrames > 0) {
                                count * 100 / session.totalFrames
                            } else 0
                            HorizontalMetricBar(
                                label = muscle.replace("_", " ").replaceFirstChar { it.uppercase() },
                                value = pct.coerceIn(0, 100),
                                color = Green,
                            )
                        }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Pose-estimated muscle focus, not direct muscle activation.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint,
                    )
                }
            }

            // Coach tips
            if (tips.isNotEmpty()) {
                SummaryPanel(title = "Coach Tips") {
                    tips.take(4).forEach { tip ->
                        CoachTip(tip)
                    }
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onNewSession,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Background)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("New Session", color = Background, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {},
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text("All History", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SummaryTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Color(0xAA121212))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
        }
        Text(
            text = "Workout Summary",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {}) {
            Icon(Icons.Filled.Share, contentDescription = "Share", tint = TextPrimary)
        }
    }
}

@Composable
private fun SummaryPanel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SurfaceColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            content()
        }
    }
}

@Composable
private fun SafetyEventRow(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(20.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Text("$count", style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
    }
}

@Composable
private fun CoachTip(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .width(6.dp)
                .height(6.dp)
                .background(Blue, RoundedCornerShape(3.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}
