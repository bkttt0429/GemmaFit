package com.gemmafit.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.*

@Composable
fun HudRow(
    repCount: Int,
    formScore: Int,
    violationActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // REP counter
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("REP", color = TextHint, fontSize = 11.sp)
            Text(
                "$repCount",
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }

        // Form Score
        val scoreColor by animateColorAsState(
            when {
                formScore >= 80 -> ScoreHigh
                formScore >= 50 -> ScoreMid
                else -> ScoreLow
            },
            label = "score_color",
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FORM SCORE", color = TextHint, fontSize = 11.sp)
            Text(
                "$formScore",
                style = MaterialTheme.typography.headlineMedium,
                color = scoreColor,
                fontWeight = FontWeight.Bold,
            )

            // Score bar
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Surface),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(formScore / 100f)
                        .background(scoreColor)
                        .clip(RoundedCornerShape(3.dp)),
                )
            }

            Text(
                when {
                    formScore >= 80 -> "Good Form!"
                    formScore >= 50 -> "Fair"
                    else -> "Needs Work"
                },
                color = TextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}
