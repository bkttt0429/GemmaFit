package com.gemmafit.senior

import com.gemmafit.video.PoseLandmarkData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeniorGestureDetectorTest {
    private val detector = SeniorGestureDetector()

    @Test
    fun detects_left_arm_raise() {
        val landmarks = baseLandmarks().toMutableList()
        landmarks[SeniorGestureDetector.LEFT_WRIST] = point(0.35f, 0.20f)

        val result = detector.detect(landmarks)

        assertEquals("left_arm_raise", result.gesture)
        assertEquals("metric.dual_task.gesture.left_arm_raise", result.evidenceRef)
        assertTrue(result.confidence > 0.7)
    }

    @Test
    fun detects_two_hand_raise_as_skip_cancel() {
        val landmarks = baseLandmarks().toMutableList()
        landmarks[SeniorGestureDetector.LEFT_WRIST] = point(0.35f, 0.20f)
        landmarks[SeniorGestureDetector.RIGHT_WRIST] = point(0.65f, 0.20f)

        val result = detector.detect(landmarks)

        assertEquals("two_hand_raise", result.gesture)
    }

    @Test
    fun blocks_when_wrists_not_visible() {
        val landmarks = baseLandmarks().toMutableList()
        landmarks[SeniorGestureDetector.LEFT_WRIST] = point(0.35f, 0.70f, visibility = 0.1f)
        landmarks[SeniorGestureDetector.RIGHT_WRIST] = point(0.65f, 0.70f, visibility = 0.1f)

        val result = detector.detect(landmarks)

        assertEquals("none", result.gesture)
        assertEquals("wrists_not_visible", result.fallbackReason)
    }

    private fun baseLandmarks(): List<PoseLandmarkData> {
        return List(33) { point(0.5f, 0.7f) }.toMutableList().apply {
            this[SeniorGestureDetector.LEFT_SHOULDER] = point(0.40f, 0.40f)
            this[SeniorGestureDetector.RIGHT_SHOULDER] = point(0.60f, 0.40f)
            this[SeniorGestureDetector.LEFT_WRIST] = point(0.35f, 0.70f)
            this[SeniorGestureDetector.RIGHT_WRIST] = point(0.65f, 0.70f)
        }
    }

    private fun point(x: Float, y: Float, visibility: Float = 0.9f): PoseLandmarkData {
        return PoseLandmarkData(x = x, y = y, z = 0f, visibility = visibility)
    }
}
