package com.gemmafit.settings

import android.content.Context

enum class AppLanguage(val storageValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    TRADITIONAL_CHINESE("zh-TW"),
}

enum class AppTrainingMode(val storageValue: String) {
    GENERAL("general"),
    SENIOR("senior"),
}

enum class AppCueStyle(val storageValue: String) {
    ENCOURAGING("encouraging"),
    TERSE("terse"),
    DETAILED("detailed"),
}

data class AppSettings(
    val language: AppLanguage = AppLanguage.SYSTEM,
    val voiceLanguage: AppLanguage = AppLanguage.SYSTEM,
    val voiceEnabled: Boolean = true,
    val voiceSpeed: Float = 1.0f,
    val trainingMode: AppTrainingMode = AppTrainingMode.GENERAL,
    val fontScale: Float = 1.0f,
    val highContrast: Boolean = true,
    val voiceFirst: Boolean = false,
    val reduceMotion: Boolean = false,
    val cueStyle: AppCueStyle = AppCueStyle.ENCOURAGING,
) {
    val assistedMode: Boolean
        get() = trainingMode == AppTrainingMode.SENIOR

    val isChinese: Boolean
        get() = language == AppLanguage.TRADITIONAL_CHINESE

    fun withTrainingMode(mode: AppTrainingMode): AppSettings {
        return when (mode) {
            AppTrainingMode.GENERAL -> copy(
                trainingMode = AppTrainingMode.GENERAL,
                voiceSpeed = 1.0f,
                fontScale = 1.0f,
                voiceFirst = false,
            )
            AppTrainingMode.SENIOR -> copy(
                trainingMode = AppTrainingMode.SENIOR,
                voiceSpeed = 0.85f,
                fontScale = 2.0f,
                voiceFirst = true,
                cueStyle = AppCueStyle.ENCOURAGING,
            )
        }
    }
}

class AppSettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "gemmafit_app_settings",
        Context.MODE_PRIVATE,
    )

    fun load(): AppSettings {
        return AppSettings(
            language = enumByStorage(
                prefs.getString(KEY_LANGUAGE, null),
                AppLanguage.SYSTEM,
                AppLanguage.entries,
            ),
            voiceLanguage = enumByStorage(
                prefs.getString(KEY_VOICE_LANGUAGE, null),
                AppLanguage.SYSTEM,
                AppLanguage.entries,
            ),
            voiceEnabled = prefs.getBoolean(KEY_VOICE_ENABLED, true),
            voiceSpeed = prefs.getFloat(KEY_VOICE_SPEED, 1.0f).coerceIn(0.7f, 1.3f),
            trainingMode = enumByStorage(
                prefs.getString(KEY_TRAINING_MODE, null),
                AppTrainingMode.GENERAL,
                AppTrainingMode.entries,
            ),
            fontScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f).coerceIn(1.0f, 2.0f),
            highContrast = prefs.getBoolean(KEY_HIGH_CONTRAST, true),
            voiceFirst = prefs.getBoolean(KEY_VOICE_FIRST, false),
            reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
            cueStyle = enumByStorage(
                prefs.getString(KEY_CUE_STYLE, null),
                AppCueStyle.ENCOURAGING,
                AppCueStyle.entries,
            ),
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_LANGUAGE, settings.language.storageValue)
            .putString(KEY_VOICE_LANGUAGE, settings.voiceLanguage.storageValue)
            .putBoolean(KEY_VOICE_ENABLED, settings.voiceEnabled)
            .putFloat(KEY_VOICE_SPEED, settings.voiceSpeed.coerceIn(0.7f, 1.3f))
            .putString(KEY_TRAINING_MODE, settings.trainingMode.storageValue)
            .putFloat(KEY_FONT_SCALE, settings.fontScale.coerceIn(1.0f, 2.0f))
            .putBoolean(KEY_HIGH_CONTRAST, settings.highContrast)
            .putBoolean(KEY_VOICE_FIRST, settings.voiceFirst)
            .putBoolean(KEY_REDUCE_MOTION, settings.reduceMotion)
            .putString(KEY_CUE_STYLE, settings.cueStyle.storageValue)
            .apply()
    }

    private fun <T> enumByStorage(
        value: String?,
        default: T,
        entries: List<T>,
    ): T where T : Enum<T> {
        return entries.firstOrNull { enumValue ->
            when (enumValue) {
                is AppLanguage -> enumValue.storageValue == value
                is AppTrainingMode -> enumValue.storageValue == value
                is AppCueStyle -> enumValue.storageValue == value
                else -> false
            }
        } ?: default
    }

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_VOICE_LANGUAGE = "voice_language"
        const val KEY_VOICE_ENABLED = "voice_enabled"
        const val KEY_VOICE_SPEED = "voice_speed"
        const val KEY_TRAINING_MODE = "training_mode"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_HIGH_CONTRAST = "high_contrast"
        const val KEY_VOICE_FIRST = "voice_first"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_CUE_STYLE = "cue_style"
    }
}
