package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LandmarkStabilizerTest {
    private fun pose(
        xOffset: Float = 0f,
        yOffset: Float = 0f,
        visibility: Float = 0.9f,
    ): List<PoseLandmarkData> {
        return List(33) { index ->
            PoseLandmarkData(
                x = 0.2f + (index % 6) * 0.1f + xOffset,
                y = 0.2f + (index / 6) * 0.08f + yOffset,
                z = 0f,
                visibility = visibility,
            )
        }
    }

    private fun List<PoseLandmarkData>.withJoint(
        index: Int,
        x: Float,
        y: Float = this[index].y,
        visibility: Float = this[index].visibility,
    ): List<PoseLandmarkData> {
        return toMutableList().also { landmarks ->
            landmarks[index] = landmarks[index].copy(x = x, y = y, visibility = visibility)
        }
    }

    @Test
    fun apply_holdsSubPixelJitterInsideDeadband() {
        val stabilizer = LandmarkStabilizer(alpha = 0.35f, deadband = 0.004f)
        val first = stabilizer.apply(pose())
        val second = stabilizer.apply(pose(xOffset = 0.002f, yOffset = 0.001f))

        assertEquals(first[25].x, second[25].x, 0.00001f)
        assertEquals(first[25].y, second[25].y, 0.00001f)
    }

    @Test
    fun apply_smoothsVisibleMotionInsteadOfSnappingToRawPoint() {
        val stabilizer = LandmarkStabilizer(alpha = 0.35f, deadband = 0.004f)
        val first = stabilizer.apply(pose())
        val second = stabilizer.apply(pose(xOffset = 0.04f))

        assertTrue(second[25].x > first[25].x)
        assertTrue(second[25].x < first[25].x + 0.04f)
    }

    @Test
    fun apply_dropsVisibilityWithoutKeepingAPhantomVisibleJoint() {
        val stabilizer = LandmarkStabilizer(alpha = 0.35f, deadband = 0.004f)
        stabilizer.apply(pose())
        val lowVisibility = stabilizer.apply(pose(xOffset = 0.02f, visibility = 0.05f))

        assertEquals(0.05f, lowVisibility[25].visibility, 0.00001f)
    }

    @Test
    fun apply_doesNotReusePreviousCoordinatesForLowVisibilityJoint() {
        val stabilizer = LandmarkStabilizer(alpha = 0.35f, deadband = 0.004f)
        val first = stabilizer.apply(pose())
        val rawHiddenX = first[25].x + 0.12f

        val lowVisibility = stabilizer.apply(
            pose().withJoint(25, x = rawHiddenX, visibility = 0.05f)
        )

        assertEquals(rawHiddenX, lowVisibility[25].x, 0.00001f)
        assertEquals(0.05f, lowVisibility[25].visibility, 0.00001f)
    }

    @Test
    fun apply_recoveredJointDoesNotBlendFromHiddenPoint() {
        val stabilizer = LandmarkStabilizer(alpha = 0.35f, deadband = 0.004f)
        val first = stabilizer.apply(pose())
        val recoveredX = first[25].x + 0.12f
        stabilizer.apply(pose().withJoint(25, x = recoveredX, visibility = 0.05f))

        val recovered = stabilizer.apply(pose().withJoint(25, x = recoveredX, visibility = 0.9f))

        assertEquals(recoveredX, recovered[25].x, 0.00001f)
        assertEquals(0.9f, recovered[25].visibility, 0.00001f)
    }
}
