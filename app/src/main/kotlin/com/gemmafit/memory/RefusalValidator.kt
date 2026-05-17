package com.gemmafit.memory

/**
 * Conservative boundary filter for stored, exported, or spoken content.
 *
 * This is not the only safety layer. It is a final app-side guard that catches
 * clinical or unsupported claims before memory writes or caregiver exports.
 */
object RefusalValidator {
    val DENY_SUBSTRINGS: List<String> = listOf(
        "medical diagnosis",
        "medical-grade",
        "clinical diagnosis",
        "clinical risk",
        "clinical improvement",
        "injury prediction",
        "injury risk",
        "fall risk",
        "fall-risk",
        "fall score",
        "sarcopenia",
        "muscle mass",
        "muscle activation",
        "activation percent",
        "emg",
        "joint force",
        "joint moment",
        "heart rate stable",
        "heart rate was stable",
        "heart-rate stable",
        "心率平穩",
        "心率稳定",
        "ground reaction force",
        "ligament load",
        "acl strain",
        "force plate",
        "lumbar load",
        "disc loading",
        "rehabilitation prescription",
        "rehab progress",
        "return-to-play",
        "treatment plan",
        "therapy plan",
        "physician",
        "dementia score",
        "dementia screening",
        "cognitive decline",
        "cognitive score",
        "memory decline",
        "wandering risk",
        "confusion detected",
        "diagnose",
        "diagnosis",
    )

    fun firstDenyMatch(payload: String): String? {
        if (payload.isEmpty()) return null
        val lower = stripSafeBoundaryPhrases(payload.lowercase())
        return DENY_SUBSTRINGS.firstOrNull { lower.contains(it) }
    }

    fun isClean(payload: String): Boolean = firstDenyMatch(payload) == null

    private fun stripSafeBoundaryPhrases(payload: String): String {
        return SAFE_BOUNDARY_PHRASES.fold(payload) { acc, phrase -> acc.replace(phrase, "") }
    }

    private val SAFE_BOUNDARY_PHRASES = listOf(
        "does not assess fall risk or sarcopenia",
        "does not assess fall risk, sarcopenia, rehabilitation progress, muscle mass, or clinical improvement",
        "does not assess fall risk, sarcopenia, rehabilitation progress, heart rate, force, or clinical status",
        "does not assess fall risk, sarcopenia, rehabilitation progress, or heart rate",
        "does not assess fall risk, sarcopenia, or rehabilitation progress",
        "does not assess fall risk",
        "does not predict fall risk",
        "not predict fall risk",
        "not assess fall risk",
        "does not assess sarcopenia",
        "not assess sarcopenia",
        "does not detect sarcopenia",
        "not detect sarcopenia",
        "does not prescribe rehabilitation",
        "not prescribe rehabilitation",
        "does not assess rehabilitation progress",
        "not assess rehabilitation progress",
        "does not estimate muscle mass",
        "not estimate muscle mass",
        "not a medical diagnosis",
        "not medical diagnosis",
        "not a medical assessment",
        "not medical assessment",
        "does not screen for dementia",
        "not a dementia screening",
        "does not assess cognitive decline",
        "not assess cognitive decline",
    )
}
