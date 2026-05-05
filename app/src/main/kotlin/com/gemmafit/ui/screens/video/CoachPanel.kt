package com.gemmafit.ui.screens.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.gemmafit.ui.theme.SurfaceColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.TextHint
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

/**
 * Coach feedback card with animated entrance/exit.
 * Only visible when there is an active message.
 */
@Composable
fun CoachPanel(
    message: String,
    priority: String,
    source: String,
    modifier: Modifier = Modifier,
) {
    val visible = message.isNotBlank()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
    ) {
        val accent = when (priority) {
            "high" -> Red
            "medium" -> Orange
            else -> Green
        }

        Surface(
            modifier = modifier.fillMaxWidth(),
            color = SurfaceColor,
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "GemmaFit Coach",
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    if (source.isNotEmpty()) {
                        Text(
                            text = source,
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    text = message,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Pose-based feedback - not medical diagnosis",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
