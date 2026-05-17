package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameEvidenceSelectorTest {
    @Test
    fun selectsPhaseAwareFramesFromCleanRep() {
        val frames = listOf(
            candidate(0, 0, phase = "top", hipY = 0.32f, velocity = 0.00f),
            candidate(4, 160, phase = "descent", hipY = 0.42f, velocity = 0.08f),
            candidate(8, 320, phase = "descent", hipY = 0.55f, velocity = 0.14f),
            candidate(12, 520, phase = "bottom", hipY = 0.74f, velocity = 0.01f),
            candidate(16, 720, phase = "ascent", hipY = 0.58f, velocity = -0.12f),
            candidate(20, 920, phase = "top", hipY = 0.35f, velocity = -0.01f),
        )

        val selected = FrameEvidenceSelector.select(frames)

        assertEquals(0, selected.top?.frameIndex)
        assertEquals(8, selected.descent?.frameIndex)
        assertEquals(12, selected.bottom?.frameIndex)
        assertEquals(16, selected.ascent?.frameIndex)
        assertEquals(FrameEvidenceSelector.CONFIDENCE_HIGH, selected.panelConfidence)
    }

    @Test
    fun filtersLowConfidenceAndUnstableFrames() {
        val frames = listOf(
            candidate(0, 0, phase = "top", hipY = 0.31f, confidence = 0.95f, stable = false),
            candidate(1, 100, phase = "top", hipY = 0.30f, confidence = 0.40f),
            candidate(3, 360, phase = "top", hipY = 0.34f, confidence = 0.82f),
            candidate(6, 720, phase = "descent", hipY = 0.48f, velocity = 0.10f),
            candidate(9, 1080, phase = "bottom", hipY = 0.72f, velocity = 0.00f),
            candidate(12, 1440, phase = "ascent", hipY = 0.55f, velocity = -0.09f),
        )

        val selected = FrameEvidenceSelector.select(frames)

        assertEquals(3, selected.top?.frameIndex)
        assertTrue(selected.selectionBasis.contains("filtered_low_confidence_or_unstable_frames"))
    }

    @Test
    fun includesWarningFrameWithoutReplacingPhaseFrames() {
        val frames = listOf(
            candidate(0, 0, phase = "top", hipY = 0.32f),
            candidate(5, 300, phase = "descent", hipY = 0.48f, velocity = 0.12f),
            candidate(10, 650, phase = "bottom", hipY = 0.76f, velocity = 0.00f),
            candidate(13, 900, phase = "unknown", hipY = 0.70f, warning = true, warningIds = listOf("gate.warning.rapid")),
            candidate(16, 1200, phase = "ascent", hipY = 0.58f, velocity = -0.10f),
        )

        val selected = FrameEvidenceSelector.select(frames, warningTimestampMs = 900)

        assertEquals(10, selected.bottom?.frameIndex)
        assertEquals(13, selected.warningFrame?.frameIndex)
        assertTrue(selected.warningFrame?.warningIds?.contains("gate.warning.rapid") == true)
        assertTrue(selected.selectionBasis.contains("warning_timestamp_covered"))
    }

    @Test
    fun fallsBackToSparseSamplingWhenPhaseSignalsAreWeak() {
        val frames = (0 until 6).map { index ->
            candidate(
                frameIndex = index * 10,
                timestampMs = index * 500L,
                phase = "unknown",
                hipY = null,
                velocity = null,
            )
        }

        val selected = FrameEvidenceSelector.select(frames)

        assertEquals(FrameEvidenceSelector.CONFIDENCE_LOW, selected.panelConfidence)
        assertTrue(selected.selectionBasis.contains("fallback_sparse_sampling_or_derived_phase"))
        assertTrue(
            listOf(selected.top, selected.descent, selected.bottom, selected.ascent)
                .filterNotNull()
                .map { it.frameIndex }
                .distinct()
                .size >= 3,
        )
    }

    @Test
    fun returnsLowConfidenceWhenNoFrameIsEligible() {
        val frames = listOf(
            candidate(0, 0, confidence = 0.30f),
            candidate(1, 120, visibility = 0.30f),
            candidate(2, 240, observed = false),
        )

        val selected = FrameEvidenceSelector.select(frames)

        assertNull(selected.sceneAnchor)
        assertNull(selected.top)
        assertEquals(FrameEvidenceSelector.CONFIDENCE_LOW, selected.panelConfidence)
        assertEquals(listOf("no_eligible_frames"), selected.selectionBasis)
    }

    private fun candidate(
        frameIndex: Int,
        timestampMs: Long,
        phase: String = "unknown",
        hipY: Float? = 0.5f,
        velocity: Float? = 0f,
        confidence: Float = 0.9f,
        visibility: Float = 0.9f,
        observed: Boolean = true,
        stable: Boolean = true,
        warning: Boolean = false,
        warningIds: List<String> = emptyList(),
    ): FrameEvidenceCandidate =
        FrameEvidenceCandidate(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            phase = phase,
            poseConfidence = confidence,
            fullBodyVisibility = visibility,
            subjectObserved = observed,
            subjectStable = stable,
            hipY = hipY,
            hipVelocityY = velocity,
            blurScore = 0.1f,
            hasWarning = warning,
            warningIds = warningIds,
            evidenceRefs = warningIds,
        )
}
