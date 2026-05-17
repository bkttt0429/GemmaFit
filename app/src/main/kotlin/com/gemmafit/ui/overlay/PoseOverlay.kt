package com.gemmafit.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.gemmafit.pose.PosePresenceGate
import com.gemmafit.ui.theme.SkeletonJoint
import com.gemmafit.ui.theme.SkeletonNormal
import com.gemmafit.ui.theme.SkeletonViolation

data class PoseLandmark(
    val x: Float,
    val y: Float,
    val visibility: Float = 1f,
)

data class PoseOverlayState(
    val landmarks: List<PoseLandmark> = emptyList(),
    val secondarySubjects: List<List<PoseLandmark>> = emptyList(),
    val trajectoryFrames: List<List<PoseLandmark>> = emptyList(),
    val connections: List<Pair<Int, Int>> = POSE_CONNECTIONS,
    val violationSegments: Set<Pair<Int, Int>> = emptySet(),
    val violationJoints: Set<Int> = emptySet(),
    val watchSegments: Set<Pair<Int, Int>> = emptySet(),
    val watchJoints: Set<Int> = emptySet(),
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

private const val MIN_DRAW_VISIBILITY = 0.35f

private fun List<PoseLandmark>.canRenderPose(): Boolean {
    return PosePresenceGate.canRender(this, { it.x }, { it.y }, { it.visibility })
}

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

private val AngleLabelPaint = android.graphics.Paint().apply {
    color = android.graphics.Color.argb(230, 0, 228, 118)
    textSize = 24f
    isFakeBoldText = true
    isAntiAlias = true
    setShadowLayer(4f, 0f, 0f, android.graphics.Color.argb(180, 0, 0, 0))
}

private val SkeletonWatch = Color(0xFFFFC107)

// Skeleton overlay with enhanced visuals.
//
// Performance note: violation pulse animation is isolated into a separate
// Canvas wrapped in `Modifier.graphicsLayer { alpha = pulseAlpha }`. The
// graphicsLayer block defers state reads to the layer phase, so the pulse
// State<Float> ticking at ~60 fps only re-runs the GPU layer alpha — neither
// Canvas content lambda recomposes on animation tick. This keeps the static
// skeleton path tied to pose updates (~15 fps) and avoids redrawing the entire
// skeleton 60 times/sec while a violation is active. Both layers compute scale
// / offsets independently from the same `width` / `height` inputs, so they
// share pixel-perfect coordinates; there is no cross-layer desync.
@Composable
fun PoseOverlay(
    state: PoseOverlayState,
    modifier: Modifier = Modifier,
    width: Float,
    height: Float,
    heroMode: Boolean = false,
) {
    val hasViolations = state.violationJoints.isNotEmpty() || state.violationSegments.isNotEmpty()
    val pulseAlphaState: State<Float>? = if (hasViolations) {
        val infiniteTransition = rememberInfiniteTransition(label = "violation_pulse")
        infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
    } else {
        null
    }

    val connections = if (heroMode) POSE_CONNECTIONS_HERO else state.connections

    Box(modifier = modifier) {
        // Static layer — recomposes only when pose state changes (~15 fps).
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawStaticSkeleton(
                state = state,
                connections = connections,
                width = width,
                height = height,
            )
        }

        // Pulsing violation layer — Canvas content does NOT recompose on
        // pulse tick because pulseAlphaState is read inside graphicsLayer
        // (deferred state read), not inside the Canvas draw block.
        if (hasViolations && pulseAlphaState != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = pulseAlphaState.value },
            ) {
                drawPulsingViolations(
                    state = state,
                    connections = connections,
                    width = width,
                    height = height,
                )
            }
        }
    }
}

/**
 * Pre-computed layout for skeleton overlay. Both canvases derive from the same
 * inputs (`width`, `height`, `DrawScope.size`) so the math produces identical
 * values, guaranteeing the static and pulsing layers align pixel-perfectly.
 */
private data class OverlayLayout(
    val scale: Float,
    val contentW: Float,
    val contentH: Float,
    val offsetX: Float,
    val offsetY: Float,
)

private fun DrawScope.computeOverlayLayout(width: Float, height: Float): OverlayLayout {
    val hasSourceSize = width > 0f && height > 0f && size.width > 0f && size.height > 0f
    val scale = if (hasSourceSize) minOf(size.width / width, size.height / height) else 1f
    val contentW = if (hasSourceSize) width * scale else size.width
    val contentH = if (hasSourceSize) height * scale else size.height
    val offsetX = if (hasSourceSize) (size.width - contentW) / 2f else 0f
    val offsetY = if (hasSourceSize) (size.height - contentH) / 2f else 0f
    return OverlayLayout(scale, contentW, contentH, offsetX, offsetY)
}

private fun OverlayLayout.point(landmarks: List<PoseLandmark>, index: Int): Offset? {
    val lm = landmarks.getOrNull(index) ?: return null
    if (lm.visibility < MIN_DRAW_VISIBILITY) return null
    return Offset(offsetX + lm.x * contentW, offsetY + lm.y * contentH)
}

private fun DrawScope.drawStaticSkeleton(
    state: PoseOverlayState,
    connections: List<Pair<Int, Int>>,
    width: Float,
    height: Float,
) {
    val layout = computeOverlayLayout(width, height)
    val hasActiveSubject = state.landmarks.canRenderPose()
    val renderableSecondarySubjects = state.secondarySubjects.filter { it.canRenderPose() }

    // ── Secondary subjects (faded skeletons of non-primary people) ──
    renderableSecondarySubjects.forEach { subject ->
        for ((a, b) in connections) {
            val la = subject.getOrNull(a)
            val lb = subject.getOrNull(b)
            if ((la?.visibility ?: 0f) >= MIN_DRAW_VISIBILITY &&
                (lb?.visibility ?: 0f) >= MIN_DRAW_VISIBILITY
            ) {
                val start = layout.point(subject, a) ?: continue
                val end = layout.point(subject, b) ?: continue
                drawLine(
                    color = Color(0xFF94A3B8).copy(alpha = 0.24f),
                    start = start,
                    end = end,
                    strokeWidth = 2f,
                    cap = StrokeCap.Round,
                )
            }
        }
        for (i in 0 until 33) {
            val lm = subject.getOrNull(i) ?: continue
            if (lm.visibility >= MIN_DRAW_VISIBILITY) {
                val center = layout.point(subject, i) ?: continue
                drawCircle(
                    color = Color(0xFF94A3B8).copy(alpha = 0.26f),
                    radius = 3.5f,
                    center = center,
                )
            }
        }
    }

    // ── Trajectory trails ────────────────────────────────────────
    val trajectoryJoints = listOf(15, 16, 23, 24, 25, 26, 27, 28)
    if (hasActiveSubject && state.trajectoryFrames.size > 1) {
        val renderableTrajectoryFrames = state.trajectoryFrames.map { frame ->
            frame to frame.canRenderPose()
        }
        trajectoryJoints.forEach { joint ->
            renderableTrajectoryFrames.zipWithNext().forEachIndexed { idx, (fromEntry, toEntry) ->
                val (from, fromRenderable) = fromEntry
                val (to, toRenderable) = toEntry
                if (!fromRenderable || !toRenderable) return@forEachIndexed
                val fromLm = from.getOrNull(joint)
                val toLm = to.getOrNull(joint)
                if ((fromLm?.visibility ?: 0f) >= MIN_DRAW_VISIBILITY &&
                    (toLm?.visibility ?: 0f) >= MIN_DRAW_VISIBILITY
                ) {
                    val start = layout.point(from, joint) ?: return@forEachIndexed
                    val end = layout.point(to, joint) ?: return@forEachIndexed
                    val alpha = ((idx + 1).toFloat() / state.trajectoryFrames.lastIndex.coerceAtLeast(1))
                        .coerceIn(0.12f, 0.80f)
                    drawLine(
                        color = Color(0xFF38BDF8).copy(alpha = alpha),
                        start = start,
                        end = end,
                        strokeWidth = if (joint == 15 || joint == 16) 4f else 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        val comJoints = listOf(11, 12, 23, 24, 25, 26, 27, 28)
        fun comFrom(frame: List<PoseLandmark>): Offset? {
            val visible = comJoints.mapNotNull { joint -> frame.getOrNull(joint) }
                .filter { it.visibility >= MIN_DRAW_VISIBILITY }
            if (visible.size < 4) return null
            val x = visible.map { it.x }.average().toFloat()
            val y = visible.map { it.y }.average().toFloat()
            return Offset(layout.offsetX + x * layout.contentW, layout.offsetY + y * layout.contentH)
        }
        renderableTrajectoryFrames.zipWithNext().forEachIndexed { idx, (fromEntry, toEntry) ->
            val (from, fromRenderable) = fromEntry
            val (to, toRenderable) = toEntry
            if (!fromRenderable || !toRenderable) return@forEachIndexed
            val start = comFrom(from)
            val end = comFrom(to)
            if (start != null && end != null) {
                val alpha = ((idx + 1).toFloat() / state.trajectoryFrames.lastIndex.coerceAtLeast(1))
                    .coerceIn(0.18f, 0.90f)
                drawLine(
                    color = Color(0xFFFFD166).copy(alpha = alpha),
                    start = start,
                    end = end,
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    // ── Bones: only normal segments draw here. Violation bones (both glow
    // and solid layers) are deferred to the pulsing canvas. ─────────────
    if (hasActiveSubject) {
        for ((a, b) in connections) {
            val isViolation = state.violationSegments.contains(a to b) ||
                state.violationSegments.contains(b to a)
            if (isViolation) continue
            val isWatch = state.watchSegments.contains(a to b) ||
                state.watchSegments.contains(b to a)

            val la = state.landmarks.getOrNull(a)
            val lb = state.landmarks.getOrNull(b)
            val vis = if (la != null && lb != null) {
                (la.visibility * lb.visibility).coerceIn(0f, 1f)
            } else 0f

            val alpha = if (state.showConfidenceFade) vis * 0.9f + 0.1f else 1f
            val start = layout.point(state.landmarks, a) ?: continue
            val end = layout.point(state.landmarks, b) ?: continue

            if (isWatch) {
                drawLine(
                    color = SkeletonWatch.copy(alpha = alpha * 0.26f),
                    start = start, end = end,
                    strokeWidth = 10f, cap = StrokeCap.Round,
                )
                drawLine(
                    color = SkeletonWatch.copy(alpha = alpha * 0.72f),
                    start = start, end = end,
                    strokeWidth = 4f, cap = StrokeCap.Round,
                )
            } else {
                drawLine(
                    color = SkeletonNormal.copy(alpha = alpha * 0.15f),
                    start = start, end = end,
                    strokeWidth = 7f, cap = StrokeCap.Round,
                )
                drawLine(
                    color = SkeletonNormal.copy(alpha = alpha * 0.85f),
                    start = start, end = end,
                    strokeWidth = 3f, cap = StrokeCap.Round,
                )
            }
        }
    }

    // ── Joints: normal joints draw here in full. For violation joints we
    // draw ONLY the constant-alpha core; the pulsing outer glow + ring are
    // handled by the pulsing canvas. This preserves the original behavior
    // where the violation core does not pulse. ───────────────────────────
    if (hasActiveSubject) {
        for (i in 0 until 33) {
            val lm = state.landmarks.getOrNull(i) ?: continue
            val vis = if (state.showConfidenceFade) lm.visibility else 1f
            val isViolation = state.violationJoints.contains(i)
            val isWatch = state.watchJoints.contains(i)
            val p = layout.point(state.landmarks, i) ?: continue
            val jointAlpha = vis * 0.8f + 0.2f

            if (isViolation) {
                // Constant-alpha core only — pulsing glow + ring drawn in pulsing canvas.
                drawCircle(
                    color = SkeletonViolation.copy(alpha = jointAlpha),
                    radius = 8f, center = p,
                )
            } else if (isWatch) {
                drawCircle(
                    color = SkeletonWatch.copy(alpha = jointAlpha * 0.20f),
                    radius = 18f, center = p,
                )
                drawCircle(
                    color = SkeletonWatch.copy(alpha = jointAlpha * 0.86f),
                    radius = 11f, center = p,
                    style = Stroke(width = 3f),
                )
                drawCircle(
                    color = SkeletonWatch.copy(alpha = jointAlpha),
                    radius = 5.5f, center = p,
                )
            } else {
                drawCircle(
                    color = SkeletonNormal.copy(alpha = jointAlpha * 0.15f),
                    radius = 12f, center = p,
                )
                drawCircle(
                    color = SkeletonJoint.copy(alpha = jointAlpha),
                    radius = 5f, center = p,
                )
            }
        }
    }

    // ── COM marker ───────────────────────────────────────────────
    state.comPosition?.let { com ->
        val comCenter = Offset(layout.offsetX + com.x * layout.contentW, layout.offsetY + com.y * layout.contentH)
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
    if (hasActiveSubject) {
        for (arc in state.angleArcs) {
            val vertex = layout.point(state.landmarks, arc.vertexIndex) ?: continue
            val armA = layout.point(state.landmarks, arc.armAIndex) ?: continue
            val armB = layout.point(state.landmarks, arc.armBIndex) ?: continue
            drawAngleArc(vertex, armA, armB, arc.label, arc.valueDeg)
        }
    }

    // ── Correction arrows ────────────────────────────────────────
    if (hasActiveSubject) {
        for (arrow in state.correctionArrows) {
            val from = layout.point(state.landmarks, arrow.fromIndex) ?: continue
            val to = layout.point(state.landmarks, arrow.toIndex) ?: continue
            drawCorrectionArrow(from, to)
        }
    }
}

/**
 * Pulsing violation overlay. Drawn inside a graphicsLayer whose alpha is the
 * pulse animation value, so per-draw alpha math here does NOT multiply by
 * pulseAlpha — the GPU compositor applies the layer alpha on top.
 *
 * Effective alpha == draw-alpha * layer-alpha, which reproduces the original
 *   `alpha * 0.3f * pulseAlpha` and `alpha * 0.7f * pulseAlpha`
 * exactly.
 *
 * Only violation glow + ring layers are drawn here. The constant-alpha
 * violation joint CORE stays in the static canvas to preserve the original
 * non-pulsing core behavior.
 */
private fun DrawScope.drawPulsingViolations(
    state: PoseOverlayState,
    connections: List<Pair<Int, Int>>,
    width: Float,
    height: Float,
) {
    val layout = computeOverlayLayout(width, height)
    val hasActiveSubject = state.landmarks.canRenderPose()
    if (!hasActiveSubject) return

    // Violation bones: outer glow + inner solid (both pulse — original behavior).
    for ((a, b) in connections) {
        val isViolation = state.violationSegments.contains(a to b) ||
            state.violationSegments.contains(b to a)
        if (!isViolation) continue

        val la = state.landmarks.getOrNull(a)
        val lb = state.landmarks.getOrNull(b)
        val vis = if (la != null && lb != null) {
            (la.visibility * lb.visibility).coerceIn(0f, 1f)
        } else 0f
        val alpha = if (state.showConfidenceFade) vis * 0.9f + 0.1f else 1f
        val start = layout.point(state.landmarks, a) ?: continue
        val end = layout.point(state.landmarks, b) ?: continue

        drawLine(
            color = SkeletonViolation.copy(alpha = alpha * 0.3f),
            start = start, end = end,
            strokeWidth = 12f, cap = StrokeCap.Round,
        )
        drawLine(
            color = SkeletonViolation.copy(alpha = alpha * 0.7f),
            start = start, end = end,
            strokeWidth = 6f, cap = StrokeCap.Round,
        )
    }

    // Violation joints: outer glow + ring (both pulse). Core is in static canvas.
    for (i in 0 until 33) {
        if (i !in state.violationJoints) continue
        val lm = state.landmarks.getOrNull(i) ?: continue
        val vis = if (state.showConfidenceFade) lm.visibility else 1f
        val p = layout.point(state.landmarks, i) ?: continue
        val jointAlpha = vis * 0.8f + 0.2f

        drawCircle(
            color = SkeletonViolation.copy(alpha = jointAlpha * 0.25f),
            radius = 22f, center = p,
        )
        drawCircle(
            color = SkeletonViolation.copy(alpha = jointAlpha * 0.8f),
            radius = 14f, center = p,
            style = Stroke(width = 3f),
        )
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
            AngleLabelPaint,
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
