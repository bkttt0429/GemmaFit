package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class CoachContext(
    val exercise: String,
    val movementPhase: String,
    val pattern: String,
    val repCount: Int,
    val cleanStreak: Int,
    val metrics: Map<String, Float>,
    val muscle: MuscleFocusResult?,
    val warnings: List<SafetyWarning>,
    val qualityFlags: List<QualityFlag>,
    val notApplicableFlags: List<QualityFlag>,
    val evidenceCard: EvidenceCard,
)

object CoachInsightRenderer {
    fun render(
        context: CoachContext,
        result: LLMBridge.FunctionCallResult? = null,
    ): CoachInsight {
        val fallback = result == null || !result.success || !isLocalModelBackend(result.backend)
        val functionName = result?.functionName
            ?.takeIf { it.isNotBlank() }
            ?: functionForContext(context)
        val args = result?.argsJson?.takeIf { it.isNotBlank() } ?: fallbackArgs(functionName, context)
        val selectionBasis = result?.selectionBasis
            ?.takeIf { it.isNotBlank() }
            ?: selectionBasisFor(context, functionName)
        val backend = backendLabel(result)
        return CoachInsight(
            message = renderMessage(functionName, args, context),
            priority = priorityFor(functionName, context),
            localizationKey = "coach.${if (fallback) "fallback" else "gemma"}.$functionName",
            backend = backend,
            functionName = functionName,
            argsJson = args,
            selectionBasis = selectionBasis,
            evidenceRefs = result?.evidenceRefs.orEmpty().ifEmpty { evidenceRefsFor(context) },
            summaryNarrative = renderSummaryNarrative(functionName, context, selectionBasis, backend, fallback),
            modelInfo = result?.modelInfoJson ?: "{}",
            fallback = fallback,
        )
    }

    fun buildMovementPromptJson(context: CoachContext): String {
        return JSONObject()
            .put("pattern", context.pattern)
            .put("exercise", context.exercise)
            .put("phase", context.movementPhase)
            .put("rep_count", context.repCount)
            .put("clean_streak_frames", context.cleanStreak)
            .put("metrics", JSONObject(context.metrics.mapValues { (_, value) -> value.toDouble() }))
            .put("evidence_verdict", context.evidenceCard.verdict)
            .put("evidence_reason", context.evidenceCard.reason)
            .put("quality_flags", JSONArray(context.qualityFlags.map { flagToJson(it) }))
            .put("not_applicable", JSONArray(context.notApplicableFlags.map { flagToJson(it) }))
            .toString()
    }

    fun buildMusclePromptJson(context: CoachContext): String {
        val muscle = context.muscle
        return JSONObject()
            .put("pattern", muscle?.pattern ?: context.pattern)
            .put("estimated_primary", JSONArray(muscle?.primary ?: emptyList<String>()))
            .put("estimated_secondary", JSONArray(muscle?.secondary ?: emptyList<String>()))
            .put("confidence", muscle?.confidence ?: "medium")
            .put("boundary", "pose_estimated_load_focus_not_muscle_activation")
            .toString()
    }

    private fun renderMessage(
        functionName: String,
        argsJson: String,
        context: CoachContext,
    ): String {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val cue = safeCoachCue(args)
        return when (functionName) {
            "positive_reinforcement" -> renderPositive(args, context, cue)
            "correct_knee_alignment" -> withCue(
                "Your knee line is drifting relative to the ankle line. Slow the next descent and guide knees along toes.",
                cue,
            )
            "correct_spinal_alignment" -> withCue(
                "Brace before the next rep. Keep ribs and hips stacked while the torso angle changes.",
                cue,
            )
            "correct_joint_angle" -> withCue(
                "Finish the rep controlled without snapping into a hard lockout.",
                cue,
            )
            "correct_asymmetry" -> withCue(
                "One side is moving ahead. Slow the next rep and make both sides feel even.",
                cue,
            )
            "warn_com_offset" -> withCue(
                "Your balance is drifting. Re-center mid-foot pressure before the next rep.",
                cue,
            )
            "warn_rapid_movement" -> withCue(
                "That transition was rushed. Own the turn-around before you add speed.",
                cue,
            )
            "increase_range_of_motion" -> withCue(
                "Use a little more comfortable range before adding speed.",
                cue,
            )
            else -> "Tracking is limited, so I am keeping this to an observation cue instead of a hard judgment."
        }
    }

    private fun renderPositive(args: JSONObject, context: CoachContext, cue: String?): String {
        val exercise = context.exercise.takeIf { it.isNotBlank() && it != "unknown" }
            ?: context.pattern.takeIf { it.isNotBlank() && it != "unknown" }
            ?: "movement"
        val phase = context.movementPhase.takeIf { it.isNotBlank() && it != "unknown" } ?: "rep"
        val muscles = args.optJSONArray("primary_muscles")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { value -> value.isNotBlank() } }
        }.orEmpty().ifEmpty { context.muscle?.primary.orEmpty() }.take(2)
        val movement = when {
            exercise == "squat" && phase == "descent" ->
                "Good control on the way down. Keep the same knee and hip rhythm into the bottom."
            exercise == "squat" && phase == "bottom" ->
                "Good depth. Your knees and hips stayed coordinated through the bottom position."
            exercise == "squat" && phase == "ascent" ->
                "Solid drive out of the bottom. Keep knees and hips moving together as you stand."
            exercise == "push_up" ->
                "Clean push-up rep. Keep the body line quiet while the elbows move."
            exercise == "lunge" ->
                "Controlled lunge rep. Keep the front knee tracking with the toes as you reset."
            else ->
                "Clean $exercise $phase. The main joints stayed coordinated without a hard warning."
        }
        val muscleText = if (muscles.isNotEmpty()) {
            " Main load looks like ${muscles.joinToString(" and ") { it.replace("_", " ") }} from posture, not muscle activation."
        } else {
            ""
        }
        return withCue("$movement$muscleText", cue ?: nextCue(context))
    }

    private fun renderSummaryNarrative(
        functionName: String,
        context: CoachContext,
        basis: String,
        backend: String,
        fallback: Boolean,
    ): String {
        val model = if (!fallback && isLocalModelBackend(backend)) {
            "Local Gemma selected ${functionName.replace("_", " ")}"
        } else {
            "Deterministic coach selected ${functionName.replace("_", " ")}"
        }
        val metrics = context.metrics.entries
            .filter { it.key in setOf("knee_angle", "hip_angle", "depth", "tempo_deg_s", "trunk_lean") }
            .take(4)
            .joinToString(", ") { (key, value) -> "${key.replace("_", " ")} ${value.roundToInt()}" }
            .ifBlank { "no dominant metric" }
        return "$model because $basis Metrics checked: $metrics. Safety grades still come from biomechanics."
    }

    private fun backendLabel(result: LLMBridge.FunctionCallResult?): String {
        if (result == null) return "fallback"
        if (result.success || result.errorMessage.isBlank()) return result.backend
        return when (result.errorMessage) {
            "llama_inference_timeout" -> "fallback:llama_timeout"
            "model_too_large_for_mobile_backend" -> "fallback:model_too_large"
            "litert_model_file_not_found" -> "fallback:litert_missing"
            "litert_lm_no_valid_tool_call" -> "fallback:litert_no_tool"
            else -> result.backend
        }
    }

    private fun isLocalModelBackend(backend: String): Boolean {
        return backend == "llama.cpp" || backend.startsWith("litert-lm")
    }

    private fun functionForContext(context: CoachContext): String {
        val topWarning = context.warnings.maxByOrNull { if (it.severity == "high") 2 else 1 }
        if (topWarning != null && topWarning.functionName != "unknown") return topWarning.functionName
        val topFlag = context.qualityFlags.firstOrNull {
            it.status in setOf("CRITICAL", "WARNING", "VIEW_LIMITED", "LOW_CONFIDENCE")
        }
        return when {
            topFlag?.status == "VIEW_LIMITED" || topFlag?.status == "LOW_CONFIDENCE" -> "warn_poor_visibility"
            topFlag?.rule == 1 -> "correct_knee_alignment"
            topFlag?.rule == 2 -> "correct_spinal_alignment"
            topFlag?.rule == 6 -> "warn_rapid_movement"
            else -> "positive_reinforcement"
        }
    }

    private fun fallbackArgs(functionName: String, context: CoachContext): String {
        val obj = JSONObject()
        if (functionName == "positive_reinforcement") {
            obj.put("pattern", context.pattern.ifBlank { context.exercise })
            obj.put("primary_muscles", JSONArray(context.muscle?.primary ?: emptyList<String>()))
            obj.put("streak", context.cleanStreak.coerceAtLeast(1))
            obj.put("coach_cue", nextCue(context))
        }
        return obj.toString()
    }

    private fun selectionBasisFor(context: CoachContext, functionName: String): String {
        return if (functionName == "positive_reinforcement") {
            "Clean evidence window with no active safety warning."
        } else {
            context.warnings.firstOrNull()?.message
                ?: context.qualityFlags.firstOrNull()?.reason
                ?: "Highest-priority evidence flag selected."
        }
    }

    private fun evidenceRefsFor(context: CoachContext): List<String> {
        if (context.evidenceCard.evidenceRefs.isNotEmpty()) {
            return context.evidenceCard.evidenceRefs.take(8)
        }
        return buildList {
            addAll(context.qualityFlags.map { flag -> flag.evidenceId.ifBlank { flag.id } })
            addAll(context.notApplicableFlags.map { flag -> flag.evidenceId.ifBlank { flag.id } })
            addAll(context.metrics.keys.take(4).map { key ->
                "metric.${context.exercise.ifBlank { context.pattern }.ifBlank { "movement" }}.$key"
            })
        }.distinct().take(6)
    }

    private fun priorityFor(functionName: String, context: CoachContext): String {
        if (context.warnings.any { it.severity == "high" }) return "high"
        if (functionName != "positive_reinforcement") return "medium"
        return "low"
    }

    private fun safeCoachCue(args: JSONObject): String? {
        val cue = args.optString("coach_cue", args.optString("cue", ""))
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
        if (cue.isBlank() || cue.length > 96) return null
        val lowered = cue.lowercase()
        val banned = listOf("injury", "diagnosis", "medical", "clinical", "force", "activation", "%")
        if (banned.any { it in lowered }) return null
        return cue
    }

    private fun withCue(base: String, cue: String?): String {
        if (cue.isNullOrBlank()) return base
        if (base.contains(cue.removeSuffix("."), ignoreCase = true)) return base
        return "$base $cue"
    }

    private fun nextCue(context: CoachContext): String {
        return when (context.movementPhase) {
            "descent" -> "Stay smooth into the bottom."
            "bottom" -> "Drive up without rushing."
            "ascent" -> "Finish tall without snapping the joints."
            "top" -> "Match that control on the next descent."
            else -> "Repeat that same tempo."
        }
    }

    private fun flagToJson(flag: QualityFlag): JSONObject {
        return JSONObject()
            .put("id", flag.id)
            .put("status", flag.status)
            .put("value", flag.value.toDouble())
            .put("threshold", flag.threshold.toDouble())
            .put("reason", flag.reason)
            .put("rule", flag.rule)
            .put("joint", flag.joint)
    }
}
