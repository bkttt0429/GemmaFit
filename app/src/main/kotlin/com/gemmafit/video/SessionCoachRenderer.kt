package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import com.gemmafit.memory.RefusalValidator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

object SessionCoachRenderer {
    private val hardCoachingFunctions = setOf(
        "correct_knee_alignment",
        "correct_spinal_alignment",
        "correct_joint_angle",
        "correct_asymmetry",
        "warn_com_offset",
        "warn_rapid_movement",
        "increase_range_of_motion",
        "positive_reinforcement",
    )

    fun contextFrom(
        summary: SessionSummary,
        seniorHeroMode: Boolean = false,
        locale: com.gemmafit.settings.ResolvedLocale = com.gemmafit.settings.ResolvedLocale.EN_US,
    ): SessionCoachContext {
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
            personalTraceEnvelope = summary.personalTraceEnvelope,
            capabilityContract = summary.capabilityContract,
            evidenceRefs = summary.evidenceRefs,
            seniorHeroMode = seniorHeroMode,
            activityContext = summary.activityContext,
            visualContext = summary.visualContext,
            locale = locale,
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
        val timing = modelTimingMetadata(result)
        val modelCareLog = if (!fallback && functionName == "create_care_activity_log") {
            careActivityLogInsight(
                context = context,
                args = args,
                backend = backend,
                evidenceRefs = evidenceRefs,
                basis = basis,
                inferenceTimeMs = result?.inferenceTimeMs ?: 0.0,
                timing = timing,
            )
        } else {
            null
        }
        if (modelCareLog != null) return modelCareLog
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
            modelInfo = timing.modelInfo,
            modelFileName = timing.modelFileName,
            modelPath = timing.modelPath,
            initTimeMs = timing.initTimeMs,
            attemptCount = timing.attemptCount,
            firstError = timing.firstError,
            retryError = timing.retryError,
            firstTokenTimeMs = timing.firstTokenTimeMs,
            constrainedDecoding = timing.constrainedDecoding,
            modelStatus = if (fallback) SessionCoachModelStatus.FALLBACK else SessionCoachModelStatus.MODEL,
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
        val requiredFunction = "create_care_activity_log"
        val requiredArgs = LiteRtOutputContract.requiredArgs(requiredFunction)
        val sessionRefs = sessionSummaryEvidenceRefs(context)
        val activityContext = activityContextToJson(context.activityContext)
        val selectedRefs = (
            sessionRefs +
                context.evidenceRefs +
                context.activityContext.evidenceRefs +
                context.visualContext.evidenceRefs
            )
            .filter { it.isNotBlank() }
            .distinct()
            .take(18)
        val narrativePacket = CoachNarrativePacketBuilder.build(
            repHistory = context.repHistory,
            envelope = context.personalTraceEnvelope,
        )
        return JSONObject()
            .put("trigger", "SESSION_SUMMARY")
            .put("locale", context.locale.tag)
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
                .put("rep_history", sessionRepHistory(context.repHistory))
            )
            .apply {
                if (context.activityContext.state != ActivityContextState.UNKNOWN) {
                    put("activity_context", activityContext)
                }
                if (context.visualContext.available) {
                    put("visual_context", context.visualContext.toJson())
                }
                if (narrativePacket != null) {
                    put("coach_narrative_packet", narrativePacket)
                }
            }
            .put("capability_contract", capabilityContractToJson(context.capabilityContract, selectedRefs))
            .put("evidence_refs", JSONArray(selectedRefs))
            .put("evidence_ledger", sessionEvidenceLedger(context, selectedRefs))
            .put(
                "output_contract",
                JSONObject()
                    .put("required_function", requiredFunction)
                    .put("allowed_function_names", JSONArray(listOf(requiredFunction)))
                    .put("required_args", JSONArray(requiredArgs))
                    .put("json_only", true)
                    .put("first_char", "{")
                    .put("first_key", "function")
                    .put("do_not_copy_as_args", true)
                    .put(
                        "style",
                        "Compose a concise observational care log. Use 2-3 natural sentences across completion and observations when evidence supports it.",
                    ),
            )
            .toString()
    }

    private fun sessionRepHistory(repHistory: List<RepRecord>): JSONArray {
        return JSONArray(
            repHistory.take(12).map { rep ->
                JSONObject()
                    .put("rep_number", rep.repNumber)
                    .put("form_quality", rep.formQuality.toDouble())
                    .put("range_of_motion_deg", rep.rangeOfMotionDeg.toDouble())
                    .put("had_violations", rep.hadViolations)
                    .put("warning_names", JSONArray(rep.warningNames.take(4)))
                    .put(
                        "trace",
                        rep.traceSummary?.let { trace ->
                            JSONObject()
                                .put("tempo_sec", trace.tempoSec.toDouble())
                                .put("rom_proxy_deg", trace.romProxyDeg.toDouble())
                                .put("peak_velocity_deg_s", trace.peakVelocityDegS.toDouble())
                                .put("smoothness_proxy", trace.smoothnessProxy.toDouble())
                                .put("lateral_sway_proxy", trace.lateralSwayProxy.toDouble())
                                .put("confidence_coverage", trace.confidenceCoverage.toDouble())
                        } ?: JSONObject.NULL,
                    )
            },
        )
    }

    private fun activityContextToJson(activityContext: ActivityContext): JSONObject {
        return JSONObject()
            .put("state", activityContext.state.wireName)
            .put("task_label", activityContext.taskLabel ?: JSONObject.NULL)
            .put("confidence", activityContext.confidence.toDouble())
            .put("ambiguity_note", activityContext.ambiguityNote ?: JSONObject.NULL)
            .put("evidence_refs", JSONArray(activityContext.evidenceRefs.take(6)))
    }

    fun validateModelResult(
        context: SessionCoachContext,
        result: LLMBridge.FunctionCallResult,
    ): LLMBridge.FunctionCallResult {
        if (!result.success) return result
        if (hasThoughtLeak(result)) {
            return result.copy(
                success = false,
                errorMessage = "thought_leak_detected",
                selectionBasis = "Model output included reasoning text instead of a bounded function call.",
            )
        }
        val allowedRefs = (
            sessionSummaryEvidenceRefs(context) +
                context.evidenceRefs +
                context.capabilityContract.evidenceRefs +
                context.activityContext.evidenceRefs +
                context.visualContext.evidenceRefs
            )
            .filter { it.isNotBlank() }
            .toSet()
        val citedRefs = result.evidenceRefs.filter { it.isNotBlank() }
        if (functionBlockedByCapability(context.capabilityContract, result.functionName)) {
            return result.copy(
                success = false,
                functionName = "refuse_unsupported_question",
                argsJson = """{"reason":"capability_contract_blocked","safe_alternative":"Use only metrics listed in can_judge."}""",
                errorMessage = "capability_contract_blocked",
                selectionBasis = "Model selected a tool for a metric that is outside can_judge.",
            )
        }
        if (result.functionName in hardCoachingFunctions) {
            if (allowedRefs.isEmpty()) {
                return result.copy(
                    success = false,
                    errorMessage = "missing_allowed_evidence_refs",
                    selectionBasis = "Hard coaching requires session Evidence DAG refs.",
                )
            }
            if (citedRefs.isEmpty()) {
                return result.copy(
                    success = false,
                    errorMessage = "missing_evidence_refs",
                    selectionBasis = "Hard coaching result did not cite bounded evidence.",
                )
            }
        }
        if (result.functionName in evidenceRequiredUserFacingFunctions) {
            if (allowedRefs.isEmpty()) {
                return result.copy(
                    success = false,
                    errorMessage = "missing_allowed_evidence_refs",
                    selectionBasis = "User-facing model output requires session Evidence DAG refs.",
                )
            }
            if (citedRefs.isEmpty()) {
                return result.copy(
                    success = false,
                    errorMessage = "missing_evidence_refs",
                    selectionBasis = "User-facing model output did not cite bounded evidence.",
                )
            }
        }
        if (result.functionName !in hardCoachingFunctions && allowedRefs.isEmpty() && citedRefs.isNotEmpty()) {
            return result.copy(
                success = false,
                errorMessage = "invalid_evidence_refs",
                selectionBasis = "Model cited evidence outside the session Evidence DAG.",
            )
        }
        if (allowedRefs.isNotEmpty() && citedRefs.any { it !in allowedRefs }) {
            return result.copy(
                success = false,
                errorMessage = "invalid_evidence_refs",
                selectionBasis = "Model cited evidence outside the session Evidence DAG.",
            )
        }
        if (result.functionName in userFacingTextFunctions && !RefusalValidator.isClean(result.argsJson)) {
            return result.copy(
                success = false,
                errorMessage = "forbidden_claim_detected",
                selectionBasis = "Model text crossed the non-diagnostic evidence boundary.",
            )
        }
        return result
    }

    private fun careActivityLogInsight(
        context: SessionCoachContext,
        args: JSONObject,
        backend: String,
        evidenceRefs: List<String>,
        basis: String,
        inferenceTimeMs: Double,
        timing: ModelTimingMetadata,
    ): SessionCoachInsight? {
        val headline = modelText(args, "headline", 80)
            ?: headlineFor(context, "create_care_activity_log", fallback = false)
        val completed = modelText(args, "what_was_completed", 220)
        val observations = modelText(args, "observations", 180)
        val notJudged = modelText(args, "not_judged", 220)
        val nextFocus = modelText(args, "next_session_focus", 140)
        val caregiverNote = modelText(args, "caregiver_note", 180)
        if (completed == null && observations == null && nextFocus == null) return null
        return SessionCoachInsight(
            headline = headline,
            whatISaw = listOfNotNull(completed, observations)
                .joinToString(" ")
                .ifBlank { whatISaw(context) },
            whyItMatters = caregiverNote ?: whyItMatters(context, "create_care_activity_log", basis),
            notJudged = notJudged ?: notJudged(context),
            nextFocus = nextFocus ?: nextFocus(context, args),
            backend = backend,
            functionName = "create_care_activity_log",
            evidenceRefs = evidenceRefs,
            selectionBasis = basis,
            inferenceTimeMs = inferenceTimeMs,
            modelInfo = timing.modelInfo,
            modelFileName = timing.modelFileName,
            modelPath = timing.modelPath,
            initTimeMs = timing.initTimeMs,
            attemptCount = timing.attemptCount,
            firstError = timing.firstError,
            retryError = timing.retryError,
            firstTokenTimeMs = timing.firstTokenTimeMs,
            constrainedDecoding = timing.constrainedDecoding,
            modelStatus = SessionCoachModelStatus.MODEL,
            fallback = false,
        )
    }

    private fun modelText(args: JSONObject, key: String, maxLength: Int): String? {
        val normalized = args.optString(key, "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank() || normalized.length > maxLength) return null
        return normalized.takeIf { RefusalValidator.isClean(it) }
    }

    private fun hasThoughtLeak(result: LLMBridge.FunctionCallResult): Boolean {
        val text = listOf(result.rawResponse, result.argsJson, result.selectionBasis)
            .joinToString("\n")
            .lowercase()
        return text.contains("<think") ||
            text.contains("</think") ||
            text.contains("<|think|") ||
            text.contains("<|thinking|") ||
            text.contains("\"analysis\"") ||
            text.contains("chain-of-thought")
    }

    private fun headlineFor(
        context: SessionCoachContext,
        functionName: String,
        fallback: Boolean,
    ): String {
        if (context.seniorHeroMode) {
            return when {
                context.lowConfidenceCount > 0 || context.viewLimitedCount > 0 ->
                    "Senior Hero review: supported strength practice"
                context.safetyEvents.isNotEmpty() ->
                    "Senior Hero review: keep the next set controlled"
                else ->
                    "Senior Hero review: controlled practice completed"
            }
        }
        val source = if (fallback) "Deterministic evidence summary" else "Local Gemma evidence summary"
        if (!hasUserFacingActivity(context)) {
            return "$source: movement review without confirmed activity"
        }
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
        if (context.seniorHeroMode) {
            val reps = if (context.totalReps > 0) {
                "${context.totalReps} completed reps"
            } else {
                "${context.totalFrames} frames"
            }
            val limits = buildList {
                if (context.viewLimitedCount > 0) add("some camera-limited frames")
                if (context.lowConfidenceCount > 0) add("some low-confidence pose frames")
            }.joinToString(", ")
            return if (limits.isNotBlank()) {
                "I reviewed $reps from a supported home strength practice. Pose tracking was usable for caregiver-friendly activity feedback, with $limits."
            } else {
                "I reviewed $reps from a supported home strength practice. Movement stayed visible enough for a caregiver-friendly activity summary."
            }
        }
        if (!hasUserFacingActivity(context)) {
            return "I reviewed ${context.totalFrames} analyzed frames. Layer 2 did not confirm a supported activity or completed rep, so this summary stays monitor-only."
        }
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
        if (context.seniorHeroMode) {
            return "This gives a caregiver-friendly activity summary from visible pose evidence without estimating fall risk, joint force, diagnosis, or rehabilitation progress."
        }
        return if (context.safetyEvents.isNotEmpty()) {
            "This matters because the app found repeatable biomechanics evidence for ${functionName.replace("_", " ")}. $basis"
        } else if (context.viewLimitedCount > 0 || context.lowConfidenceCount > 0) {
            "This matters because limited camera or pose evidence can make a confident form grade misleading. The coach should explain the boundary instead of making a hard call."
        } else {
            "This matters because the clean summary is based on checked movement evidence, not a generic compliment."
        }
    }

    private fun notJudged(context: SessionCoachContext): String {
        if (context.seniorHeroMode) {
            val cameraBoundary = buildList {
                if (context.viewLimitedCount > 0) add("some camera-limited frames")
                if (context.lowConfidenceCount > 0) add("unstable pose tracking")
            }
            val fixed = listOf(
                "fall risk",
                "clinical diagnosis",
                "joint force",
                "muscle activation",
                "rehabilitation progress",
            )
            return "Not assessed: ${(cameraBoundary + fixed).distinct().joinToString(", ")}."
        }
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
        if (context.seniorHeroMode) {
            val topWarning = topSafetyEvent(context)
            return when (topWarning?.functionName) {
                "correct_spinal_alignment" ->
                    "Next time, keep the support area clear and move slowly while keeping the trunk steady."
                "correct_knee_alignment" ->
                    "Next time, use the same support and keep knees tracking comfortably over the feet."
                "warn_rapid_movement" ->
                    "Next time, slow the pace and pause briefly before standing tall again."
                else ->
                    "Next time, keep the area clear, use stable support, and move at the same controlled pace."
            }
        }
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
        if (!hasUserFacingActivity(context)) {
            return "refuse_unsupported_question"
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
            functionName == "refuse_unsupported_question" && !hasUserFacingActivity(context) ->
                "Layer 2 did not confirm a supported activity or completed rep."
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
        val sessionRefs = sessionSummaryEvidenceRefs(context)
        if (sessionRefs.isNotEmpty()) return sessionRefs.take(8)
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
        return "fallback:${friendlyFallbackReason(result.errorMessage.ifBlank { result.backend })}"
    }

    private fun modelTimingMetadata(result: LLMBridge.FunctionCallResult?): ModelTimingMetadata {
        val raw = result?.modelInfoJson ?: "{}"
        val info = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val path = info.optString("model_path")
        val modelName = info.optString("model_name")
            .ifBlank { path.takeIf { it.isNotBlank() }?.let { File(it).name }.orEmpty() }
        val initTimeMs = info.optNullableLong("init_time_ms")
        return ModelTimingMetadata(
            modelInfo = raw,
            modelFileName = modelName,
            modelPath = path,
            initTimeMs = initTimeMs,
            attemptCount = info.optInt("attempt_count", if (result == null) 0 else 1),
            firstError = info.optString("first_error"),
            retryError = info.optString("retry_error"),
            firstTokenTimeMs = info.optNullableLong("first_token_ms"),
            constrainedDecoding = info.optBoolean("constrained_decoding", false),
        )
    }

    private fun friendlyFallbackReason(reason: String): String {
        val normalized = reason.lowercase()
        return when {
            "isolated_litert" in normalized -> "local_model_unavailable"
            "litert_prompt_infer" in normalized -> "local_model_unavailable"
            "input token ids are too long" in normalized -> "local_model_unavailable"
            "failed to generate content" in normalized -> "local_model_unavailable"
            "deadobject" in normalized -> "local_model_unavailable"
            "sigsegv" in normalized || "sigbus" in normalized -> "local_model_unavailable"
            else -> reason
        }
    }

    private fun isLocalModelBackend(backend: String): Boolean {
        return backend == "llama.cpp" || backend.startsWith("litert-lm")
    }

    private fun hasUserFacingActivity(context: SessionCoachContext): Boolean {
        val hasActivityContext = context.activityContext.taskLabel?.isNotBlank() == true &&
            context.activityContext.state in setOf(
                ActivityContextState.CALIBRATING,
                ActivityContextState.LOCKED,
                ActivityContextState.SUSPECT_SWITCH,
            )
        return context.totalReps > 0 || hasActivityContext || context.mainExercise != "unknown"
    }

    private fun displayExercise(exercise: String): String {
        return exercise
            .ifBlank { "movement review" }
            .let { if (it == "unknown") "movement review" else it }
            .replace("_", " ")
    }

    private fun safeSummaryText(text: String): Boolean {
        val lowered = text.lowercase()
        val banned = listOf("diagnosis", "medical", "clinical", "force", "activation", "%")
        return banned.none { it in lowered }
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
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

    private fun capabilityContractToJson(
        contract: CapabilityContract,
        selectedRefs: List<String>,
    ): JSONObject {
        val selected = selectedRefs.toSet()
        fun compact(items: List<CapabilityJudgment>): List<JSONObject> {
            return items
                .filter { item -> item.evidenceRefs.isEmpty() || item.evidenceRefs.any { it in selected } }
                .take(8)
                .map { item ->
                    capabilityToJson(
                        item.copy(
                            evidenceRefs = item.evidenceRefs
                                .filter { it in selected }
                                .ifEmpty { item.evidenceRefs.take(1) },
                        )
                    )
                }
        }
        return JSONObject()
            .put("can_judge", JSONArray(compact(contract.canJudge)))
            .put("cannot_judge", JSONArray(compact(contract.cannotJudge)))
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

    private fun sessionSummaryEvidenceRefs(context: SessionCoachContext): List<String> {
        return buildList {
            add("metric.session.total_frames")
            add("metric.session.total_reps")
            add("metric.session.avg_form_score")
            add("metric.session.duration_seconds")
            if (context.viewLimitedCount > 0) add("metric.session.view_limited_count")
            if (context.lowConfidenceCount > 0) add("metric.session.low_confidence_count")
            if (context.safetyEvents.isNotEmpty()) add("metric.session.safety_events")
            context.repHistory.take(12).forEach { rep -> add("rep.${rep.repNumber}.trace") }
            addAll(context.activityContext.evidenceRefs)
            addAll(context.visualContext.evidenceRefs)
        }
    }

    private fun sessionEvidenceLedger(
        context: SessionCoachContext,
        refs: List<String>,
    ): JSONArray {
        val ledger = JSONArray()
        val sessionValues = mapOf(
            "metric.session.total_frames" to context.totalFrames,
            "metric.session.total_reps" to context.totalReps,
            "metric.session.avg_form_score" to context.avgFormScore.toDouble(),
            "metric.session.duration_seconds" to context.durationSeconds,
            "metric.session.view_limited_count" to context.viewLimitedCount,
            "metric.session.low_confidence_count" to context.lowConfidenceCount,
            "metric.session.safety_events" to context.safetyEvents.size,
        )
        refs.forEach { ref ->
            val value = sessionValues[ref]
            ledger.put(
                JSONObject()
                    .put("id", ref)
                    .put("type", if (ref.startsWith("metric.session.")) "session_metric" else "evidence_ref")
                    .put("metric", ref.substringAfterLast('.'))
                    .put("value", value ?: JSONObject.NULL)
                    .put("confidence", if (ref.startsWith("gate.") || ref.startsWith("capability.cannot.")) 0.0 else 0.85)
                    .put("status", sessionEvidenceStatus(ref))
                    .put("source", "session_summary"),
            )
        }
        return ledger
    }

    private fun sessionEvidenceStatus(ref: String): String {
        return when {
            ref.startsWith("gate.critical.") -> "CRITICAL"
            ref.startsWith("gate.warning.") -> "WARNING"
            ref.startsWith("gate.view_limited.") -> "VIEW_LIMITED"
            ref.startsWith("gate.not_applicable.") -> "NOT_APPLICABLE"
            ref.startsWith("capability.cannot.") -> "NOT_SUPPORTED"
            else -> "OK"
        }
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

    private val evidenceRequiredUserFacingFunctions = setOf(
        "create_care_activity_log",
        "create_persona_activity_report",
        "positive_reinforcement",
    )

    private val userFacingTextFunctions = evidenceRequiredUserFacingFunctions + setOf(
        "refuse_unsupported_question",
        "correct_knee_alignment",
        "correct_spinal_alignment",
        "correct_joint_angle",
        "correct_asymmetry",
        "warn_com_offset",
        "warn_rapid_movement",
        "increase_range_of_motion",
    )

    private data class ModelTimingMetadata(
        val modelInfo: String = "{}",
        val modelFileName: String = "",
        val modelPath: String = "",
        val initTimeMs: Long? = null,
        val attemptCount: Int = 0,
        val firstError: String = "",
        val retryError: String = "",
        val firstTokenTimeMs: Long? = null,
        val constrainedDecoding: Boolean = false,
    )
}
