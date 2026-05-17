package com.gemmafit.senior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeniorVoiceAnswerParserTest {
    @Test
    fun accepts_bounded_a_b_answer() {
        val parsed = SeniorVoiceAnswerParser.parse(
            transcript = "left",
            allowedAnswers = listOf("A", "B"),
            confidence = 0.8,
        )

        assertTrue(parsed.accepted)
        assertEquals("a", parsed.normalizedAnswer)
        assertEquals(0.8, parsed.confidence, 0.001)
        assertEquals("bounded_answer_match", parsed.selectionBasis)
    }

    @Test
    fun accepts_yes_no_and_numeric_answers() {
        val yes = SeniorVoiceAnswerParser.parse(
            transcript = "yeah",
            allowedAnswers = listOf("yes", "no"),
            confidence = 0.9,
        )
        val number = SeniorVoiceAnswerParser.parse(
            transcript = "three",
            allowedAnswers = listOf("1", "2", "3", "4"),
            confidence = 0.9,
        )

        assertTrue(yes.accepted)
        assertEquals("yes", yes.normalizedAnswer)
        assertTrue(number.accepted)
        assertEquals("3", number.normalizedAnswer)
    }

    @Test
    fun falls_back_on_low_confidence() {
        val parsed = SeniorVoiceAnswerParser.parse(
            transcript = "A",
            allowedAnswers = listOf("A", "B"),
            confidence = 0.4,
        )

        assertFalse(parsed.accepted)
        assertEquals("asr_confidence_low", parsed.fallbackReason)
    }

    @Test
    fun accepts_exact_bounded_match_without_confidence() {
        val parsed = SeniorVoiceAnswerParser.parse(
            transcript = "A",
            allowedAnswers = listOf("A", "B"),
            confidence = null,
        )

        assertTrue(parsed.accepted)
        assertEquals("a", parsed.normalizedAnswer)
        assertEquals(0.65, parsed.confidence, 0.001)
        assertEquals("exact_bounded_match_without_confidence", parsed.selectionBasis)
    }

    @Test
    fun missing_confidence_free_form_falls_back() {
        val parsed = SeniorVoiceAnswerParser.parse(
            transcript = "tell me if I have fall risk",
            allowedAnswers = listOf("yes", "no"),
            confidence = null,
        )

        assertFalse(parsed.accepted)
        assertEquals("answer_outside_bounded_set", parsed.fallbackReason)
    }

    @Test
    fun rejects_free_form_answer() {
        val parsed = SeniorVoiceAnswerParser.parse(
            transcript = "tell me if I have fall risk",
            allowedAnswers = listOf("yes", "no"),
            confidence = 0.9,
        )

        assertFalse(parsed.accepted)
        assertEquals("answer_outside_bounded_set", parsed.fallbackReason)
    }

    @Test
    fun rejects_sentence_that_contains_allowed_alias_as_noise() {
        val parsed = SeniorVoiceAnswerParser.parse(
            transcript = "my left knee hurts",
            allowedAnswers = listOf("A", "B"),
            confidence = 0.9,
        )

        assertFalse(parsed.accepted)
        assertEquals("answer_outside_bounded_set", parsed.fallbackReason)
    }
}
