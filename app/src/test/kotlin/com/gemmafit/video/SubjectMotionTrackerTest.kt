package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectMotionTrackerTest {

    @Test
    fun predictsForwardMotionFromRecentBoxes() {
        val tracker = SubjectMotionTracker()

        tracker.update(box(centerX = 0.30f), timestampMs = 0L)
        tracker.update(box(centerX = 0.40f), timestampMs = 100L)

        val predicted = tracker.predict(timestampMs = 200L)

        assertNotNull(predicted)
        val centerX = predicted!!.bbox.centerX()
        assertTrue("prediction should continue rightward, got $centerX", centerX > 0.40f)
    }

    @Test
    fun confidenceDecaysDuringHold() {
        val tracker = SubjectMotionTracker()

        tracker.update(box(centerX = 0.50f), timestampMs = 0L)
        val fresh = tracker.predict(timestampMs = 100L)!!.confidence
        tracker.markHold(timestampMs = 200L)
        tracker.markHold(timestampMs = 300L)
        val held = tracker.predict(timestampMs = 400L)!!.confidence

        assertTrue("held confidence should decay", held < fresh)
    }

    @Test
    fun stalePredictionReturnsNull() {
        val tracker = SubjectMotionTracker(maxPredictMs = 300L)

        tracker.update(box(centerX = 0.50f), timestampMs = 0L)

        assertNull(tracker.predict(timestampMs = 400L))
    }

    @Test
    fun timelineBackwardsDropsPredictionUntilUpdated() {
        val tracker = SubjectMotionTracker()

        tracker.update(box(centerX = 0.50f), timestampMs = 1_000L)

        assertNull(tracker.predict(timestampMs = 100L))
        tracker.update(box(centerX = 0.20f), timestampMs = 100L)
        assertEquals(0.20f, tracker.predict(timestampMs = 100L)!!.bbox.centerX(), 0.0001f)
    }

    private fun box(centerX: Float, centerY: Float = 0.50f): PoseBoundingBox {
        return PoseBoundingBox(
            minX = centerX - 0.05f,
            minY = centerY - 0.10f,
            maxX = centerX + 0.05f,
            maxY = centerY + 0.10f,
        )
    }

    private fun PoseBoundingBox.centerX(): Float = (minX + maxX) / 2f
}
