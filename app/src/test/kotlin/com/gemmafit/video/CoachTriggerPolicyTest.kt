package com.gemmafit.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTriggerPolicyTest {
    @Test
    fun summaryOnlyPolicyDoesNotTriggerLiveModelCalls() {
        assertFalse(CoachTriggerPolicy.shouldTrigger(CoachTriggerEvent.WARNING_CHANGED))
        assertFalse(CoachTriggerPolicy.shouldTrigger(CoachTriggerEvent.REP_COMPLETED))
    }

    @Test
    fun summaryOnlyPolicyTriggersOnFullAnalysisComplete() {
        assertTrue(CoachTriggerPolicy.shouldTrigger(CoachTriggerEvent.FULL_ANALYSIS_COMPLETE))
    }
}
