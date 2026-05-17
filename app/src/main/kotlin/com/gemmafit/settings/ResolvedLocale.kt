package com.gemmafit.settings

import java.util.Locale

/**
 * Concrete locale used by downstream non-UI components (E2B prompt, refusal
 * validator, deterministic care-log fallback wording) where `AppLanguage.SYSTEM`
 * must be resolved to a real language code.
 *
 * UI string layer already does this resolution inside `AppStrings.forLanguage`.
 * `ResolvedLocale` is the same resolution but shared with non-UI consumers so
 * the model output, voice, cue catalog, and validator all agree on what
 * "the user's language" actually is at any given moment.
 *
 * P0 scope is en-US + zh-TW. Adding a new locale requires:
 *   1. New enum entry here
 *   2. New `AppStrings.<locale>` in [com.gemmafit.ui.localization.AppStrings]
 *   3. New cue catalog entry in `LiveCuePlanner.cueVariants(locale)`
 *   4. New 1-shot example in `LiteRtEvidencePromptRenderer`
 *   5. New banned-term list in `RefusalValidator`
 *   6. A TTS availability check (Android may not ship the voice)
 */
enum class ResolvedLocale(val tag: String, val javaLocale: Locale) {
    EN_US("en-US", Locale.US),
    ZH_TW("zh-TW", Locale.TRADITIONAL_CHINESE);

    companion object {
        /**
         * Resolves the user's chosen [AppLanguage] (which may be `SYSTEM`) to a
         * concrete locale. Falls back to en-US when the device locale is
         * neither English nor Chinese — by design, since the model + cue
         * catalog only ship the two locales for P0.
         */
        fun resolve(language: AppLanguage): ResolvedLocale = when (language) {
            AppLanguage.ENGLISH -> EN_US
            AppLanguage.TRADITIONAL_CHINESE -> ZH_TW
            AppLanguage.SYSTEM ->
                if (Locale.getDefault().language.startsWith("zh")) ZH_TW else EN_US
        }

        /** Parses the wire tag (e.g. `"zh-TW"`) back to a locale; en-US fallback. */
        fun fromTag(tag: String?): ResolvedLocale {
            if (tag.isNullOrBlank()) return EN_US
            return entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) } ?: EN_US
        }
    }
}
