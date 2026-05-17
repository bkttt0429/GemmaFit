package com.gemmafit.ui.screens.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.ModelReadinessSnapshot
import com.gemmafit.video.ModelReadinessStatus
import com.gemmafit.video.MotionZipUiState
import com.gemmafit.video.TrustSourceBadge
import com.gemmafit.video.TrustSourceKind

@Composable
fun SourceTrustBadge(
    badge: TrustSourceBadge,
    modifier: Modifier = Modifier,
) {
    val (color, icon) = when (badge.kind) {
        TrustSourceKind.POSE_RULES -> Blue to Icons.Filled.Info
        TrustSourceKind.LOCAL_GEMMA -> Green to Icons.Filled.CheckCircle
        TrustSourceKind.TEMPLATE_FALLBACK -> Orange to Icons.Filled.Warning
        TrustSourceKind.ABSTAINED -> Red to Icons.Filled.Warning
    }
    TrustPill(
        label = badge.label,
        color = color,
        icon = icon,
        modifier = modifier,
    )
}

@Composable
fun ModelReadinessCard(
    snapshot: ModelReadinessSnapshot,
    modifier: Modifier = Modifier,
) {
    val (color, icon) = when (snapshot.status) {
        ModelReadinessStatus.LOCAL_GEMMA_READY -> Green to Icons.Filled.CheckCircle
        ModelReadinessStatus.TEMPLATE_FALLBACK -> Orange to Icons.Filled.Warning
        ModelReadinessStatus.MODEL_MISSING -> Red to Icons.Filled.Warning
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SurfaceColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = snapshot.label, tint = color)
            Column(Modifier.weight(1f)) {
                Text(
                    text = snapshot.label,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = readinessDetail(snapshot),
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
            if (snapshot.backend.isNotBlank()) {
                TrustPill(
                    label = snapshot.backend,
                    color = color,
                    icon = Icons.Filled.Info,
                )
            }
        }
    }
}

@Composable
fun MotionZipStatusCard(
    state: MotionZipUiState,
    modifier: Modifier = Modifier,
) {
    if (!state.enabled) return
    val color = when {
        state.abstainBlocks > 0 -> Orange
        state.monitorOnlyBlocks > 0 -> Blue
        else -> Green
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SurfaceColor,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.BarChart, contentDescription = "MotionZip", tint = color)
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MotionZip evidence",
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "blocks ${state.blockCount} | judge ${state.judgeableBlocks} | monitor ${state.monitorOnlyBlocks} | abstain ${state.abstainBlocks}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
                if (state.latestAbstainReason.isNotBlank()) {
                    Text(
                        text = state.latestAbstainReason.replace("_", " "),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
            }
            if (state.latestOutputState.isNotBlank()) {
                TrustPill(
                    label = state.latestOutputState.replace("_", " "),
                    color = color,
                    icon = Icons.Filled.Info,
                )
            }
        }
    }
}

@Composable
private fun TrustPill(
    label: String,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun readinessDetail(snapshot: ModelReadinessSnapshot): String {
    return when (snapshot.status) {
        ModelReadinessStatus.LOCAL_GEMMA_READY ->
            "${snapshot.modelFileName} (${formatModelSize(snapshot.modelSizeBytes)})"
        ModelReadinessStatus.TEMPLATE_FALLBACK ->
            "${snapshot.modelFileName} available; ${snapshot.fallbackReason}"
        ModelReadinessStatus.MODEL_MISSING ->
            snapshot.fallbackReason
    }
}

private fun formatModelSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return String.format("%.2f GB", gib)
    val mib = bytes / (1024.0 * 1024.0)
    return String.format("%.1f MB", mib)
}
