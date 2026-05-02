package com.gemmafit.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CoachVoice: Text-to-Speech manager with cooldown and prioritization.
 *
 * Rules:
 *   - Minimum 3-second interval between utterances (cooldown)
 *   - Critical warnings bypass queue and interrupt current speech
 *   - Duplicate messages within 10 seconds are suppressed
 *   - Queue size limited to 5 messages to prevent backlog
 */
class CoachVoice(context: Context) {

    private var tts: TextToSpeech? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val messageQueue = ConcurrentLinkedQueue<CoachMessage>()
    private val isSpeaking = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)

    private var lastSpokenTime = 0L
    private var lastSpokenText = ""
    private var lastSpokenTimestamp = 0L

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    data class CoachMessage(
        val text: String,
        val priority: MessagePriority,
        val functionName: String = "",
    )

    enum class MessagePriority {
        CRITICAL,   // Immediate: safety violation
        HIGH,       // Urgent: form correction
        NORMAL,     // Standard: coaching tip
        LOW,        // Background: encouragement
    }

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    engine.language = Locale.US
                    engine.setSpeechRate(1.0f)
                    engine.setPitch(1.0f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            isSpeaking.set(false)
                            processNextMessage()
                        }
                        override fun onError(utteranceId: String?) {
                            isSpeaking.set(false)
                            processNextMessage()
                        }
                    })
                    isInitialized.set(true)
                    _isReady.value = true
                }
            } else {
                Log.e("CoachVoice", "TTS initialization failed: $status")
            }
        }
    }

    /**
     * Speak a coaching message. Respects cooldown and deduplication.
     */
    fun speak(text: String, priority: MessagePriority = MessagePriority.NORMAL, functionName: String = "") {
        if (!isInitialized.get()) {
            Log.w("CoachVoice", "TTS not initialized, dropping: $text")
            return
        }

        // Deduplication: suppress identical messages within 10 seconds
        val now = System.currentTimeMillis()
        if (text == lastSpokenText && now - lastSpokenTimestamp < 10_000) {
            return
        }

        val message = CoachMessage(text, priority, functionName)

        // Critical messages bypass queue and interrupt
        if (priority == MessagePriority.CRITICAL) {
            tts?.stop()
            isSpeaking.set(false)
            speakInternal(message)
            return
        }

        // Add to queue, respecting size limit
        if (messageQueue.size >= 5) {
            // Remove lowest priority message if queue is full
            val lowest = messageQueue.minByOrNull { it.priority.ordinal }
            if (lowest != null && lowest.priority.ordinal < priority.ordinal) {
                messageQueue.remove(lowest)
                messageQueue.add(message)
            }
        } else {
            messageQueue.add(message)
        }

        if (!isSpeaking.get()) {
            processNextMessage()
        }
    }

    /**
     * Speak based on a Function Calling result.
     */
    fun speakFunctionCall(functionName: String, argsJson: String = "") {
        val message = when (functionName) {
            "correct_knee_alignment" -> "Keep your knees tracking over your toes."
            "correct_spinal_alignment" -> "Maintain a neutral spine. Don't round your back."
            "correct_joint_angle" -> "Avoid locking your joints. Keep a slight bend."
            "correct_asymmetry" -> "Try to keep both sides even and balanced."
            "warn_com_offset" -> "Center your weight. Stay balanced over your feet."
            "warn_rapid_movement" -> "Slow down. Control the movement."
            "increase_range_of_motion" -> "Move through your full comfortable range."
            "positive_reinforcement" -> "Great form! Keep it up."
            "warn_poor_visibility" -> "Please adjust your position so your full body is visible."
            else -> "Adjust your form for better safety."
        }

        val priority = when (functionName) {
            "correct_knee_alignment", "correct_spinal_alignment",
            "correct_joint_angle", "warn_com_offset" -> MessagePriority.CRITICAL
            "correct_asymmetry", "warn_rapid_movement" -> MessagePriority.HIGH
            "positive_reinforcement" -> MessagePriority.LOW
            else -> MessagePriority.NORMAL
        }

        speak(message, priority, functionName)
    }

    private fun processNextMessage() {
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastSpokenTime

        // Cooldown enforcement: 3 seconds minimum between non-critical messages
        val cooldownMs = if (messageQueue.peek()?.priority == MessagePriority.CRITICAL) 0 else 3000
        val waitTime = (cooldownMs - timeSinceLast).coerceAtLeast(0)

        scope.launch {
            if (waitTime > 0) {
                delay(waitTime)
            }
            val message = messageQueue.poll() ?: return@launch
            speakInternal(message)
        }
    }

    private fun speakInternal(message: CoachMessage) {
        if (!isInitialized.get()) return

        isSpeaking.set(true)
        lastSpokenTime = System.currentTimeMillis()
        lastSpokenText = message.text
        lastSpokenTimestamp = lastSpokenTime

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UUID.randomUUID().toString())

        tts?.speak(message.text, TextToSpeech.QUEUE_FLUSH, params, message.functionName)
    }

    /**
     * Stop all speech and clear the queue.
     */
    fun stop() {
        messageQueue.clear()
        tts?.stop()
        isSpeaking.set(false)
    }

    /**
     * Release TTS resources.
     */
    fun shutdown() {
        stop()
        scope.cancel()
        tts?.shutdown()
        tts = null
        isInitialized.set(false)
        _isReady.value = false
    }
}