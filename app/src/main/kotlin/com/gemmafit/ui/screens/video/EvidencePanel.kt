package com.gemmafit.ui.screens.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.PurpleSecondary
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.EvidenceCard
import com.gemmafit.video.QualityFlag

/**
 * Collapsible Evidence and Cannot-Judge panels.
 * Collapsed by default to reduce cognitive load.
 */
@Composable
fun EvidencePanel(
    card: EvidenceCard,
    qualityFlags: List<QualityFlag>,
    modifier: Modifier = Modifier,
) {
    var evidenceExpanded by rememberSaveable { mutableStateOf(false) }
    var cannotJudgeExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Evidence section ──────────────────────────────────────────
        if (card.evidence.isNotEmpty() || card.trustFlags.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceColor,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { evidenceExpanded = !evidenceExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Evidence",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (evidenceExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (evidenceExpanded) "Collapse" else "Expand",
                            tint = TextSecondary,
                        )
                    }

                    // Always-visible summary
                    val summary = card.evidence.firstOrNull()?.let { "${it.label} = ${it.value}" }
                        ?: card.reason
                    if (summary.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = summary,
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }

                    AnimatedVisibility(
                        visible = evidenceExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            card.evidence.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        item.label,
                                        color = TextSecondary,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    Text(
                                        item.value,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            if (card.trustFlags.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "Trust flags",
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    card.trustFlags.take(3).forEach { flag ->
                                        Surface(
                                            color = Color(0xFF1E2530),
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                flag.replace("_", " "),
                                                color = Blue,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Cannot Judge section ──────────────────────────────────────
        val notApplicable = qualityFlags.filter {
            it.status in setOf("NOT_APPLICABLE", "VIEW_LIMITED", "LOW_CONFIDENCE")
        }
        val unsupported = card.unsupportedJudgments.ifEmpty {
            listOf("joint_force", "clinical_injury_risk", "medical_diagnosis")
        }

        if (notApplicable.isNotEmpty() || unsupported.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = SurfaceColor,
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { cannotJudgeExpanded = !cannotJudgeExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Cannot judge from this view",
                            color = PurpleSecondary,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (cannotJudgeExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (cannotJudgeExpanded) "Collapse" else "Expand",
                            tint = TextSecondary,
                        )
                    }

                    AnimatedVisibility(
                        visible = cannotJudgeExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column {
                            Spacer(Modifier.height(10.dp))
                            notApplicable.take(3).forEach { f ->
                                CannotRow(
                                    title = f.id.replace("rule_", "rule ").replace("_", " "),
                                    reason = when (f.status) {
                                        "NOT_APPLICABLE" -> "Not applicable to this exercise / view"
                                        "VIEW_LIMITED"   -> "Camera angle limits this judgment"
                                        "LOW_CONFIDENCE" -> "Pose tracking unstable"
                                        else             -> f.reason.ifBlank { "Skipped" }
                                    },
                                )
                            }
                            unsupported.take(4).forEach { judg ->
                                CannotRow(
                                    title = displayJudgment(judg),
                                    reason = whyUnsupported(judg),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = card.modelBoundary,
                                color = TextSecondary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CannotRow(title: String, reason: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("•", color = TextSecondary, modifier = Modifier.padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                reason,
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun displayJudgment(j: String): String = when (j) {
    "joint_force"                  -> "Joint force"
    "clinical_injury_risk"         -> "Injury risk"
    "medical_diagnosis"            -> "Medical diagnosis"
    "muscle_activation_percentage" -> "Muscle activation %"
    else                           -> j.replace("_", " ")
        .replaceFirstChar(Char::titlecase)
}

private fun whyUnsupported(j: String): String = when (j) {
    "joint_force"                  -> "Pose-based, not measured force"
    "clinical_injury_risk"         -> "Out of scope — not a clinical tool"
    "medical_diagnosis"            -> "Movement quality only, not diagnosis"
    "muscle_activation_percentage" -> "Pose-based estimate, not EMG"
    else                           -> "Out of scope for single-camera pose"
}
