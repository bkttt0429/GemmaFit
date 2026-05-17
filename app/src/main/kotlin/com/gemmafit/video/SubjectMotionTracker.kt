package com.gemmafit.video

internal data class PredictedSubjectBbox(
    val bbox: PoseBoundingBox,
    val confidence: Float,
    val ageMs: Long,
    val holdFrames: Int,
)

internal class SubjectMotionTracker(
    private val alpha: Float = 0.72f,
    private val beta: Float = 0.45f,
    private val maxPredictMs: Long = 900L,
) {
    private var state: BoxState? = null
    private var lastTimestampMs: Long? = null
    private var consecutiveHoldFrames = 0

    fun reset() {
        state = null
        lastTimestampMs = null
        consecutiveHoldFrames = 0
    }

    fun update(measurement: PoseBoundingBox, timestampMs: Long) {
        val normalizedMeasurement = measurement.normalized()
        val current = state
        val lastTimestamp = lastTimestampMs
        if (current == null || lastTimestamp == null) {
            state = BoxState.from(normalizedMeasurement)
            lastTimestampMs = timestampMs
            consecutiveHoldFrames = 0
            return
        }
        if (timestampMs + TIMELINE_RESET_TOLERANCE_MS < lastTimestamp) {
            state = BoxState.from(normalizedMeasurement)
            lastTimestampMs = timestampMs
            consecutiveHoldFrames = 0
            return
        }

        val dtSec = ((timestampMs - lastTimestamp).coerceAtLeast(1L).coerceAtMost(maxPredictMs)).toFloat() / 1000f
        val predicted = current.predict(dtSec)
        val observed = BoxState.from(normalizedMeasurement)
        val residualCx = observed.cx - predicted.cx
        val residualCy = observed.cy - predicted.cy
        val residualW = observed.w - predicted.w
        val residualH = observed.h - predicted.h

        state = predicted.copy(
            cx = predicted.cx + alpha * residualCx,
            cy = predicted.cy + alpha * residualCy,
            w = (predicted.w + alpha * residualW).coerceIn(MIN_BOX_SIZE, 1f),
            h = (predicted.h + alpha * residualH).coerceIn(MIN_BOX_SIZE, 1f),
            vx = predicted.vx + beta * residualCx / dtSec,
            vy = predicted.vy + beta * residualCy / dtSec,
            vw = predicted.vw + beta * residualW / dtSec,
            vh = predicted.vh + beta * residualH / dtSec,
        ).clamped()
        lastTimestampMs = timestampMs
        consecutiveHoldFrames = 0
    }

    fun predict(timestampMs: Long): PredictedSubjectBbox? {
        val current = state ?: return null
        val lastTimestamp = lastTimestampMs ?: return null
        if (timestampMs + TIMELINE_RESET_TOLERANCE_MS < lastTimestamp) return null
        val ageMs = (timestampMs - lastTimestamp).coerceAtLeast(0L)
        if (ageMs > maxPredictMs) return null
        val dtSec = ageMs.toFloat() / 1000f
        val predicted = current.predict(dtSec).clamped()
        val ageConfidence = 1f - (ageMs.toFloat() / maxPredictMs.toFloat()).coerceIn(0f, 1f)
        val holdConfidence = (1f - consecutiveHoldFrames * 0.12f).coerceIn(0.25f, 1f)
        return PredictedSubjectBbox(
            bbox = predicted.toBbox(),
            confidence = (ageConfidence * holdConfidence).coerceIn(0f, 1f),
            ageMs = ageMs,
            holdFrames = consecutiveHoldFrames,
        )
    }

    fun markHold(timestampMs: Long) {
        if (state == null) return
        predict(timestampMs)?.let { predicted ->
            state = BoxState.from(predicted.bbox)
            lastTimestampMs = timestampMs
            consecutiveHoldFrames += 1
        }
    }

    private data class BoxState(
        val cx: Float,
        val cy: Float,
        val w: Float,
        val h: Float,
        val vx: Float = 0f,
        val vy: Float = 0f,
        val vw: Float = 0f,
        val vh: Float = 0f,
    ) {
        fun predict(dtSec: Float): BoxState {
            return copy(
                cx = cx + vx * dtSec,
                cy = cy + vy * dtSec,
                w = (w + vw * dtSec).coerceIn(MIN_BOX_SIZE, 1f),
                h = (h + vh * dtSec).coerceIn(MIN_BOX_SIZE, 1f),
            )
        }

        fun clamped(): BoxState {
            val clampedWidth = w.coerceIn(MIN_BOX_SIZE, 1f)
            val clampedHeight = h.coerceIn(MIN_BOX_SIZE, 1f)
            val halfW = clampedWidth / 2f
            val halfH = clampedHeight / 2f
            return copy(
                cx = cx.coerceIn(halfW, 1f - halfW),
                cy = cy.coerceIn(halfH, 1f - halfH),
                w = clampedWidth,
                h = clampedHeight,
                vx = sanitizeVelocity(vx),
                vy = sanitizeVelocity(vy),
                vw = sanitizeVelocity(vw),
                vh = sanitizeVelocity(vh),
            )
        }

        fun toBbox(): PoseBoundingBox {
            val halfW = w / 2f
            val halfH = h / 2f
            return PoseBoundingBox(
                minX = (cx - halfW).coerceIn(0f, 1f),
                minY = (cy - halfH).coerceIn(0f, 1f),
                maxX = (cx + halfW).coerceIn(0f, 1f),
                maxY = (cy + halfH).coerceIn(0f, 1f),
            )
        }

        companion object {
            fun from(bbox: PoseBoundingBox): BoxState {
                return BoxState(
                    cx = (bbox.minX + bbox.maxX) / 2f,
                    cy = (bbox.minY + bbox.maxY) / 2f,
                    w = bbox.width.coerceIn(MIN_BOX_SIZE, 1f),
                    h = bbox.height.coerceIn(MIN_BOX_SIZE, 1f),
                )
            }
        }
    }

    private fun PoseBoundingBox.normalized(): PoseBoundingBox {
        val minX = minOf(this.minX, this.maxX).coerceIn(0f, 1f)
        val maxX = maxOf(this.minX, this.maxX).coerceIn(0f, 1f)
        val minY = minOf(this.minY, this.maxY).coerceIn(0f, 1f)
        val maxY = maxOf(this.minY, this.maxY).coerceIn(0f, 1f)
        return PoseBoundingBox(minX, minY, maxX, maxY)
    }

    private companion object {
        const val MIN_BOX_SIZE = 0.02f
        const val MAX_NORMALIZED_VELOCITY = 3f
        const val TIMELINE_RESET_TOLERANCE_MS = 50L

        fun sanitizeVelocity(value: Float): Float {
            if (!value.isFinite()) return 0f
            return value.coerceIn(-MAX_NORMALIZED_VELOCITY, MAX_NORMALIZED_VELOCITY)
        }
    }
}
