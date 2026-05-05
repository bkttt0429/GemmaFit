package com.gemmafit.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveRecalibrationTest {
    @Test
    fun requires_30_clean_reps_across_3_sessions() {
        val outcome = AdaptiveRecalibration().afterSession(
            existing = calibration(cleanRepsCollected = 20, sessionsSinceCalibration = 1),
            sessionStats = stats(cleanReps = 9, candidateRomProxy = 0.7),
        )

        assertTrue(outcome is AdaptiveRecalibration.Outcome.AccrueOnly)
    }

    @Test
    fun delta_above_15_percent_requires_user_confirm() {
        val outcome = AdaptiveRecalibration().afterSession(
            existing = calibration(
                baselineRomProxy = 1.0,
                cleanRepsCollected = 25,
                sessionsSinceCalibration = 2,
            ),
            sessionStats = stats(cleanReps = 5, candidateRomProxy = 1.2),
        )

        assertTrue(outcome is AdaptiveRecalibration.Outcome.AcceptCandidate)
        outcome as AdaptiveRecalibration.Outcome.AcceptCandidate
        assertEquals(1.2, outcome.candidate, 0.0001)
        assertTrue(outcome.requireUserConfirm)
    }

    @Test
    fun camera_setup_change_forces_recalibration() {
        val outcome = AdaptiveRecalibration().afterSession(
            existing = calibration(),
            sessionStats = stats(cameraSetupChanged = true),
        )

        assertTrue(outcome is AdaptiveRecalibration.Outcome.ForceRecalibration)
    }

    @Test
    fun low_confidence_streak_blocks_baseline_update() {
        val outcome = AdaptiveRecalibration().afterSession(
            existing = calibration(lowConfidenceStreak = 1),
            sessionStats = stats(lowConfidenceStreakDelta = 1),
        )

        assertTrue(outcome is AdaptiveRecalibration.Outcome.PromptAdjustCamera)
    }

    @Test
    fun small_delta_accepts_without_user_confirm() {
        val outcome = AdaptiveRecalibration().afterSession(
            existing = calibration(
                baselineRomProxy = 1.0,
                cleanRepsCollected = 29,
                sessionsSinceCalibration = 2,
            ),
            sessionStats = stats(cleanReps = 1, candidateRomProxy = 1.05),
        )

        assertTrue(outcome is AdaptiveRecalibration.Outcome.AcceptCandidate)
        outcome as AdaptiveRecalibration.Outcome.AcceptCandidate
        assertFalse(outcome.requireUserConfirm)
    }

    private fun calibration(
        baselineRomProxy: Double? = 1.0,
        cleanRepsCollected: Int = 0,
        sessionsSinceCalibration: Int = 0,
        lowConfidenceStreak: Int = 0,
    ) = CalibrationMemory(
        exercise = "chair_sit_to_stand",
        baselineRomProxy = baselineRomProxy,
        baselineTempoSec = 2.0,
        cameraSetupHint = CameraSetupHint("mid", "frontal", "ok"),
        supportType = SupportType.CHAIR,
        capturedAt = 0L,
        sessionsSinceCalibration = sessionsSinceCalibration,
        cleanRepsCollected = cleanRepsCollected,
        lowConfidenceStreak = lowConfidenceStreak,
    )

    private fun stats(
        cleanReps: Int = 0,
        candidateRomProxy: Double? = null,
        cameraSetupChanged: Boolean = false,
        lowConfidenceStreakDelta: Int = 0,
    ) = AdaptiveRecalibration.SessionRepStats(
        cleanReps = cleanReps,
        candidateRomProxy = candidateRomProxy,
        cameraSetupChanged = cameraSetupChanged,
        lowConfidenceStreakDelta = lowConfidenceStreakDelta,
    )
}
