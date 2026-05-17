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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemmafit.ui.components.HorizontalMetricBar
import com.gemmafit.ui.components.StatCard
import com.gemmafit.ui.localization.LocalAppStrings
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
import com.gemmafit.video.AuditReceipt
import com.gemmafit.video.AuditReceiptBuilder
import com.gemmafit.video.AuditReceiptClaim
import com.gemmafit.video.ActivityContextState
import com.gemmafit.video.SessionCoachInsight
import com.gemmafit.video.SessionCoachModelStatus
import com.gemmafit.video.SessionCoachStreamPhase
import com.gemmafit.video.SafetyEvent
import com.gemmafit.video.ReviewCue
import com.gemmafit.video.SessionSummary

@Composable
fun SummaryScreen(
    session: SessionSummary,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = LocalAppStrings.current
    val detection = session.detection
    val safetyEvents = session.safetyEvents
    val formScores = session.formScores
    val muscles = session.muscleFocusDistribution
    val tips = session.coachTips
    val coachSummary = session.sessionCoachInsight
    val auditReceipt = AuditReceiptBuilder.from(session)
    val monitorOnlyUnknownActivity = detection.mainExercise == "unknown" &&
        session.totalReps == 0 &&
        session.activityContext.state == ActivityContextState.UNKNOWN
    val movementHelper = if (monitorOnlyUnknownActivity) {
        "Movement review"
    } else {
        copy.exerciseLabel(detection.mainExercise)
    }
    val visibleDetectedExercises = if (monitorOnlyUnknownActivity) {
        emptyMap()
    } else {
        detection.detectedExercises
    }

    val criticalCount = safetyEvents.count { it.severity == "high" }
    val warningCount = safetyEvents.count { it.severity == "medium" }
    val cleanPercent = if (session.totalFrames > 0) {
        ((session.totalFrames - safetyEvents.size).coerceAtLeast(0) * 100 / session.totalFrames)
    } else {
        100
    }
    val durationStr = String.format("%02d:%02d", session.durationSeconds / 60, session.durationSeconds % 60)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Background, Color(0xFF0E1210), Background),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    title = copy.totalReps,
                    value = "${session.totalReps}",
                    helper = movementHelper,
                    accent = Green,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = copy.formScore,
                    value = "${session.avgFormScore.toInt()}%",
                    helper = "$cleanPercent% ${copy.clean}",
                    accent = Green,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = copy.duration,
                    value = durationStr,
                    helper = "${session.totalFrames} ${copy.frames}",
                    accent = Blue,
                    modifier = Modifier.weight(1f),
                )
            }

            if (formScores.isNotEmpty()) {
                SummaryPanel(title = copy.formScoreTrend) {
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

            if (session.reviewCues.isNotEmpty()) {
                SummaryPanel(title = "Top review moments") {
                    session.reviewCues.take(5).forEach { cue ->
                        ReviewCueRow(cue)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "These moments are deterministic frame cues from pose and quality evidence.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryPanel(title = copy.safetyEvents) {
                    if (safetyEvents.isNotEmpty()) {
                        safetyEvents.groupBy { it.functionName }.entries.take(4).forEach { (funcName, events) ->
                            SafetyEventRow(
                                label = safetyEventLabel(funcName),
                                count = events.size,
                                color = if (events.any { it.severity == "high" }) Red else Orange,
                            )
                        }
                        val explanations = safetyEventExplanations(safetyEvents)
                        if (explanations.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Why these were flagged",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                            explanations.take(3).forEach { explanation ->
                                SafetyEventExplanationRow(explanation)
                            }
                        }
                    } else {
                        Text(
                            text = copy.noSafetyEvents,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Green,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${copy.critical}: $criticalCount | ${copy.warning}: $warningCount | ${copy.clean}: $cleanPercent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }

                SummaryPanel(title = copy.movement) {
                    if (visibleDetectedExercises.isEmpty()) {
                        Text(
                            text = "Layer 2 did not confirm a supported activity, so this stays monitor-only.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                    } else {
                        visibleDetectedExercises.entries
                            .sortedByDescending { it.value }
                            .take(3)
                            .forEach { (exercise, count) ->
                                val pct = if (session.totalFrames > 0) count * 100 / session.totalFrames else 0
                                HorizontalMetricBar(
                                    label = copy.exerciseLabel(exercise),
                                    value = pct,
                                    color = when (exercise) {
                                        "squat" -> Green
                                        "push_up" -> Blue
                                        "deadlift", "lunge" -> Orange
                                        else -> TextSecondary
                                    },
                                )
                            }
                    }
                }
            }

            if (muscles.isNotEmpty()) {
                SummaryPanel(title = copy.muscleFocus) {
                    muscles.entries
                        .sortedByDescending { it.value }
                        .take(4)
                        .forEach { (muscle, count) ->
                            val pct = if (session.totalFrames > 0) count * 100 / session.totalFrames else 0
                            HorizontalMetricBar(
                                label = muscle.replace("_", " ").replaceFirstChar { it.uppercase() },
                                value = pct.coerceIn(0, 100),
                                color = Green,
                            )
                        }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = copy.muscleFocusBoundary,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint,
                    )
                }
            }

            if (coachSummary.headline.isNotBlank() || coachSummary.modelStatus == SessionCoachModelStatus.PENDING) {
                LocalAiCoachSummaryPanel(coachSummary)
            }

            AuditReceiptPanel(auditReceipt)

            if (tips.isNotEmpty()) {
                SummaryPanel(title = copy.coachTips) {
                    tips.take(4).forEach { tip -> CoachTip(tip) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onNewSession,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Background)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(copy.newSession, color = Background, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {},
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceColor),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text(copy.allHistory, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AuditReceiptPanel(receipt: AuditReceipt) {
    SummaryPanel(title = receipt.title) {
        ReceiptSourceRow(
            source = receipt.sourceSummary,
            backend = receipt.backend,
            functionName = receipt.functionName,
            fallback = receipt.fallback,
        )
        ReceiptSectionTitle("What GemmaFit reported")
        receipt.claims.take(5).forEach { claim ->
            ReceiptClaimRow(claim)
        }
        if (receipt.basedOnEvidenceRefs.isNotEmpty()) {
            ReceiptSectionTitle("Based on evidence refs")
            Text(
                text = receipt.basedOnEvidenceRefs.take(10).joinToString(", "),
                style = MaterialTheme.typography.labelSmall,
                color = TextHint,
            )
        }
        ReceiptSectionTitle("Not judged")
        receipt.notJudged.take(8).forEach {
            ReceiptBullet(text = it)
        }
        ReceiptSectionTitle("Confidence notes")
        receipt.confidenceNotes.take(4).forEach {
            ReceiptBullet(text = it)
        }
        Text(
            text = receipt.boundaryNote,
            style = MaterialTheme.typography.labelSmall,
            color = Orange,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ReviewCueRow(cue: ReviewCue) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .background(reviewCueColor(cue.severity), CircleShape),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = cue.title.ifBlank { "Review cue" },
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${formatReviewCueTime(cue.timestampMs)}  F${cue.frameIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = cue.suggestion,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (cue.evidenceRef.isNotBlank()) {
                Text(
                    text = cue.evidenceRef,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
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

private fun formatReviewCueTime(timestampMs: Long): String {
    val totalSec = timestampMs.coerceAtLeast(0L) / 1000L
    return "${totalSec / 60}:${(totalSec % 60).toString().padStart(2, '0')}"
}

@Composable
private fun ReceiptSourceRow(
    source: String,
    backend: String,
    functionName: String,
    fallback: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = source,
            style = MaterialTheme.typography.bodyMedium,
            color = if (fallback) Orange else Green,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Backend: $backend | Function: $functionName | Fallback: ${if (fallback) "Yes" else "No"}",
            style = MaterialTheme.typography.labelSmall,
            color = TextHint,
        )
    }
}

@Composable
private fun ReceiptClaimRow(claim: AuditReceiptClaim) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ReceiptBullet("${claim.text} (${claim.source})")
        if (claim.evidenceRefs.isNotEmpty()) {
            Text(
                text = "refs: ${claim.evidenceRefs.take(4).joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = TextHint,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
    }
}

@Composable
private fun ReceiptSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = TextPrimary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ReceiptBullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "-",
            style = MaterialTheme.typography.bodyMedium,
            color = TextHint,
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LocalAiCoachSummaryPanel(insight: SessionCoachInsight) {
    val copy = LocalAppStrings.current
    SummaryPanel(title = copy.localAiCoachSummary) {
        SummaryCoachHeader(insight)
        SummaryCoachStatusRow(insight)
        SummaryCoachProgress(insight)

        SummaryReportField(
            label = copy.whatISaw,
            body = insight.whatISaw,
            accent = Green,
        )
        SummaryReportField(
            label = copy.nextFocus,
            body = insight.nextFocus,
            accent = Blue,
        )
        SummaryReportField(
            label = "Evidence boundary",
            body = evidenceBoundaryText(insight),
            accent = Orange,
        )
        SummaryReportField(
            label = copy.whyItMatters,
            body = insight.whyItMatters,
            accent = TextHint,
        )
        if (insight.notJudged.isNotBlank()) {
            SummaryReportField(
                label = copy.notJudged,
                body = insight.notJudged,
                accent = TextHint,
            )
        }
        SummaryEvidenceRefs(insight.evidenceRefs, label = copy.evidenceRefs)
        val modelMetadata = modelMetadataLine(insight)
        if (modelMetadata.isNotBlank()) {
            SummaryThinDivider()
            Text(
                text = "Model details: $modelMetadata",
                style = MaterialTheme.typography.labelSmall,
                color = TextHint,
            )
        }
    }
}

@Composable
private fun SummaryCoachHeader(insight: SessionCoachInsight) {
    val headline = insight.headline.ifBlank {
        if (insight.modelStatus == SessionCoachModelStatus.PENDING) {
            "Coach report is being prepared"
        } else {
            "Session report"
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SummaryBusyDot(
            color = modelStatusColor(insight),
            modifier = Modifier.padding(top = 7.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = modelStatusDetail(insight),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun SummaryCoachStatusRow(insight: SessionCoachInsight) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryStatusPill(
                label = "Status",
                value = modelStatusLabel(insight),
                color = modelStatusColor(insight),
                modifier = Modifier.weight(1f),
            )
            SummaryStatusPill(
                label = "Evidence",
                value = "${insight.evidenceRefs.size.coerceAtLeast(0)} refs",
                color = Blue,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryStatusPill(
                label = "Backend",
                value = backendShortLabel(insight),
                color = if (insight.fallback) Orange else Green,
                modifier = Modifier.weight(1f),
            )
            SummaryStatusPill(
                label = "Timing",
                value = timingLabel(insight),
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryCoachProgress(insight: SessionCoachInsight) {
    if (insight.modelStatus != SessionCoachModelStatus.PENDING) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryBusyDot(color = TextHint)
        Text(
            text = streamingStatusLabel(insight),
            style = MaterialTheme.typography.labelSmall,
            color = TextHint,
        )
    }
    if (insight.streamingPhase == SessionCoachStreamPhase.STREAMING && insight.streamTokenCount > 0) {
        Text(
            text = "Drafting coach summary: ${insight.streamTokenCount} tokens received.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}

@Composable
private fun SummaryStatusPill(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = TextHint,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SummaryReportField(
    label: String,
    body: String,
    accent: Color,
) {
    if (body.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(4.dp)
                .height(32.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextHint,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun SummaryEvidenceRefs(
    refs: List<String>,
    label: String,
) {
    if (refs.isEmpty()) return
    SummaryThinDivider()
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = TextHint,
        fontWeight = FontWeight.SemiBold,
    )
    refs.take(6).chunked(2).forEach { rowRefs ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rowRefs.forEach { ref ->
                SummaryRefChip(
                    text = ref,
                    modifier = Modifier.weight(1f),
                )
            }
            if (rowRefs.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryRefChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary,
        maxLines = 2,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun SummaryThinDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

private fun modelStatusLabel(insight: SessionCoachInsight): String {
    return when (insight.modelStatus) {
        SessionCoachModelStatus.PENDING -> "Preparing"
        SessionCoachModelStatus.MODEL -> "Local Gemma"
        SessionCoachModelStatus.FALLBACK -> "Template"
    }
}

private fun modelStatusColor(insight: SessionCoachInsight): Color {
    return when (insight.modelStatus) {
        SessionCoachModelStatus.PENDING -> Blue
        SessionCoachModelStatus.MODEL -> Green
        SessionCoachModelStatus.FALLBACK -> Orange
    }
}

private fun modelStatusDetail(insight: SessionCoachInsight): String {
    return when (insight.modelStatus) {
        SessionCoachModelStatus.PENDING -> "You can review the session while the local model finishes."
        SessionCoachModelStatus.MODEL -> "Generated on device from compact movement evidence."
        SessionCoachModelStatus.FALLBACK -> "Built from deterministic pose rules because the model result was unavailable or blocked."
    }
}

private fun backendShortLabel(insight: SessionCoachInsight): String {
    val backend = insight.backend.ifBlank { if (insight.fallback) "fallback" else "local" }
    return when {
        backend.startsWith("fallback", ignoreCase = true) -> "Fallback"
        backend.contains("litert", ignoreCase = true) -> "LiteRT"
        backend.contains("llama", ignoreCase = true) -> "llama.cpp"
        backend.length > 18 -> backend.take(18)
        else -> backend
    }
}

private fun timingLabel(insight: SessionCoachInsight): String {
    return when {
        insight.firstTokenTimeMs != null -> "first ${insight.firstTokenTimeMs} ms"
        insight.inferenceTimeMs > 0.0 -> "${insight.inferenceTimeMs.toInt()} ms"
        insight.initTimeMs != null -> "init ${insight.initTimeMs} ms"
        insight.modelStatus == SessionCoachModelStatus.PENDING -> "running"
        else -> "ready"
    }
}

private fun evidenceBoundaryText(insight: SessionCoachInsight): String {
    val notJudged = insight.notJudged.trim()
    val basis = insight.selectionBasis.trim()
    return when {
        notJudged.isNotBlank() ->
            "Bounded to visible pose evidence. Not judged: ${notJudged.removePrefix("Not assessed:").trim()}"
        basis.isNotBlank() ->
            "Bounded to visible pose evidence. Selection basis: $basis"
        else ->
            "Movement-quality activity feedback only. This is not a medical diagnosis or injury-risk prediction."
    }
}

private fun modelMetadataLine(insight: SessionCoachInsight): String {
    return buildList {
        if (insight.modelFileName.isNotBlank()) add("Model: ${insight.modelFileName}")
        insight.initTimeMs?.takeIf { it > 0L }?.let { add("init: ${it} ms") }
        if (insight.attemptCount > 0) add("attempts: ${insight.attemptCount}")
        if (insight.constrainedDecoding) add("constrained JSON")
        if (insight.firstTokenTimeMs != null) add("first token: ${insight.firstTokenTimeMs} ms")
        if (insight.streamingPhase != SessionCoachStreamPhase.IDLE) add("phase: ${insight.streamingPhase.name.lowercase()}")
        if (insight.firstError.isNotBlank()) add("first error: ${insight.firstError}")
        if (insight.retryError.isNotBlank()) add("retry error: ${insight.retryError}")
    }.joinToString(" | ")
}

private fun streamingStatusLabel(insight: SessionCoachInsight): String {
    return when (insight.streamingPhase) {
        SessionCoachStreamPhase.QUEUED -> "Local Gemma is queued. You can keep reviewing."
        SessionCoachStreamPhase.PREFILL -> "Local Gemma is reading compact evidence..."
        SessionCoachStreamPhase.STREAMING -> "Local Gemma is writing the coach summary..."
        SessionCoachStreamPhase.VALIDATING -> "Checking Local Gemma response..."
        SessionCoachStreamPhase.COMPLETE -> "Local Gemma summary is ready."
        SessionCoachStreamPhase.IDLE -> "Local Gemma is generating your session summary..."
    }
}

@Composable
private fun SummaryBusyDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(12.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun SummaryTopBar(onBack: () -> Unit) {
    val copy = LocalAppStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Color(0xAA121212))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = copy.back, tint = TextPrimary)
        }
        Text(
            text = copy.workoutSummary,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = {}) {
            Icon(Icons.Filled.Share, contentDescription = copy.share, tint = TextPrimary)
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
private fun SafetyEventExplanationRow(explanation: SafetyEventExplanation) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .width(6.dp)
                .height(6.dp)
                .background(explanation.color, RoundedCornerShape(3.dp)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = explanation.title,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = explanation.reason,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (explanation.detail.isNotBlank()) {
                Text(
                    text = explanation.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextHint,
                )
            }
        }
    }
}

private data class SafetyEventExplanation(
    val title: String,
    val reason: String,
    val detail: String,
    val color: Color,
)

private fun safetyEventExplanations(events: List<SafetyEvent>): List<SafetyEventExplanation> {
    return events
        .groupBy { it.functionName.ifBlank { "unknown" } }
        .entries
        .sortedWith(
            compareByDescending<Map.Entry<String, List<SafetyEvent>>> { entry ->
                entry.value.maxOfOrNull { safetySeverityRank(it.severity) } ?: 0
            }.thenByDescending { it.value.size },
        )
        .mapNotNull { (functionName, groupedEvents) ->
            val sample = groupedEvents.maxWithOrNull(
                compareBy<SafetyEvent> { safetySeverityRank(it.severity) }
                    .thenBy { it.frameIndex },
            ) ?: return@mapNotNull null
            val color = if (groupedEvents.any { it.severity == "high" }) Red else Orange
            SafetyEventExplanation(
                title = "${safetyEventLabel(functionName)} (${groupedEvents.size})",
                reason = safetyEventReason(functionName, sample),
                detail = safetyEventDetail(sample),
                color = color,
            )
        }
}

private fun safetyEventLabel(functionName: String): String {
    return when (functionName) {
        "correct_knee_alignment" -> "Knee alignment"
        "correct_spinal_alignment" -> "Spine / neck alignment"
        "correct_joint_angle" -> "Joint angle"
        "correct_asymmetry" -> "Left-right asymmetry"
        "warn_com_offset" -> "Balance / center of mass"
        "warn_rapid_movement" -> "Rapid movement"
        "increase_range_of_motion" -> "Range of motion"
        else -> functionName.replace("_", " ").replaceFirstChar { it.uppercase() }
    }
}

private fun safetyEventReason(functionName: String, event: SafetyEvent): String {
    return when (functionName) {
        "correct_knee_alignment" ->
            "The knee-tracking proxy moved outside the expected line over the foot."
        "correct_spinal_alignment" ->
            "The torso or neck alignment proxy moved outside the upright range."
        "correct_joint_angle" ->
            "A tracked joint angle reached a range the pose rule treats as too closed or extended."
        "correct_asymmetry" ->
            "Left and right side joint angles differed more than the rule allows."
        "warn_com_offset" ->
            "The body-center proxy moved away from the support area."
        "warn_rapid_movement" ->
            "A joint velocity proxy was high between sampled frames."
        "increase_range_of_motion" ->
            "The detected motion range was below the expected movement range."
        else -> event.description.ifBlank { "A pose-quality rule was triggered on this frame." }
    }
}

private fun safetyEventDetail(event: SafetyEvent): String {
    val metric = event.description
        .replace("_", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    val whenText = when {
        event.timestampSeconds > 0 -> "around ${event.timestampSeconds}s"
        event.frameIndex > 0 -> "frame ${event.frameIndex + 1}"
        else -> ""
    }
    return listOf(
        whenText,
        metric.takeIf { it.isNotBlank() }?.let { "measurement: $it" }.orEmpty(),
    ).filter { it.isNotBlank() }.joinToString(" | ")
}

private fun safetySeverityRank(severity: String): Int {
    return when (severity.lowercase()) {
        "high", "critical" -> 2
        "medium", "warning" -> 1
        else -> 0
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
