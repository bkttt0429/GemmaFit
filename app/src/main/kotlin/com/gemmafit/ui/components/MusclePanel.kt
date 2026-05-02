package com.gemmafit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.PurpleHighlight
import com.gemmafit.ui.theme.Surface
import com.gemmafit.ui.theme.TextHint
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

@Composable
fun MusclePanel(
    primary: List<String>,
    secondary: List<String>,
    patternDescription: String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .background(Surface, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = PurpleHighlight,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))

            Text(
                text = if (primary.isNotEmpty()) {
                    "Primary: ${primary.joinToString(", ")}"
                } else {
                    "No movement detected"
                },
                color = if (primary.isNotEmpty()) TextPrimary else TextHint,
                fontWeight = if (primary.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                if (patternDescription.isNotBlank()) {
                    Text(
                        text = patternDescription,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (secondary.isNotEmpty()) {
                    Text(
                        text = "Secondary: ${secondary.joinToString(", ")}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = TextHint,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Pose-based estimate, not EMG measurement",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextHint,
                    )
                }
            }
        }
    }
}
