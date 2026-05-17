package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectRelocalizationPolicyTest {

    @Test
    fun uncertainFramesDebounceDetectorBurst() {
        val policy = SubjectRelocalizationPolicy(uncertainFramesToTrigger = 3)

        val first = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = false,
            candidateCount = 2,
            reason = "subject_temporarily_occluded",
            timestampMs = 0L,
        )
        val second = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = false,
            candidateCount = 2,
            reason = "subject_temporarily_occluded",
            timestampMs = 200L,
        )
        val third = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = false,
            candidateCount = 2,
            reason = "subject_temporarily_occluded",
            timestampMs = 400L,
        )

        assertFalse(first.shouldRequestBurst)
        assertFalse(second.shouldRequestBurst)
        assertTrue(third.shouldRequestBurst)
        assertEquals(SubjectRelocalizationMode.RELOCALIZING, third.mode)
    }

    @Test
    fun minBurstIntervalPreventsDetectorThrash() {
        val policy = SubjectRelocalizationPolicy(
            uncertainFramesToTrigger = 1,
            minBurstIntervalMs = 750L,
        )

        val first = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = false,
            candidateCount = 2,
            reason = "subject_identity_reacquiring",
            timestampMs = 1_000L,
        )
        val tooSoon = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = false,
            candidateCount = 2,
            reason = "subject_identity_reacquiring",
            timestampMs = 1_500L,
        )
        val afterInterval = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = false,
            candidateCount = 2,
            reason = "subject_identity_reacquiring",
            timestampMs = 1_760L,
        )

        assertTrue(first.shouldRequestBurst)
        assertFalse(tooSoon.shouldRequestBurst)
        assertTrue(afterInterval.shouldRequestBurst)
    }

    @Test
    fun recoveryRequiresStableFramesAndStartsCooldown() {
        val policy = SubjectRelocalizationPolicy(
            uncertainFramesToTrigger = 1,
            stableFramesToRecover = 2,
            recoveryCooldownMs = 2_000L,
        )

        val request = policy.update(
            status = SubjectLockStatus.SUBJECT_LOST,
            hasActiveSubject = false,
            candidateCount = 0,
            reason = "locked_subject_lost",
            timestampMs = 0L,
        )
        val firstStable = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = true,
            candidateCount = 1,
            reason = "",
            timestampMs = 200L,
        )
        val recovered = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = true,
            candidateCount = 1,
            reason = "",
            timestampMs = 400L,
        )
        val cooldown = policy.update(
            status = SubjectLockStatus.LOCKED,
            hasActiveSubject = false,
            candidateCount = 2,
            reason = "subject_temporarily_occluded",
            timestampMs = 800L,
        )

        assertTrue(request.shouldRequestBurst)
        assertEquals(SubjectRelocalizationMode.RELOCALIZING, firstStable.mode)
        assertTrue(recovered.trustFlags.contains("relocalization_cooldown"))
        assertFalse(cooldown.shouldRequestBurst)
        assertTrue(cooldown.trustFlags.contains("relocalization_cooldown"))
    }
}
