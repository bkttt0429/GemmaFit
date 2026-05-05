package com.gemmafit.memory

/**
 * Adaptive baseline-recalibration policy.
 *
 * Replaces the "every N sessions" rule with evidence-quality gating
 * (implementation_plan.md §11.6).
 *
 * Triggers a baseline candidate ONLY when:
 *   - clean_reps_collected ≥ MIN_CLEAN_REPS (default 30)
 *   - clean reps span ≥ MIN_DISTINCT_SESSIONS (default 3)
 *
 * If the candidate baseline differs from the stored baseline by more
 * than DELTA_CONFIRM (default 15%), the manager surfaces a user
 * confirmation rather than auto-overwriting.
 *
 * If `low_confidence_streak ≥ 2`, no baseline update is proposed —
 * the user is asked to adjust the camera instead.
 *
 * Falls back to N_SESSIONS = 10 only when no clean reps have been
 * collected at all, to avoid getting permanently stuck on a stale
 * baseline.
 */
class AdaptiveRecalibration(
    private val minCleanReps: Int = 30,
    private val minDistinctSessions: Int = 3,
    private val deltaConfirmRatio: Double = 0.15,
    private val fallbackSessionsThreshold: Int = 10,
) {

    /**
     * Decide what to do when a session ends, given the current
     * calibration state and stats from the just-finished session.
     */
    fun afterSession(
        existing: CalibrationMemory,
        sessionStats: SessionRepStats,
    ): Outcome {
        // Camera-setup change always forces recalibration.
        if (sessionStats.cameraSetupChanged) {
            return Outcome.ForceRecalibration("camera_setup_changed")
        }

        // Persistent low-confidence: ask user to fix the camera.
        if (existing.lowConfidenceStreak + sessionStats.lowConfidenceStreakDelta >= 2) {
            return Outcome.PromptAdjustCamera("low_confidence_streak")
        }

        // Add this session's clean reps to the running total.
        val newCleanReps = existing.cleanRepsCollected + sessionStats.cleanReps
        val newDistinctSessions = existing.sessionsSinceCalibration + 1

        if (newCleanReps >= minCleanReps && newDistinctSessions >= minDistinctSessions) {
            // Evidence-driven candidate.
            val candidate = sessionStats.candidateRomProxy
                ?: return Outcome.AccrueOnly(newCleanReps, newDistinctSessions)
            val storedBaseline = existing.baselineRomProxy
            return if (storedBaseline == null) {
                // First-ever baseline — accept silently.
                Outcome.AcceptCandidate(candidate, requireUserConfirm = false)
            } else {
                val delta = kotlin.math.abs(candidate - storedBaseline) /
                    kotlin.math.max(storedBaseline, 1e-6)
                Outcome.AcceptCandidate(
                    candidate = candidate,
                    requireUserConfirm = delta > deltaConfirmRatio,
                )
            }
        }

        // Fallback: long stretch with no clean reps but lots of sessions.
        if (newCleanReps == 0 && newDistinctSessions >= fallbackSessionsThreshold) {
            return Outcome.PromptAdjustCamera("no_clean_reps_in_${newDistinctSessions}_sessions")
        }

        return Outcome.AccrueOnly(newCleanReps, newDistinctSessions)
    }

    /**
     * Stats the rep counter / motion quality module produces at session
     * end, used to update calibration.
     */
    data class SessionRepStats(
        val cleanReps: Int,                       // OK or MONITOR, no warnings, high visibility
        val candidateRomProxy: Double?,           // null if not measurable this session
        val cameraSetupChanged: Boolean,
        val lowConfidenceStreakDelta: Int,        // 0 or 1; 1 means session ended with high low-conf rate
    )

    sealed class Outcome {
        /** Wait for more clean reps before proposing a baseline. */
        data class AccrueOnly(
            val newCleanReps: Int,
            val newSessionsSince: Int,
        ) : Outcome()

        /** New baseline candidate ready; may need user confirmation. */
        data class AcceptCandidate(
            val candidate: Double,
            val requireUserConfirm: Boolean,
        ) : Outcome()

        /** Force user to redo calibration (e.g. camera moved). */
        data class ForceRecalibration(val reason: String) : Outcome()

        /** Don't update baseline — surface a UI hint to fix the camera. */
        data class PromptAdjustCamera(val reason: String) : Outcome()
    }
}
