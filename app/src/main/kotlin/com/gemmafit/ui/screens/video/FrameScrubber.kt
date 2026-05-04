package com.gemmafit.ui.screens.video

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

/**
 * Frame-level scrubber with large thumb, timestamp tooltip, and frame-step controls.
 * Designed for thumb-friendly scrubbing during video review.
 */
@Composable
fun FrameScrubber(
    current: Int,
    total: Int,
    timestampMs: Long,
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeTotal = (total - 1).coerceAtLeast(1)
    val progress = current.toFloat().coerceIn(0f, safeTotal.toFloat())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // Timestamp + frame count row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTimestamp(timestampMs),
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${current + 1}",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    text = " / $total",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // Slider + frame step controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Play / Pause
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(40.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SurfaceColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.PlayArrow else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Green,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // Previous frame
            IconButton(
                onClick = onPrev,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Previous frame",
                    tint = TextPrimary,
                    modifier = Modifier.scale(-1f, 1f).size(18.dp),
                )
            }

            // Slider with custom thumb
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = progress,
                    onValueChange = { onSeek(it.toInt()) },
                    valueRange = 0f..safeTotal.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Green,
                        activeTrackColor = Green,
                        inactiveTrackColor = SurfaceColor,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Next frame
            IconButton(
                onClick = onNext,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Next frame",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
