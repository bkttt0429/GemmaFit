package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectIdentityMatcherTest {

    @Test
    fun appearancePreventsGeometryOnlyIdentitySwitch() {
        val anchor = signature(0 to 1f)
        val previous = candidate(0.45f, 0.50f, appearance = anchor)
        val geometryBestWrongPerson = candidate(0.46f, 0.50f, appearance = signature(8 to 1f))
        val samePersonAfterCrossing = candidate(0.55f, 0.50f, appearance = anchor)

        val match = SubjectIdentityMatcher.select(
            previous = previous,
            appearanceAnchor = anchor,
            candidates = listOf(geometryBestWrongPerson, samePersonAfterCrossing),
            threshold = 0.25f,
        )

        assertFalse(match.hold)
        assertEquals(0.55f, match.candidate?.centerX ?: 0f, 0.0001f)
    }

    @Test
    fun lowAppearanceMatchHoldsInsteadOfSelectingWrongPerson() {
        val anchor = signature(0 to 1f)
        val previous = candidate(0.45f, 0.50f, appearance = anchor)
        val wrongCandidate = candidate(0.46f, 0.50f, appearance = signature(8 to 1f))

        val match = SubjectIdentityMatcher.select(
            previous = previous,
            appearanceAnchor = anchor,
            candidates = listOf(wrongCandidate),
            threshold = 0.25f,
        )

        assertTrue(match.hold)
        assertNull(match.candidate)
        assertEquals("subject_appearance_mismatch", match.reason)
    }

    @Test
    fun closeTopTwoAppearanceMatchesHoldAsOccluded() {
        val anchor = signature(0 to 1f)
        val previous = candidate(0.45f, 0.50f, appearance = anchor)
        val first = candidate(0.49f, 0.50f, appearance = signature(0 to 0.42f, 8 to 0.58f))
        val second = candidate(0.51f, 0.50f, appearance = signature(0 to 0.40f, 9 to 0.60f))

        val match = SubjectIdentityMatcher.select(
            previous = previous,
            appearanceAnchor = anchor,
            candidates = listOf(first, second),
            threshold = 0.25f,
        )

        assertTrue(match.hold)
        assertNull(match.candidate)
        assertEquals("subject_temporarily_occluded", match.reason)
    }

    @Test
    fun duplicateCandidateForSameSubjectDoesNotForceHold() {
        val anchor = signature(0 to 1f)
        val previous = candidate(0.45f, 0.50f, appearance = anchor)
        val first = candidate(0.49f, 0.50f, appearance = anchor)
        val duplicate = candidate(0.515f, 0.50f, appearance = anchor)

        val match = SubjectIdentityMatcher.select(
            previous = previous,
            appearanceAnchor = anchor,
            candidates = listOf(first, duplicate),
            threshold = 0.25f,
        )

        assertFalse(match.hold)
        assertEquals(0.49f, match.candidate?.centerX ?: 0f, 0.0001f)
    }

    @Test
    fun recoveringFromHoldRequiresStrongAppearanceEvidence() {
        val anchor = signature(0 to 1f)
        val previous = candidate(0.45f, 0.50f, appearance = anchor)
        val weakReacquire = candidate(0.52f, 0.50f, appearance = signature(0 to 0.54f, 8 to 0.46f))

        val match = SubjectIdentityMatcher.select(
            previous = previous,
            appearanceAnchor = anchor,
            candidates = listOf(weakReacquire),
            threshold = 0.25f,
            recoveringFromHold = true,
        )

        assertTrue(match.hold)
        assertNull(match.candidate)
        assertEquals("subject_identity_reacquiring", match.reason)
    }

    @Test
    fun recoveringFromHoldAcceptsClearAppearanceEvidence() {
        val anchor = signature(0 to 1f)
        val previous = candidate(0.45f, 0.50f, appearance = anchor)
        val clearReacquire = candidate(0.52f, 0.50f, appearance = signature(0 to 0.57f, 8 to 0.43f))

        val match = SubjectIdentityMatcher.select(
            previous = previous,
            appearanceAnchor = anchor,
            candidates = listOf(clearReacquire),
            threshold = 0.25f,
            recoveringFromHold = true,
        )

        assertFalse(match.hold)
        assertEquals(0.52f, match.candidate?.centerX ?: 0f, 0.0001f)
    }

    @Test
    fun predictedBboxBoostsMotionConsistentCandidateScore() {
        val anchor = signature(0 to 1f)
        val previous = candidate(0.45f, 0.50f, appearance = anchor)
        val forwardCandidate = candidate(0.57f, 0.50f, appearance = anchor)
        val predicted = PoseBoundingBox(0.52f, 0.40f, 0.62f, 0.60f)

        val withoutPrediction = SubjectIdentityMatcher.scoreCandidate(
            previous = previous,
            appearanceAnchor = anchor,
            candidate = forwardCandidate,
        )
        val withPrediction = SubjectIdentityMatcher.scoreCandidate(
            previous = previous,
            appearanceAnchor = anchor,
            candidate = forwardCandidate,
            predictedBbox = predicted,
        )

        assertTrue(withPrediction.trackScore > withoutPrediction.trackScore)
    }

    private fun signature(vararg bins: Pair<Int, Float>): SubjectAppearanceSignature {
        val hist = FloatArray(12 * 6 * 4)
        for ((index, value) in bins) {
            hist[index] = value
        }
        return SubjectAppearanceSignature(hist)
    }

    private fun candidate(
        centerX: Float,
        centerY: Float,
        half: Float = 0.10f,
        appearance: SubjectAppearanceSignature? = null,
    ): PoseCandidate {
        val landmarks = MutableList(33) { index ->
            val dx = ((index % 5) - 2) * half / 2f
            val dy = (((index / 5) % 5) - 2) * half / 2f
            PoseLandmarkData(centerX + dx, centerY + dy, 0f, 0.9f)
        }
        landmarks[11] = PoseLandmarkData(centerX - half * 0.45f, centerY - half * 0.80f, 0f, 0.9f)
        landmarks[12] = PoseLandmarkData(centerX + half * 0.45f, centerY - half * 0.80f, 0f, 0.9f)
        landmarks[23] = PoseLandmarkData(centerX - half * 0.35f, centerY + half * 0.35f, 0f, 0.9f)
        landmarks[24] = PoseLandmarkData(centerX + half * 0.35f, centerY + half * 0.35f, 0f, 0.9f)
        val bbox = PoseBoundingBox(
            minX = centerX - half,
            minY = centerY - half,
            maxX = centerX + half,
            maxY = centerY + half,
        )
        return PoseCandidate(
            landmarks = landmarks,
            bbox = bbox,
            centerX = centerX,
            centerY = centerY,
            avgVisibility = 0.9f,
            appearance = appearance,
        )
    }
}
