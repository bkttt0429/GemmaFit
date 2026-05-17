package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonProposalFusionTest {

    @Test
    fun bestProposalPrefersAnchorOverlapOverRawDetectorScore() {
        val anchor = PoseBoundingBox(0.40f, 0.20f, 0.70f, 0.90f)
        val near = proposal(PoseBoundingBox(0.41f, 0.22f, 0.71f, 0.92f), score = 0.70f)
        val far = proposal(PoseBoundingBox(0.02f, 0.10f, 0.22f, 0.70f), score = 0.95f)

        val selected = PersonProposalFusion.bestProposalForAnchor(listOf(far, near), anchor)

        assertEquals(near, selected)
    }

    @Test
    fun lowScoreProposalIsIgnored() {
        val anchor = PoseBoundingBox(0.40f, 0.20f, 0.70f, 0.90f)
        val lowScore = proposal(PoseBoundingBox(0.41f, 0.22f, 0.71f, 0.92f), score = 0.10f)

        assertNull(PersonProposalFusion.bestProposalForAnchor(listOf(lowScore), anchor))
    }

    @Test
    fun candidatesNearProposalKeepsOverlappingSkeletonsOnly() {
        val proposal = proposal(PoseBoundingBox(0.40f, 0.20f, 0.70f, 0.90f), score = 0.80f)
        val overlapping = candidate(0.55f, 0.55f)
        val far = candidate(0.12f, 0.55f)

        val nearby = PersonProposalFusion.candidatesNearProposal(listOf(far, overlapping), proposal)

        assertEquals(listOf(overlapping), nearby)
    }

    @Test
    fun iouIsSymmetricAndBounded() {
        val a = PoseBoundingBox(0.10f, 0.10f, 0.60f, 0.60f)
        val b = PoseBoundingBox(0.40f, 0.40f, 0.90f, 0.90f)

        val ab = PersonProposalFusion.iou(a, b)
        val ba = PersonProposalFusion.iou(b, a)

        assertEquals(ab, ba, 0.0001f)
        assertTrue(ab in 0f..1f)
    }

    private fun proposal(bbox: PoseBoundingBox, score: Float): PersonProposal {
        return PersonProposal(bbox = bbox, score = score, source = "yolo_person")
    }

    private fun candidate(centerX: Float, centerY: Float): PoseCandidate {
        val bbox = PoseBoundingBox(centerX - 0.12f, centerY - 0.24f, centerX + 0.12f, centerY + 0.24f)
        val landmarks = MutableList(33) { PoseLandmarkData(centerX, centerY, 0f, 0.8f) }
        landmarks[11] = PoseLandmarkData(centerX - 0.05f, centerY - 0.12f, 0f, 0.9f)
        landmarks[12] = PoseLandmarkData(centerX + 0.05f, centerY - 0.12f, 0f, 0.9f)
        landmarks[23] = PoseLandmarkData(centerX - 0.04f, centerY + 0.08f, 0f, 0.9f)
        landmarks[24] = PoseLandmarkData(centerX + 0.04f, centerY + 0.08f, 0f, 0.9f)
        return PoseCandidate(
            landmarks = landmarks,
            bbox = bbox,
            centerX = centerX,
            centerY = centerY,
            avgVisibility = 0.8f,
        )
    }
}
