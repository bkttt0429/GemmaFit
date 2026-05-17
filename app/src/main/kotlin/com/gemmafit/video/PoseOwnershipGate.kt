package com.gemmafit.video

import com.gemmafit.pose.PosePresenceGate
import kotlin.math.sqrt

internal data class PoseOwnershipResult(
    val canDrawSkeleton: Boolean,
    val canUseForJudgment: Boolean,
    val reason: String,
    val trustFlags: List<String>,
)

internal object PoseOwnershipGate {
    private const val TARGET_IOU_MIN = 0.25f
    private const val TARGET_CENTER_MAX = 0.22f
    private const val MIN_TORSO_POINTS_IN_TARGET = 2
    private const val TARGET_PADDING = 0.045f
    private const val AMBIGUOUS_DUPLICATE_IOU = 0.35f
    private const val AMBIGUOUS_MATCH_MARGIN = 0.045f

    fun evaluate(
        candidate: PoseCandidate,
        targetBbox: PoseBoundingBox = candidate.bbox,
        otherCandidates: List<PoseCandidate> = emptyList(),
    ): PoseOwnershipResult {
        val stats = PosePresenceGate.evaluate(candidate.landmarks, { it.x }, { it.y }, { it.visibility })
        if (!stats.canRender) {
            return blocked("pose_not_renderable")
        }

        val overlap = bboxIou(candidate.bbox, targetBbox)
        val targetCenterDistance = centerDistance(candidate.bbox, targetBbox)
        if (overlap < TARGET_IOU_MIN && targetCenterDistance > TARGET_CENTER_MAX) {
            return blocked("pose_target_bbox_mismatch")
        }

        val expandedTarget = targetBbox.expanded(TARGET_PADDING)
        val torsoInTarget = TORSO_INDICES.count { index ->
            val landmark = candidate.landmarks.getOrNull(index) ?: return@count false
            landmark.visibility >= PosePresenceGate.HIGH_VISIBILITY_THRESHOLD &&
                expandedTarget.contains(landmark.x, landmark.y)
        }
        if (torsoInTarget < MIN_TORSO_POINTS_IN_TARGET) {
            return blocked("pose_target_torso_mismatch")
        }

        val ambiguousOverlap = otherCandidates.any { other ->
            other !== candidate &&
                PosePresenceGate.canRender(other.landmarks, { it.x }, { it.y }, { it.visibility }) &&
                bboxIou(candidate.bbox, other.bbox) >= AMBIGUOUS_DUPLICATE_IOU
        }
        if (ambiguousOverlap &&
            candidate.identityScore > 0f &&
            candidate.matchMargin in 0f..AMBIGUOUS_MATCH_MARGIN
        ) {
            return blocked("pose_identity_overlap_ambiguous")
        }

        return PoseOwnershipResult(
            canDrawSkeleton = true,
            canUseForJudgment = true,
            reason = "pose_ownership_ok",
            trustFlags = listOf("pose_ownership_ok"),
        )
    }

    private fun blocked(reason: String): PoseOwnershipResult {
        return PoseOwnershipResult(
            canDrawSkeleton = false,
            canUseForJudgment = false,
            reason = reason,
            trustFlags = listOf("pose_ownership_blocked", reason),
        )
    }

    private fun PoseBoundingBox.expanded(padding: Float): PoseBoundingBox {
        return PoseBoundingBox(
            minX = (minX - padding).coerceIn(0f, 1f),
            minY = (minY - padding).coerceIn(0f, 1f),
            maxX = (maxX + padding).coerceIn(0f, 1f),
            maxY = (maxY + padding).coerceIn(0f, 1f),
        )
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

    private fun centerDistance(a: PoseBoundingBox, b: PoseBoundingBox): Float {
        val ax = (a.minX + a.maxX) / 2f
        val ay = (a.minY + a.maxY) / 2f
        val bx = (b.minX + b.maxX) / 2f
        val by = (b.minY + b.maxY) / 2f
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private val TORSO_INDICES = intArrayOf(11, 12, 23, 24)
}
