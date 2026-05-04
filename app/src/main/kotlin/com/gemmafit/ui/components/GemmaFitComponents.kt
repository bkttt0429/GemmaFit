package com.gemmafit.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemmafit.ui.theme.Blue
import com.gemmafit.ui.theme.Green
import com.gemmafit.ui.theme.OverlayPanel as OverlayPanelColor
import com.gemmafit.ui.theme.Red
import com.gemmafit.ui.theme.ScoreHigh
import com.gemmafit.ui.theme.ScoreLow
import com.gemmafit.ui.theme.ScoreMid
import com.gemmafit.ui.theme.SurfaceColor
import com.gemmafit.ui.theme.TextHint
import com.gemmafit.ui.theme.TextPrimary
import com.gemmafit.ui.theme.TextSecondary

@Composable
fun OverlayPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = OverlayPanelColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
fun MetricBlock(
    label: String,
    value: String,
    helper: String,
    accent: Color = TextPrimary,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            color = accent,
        )
        Text(
            text = helper,
            style = MaterialTheme.typography.labelSmall,
            color = TextHint,
        )
    }
}

@Composable
fun ScoreBar(score: Int, modifier: Modifier = Modifier) {
    val color = when {
        score >= 80 -> ScoreHigh
        score >= 50 -> ScoreMid
        else -> ScoreLow
    }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Form score",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                text = "$score",
                style = MaterialTheme.typography.headlineMedium,
                color = color,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(SurfaceColor, RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(score.coerceIn(0, 100) / 100f)
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
        }
    }
}

@Composable
fun MuscleFocusPanel(
    primary: List<String>,
    secondary: List<String>,
    modifier: Modifier = Modifier,
) {
    OverlayPanel(modifier = modifier) {
        Column {
            Text(
                text = "Primary load area",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                text = primary.joinToString(", "),
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Secondary: ${secondary.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Text(
                text = "Pose-based estimate, not EMG.",
                style = MaterialTheme.typography.labelSmall,
                color = TextHint,
            )
        }
    }
}

@Composable
fun SkeletonPreviewCanvas(
    modifier: Modifier = Modifier,
    kneeWarning: Boolean = true,
    comInside: Boolean = true,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val joints = mapOf(
            "head" to Offset(w * 0.50f, h * 0.16f),
            "lShoulder" to Offset(w * 0.38f, h * 0.28f),
            "rShoulder" to Offset(w * 0.62f, h * 0.28f),
            "lElbow" to Offset(w * 0.30f, h * 0.44f),
            "rElbow" to Offset(w * 0.70f, h * 0.44f),
            "lWrist" to Offset(w * 0.32f, h * 0.58f),
            "rWrist" to Offset(w * 0.68f, h * 0.58f),
            "lHip" to Offset(w * 0.42f, h * 0.56f),
            "rHip" to Offset(w * 0.58f, h * 0.56f),
            "lKnee" to Offset(w * 0.45f, h * 0.74f),
            "rKnee" to Offset(w * 0.55f, h * 0.74f),
            "lAnkle" to Offset(w * 0.36f, h * 0.92f),
            "rAnkle" to Offset(w * 0.64f, h * 0.92f),
        )
        fun line(a: String, b: String, color: Color = Green, stroke: Float = 5f) {
            drawLine(
                color = color,
                start = joints.getValue(a),
                end = joints.getValue(b),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        val kneeColor = if (kneeWarning) Red else Green
        line("head", "lShoulder")
        line("head", "rShoulder")
        line("lShoulder", "rShoulder")
        line("lShoulder", "lElbow")
        line("lElbow", "lWrist")
        line("rShoulder", "rElbow")
        line("rElbow", "rWrist")
        line("lShoulder", "lHip")
        line("rShoulder", "rHip")
        line("lHip", "rHip")
        line("lHip", "lKnee", kneeColor, if (kneeWarning) 10f else 5f)
        line("lKnee", "lAnkle", kneeColor, if (kneeWarning) 10f else 5f)
        line("rHip", "rKnee", kneeColor, if (kneeWarning) 10f else 5f)
        line("rKnee", "rAnkle", kneeColor, if (kneeWarning) 10f else 5f)

        joints.values.forEach { point ->
            drawCircle(color = Color.White, radius = 6f, center = point)
        }

        val bosTopLeft = Offset(w * 0.30f, h * 0.90f)
        drawRoundRect(
            color = Color(0x5533D6C5),
            topLeft = bosTopLeft,
            size = Size(w * 0.40f, h * 0.07f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
        )
        drawRoundRect(
            color = Blue,
            topLeft = bosTopLeft,
            size = Size(w * 0.40f, h * 0.07f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
            style = Stroke(width = 3f),
        )
        drawCircle(
            color = if (comInside) Blue else Red,
            radius = 10f,
            center = Offset(w * 0.50f, h * 0.79f),
        )
    }
}

@Composable
fun MiniSkeletonWindow(modifier: Modifier = Modifier) {
    OverlayPanel(modifier = modifier.width(140.dp).height(180.dp)) {
        Column {
            Text(
                text = "3D View",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            SkeletonPreviewCanvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                kneeWarning = false,
            )
        }
    }
}

@Composable
fun StatCard(
    title: String,
    value: String,
    helper: String,
    accent: Color = Green,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = SurfaceColor,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = accent)
            Text(helper, style = MaterialTheme.typography.labelSmall, color = TextHint)
        }
    }
}

@Composable
fun HorizontalMetricBar(
    label: String,
    value: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.width(112.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(SurfaceColor, RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(value.coerceIn(0, 100) / 100f)
                    .height(8.dp)
                    .background(color, RoundedCornerShape(4.dp)),
            )
        }
        Text(
            text = "$value%",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.width(48.dp),
        )
    }
}
