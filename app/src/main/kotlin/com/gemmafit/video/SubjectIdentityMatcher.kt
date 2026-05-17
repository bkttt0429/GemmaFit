package com.gemmafit.video

import kotlin.math.abs
import kotlin.math.sqrt

data class SubjectAppearanceSignature(
    val histogram: FloatArray,
) {
    val isValid: Boolean
        get() {
            if (histogram.isEmpty()) return false
            var sum = 0f
            for (value in histogram) {
                if (value.isFinite() && value > 0f) sum += value
            }
            return sum > 0f
        }

    fun similarity(other: SubjectAppearanceSignature?): Float {
        if (other == null || !isValid || !other.isValid || histogram.size != other.histogram.size) {
            return 0.5f
        }
        val aTotal = positiveSum(histogram)
        val bTotal = positiveSum(other.histogram)
        if (aTotal <= 0f || bTotal <= 0f) return 0.5f
        var intersection = 0f
        for (i in histogram.indices) {
            val a = (histogram[i].coerceAtLeast(0f)) / aTotal
            val b = (other.histogram[i].coerceAtLeast(0f)) / bTotal
            intersection += minOf(a, b)
        }
        return intersection.coerceIn(0f, 1f)
    }

    private fun positiveSum(values: FloatArray): Float {
        var sum = 0f
        for (value in values) {
            if (value.isFinite() && value > 0f) sum += value
        }
        return sum
    }
}

internal data class SubjectIdentityMatchResult(
    val candidate: PoseCandidate? = null,
    val index: Int? = null,
    val score: Float = -1f,
    val hold: Boolean = false,
    val reason: String = "",
)

internal object SubjectIdentityMatcher {
    const val MOTION_WEIGHT = 0.45f
    const val KEYPOINT_WEIGHT = 0.25f
    const val APPEARANCE_WEIGHT = 0.25f
    const val AREA_VISIBILITY_WEIGHT = 0.05f
    const val PREDICTED_MOTION_WEIGHT = 0.18f
    const val APPEARANCE_ACCEPT = 0.42f

    private const val MATCH_CENTER_RADIUS = 0.45f
    private const val KEYPOINT_MATCH_RADIUS = 0.35f
    private const val AMBIGUITY_MARGIN = 0.08f
    private const val OVERLAP_IOU = 0.20f
    private const val CENTER_CLOSE = 0.08f
    private const val APPEARANCE_STRONG = 0.55f
    private const val DUPLICATE_IOU = 0.35f
    private const val DUPLICATE_CENTER_CLOSE = 0.04f
    private const val REACQUIRE_ACCEPT = 0.56f
    private const val REACQUIRE_MARGIN = 0.10f

    fun select(
        previous: PoseCandidate,
        appearanceAnchor: SubjectAppearanceSignature?,
        candidates: List<PoseCandidate>,
        threshold: Float,
        recoveringFromHold: Boolean = false,
        predictedBbox: PoseBoundingBox? = null,
    ): SubjectIdentityMatchResult {
        if (candidates.isEmpty()) return SubjectIdentityMatchResult()

        val scored = candidates.mapIndexed { index, candidate ->
            index to scoreCandidate(previous, appearanceAnchor, candidate, predictedBbox)
        }.sortedByDescending { it.second.trackScore }

        val best = scored.first()
        val second = scored.getOrNull(1)
        val margin = if (second != null) {
            best.second.trackScore - second.second.trackScore
        } else {
            1f
        }
        val bestCandidate = best.second.copy(matchMargin = margin)

        if (appearanceAnchor?.isValid == true && bestCandidate.appearanceScore < APPEARANCE_ACCEPT) {
            return SubjectIdentityMatchResult(
                candidate = null,
                index = null,
                score = bestCandidate.trackScore,
                hold = true,
                reason = "subject_appearance_mismatch",
            )
        }

        if (bestCandidate.trackScore < threshold) {
            return SubjectIdentityMatchResult()
        }

        if (isAmbiguous(appearanceAnchor, bestCandidate, second?.second, margin)) {
            return SubjectIdentityMatchResult(
                candidate = null,
                index = null,
                score = bestCandidate.trackScore,
                hold = true,
                reason = "subject_temporarily_occluded",
            )
        }

        if (recoveringFromHold && appearanceAnchor?.isValid == true) {
            val reacquireIsClear = bestCandidate.appearanceScore >= REACQUIRE_ACCEPT &&
                margin >= REACQUIRE_MARGIN
            if (!reacquireIsClear) {
                return SubjectIdentityMatchResult(
                    candidate = null,
                    index = null,
                    score = bestCandidate.trackScore,
                    hold = true,
                    reason = "subject_identity_reacquiring",
                )
            }
        }

        return SubjectIdentityMatchResult(
            candidate = bestCandidate,
            index = best.first,
            score = bestCandidate.trackScore,
        )
    }

    fun scoreCandidate(
        previous: PoseCandidate,
        appearanceAnchor: SubjectAppearanceSignature?,
        candidate: PoseCandidate,
        predictedBbox: PoseBoundingBox? = null,
    ): PoseCandidate {
        val motionScore = centerScore(previous, candidate)
        val predictedMotionScore = predictedBbox?.let { bboxCenterScore(it, candidate.bbox) }
        val keypointScore = (1f - meanKeypointDistance(previous.landmarks, candidate.landmarks) / KEYPOINT_MATCH_RADIUS)
            .coerceIn(0f, 1f)
        val appearanceScore = appearanceAnchor?.similarity(candidate.appearance)
            ?: previous.appearance?.similarity(candidate.appearance)
            ?: 0.5f
        val areaBase = maxOf(previous.bbox.area, candidate.bbox.area, 0.001f)
        val areaScore = (1f - abs(previous.bbox.area - candidate.bbox.area) / areaBase)
            .coerceIn(0f, 1f)
        val areaVisibilityScore = 0.60f * areaScore + 0.40f * candidate.avgVisibility.coerceIn(0f, 1f)
        val baseScore = (
            MOTION_WEIGHT * motionScore +
                KEYPOINT_WEIGHT * keypointScore +
                APPEARANCE_WEIGHT * appearanceScore +
                AREA_VISIBILITY_WEIGHT * areaVisibilityScore
            ).coerceIn(0f, 1f)
        val score = if (predictedMotionScore != null) {
            ((1f - PREDICTED_MOTION_WEIGHT) * baseScore + PREDICTED_MOTION_WEIGHT * predictedMotionScore)
                .coerceIn(0f, 1f)
        } else {
            baseScore
        }
        return candidate.copy(
            trackScore = score,
            identityScore = score,
            appearanceScore = appearanceScore,
        )
    }

    private fun isAmbiguous(
        appearanceAnchor: SubjectAppearanceSignature?,
        best: PoseCandidate,
        second: PoseCandidate?,
        margin: Float,
    ): Boolean {
        if (appearanceAnchor?.isValid != true || second == null) return false
        val appearanceGap = best.appearanceScore - second.appearanceScore
        val centerDistance = distance(best.centerX, best.centerY, second.centerX, second.centerY)
        val boxOverlap = bboxIou(best.bbox, second.bbox)
        val duplicateSameSubject = boxOverlap >= DUPLICATE_IOU &&
            centerDistance <= DUPLICATE_CENTER_CLOSE &&
            best.appearanceScore >= APPEARANCE_ACCEPT &&
            second.appearanceScore >= APPEARANCE_ACCEPT
        if (duplicateSameSubject) return false

        val identityIsClear = best.appearanceScore >= APPEARANCE_STRONG && appearanceGap >= 0.12f
        if (identityIsClear) return false

        val centerClose = centerDistance <= CENTER_CLOSE
        val boxesOverlap = boxOverlap >= OVERLAP_IOU
        val scoreTie = margin < AMBIGUITY_MARGIN
        return scoreTie || centerClose || boxesOverlap
    }

    private fun centerScore(previous: PoseCandidate, candidate: PoseCandidate): Float {
        val centerDist = distance(previous.centerX, previous.centerY, candidate.centerX, candidate.centerY)
        return (1f - centerDist / MATCH_CENTER_RADIUS).coerceIn(0f, 1f)
    }

    private fun bboxCenterScore(predicted: PoseBoundingBox, candidate: PoseBoundingBox): Float {
        val predictedCenterX = (predicted.minX + predicted.maxX) / 2f
        val predictedCenterY = (predicted.minY + predicted.maxY) / 2f
        val candidateCenterX = (candidate.minX + candidate.maxX) / 2f
        val candidateCenterY = (candidate.minY + candidate.maxY) / 2f
        val centerDist = distance(predictedCenterX, predictedCenterY, candidateCenterX, candidateCenterY)
        val centerScore = (1f - centerDist / MATCH_CENTER_RADIUS).coerceIn(0f, 1f)
        val iouScore = bboxIou(predicted, candidate)
        return (0.70f * centerScore + 0.30f * iouScore).coerceIn(0f, 1f)
    }

    private fun meanKeypointDistance(
        a: List<PoseLandmarkData>,
        b: List<PoseLandmarkData>,
    ): Float {
        val keypoints = intArrayOf(11, 12, 23, 24, 25, 26, 27, 28)
        var sum = 0f
        var count = 0
        for (index in keypoints) {
            val la = a.getOrNull(index)
            val lb = b.getOrNull(index)
            if (la != null && lb != null && la.visibility > 0.15f && lb.visibility > 0.15f) {
                sum += distance(la.x, la.y, lb.x, lb.y)
                count += 1
            }
        }
        return if (count == 0) 1f else sum / count
    }

    private fun bboxIou(a: PoseBoundingBox, b: PoseBoundingBox): Float {
        val left = maxOf(a.minX, b.minX)
        val top = maxOf(a.minY, b.minY)
        val right = minOf(a.maxX, b.maxX)
        val bottom = minOf(a.maxY, b.maxY)
        val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else (intersection / union).coerceIn(0f, 1f)
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
