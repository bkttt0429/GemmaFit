package com.gemmafit.voice

import com.gemmafit.voice.CoachVoice.MessagePriority

internal enum class VoiceCueCategory {
    CRITICAL_SAFETY,
    TECHNIQUE_WARNING,
    VISIBILITY,
    TEMPO,
    POSITIVE,
    SUPPORT,
    PREVIEW,
    GENERAL,
}

enum class VoiceInteractionProfile {
    STANDARD,
    DEMENTIA_FRIENDLY_SELF_GUIDED,
}

internal data class VoiceCueDecision(
    val shouldSpeak: Boolean,
    val category: VoiceCueCategory,
    val reason: String = "",
)

internal class VoiceCuePolicy(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class SeenCue(
        var firstSeenMs: Long,
        var lastSeenMs: Long,
        var count: Int,
    )

    private val seenByKey = mutableMapOf<String, SeenCue>()
    private val lastSpokenByKey = mutableMapOf<String, Long>()
    private val lastSpokenByCategory = mutableMapOf<VoiceCueCategory, Long>()
    private val spokenWindow = ArrayDeque<Long>()
    private var lastSpokenGlobalMs: Long? = null

    fun reset() {
        seenByKey.clear()
        lastSpokenByKey.clear()
        lastSpokenByCategory.clear()
        spokenWindow.clear()
        lastSpokenGlobalMs = null
    }

    fun shouldSpeak(
        functionName: String,
        text: String,
        priority: MessagePriority,
        profile: VoiceInteractionProfile = VoiceInteractionProfile.STANDARD,
    ): VoiceCueDecision {
        val now = clockMs()
        val category = resolveCategory(functionName, priority)
        if (category == VoiceCueCategory.PREVIEW) {
            return allow(category, cueKey(functionName, text), now, countForBudget = false)
        }

        val key = cueKey(functionName, text)
        val seen = seenByKey.getOrPut(key) {
            SeenCue(firstSeenMs = now, lastSeenMs = now, count = 0)
        }
        if (now - seen.lastSeenMs > OBSERVATION_RESET_MS) {
            seen.firstSeenMs = now
            seen.count = 0
        }
        seen.lastSeenMs = now
        seen.count += 1

        val observedForMs = now - seen.firstSeenMs
        val persistenceMs = persistenceMsFor(category, profile)
        if (observedForMs < persistenceMs) {
            return suppress(category, "waiting_for_persistence")
        }

        lastSpokenByKey[key]?.let { lastSpoken ->
            val cooldownMs = keyCooldownMsFor(category, profile)
            if (now - lastSpoken < cooldownMs) {
                return suppress(category, "cue_cooldown")
            }
        }

        lastSpokenByCategory[category]?.let { lastSpoken ->
            val cooldownMs = categoryCooldownMsFor(category, profile)
            if (cooldownMs > 0 && now - lastSpoken < cooldownMs) {
                return suppress(category, "category_cooldown")
            }
        }

        lastSpokenGlobalMs?.let { lastSpoken ->
            val cooldownMs = globalCooldownMsFor(category, profile)
            if (cooldownMs > 0 && now - lastSpoken < cooldownMs) {
                return suppress(category, "global_voice_cooldown")
            }
        }

        pruneSpokenWindow(now, profile)
        if (spokenWindow.size >= maxCuesPerWindow(profile)) {
            return suppress(category, "voice_budget_exhausted")
        }

        return allow(category, key, now)
    }

    private fun allow(
        category: VoiceCueCategory,
        key: String,
        now: Long,
        countForBudget: Boolean = true,
    ): VoiceCueDecision {
        lastSpokenByKey[key] = now
        lastSpokenByCategory[category] = now
        if (countForBudget) {
            lastSpokenGlobalMs = now
            spokenWindow.addLast(now)
        }
        return VoiceCueDecision(shouldSpeak = true, category = category)
    }

    private fun suppress(category: VoiceCueCategory, reason: String): VoiceCueDecision {
        return VoiceCueDecision(shouldSpeak = false, category = category, reason = reason)
    }

    private fun cueKey(functionName: String, text: String): String {
        return functionName.ifBlank { text.trim().lowercase() }
    }

    private fun resolveCategory(functionName: String, priority: MessagePriority): VoiceCueCategory {
        return when (functionName) {
            CoachCueCatalog.PREVIEW_CUE_ID -> VoiceCueCategory.PREVIEW
            "positive_reinforcement" -> VoiceCueCategory.POSITIVE
            "senior_continue",
            "senior_repeat_simple_cue",
            "senior_setup_check",
            "senior_step_back_into_view",
            "senior_one_person_only",
            "senior_pause_for_support",
            "senior_session_summary",
            -> VoiceCueCategory.SUPPORT
            "no_person_detected",
            "warn_poor_visibility",
            "multi_person_selection" -> VoiceCueCategory.VISIBILITY
            "warn_rapid_movement" -> VoiceCueCategory.TEMPO
            "correct_knee_alignment",
            "correct_spinal_alignment",
            "correct_joint_angle",
            "warn_com_offset" -> VoiceCueCategory.CRITICAL_SAFETY
            "correct_asymmetry",
            "increase_range_of_motion" -> VoiceCueCategory.TECHNIQUE_WARNING
            else -> when (priority) {
                MessagePriority.CRITICAL -> VoiceCueCategory.CRITICAL_SAFETY
                MessagePriority.HIGH -> VoiceCueCategory.TECHNIQUE_WARNING
                MessagePriority.LOW -> VoiceCueCategory.POSITIVE
                MessagePriority.NORMAL -> VoiceCueCategory.GENERAL
            }
        }
    }

    private fun persistenceMsFor(
        category: VoiceCueCategory,
        profile: VoiceInteractionProfile,
    ): Long {
        val base = when (category) {
            VoiceCueCategory.CRITICAL_SAFETY -> 0L
            VoiceCueCategory.TECHNIQUE_WARNING -> 700L
            VoiceCueCategory.VISIBILITY -> 2_000L
            VoiceCueCategory.TEMPO -> 600L
            VoiceCueCategory.SUPPORT -> 0L
            VoiceCueCategory.POSITIVE,
            VoiceCueCategory.PREVIEW,
            VoiceCueCategory.GENERAL -> 0L
        }
        return scaleForProfile(base, profile)
    }

    private fun keyCooldownMsFor(
        category: VoiceCueCategory,
        profile: VoiceInteractionProfile,
    ): Long {
        val base = when (category) {
            VoiceCueCategory.CRITICAL_SAFETY -> 5_000L
            VoiceCueCategory.TECHNIQUE_WARNING -> 8_000L
            VoiceCueCategory.VISIBILITY -> 12_000L
            VoiceCueCategory.TEMPO -> 6_000L
            VoiceCueCategory.POSITIVE -> 25_000L
            VoiceCueCategory.SUPPORT -> 4_000L
            VoiceCueCategory.PREVIEW -> 0L
            VoiceCueCategory.GENERAL -> 5_000L
        }
        return if (category == VoiceCueCategory.SUPPORT &&
            profile == VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED
        ) {
            7_000L
        } else {
            scaleForProfile(base, profile)
        }
    }

    private fun categoryCooldownMsFor(
        category: VoiceCueCategory,
        profile: VoiceInteractionProfile,
    ): Long {
        val base = when (category) {
            VoiceCueCategory.CRITICAL_SAFETY -> 0L
            VoiceCueCategory.TECHNIQUE_WARNING -> 4_000L
            VoiceCueCategory.VISIBILITY -> 8_000L
            VoiceCueCategory.TEMPO -> 4_000L
            VoiceCueCategory.POSITIVE -> 20_000L
            VoiceCueCategory.SUPPORT -> 3_000L
            VoiceCueCategory.PREVIEW -> 0L
            VoiceCueCategory.GENERAL -> 3_000L
        }
        return if (category == VoiceCueCategory.SUPPORT &&
            profile == VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED
        ) {
            7_000L
        } else {
            scaleForProfile(base, profile)
        }
    }

    private fun globalCooldownMsFor(
        category: VoiceCueCategory,
        profile: VoiceInteractionProfile,
    ): Long {
        val base = when (category) {
            VoiceCueCategory.CRITICAL_SAFETY -> 2_000L
            VoiceCueCategory.TECHNIQUE_WARNING -> 3_500L
            VoiceCueCategory.TEMPO -> 3_500L
            VoiceCueCategory.VISIBILITY -> 5_000L
            VoiceCueCategory.SUPPORT -> 3_500L
            VoiceCueCategory.POSITIVE -> 10_000L
            VoiceCueCategory.GENERAL -> 5_000L
            VoiceCueCategory.PREVIEW -> 0L
        }
        return scaleForProfile(base, profile)
    }

    private fun pruneSpokenWindow(now: Long, profile: VoiceInteractionProfile) {
        val windowMs = rollingWindowMsFor(profile)
        while (spokenWindow.isNotEmpty() && now - spokenWindow.first() >= windowMs) {
            spokenWindow.removeFirst()
        }
    }

    private fun rollingWindowMsFor(profile: VoiceInteractionProfile): Long =
        if (profile == VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED) {
            45_000L
        } else {
            30_000L
        }

    private fun maxCuesPerWindow(profile: VoiceInteractionProfile): Int =
        if (profile == VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED) {
            3
        } else {
            4
        }

    private fun scaleForProfile(baseMs: Long, profile: VoiceInteractionProfile): Long {
        if (profile == VoiceInteractionProfile.STANDARD || baseMs == 0L) return baseMs
        return baseMs * 2L
    }

    private companion object {
        const val OBSERVATION_RESET_MS = 1_500L
    }
}
