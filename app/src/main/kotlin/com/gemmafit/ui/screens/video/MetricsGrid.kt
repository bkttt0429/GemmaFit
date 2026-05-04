package com.gemmafit.ui.screens.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.QualityFlag

/**
 * 2×2 metric grid with expandable "More" section and customizable visibility.
 * Flagged metrics pulse with an accent border.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetricsGrid(
    metrics: Map<String, Float>,
    qualityFlags: List<QualityFlag>,
    modifier: Modifier = Modifier,
) {
    if (metrics.isEmpty()) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // Default priority order for auto-selection
    val priorityOrder = remember {
        listOf("knee_angle", "hip_angle", "back_angle", "knee_dps",
               "symmetry", "com_offset", "elbow_angle", "shoulder_angle",
               "ankle_angle", "tempo")
    }

    // User-selected visible keys (persisted across recompositions)
    var visibleKeys by rememberSaveable {
        mutableStateOf(setOf<String>())
    }

    // Auto-initialize visible keys on first valid metrics
    val sortedKeys = remember(metrics) {
        metrics.keys.sortedBy { key ->
            priorityOrder.indexOfFirst { key.contains(it) }.let { if (it == -1) Int.MAX_VALUE else it }
        }
    }

    if (visibleKeys.isEmpty() && sortedKeys.isNotEmpty()) {
        visibleKeys = sortedKeys.take(4).toSet()
    }

    val flaggedKeys = qualityFlags
        .filter { it.status in setOf("WARNING", "CRITICAL", "MONITOR") }
        .map { it.id.lowercase() }
        .toSet()

    val displayKeys = sortedKeys.filter { it in visibleKeys }.take(4)
    val extraKeys = sortedKeys.filter { it !in visibleKeys }

    Column(modifier = modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Live Metrics",
                color = TextSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Row {
                if (extraKeys.isNotEmpty()) {
                    TextButtonCompact(
                        text = if (expanded) "Less" else "More",
                        onClick = { expanded = !expanded },
                    )
                }
                TextButtonCompact(
                    text = "Edit",
                    onClick = { showPicker = !showPicker },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 2×2 Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            displayKeys.take(2).forEach { key ->
                val (unit, _) = parseUnit(key)
                MetricCell(
                    label = displayKey(key),
                    rawValue = metrics.getValue(key),
                    unit = unit,
                    flagged = flaggedKeys.any { key.contains(shortKey(it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (displayKeys.size > 2) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                displayKeys.drop(2).take(2).forEach { key ->
                    val (unit, _) = parseUnit(key)
                    MetricCell(
                        label = displayKey(key),
                        rawValue = metrics.getValue(key),
                        unit = unit,
                        flagged = flaggedKeys.any { key.contains(shortKey(it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Expanded extra metrics
        AnimatedVisibility(
            visible = expanded && extraKeys.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    extraKeys.forEach { key ->
                        val (unit, _) = parseUnit(key)
                        MetricCellCompact(
                            label = displayKey(key),
                            rawValue = metrics.getValue(key),
                            unit = unit,
                            flagged = flaggedKeys.any { key.contains(shortKey(it)) },
                        )
                    }
                }
            }
        }

        // Visibility picker
        AnimatedVisibility(
            visible = showPicker,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = SurfaceColor,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Select metrics to display",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            sortedKeys.forEach { key ->
                                val isSelected = key in visibleKeys
                                val chipColor = if (isSelected) Green else TextSecondary
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) Green.copy(alpha = 0.12f) else SurfaceColor)
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) Green else Color(0xFF333333),
                                            shape = RoundedCornerShape(6.dp),
                                        )
                                        .clickable {
                                            visibleKeys = if (isSelected) {
                                                visibleKeys - key
                                            } else {
                                                visibleKeys + key
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            null,
                                            tint = Green,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(
                                        displayKey(key),
                                        color = if (isSelected) Green else TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
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

@Composable
private fun MetricCell(
    label: String,
    rawValue: Float,
    unit: String,
    flagged: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "metric_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    // Smooth animated number: the displayed value glides over 200ms
    // even if the underlying raw value changes every frame.
    val animatedValue by animateFloatAsState(
        targetValue = rawValue,
        animationSpec = tween(200),
        label = "metric_value",
    )
    val displayText = "${"%.0f".format(animatedValue)}$unit"

    val borderColor = if (flagged) Orange.copy(alpha = pulseAlpha) else Color.Transparent
    val valueColor = if (flagged) Orange else TextPrimary

    Surface(
        modifier = modifier,
        color = SurfaceColor,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = displayText,
                color = valueColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun MetricCellCompact(
    label: String,
    rawValue: Float,
    unit: String,
    flagged: Boolean,
) {
    val animatedValue by animateFloatAsState(
        targetValue = rawValue,
        animationSpec = tween(200),
        label = "metric_compact",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${"%.0f".format(animatedValue)}$unit",
            color = if (flagged) Orange else TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
}

@Composable
private fun TextButtonCompact(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Green,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ── Helpers ─────────────────────────────────────────────────────────

private fun shortKey(k: String): String =
    k.lowercase().substringBefore("_angle").substringBefore("_deg").substringBefore("_pct")

private fun displayKey(k: String): String {
    val cleaned = k
        .replace("_angle_deg", "")
        .replace("_angle", "")
        .replace("_deg", "")
        .replace("_pct", "")
        .replace("_", " ")
    return cleaned.split(" ").joinToString(" ") {
        it.replaceFirstChar(Char::titlecase)
    }
}

private fun parseUnit(k: String): Pair<String, String> {
    val unit = when {
        "dps" in k || "tempo" in k -> "°/s"
        "pct" in k -> "%"
        else -> "°"
    }
    return unit to "${"%.0f".format(0f)}$unit"
}
