package com.gemmafit.voice

import com.gemmafit.settings.AppCueStyle
import com.gemmafit.settings.AppLanguage
import com.gemmafit.settings.AppSettings
import com.gemmafit.settings.AppTrainingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachCueCatalogTest {

    @Test
    fun appSettingsConvertToVoiceConfigAndClampSpeed() {
        val high = AppSettings(
            voiceEnabled = false,
            voiceLanguage = AppLanguage.ENGLISH,
            voiceSpeed = 9.0f,
            cueStyle = AppCueStyle.DETAILED,
        ).toCoachVoiceConfig()

        assertFalse(high.enabled)
        assertEquals(AppLanguage.ENGLISH, high.language)
        assertEquals(1.3f, high.speed, 0.001f)
        assertEquals(AppCueStyle.DETAILED, high.cueStyle)

        val low = AppSettings(voiceSpeed = 0.1f).toCoachVoiceConfig()
        assertEquals(0.7f, low.speed, 0.001f)
    }

    @Test
    fun systemVoiceLanguageFollowsAppLanguageForConfig() {
        val config = AppSettings(
            language = AppLanguage.TRADITIONAL_CHINESE,
            voiceLanguage = AppLanguage.SYSTEM,
        ).toCoachVoiceConfig()

        assertEquals(AppLanguage.TRADITIONAL_CHINESE, config.language)
    }

    @Test
    fun languageSelectionResolvesDeterministicCueText() {
        val english = CoachCueCatalog.resolve(
            cueId = "warn_poor_visibility",
            config = CoachVoiceConfig(language = AppLanguage.ENGLISH),
        )
        val chinese = CoachCueCatalog.resolve(
            cueId = "warn_poor_visibility",
            config = CoachVoiceConfig(language = AppLanguage.TRADITIONAL_CHINESE),
        )
        val system = CoachCueCatalog.resolve(
            cueId = "warn_poor_visibility",
            config = CoachVoiceConfig(language = AppLanguage.SYSTEM),
        )

        assertEquals("Step into a clearer view so I can coach you.", english)
        assertEquals("請站到更清楚的位置，讓我可以協助你。", chinese)
        assertEquals(english, system)
    }

    @Test
    fun chineseVoiceCuesDoNotUseMojibakeFallbackText() {
        val cueIds = listOf(
            "correct_knee_alignment",
            "correct_spinal_alignment",
            "correct_joint_angle",
            "correct_asymmetry",
            "warn_com_offset",
            "warn_rapid_movement",
            "increase_range_of_motion",
            "positive_reinforcement",
            "warn_poor_visibility",
            "no_person_detected",
            "multi_person_selection",
            CoachCueCatalog.PREVIEW_CUE_ID,
            "senior_continue",
            "senior_repeat_simple_cue",
            "senior_setup_check",
            "senior_step_back_into_view",
            "senior_one_person_only",
            "senior_pause_for_support",
            "senior_session_summary",
            "unknown",
        )
        val config = CoachVoiceConfig(language = AppLanguage.TRADITIONAL_CHINESE)

        cueIds.forEach { cueId ->
            val text = CoachCueCatalog.resolve(cueId, config)
            assertFalse("Mojibake in $cueId: $text", text.contains("?"))
            assertFalse("English fallback in $cueId: $text", Regex("[A-Za-z]{4,}").containsMatchIn(text))
        }
    }

    @Test
    fun cueStylesProduceDifferentCoachingText() {
        val terse = CoachCueCatalog.resolve(
            cueId = "correct_spinal_alignment",
            config = CoachVoiceConfig(language = AppLanguage.ENGLISH, cueStyle = AppCueStyle.TERSE),
        )
        val encouraging = CoachCueCatalog.resolve(
            cueId = "correct_spinal_alignment",
            config = CoachVoiceConfig(language = AppLanguage.ENGLISH, cueStyle = AppCueStyle.ENCOURAGING),
        )
        val detailed = CoachCueCatalog.resolve(
            cueId = "correct_spinal_alignment",
            config = CoachVoiceConfig(language = AppLanguage.ENGLISH, cueStyle = AppCueStyle.DETAILED),
        )

        assertEquals("Neutral spine.", terse)
        assertTrue(encouraging.length > terse.length)
        assertTrue(detailed.length > encouraging.length)
        assertTrue(detailed.contains("before continuing"))
    }

    @Test
    fun seniorVoiceFirstUsesDementiaFriendlyProfile() {
        val config = AppSettings(
            trainingMode = AppTrainingMode.SENIOR,
            voiceFirst = true,
            voiceSpeed = 1.2f,
            cueStyle = AppCueStyle.DETAILED,
        ).toCoachVoiceConfig()

        assertEquals(VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED, config.interactionProfile)
        assertEquals(AppCueStyle.TERSE, config.cueStyle)
        assertEquals(0.85f, config.speed, 0.001f)
    }

    @Test
    fun priorityMappingKeepsSafetyCuesAbovePositiveFeedback() {
        assertEquals(
            CoachVoice.MessagePriority.CRITICAL,
            CoachCueCatalog.priorityFor("correct_spinal_alignment"),
        )
        assertEquals(
            CoachVoice.MessagePriority.HIGH,
            CoachCueCatalog.priorityFor("warn_rapid_movement"),
        )
        assertEquals(
            CoachVoice.MessagePriority.LOW,
            CoachCueCatalog.priorityFor("positive_reinforcement"),
        )
    }
}
