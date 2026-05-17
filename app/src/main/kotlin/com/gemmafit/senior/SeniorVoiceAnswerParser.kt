package com.gemmafit.senior

data class SeniorVoiceAnswer(
    val accepted: Boolean,
    val normalizedAnswer: String = "",
    val fallbackReason: String = "",
    val confidence: Double = 0.0,
    val selectionBasis: String = "",
)

/**
 * Bounded ASR answer parser. It deliberately accepts only closed answer sets
 * so voice input cannot become a free-form medical or coaching channel.
 */
object SeniorVoiceAnswerParser {
    fun parse(
        transcript: String,
        allowedAnswers: List<String>,
        confidence: Double?,
        minConfidence: Double = 0.65,
    ): SeniorVoiceAnswer {
        val normalizedAllowed = allowedAnswers.map { normalize(it) }.filter { it.isNotBlank() }
        if (normalizedAllowed.isEmpty()) {
            return SeniorVoiceAnswer(accepted = false, fallbackReason = "empty_answer_set")
        }
        val normalizedTranscript = normalize(transcript)
        val match = normalizedAllowed.firstOrNull { it == normalizedTranscript }
            ?: aliasToAnswer(normalizedTranscript, normalizedAllowed)
        if (match == null) {
            return SeniorVoiceAnswer(accepted = false, fallbackReason = "answer_outside_bounded_set")
        }
        if (confidence == null) {
            return SeniorVoiceAnswer(
                accepted = true,
                normalizedAnswer = match,
                confidence = minConfidence,
                selectionBasis = "exact_bounded_match_without_confidence",
            )
        }
        if (confidence < minConfidence) {
            return SeniorVoiceAnswer(accepted = false, fallbackReason = "asr_confidence_low")
        }
        return SeniorVoiceAnswer(
            accepted = true,
            normalizedAnswer = match,
            confidence = confidence,
            selectionBasis = "bounded_answer_match",
        )
    }

    private fun aliasToAnswer(text: String, allowed: List<String>): String? {
        val aliases = mapOf(
            "ay" to "a",
            "optiona" to "a",
            "left" to "a",
            "bee" to "b",
            "optionb" to "b",
            "right" to "b",
            "yes" to "yes",
            "yeah" to "yes",
            "no" to "no",
            "one" to "1",
            "two" to "2",
            "three" to "3",
            "four" to "4",
        )
        return aliases[text]?.takeIf { it in allowed }
    }

    private fun normalize(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }
}
