package com.gemmafit.video

import kotlin.math.sqrt

internal class LandmarkStabilizer(
    private val alpha: Float = 0.35f,
    private val deadband: Float = 0.0035f,
    private val minVisibility: Float = 0.2f,
    private val resetDistance: Float = 0.16f,
) {
    private var previous: List<PoseLandmarkData>? = null

    fun reset() {
        previous = null
    }

    fun apply(landmarks: List<PoseLandmarkData>): List<PoseLandmarkData> {
        if (landmarks.size < 33) {
            reset()
            return landmarks
        }

        val last = previous
        if (last == null || last.size != landmarks.size || meanVisibleDistance(last, landmarks) > resetDistance) {
            val sanitized = landmarks.map { it.sanitized() }
            previous = sanitized
            return sanitized
        }

        val smoothed = landmarks.mapIndexed { index, current ->
            smoothLandmark(last[index], current)
        }
        previous = smoothed
        return smoothed
    }

    private fun smoothLandmark(previous: PoseLandmarkData, current: PoseLandmarkData): PoseLandmarkData {
        val cur = current.sanitized(fallback = previous)
        val prev = previous.sanitized(fallback = cur)
        val curVisibility = cur.visibility.coerceIn(0f, 1f)
        val prevVisibility = prev.visibility.coerceIn(0f, 1f)
        if (curVisibility < minVisibility || prevVisibility < minVisibility) {
            return cur.copy(visibility = curVisibility)
        }

        val dx = cur.x - prev.x
        val dy = cur.y - prev.y
        val dz = cur.z - prev.z
        val dist = sqrt(dx * dx + dy * dy)
        val blend = if (dist < deadband) 0f else alpha.coerceIn(0f, 1f)
        return PoseLandmarkData(
            x = prev.x + dx * blend,
            y = prev.y + dy * blend,
            z = prev.z + dz * blend,
            visibility = (prevVisibility + (curVisibility - prevVisibility) * 0.5f).coerceIn(0f, 1f),
        )
    }

    private fun meanVisibleDistance(
        previous: List<PoseLandmarkData>,
        current: List<PoseLandmarkData>,
    ): Float {
        var sum = 0f
        var count = 0
        val limit = minOf(previous.size, current.size)
        for (i in 0 until limit) {
            val prev = previous[i]
            val cur = current[i]
            if (prev.visibility >= minVisibility && cur.visibility >= minVisibility) {
                val dx = cur.x - prev.x
                val dy = cur.y - prev.y
                sum += sqrt(dx * dx + dy * dy)
                count += 1
            }
        }
        return if (count == 0) Float.POSITIVE_INFINITY else sum / count
    }

    private fun PoseLandmarkData.sanitized(fallback: PoseLandmarkData = this): PoseLandmarkData {
        return copy(
            x = finiteOrFallback(x, fallback.x),
            y = finiteOrFallback(y, fallback.y),
            z = finiteOrFallback(z, fallback.z),
            visibility = if (visibility.isFinite()) visibility.coerceIn(0f, 1f) else 0f,
        )
    }

    private fun finiteOrFallback(value: Float, fallback: Float): Float {
        return when {
            value.isFinite() -> value
            fallback.isFinite() -> fallback
            else -> 0f
        }
    }
}
