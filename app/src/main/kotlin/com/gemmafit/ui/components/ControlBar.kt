package com.gemmafit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.*

@Composable
fun ControlBar(
    isPaused: Boolean,
    isRecording: Boolean,
    onPauseToggle: () -> Unit,
    onStartStop: () -> Unit,
    onViewSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Pause / Resume
        Button(
            onClick = onPauseToggle,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isPaused) Green else Surface,
            ),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(
                if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                null,
                tint = if (isPaused) Background else TextPrimary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (isPaused) "Resume" else "Pause",
                color = if (isPaused) Background else TextPrimary,
                fontSize = 14.sp,
            )
        }

        // Flip camera
        IconButton(
            onClick = { /* flip camera */ },
            modifier = Modifier
                .size(48.dp)
                .background(Surface, RoundedCornerShape(10.dp)),
        ) {
            Icon(Icons.Filled.FlipCameraAndroid, "Flip", tint = TextSecondary)
        }

        // View Summary
        IconButton(
            onClick = onViewSummary,
            modifier = Modifier
                .size(48.dp)
                .background(Surface, RoundedCornerShape(10.dp)),
        ) {
            Icon(Icons.Rounded.Analytics, "Summary", tint = Green)
        }
    }
}
