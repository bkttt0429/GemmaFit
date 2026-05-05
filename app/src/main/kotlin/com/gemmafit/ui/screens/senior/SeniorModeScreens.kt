package com.gemmafit.ui.screens.senior

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.export.CaregiverExportBuilder
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

@Immutable
data class SeniorExerciseOption(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

@Immutable
data class SeniorLiveCoachUiState(
    val exerciseTitle: String = "Sit-to-Stand",
    val repCount: Int = 0,
    val currentCue: String = "Stand tall, then sit with control.",
    val trustStatus: String = "OK",
    val sourceLabel: String = "deterministic_fallback",
    val isSpeaking: Boolean = false,
)

@Immutable
data class SeniorEvidenceUiState(
    val verdict: String = "OK",
    val observed: List<String> = emptyList(),
    val reason: String = "Movement evidence is stable enough for a coaching cue.",
    val skippedJudgments: List<String> = CaregiverExportBuilder.MANDATORY_UNSUPPORTED,
    val sourceLabel: String = "deterministic_fallback",
)

@Immutable
data class SeniorTrendUiState(
    val weeklySessions: Int = 0,
    val completionCount: Int = 0,
    val commonCues: List<String> = emptyList(),
    val lowConfidenceInterruptions: Int = 0,
    val sourceLabel: String = "local_memory",
)

@Composable
fun SeniorHomeScreen(
    onStartExercise: (String) -> Unit,
    onOpenTrends: () -> Unit,
    onStopReset: () -> Unit,
    modifier: Modifier = Modifier,
    options: List<SeniorExerciseOption> = defaultSeniorExerciseOptions(),
) {
    SeniorScaffold(
        title = "Senior Strength",
        subtitle = "Large-text offline coaching",
        sourceLabel = "local_mode",
        onStopReset = onStopReset,
        modifier = modifier,
    ) {
        Text(
            text = "Choose today's movement",
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
        )
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            options.forEach { option ->
                SeniorActionButton(
                    title = option.title,
                    subtitle = option.subtitle,
                    icon = option.icon,
                    onClick = { onStartExercise(option.id) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onOpenTrends,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(Icons.Filled.History, contentDescription = null, tint = TextPrimary)
            Spacer(Modifier.width(10.dp))
            Text("Memory & Trends", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SeniorLiveCoachScreen(
    state: SeniorLiveCoachUiState,
    onStopReset: () -> Unit,
    onRepeatCue: () -> Unit,
    onOpenEvidence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeniorScaffold(
        title = state.exerciseTitle,
        subtitle = "Live Coach",
        sourceLabel = state.sourceLabel,
        onStopReset = onStopReset,
        modifier = modifier,
    ) {
        CameraPlaceholder()
        Spacer(Modifier.height(16.dp))
        SeniorTrustBadge(status = state.trustStatus)
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SurfaceColor,
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Current cue", color = TextSecondary, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.currentCue,
                    color = TextPrimary,
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRepeatCue, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Filled.VolumeUp,
                            contentDescription = "Repeat cue",
                            tint = if (state.isSpeaking) Green else TextPrimary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Rep ${state.repCount}", color = Green, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onOpenEvidence,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Why?", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SeniorEvidenceCardScreen(
    state: SeniorEvidenceUiState,
    onStopReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeniorScaffold(
        title = "Evidence Card",
        subtitle = "What was observed",
        sourceLabel = state.sourceLabel,
        onStopReset = onStopReset,
        modifier = modifier,
    ) {
        SeniorTrustBadge(status = state.verdict)
        Spacer(Modifier.height(16.dp))
        SeniorSection(title = "Observed") {
            if (state.observed.isEmpty()) {
                SeniorBodyText("No active warning. Pose tracking is usable.")
            } else {
                state.observed.forEach { SeniorBodyText("- $it") }
            }
        }
        SeniorSection(title = "Why feedback was given") {
            SeniorBodyText(state.reason)
        }
        SeniorSection(title = "Not judged") {
            state.skippedJudgments.forEach { SeniorBodyText("- ${it.replace("_", " ")}") }
        }
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Back to coach", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SeniorMemoryTrendsScreen(
    state: SeniorTrendUiState,
    onStopReset: () -> Unit,
    onClearMemory: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeniorScaffold(
        title = "Memory & Trends",
        subtitle = "Local structured records",
        sourceLabel = state.sourceLabel,
        onStopReset = onStopReset,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            SeniorMetricTile("Sessions", state.weeklySessions.toString(), Modifier.weight(1f))
            SeniorMetricTile("Completed", state.completionCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        SeniorMetricTile("Low-confidence interruptions", state.lowConfidenceInterruptions.toString(), Modifier.fillMaxWidth())
        SeniorSection(title = "Common cues") {
            if (state.commonCues.isEmpty()) {
                SeniorBodyText("No repeated cues yet.")
            } else {
                state.commonCues.take(5).forEach { SeniorBodyText("- $it") }
            }
        }
        SeniorSection(title = "Export boundary") {
            SeniorBodyText(CaregiverExportBuilder.DISCLAIMER_TEXT)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onClearMemory,
                modifier = Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onExport,
                modifier = Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Export", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SeniorScaffold(
    title: String,
    subtitle: String,
    sourceLabel: String,
    onStopReset: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextSecondary, fontSize = 18.sp)
                Text(sourceLabel, color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Button(
                onClick = onStopReset,
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(58.dp),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(22.dp))
        content()
    }
}

@Composable
private fun SeniorActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(86.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green),
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Background, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.weight(1f)) {
            Text(title, color = Background, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Background.copy(alpha = 0.78f), fontSize = 16.sp)
        }
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Background)
    }
}

@Composable
private fun CameraPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color(0xFF101614), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.AccessibilityNew, contentDescription = null, tint = Green, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(10.dp))
            Text("Camera preview + skeleton", color = TextSecondary, fontSize = 18.sp)
        }
    }
}

@Composable
private fun SeniorTrustBadge(status: String) {
    val color = when (status) {
        "CRITICAL" -> Red
        "WARNING" -> Orange
        "LOW_CONFIDENCE", "VIEW_LIMITED" -> Blue
        else -> Green
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (status == "OK") Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
            )
            Spacer(Modifier.width(10.dp))
            Text(status, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = color)
        }
    }
}

@Composable
private fun SeniorSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        color = SurfaceColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SeniorMetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = SurfaceColor, shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Green, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(label, color = TextSecondary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SeniorBodyText(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 19.sp,
        lineHeight = 26.sp,
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun defaultSeniorExerciseOptions(): List<SeniorExerciseOption> = listOf(
    SeniorExerciseOption(
        id = "chair_sit_to_stand",
        title = "Sit-to-Stand",
        subtitle = "Chair support",
        icon = Icons.Filled.AccessibilityNew,
    ),
    SeniorExerciseOption(
        id = "supported_squat",
        title = "Supported Squat",
        subtitle = "Wall or chair support",
        icon = Icons.Filled.FitnessCenter,
    ),
    SeniorExerciseOption(
        id = "balance_hold",
        title = "Balance Hold",
        subtitle = "Steady stance",
        icon = Icons.Filled.CheckCircle,
    ),
)
