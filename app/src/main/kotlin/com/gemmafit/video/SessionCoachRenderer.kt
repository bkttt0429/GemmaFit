package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

object SessionCoachRenderer {
    fun contextFrom(summary: SessionSummary): SessionCoachContext {
        return SessionCoachContext(
            totalFrames = summary.totalFrames,
            totalReps = summary.totalReps,
            avgFormScore = summary.avgFormScore,
            durationSeconds = summary.durationSeconds,
            mainExercise = summary.detection.mainExercise,
            exerciseConfidence = summary.detection.confidence,
            detectedExercises = summary.detection.detectedExercises,
            safetyEvents = summary.safetyEvents,
            formScores = summary.formScores,
            viewLimitedCount = summary.viewLimitedCount,
            lowConfidenceCount = summary.lowConfidenceCount,
            notApplicableCounts = summary.notApplicableCounts,
            muscleFocusDistribution = summary.muscleFocusDistribution,
            repHistory = summary.repHistory,
            capabilityContract = summary.capabilityContract,
            evidenceRefs = summary.evidenceRefs,
        )
    }

    fun render(
        context: SessionCoachContext,
        result: LLMBridge.FunctionCallResult? = null,
    ): SessionCoachInsight {
        val fallback = result == null || !result.success || !isLocalModelBackend(result.backend)
        val functionName = result?.functionName
            ?.takeIf { it.isNotBlank() }
            ?: functionForSession(context)
        val args = runCatching { JSONObject(result?.argsJson ?: "{}") }.getOrElse { JSONObject() }
        val evidenceRefs = result?.evidenceRefs.orEmpty().ifEmpty { evidenceRefsFor(context, functionName) }
        val basis = result?.selectionBasis
            ?.takeIf { it.isNotBlank() }
            ?: selectionBasisFor(context, functionName)
        val backend = backendLabel(result)
        return SessionCoachInsight(
            headline = headlineFor(context, functionName, fallback),
            whatISaw = whatISaw(context),
            whyItMatters = whyItMatters(context, functionName, basis),
            notJudged = notJudged(context),
            nextFocus = nextFocus(context, args),
            backend = backend,
            functionName = functionName,
            evidenceRefs = evidenceRefs,
            selectionBasis = basis,
            inferenceTimeMs = result?.inferenceTimeMs ?: 0.0,
            fallback = fallback,
        )
    }

    fun toCoachContext(context: SessionCoachContext): CoachContext {
        val topWarning = topSafetyEvent(context)
        val qualityFlags = buildList {
            topWarning?.let {
                add(
                    QualityFlag(
                        id = it.functionName.ifBlank { "safety_event" },
                        status = if (it.severity == "high") "CRITICAL" else "WARNING",
                        value = context.safetyEvents.size.toFloat(),
                        threshold = 1f,
                        evidence = "session_summary",
                        reason = it.description,
                        rule = it.rule,
                        joint = it.joint,
                    )
                )
            }
            if (context.viewLimitedCount > 0) {
                add(
                    QualityFlag(
                        id = "view_limited_frames",
                        status = "VIEW_LIMITED",
                        value = context.viewLimitedCount.toFloat(),
                        threshold = 1f,
                        evidence = "session_summary",
                        reason = "Some frames were camera-limited.",
                    )
                )
            }
            if (context.lowConfidenceCount > 0) {
                add(
                    QualityFlag(
                        id = "low_confidence_frames",
                        status = "LOW_CONFIDENCE",
                        value = context.lowConfidenceCount.toFloat(),
                        threshold = 1f,
                        evidence = "session_summary",
                        reason = "Some frames had unstable pose tracking.",
                    )
                )
            }
        }
        val muscle = context.muscleFocusDistribution
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
        return CoachContext(
            exercise = context.mainExercise,
            movementPhase = "session_summary",
            pattern = context.mainExercise,
            repCount = context.totalReps,
            cleanStreak = cleanPercent(context),
            metrics = mapOf(
                "avg_form_score" to context.avgFormScore,
                "warning_events" to context.safetyEvents.size.toFloat(),
                "view_limited_frames" to context.viewLimitedCount.toFloat(),
                "low_confidence_frames" to context.lowConfidenceCount.toFloat(),
            ),
            muscle = MuscleFocusResult(
                primary = muscle,
                secondary = emptyList(),
                pattern = context.mainExercise,
                confidence = "session_aggregate",
            ),
            warnings = topWarning?.let {
                listOf(
                    SafetyWarning(
                        rule = it.rule,
                        functionName = it.functionName,
                        message = it.description,
                        severity = it.severity,
                        joint = it.joint,
                    )
                )
            }.orEmpty(),
            qualityFlags = qualityFlags,
            notApplicableFlags = context.notApplicableCounts.keys.map {
                QualityFlag(
                    id = it,
                    status = "NOT_APPLICABLE",
                    value = (context.notApplicableCounts[it] ?: 0).toFloat(),
                    threshold = 1f,
                    evidence = "session_summary",
                    reason = "Skipped by applicability or view gate.",
                )
            },
            evidenceCard = EvidenceCard(
                verdict = if (context.safetyEvents.isNotEmpty()) "WARNING" else "OK",
                reason = selectionBasisFor(context, functionForSession(context)),
                evidence = evidenceRefsFor(context, functionForSession(context)).take(4).map {
                    EvidenceItem(it.replace("_", " "), "session")
                },
                trustFlags = trustFlagsFor(context),
                evidenceRefs = context.evidenceRefs,
                capabilityCanJudge = context.capabilityContract.canJudge.map { it.metric },
                capabilityCannotJudge = context.capabilityContract.cannotJudge.map { it.metric },
            ),
        )
    }

    fun buildSafetyJson(context: SessionCoachContext): String {
        return JSONObject()
            .put("session_summary", JSONObject()
                .put("total_frames", context.totalFrames)
                .put("total_reps", context.totalReps)
                .put("avg_form_score", context.avgFormScore.toDouble())
                .put("duration_seconds", context.durationSeconds)
                .put("main_exercise", context.mainExercise)
                .put("exercise_confidence", context.exerciseConfidence.toDouble())
                .put("safety_events", context.safetyEvents.size)
                .put("view_limited_count", context.viewLimitedCount)
                .put("low_confidence_count", context.lowConfidenceCount)
                .put("not_applicable", JSONObject(context.notApplicableCounts))
                .put("top_warnings", JSONArray(context.safetyEvents.take(5).map { warningToJson(it) }))
            )
            .put("capability_contract", capabilityContractToJson(context.capabilityContract))
            .put("evidence_refs", JSONArray(context.evidenceRefs.take(24)))
            .toString()
    }

    fun validateModelResult(
        context: SessionCoachContext,
        result: LLMBridge.FunctionCallResult,
    ): LLMBridge.FunctionCallResult {
        if (!result.success) return result
        val allowedRefs = (context.evidenceRefs + context.capabilityContract.evidenceRefs).toSet()
        if (allowedRefs.isNotEmpty() && result.evidenceRefs.any { it !in allowedRefs }) {
            return result.copy(
                success = false,
                errorMessage = "invalid_evidence_refs",
                selectionBasis = "Model cited evidence outside the session Evidence DAG.",
            )
        }
        if (functionBlockedByCapability(context.capabilityContract, result.functionName)) {
            return result.copy(
                success = false,
                functionName = "refuse_unsupported_question",
                argsJson = """{"reason":"capability_contract_blocked","safe_alternative":"Use only metrics listed in can_judge."}""",
                errorMessage = "capability_contract_blocked",
                selectionBasis = "Model selected a tool for a metric that is outside can_judge.",
            )
        }
        return result
    }

    private fun headlineFor(
        context: SessionCoachContext,
        functionName: String,
        fallback: Boolean,
    ): String {
        val source = if (fallback) "Deterministic evidence summary" else "Local Gemma evidence summary"
        val exercise = displayExercise(context.mainExercise)
        return when {
            context.lowConfidenceCount > 0 || context.viewLimitedCount > 0 ->
                "$source: $exercise with camera-limited evidence"
            context.safetyEvents.isNotEmpty() ->
                "$source: $exercise needs ${functionName.replace("_", " ")} focus"
            else ->
                "$source: clean $exercise pattern"
        }
    }

    private fun whatISaw(context: SessionCoachContext): String {
        val exercise = displayExercise(context.mainExercise)
        val score = context.avgFormScore.roundToInt()
        val reps = if (context.totalReps > 0) "${context.totalReps} reps" else "${context.totalFrames} analyzed frames"
        val issues = context.safetyEvents
            .groupBy { it.functionName }
            .entries
            .sortedByDescending { it.value.size }
            .take(2)
            .joinToString(", ") { "${it.key.replace("_", " ")} x${it.value.size}" }
        val limits = buildList {
            if (context.viewLimitedCount > 0) add("${context.viewLimitedCount} view-limited frames")
            if (context.lowConfidenceCount > 0) add("${context.lowConfidenceCount} low-confidence frames")
        }.joinToString(", ")
        val base = "I saw $reps of $exercise with an average form score of $score."
        return when {
            issues.isNotBlank() -> "$base The main evidence flags were $issues."
            limits.isNotBlank() -> "$base The main boundary was $limits, so hard judgments stayed limited."
            else -> "$base No warning-level safety event dominated the session."
        }
    }

    private fun whyItMatters(
        context: SessionCoachContext,
        functionName: String,
        basis: String,
    ): String {
        return if (context.safetyEvents.isNotEmpty()) {
            "This matters because the app found repeatable biomechanics evidence for ${functionName.replace("_", " ")}. $basis"
        } else if (context.viewLimitedCount > 0 || context.lowConfidenceCount > 0) {
            "This matters because limited camera or pose evidence can make a confident form grade misleading. The coach should explain the boundary instead of making a hard call."
        } else {
            "This matters because the clean summary is based on checked movement evidence, not a generic compliment."
        }
    }

    private fun notJudged(context: SessionCoachContext): String {
        val capabilitySkipped = context.capabilityContract.cannotJudge
            .map { it.metric }
            .filterNot { it in productBoundaryMetrics }
        val skipped = (context.notApplicableCounts.keys + capabilitySkipped)
            .distinct()
            .take(3)
            .joinToString(", ") { it.replace("_", " ") }
        val limits = buildList {
            if (skipped.isNotBlank()) add("skipped $skipped")
            if (context.viewLimitedCount > 0) add("camera-limited view")
            if (context.lowConfidenceCount > 0) add("unstable pose tracking")
            add("joint force")
            add("clinical risk")
            add("muscle activation percentage")
        }
        return "I did not judge ${limits.distinct().joinToString(", ")}."
    }

    private fun nextFocus(
        context: SessionCoachContext,
        args: JSONObject,
    ): String {
        val modelFocus = args.optString("next_focus", "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .takeIf { it.isNotBlank() && it.length <= 120 && safeSummaryText(it) }
        if (modelFocus != null) return modelFocus
        val topWarning = topSafetyEvent(context)
        if (topWarning != null) {
            return when (topWarning.functionName) {
                "correct_knee_alignment" -> "Next set: keep knee travel aligned with the toes before adding speed."
                "correct_spinal_alignment" -> "Next set: brace first and keep ribs and hips moving as one unit."
                "warn_rapid_movement" -> "Next set: slow the turn-around and keep the tempo controlled."
                "increase_range_of_motion" -> "Next set: use comfortable range first, then build depth gradually."
                else -> "Next set: repeat the movement more slowly and check the same evidence flag."
            }
        }
        val muscle = context.muscleFocusDistribution.entries.maxByOrNull { it.value }?.key
        return if (muscle != null) {
            "Next set: keep the same tempo while monitoring ${muscle.replace("_", " ")} as a pose-estimated load focus."
        } else {
            "Next set: keep the same controlled tempo and review camera setup if tracking drops."
        }
    }

    private fun functionForSession(context: SessionCoachContext): String {
        val top = topSafetyEvent(context)
        if (top != null && top.functionName.isNotBlank() && top.functionName != "unknown") {
            return top.functionName
        }
        if (context.viewLimitedCount > 0 || context.lowConfidenceCount > 0) {
            return "refuse_unsupported_question"
        }
        return "positive_reinforcement"
    }

    private fun topSafetyEvent(context: SessionCoachContext): SafetyEvent? {
        return context.safetyEvents
            .filter { it.functionName.isNotBlank() && it.functionName != "unknown" }
            .groupBy { it.functionName }
            .values
            .maxWithOrNull(
                compareBy<List<SafetyEvent>> { events ->
                    events.maxOf { severityPriority(it.severity) }
                }.thenBy { events ->
                    events.size
                }.thenBy { events ->
                    -events.minOf { it.frameIndex }
                }
            )
            ?.let { events ->
                val maxSeverity = events.maxOf { severityPriority(it.severity) }
                events
                    .filter { severityPriority(it.severity) == maxSeverity }
                    .minByOrNull { it.frameIndex }
            }
    }

    private fun severityPriority(severity: String): Int {
        return when (severity.lowercase()) {
            "high", "critical" -> 2
            "medium", "warning" -> 1
            else -> 0
        }
    }

    private fun selectionBasisFor(
        context: SessionCoachContext,
        functionName: String,
    ): String {
        val top = topSafetyEvent(context)
        return when {
            top != null -> "${top.description} at frame ${top.frameIndex}."
            functionName == "refuse_unsupported_question" ->
                "Camera or pose confidence limited supported judgments."
            else -> "No warning-level safety event dominated the session."
        }
    }

    private fun evidenceRefsFor(
        context: SessionCoachContext,
        functionName: String,
    ): List<String> {
        val contractRefs = refsForFunction(context.capabilityContract, functionName)
        if (contractRefs.isNotEmpty()) return contractRefs
        if (context.evidenceRefs.isNotEmpty()) return context.evidenceRefs.take(8)
        return buildList {
            add("avg_form_score")
            add("total_reps")
            if (context.safetyEvents.isNotEmpty()) addAll(context.safetyEvents.map { it.functionName })
            if (context.viewLimitedCount > 0) add("view_limited_count")
            if (context.lowConfidenceCount > 0) add("low_confidence_count")
            addAll(context.notApplicableCounts.keys)
            addAll(context.muscleFocusDistribution.keys.take(2).map { "pose_estimated_$it" })
            add(functionName)
        }.filter { it.isNotBlank() }.distinct().take(8)
    }

    private fun trustFlagsFor(context: SessionCoachContext): List<String> {
        return buildList {
            if (context.safetyEvents.isEmpty()) add("OK")
            if (context.viewLimitedCount > 0) add("VIEW_LIMITED")
            if (context.lowConfidenceCount > 0) add("LOW_CONFIDENCE")
            if (context.notApplicableCounts.isNotEmpty()) add("NOT_APPLICABLE")
        }
    }

    private fun cleanPercent(context: SessionCoachContext): Int {
        if (context.totalFrames <= 0) return 100
        return ((context.totalFrames - context.safetyEvents.size).coerceAtLeast(0) * 100 / context.totalFrames)
    }

    private fun backendLabel(result: LLMBridge.FunctionCallResult?): String {
        if (result == null) return "fallback:deterministic"
        if (result.success || result.errorMessage.isBlank()) return result.backend
        return "fallback:${result.errorMessage.ifBlank { result.backend }}"
    }

    private fun isLocalModelBackend(backend: String): Boolean {
        return backend == "llama.cpp" || backend.startsWith("litert-lm")
    }

    private fun displayExercise(exercise: String): String {
        return exercise.ifBlank { "unknown" }.replace("_", " ")
    }

    private fun safeSummaryText(text: String): Boolean {
        val lowered = text.lowercase()
        val banned = listOf("diagnosis", "medical", "clinical", "force", "activation", "%")
        return banned.none { it in lowered }
    }

    private fun warningToJson(warning: SafetyEvent): JSONObject {
        return JSONObject()
            .put("rule", warning.rule)
            .put("function", warning.functionName)
            .put("description", warning.description)
            .put("severity", warning.severity)
            .put("joint", warning.joint)
            .put("frame_index", warning.frameIndex)
    }

    private fun capabilityContractToJson(contract: CapabilityContract): JSONObject {
        return JSONObject()
            .put("can_judge", JSONArray(contract.canJudge.map { capabilityToJson(it) }))
            .put("cannot_judge", JSONArray(contract.cannotJudge.map { capabilityToJson(it) }))
    }

    private fun capabilityToJson(item: CapabilityJudgment): JSONObject {
        return JSONObject()
            .put("metric", item.metric)
            .put("reason", item.reason)
            .put("confidence_ceiling", item.confidenceCeiling.toDouble())
            .put("required_evidence", JSONArray(item.requiredEvidence))
            .put("evidence_refs", JSONArray(item.evidenceRefs))
    }

    private fun refsForFunction(
        contract: CapabilityContract,
        functionName: String,
    ): List<String> {
        val relevant = capabilityMetricsForFunction(functionName)
        return contract.canJudge
            .filter { item -> relevant.isEmpty() || item.metric in relevant }
            .flatMap { it.evidenceRefs }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun functionBlockedByCapability(
        contract: CapabilityContract,
        functionName: String,
    ): Boolean {
        if (contract.cannotJudge.any { it.metric == "hard_form_judgment" } &&
            functionName in hardJudgmentFunctions
        ) {
            return true
        }
        val relevant = capabilityMetricsForFunction(functionName)
        if (relevant.isEmpty()) return false
        val can = contract.canJudge.map { it.metric }.toSet()
        val cannot = contract.cannotJudge.map { it.metric }.toSet()
        return relevant.any { it in cannot } && relevant.none { it in can }
    }

    private fun capabilityMetricsForFunction(functionName: String): Set<String> = when (functionName) {
        "correct_knee_alignment" -> setOf("frontal_knee_valgus", "knee_valgus_fppa")
        "correct_spinal_alignment" -> setOf("trunk_lean", "trunk_angle", "body_line", "standing_trunk_angle")
        "correct_asymmetry" -> setOf("bilateral_asymmetry")
        "warn_com_offset" -> setOf("com_offset", "stability")
        "warn_rapid_movement" -> setOf("tempo")
        "increase_range_of_motion" -> setOf("squat_depth", "push_up_depth", "front_knee_angle")
        "correct_joint_angle" -> setOf("knee_angle", "hip_angle", "elbow_angle", "front_knee_angle")
        else -> emptySet()
    }

    private val productBoundaryMetrics = setOf(
        "joint_force",
        "clinical_injury_risk",
        "medical_diagnosis",
        "muscle_activation_percentage",
    )

    private val hardJudgmentFunctions = setOf(
        "correct_knee_alignment",
        "correct_spinal_alignment",
        "correct_joint_angle",
        "correct_asymmetry",
        "warn_com_offset",
        "warn_rapid_movement",
        "increase_range_of_motion",
        "positive_reinforcement",
    )
}
