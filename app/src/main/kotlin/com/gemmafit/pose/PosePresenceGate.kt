package com.gemmafit.pose

data class PosePresenceStats(
    val avgVisibility: Float,
    val highVisibilityCount: Int,
    val torsoVisibleCount: Int,
    val upperBodyVisibleCount: Int,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    val width: Float get() = (maxX - minX).coerceAtLeast(0f)
    val height: Float get() = (maxY - minY).coerceAtLeast(0f)
    val area: Float get() = width * height
    val hasBodyAnchor: Boolean get() = torsoVisibleCount >= 2 || upperBodyVisibleCount >= 4
    val canRender: Boolean
        get() = avgVisibility >= PosePresenceGate.MIN_AVG_VISIBILITY &&
            highVisibilityCount >= PosePresenceGate.MIN_HIGH_VISIBILITY_KEYPOINTS &&
            area >= PosePresenceGate.MIN_BBOX_AREA &&
            hasBodyAnchor
}

object PosePresenceGate {
    const val MIN_AVG_VISIBILITY = 0.18f
    const val HIGH_VISIBILITY_THRESHOLD = 0.25f
    const val MIN_HIGH_VISIBILITY_KEYPOINTS = 8
    const val MIN_BBOX_AREA = 0.01f

    private val torsoIndices = setOf(11, 12, 23, 24)
    private val upperBodyIndices = setOf(11, 12, 13, 14, 15, 16, 23, 24)

    fun <T> evaluate(
        landmarks: List<T>,
        xOf: (T) -> Float,
        yOf: (T) -> Float,
        visibilityOf: (T) -> Float,
    ): PosePresenceStats {
        if (landmarks.size < 33) {
            return emptyStats()
        }

        var visibilitySum = 0f
        var highCount = 0
        var torsoCount = 0
        var upperBodyCount = 0
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f

        for (i in 0 until 33) {
            val landmark = landmarks[i]
            val visibility = visibilityOf(landmark).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            visibilitySum += visibility
            if (visibility >= HIGH_VISIBILITY_THRESHOLD) {
                val x = xOf(landmark)
                val y = yOf(landmark)
                if (x.isFinite() && y.isFinite()) {
                    highCount += 1
                    if (i in torsoIndices) torsoCount += 1
                    if (i in upperBodyIndices) upperBodyCount += 1
                    minX = minOf(minX, x.coerceIn(0f, 1f))
                    minY = minOf(minY, y.coerceIn(0f, 1f))
                    maxX = maxOf(maxX, x.coerceIn(0f, 1f))
                    maxY = maxOf(maxY, y.coerceIn(0f, 1f))
                }
            }
        }

        if (highCount == 0) {
            minX = 0f
            minY = 0f
            maxX = 0f
            maxY = 0f
        }

        return PosePresenceStats(
            avgVisibility = visibilitySum / 33f,
            highVisibilityCount = highCount,
            torsoVisibleCount = torsoCount,
            upperBodyVisibleCount = upperBodyCount,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
        )
    }

    fun <T> canRender(
        landmarks: List<T>,
        xOf: (T) -> Float,
        yOf: (T) -> Float,
        visibilityOf: (T) -> Float,
    ): Boolean {
        return evaluate(landmarks, xOf, yOf, visibilityOf).canRender
    }

    private fun emptyStats(): PosePresenceStats {
        return PosePresenceStats(
            avgVisibility = 0f,
            highVisibilityCount = 0,
            torsoVisibleCount = 0,
            upperBodyVisibleCount = 0,
            minX = 0f,
            minY = 0f,
            maxX = 0f,
            maxY = 0f,
        )
    }
}
