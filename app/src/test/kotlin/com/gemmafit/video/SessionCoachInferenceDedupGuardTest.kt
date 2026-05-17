package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoachInferenceDedupGuardTest {
    @Test
    fun firstCallFires() {
        val guard = SessionCoachInferenceDedupGuard()

        assertTrue(guard.shouldFire("run1|session|model"))
    }

    @Test
    fun successBlocksDuplicateKey() {
        val guard = SessionCoachInferenceDedupGuard()
        val key = "run1|session|model"

        assertTrue(guard.shouldFire(key))
        guard.recordSuccess(key)

        assertFalse(guard.shouldFire(key))
    }

    @Test
    fun oneFailureAllowsRetry() {
        val guard = SessionCoachInferenceDedupGuard()
        val key = "run1|session|model"

        guard.recordFailure(key)

        assertTrue(guard.shouldFire(key))
    }

    @Test
    fun twoFailuresBlockDuplicateKey() {
        val guard = SessionCoachInferenceDedupGuard()
        val key = "run1|session|model"

        guard.recordFailure(key)
        guard.recordFailure(key)

        assertFalse(guard.shouldFire(key))
    }

    @Test
    fun pureDecisionSkipsOnlyCompletedKey() {
        assertEquals(
            SessionCoachDedupDecision.SKIP,
            SessionCoachInferenceDedupGuard.decide(
                key = "run1|session|model",
                state = SessionCoachDedupState(lastDoneKey = "run1|session|model"),
            ),
        )
        assertEquals(
            SessionCoachDedupDecision.FIRE,
            SessionCoachInferenceDedupGuard.decide(
                key = "run2|session|model",
                state = SessionCoachDedupState(lastDoneKey = "run1|session|model"),
            ),
        )
    }
}
