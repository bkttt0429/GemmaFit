package com.gemmafit.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test
    fun switchingToSeniorAppliesSeniorUiDefaults() {
        val settings = AppSettings(
            trainingMode = AppTrainingMode.GENERAL,
            voiceSpeed = 1.0f,
            fontScale = 1.0f,
            voiceFirst = false,
            cueStyle = AppCueStyle.TERSE,
        )

        val senior = settings.withTrainingMode(AppTrainingMode.SENIOR)

        assertEquals(AppTrainingMode.SENIOR, senior.trainingMode)
        assertEquals(0.85f, senior.voiceSpeed, 0.001f)
        assertEquals(2.0f, senior.fontScale, 0.001f)
        assertTrue(senior.voiceFirst)
        assertEquals(AppCueStyle.ENCOURAGING, senior.cueStyle)
    }

    @Test
    fun switchingFromSeniorToGeneralRestoresGeneralUiDefaults() {
        val settings = AppSettings(
            trainingMode = AppTrainingMode.SENIOR,
            voiceSpeed = 0.85f,
            fontScale = 2.0f,
            voiceFirst = true,
        )

        val general = settings.withTrainingMode(AppTrainingMode.GENERAL)

        assertEquals(AppTrainingMode.GENERAL, general.trainingMode)
        assertEquals(1.0f, general.voiceSpeed, 0.001f)
        assertEquals(1.0f, general.fontScale, 0.001f)
        assertFalse(general.voiceFirst)
    }

    @Test
    fun selectingGeneralPresetRestoresStaleSeniorUiValues() {
        val settings = AppSettings(
            trainingMode = AppTrainingMode.GENERAL,
            voiceSpeed = 0.85f,
            fontScale = 2.0f,
            voiceFirst = true,
        )

        val result = settings.withTrainingMode(AppTrainingMode.GENERAL)

        assertEquals(AppTrainingMode.GENERAL, result.trainingMode)
        assertEquals(1.0f, result.voiceSpeed, 0.001f)
        assertEquals(1.0f, result.fontScale, 0.001f)
        assertFalse(result.voiceFirst)
    }
}
