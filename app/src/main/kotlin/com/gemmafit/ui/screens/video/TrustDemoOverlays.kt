package com.gemmafit.ui.screens.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.CapabilityJudgment
import com.gemmafit.video.CoachBoundaryKind
import com.gemmafit.video.LiveWorkoutState
import com.gemmafit.video.TrustUiMapper
import java.util.Locale

private val DemoCannotJudgeOrder = listOf(
    "fall_risk_prediction",
    "joint_force",
    "muscle_activation_percentage",
    "medical_diagnosis",
)

private data class CapabilityLine(
    val metric: String,
    val label: String,
    val score: String = "",
)

fun shouldShowCapabilityOverlay(live: LiveWorkoutState): Boolean {
    return hasRuntimeEvidence(live) &&
        (capabilityCanLines(live).isNotEmpty() || capabilityCannotLines(live).isNotEmpty())
}

@Composable
fun CapabilityContractOverlay(
    live: LiveWorkoutState,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
) {
    if (!shouldShowCapabilityOverlay(live)) return
    val canLines = capabilityCanLines(live).take(3)
    val cannotLines = capabilityCannotLines(live).take(4)

    if (compact) {
        Column(
            modifier = modifier
                .widthIn(max = 158.dp)
                .background(Color(0xD9000000), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "TRUST CONTRACT",
                color = TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.sp,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapabilityCountPill(
                    label = "CAN",
                    count = canLines.size,
                    accent = Green,
                )
                CapabilityCountPill(
                    label = "CANNOT",
                    count = cannotLines.size,
                    accent = Orange,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier
            .widthIn(max = 260.dp)
            .background(Color(0xD9000000), RoundedCornerShape(14.dp))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canLines.isNotEmpty()) {
            CapabilitySection(
                title = "CAN JUDGE",
                accent = Green,
                lines = canLines,
            )
        }
        if (cannotLines.isNotEmpty()) {
            CapabilitySection(
                title = "CANNOT JUDGE",
                accent = Orange,
                lines = cannotLines,
            )
        }
    }
}

@Composable
private fun CapabilityCountPill(
    label: String,
    count: Int,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .background(accent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 0.sp,
            maxLines = 1,
        )
        Text(
            text = count.toString(),
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun RefusalMomentOverlay(
    live: LiveWorkoutState,
    modifier: Modifier = Modifier,
) {
    val boundary = TrustUiMapper.coachBoundaryState(live)
    if (boundary.kind != CoachBoundaryKind.REFUSED) return

    Surface(
        modifier = modifier.widthIn(max = 330.dp),
        color = Color(0xEF080A0D),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .border(1.dp, Red.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Cannot answer",
                color = Red,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
            RefusalLine(
                label = "Reason",
                value = boundary.summary.ifBlank { "unsupported claim" },
            )
            RefusalLine(
                label = "Source",
                value = "capability contract",
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Safe alternative",
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = boundary.detail.ifBlank {
                        "I can summarize visible movement quality and view limits."
                    },
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun CapabilitySection(
    title: String,
    accent: Color,
    lines: List<CapabilityLine>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 0.sp,
        )
        lines.forEach { line ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line.label,
                    color = TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (line.score.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = line.score,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RefusalLine(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(54.dp),
        )
        Text(
            text = value,
            color = TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun capabilityCanLines(live: LiveWorkoutState): List<CapabilityLine> {
    val fromContract = live.capabilityContract.canJudge
        .mapNotNull { it.toCapabilityLine(includeScore = true) }
    val fromCard = live.evidenceCard.capabilityCanJudge
        .mapNotNull { it.toCapabilityLine() }
    val fromMetrics = live.templateMetrics.keys
        .filter { it.isNotBlank() }
        .map { metric ->
            CapabilityLine(
                metric = metric,
                label = capabilityLabel(metric),
            )
        }
    val fallback = if (hasRuntimeEvidence(live)) {
        listOf(
            CapabilityLine("visible_pose_quality", "visible pose quality"),
            CapabilityLine("movement_tempo", "movement tempo"),
        )
    } else {
        emptyList()
    }
    return distinctCapabilities(fromContract + fromCard + fromMetrics + fallback)
}

private fun capabilityCannotLines(live: LiveWorkoutState): List<CapabilityLine> {
    val orderedDefaults = DemoCannotJudgeOrder.mapNotNull { it.toCapabilityLine() }
    val fromContract = live.capabilityContract.cannotJudge
        .mapNotNull { it.toCapabilityLine(includeScore = false) }
    val fromCard = (live.evidenceCard.capabilityCannotJudge + live.evidenceCard.unsupportedJudgments)
        .mapNotNull { it.toCapabilityLine() }
    return distinctCapabilities(orderedDefaults + fromContract + fromCard)
}

private fun CapabilityJudgment.toCapabilityLine(includeScore: Boolean): CapabilityLine? {
    if (metric.isBlank()) return null
    return CapabilityLine(
        metric = metric,
        label = capabilityLabel(metric),
        score = if (includeScore && confidenceCeiling > 0f) {
            String.format(Locale.US, "%.2f", confidenceCeiling.coerceIn(0f, 1f))
        } else {
            ""
        },
    )
}

private fun String.toCapabilityLine(): CapabilityLine? {
    if (isBlank()) return null
    return CapabilityLine(
        metric = this,
        label = capabilityLabel(this),
    )
}

private fun distinctCapabilities(lines: List<CapabilityLine>): List<CapabilityLine> {
    val seen = mutableSetOf<String>()
    return lines.filter { line ->
        seen.add(line.metric.lowercase(Locale.US))
    }
}

private fun capabilityLabel(metric: String): String {
    val normalized = metric.trim().lowercase(Locale.US)
    return when (normalized) {
        "fall_risk", "fall_risk_prediction", "clinical_injury_risk" -> "fall risk"
        "joint_force", "joint_load", "ground_reaction_force" -> "joint force"
        "muscle_activation", "muscle_activation_percentage", "muscle_activation_percent" -> {
            "muscle activation %"
        }
        "medical_diagnosis", "clinical_diagnosis", "diagnosis" -> "diagnosis"
        "knee_angle_deg", "knee_angle", "knee_flexion_angle" -> "knee angle"
        "tempo_sec", "movement_tempo", "rep_tempo" -> "tempo"
        "trunk_uprightness", "trunk_angle_deg" -> "trunk uprightness"
        "confidence_coverage", "pose_confidence" -> "pose confidence"
        else -> normalized.replace("_", " ")
    }
}

private fun hasRuntimeEvidence(live: LiveWorkoutState): Boolean {
    return live.totalFramesAnalyzed > 0 ||
        live.templateMetrics.isNotEmpty() ||
        live.evidenceCard.evidence.isNotEmpty() ||
        live.capabilityContract.canJudge.isNotEmpty() ||
        live.capabilityContract.cannotJudge.isNotEmpty()
}
