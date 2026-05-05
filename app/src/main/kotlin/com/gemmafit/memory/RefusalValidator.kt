package com.gemmafit.memory

/**
 * Refusal regex generated from CLAUDE.md "❌ 不可以說" table.
 *
 * Used by both:
 *  - [MemoryWritePolicy] to block disallowed memory writes
 *  - [MemoryAwarePromptBuilder] to filter Gemma's free-form coaching cues
 *    before TTS hands them to the user
 *
 * Match policy: case-insensitive substring on the structured payload's
 * stringified form (after JSON encode). We intentionally over-match
 * rather than under-match — false positives turn into NEEDS_REVIEW,
 * not silent acceptance.
 */
object RefusalValidator {

    /**
     * Lowercase substrings that mark a payload as a clinical claim
     * GemmaFit must never store, export, or speak.
     */
    val DENY_SUBSTRINGS: List<String> = listOf(
        // English
        "medical diagnosis",
        "medical-grade",
        "clinical diagnosis",
        "clinical risk",
        "injury prediction",
        "injury risk",
        "fall risk",
        "fall-risk",
        "sarcopenia",
        "muscle mass",
        "muscle activation percent",
        "emg",
        "joint force",
        "force plate",
        "lumbar load",
        "disc loading",
        "rehabilitation prescription",
        "treatment plan",
        "physician",
        "diagnose",
        // Traditional Chinese
        "醫療診斷",
        "醫學診斷",
        "臨床診斷",
        "受傷風險",
        "跌倒風險",
        "肌少症",
        "肌肉量",
        "處方",
        "復健處方",
        "藥物",
        "治療方案",
    )

    /**
     * Return the first matched deny-substring, or null if the payload
     * is acceptable. Caller decides REJECT vs NEEDS_REVIEW based on
     * context (TREND_NOTE auto-rejects; PROFILE may go to NEEDS_REVIEW).
     */
    fun firstDenyMatch(payload: String): String? {
        if (payload.isEmpty()) return null
        val lower = payload.lowercase()
        return DENY_SUBSTRINGS.firstOrNull { lower.contains(it) }
    }

    /** Convenience for boolean call sites. */
    fun isClean(payload: String): Boolean = firstDenyMatch(payload) == null
}
