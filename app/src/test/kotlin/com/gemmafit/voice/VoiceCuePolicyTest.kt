package com.gemmafit.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCuePolicyTest {
    @Test
    fun techniqueWarningsNeedPersistenceBeforeSpeech() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertFalse(
            policy.shouldSpeak(
                functionName = "correct_asymmetry",
                text = "Even both sides.",
                priority = CoachVoice.MessagePriority.HIGH,
            ).shouldSpeak
        )

        now = 800L
        assertTrue(
            policy.shouldSpeak(
                functionName = "correct_asymmetry",
                text = "Even both sides.",
                priority = CoachVoice.MessagePriority.HIGH,
            ).shouldSpeak
        )
    }

    @Test
    fun visibilityCuesWaitLongerAndThenCooldown() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertFalse(
            policy.shouldSpeak(
                functionName = "no_person_detected",
                text = "Step into frame.",
                priority = CoachVoice.MessagePriority.HIGH,
            ).shouldSpeak
        )

        now = 1_000L
        assertFalse(
            policy.shouldSpeak(
                functionName = "no_person_detected",
                text = "Step into frame.",
                priority = CoachVoice.MessagePriority.HIGH,
            ).shouldSpeak
        )

        now = 2_100L
        assertTrue(
            policy.shouldSpeak(
                functionName = "no_person_detected",
                text = "Step into frame.",
                priority = CoachVoice.MessagePriority.HIGH,
            ).shouldSpeak
        )

        now = 5_000L
        assertFalse(
            policy.shouldSpeak(
                functionName = "no_person_detected",
                text = "Step into frame.",
                priority = CoachVoice.MessagePriority.HIGH,
            ).shouldSpeak
        )
    }

    @Test
    fun criticalSafetyCuesSpeakImmediatelyButDoNotRepeatSameCueRapidly() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertTrue(
            policy.shouldSpeak(
                functionName = "warn_com_offset",
                text = "Re-center.",
                priority = CoachVoice.MessagePriority.CRITICAL,
            ).shouldSpeak
        )

        now = 1_000L
        assertFalse(
            policy.shouldSpeak(
                functionName = "warn_com_offset",
                text = "Re-center.",
                priority = CoachVoice.MessagePriority.CRITICAL,
            ).shouldSpeak
        )

        now = 5_100L
        assertTrue(
            policy.shouldSpeak(
                functionName = "warn_com_offset",
                text = "Re-center.",
                priority = CoachVoice.MessagePriority.CRITICAL,
            ).shouldSpeak
        )
    }

    @Test
    fun differentCriticalCuesRespectGlobalVoiceCooldown() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertTrue(
            policy.shouldSpeak(
                functionName = "warn_com_offset",
                text = "Re-center.",
                priority = CoachVoice.MessagePriority.CRITICAL,
            ).shouldSpeak
        )

        now = 1_000L
        val tooSoon = policy.shouldSpeak(
            functionName = "correct_knee_alignment",
            text = "Knees over toes.",
            priority = CoachVoice.MessagePriority.CRITICAL,
        )
        assertFalse(tooSoon.shouldSpeak)
        assertEquals("global_voice_cooldown", tooSoon.reason)

        now = 2_100L
        assertTrue(
            policy.shouldSpeak(
                functionName = "correct_knee_alignment",
                text = "Knees over toes.",
                priority = CoachVoice.MessagePriority.CRITICAL,
            ).shouldSpeak
        )
    }

    @Test
    fun rollingVoiceBudgetPreventsOvercrowdedSpeech() {
        var now = 0L
        val policy = VoiceCuePolicy { now }
        val functions = listOf(
            "warn_com_offset",
            "correct_knee_alignment",
            "correct_spinal_alignment",
            "correct_joint_angle",
        )

        functions.forEachIndexed { index, functionName ->
            now = index * 6_000L
            assertTrue(
                policy.shouldSpeak(
                    functionName = functionName,
                    text = "Cue $index",
                    priority = CoachVoice.MessagePriority.CRITICAL,
                ).shouldSpeak
            )
        }

        now = 24_000L
        val fifth = policy.shouldSpeak(
            functionName = "warn_com_offset",
            text = "Different balance cue.",
            priority = CoachVoice.MessagePriority.CRITICAL,
        )
        assertFalse(fifth.shouldSpeak)
        assertEquals("voice_budget_exhausted", fifth.reason)

        now = 30_100L
        assertTrue(
            policy.shouldSpeak(
                functionName = "warn_com_offset",
                text = "Different balance cue.",
                priority = CoachVoice.MessagePriority.CRITICAL,
            ).shouldSpeak
        )
    }

    @Test
    fun positiveFeedbackIsSparse() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertTrue(
            policy.shouldSpeak(
                functionName = "positive_reinforcement",
                text = "Good control.",
                priority = CoachVoice.MessagePriority.LOW,
            ).shouldSpeak
        )

        now = 10_000L
        assertFalse(
            policy.shouldSpeak(
                functionName = "positive_reinforcement",
                text = "Good control.",
                priority = CoachVoice.MessagePriority.LOW,
            ).shouldSpeak
        )

        now = 25_100L
        assertTrue(
            policy.shouldSpeak(
                functionName = "positive_reinforcement",
                text = "Good control.",
                priority = CoachVoice.MessagePriority.LOW,
            ).shouldSpeak
        )
    }

    @Test
    fun previewBypassesPolicyCooldown() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertTrue(
            policy.shouldSpeak(
                functionName = CoachCueCatalog.PREVIEW_CUE_ID,
                text = "Voice preview.",
                priority = CoachVoice.MessagePriority.NORMAL,
            ).shouldSpeak
        )

        now = 1L
        assertTrue(
            policy.shouldSpeak(
                functionName = CoachCueCatalog.PREVIEW_CUE_ID,
                text = "Voice preview.",
                priority = CoachVoice.MessagePriority.NORMAL,
            ).shouldSpeak
        )
    }

    @Test
    fun dementiaFriendlyProfileStretchesTechniquePersistence() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertFalse(
            policy.shouldSpeak(
                functionName = "correct_asymmetry",
                text = "Even both sides.",
                priority = CoachVoice.MessagePriority.HIGH,
                profile = VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED,
            ).shouldSpeak
        )

        now = 800L
        assertFalse(
            policy.shouldSpeak(
                functionName = "correct_asymmetry",
                text = "Even both sides.",
                priority = CoachVoice.MessagePriority.HIGH,
                profile = VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED,
            ).shouldSpeak
        )

        now = 1_500L
        assertTrue(
            policy.shouldSpeak(
                functionName = "correct_asymmetry",
                text = "Even both sides.",
                priority = CoachVoice.MessagePriority.HIGH,
                profile = VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED,
            ).shouldSpeak
        )
    }

    @Test
    fun dementiaFriendlySupportCueCanRepeatAfterPolicyInterval() {
        var now = 0L
        val policy = VoiceCuePolicy { now }

        assertTrue(
            policy.shouldSpeak(
                functionName = "senior_repeat_simple_cue",
                text = "One slow rep.",
                priority = CoachVoice.MessagePriority.NORMAL,
                profile = VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED,
            ).shouldSpeak
        )

        now = 6_000L
        assertFalse(
            policy.shouldSpeak(
                functionName = "senior_repeat_simple_cue",
                text = "One slow rep.",
                priority = CoachVoice.MessagePriority.NORMAL,
                profile = VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED,
            ).shouldSpeak
        )

        now = 7_100L
        assertTrue(
            policy.shouldSpeak(
                functionName = "senior_repeat_simple_cue",
                text = "One slow rep.",
                priority = CoachVoice.MessagePriority.NORMAL,
                profile = VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED,
            ).shouldSpeak
        )
    }

    @Test
    fun dementiaFriendlyVoiceConfigUsesSlowProfile() {
        val config = CoachVoiceConfig(speed = 1.2f).dementiaFriendlySelfGuided()

        assertEquals(VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED, config.interactionProfile)
        assertEquals(0.85f, config.speed, 0.001f)
    }
}
