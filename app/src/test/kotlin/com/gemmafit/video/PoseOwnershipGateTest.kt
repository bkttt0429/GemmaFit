package com.gemmafit.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseOwnershipGateTest {

    @Test
    fun acceptsCleanPoseInsideTargetBox() {
        val candidate = candidate(centerX = 0.50f, centerY = 0.50f)

        val result = PoseOwnershipGate.evaluate(candidate)

        assertTrue(result.canDrawSkeleton)
        assertTrue(result.canUseForJudgment)
    }

    @Test
    fun rejectsPoseFarFromTargetBox() {
        val candidate = candidate(centerX = 0.25f, centerY = 0.50f)
        val target = box(centerX = 0.70f, centerY = 0.50f)

        val result = PoseOwnershipGate.evaluate(candidate, targetBbox = target)

        assertFalse(result.canDrawSkeleton)
        assertFalse(result.canUseForJudgment)
        assertTrue(result.trustFlags.contains("pose_ownership_blocked"))
    }

    @Test
    fun rejectsWhenTorsoDoesNotBelongToTargetBox() {
        val candidate = candidate(centerX = 0.50f, centerY = 0.50f)
        val target = box(centerX = 0.50f, centerY = 0.15f)

        val result = PoseOwnershipGate.evaluate(candidate, targetBbox = target)

        assertFalse(result.canDrawSkeleton)
    }

    @Test
    fun ambiguousOverlappingIdentityBlocksHardPoseUse() {
        val candidate = candidate(centerX = 0.50f, centerY = 0.50f).copy(
            identityScore = 0.62f,
            matchMargin = 0.01f,
        )
        val duplicate = candidate(centerX = 0.51f, centerY = 0.50f)

        val result = PoseOwnershipGate.evaluate(candidate, otherCandidates = listOf(duplicate))

        assertFalse(result.canDrawSkeleton)
        assertTrue(result.reason == "pose_identity_overlap_ambiguous")
    }

    private fun candidate(
        centerX: Float,
        centerY: Float,
        halfWidth: Float = 0.06f,
        halfHeight: Float = 0.16f,
    ): PoseCandidate {
        val landmarks = MutableList(33) { index ->
            val col = (index % 5) - 2
            val row = ((index / 5) % 7) - 3
            PoseLandmarkData(
                x = centerX + col * halfWidth / 2f,
                y = centerY + row * halfHeight / 4f,
                z = 0f,
                visibility = 0.9f,
            )
        }
        landmarks[11] = PoseLandmarkData(centerX - halfWidth * 0.55f, centerY - halfHeight * 0.35f, 0f, 0.9f)
        landmarks[12] = PoseLandmarkData(centerX + halfWidth * 0.55f, centerY - halfHeight * 0.35f, 0f, 0.9f)
        landmarks[23] = PoseLandmarkData(centerX - halfWidth * 0.45f, centerY + halfHeight * 0.20f, 0f, 0.9f)
        landmarks[24] = PoseLandmarkData(centerX + halfWidth * 0.45f, centerY + halfHeight * 0.20f, 0f, 0.9f)
        val bbox = box(centerX, centerY, halfWidth, halfHeight)
        return PoseCandidate(
            landmarks = landmarks,
            bbox = bbox,
            centerX = centerX,
            centerY = centerY,
            avgVisibility = 0.9f,
        )
    }

    private fun box(
        centerX: Float,
        centerY: Float,
        halfWidth: Float = 0.06f,
        halfHeight: Float = 0.16f,
    ): PoseBoundingBox {
        return PoseBoundingBox(
            minX = centerX - halfWidth,
            minY = centerY - halfHeight,
            maxX = centerX + halfWidth,
            maxY = centerY + halfHeight,
        )
    }
}
