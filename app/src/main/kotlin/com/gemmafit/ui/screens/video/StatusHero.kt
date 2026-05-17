package com.gemmafit.ui.screens.video

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.localization.LocalAppStrings
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.PurpleSecondary
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.ActivityContextState
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.SessionVisualContext

@Composable
fun StatusHero(
    live: LiveWorkoutState,
    modifier: Modifier = Modifier,
) {
    val copy = LocalAppStrings.current
    val sessionStatus = live.sessionStatus.takeIf { it.ready }
    val displayLive = if (sessionStatus != null) {
        live.copy(
            detectedExercise = sessionStatus.exercise,
            formScore = sessionStatus.formScore,
            repCount = sessionStatus.repCount,
            movementPhase = sessionStatus.phase,
            activityContext = if (live.activityContext.state != ActivityContextState.UNKNOWN) {
                live.activityContext
            } else {
                sessionStatus.activityContext
            },
            visualContext = if (live.visualContext.available) {
                live.visualContext
            } else {
                sessionStatus.visualContext
            },
        )
    } else {
        live
    }
    var stableExercise by remember { mutableStateOf("unknown") }
    LaunchedEffect(displayLive.totalFramesAnalyzed, displayLive.detectedExercise, sessionStatus?.ready) {
        val currentExercise = displayLive.detectedExercise
        if (sessionStatus == null && displayLive.totalFramesAnalyzed <= 1 && currentExercise == "unknown") {
            stableExercise = "unknown"
        } else if (sessionStatus != null && currentExercise == "unknown") {
            stableExercise = "unknown"
        } else if (currentExercise.isNotBlank() && currentExercise != "unknown") {
            stableExercise = currentExercise
        }
    }
    val active = displayLive.qualityFlags.firstOrNull { it.status != "OK" }
    val status = active?.status ?: displayLive.evidenceCard.verdict.ifBlank { "OK" }
    val color = statusColor(status)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Background,
        shape = RoundedCornerShape(0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val display = visualAwareExerciseDisplay(displayLive, stableExercise)
                ExerciseInfo(
                    display = display,
                    repCount = displayLive.repCount,
                    phase = derivePhaseLabel(displayLive),
                    context = visualContextSubtitle(displayLive),
                )
                FormScoreBadge(score = displayLive.formScore)
            }

            if (status == "CRITICAL" || status == "WARNING") {
                Spacer(Modifier.height(10.dp))
                val subtitle = active?.let {
                    it.id.replace("rule_", "rule ").replace("_", " ") +
                        it.joint.takeIf { joint -> joint.isNotBlank() }?.let { joint -> " - $joint" }.orEmpty()
                } ?: displayLive.evidenceCard.reason.ifBlank { copy.feedbackBoundary }
                VerdictStrip(
                    status = status,
                    color = color,
                    subtitle = subtitle,
                )
            }
        }
    }
}

@Composable
private fun ExerciseInfo(
    display: ActivityDisplay,
    repCount: Int,
    phase: String,
    context: String,
) {
    val copy = LocalAppStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = display.icon,
            contentDescription = display.label,
            tint = Green,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = display.label,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 24.sp,
            )
            Text(
                text = buildString {
                    append(copy.reps)
                    append(' ')
                    append(repCount)
                    append(" - ")
                    append(phase)
                    if (context.isNotBlank()) {
                        append(" / ")
                        append(context)
                    }
                },
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun FormScoreBadge(score: Int) {
    val copy = LocalAppStrings.current
    val animatedScore by animateIntAsState(
        targetValue = score.coerceIn(0, 100),
        animationSpec = tween(250),
        label = "score_value",
    )
    val scoreColor by animateColorAsState(
        targetValue = when {
            animatedScore >= 80 -> Green
            animatedScore >= 50 -> Orange
            else -> Red
        },
        animationSpec = tween(400),
        label = "score_color",
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = copy.form,
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Text(
            text = "$animatedScore",
            color = scoreColor,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        val animatedBar by animateFloatAsState(
            targetValue = animatedScore / 100f,
            animationSpec = tween(300),
            label = "score_bar",
        )
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(SurfaceColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedBar)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scoreColor),
            )
        }
    }
}

@Composable
private fun VerdictStrip(
    status: String,
    color: Color,
    subtitle: String,
) {
    val copy = LocalAppStrings.current
    var debouncedStatus by remember { mutableStateOf(status) }
    var debouncedColor by remember { mutableStateOf(color) }
    var debouncedSubtitle by remember { mutableStateOf(subtitle) }

    LaunchedEffect(status, color, subtitle) {
        kotlinx.coroutines.delay(300)
        debouncedStatus = status
        debouncedColor = color
        debouncedSubtitle = subtitle
    }

    val isCritical = debouncedStatus == "CRITICAL" || debouncedStatus == "WARNING"
    val infiniteTransition = rememberInfiniteTransition(label = "verdict_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val borderAlpha = if (isCritical) pulseAlpha else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(debouncedColor.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(debouncedColor.copy(alpha = borderAlpha)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = copy.statusTitle(debouncedStatus),
                color = debouncedColor,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
            )
            Text(
                text = debouncedSubtitle,
                color = TextPrimary.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = statusIcon(debouncedStatus),
            contentDescription = copy.statusTitle(debouncedStatus),
            tint = debouncedColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun exerciseDisplay(exercise: String): Pair<ImageVector, String> {
    val copy = LocalAppStrings.current
    return when (exercise) {
        "squat" -> Icons.Filled.FitnessCenter to copy.exerciseLabel(exercise)
        "bodyweight_or_goblet_squat" -> Icons.Filled.FitnessCenter to copy.exerciseLabel("squat")
        "push_up" -> Icons.Filled.AccessibilityNew to copy.exerciseLabel(exercise)
        "lunge" -> Icons.Filled.DirectionsRun to copy.exerciseLabel(exercise)
        "deadlift" -> Icons.Filled.FitnessCenter to copy.exerciseLabel(exercise)
        else -> Icons.Filled.HelpOutline to copy.exerciseLabel("unknown")
    }
}

private data class ActivityDisplay(
    val icon: ImageVector,
    val label: String,
)

@Composable
private fun visualAwareExerciseDisplay(
    live: LiveWorkoutState,
    stableExercise: String,
): ActivityDisplay {
    val copy = LocalAppStrings.current
    val visual = live.visualContext
    val activity = live.activityContext
    val activityLabel = when (activity.taskLabel) {
        "chair_sit_to_stand" -> "Chair sit-to-stand"
        "supported_squat" -> copy.exerciseLabel("squat") + " with support"
        "bodyweight_or_goblet_squat" -> copy.exerciseLabel("squat")
        "balance_hold" -> "Balance hold"
        else -> null
    }
    if (activityLabel != null && activity.state != ActivityContextState.UNKNOWN) {
        return ActivityDisplay(Icons.Filled.AccessibilityNew, activityLabel)
    }

    if (
        live.sessionStatus.ready &&
        live.sessionStatus.exercise == "unknown" &&
        live.repCount == 0 &&
        activity.state == ActivityContextState.UNKNOWN
    ) {
        return ActivityDisplay(Icons.Filled.HelpOutline, "Movement review")
    }

    if (
        visual.support == SessionVisualContext.SUPPORT_CHAIR &&
        stableExercise in chairSupportConflictLabels
    ) {
        return ActivityDisplay(Icons.Filled.AccessibilityNew, "Chair-supported movement")
    }

    if (stableExercise.isNotBlank() && stableExercise != "unknown") {
        val (icon, label) = exerciseDisplay(stableExercise)
        return ActivityDisplay(icon, label)
    }

    if (visual.support == SessionVisualContext.SUPPORT_CHAIR) {
        return ActivityDisplay(Icons.Filled.AccessibilityNew, "Chair-supported movement")
    }
    val (icon, label) = exerciseDisplay(stableExercise)
    return ActivityDisplay(icon, label)
}

private val chairSupportConflictLabels = setOf(
    "lunge",
    "deadlift",
    "push_up",
)

private fun visualContextSubtitle(live: LiveWorkoutState): String {
    val visual = live.visualContext
    val parts = buildList {
        when (live.activityContext.state) {
            ActivityContextState.AMBIGUOUS -> add("activity ambiguous")
            ActivityContextState.CALIBRATING -> live.activityContext.taskLabel
                ?.replace("_", " ")
                ?.takeIf { it.isNotBlank() }
                ?.let { add("calibrating: $it") }
            ActivityContextState.LOCKED -> live.activityContext.taskLabel
                ?.replace("_", " ")
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it) }
            else -> Unit
        }
        if (visual.support == SessionVisualContext.SUPPORT_CHAIR) add("vision: chair support")
        if (visual.env != SessionVisualContext.ENV_UNKNOWN) add(visual.env)
        if (visual.limited == true) add("view limited")
    }
    return parts.distinct().take(2).joinToString(" / ")
}

private fun derivePhaseLabel(live: LiveWorkoutState): String {
    if (
        live.activityContext.state == ActivityContextState.CALIBRATING &&
        !live.activityContext.taskLabel.isNullOrBlank()
    ) {
        return "calibrating"
    }
    if (live.movementPhase != "unknown") return live.movementPhase
    val knee = live.templateMetrics["left_knee_angle"] ?: live.templateMetrics["knee_angle_deg"]
    return when {
        knee == null -> "--"
        knee > 160 -> "top"
        knee < 100 -> "bottom"
        else -> "transition"
    }
}

private fun statusColor(status: String): Color = when (status) {
    "CRITICAL" -> Red
    "WARNING" -> Orange
    "MONITOR" -> Color(0xFFFFD700)
    "VIEW_LIMITED" -> com.gemmafit.ui.theme.Blue
    "LOW_CONFIDENCE" -> PurpleSecondary
    "NOT_APPLICABLE" -> Color(0xFF7A7F87)
    "OK" -> Green
    else -> TextSecondary
}

private fun statusIcon(status: String): ImageVector = when (status) {
    "CRITICAL" -> Icons.Filled.Error
    "WARNING" -> Icons.Filled.Warning
    "MONITOR" -> Icons.Filled.Info
    "VIEW_LIMITED" -> Icons.Filled.Videocam
    "LOW_CONFIDENCE" -> Icons.Filled.Warning
    "NOT_APPLICABLE" -> Icons.Filled.Block
    "OK" -> Icons.Filled.CheckCircle
    else -> Icons.Filled.HelpOutline
}
