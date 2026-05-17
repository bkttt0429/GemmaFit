package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonTrackingPolicyTest {
    @Test
    fun trustFlagsOverrideLockedStatus() {
        val state = PersonTrackingPolicy.stateFor(
            subjectLockStatus = SubjectLockStatus.LOCKED,
            subjectTrustFlags = listOf("subject_identity_reacquiring"),
        )

        assertEquals(PersonTrackingState.PREDICTED, state)
        assertTrue(PersonTrackingPolicy.blocksHardJudgment(state))
    }

    @Test
    fun observedAutoAndSingleStatesAllowHardJudgment() {
        assertFalse(PersonTrackingPolicy.blocksHardJudgment(PersonTrackingState.OBSERVED))
        assertFalse(PersonTrackingPolicy.blocksHardJudgment(PersonTrackingState.AUTO_LOCKED))
        assertFalse(PersonTrackingPolicy.blocksHardJudgment(PersonTrackingState.SINGLE_PERSON))
    }

    @Test
    fun nonObservedStatesBlockHardJudgment() {
        listOf(
            PersonTrackingState.PREDICTED,
            PersonTrackingState.HOLD,
            PersonTrackingState.LOST,
            PersonTrackingState.NO_PERSON,
            PersonTrackingState.MULTI_PERSON_AMBIGUOUS,
            PersonTrackingState.NEEDS_SELECTION,
        ).forEach { state ->
            assertTrue("Expected $state to block hard judgment", PersonTrackingPolicy.blocksHardJudgment(state))
        }
    }
}
