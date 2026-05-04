package com.gemmafit.ui.screens.summary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gemmafit.ui.theme.Background
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.Orange
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.TextSecondary
import com.gemmafit.video.FormScorePoint
import com.gemmafit.video.SafetyEvent

/**
 * Enhanced area chart with gradient fill, safety event markers,
 * min/max annotations, and animated reveal.
 */
@Composable
fun FormScoreTrendChart(
    scores: List<FormScorePoint>,
    safetyEvents: List<SafetyEvent>,
    totalFrames: Int,
    modifier: Modifier = Modifier,
) {
    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(scores) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(1f, animationSpec = tween(800))
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(horizontal = 4.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                if (scores.size < 2) return@Canvas

                val left = 28.dp.toPx()
                val right = size.width - 12.dp.toPx()
                val top = 24.dp.toPx()
                val bottom = size.height - 28.dp.toPx()
                val chartHeight = bottom - top
                val chartWidth = right - left

                // Grid lines (horizontal)
                val gridColor = Color(0x22FFFFFF)
                for (i in 0..4) {
                    val y = top + chartHeight * i / 4f
                    drawLine(
                        color = gridColor,
                        start = Offset(left, y),
                        end = Offset(right, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // Y-axis labels
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(120, 176, 176, 176)
                    textSize = 20f
                    isAntiAlias = true
                }
                for (i in 0..4) {
                    val value = 100 - i * 25
                    val y = top + chartHeight * i / 4f + 6f
                    drawContext.canvas.nativeCanvas.drawText(
                        "$value",
                        0f,
                        y,
                        labelPaint,
                    )
                }

                // Map frame index to x position
                fun frameToX(frameIndex: Int): Float {
                    val total = totalFrames.coerceAtLeast(scores.last().frameIndex)
                    return left + chartWidth * (frameIndex.toFloat() / total.coerceAtLeast(1))
                }

                fun scoreToY(score: Int): Float {
                    return bottom - chartHeight * (score.coerceIn(0, 100) / 100f)
                }

                // Build smooth path through points
                val path = Path()
                val visibleCount = (scores.size * animatedProgress.value).toInt().coerceAtLeast(2)
                val visibleScores = scores.take(visibleCount)

                if (visibleScores.size >= 2) {
                    val firstPoint = Offset(
                        frameToX(visibleScores.first().frameIndex),
                        scoreToY(visibleScores.first().score),
                    )
                    path.moveTo(firstPoint.x, firstPoint.y)

                    // Draw smooth curve through points
                    for (i in 1 until visibleScores.size) {
                        val prev = visibleScores[i - 1]
                        val curr = visibleScores[i]
                        val prevX = frameToX(prev.frameIndex)
                        val prevY = scoreToY(prev.score)
                        val currX = frameToX(curr.frameIndex)
                        val currY = scoreToY(curr.score)

                        // Simple cubic bezier for smoothness
                        val cp1x = prevX + (currX - prevX) * 0.5f
                        val cp1y = prevY
                        val cp2x = prevX + (currX - prevX) * 0.5f
                        val cp2y = currY
                        path.cubicTo(cp1x, cp1y, cp2x, cp2y, currX, currY)
                    }

                    // Gradient fill path (close to bottom)
                    val fillPath = Path().apply {
                        addPath(path)
                        val lastX = frameToX(visibleScores.last().frameIndex)
                        lineTo(lastX, bottom)
                        lineTo(firstPoint.x, bottom)
                        close()
                    }

                    // Gradient fill
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Green.copy(alpha = 0.25f),
                                Green.copy(alpha = 0.05f),
                                Color.Transparent,
                            ),
                            startY = top,
                            endY = bottom,
                        ),
                        style = Fill,
                    )

                    // Main line
                    drawPath(
                        path = path,
                        color = Green,
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                        ),
                    )

                    // Data points
                    visibleScores.forEachIndexed { index, point ->
                        val px = frameToX(point.frameIndex)
                        val py = scoreToY(point.score)
                        val isKeyPoint = index == 0 || index == visibleScores.lastIndex ||
                                point.score == visibleScores.minOf { it.score } ||
                                point.score == visibleScores.maxOf { it.score }

                        if (isKeyPoint) {
                            // Outer glow
                            drawCircle(
                                color = Green.copy(alpha = 0.2f),
                                radius = 10.dp.toPx(),
                                center = Offset(px, py),
                            )
                            // Core dot
                            drawCircle(
                                color = Background,
                                radius = 5.dp.toPx(),
                                center = Offset(px, py),
                            )
                            drawCircle(
                                color = Green,
                                radius = 5.dp.toPx(),
                                center = Offset(px, py),
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    }

                    // Min / Max annotations
                    val minScore = visibleScores.minByOrNull { it.score }
                    val maxScore = visibleScores.maxByOrNull { it.score }
                    minScore?.let {
                        drawAnnotation(
                            text = "${it.score}",
                            position = Offset(frameToX(it.frameIndex), scoreToY(it.score)),
                            color = Orange,
                            isAbove = false,
                        )
                    }
                    maxScore?.let {
                        if (it != minScore) {
                            drawAnnotation(
                                text = "${it.score}",
                                position = Offset(frameToX(it.frameIndex), scoreToY(it.score)),
                                color = Green,
                                isAbove = true,
                            )
                        }
                    }
                }

                // Safety event markers
                val relevantEvents = safetyEvents.filter { event ->
                    event.frameIndex in scores.first().frameIndex..scores.last().frameIndex
                }
                relevantEvents.forEach { event ->
                    val mx = frameToX(event.frameIndex)
                    val markerColor = when (event.severity) {
                        "high" -> Red
                        "medium" -> Orange
                        else -> Orange.copy(alpha = 0.6f)
                    }
                    // Vertical dashed indicator line
                    drawLine(
                        color = markerColor.copy(alpha = 0.4f),
                        start = Offset(mx, top),
                        end = Offset(mx, bottom),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(4f, 4f),
                        ),
                    )
                    // Marker dot at top
                    drawCircle(
                        color = markerColor,
                        radius = 5.dp.toPx(),
                        center = Offset(mx, top + 8.dp.toPx()),
                    )
                    // Glow
                    drawCircle(
                        color = markerColor.copy(alpha = 0.2f),
                        radius = 12.dp.toPx(),
                        center = Offset(mx, top + 8.dp.toPx()),
                    )
                }
            }
        }

        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(color = Green, text = "Form Score")
            Spacer(Modifier.weight(1f))
            LegendItem(color = Orange, text = "Warning")
            Spacer(Modifier.weight(1f))
            LegendItem(color = Red, text = "Critical")
        }
    }
}

private fun DrawScope.drawAnnotation(
    text: String,
    position: Offset,
    color: Color,
    isAbove: Boolean,
) {
    val offsetY = if (isAbove) -16.dp.toPx() else 16.dp.toPx()
    val paint = android.graphics.Paint().apply {
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt(),
        )
        textSize = 22f
        isFakeBoldText = true
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(
        text,
        position.x - 10f,
        position.y + offsetY,
        paint,
    )
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .height(6.dp)
                .width(6.dp)
                .background(color, CircleShape),
        )
        Text(
            text = text,
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
