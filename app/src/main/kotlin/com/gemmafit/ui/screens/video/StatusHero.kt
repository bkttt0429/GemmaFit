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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.EvidenceCard
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.QualityFlag

/**
 * Hero status bar sitting directly below the video.
 * Combines exercise info, rep count, form score, and current verdict into one glanceable row.
 */
@Composable
fun StatusHero(
    live: LiveWorkoutState,
    modifier: Modifier = Modifier,
) {
    val active = live.qualityFlags.firstOrNull { it.status != "OK" }
    val status = active?.status ?: live.evidenceCard.verdict.ifBlank { "OK" }
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
            // Top row: exercise + rep + form score
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: exercise + rep
                ExerciseInfo(
                    exercise = live.detectedExercise,
                    repCount = live.repCount,
                    phase = derivePhaseLabel(live),
                )

                // Right: form score
                FormScoreBadge(score = live.formScore)
            }

            Spacer(Modifier.height(10.dp))

            // Bottom: verdict pill
            VerdictStrip(
                status = status,
                color = color,
                subtitle = active?.let {
                    "${it.id.replace("rule_", "rule ").replace("_", " ")} — ${it.joint.ifBlank { "—" }}"
                } ?: live.evidenceCard.reason.ifBlank { "Evidence-gated feedback" },
            )
        }
    }
}

@Composable
private fun ExerciseInfo(
    exercise: String,
    repCount: Int,
    phase: String,
) {
    val (icon, label) = exerciseDisplay(exercise)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 28.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 24.sp,
            )
            Text(
                text = "Rep $repCount · $phase",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun FormScoreBadge(score: Int) {
    // Smooth animated value — even if underlying score changes every frame,
    // UI shows a smooth glide over 250ms
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
            text = "FORM",
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
        // Mini score bar (animated width)
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
    // Debounced status: a new status must persist for 300ms before UI reflects it.
    // This prevents flickering when the verdict rapidly toggles between OK and WARNING.
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

    // Pulse animation for critical states
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
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(debouncedColor.copy(alpha = borderAlpha)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = statusTitle(debouncedStatus),
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
        Text(
            text = statusIcon(debouncedStatus),
            fontSize = 18.sp,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────

private fun exerciseDisplay(exercise: String): Pair<String, String> = when (exercise) {
    "squat"    -> "🏋️" to "Squat"
    "push_up"  -> "💪" to "Push-up"
    "lunge"    -> "🦵" to "Lunge"
    "deadlift" -> "🔩" to "Deadlift"
    else       -> "❓" to "Detecting…"
}

private fun derivePhaseLabel(live: LiveWorkoutState): String {
    if (live.movementPhase != "unknown") return live.movementPhase
    val k = live.templateMetrics["left_knee_angle"] ?: live.templateMetrics["knee_angle_deg"]
    return when {
        k == null -> "—"
        k > 160   -> "top"
        k < 100   -> "bottom"
        else      -> "transition"
    }
}

private fun statusColor(status: String): Color = when (status) {
    "CRITICAL"       -> Red
    "WARNING"        -> Orange
    "MONITOR"        -> Color(0xFFFFD700)
    "VIEW_LIMITED"   -> com.gemmafit.ui.theme.Blue
    "LOW_CONFIDENCE" -> com.gemmafit.ui.theme.PurpleSecondary
    "NOT_APPLICABLE" -> Color(0xFF7A7F87)
    "OK"             -> Green
    else             -> TextSecondary
}

private fun statusIcon(status: String): String = when (status) {
    "CRITICAL"       -> "🛑"
    "WARNING"        -> "⚠"
    "MONITOR"        -> "👀"
    "VIEW_LIMITED"   -> "📷"
    "LOW_CONFIDENCE" -> "❓"
    "NOT_APPLICABLE" -> "—"
    "OK"             -> "✅"
    else             -> "•"
}

private fun statusTitle(status: String): String = when (status) {
    "CRITICAL"       -> "CORRECT NOW"
    "WARNING"        -> "WARNING"
    "MONITOR"        -> "WATCH"
    "VIEW_LIMITED"   -> "VIEW LIMITED"
    "LOW_CONFIDENCE" -> "LOW CONFIDENCE"
    "NOT_APPLICABLE" -> "NOT APPLICABLE"
    "OK"             -> "CLEAN"
    else             -> status
}
