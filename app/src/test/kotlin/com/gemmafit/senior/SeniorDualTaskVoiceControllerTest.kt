package com.gemmafit.senior

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeniorDualTaskVoiceControllerTest {
    @Test
    fun accepted_voice_answer_records_voice_attempt() {
        val gateway = FakeSpeechRecognitionGateway()
        val controller = newController(gateway)
        val states = mutableListOf<SeniorDualTaskVoiceState>()

        controller.start(onState = states::add)
        gateway.sendResults(listOf(SpeechRecognitionCandidate("left", 0.86)))

        val accepted = states.last()
        assertFalse(accepted.isListening)
        assertTrue(accepted.answerMatched)
        assertEquals("A", accepted.recognizedSpeech)
        assertEquals(0.86, accepted.asrConfidence, 0.001)
        assertEquals(ResponseMode.VOICE, accepted.attempt?.responseMode)
        assertEquals("A", accepted.attempt?.recognizedSpeech)
        assertEquals(false, accepted.attempt?.movementCompleted)
        assertEquals(listOf(DUAL_TASK_VOICE_BOUNDED_EVIDENCE_REF), accepted.attempt?.evidenceRefs)
    }

    @Test
    fun missing_confidence_exact_answer_is_accepted_with_default_confidence() {
        val gateway = FakeSpeechRecognitionGateway()
        val controller = newController(gateway)
        val states = mutableListOf<SeniorDualTaskVoiceState>()

        controller.start(onState = states::add)
        gateway.sendResults(listOf(SpeechRecognitionCandidate("B", null)))

        val accepted = states.last()
        assertTrue(accepted.answerMatched)
        assertEquals("B", accepted.recognizedSpeech)
        assertEquals(0.65, accepted.asrConfidence, 0.001)
        assertEquals(listOf(DUAL_TASK_VOICE_BOUNDED_EVIDENCE_REF), accepted.attempt?.evidenceRefs)
    }

    @Test
    fun free_form_voice_answer_falls_back() {
        val gateway = FakeSpeechRecognitionGateway()
        val controller = newController(gateway)
        val states = mutableListOf<SeniorDualTaskVoiceState>()

        controller.start(onState = states::add)
        gateway.sendResults(listOf(SpeechRecognitionCandidate("predict fall risk", 0.92)))

        val fallback = states.last()
        assertFalse(fallback.answerMatched)
        assertEquals("answer_outside_bounded_set", fallback.fallbackReason)
        assertEquals("", fallback.recognizedSpeech)
        assertEquals(ResponseMode.VOICE, fallback.attempt?.responseMode)
        assertEquals("", fallback.attempt?.recognizedSpeech)
        assertEquals(false, fallback.attempt?.movementCompleted)
        assertEquals(listOf(DUAL_TASK_VOICE_FALLBACK_EVIDENCE_REF), fallback.attempt?.evidenceRefs)
    }

    @Test
    fun low_confidence_falls_back() {
        val gateway = FakeSpeechRecognitionGateway()
        val controller = newController(gateway)
        val states = mutableListOf<SeniorDualTaskVoiceState>()

        controller.start(onState = states::add)
        gateway.sendResults(listOf(SpeechRecognitionCandidate("A", 0.3)))

        assertEquals("asr_confidence_low", states.last().fallbackReason)
        assertEquals("", states.last().attempt?.recognizedSpeech)
        assertEquals(listOf(DUAL_TASK_VOICE_FALLBACK_EVIDENCE_REF), states.last().attempt?.evidenceRefs)
    }

    @Test
    fun unavailable_recognizer_falls_back_without_starting_gateway() {
        val gateway = FakeSpeechRecognitionGateway(available = false)
        val controller = newController(gateway)
        val states = mutableListOf<SeniorDualTaskVoiceState>()

        controller.start(onState = states::add)

        assertEquals("asr_service_unavailable", states.last().fallbackReason)
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun permission_denied_records_audio_permission_fallback() {
        val controller = newController(FakeSpeechRecognitionGateway())

        val state = controller.permissionDenied()

        assertEquals("audio_permission_denied", state.fallbackReason)
        assertEquals(ResponseMode.VOICE, state.attempt?.responseMode)
        assertEquals(listOf(DUAL_TASK_VOICE_FALLBACK_EVIDENCE_REF), state.attempt?.evidenceRefs)
    }

    @Test
    fun duplicate_start_does_not_request_gateway_twice() {
        val gateway = FakeSpeechRecognitionGateway()
        val controller = newController(gateway)
        val states = mutableListOf<SeniorDualTaskVoiceState>()

        controller.start(onState = states::add)
        controller.start(onState = states::add)

        assertEquals(1, gateway.startCount)
        assertTrue(states.last().isListening)
    }

    @Test
    fun android_asr_error_codes_map_to_fixed_fallback_reasons() {
        assertEquals("asr_audio_error", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_AUDIO))
        assertEquals("asr_service_unavailable", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_CLIENT))
        assertEquals("audio_permission_denied", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("asr_service_unavailable", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_NETWORK))
        assertEquals("asr_service_unavailable", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals("asr_service_unavailable", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_SERVER))
        assertEquals("asr_no_match", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals("asr_busy", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals("asr_timeout", SeniorDualTaskVoiceController.errorReason(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    private fun newController(gateway: FakeSpeechRecognitionGateway): SeniorDualTaskVoiceController {
        return SeniorDualTaskVoiceController(
            gateway = gateway,
            promptId = "prompt-1",
            allowedAnswers = listOf("A", "B"),
            expectedMovement = "left_arm_raise_or_right_arm_raise",
        )
    }

    private class FakeSpeechRecognitionGateway(
        private val available: Boolean = true,
    ) : SpeechRecognitionGateway {
        var startCount = 0
        private var callback: SpeechRecognitionCallback? = null

        override fun isAvailable(): Boolean = available

        override fun startListening(request: SpeechRecognitionRequest, callback: SpeechRecognitionCallback) {
            startCount += 1
            this.callback = callback
            callback.onReadyForSpeech()
        }

        override fun stopListening() {
            callback = null
        }

        override fun destroy() {
            callback = null
        }

        fun sendResults(candidates: List<SpeechRecognitionCandidate>) {
            assertNotNull(callback)
            callback?.onResults(candidates)
        }
    }
}
