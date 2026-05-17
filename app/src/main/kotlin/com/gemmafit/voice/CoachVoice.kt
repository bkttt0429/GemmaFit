package com.gemmafit.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.gemmafit.settings.AppCueStyle
import com.gemmafit.settings.AppLanguage
import com.gemmafit.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class UtteranceSequenceGate {
    private val sequence = AtomicLong(0)
    private val activeUtteranceId = AtomicReference<String?>()

    fun nextId(functionName: String): String {
        val prefix = functionName.ifBlank { "voice" }
        val id = "$prefix-${sequence.incrementAndGet()}"
        activeUtteranceId.set(id)
        return id
    }

    fun interrupt() {
        activeUtteranceId.set(null)
    }

    fun complete(utteranceId: String?): Boolean {
        return utteranceId != null && activeUtteranceId.compareAndSet(utteranceId, null)
    }
}

/**
 * CoachVoice: settings-driven Text-to-Speech manager with fixed coaching cues.
 *
 * Rules:
 *   - Minimum 3-second interval between utterances (cooldown)
 *   - Critical warnings bypass queue and interrupt current speech
 *   - Duplicate messages within 10 seconds are suppressed
 *   - Queue size limited to 3 messages to prevent backlog
 */
class CoachVoice(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (
            focusChange == AudioManager.AUDIOFOCUS_LOSS ||
            focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        ) {
            stop()
        }
    }
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .build()
        )
        .setOnAudioFocusChangeListener(audioFocusChangeListener)
        .build()
    private val tts = AtomicReference<TextToSpeech?>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val messageQueue = ConcurrentLinkedQueue<CoachMessage>()
    private val isSpeaking = AtomicBoolean(false)
    private val queueWorkerActive = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    private val utteranceGate = UtteranceSequenceGate()
    private val cuePolicy = VoiceCuePolicy()

    private var lastSpokenTime = 0L
    private var lastSpokenText = ""
    private var lastSpokenTimestamp = 0L

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private var config = CoachVoiceConfig()
    private var activeCueLanguage = config.language

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
        val engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.get()?.let { readyEngine ->
                    readyEngine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            if (!utteranceGate.complete(utteranceId)) return
                            isSpeaking.set(false)
                            abandonSpeechAudioFocus()
                            processNextMessage()
                        }
                        override fun onError(utteranceId: String?) {
                            if (!utteranceGate.complete(utteranceId)) return
                            isSpeaking.set(false)
                            abandonSpeechAudioFocus()
                            processNextMessage()
                        }
                    })
                    isInitialized.set(true)
                    applyConfigToEngine()
                    _isReady.value = true
                }
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
        tts.set(engine)
    }

    /**
     * Apply app/user settings to the TTS engine. Disabling voice immediately clears speech.
     */
    fun configure(settings: AppSettings) {
        configure(settings.toCoachVoiceConfig())
    }

    fun configure(config: CoachVoiceConfig) {
        this.config = config.normalized()
        activeCueLanguage = this.config.language
        if (!this.config.enabled) {
            stop()
            return
        }
        if (isInitialized.get()) {
            applyConfigToEngine()
        }
    }

    fun preview(settings: AppSettings) {
        preview(settings.toCoachVoiceConfig())
    }

    fun preview(config: CoachVoiceConfig = this.config) {
        configure(config)
        speakCue(CoachCueCatalog.PREVIEW_CUE_ID, MessagePriority.NORMAL)
    }

    fun speakCue(cueId: String, priority: MessagePriority = CoachCueCatalog.priorityFor(cueId)) {
        speak(
            text = CoachCueCatalog.resolve(cueId, config.copy(language = activeCueLanguage)),
            priority = priority,
            functionName = cueId,
        )
    }

    /**
     * Speak a coaching message. Respects cooldown and deduplication.
     */
    fun speak(text: String, priority: MessagePriority = MessagePriority.NORMAL, functionName: String = "") {
        if (!config.enabled) {
            return
        }
        if (!isInitialized.get()) {
            Log.w(TAG, "TTS not initialized, dropping cue: ${functionName.ifBlank { "raw_text" }}")
            return
        }

        val now = System.currentTimeMillis()
        val policyDecision = cuePolicy.shouldSpeak(
            functionName = functionName,
            text = text,
            priority = priority,
            profile = config.interactionProfile,
        )
        if (!policyDecision.shouldSpeak) {
            return
        }

        // Deduplication: suppress identical messages within 10 seconds
        if (priority != MessagePriority.CRITICAL && text == lastSpokenText && now - lastSpokenTimestamp < 10_000) {
            return
        }

        val message = CoachMessage(text, priority, functionName)

        // Critical messages bypass queue and interrupt
        if (priority == MessagePriority.CRITICAL) {
            messageQueue.clear()
            utteranceGate.interrupt()
            tts.get()?.stop()
            isSpeaking.set(false)
            speakInternal(message)
            return
        }

        // Add to queue, respecting size limit
        if (messageQueue.size >= MAX_QUEUE_SIZE) {
            // Remove lowest priority message if queue is full
            val lowest = messageQueue.maxByOrNull { it.priority.ordinal }
            if (lowest != null && priority.ordinal < lowest.priority.ordinal) {
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
        speakCue(functionName, CoachCueCatalog.priorityFor(functionName))
    }

    private fun processNextMessage() {
        if (!queueWorkerActive.compareAndSet(false, true)) {
            return
        }
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastSpokenTime

        // Cooldown enforcement: 3 seconds minimum between non-critical messages
        val cooldownMs = if (messageQueue.peek()?.priority == MessagePriority.CRITICAL) 0 else 3000
        val waitTime = (cooldownMs - timeSinceLast).coerceAtLeast(0)

        scope.launch {
            try {
                if (waitTime > 0) {
                    delay(waitTime)
                }
                val message = messageQueue.poll() ?: return@launch
                speakInternal(message)
            } finally {
                queueWorkerActive.set(false)
                if (!isSpeaking.get() && messageQueue.isNotEmpty()) {
                    processNextMessage()
                }
            }
        }
    }

    private fun speakInternal(message: CoachMessage) {
        if (!config.enabled || !isInitialized.get()) return

        val engine = tts.get() ?: return
        if (!requestSpeechAudioFocus()) {
            Log.w(TAG, "TTS audio focus not granted for ${message.functionName.ifBlank { "raw_text" }}")
            return
        }
        isSpeaking.set(true)
        lastSpokenTime = System.currentTimeMillis()
        lastSpokenText = message.text
        lastSpokenTimestamp = lastSpokenTime

        val utteranceId = utteranceGate.nextId(message.functionName)
        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)

        val result = engine.speak(message.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            utteranceGate.complete(utteranceId)
            isSpeaking.set(false)
            abandonSpeechAudioFocus()
            Log.w(TAG, "TTS speak failed for ${message.functionName.ifBlank { "raw_text" }}")
        }
    }

    private fun requestSpeechAudioFocus(): Boolean {
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonSpeechAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }

    private fun applyConfigToEngine() {
        val engine = tts.get() ?: return
        val targetLocale = config.targetLocale()
        val languageResult = engine.setLanguage(targetLocale)
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w(TAG, "TTS language unsupported: $targetLocale; falling back to en-US")
            engine.setLanguage(Locale.US)
            activeCueLanguage = AppLanguage.ENGLISH
        } else {
            activeCueLanguage = config.language
        }
        engine.setSpeechRate(config.speed)
        engine.setPitch(1.0f)
    }

    private fun CoachVoiceConfig.targetLocale(): Locale {
        return when (language) {
            AppLanguage.ENGLISH -> Locale.US
            AppLanguage.TRADITIONAL_CHINESE -> Locale.TAIWAN
            AppLanguage.SYSTEM -> Locale.getDefault()
        }
    }

    /**
     * Stop all speech and clear the queue.
     */
    fun stop() {
        messageQueue.clear()
        cuePolicy.reset()
        utteranceGate.interrupt()
        tts.get()?.stop()
        isSpeaking.set(false)
        abandonSpeechAudioFocus()
    }

    /**
     * Release TTS resources.
     */
    fun shutdown() {
        stop()
        scope.cancel()
        tts.getAndSet(null)?.shutdown()
        isInitialized.set(false)
        _isReady.value = false
    }

    private companion object {
        const val TAG = "CoachVoice"
        const val MAX_QUEUE_SIZE = 3
    }
}

data class CoachVoiceConfig(
    val enabled: Boolean = true,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val speed: Float = 1.0f,
    val cueStyle: AppCueStyle = AppCueStyle.ENCOURAGING,
    val interactionProfile: VoiceInteractionProfile = VoiceInteractionProfile.STANDARD,
) {
    fun normalized(): CoachVoiceConfig {
        val normalizedSpeed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        return if (interactionProfile == VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED) {
            copy(speed = normalizedSpeed.coerceAtMost(DEMENTIA_FRIENDLY_MAX_SPEED))
        } else {
            copy(speed = normalizedSpeed)
        }
    }

    fun dementiaFriendlySelfGuided(): CoachVoiceConfig {
        return copy(
            speed = speed.coerceAtMost(DEMENTIA_FRIENDLY_MAX_SPEED),
            cueStyle = AppCueStyle.TERSE,
            interactionProfile = VoiceInteractionProfile.DEMENTIA_FRIENDLY_SELF_GUIDED,
        ).normalized()
    }

    companion object {
        const val MIN_SPEED = 0.7f
        const val MAX_SPEED = 1.3f
        const val DEMENTIA_FRIENDLY_MAX_SPEED = 0.85f
    }
}

fun AppSettings.toCoachVoiceConfig(): CoachVoiceConfig {
    val resolvedLanguage = if (voiceLanguage == AppLanguage.SYSTEM) {
        language
    } else {
        voiceLanguage
    }
    val baseConfig = CoachVoiceConfig(
        enabled = voiceEnabled,
        language = resolvedLanguage,
        speed = voiceSpeed.coerceIn(CoachVoiceConfig.MIN_SPEED, CoachVoiceConfig.MAX_SPEED),
        cueStyle = cueStyle,
    )
    return if (assistedMode && voiceFirst) {
        baseConfig.dementiaFriendlySelfGuided()
    } else {
        baseConfig
    }
}

object CoachCueCatalog {
    const val PREVIEW_CUE_ID = "preview_voice"

    fun resolve(cueId: String, config: CoachVoiceConfig): String {
        val localizedCue = cues[cueId] ?: cues.getValue("unknown")
        val language = if (config.language == AppLanguage.TRADITIONAL_CHINESE) {
            CueLanguage.ZH_TW
        } else {
            CueLanguage.EN
        }
        val style = config.cueStyle
        return localizedCue.text(language, style)
    }

    fun priorityFor(cueId: String): CoachVoice.MessagePriority {
        return when (cueId) {
            "correct_knee_alignment",
            "correct_spinal_alignment",
            "correct_joint_angle",
            "warn_com_offset" -> CoachVoice.MessagePriority.CRITICAL
            "correct_asymmetry",
            "warn_rapid_movement",
            "no_person_detected" -> CoachVoice.MessagePriority.HIGH
            "positive_reinforcement" -> CoachVoice.MessagePriority.LOW
            else -> CoachVoice.MessagePriority.NORMAL
        }
    }

    private enum class CueLanguage {
        EN,
        ZH_TW,
    }

    private data class LocalizedCue(
        val en: StyledCue,
        val zhTw: StyledCue,
    ) {
        fun text(language: CueLanguage, style: AppCueStyle): String {
            val cue = when (language) {
                CueLanguage.EN -> en
                CueLanguage.ZH_TW -> zhTw
            }
            return cue.text(style)
        }
    }

    private data class StyledCue(
        val encouraging: String,
        val terse: String,
        val detailed: String,
    ) {
        fun text(style: AppCueStyle): String {
            return when (style) {
                AppCueStyle.ENCOURAGING -> encouraging
                AppCueStyle.TERSE -> terse
                AppCueStyle.DETAILED -> detailed
            }
        }
    }

    private val cues = mapOf(
        "correct_knee_alignment" to cue(
            enTerse = "Knees over toes.",
            enEncouraging = "Keep your knees tracking over your toes.",
            enDetailed = "Before the next rep, line your knees up with your toes and move with control.",
            zhTerse = "膝蓋對齊腳尖。",
            zhEncouraging = "膝蓋跟著腳尖方向走，保持穩定。",
            zhDetailed = "下一次動作前，讓膝蓋對齊腳尖，再用可控制的速度完成。",
        ),
        "correct_spinal_alignment" to cue(
            enTerse = "Neutral spine.",
            enEncouraging = "Keep your spine long and steady.",
            enDetailed = "Reset your trunk position and keep a neutral spine before continuing.",
            zhTerse = "脊椎保持中立。",
            zhEncouraging = "背部拉長，保持穩定。",
            zhDetailed = "繼續前先調整軀幹位置，讓脊椎維持自然中立。",
        ),
        "correct_joint_angle" to cue(
            enTerse = "Soft joints.",
            enEncouraging = "Keep a small bend instead of locking out.",
            enDetailed = "Avoid locking the joint; keep a small comfortable bend through the movement.",
            zhTerse = "關節不要鎖死。",
            zhEncouraging = "保留一點彎曲，不要把關節打直鎖住。",
            zhDetailed = "避免把關節鎖死，整個動作都保持舒服、可控制的小彎曲。",
        ),
        "correct_asymmetry" to cue(
            enTerse = "Even both sides.",
            enEncouraging = "Try to keep both sides balanced.",
            enDetailed = "Slow the next rep and keep the left and right side moving evenly.",
            zhTerse = "左右兩邊平均。",
            zhEncouraging = "試著讓左右兩邊保持平衡。",
            zhDetailed = "下一次動作放慢一點，讓左右兩側用接近的速度移動。",
        ),
        "warn_com_offset" to cue(
            enTerse = "Re-center.",
            enEncouraging = "Center your weight and stay balanced.",
            enDetailed = "Shift your weight back toward center before the next rep.",
            zhTerse = "重心回到中間。",
            zhEncouraging = "把重心帶回中間，保持平衡。",
            zhDetailed = "下一次動作前，先把重心移回中間，再穩穩開始。",
        ),
        "warn_rapid_movement" to cue(
            enTerse = "Slow down.",
            enEncouraging = "Slow down and control the movement.",
            enDetailed = "Use a slower tempo so each rep stays controlled from start to finish.",
            zhTerse = "慢一點。",
            zhEncouraging = "放慢速度，讓動作更可控制。",
            zhDetailed = "用較慢的節奏完成，讓每一下從開始到結束都能穩定控制。",
        ),
        "increase_range_of_motion" to cue(
            enTerse = "Comfortable range.",
            enEncouraging = "Move through your comfortable range.",
            enDetailed = "Use the fullest comfortable range you can control without forcing it.",
            zhTerse = "用舒服的活動範圍。",
            zhEncouraging = "在舒服、可控制的範圍內移動。",
            zhDetailed = "使用你能控制的最大舒服範圍，不要硬撐或勉強。",
        ),
        "positive_reinforcement" to cue(
            enTerse = "Good control.",
            enEncouraging = "Good control. Keep it steady.",
            enDetailed = "Your movement looks controlled; keep the same steady tempo.",
            zhTerse = "控制得很好。",
            zhEncouraging = "控制得很好，保持穩定。",
            zhDetailed = "目前動作看起來可控制，繼續維持同樣穩定的節奏。",
        ),
        "warn_poor_visibility" to cue(
            enTerse = "Improve camera view.",
            enEncouraging = "Step into a clearer view so I can coach you.",
            enDetailed = "Adjust your position or camera view so your body landmarks are visible.",
            zhTerse = "讓鏡頭更清楚。",
            zhEncouraging = "請站到更清楚的位置，讓我可以協助你。",
            zhDetailed = "請調整站位或鏡頭角度，讓身體關鍵點能清楚出現在畫面中。",
        ),
        "no_person_detected" to cue(
            enTerse = "Step into frame.",
            enEncouraging = "Step back into frame so I can see you.",
            enDetailed = "I do not have a usable body view right now; step into frame before continuing.",
            zhTerse = "請回到畫面中。",
            zhEncouraging = "請回到畫面中，讓我看得到你。",
            zhDetailed = "目前沒有可用的身體畫面，請先回到鏡頭範圍內再繼續。",
        ),
        "multi_person_selection" to cue(
            enTerse = "Tap yourself.",
            enEncouraging = "Tap yourself to start tracking.",
            enDetailed = "I can see more than one person; tap your body on screen to start tracking.",
            zhTerse = "請點一下自己。",
            zhEncouraging = "請在畫面上點一下自己，開始追蹤。",
            zhDetailed = "我看到不只一個人，請點選畫面中的自己，讓系統開始追蹤正確目標。",
        ),
        PREVIEW_CUE_ID to cue(
            enTerse = "Voice preview.",
            enEncouraging = "Keep steady and move with control.",
            enDetailed = "This is your coaching voice. Keep steady and move with control.",
            zhTerse = "語音預覽。",
            zhEncouraging = "保持穩定，用可控制的速度移動。",
            zhDetailed = "這是你的教練語音。請保持穩定，並用可控制的速度移動。",
        ),
        "senior_continue" to cue(
            enTerse = "Keep going slowly.",
            enEncouraging = "Keep going slowly.",
            enDetailed = "Keep going slowly. I will use one step at a time.",
            zhTerse = "慢慢繼續。",
            zhEncouraging = "慢慢繼續，保持穩定。",
            zhDetailed = "慢慢繼續，我會一次只給一個步驟。",
        ),
        "senior_repeat_simple_cue" to cue(
            enTerse = "One slow rep.",
            enEncouraging = "One slow rep.",
            enDetailed = "Do one slow rep when you are ready.",
            zhTerse = "做一次慢動作。",
            zhEncouraging = "準備好後，慢慢做一次。",
            zhDetailed = "準備好後，請慢慢完成一次動作。",
        ),
        "senior_setup_check" to cue(
            enTerse = "Pause and adjust.",
            enEncouraging = "Pause and adjust your view.",
            enDetailed = "Pause for a moment and adjust the camera view before continuing.",
            zhTerse = "先暫停調整。",
            zhEncouraging = "先暫停一下，調整畫面。",
            zhDetailed = "先暫停一下，繼續前請調整鏡頭畫面。",
        ),
        "senior_step_back_into_view" to cue(
            enTerse = "Step back into view.",
            enEncouraging = "Step back into view.",
            enDetailed = "Step back into view, then press continue when you are ready.",
            zhTerse = "回到畫面中。",
            zhEncouraging = "請回到畫面中。",
            zhDetailed = "請回到畫面中，準備好後再按繼續。",
        ),
        "senior_one_person_only" to cue(
            enTerse = "Pause with one person in view.",
            enEncouraging = "Pause with one person in view.",
            enDetailed = "Pause until only the exercising person is clearly in view.",
            zhTerse = "畫面保留一位。",
            zhEncouraging = "請先讓畫面中只保留一位運動者。",
            zhDetailed = "請先暫停，直到畫面中只有正在運動的人清楚可見。",
        ),
        "senior_pause_for_support" to cue(
            enTerse = "Let's pause.",
            enEncouraging = "Let's pause for now.",
            enDetailed = "Let's pause for now. You can continue when you are ready.",
            zhTerse = "先暫停。",
            zhEncouraging = "我們先暫停一下。",
            zhDetailed = "我們先暫停一下。準備好後再繼續。",
        ),
        "senior_session_summary" to cue(
            enTerse = "Session ended.",
            enEncouraging = "Session ended.",
            enDetailed = "Session ended. I will summarize what was observed.",
            zhTerse = "活動結束。",
            zhEncouraging = "活動結束了。",
            zhDetailed = "活動結束了。我會整理剛才觀察到的內容。",
        ),
        "unknown" to cue(
            enTerse = "Adjust form.",
            enEncouraging = "Adjust your form for better control.",
            enDetailed = "Adjust your form and continue only when the movement feels controlled.",
            zhTerse = "調整姿勢。",
            zhEncouraging = "調整姿勢，讓動作更穩。",
            zhDetailed = "請調整姿勢，只有在動作感覺可控制時再繼續。",
        ),
    )

    private fun cue(
        enTerse: String,
        enEncouraging: String,
        enDetailed: String,
        zhTerse: String,
        zhEncouraging: String,
        zhDetailed: String,
    ): LocalizedCue {
        return LocalizedCue(
            en = StyledCue(
                encouraging = enEncouraging,
                terse = enTerse,
                detailed = enDetailed,
            ),
            zhTw = StyledCue(
                encouraging = zhEncouraging,
                terse = zhTerse,
                detailed = zhDetailed,
            ),
        )
    }
}
