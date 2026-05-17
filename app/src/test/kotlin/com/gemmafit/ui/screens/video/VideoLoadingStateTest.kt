package com.gemmafit.ui.screens.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gemmafit.video.SubjectLockStatus

class VideoLoadingStateTest {
    @Test
    fun loadingPhasesUseLoopingProgress() {
        listOf(
            "video_loading",
            "loading_model",
            "preview_loading",
            "preview_analysis",
        ).forEach { subPhase ->
            assertNull(
                videoLoadingProgressOrNull(
                    currentFrame = 12,
                    totalFrames = 48,
                    subPhaseProgress = 0.25f,
                    subPhase = subPhase,
                )
            )
            assertFalse(shouldShowLoadingFrameStats(subPhase = subPhase, totalFrames = 48))
        }
    }

    @Test
    fun nonLoopingLoadingUsesSubPhaseProgressWhenAvailable() {
        assertEquals(
            0.12f,
            videoLoadingProgressOrNull(
                currentFrame = 0,
                totalFrames = 48,
                subPhaseProgress = 0.12f,
                subPhase = "manual_review",
            ) ?: -1f,
            0.0001f,
        )
    }

    @Test
    fun frameStatsOnlyShowWhenFramesAreMeaningful() {
        assertTrue(shouldShowLoadingFrameStats(subPhase = "manual_review", totalFrames = 100))
        assertTrue(shouldShowLoadingFrameStats(subPhase = "full_analysis", totalFrames = 100))
        assertFalse(shouldShowLoadingFrameStats(subPhase = "video_loading", totalFrames = 0))
    }

    @Test
    fun fullAnalysisUsesDeterminateProgress() {
        assertEquals(
            0.25f,
            videoLoadingProgressOrNull(
                currentFrame = 12,
                totalFrames = 48,
                subPhaseProgress = 0.25f,
                subPhase = "full_analysis",
            ) ?: -1f,
            0.0001f,
        )
    }

    @Test
    fun unresolvedSubjectsDoNotRenderSecondarySkeletons() {
        assertFalse(
            shouldRenderSecondarySkeletons(
                subjectLockStatus = SubjectLockStatus.NEEDS_SELECTION,
                hasActiveSubject = false,
            )
        )
        assertFalse(
            shouldRenderSecondarySkeletons(
                subjectLockStatus = SubjectLockStatus.SUBJECT_LOST,
                hasActiveSubject = true,
            )
        )
        assertTrue(
            shouldRenderSecondarySkeletons(
                subjectLockStatus = SubjectLockStatus.AUTO_LOCKED,
                hasActiveSubject = true,
            )
        )
    }
}
