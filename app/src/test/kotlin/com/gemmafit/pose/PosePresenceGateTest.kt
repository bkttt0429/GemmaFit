package com.gemmafit.pose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosePresenceGateTest {

    private data class Lm(
        val x: Float,
        val y: Float,
        val visibility: Float,
    )

    private fun distributedPose(visibility: Float): List<Lm> {
        return List(33) { index ->
            val x = ((index % 6).toFloat() / 5f).coerceIn(0f, 1f)
            val y = ((index / 6).toFloat() / 5f).coerceIn(0f, 1f)
            Lm(x, y, visibility)
        }
    }

    private fun List<Lm>.withVisible(indices: Iterable<Int>, visibility: Float = 0.9f): List<Lm> {
        val visible = indices.toSet()
        return mapIndexed { index, lm ->
            if (index in visible) lm.copy(visibility = visibility) else lm.copy(visibility = 0f)
        }
    }

    private fun canRender(landmarks: List<Lm>): Boolean {
        return PosePresenceGate.canRender(landmarks, { it.x }, { it.y }, { it.visibility })
    }

    @Test
    fun distributedZeroVisibilityIsBlocked() {
        assertFalse(canRender(distributedPose(0f)))
    }

    @Test
    fun missingVisibilityAsZeroIsBlocked() {
        val missingVisibilityFallback = distributedPose(0f)

        assertFalse(canRender(missingVisibilityFallback))
    }

    @Test
    fun faceOnlyHighConfidenceIsBlocked() {
        val faceOnly = distributedPose(0f).withVisible(0..10)

        assertFalse(canRender(faceOnly))
    }

    @Test
    fun torsoAndUpperBodyCropCanRender() {
        val upperBody = distributedPose(0f).withVisible(listOf(11, 12, 13, 14, 15, 16, 23, 24))

        assertTrue(canRender(upperBody))
    }

    @Test
    fun smallDistantBodyCanStillRenderForVideoReview() {
        val landmarks = MutableList(33) { Lm(0.50f, 0.50f, 0f) }
        listOf(
            11 to Lm(0.46f, 0.46f, 0.9f),
            12 to Lm(0.54f, 0.46f, 0.9f),
            13 to Lm(0.45f, 0.50f, 0.9f),
            14 to Lm(0.55f, 0.50f, 0.9f),
            15 to Lm(0.44f, 0.54f, 0.9f),
            16 to Lm(0.56f, 0.54f, 0.9f),
            23 to Lm(0.47f, 0.55f, 0.9f),
            24 to Lm(0.53f, 0.55f, 0.9f),
        ).forEach { (index, landmark) -> landmarks[index] = landmark }

        assertTrue(canRender(landmarks))
    }

    @Test
    fun bboxAreaAloneDoesNotPass() {
        val twoVisibleCorners = distributedPose(0f).withVisible(listOf(11, 24))

        assertFalse(canRender(twoVisibleCorners))
    }
}
