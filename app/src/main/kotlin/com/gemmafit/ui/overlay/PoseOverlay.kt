package com.gemmafit.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.gemmafit.ui.theme.SkeletonJoint
import com.gemmafit.ui.theme.SkeletonNormal
import com.gemmafit.ui.theme.SkeletonViolation

data class PoseLandmark(
    val x: Float,
    val y: Float,
    val visibility: Float = 1f,
)

data class PoseOverlayState(
    val landmarks: List<PoseLandmark> = List(33) { PoseLandmark(0.5f, 0.5f, 0f) },
    val trajectoryFrames: List<List<PoseLandmark>> = emptyList(),
    val connections: List<Pair<Int, Int>> = POSE_CONNECTIONS,
    val violationSegments: Set<Pair<Int, Int>> = emptySet(),
    val violationJoints: Set<Int> = emptySet(),
    val angleArcs: List<AngleArc> = emptyList(),
    val correctionArrows: List<CorrectionArrow> = emptyList(),
    val comPosition: Offset? = null,
    val showConfidenceFade: Boolean = true,
)

data class AngleArc(
    val vertexIndex: Int,
    val armAIndex: Int,
    val armBIndex: Int,
    val label: String = "",
    val valueDeg: Float = 0f,
)

data class CorrectionArrow(
    val fromIndex: Int,
    val toIndex: Int,
)

// Standard MediaPipe pose connections (body skeleton)
val POSE_CONNECTIONS = listOf(
    // Face
    0 to 1, 1 to 2, 2 to 3, 3 to 7, 0 to 4, 4 to 5, 5 to 6, 6 to 8,
    // Mouth
    9 to 10,
    // Shoulders & torso
    11 to 12, 11 to 23, 12 to 24, 23 to 24,
    // Left arm
    11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21, 17 to 19,
    // Right arm
    12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 18 to 20,
    // Left leg
    23 to 25, 25 to 27, 27 to 29, 27 to 31, 29 to 31,
    // Right leg
    24 to 26, 26 to 28, 28 to 30, 28 to 32, 30 to 32,
)

// Subset for "hero" overlay (torso + limbs, no face detail)
val POSE_CONNECTIONS_HERO = listOf(
    11 to 12, 11 to 23, 12 to 24, 23 to 24,
    11 to 13, 13 to 15,
    12 to 14, 14 to 16,
    23 to 25, 25 to 27,
    24 to 26, 26 to 28,
)

// Key joint indices for angle display
val KEY_JOINTS = mapOf(
    "left_knee" to 25,
    "right_knee" to 26,
    "left_hip" to 23,
    "right_hip" to 24,
    "left_elbow" to 13,
    "right_elbow" to 14,
    "left_shoulder" to 11,
    "right_shoulder" to 12,
)

// Skeleton overlay with enhanced visuals
@Composable
fun PoseOverlay(
    state: PoseOverlayState,
    modifier: Modifier = Modifier,
    width: Float,
    height: Float,
    heroMode: Boolean = false,
) {
    // Pulsing animation for violation joints
    val infiniteTransition = rememberInfiniteTransition(label = "violation_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    val connections = if (heroMode) POSE_CONNECTIONS_HERO else state.connections

    Canvas(modifier = modifier) {
        fun point(index: Int): Offset {
            val lm = state.landmarks.getOrElse(index) { PoseLandmark(0.5f, 0.5f, 0f) }
            return Offset(lm.x * size.width, lm.y * size.height)
        }

        fun pointFrom(frame: List<PoseLandmark>, index: Int): Offset {
            val lm = frame.getOrElse(index) { PoseLandmark(0.5f, 0.5f, 0f) }
            return Offset(lm.x * size.width, lm.y * size.height)
        }

        // ── Dark vignette backdrop for contrast on bright camera ─────
        if (heroMode) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val vignetteRadius = maxOf(size.width, size.height) * 0.55f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x00000000), Color(0x44000000), Color(0xAA000000)),
                    center = Offset(cx, cy),
                    radius = vignetteRadius,
                ),
                radius = vignetteRadius,
            )
        }

        // ── Bone connections ─────────────────────────────────────────
        val trajectoryJoints = listOf(15, 16, 23, 24, 25, 26, 27, 28)
        if (state.trajectoryFrames.size > 1) {
            trajectoryJoints.forEach { joint ->
                state.trajectoryFrames.zipWithNext().forEachIndexed { idx, (from, to) ->
                    val fromLm = from.getOrNull(joint)
                    val toLm = to.getOrNull(joint)
                    if ((fromLm?.visibility ?: 0f) > 0.2f && (toLm?.visibility ?: 0f) > 0.2f) {
                        val alpha = ((idx + 1).toFloat() / state.trajectoryFrames.lastIndex.coerceAtLeast(1))
                            .coerceIn(0.12f, 0.80f)
                        drawLine(
                            color = Color(0xFF38BDF8).copy(alpha = alpha),
                            start = pointFrom(from, joint),
                            end = pointFrom(to, joint),
                            strokeWidth = if (joint == 15 || joint == 16) 4f else 3f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }

        for ((a, b) in connections) {
            val la = state.landmarks.getOrNull(a)
            val lb = state.landmarks.getOrNull(b)
            val vis = if (la != null && lb != null) {
                (la.visibility * lb.visibility).coerceIn(0f, 1f)
            } else 0f

            val isViolation = state.violationSegments.contains(a to b) ||
                    state.violationSegments.contains(b to a)

            val alpha = if (state.showConfidenceFade) vis * 0.9f + 0.1f else 1f

            if (isViolation) {
                // Glow layer for violated bones
                drawLine(
                    color = SkeletonViolation.copy(alpha = alpha * 0.3f * pulseAlpha),
                    start = point(a), end = point(b),
                    strokeWidth = 12f, cap = StrokeCap.Round,
                )
                drawLine(
                    color = SkeletonViolation.copy(alpha = alpha * 0.7f * pulseAlpha),
                    start = point(a), end = point(b),
                    strokeWidth = 6f, cap = StrokeCap.Round,
                )
            } else {
                // Normal bone: soft outer + solid inner
                drawLine(
                    color = SkeletonNormal.copy(alpha = alpha * 0.15f),
                    start = point(a), end = point(b),
                    strokeWidth = 7f, cap = StrokeCap.Round,
                )
                drawLine(
                    color = SkeletonNormal.copy(alpha = alpha * 0.85f),
                    start = point(a), end = point(b),
                    strokeWidth = 3f, cap = StrokeCap.Round,
                )
            }
        }

        // ── Joints ───────────────────────────────────────────────────
        for (i in 0 until 33) {
            val lm = state.landmarks.getOrNull(i) ?: continue
            val vis = if (state.showConfidenceFade) lm.visibility else 1f
            val isViolation = state.violationJoints.contains(i)
            val p = point(i)
            val jointAlpha = vis * 0.8f + 0.2f

            if (isViolation) {
                // Pulsing outer glow
                drawCircle(
                    color = SkeletonViolation.copy(alpha = jointAlpha * 0.25f * pulseAlpha),
                    radius = 22f, center = p,
                )
                // Ring
                drawCircle(
                    color = SkeletonViolation.copy(alpha = jointAlpha * 0.8f * pulseAlpha),
                    radius = 14f, center = p,
                    style = Stroke(width = 3f),
                )
                // Core
                drawCircle(
                    color = SkeletonViolation.copy(alpha = jointAlpha),
                    radius = 8f, center = p,
                )
            } else {
                // Outer glow
                drawCircle(
                    color = SkeletonNormal.copy(alpha = jointAlpha * 0.15f),
                    radius = 12f, center = p,
                )
                // Core dot
                drawCircle(
                    color = SkeletonJoint.copy(alpha = jointAlpha),
                    radius = 5f, center = p,
                )
            }
        }

        // ── COM marker ───────────────────────────────────────────────
        state.comPosition?.let { com ->
            val comCenter = Offset(com.x * size.width, com.y * size.height)
            drawCircle(
                color = Color(0xCCFFD700),
                radius = 14f, center = comCenter,
            )
            drawCircle(
                color = Color(0x44FFD700),
                radius = 22f, center = comCenter,
            )
            val comColor = Color(0x88FFD700)
            drawLine(comColor, Offset(comCenter.x - 20f, comCenter.y), Offset(comCenter.x + 20f, comCenter.y), strokeWidth = 1.5f)
            drawLine(comColor, Offset(comCenter.x, comCenter.y - 20f), Offset(comCenter.x, comCenter.y + 20f), strokeWidth = 1.5f)
        }

        // ── Angle arcs ───────────────────────────────────────────────
        for (arc in state.angleArcs) {
            drawAngleArc(point(arc.vertexIndex), point(arc.armAIndex), point(arc.armBIndex), arc.label, arc.valueDeg)
        }

        // ── Correction arrows ────────────────────────────────────────
        for (arrow in state.correctionArrows) {
            drawCorrectionArrow(point(arrow.fromIndex), point(arrow.toIndex))
        }
    }
}

private fun DrawScope.drawAngleArc(
    vertex: Offset,
    armA: Offset,
    armB: Offset,
    label: String,
    valueDeg: Float = 0f,
) {
    val arcColor = Color(0xCC00E676) // green arc
    val radius = 35f

    // Draw arm lines (short segments from vertex)
    listOf(armA to armA, armB to armB).forEach { (_, arm) ->
        val dx = arm.x - vertex.x; val dy = arm.y - vertex.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        drawLine(
            color = arcColor.copy(alpha = 0.5f),
            start = vertex,
            end = Offset(vertex.x + dx / len * radius, vertex.y + dy / len * radius),
            strokeWidth = 2f, cap = StrokeCap.Round,
        )
    }
    // Arc
    drawCircle(
        color = arcColor.copy(alpha = 0.4f),
        radius = radius, center = vertex,
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 3f))),
    )
    // Label
    val displayText = if (valueDeg > 0f) "${valueDeg.toInt()}°" else label
    if (displayText.isNotEmpty()) {
        drawContext.canvas.nativeCanvas.drawText(
            displayText, vertex.x + radius + 6f, vertex.y - 6f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.argb(230, 0, 228, 118)
                textSize = 24f; isFakeBoldText = true; isAntiAlias = true
                setShadowLayer(4f, 0f, 0f, android.graphics.Color.argb(180, 0, 0, 0))
            },
        )
    }
}

private fun DrawScope.drawCorrectionArrow(from: Offset, to: Offset) {
    val dx = to.x - from.x; val dy = to.y - from.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    if (len < 1f) return
    val ux = dx / len; val uy = dy / len
    val headSize = 16f
    val arrowColor = Color(0xD9448AFF)

    drawLine(arrowColor, from, Offset(to.x - ux * headSize, to.y - uy * headSize), strokeWidth = 3f, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - ux * headSize + uy * headSize * 0.5f, to.y - uy * headSize - ux * headSize * 0.5f)
        lineTo(to.x - ux * headSize - uy * headSize * 0.5f, to.y - uy * headSize + ux * headSize * 0.5f)
        close()
    }
    drawPath(path, arrowColor)
}
