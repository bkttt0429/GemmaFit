package com.gemmafit.video

import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

private const val DEFAULT_REPEAT_WINDOW_MS = 15_000L
private const val WARNING_IMPROVEMENT_WINDOW_MS = 30_000L
private const val SAME_REP_WINDOW_MS = 8_000L
private const val MAX_REWRITE_MESSAGE_CHARS = 160

data class LiveCueMemoryEntry(
    val intent: String,
    val evidenceKey: String,
    val phase: String,
    val repNumber: Int,
    val variantId: String,
    val timestampMs: Long,
    val outcome: String,
    val priority: String,
    val message: String,
) {
    val priorityRank: Int
        get() = LiveCuePlanner.priorityRank(priority)
}

class LiveCueMemory(
    private val maxEntries: Int = 80,
) {
    private val entries = ArrayDeque<LiveCueMemoryEntry>()

    fun reset() {
        entries.clear()
    }

    fun remember(entry: LiveCueMemoryEntry) {
        entries.addLast(entry)
        while (entries.size > maxEntries) {
            entries.removeFirst()
        }
    }

    fun recent(nowMs: Long, windowMs: Long = DEFAULT_REPEAT_WINDOW_MS): List<LiveCueMemoryEntry> =
        entries.filter { nowMs - it.timestampMs in 0..windowMs }

    fun countIntent(intent: String, nowMs: Long, windowMs: Long = WARNING_IMPROVEMENT_WINDOW_MS): Int =
        recent(nowMs, windowMs).count { it.intent == intent }

    fun recentSameMessage(message: String, nowMs: Long, windowMs: Long = DEFAULT_REPEAT_WINDOW_MS): Boolean {
        if (message.isBlank()) return false
        return recent(nowMs, windowMs).any { it.message == message }
    }

    fun latestSameRep(repNumber: Int, nowMs: Long, windowMs: Long = SAME_REP_WINDOW_MS): LiveCueMemoryEntry? =
        recent(nowMs, windowMs).lastOrNull { it.repNumber == repNumber && repNumber > 0 }

    fun latestWarning(nowMs: Long, windowMs: Long = WARNING_IMPROVEMENT_WINDOW_MS): LiveCueMemoryEntry? =
        recent(nowMs, windowMs).lastOrNull { it.outcome.contains("warning", ignoreCase = true) }

    fun hasRecentOutcome(
        outcome: String,
        nowMs: Long,
        windowMs: Long = WARNING_IMPROVEMENT_WINDOW_MS,
        evidenceKey: String? = null,
    ): Boolean =
        recent(nowMs, windowMs).any {
            it.outcome == outcome && (evidenceKey == null || it.evidenceKey == evidenceKey)
        }
}

enum class LiveCueRewriteEvent {
    NONE,
    REP_COMPLETED,
    WARNING_PERSISTED,
    IMPROVEMENT_AFTER_WARNING,
    SESSION_MICRO_SUMMARY,
}

internal object LiveCueModelPolicy {
    val hardCoachingFunctions = setOf(
        "correct_knee_alignment",
        "correct_spinal_alignment",
        "correct_joint_angle",
        "correct_asymmetry",
        "warn_com_offset",
        "warn_rapid_movement",
        "increase_range_of_motion",
        "positive_reinforcement",
    )

    fun triggerFor(event: LiveCueRewriteEvent): ModelInvocationTrigger? {
        return when (event) {
            LiveCueRewriteEvent.REP_COMPLETED -> ModelInvocationTrigger.REP_COMPLETED
            LiveCueRewriteEvent.WARNING_PERSISTED -> ModelInvocationTrigger.WARNING_PERSISTED
            LiveCueRewriteEvent.IMPROVEMENT_AFTER_WARNING -> ModelInvocationTrigger.IMPROVEMENT_AFTER_WARNING
            LiveCueRewriteEvent.SESSION_MICRO_SUMMARY -> ModelInvocationTrigger.SESSION_MICRO_SUMMARY
            LiveCueRewriteEvent.NONE -> null
        }
    }

    fun eventKey(cameraEpoch: Long, request: LiveCueRewriteRequest): String {
        return listOf(
            cameraEpoch,
            request.event.name,
            request.repNumber,
            request.variantId,
            request.evidenceKey,
        ).joinToString(":")
    }
}

data class LiveCueRewriteRequest(
    val event: LiveCueRewriteEvent,
    val intent: String,
    val evidenceKey: String,
    val phase: String,
    val repNumber: Int,
    val variantId: String,
    val baseMessage: String,
    val plannedMessage: String,
    val priority: String,
    val tone: String,
    val timestampMs: Long,
) {
    fun toDebugJson(): String =
        JSONObject()
            .put("event", event.name)
            .put("intent", intent)
            .put("evidence_key", evidenceKey)
            .put("phase", phase)
            .put("rep_number", repNumber)
            .put("variant_id", variantId)
            .put("base_message", baseMessage)
            .put("planned_message", plannedMessage)
            .put("priority", priority)
            .put("tone", tone)
            .put("timestamp_ms", timestampMs)
            .toString()
}

data class LiveCuePlan(
    val insight: CoachInsight,
    val outcome: String,
    val variantId: String,
    val rewriteRequest: LiveCueRewriteRequest? = null,
)

data class LiveCueRewriteValidation(
    val accepted: Boolean,
    val message: String? = null,
    val tone: String? = null,
    val variantId: String? = null,
    val reason: String? = null,
)

object LiveCueRewriteValidator {
    private val allowedTones = setOf("neutral", "encouraging", "calm", "concise")
    private val bannedTerms = listOf(
        "diagnosis",
        "diagnose",
        "medical",
        "clinical",
        "disease",
        "treatment",
        "rehab",
        "rehabilitation",
        "fall risk",
        "risk of falling",
        "sarcopenia",
        "dementia",
        "injury risk",
        "pain",
        "activation",
    )

    fun validate(raw: String?, request: LiveCueRewriteRequest): LiveCueRewriteValidation {
        if (raw.isNullOrBlank()) {
            return LiveCueRewriteValidation(false, reason = "empty_response")
        }
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return LiveCueRewriteValidation(false, reason = "invalid_json")
        val message = json.optString("message").trim()
        val tone = json.optString("tone", "neutral").lowercase(Locale.US).trim()
        val variantId = json.optString("variant_id").trim()
        if (message.isBlank()) {
            return LiveCueRewriteValidation(false, reason = "empty_message")
        }
        if (message.length > MAX_REWRITE_MESSAGE_CHARS) {
            return LiveCueRewriteValidation(false, reason = "message_too_long")
        }
        if (variantId != request.variantId) {
            return LiveCueRewriteValidation(false, reason = "variant_changed")
        }
        if (tone !in allowedTones) {
            return LiveCueRewriteValidation(false, reason = "tone_not_allowed")
        }
        val lowerMessage = message.lowercase(Locale.US)
        if (bannedTerms.any { lowerMessage.contains(it.lowercase(Locale.US)) }) {
            return LiveCueRewriteValidation(false, reason = "unsafe_claim")
        }
        return LiveCueRewriteValidation(
            accepted = true,
            message = message,
            tone = tone,
            variantId = variantId,
        )
    }
}

class LiveCuePlanner(
    private val memory: LiveCueMemory = LiveCueMemory(),
    private val repeatWindowMs: Long = DEFAULT_REPEAT_WINDOW_MS,
) {
    private var lastRepRewriteRequest = -1
    private var lastMicroSummaryRep = -1

    fun reset() {
        memory.reset()
        lastRepRewriteRequest = -1
        lastMicroSummaryRep = -1
    }

    fun plan(
        context: CoachContext,
        deterministic: CoachInsight,
        timestampMs: Long,
    ): LiveCuePlan {
        val intent = deterministic.functionName.ifBlank { "positive_reinforcement" }
        val evidenceKey = evidenceKey(context, intent)
        val phase = context.movementPhase.ifBlank { "unknown" }
        val repNumber = max(context.repCount, 0)
        val limited = isTrackingLimited(context, deterministic)
        if (limited) {
            return rememberAndReturn(
                context = context,
                insight = deterministic,
                intent = intent,
                evidenceKey = evidenceKey,
                phase = phase,
                repNumber = repNumber,
                variantId = "limited_0",
                timestampMs = timestampMs,
                outcome = "limited",
                rewriteRequest = null,
            )
        }

        val latestSameRep = memory.latestSameRep(repNumber, timestampMs)
        if (latestSameRep != null && latestSameRep.priorityRank > priorityRank(deterministic.priority)) {
            val retained = deterministic.copy(
                message = latestSameRep.message,
                priority = latestSameRep.priority,
                functionName = latestSameRep.intent,
                selectionBasis = "live_cue_memory_retained_highest_priority",
            )
            return LiveCuePlan(
                insight = retained,
                outcome = "same_rep_highest_priority_retained",
                variantId = latestSameRep.variantId,
                rewriteRequest = null,
            )
        }

        val improvement = improvementCueIfNeeded(context, deterministic, timestampMs, intent, evidenceKey)
        if (improvement != null) {
            return rememberAndReturn(
                context = context,
                insight = improvement.first,
                intent = improvement.first.functionName.ifBlank { "positive_reinforcement" },
                evidenceKey = improvement.first.evidenceRefs.firstOrNull() ?: improvement.second,
                phase = phase,
                repNumber = repNumber,
                variantId = improvement.third,
                timestampMs = timestampMs,
                outcome = "improved",
                rewriteRequest = rewriteRequest(
                    event = LiveCueRewriteEvent.IMPROVEMENT_AFTER_WARNING,
                    context = context,
                    intent = intent,
                    evidenceKey = evidenceKey,
                    phase = phase,
                    repNumber = repNumber,
                    variantId = improvement.third,
                    baseMessage = deterministic.message,
                    plannedMessage = improvement.first.message,
                    priority = improvement.first.priority,
                    timestampMs = timestampMs,
                ),
            )
        }

        val intentCount = memory.countIntent(intent, timestampMs)
        val repeated = intentCount > 0 || memory.recentSameMessage(deterministic.message, timestampMs, repeatWindowMs)
        val variant = selectVariant(intent, intentCount)
        val selectedMessage = if (repeated && variant.message.isNotBlank()) {
            variant.message
        } else {
            deterministic.message.ifBlank { variant.message }
        }
        val selectedInsight = deterministic.copy(
            message = selectedMessage,
            selectionBasis = selectionBasis(deterministic.selectionBasis, repeated, variant.id),
        )
        val outcome = when {
            priorityRank(selectedInsight.priority) >= 2 -> if (repeated) "repeated_warning" else "warning"
            intent == "positive_reinforcement" -> "positive"
            else -> "cue"
        }
        val event = rewriteEventFor(
            context = context,
            outcome = outcome,
            repeated = repeated,
            repNumber = repNumber,
        )
        return rememberAndReturn(
            context = context,
            insight = selectedInsight,
            intent = intent,
            evidenceKey = evidenceKey,
            phase = phase,
            repNumber = repNumber,
            variantId = variant.id,
            timestampMs = timestampMs,
            outcome = outcome,
            rewriteRequest = if (event == LiveCueRewriteEvent.NONE) {
                null
            } else {
                rewriteRequest(
                    event = event,
                    context = context,
                    intent = intent,
                    evidenceKey = evidenceKey,
                    phase = phase,
                    repNumber = repNumber,
                    variantId = variant.id,
                    baseMessage = deterministic.message,
                    plannedMessage = selectedMessage,
                    priority = selectedInsight.priority,
                    timestampMs = timestampMs,
                )
            },
        )
    }

    private fun rememberAndReturn(
        context: CoachContext,
        insight: CoachInsight,
        intent: String,
        evidenceKey: String,
        phase: String,
        repNumber: Int,
        variantId: String,
        timestampMs: Long,
        outcome: String,
        rewriteRequest: LiveCueRewriteRequest?,
    ): LiveCuePlan {
        if (insight.message.isNotBlank()) {
            memory.remember(
                LiveCueMemoryEntry(
                    intent = intent,
                    evidenceKey = evidenceKey,
                    phase = phase,
                    repNumber = repNumber,
                    variantId = variantId,
                    timestampMs = timestampMs,
                    outcome = outcome,
                    priority = insight.priority,
                    message = insight.message,
                ),
            )
        }
        return LiveCuePlan(
            insight = insight,
            outcome = outcome,
            variantId = variantId,
            rewriteRequest = rewriteRequest,
        )
    }

    private fun improvementCueIfNeeded(
        context: CoachContext,
        deterministic: CoachInsight,
        timestampMs: Long,
        intent: String,
        evidenceKey: String,
    ): Triple<CoachInsight, String, String>? {
        if (intent != "positive_reinforcement" || context.repCount <= 0) return null
        val lastWarning = memory.latestWarning(timestampMs) ?: return null
        if (lastWarning.repNumber >= context.repCount) return null
        if (memory.hasRecentOutcome("improved", timestampMs, evidenceKey = lastWarning.evidenceKey)) return null
        val variant = when (lastWarning.intent) {
            "warn_rapid_movement" -> CueVariant(
                "improve_tempo_0",
                "That was smoother. Keep this tempo for the next rep.",
            )
            "correct_knee_alignment" -> CueVariant(
                "improve_knee_0",
                "Better knee control on that rep. Keep the same setup.",
            )
            "correct_spinal_alignment" -> CueVariant(
                "improve_trunk_0",
                "That rep looked more controlled through the torso.",
            )
            else -> CueVariant(
                "improve_control_0",
                "That rep looked more controlled. Keep the same rhythm.",
            )
        }
        val insight = deterministic.copy(
            message = variant.message,
            priority = "low",
            localizationKey = "coach.improvement",
            selectionBasis = "live_cue_memory_improvement_after_${lastWarning.intent}",
            evidenceRefs = listOf(lastWarning.evidenceKey, evidenceKey).distinct(),
        )
        return Triple(insight, lastWarning.evidenceKey, variant.id)
    }

    private fun rewriteEventFor(
        context: CoachContext,
        outcome: String,
        repeated: Boolean,
        repNumber: Int,
    ): LiveCueRewriteEvent =
        when {
            outcome == "repeated_warning" && repeated -> LiveCueRewriteEvent.WARNING_PERSISTED
            repNumber > 0 && repNumber % 5 == 0 && repNumber != lastMicroSummaryRep -> {
                lastMicroSummaryRep = repNumber
                LiveCueRewriteEvent.SESSION_MICRO_SUMMARY
            }
            repNumber > 0 &&
                repNumber != lastRepRewriteRequest &&
                context.movementPhase.equals("completed", ignoreCase = true) -> {
                lastRepRewriteRequest = repNumber
                LiveCueRewriteEvent.REP_COMPLETED
            }
            else -> LiveCueRewriteEvent.NONE
        }

    private fun rewriteRequest(
        event: LiveCueRewriteEvent,
        context: CoachContext,
        intent: String,
        evidenceKey: String,
        phase: String,
        repNumber: Int,
        variantId: String,
        baseMessage: String,
        plannedMessage: String,
        priority: String,
        timestampMs: Long,
    ): LiveCueRewriteRequest =
        LiveCueRewriteRequest(
            event = event,
            intent = intent,
            evidenceKey = evidenceKey,
            phase = phase,
            repNumber = repNumber,
            variantId = variantId,
            baseMessage = baseMessage,
            plannedMessage = plannedMessage,
            priority = priority,
            tone = if (priorityRank(priority) >= 2) "concise" else "encouraging",
            timestampMs = timestampMs,
        )

    private fun selectionBasis(base: String, repeated: Boolean, variantId: String): String =
        listOfNotNull(
            base.takeIf { it.isNotBlank() },
            "live_cue_variant=$variantId".takeIf { repeated },
        ).joinToString(";")

    private fun selectVariant(intent: String, intentCount: Int): CueVariant {
        val variants = cueVariants[intent] ?: cueVariants.getValue("generic")
        return variants[intentCount % variants.size]
    }

    private fun evidenceKey(context: CoachContext, intent: String): String {
        context.warnings.firstOrNull()?.let { warning ->
            return listOf(warning.functionName, warning.severity, warning.joint)
                .filter { it.isNotBlank() }
                .joinToString(":")
        }
        context.qualityFlags.firstOrNull {
            it.status.equals("warning", ignoreCase = true) ||
                it.status.equals("critical", ignoreCase = true)
        }?.let { flag ->
            return listOf(flag.id, flag.status).joinToString(":")
        }
        context.evidenceCard.evidenceRefs.firstOrNull()?.let { return it }
        return intent
    }

    private fun isTrackingLimited(context: CoachContext, deterministic: CoachInsight): Boolean {
        if (deterministic.functionName == "warn_poor_visibility") return true
        if (context.evidenceCard.verdict in setOf("LOW_CONFIDENCE", "VIEW_LIMITED", "NEEDS_SELECTION")) return true
        return context.qualityFlags.any { flag ->
            flag.id.equals("LOW_CONFIDENCE", ignoreCase = true) ||
                flag.id.equals("VIEW_LIMITED", ignoreCase = true) ||
                flag.id.equals("NEEDS_SELECTION", ignoreCase = true) ||
                flag.status.equals("view_limited", ignoreCase = true) ||
                flag.status.equals("low_confidence", ignoreCase = true)
        }
    }

    private data class CueVariant(
        val id: String,
        val message: String,
    )

    companion object {
        fun priorityRank(priority: String): Int =
            when (priority.lowercase(Locale.US)) {
                "critical", "high" -> 3
                "warning", "medium" -> 2
                "low" -> 1
                else -> 0
            }

        private val cueVariants = mapOf(
            "warn_rapid_movement" to listOf(
                CueVariant("rapid_0", "Slow the next rep and own the turn-around."),
                CueVariant("rapid_1", "Pause for a beat before you stand."),
                CueVariant("rapid_2", "Keep the transition smooth before adding speed."),
            ),
            "correct_knee_alignment" to listOf(
                CueVariant("knee_0", "Keep the knee tracking over the foot."),
                CueVariant("knee_1", "Set the foot first, then guide the knee forward."),
                CueVariant("knee_2", "Control the knee path before the next rep."),
            ),
            "correct_spinal_alignment" to listOf(
                CueVariant("spine_0", "Keep the torso stacked and move with control."),
                CueVariant("spine_1", "Reset the ribs over the hips before continuing."),
                CueVariant("spine_2", "Stay tall through the torso on the next rep."),
            ),
            "correct_joint_angle" to listOf(
                CueVariant("joint_0", "Stay inside the comfortable joint range."),
                CueVariant("joint_1", "Shorten the range slightly and keep control."),
                CueVariant("joint_2", "Reset the joint angle before the next rep."),
            ),
            "correct_asymmetry" to listOf(
                CueVariant("asym_0", "Make both sides move at the same pace."),
                CueVariant("asym_1", "Match left and right before adding speed."),
                CueVariant("asym_2", "Even out the two sides on the next rep."),
            ),
            "warn_com_offset" to listOf(
                CueVariant("com_0", "Bring your weight back over your base."),
                CueVariant("com_1", "Find the middle of your stance before moving."),
                CueVariant("com_2", "Keep the pressure centered through the rep."),
            ),
            "increase_range_of_motion" to listOf(
                CueVariant("rom_0", "Use a little more range if it stays controlled."),
                CueVariant("rom_1", "Reach the same depth with a slower tempo."),
                CueVariant("rom_2", "Keep the path steady as you add range."),
            ),
            "positive_reinforcement" to listOf(
                CueVariant("positive_0", "Good control. Match that tempo again."),
                CueVariant("positive_1", "That rep stayed steady. Keep the rhythm."),
                CueVariant("positive_2", "Clean rep. Keep the same setup."),
            ),
            "warn_poor_visibility" to listOf(
                CueVariant("limited_0", "Tracking is limited. Adjust the camera before I judge form."),
                CueVariant("limited_1", "I need a clearer view before giving form feedback."),
                CueVariant("limited_2", "Hold the set until the body is easier to track."),
            ),
            "generic" to listOf(
                CueVariant("generic_0", "Keep the next rep controlled."),
                CueVariant("generic_1", "Reset your position and move smoothly."),
                CueVariant("generic_2", "Stay steady through the next rep."),
            ),
        )
    }
}
