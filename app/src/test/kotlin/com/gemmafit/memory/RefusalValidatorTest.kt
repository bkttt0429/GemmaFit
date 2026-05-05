package com.gemmafit.memory

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusalValidatorTest {
    @Test
    fun covers_every_deny_substring() {
        RefusalValidator.DENY_SUBSTRINGS.forEach { deny ->
            assertNotNull(RefusalValidator.firstDenyMatch("safe prefix $deny safe suffix"))
        }
    }

    @Test
    fun accepts_pose_based_non_clinical_feedback() {
        assertTrue(
            RefusalValidator.isClean(
                "pose-based movement quality feedback with limited camera evidence",
            ),
        )
    }
}
