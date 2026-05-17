package com.gemmafit.senior

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.Keep

const val DUAL_TASK_VOICE_BOUNDED_EVIDENCE_REF = "metric.dual_task.voice.bounded_answer"
const val DUAL_TASK_VOICE_FALLBACK_EVIDENCE_REF = "metric.dual_task.voice.fallback"

@Keep
data class SpeechRecognitionCandidate(
    val text: String,
    val confidence: Double? = null,
)

@Keep
data class SpeechRecognitionRequest(
    val languageTag: String = "",
    val maxResults: Int = 3,
)

interface SpeechRecognitionGateway {
    fun isAvailable(): Boolean
    fun startListening(request: SpeechRecognitionRequest, callback: SpeechRecognitionCallback)
    fun stopListening()
    fun destroy()
}

interface SpeechRecognitionCallback {
    fun onReadyForSpeech()
    fun onResults(candidates: List<SpeechRecognitionCandidate>)
    fun onError(errorCode: Int)
}

@Keep
data class SeniorDualTaskVoiceState(
    val isListening: Boolean = false,
    val recognizedSpeech: String = "",
    val asrConfidence: Double = 0.0,
    val answerMatched: Boolean = false,
    val fallbackReason: String = "",
    val voiceStatus: String = "gesture fallback ready",
    val attempt: DualTaskAttempt? = null,
)

class SeniorDualTaskVoiceController(
    private val gateway: SpeechRecognitionGateway,
    private val promptId: String,
    private val allowedAnswers: List<String>,
    private val expectedMovement: String = "",
    private val minConfidence: Double = 0.65,
) {
    private var listening = false

    fun start(languageTag: String = "", onState: (SeniorDualTaskVoiceState) -> Unit) {
        if (listening) {
            onState(SeniorDualTaskVoiceState(isListening = true, voiceStatus = "Listening..."))
            return
        }
        if (!gateway.isAvailable()) {
            onState(fallbackState("asr_service_unavailable"))
            return
        }
        listening = true
        onState(SeniorDualTaskVoiceState(isListening = true, voiceStatus = "Listening..."))
        gateway.startListening(
            SpeechRecognitionRequest(languageTag = languageTag),
            object : SpeechRecognitionCallback {
                override fun onReadyForSpeech() {
                    onState(SeniorDualTaskVoiceState(isListening = true, voiceStatus = "Listening..."))
                }

                override fun onResults(candidates: List<SpeechRecognitionCandidate>) {
                    listening = false
                    onState(resultState(candidates))
                }

                override fun onError(errorCode: Int) {
                    listening = false
                    onState(fallbackState(errorReason(errorCode)))
                }
            },
        )
    }

    fun permissionDenied(): SeniorDualTaskVoiceState {
        listening = false
        return fallbackState("audio_permission_denied")
    }

    fun stop() {
        listening = false
        gateway.stopListening()
    }

    fun destroy() {
        listening = false
        gateway.destroy()
    }

    private fun resultState(candidates: List<SpeechRecognitionCandidate>): SeniorDualTaskVoiceState {
        if (candidates.isEmpty()) {
            return fallbackState("asr_no_match")
        }
        val firstCandidate = candidates.first()
        candidates.forEach { candidate ->
            val parsed = SeniorVoiceAnswerParser.parse(
                transcript = candidate.text,
                allowedAnswers = allowedAnswers,
                confidence = candidate.confidence,
                minConfidence = minConfidence,
            )
            if (parsed.accepted) {
                val normalizedAnswer = parsed.normalizedAnswer.uppercase()
                return SeniorDualTaskVoiceState(
                    isListening = false,
                    recognizedSpeech = normalizedAnswer,
                    asrConfidence = parsed.confidence,
                    answerMatched = true,
                    voiceStatus = "Voice answer accepted: $normalizedAnswer",
                    attempt = DualTaskAttempt(
                        promptId = promptId,
                        responseMode = ResponseMode.VOICE,
                        detectedGesture = "none",
                        recognizedSpeech = normalizedAnswer,
                        answerMatched = true,
                        movementCompleted = false,
                        asrConfidence = parsed.confidence,
                        fallbackReason = "",
                        evidenceRefs = listOf(DUAL_TASK_VOICE_BOUNDED_EVIDENCE_REF),
                    ),
                )
            }
        }
        val fallbackReason = SeniorVoiceAnswerParser.parse(
            transcript = firstCandidate.text,
            allowedAnswers = allowedAnswers,
            confidence = firstCandidate.confidence,
            minConfidence = minConfidence,
        ).fallbackReason.ifBlank { "answer_outside_bounded_set" }
        return fallbackState(
            reason = fallbackReason,
            recognizedSpeech = sanitizedFallbackSpeech(fallbackReason, firstCandidate.text),
            asrConfidence = firstCandidate.confidence ?: 0.0,
        )
    }

    private fun fallbackState(
        reason: String,
        recognizedSpeech: String = "",
        asrConfidence: Double = 0.0,
    ): SeniorDualTaskVoiceState {
        return SeniorDualTaskVoiceState(
            isListening = false,
            recognizedSpeech = recognizedSpeech,
            asrConfidence = asrConfidence,
            answerMatched = false,
            fallbackReason = reason,
            voiceStatus = "Voice unavailable, use gesture",
            attempt = DualTaskAttempt(
                promptId = promptId,
                responseMode = ResponseMode.VOICE,
                detectedGesture = "none",
                recognizedSpeech = recognizedSpeech,
                answerMatched = false,
                movementCompleted = false,
                poseConfidence = 0.0,
                asrConfidence = asrConfidence,
                fallbackReason = reason,
                evidenceRefs = listOf(DUAL_TASK_VOICE_FALLBACK_EVIDENCE_REF),
            ),
        )
    }

    private fun sanitizedFallbackSpeech(reason: String, speech: String): String {
        if (speech.isBlank()) {
            return ""
        }
        return when (reason) {
            "answer_outside_bounded_set",
            "asr_confidence_low",
            "asr_confidence_missing" -> ""
            else -> speech
        }
    }

    companion object {
        fun errorReason(errorCode: Int): String {
            return when (errorCode) {
                SpeechRecognizer.ERROR_AUDIO -> "asr_audio_error"
                SpeechRecognizer.ERROR_CLIENT -> "asr_service_unavailable"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "audio_permission_denied"
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> "asr_service_unavailable"
                SpeechRecognizer.ERROR_NO_MATCH -> "asr_no_match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "asr_busy"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "asr_timeout"
                else -> "asr_service_unavailable"
            }
        }
    }
}

class AndroidSpeechRecognitionGateway(context: Context) : SpeechRecognitionGateway {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(appContext)
    }

    override fun startListening(request: SpeechRecognitionRequest, callback: SpeechRecognitionCallback) {
        if (!isAvailable()) {
            callback.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        stopListening()
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    callback.onReadyForSpeech()
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    callback.onError(error)
                }

                override fun onResults(results: Bundle?) {
                    val texts = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                    val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val candidates = texts.take(request.maxResults).mapIndexed { index, text ->
                        val confidence = scores?.getOrNull(index)?.toDouble()
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                        SpeechRecognitionCandidate(text = text, confidence = confidence)
                    }
                    callback.onResults(candidates)
                }
            })
            startListening(buildIntent(request))
        }
    }

    override fun stopListening() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun destroy() {
        stopListening()
    }

    private fun buildIntent(request: SpeechRecognitionRequest): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, request.maxResults)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            if (request.languageTag.isNotBlank() && request.languageTag != "system") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.languageTag)
            }
        }
    }
}
