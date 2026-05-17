package com.gemmafit.video

import android.app.Application
import com.gemmafit.jni.LLMBridge
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session
import com.google.ai.edge.litertlm.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LiteRtLmCoachBackend(
    private val app: Application,
) : CoachInferenceBackend {
    override val name: String = "litert-lm"

    private val initMutex = Mutex()
    private var engine: Engine? = null
    private var session: Session? = null
    private var activeBackendName: String = "litert-lm:uninitialized"
    private var modelPath: String? = null
    private var initTimeMs: Long? = null
    private val initAttempts = mutableListOf<LiteRtInitAttempt>()

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        CoachModelResolver.resolveLiteRtModelPath(app) != null
    }

    override suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode,
    ): LLMBridge.FunctionCallResult = withContext(Dispatchers.IO) {
        val path = CoachModelResolver.resolveLiteRtModelPath(app)
            ?: return@withContext liteRtUnavailable("fallback", "litert_model_file_not_found")
        val safety = parseSafetyJson(safetyJson)
        LiteRtRealtimeFastPath.maybeHandle(
            context = context,
            safety = safety,
            reasoningMode = reasoningMode,
            modelPath = path,
        )?.let { return@withContext it }
        val packet = LiteRtEvidencePromptRenderer.buildEvidencePacket(context, safety, reasoningMode)
        val prompt = LiteRtEvidencePromptRenderer.buildPrompt(packet)
        val started = System.currentTimeMillis()
        val rawResponse = try {
            withTimeout(LITERT_TIMEOUT_MS) {
                ensureSession(path).generateContent(listOf(InputData.Text(prompt)))
            }
        } catch (e: Exception) {
            return@withContext liteRtUnavailable(
                backend = activeBackendName.takeIf { it != "litert-lm:uninitialized" } ?: "litert-lm",
                error = e.message ?: "litert_lm_inference_failed",
                modelInfo = liteRtModelInfoJson(activeBackendName, path, initTimeMs),
            )
        }
        val firstElapsed = (System.currentTimeMillis() - started).toDouble()
        val modelInfo = liteRtModelInfoJson(activeBackendName, path, initTimeMs)
        val parsed = LiteRtToolCallParser.parseRaw(
            rawResponse = rawResponse,
            backend = activeBackendName,
            modelInfoJson = LiteRtRetryPolicy.modelInfoWithAttempts(
                baseModelInfoJson = modelInfo,
                attemptCount = 1,
                firstError = "",
                retryError = "",
            ),
            inferenceTimeMs = firstElapsed,
        )
        val repaired = LiteRtToolResultGuard.validateAndRepair(context, packet, parsed)
        if (repaired.success || !LiteRtRetryPolicy.shouldRetry(repaired)) {
            return@withContext repaired
        }

        val retryPrompt = LiteRtEvidencePromptRenderer.buildRetryPrompt(
            packet = packet,
            firstError = repaired.errorMessage,
        )
        val retryStarted = System.currentTimeMillis()
        val retryRawResponse = try {
            withTimeout(LITERT_TIMEOUT_MS) {
                ensureSession(path).generateContent(listOf(InputData.Text(retryPrompt)))
            }
        } catch (e: Exception) {
            return@withContext liteRtUnavailable(
                backend = activeBackendName.takeIf { it != "litert-lm:uninitialized" } ?: "litert-lm",
                error = LiteRtRetryPolicy.afterRetryError(e.message ?: "litert_lm_inference_failed"),
                modelInfo = LiteRtRetryPolicy.modelInfoWithAttempts(
                    baseModelInfoJson = modelInfo,
                    attemptCount = 2,
                    firstError = repaired.errorMessage,
                    retryError = e.message ?: "litert_lm_inference_failed",
                ),
                rawResponse = retryRawResponseForFailure(rawResponse, ""),
            )
        }
        val retryElapsed = (System.currentTimeMillis() - retryStarted).toDouble()
        val retryParsed = LiteRtToolCallParser.parseRaw(
            rawResponse = retryRawResponse,
            backend = activeBackendName,
            modelInfoJson = LiteRtRetryPolicy.modelInfoWithAttempts(
                baseModelInfoJson = modelInfo,
                attemptCount = 2,
                firstError = repaired.errorMessage,
                retryError = "",
            ),
            inferenceTimeMs = firstElapsed + retryElapsed,
        )
        val retryRepaired = LiteRtToolResultGuard.validateAndRepair(context, packet, retryParsed)
        if (retryRepaired.success) {
            return@withContext retryRepaired.copy(
                modelInfoJson = LiteRtRetryPolicy.modelInfoWithAttempts(
                    baseModelInfoJson = modelInfo,
                    attemptCount = 2,
                    firstError = repaired.errorMessage,
                    retryError = "",
                ),
                inferenceTimeMs = firstElapsed + retryElapsed,
            )
        }
        retryRepaired.copy(
            errorMessage = LiteRtRetryPolicy.afterRetryError(retryRepaired.errorMessage),
            rawResponse = retryRawResponseForFailure(rawResponse, retryRawResponse),
            modelInfoJson = LiteRtRetryPolicy.modelInfoWithAttempts(
                baseModelInfoJson = modelInfo,
                attemptCount = 2,
                firstError = repaired.errorMessage,
                retryError = retryRepaired.errorMessage,
            ),
            inferenceTimeMs = firstElapsed + retryElapsed,
        )
    }

    override fun close() {
        session?.close()
        session = null
        engine?.close()
        engine = null
    }

    internal fun initAttemptsSnapshot(): List<LiteRtInitAttempt> {
        return initAttempts.toList()
    }

    private suspend fun ensureSession(path: String): Session {
        return initMutex.withLock {
            val existing = session
            if (existing != null && modelPath == path && existing.isAlive) return@withLock existing

            close()
            modelPath = path
            initAttempts.clear()
            val backends = listOf(
                "litert-lm:raw:gpu" to Backend.GPU(),
                "litert-lm:raw:cpu" to Backend.CPU(),
            )
            var lastError: Throwable? = null
            for ((label, backend) in backends) {
                activeBackendName = label
                val started = System.currentTimeMillis()
                var stage = "engine_create"
                var createdEngine: Engine? = null
                try {
                    createdEngine = Engine(
                        EngineConfig(
                            modelPath = path,
                            backend = backend,
                            maxNumTokens = LITERT_MAX_TOKENS,
                            cacheDir = File(app.cacheDir, "litert-lm").absolutePath,
                        )
                    )
                    stage = "engine_initialize"
                    createdEngine.initialize()
                    stage = "session_create"
                    val createdSession = createdEngine.createSession(
                        SessionConfig(
                            SamplerConfig(
                                topK = 1,
                                topP = 0.1,
                                temperature = 0.0,
                                seed = 17,
                            )
                        )
                    )
                    engine = createdEngine
                    session = createdSession
                    initTimeMs = System.currentTimeMillis() - started
                    initAttempts += LiteRtInitAttempt(
                        backend = label,
                        status = "success",
                        stage = "ready",
                        elapsedMs = initTimeMs ?: 0L,
                        errorType = "",
                        errorMessage = "",
                        maxNumTokens = LITERT_MAX_TOKENS,
                        modelSizeBytes = File(path).length(),
                    )
                    return@withLock createdSession
                } catch (e: Throwable) {
                    lastError = e
                    initAttempts += LiteRtInitAttempt(
                        backend = label,
                        status = "failed",
                        stage = stage,
                        elapsedMs = System.currentTimeMillis() - started,
                        errorType = e::class.java.name,
                        errorMessage = e.message ?: "unknown",
                        maxNumTokens = LITERT_MAX_TOKENS,
                        modelSizeBytes = File(path).length(),
                    )
                    runCatching { createdEngine?.close() }
                    close()
                }
            }
            throw IllegalStateException(lastError?.message ?: "litert_lm_init_failed")
        }
    }

    private companion object {
        const val LITERT_TIMEOUT_MS = 60_000L
        const val LITERT_MAX_TOKENS = 4096
    }
}

internal data class LiteRtInitAttempt(
    val backend: String,
    val status: String,
    val stage: String,
    val elapsedMs: Long,
    val errorType: String,
    val errorMessage: String,
    val maxNumTokens: Int,
    val modelSizeBytes: Long,
)

internal data class LiteRtToolCandidate(
    val name: String,
    val arguments: Map<String, Any?>,
)

internal fun parseSafetyJson(safetyJson: String): JSONObject {
    return runCatching { JSONObject(safetyJson) }
        .getOrElse { JSONObject().put("raw", safetyJson) }
}

internal object LiteRtRetryPolicy {
    private val retryableErrors = setOf(
        "litert_lm_json_parse_failed",
        "litert_lm_no_valid_tool_call",
        "thought_leak_detected",
    )

    fun shouldRetry(result: LLMBridge.FunctionCallResult): Boolean {
        return !result.success && result.errorMessage in retryableErrors
    }

    fun afterRetryError(error: String): String {
        val normalized = error.ifBlank { "litert_lm_invalid_output" }
        return if (normalized.endsWith("_after_retry")) normalized else "${normalized}_after_retry"
    }

    fun modelInfoWithAttempts(
        baseModelInfoJson: String,
        attemptCount: Int,
        firstError: String,
        retryError: String,
    ): String {
        val root = runCatching { JSONObject(baseModelInfoJson) }.getOrElse { JSONObject() }
        return root
            .put("attempt_count", attemptCount)
            .put("first_error", firstError)
            .put("retry_error", retryError)
            .toString()
    }
}

private fun retryRawResponseForFailure(firstRaw: String, retryRaw: String): String {
    return JSONObject()
        .put("first_raw_response", firstRaw.take(4000))
        .put("retry_raw_response", retryRaw.take(4000))
        .toString()
}

internal object LiteRtEvidencePromptRenderer {
    fun build(
        context: CoachContext,
        safety: JSONObject,
        reasoningMode: ModelReasoningMode,
    ): String {
        val packet = buildEvidencePacket(context, safety, reasoningMode)
        return buildPrompt(packet)
    }

    fun buildEvidencePacket(
        context: CoachContext,
        safety: JSONObject,
        reasoningMode: ModelReasoningMode,
    ): JSONObject {
        val packet = buildBaseEvidencePacket(context, safety, reasoningMode)
        val requiredFunction = LiteRtOutputContract.requiredFunction(context, packet)
        val requiredArgs = LiteRtOutputContract.requiredArgs(requiredFunction)
        ensureOutputContract(packet, requiredFunction, requiredArgs)
        return packet
    }

    fun buildPrompt(packet: JSONObject): String {
        val outputContract = packet.optJSONObject("output_contract") ?: JSONObject()
        val requiredFunction = outputContract.optString("required_function")
        val requiredArgs = jsonStringList(outputContract.optJSONArray("required_args"))
        return buildString {
            append("<|turn>system\n")
            append(SYSTEM_PROMPT)
            append("\n<|turn>user\n")
            append("Tool contract:\n")
            append(COMPACT_TOOL_CONTRACT_TEXT)
            append("\nRequired output function: ")
            append(requiredFunction)
            append(". Required args: ")
            append(requiredArgs.joinToString(","))
            append(". Begin with {\"function\": and return no prose.\n")
            if (requiredFunction in narrativeFunctions) {
                append(
                    "Narrative style: write participant-friendly observational text inside the required args. " +
                        "Use 2-3 concise sentences across completion, observations, and next focus when evidence supports it. " +
                        "Refer to specific visible evidence, reps, camera limits, or support use only when present in evidence_refs. " +
                        "Do not invent history or sensor data.\n"
                )
            }
            append("E2B evidence packet:\n```json\n")
            append(packet.toString())
            append("\n```\nReturn one JSON function call only.\n")
            append("<|turn>model\n")
        }
    }

    fun buildSessionSummaryPrompt(
        packet: JSONObject,
        includeNarrativePacket: Boolean = ENABLE_SESSION_NARRATIVE_PACKET,
    ): String {
        val promptPacket = buildSessionSummaryPromptPacket(packet, includeNarrativePacket)
        val locale = com.gemmafit.settings.ResolvedLocale.fromTag(
            promptPacket.optString("locale").ifBlank { packet.optString("locale") }
        )
        return buildString {
            append("<|turn>system\n")
            append("You route GemmaFit session summaries as one JSON tool call.")
            append("\n<|turn>user\n")
            append(
                "Call only create_care_activity_log. Return one JSON object starting with {\"function\":. " +
                    "Use only the provided compact packet sections. Cite evidence_refs only. " +
                    "Use warm senior-coach wording in JSON string values; avoid debug terms like monitor, unsupported, blocked feedback, or view limits; say moments to watch and camera-limited moments instead. " +
                    "If rep_summaries exists, cite specific rep numbers in observations and next_focus. Use session_trend for early-vs-late changes and baseline_comparison only when present. " +
                    "Use quality_cues for one observation/focus; add no safety claims. " +
                    "No text outside JSON, no diagnostics, no medical/force/sensor claims.\n"
            )
            // Locale instruction at the END of the user turn so it sits closest
            // to the final-layer global attention window (Gemma 4 hybrid
            // attention puts the final layer on global so end-of-prompt tokens
            // carry the most influence over generation). Critical for keeping
            // the model from defaulting to English when zh-TW is requested.
            append(localeInstructionFor(locale))
            append('\n')
            append(oneShotExampleFor(locale))
            append('\n')
            append(promptPacket.toString())
            append("\nReturn one JSON function call only.\n")
            append("<|turn>model\n")
        }
    }

    /**
     * Locale-specific instruction injected at the end of the user turn. Kept
     * short (~30-50 chars) so it does not eat prefill budget. Defaults to
     * English with no instruction when locale is en-US to avoid spending
     * tokens on the model's native default behavior.
     */
    private fun localeInstructionFor(locale: com.gemmafit.settings.ResolvedLocale): String {
        return when (locale) {
            com.gemmafit.settings.ResolvedLocale.EN_US ->
                "Output language: English."
            com.gemmafit.settings.ResolvedLocale.ZH_TW ->
                "Output language: 繁體中文 (Traditional Chinese, zh-TW). " +
                    "Use natural Taiwanese senior-friendly phrasing inside the JSON string values."
        }
    }

    /**
     * One-shot example pinned to the target locale. Strongly anchors Gemma 4
     * to the desired output language; without this, the multilingual base
     * tends to default to English even when the instruction asks for zh-TW.
     * Each example is intentionally short (~60-80 tokens) — just enough to
     * demonstrate field shape and language without bloating prefill.
     */
    private fun oneShotExampleFor(locale: com.gemmafit.settings.ResolvedLocale): String {
        return when (locale) {
            com.gemmafit.settings.ResolvedLocale.EN_US -> EN_ONE_SHOT_EXAMPLE
            com.gemmafit.settings.ResolvedLocale.ZH_TW -> ZH_TW_ONE_SHOT_EXAMPLE
        }
    }

    private const val EN_ONE_SHOT_EXAMPLE =
        "Example output (en-US):\n" +
            "{\"function\":\"create_care_activity_log\",\"args\":{" +
            "\"headline\":\"Completed 4 supported chair stands\"," +
            "\"observations\":\"Four steady stands; rep 2 looked smoothest.\"," +
            "\"next_focus\":\"Keep the same pace; pause briefly when standing tall.\"," +
            "\"evidence_refs\":[\"metric.session.total_reps\",\"metric.session.duration_seconds\"]" +
            "}}"

    private const val ZH_TW_ONE_SHOT_EXAMPLE =
        "Example output (zh-TW):\n" +
            "{\"function\":\"create_care_activity_log\",\"args\":{" +
            "\"headline\":\"完成 4 次扶椅起立\"," +
            "\"observations\":\"4 次起立都很平穩，第 2 次最流暢。\"," +
            "\"next_focus\":\"保持目前節奏，站直時稍微停一下再坐下。\"," +
            "\"evidence_refs\":[\"metric.session.total_reps\",\"metric.session.duration_seconds\"]" +
            "}}"

    fun buildRetryPrompt(packet: JSONObject, firstError: String): String {
        return buildString {
            append("<|turn>system\n")
            append(SYSTEM_PROMPT)
            append("\n<|turn>user\n")
            append("Previous output was rejected with error: ")
            append(firstError.ifBlank { "invalid_json" })
            append(". Retry once using the exact same E2B evidence packet. ")
            append("Do not add prose, markdown, analysis, or alternative schemas. ")
            append("Return exactly one JSON object whose first key is \"function\".\n")
            append("Tool contract:\n")
            append(COMPACT_TOOL_CONTRACT_TEXT)
            append("\nE2B evidence packet:\n```json\n")
            append(packet.toString())
            append("\n```\nReturn one JSON function call only.\n")
            append("<|turn>model\n")
        }
    }

    private fun buildBaseEvidencePacket(
        context: CoachContext,
        safety: JSONObject,
        reasoningMode: ModelReasoningMode,
    ): JSONObject {
        val packet = JSONObject(safety.toString())
        if (!packet.has("activity_context")) {
            packet.put(
                "activity_context",
                JSONObject()
                    .put("activity_family", if (context.exercise.startsWith("chair_")) "senior_strength" else "general_fitness")
                    .put("task_label", context.exercise.ifBlank { context.pattern })
                    .put("confidence", 0.9)
                    .put("source", JSONArray(listOf("app_context", "pose_sequence"))),
            )
        }
        if (!packet.has("person_tracking_state")) {
            packet.put(
                "person_tracking_state",
                JSONObject()
                    .put("schema_version", "person_tracking_v1")
                    .put("state", "observed")
                    .put("pose_confidence", 0.9)
                    .put("judgment_allowed", true)
                    .put("hard_judgment_allowed", true)
                    .put("reason", "app_context_available"),
            )
        }
        if (!packet.has("motion_feature_window")) {
            packet.put(
                "motion_feature_window",
                JSONObject()
                    .put("schema_version", "motion_feature_window_v1")
                    .put("trigger", safety.optString("trigger", "APP_COACH_EVENT"))
                    .put("source", "app_motion_context")
                    .put("features", JSONObject(context.metrics.mapValues { (_, value) -> value.toDouble() }))
                    .put(
                        "derived_labels",
                        JSONObject()
                            .put("movement_phase", context.movementPhase)
                            .put("pattern", context.pattern)
                            .put("clean_streak_frames", context.cleanStreak)
                            .put("rep_count", context.repCount),
                    )
                    .put(
                        "limits",
                        JSONArray(
                            listOf(
                                "single_camera_pose_only",
                                "no_force_or_grf",
                                "no_joint_moment",
                                "no_medical_risk_prediction",
                            )
                        ),
                    ),
            )
        }
        if (!packet.has("visual_summary")) {
            packet.put(
                "visual_summary",
                JSONObject()
                    .put("scene_cues", JSONArray(listOf("single_person")))
                    .put("visual_assets_available", JSONArray(listOf("pose_overlay")))
                    .put("visual_assets_are_authoritative", false),
            )
        }
        if (!packet.has("capability_contract")) {
            packet.put("capability_contract", capabilityContractFromContext(context))
        }
        if (!packet.has("evidence_ledger")) {
            packet.put("evidence_ledger", evidenceLedgerFrom(context, safety))
        }
        if (reasoningMode != ModelReasoningMode.OFF && !packet.has("reasoning_policy")) {
            packet.put("reasoning_policy", JSONObject(SummaryReasoningPolicy.promptPolicy(reasoningMode)))
        }
        return packet
    }

    private fun capabilityContractFromContext(context: CoachContext): JSONObject {
        return JSONObject()
            .put(
                "can_judge",
                JSONArray(
                    context.evidenceCard.capabilityCanJudge.ifEmpty { context.metrics.keys.toList() }.map { metric ->
                        JSONObject()
                            .put("metric", metric)
                            .put("confidence_ceiling", 0.9)
                            .put("evidence_refs", JSONArray(evidenceRefsForMetric(metric, context)))
                    }
                ),
            )
            .put(
                "cannot_judge",
                JSONArray(
                    context.evidenceCard.capabilityCannotJudge.map { metric ->
                        JSONObject()
                            .put("metric", metric)
                            .put("reason", "outside_app_evidence_boundary")
                            .put("required_evidence", JSONArray(listOf("reliable_pose_or_additional_sensor")))
                            .put("evidence_refs", JSONArray(listOf("capability.$metric.blocked")))
                    }
                ),
            )
    }

    private fun evidenceLedgerFrom(context: CoachContext, safety: JSONObject): JSONArray {
        safety.optJSONArray("evidence_dag_compact")?.let { return it }
        val ledger = JSONArray()
        context.metrics.forEach { (metric, value) ->
            ledger.put(
                JSONObject()
                    .put("id", "metric.${context.exercise.ifBlank { context.pattern }}.$metric")
                    .put("type", "metric")
                    .put("metric", metric)
                    .put("value", value.toDouble())
                    .put("confidence", 0.85)
                    .put("status", "OK")
                    .put("source", "app_motion_context"),
            )
        }
        context.qualityFlags.forEach { flag ->
            ledger.put(
                JSONObject()
                    .put("id", flag.evidenceId.ifBlank { flag.id })
                    .put("type", "metric")
                    .put("metric", flag.id)
                    .put("value", flag.value.toDouble())
                    .put("threshold", flag.threshold.toDouble())
                    .put("confidence", 0.85)
                    .put("status", flag.status)
                    .put("source", "quality_gate"),
            )
        }
        context.evidenceCard.evidenceRefs.forEach { ref ->
            if (!ledgerContains(ledger, ref)) {
                ledger.put(
                    JSONObject()
                        .put("id", ref)
                        .put("type", "evidence_ref")
                        .put("metric", ref.substringAfterLast('.'))
                        .put("confidence", 0.8)
                        .put("status", context.evidenceCard.verdict)
                        .put("source", "evidence_card"),
                )
            }
        }
        return ledger
    }

    private fun ensureOutputContract(
        packet: JSONObject,
        requiredFunction: String,
        requiredArgs: List<String>,
    ) {
        if (packet.has("output_contract")) return
        packet.put(
            "output_contract",
            JSONObject()
                .put("required_function", requiredFunction)
                .put("allowed_function_names", JSONArray(listOf(requiredFunction)))
                .put("required_args", JSONArray(requiredArgs))
                .put("json_only", true)
                .put("first_char", "{")
                .put("first_key", "function")
                .put("do_not_copy_as_args", true),
        )
    }

    private fun evidenceRefsForMetric(metric: String, context: CoachContext): List<String> {
        return context.evidenceCard.evidenceRefs
            .filter { it.contains(metric) }
            .ifEmpty { listOf("metric.${context.exercise.ifBlank { context.pattern }}.$metric") }
    }

    private fun ledgerContains(ledger: JSONArray, ref: String): Boolean {
        for (i in 0 until ledger.length()) {
            if (ledger.optJSONObject(i)?.optString("id") == ref) return true
        }
        return false
    }

    private fun buildSessionSummaryPromptPacket(
        packet: JSONObject,
        includeNarrativePacket: Boolean,
    ): JSONObject {
        val session = packet.optJSONObject("session_summary") ?: JSONObject()
        val outputContract = packet.optJSONObject("output_contract") ?: JSONObject()
        val evidenceRefs = compactEvidenceRefs(packet, limit = 4)
        val compact = JSONObject()
            .put("trigger", packet.optString("trigger", "SESSION_SUMMARY"))
            .put("compressed_session_memory", compressedSessionMemory(session, packet))
            .put("event_index", buildSessionEventIndex(packet, session, evidenceRefs, limit = 4))
            .put("evidence_refs", JSONArray(evidenceRefs))
            .put("output_contract", compactOutputContract(outputContract))
        if (includeNarrativePacket) {
            packet.optJSONObject("coach_narrative_packet")?.let { narrative ->
                compact.put("rep_summaries", narrative.getJSONArray("rep_summaries"))
                compact.put("session_trend", narrative.getJSONObject("session_trend"))
                narrative.optJSONObject("video_quality_cues")?.let {
                    compact.put("quality_cues", compactVideoQualityCues(it))
                }
                narrative.optJSONObject("baseline_comparison")?.let {
                    compact.put("baseline_comparison", it)
                }
            }
        }
        packet.optString("locale").takeIf { it.isNotBlank() }?.let { compact.put("locale", it) }
        return compact
    }

    private fun compactVideoQualityCues(source: JSONObject): JSONObject {
        return JSONObject()
            .apply {
                source.optJSONObject("best_rep")?.let { put("best", compactCueRep(it, includeReason = false)) }
                source.optJSONObject("watch_rep")?.let { put("watch", compactCueRep(it, includeReason = true)) }
                source.optString("primary_focus")
                    .takeIf { it.isNotBlank() }
                    ?.let { put("focus", it) }
            }
    }

    private fun compactCueRep(source: JSONObject, includeReason: Boolean): JSONObject {
        return JSONObject()
            .put("rep", source.optInt("rep"))
            .put("ref", source.optString("evidence_ref"))
            .apply {
                if (includeReason) {
                    source.optString("reason").takeIf { it.isNotBlank() }?.let { put("reason", it) }
                }
            }
    }

    private fun compressedSessionMemory(session: JSONObject, packet: JSONObject): JSONObject {
        val activityContext = packet.optJSONObject("activity_context")
        val visualContext = packet.optJSONObject("visual_context")
        val tracking = packet.optJSONObject("person_tracking_state")
        val activityState = activityContext?.optString("state").orEmpty()
        val activityTaskLabel = activityContext?.optString("task_label").orEmpty()
        val sessionExercise = session.optString("main_exercise")
            .takeUnless { it.isBlank() || it == "unknown" }
            .orEmpty()
        val taskLabel = when (activityState) {
            "ambiguous" -> "ambiguous_activity"
            "locked", "suspect_switch", "calibrating" -> activityTaskLabel.ifBlank { sessionExercise }
            else -> sessionExercise.ifBlank { activityTaskLabel }
        }
            .ifBlank { "unknown_activity" }
        val blockReason = tracking?.optString("block_reason").orEmpty()
            .ifBlank { tracking?.optString("reason").orEmpty() }
        return JSONObject()
            .put("activity", taskLabel)
            .put("duration_sec", session.optInt("duration_seconds", 0))
            .put("completed_reps", session.optInt("total_reps", 0))
            .put("safety_events", session.optInt("safety_events", 0))
            .put("view_limited_count", session.optInt("view_limited_count", 0))
            .put("person_state", tracking?.optString("state", "observed") ?: "observed")
            .put("judgment_allowed", tracking?.optBoolean("judgment_allowed", true) ?: true)
            .put("block_reason", blockReason)
            .apply {
                if (activityState.isNotBlank()) {
                    put("activity_context_state", activityState)
                }
                if (activityState.isNotBlank() && activityTaskLabel.isNotBlank() && activityState != "ambiguous") {
                    put("activity_context_label", activityTaskLabel)
                }
                activityContext?.optString("ambiguity_note")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { put("activity_context_note", it) }
                visualContext?.optString("env")
                    ?.takeIf { it.isNotBlank() && it != "unknown" }
                    ?.let { put("visual_env", it) }
                visualContext?.optString("support")
                    ?.takeIf { it.isNotBlank() && it != "unknown" }
                    ?.let { put("visual_support", it) }
                visualContext?.optString("person")
                    ?.takeIf { it.isNotBlank() && it != "unknown" }
                    ?.let { put("visual_person", it) }
                if (visualContext != null && !visualContext.isNull("overlay_readable")) {
                    put("visual_overlay_readable", visualContext.optBoolean("overlay_readable"))
                }
                if (visualContext != null && !visualContext.isNull("limited")) {
                    put("visual_limited", visualContext.optBoolean("limited"))
                }
            }
    }

    private fun buildSessionEventIndex(
        packet: JSONObject,
        session: JSONObject,
        evidenceRefs: List<String>,
        limit: Int,
    ): JSONArray {
        val events = mutableListOf<JSONObject>()
        val topWarnings = session.optJSONArray("top_warnings")
        if (topWarnings != null) {
            val warnings = (0 until topWarnings.length())
                .mapNotNull { topWarnings.optJSONObject(it) }
                .sortedBy { severityRank(it.optString("severity")) }
            warnings.firstOrNull()?.let { warning ->
                val function = warning.optString("function").ifBlank { "safety_event" }
                addSessionEvent(
                    events = events,
                    condition = true,
                    kind = "safety_event",
                    severity = warning.optString("severity", "medium"),
                    evidenceRef = eventRefFor(function, evidenceRefs, fallback = "metric.session.safety_events"),
                    limit = limit,
                )
            }
        }
        addSessionEvent(
            events = events,
            condition = session.optInt("safety_events", 0) > 0,
            kind = "safety_event",
            severity = "high",
            evidenceRef = eventRefFor("safety_events", evidenceRefs, fallback = "metric.session.safety_events"),
            limit = limit,
        )
        addSessionEvent(
            events = events,
            condition = session.optInt("view_limited_count", 0) > 0,
            kind = "view_limited",
            severity = "monitor",
            evidenceRef = eventRefFor("view_limited", evidenceRefs, fallback = "metric.session.view_limited_count"),
            limit = limit,
        )
        addSessionEvent(
            events = events,
            condition = session.optInt("low_confidence_count", 0) > 0,
            kind = "low_confidence",
            severity = "monitor",
            evidenceRef = eventRefFor("low_confidence", evidenceRefs, fallback = "metric.session.low_confidence_count"),
            limit = limit,
        )
        val cannotJudge = packet.optJSONObject("capability_contract")?.optJSONArray("cannot_judge")
        if (cannotJudge != null) {
            for (i in 0 until cannotJudge.length()) {
                if (events.size >= limit) break
                val item = cannotJudge.optJSONObject(i) ?: continue
                val category = categorizeCannotJudge(item.optString("metric"), item.optString("reason"))
                if (events.any { it.optString("kind") == category }) continue
                val refs = jsonStringList(item.optJSONArray("evidence_refs"))
                val ref = refs.firstOrNull { it in evidenceRefs }
                    ?: refs.firstOrNull()
                    ?: eventRefFor(category, evidenceRefs, fallback = "metric.session.duration_seconds")
                events.add(
                    JSONObject()
                        .put("kind", category)
                        .put("severity", "blocked")
                        .put("evidence_ref", ref)
                )
            }
        }
        if (events.isEmpty()) {
            events.add(
                JSONObject()
                    .put("kind", "session_completed")
                    .put("severity", "info")
                    .put("evidence_ref", eventRefFor("duration_seconds", evidenceRefs, fallback = "metric.session.duration_seconds"))
            )
        }
        return JSONArray(events.take(limit))
    }

    private fun addSessionEvent(
        events: MutableList<JSONObject>,
        condition: Boolean,
        kind: String,
        severity: String,
        evidenceRef: String,
        limit: Int,
    ) {
        if (!condition || events.size >= limit) return
        if (events.any { it.optString("kind") == kind }) return
        events.add(
            JSONObject()
                .put("kind", kind)
                .put("severity", severity)
                .put("evidence_ref", evidenceRef)
        )
    }

    private fun eventRefFor(
        key: String,
        evidenceRefs: List<String>,
        fallback: String,
    ): String {
        return evidenceRefs.firstOrNull { it.contains(key) }
            ?: evidenceRefs.firstOrNull { it == fallback }
            ?: evidenceRefs.firstOrNull()
            ?: fallback
    }

    private fun severityRank(severity: String): Int {
        return when (severity.lowercase()) {
            "critical", "high" -> 0
            "warning", "medium" -> 1
            "low" -> 2
            else -> 3
        }
    }

    private fun categorizeCannotJudge(metric: String, reason: String): String {
        val text = "$metric $reason".lowercase()
        return when {
            listOf("force", "grf", "moment", "emg", "muscle", "heart", "sensor", "fall", "sarcopenia", "diagnosis", "clinical", "rehab")
                .any { it in text } -> "medical_or_force_or_sensor"
            "view" in text || "camera" in text || "occlusion" in text -> "view_limited"
            "confidence" in text || "visibility" in text -> "low_confidence"
            else -> "not_supported"
        }
    }

    private fun compactEvidenceRefs(packet: JSONObject, limit: Int): List<String> {
        val available = LinkedHashSet<String>()
        jsonStringList(packet.optJSONArray("evidence_refs")).forEach { if (it.isNotBlank()) available.add(it) }
        val ledger = packet.optJSONArray("evidence_ledger")
        if (ledger != null) {
            for (i in 0 until ledger.length()) {
                ledger.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { available.add(it) }
            }
        }
        packet.optJSONObject("capability_contract")?.let { contract ->
            listOf("can_judge", "cannot_judge").forEach { key ->
                val arr = contract.optJSONArray(key) ?: return@forEach
                for (i in 0 until arr.length()) {
                    jsonStringList(arr.optJSONObject(i)?.optJSONArray("evidence_refs")).forEach { ref ->
                        if (ref.isNotBlank()) available.add(ref)
                    }
                }
            }
        }
        val refs = LinkedHashSet<String>()
        listOf(
            "metric.session.duration_seconds",
            "metric.session.total_reps",
            "metric.session.view_limited_count",
            "metric.session.safety_events",
        ).forEach { preferred ->
            if (preferred in available) refs.add(preferred)
        }
        available
            .filter { it.startsWith("activity_context.") }
            .forEach { refs.add(it) }
        available
            .filter { it.startsWith("visual_context.") }
            .forEach { refs.add(it) }
        available
            .filter { it.startsWith("gate.view_limited.") || it.startsWith("gate.warning.") || it.startsWith("gate.critical.") }
            .forEach { refs.add(it) }
        available.forEach { refs.add(it) }
        return refs.take(limit).ifEmpty { listOf("metric.session.duration_seconds") }
    }

    private fun compactOutputContract(source: JSONObject): JSONObject {
        return JSONObject()
            .put("function", "create_care_activity_log")
            .put(
                "required_args",
                JSONArray(
                    listOf(
                        "headline",
                        "observations",
                        "next_session_focus",
                        "evidence_refs",
                    ),
                ),
            )
            .put("boundary", "json_only_evidence_refs_no_medical_force_sensor_claims")
    }

    private val COMPACT_TOOL_CONTRACT_TEXT: String
        get() = "Allowed function names: " +
            LiteRtToolCallParser.allowed.sorted().joinToString(", ") +
            "\nNever use input section names as function names: " +
            LiteRtOutputContract.neverFunctionNames.joinToString(", ") +
            "\nUse output_contract.required_function exactly. Include every " +
            "output_contract.required_args key in args. Cite only evidence_refs that " +
            "exist in the evidence packet. Return one JSON object only."

    private const val SYSTEM_PROMPT =
        "You are GemmaFit's local E2B evidence router. Return exactly one JSON " +
            "object with schema {\"function\":\"...\",\"args\":{...}}. Use only " +
            "app-provided activity_context, person_tracking_state, motion_feature_window, " +
            "visual_summary, capability_contract, evidence_ledger, subjective self-report, " +
            "and memory aggregates. Do not make unsupported health, sensor, prognosis, " +
            "recovery, force, or muscle-measurement claims. If evidence is low confidence, " +
            "predicted-only, lost, missing, or outside capability_contract, call " +
            "refuse_unsupported_question or use boundary wording. Cite only evidence_refs " +
            "that exist in the input. Keep blocked category names only in reason enums and " +
            "evidence_refs; do not repeat those names in user-facing text fields. " +
            "The function name must be one of the allowed tools listed in the user " +
            "message. Input section names such as capability_contract, activity_context, " +
            "motion_feature_window, evidence_ledger, person_tracking_state, visual_summary, " +
            "phase_context, output_contract, and router_contract are never valid function names. " +
            "If output_contract.required_function is present, use that function exactly. " +
            "For create_care_activity_log and create_persona_activity_report, compose natural " +
            "observational narrative inside the JSON string fields while staying evidence-bound."

    private const val SESSION_SUMMARY_SYSTEM_PROMPT =
        "Return exactly one JSON tool call for GemmaFit's non-diagnostic care log. " +
            "Use only the compact evidence packet. No diagnosis, fall-risk prediction, " +
            "force, heart-rate, or clinical-progress claims. Cite packet evidence_refs only."

    private const val SESSION_SUMMARY_TOOL_CONTRACT_TEXT =
        "Allowed function names: create_care_activity_log\n" +
            "Args: headline, what_was_completed, observations, not_judged, next_session_focus, " +
            "caregiver_note, evidence_refs, selection_basis."

    private val narrativeFunctions = setOf(
        "create_care_activity_log",
        "create_persona_activity_report",
    )

    private const val ENABLE_SESSION_NARRATIVE_PACKET = true
}

internal object LiteRtOutputContract {
    val neverFunctionNames = listOf(
        "activity_context",
        "capability_contract",
        "evidence_ledger",
        "motion_context",
        "motion_feature_window",
        "output_contract",
        "person_tracking_state",
        "phase_context",
        "router_contract",
        "visual_summary",
    )

    private val requiredArgsByFunction = mapOf(
        "ask_subjective_checkin" to listOf("prompt_keys", "input_modes", "response_schema", "evidence_refs"),
        "correct_asymmetry" to listOf("joint", "left", "right", "evidence_refs", "selection_basis"),
        "correct_joint_angle" to listOf("joint", "current", "safe_range", "evidence_refs", "selection_basis"),
        "correct_knee_alignment" to listOf("side", "ratio", "severity", "evidence_refs", "selection_basis"),
        "correct_spinal_alignment" to listOf("deviation", "region", "evidence_refs", "selection_basis"),
        "create_care_activity_log" to listOf(
            "headline",
            "what_was_completed",
            "observations",
            "not_judged",
            "next_session_focus",
            "caregiver_note",
            "evidence_refs",
            "selection_basis",
        ),
        "create_persona_activity_report" to listOf(
            "persona",
            "report_text",
            "objective_evidence_refs",
            "subjective_evidence_refs",
            "boundary_note",
            "selection_basis",
        ),
        "increase_range_of_motion" to listOf("joint", "current", "target", "evidence_refs", "selection_basis"),
        "positive_reinforcement" to listOf("pattern", "streak", "evidence_refs", "selection_basis"),
        "read_memory" to listOf("scope"),
        "record_dual_task_result" to listOf(
            "prompt_id",
            "response_mode",
            "answer_matched",
            "movement_completed",
            "evidence_refs",
        ),
        "record_subjective_checkin" to listOf(
            "rpe_0_10",
            "breathlessness",
            "leg_soreness",
            "needed_rest",
            "discomfort_reported",
            "evidence_refs",
        ),
        "refuse_unsupported_question" to listOf(
            "reason",
            "safe_alternative",
            "selection_basis",
            "evidence_refs",
            "refusal_level",
        ),
        "request_memory_update" to listOf(
            "request_id",
            "type",
            "proposed_value",
            "evidence_ids",
            "confidence",
            "evidence_refs",
        ),
        "select_dual_task_prompt" to listOf(
            "prompt_text_key",
            "expected_response_modes",
            "expected_movement",
            "evidence_refs",
        ),
        "summarize_trend" to listOf("scope", "exercise", "focus", "evidence_refs"),
        "warn_com_offset" to listOf("direction", "distance", "evidence_refs", "selection_basis"),
        "warn_rapid_movement" to listOf("joint", "velocity", "evidence_refs", "selection_basis"),
    )

    fun requiredArgs(functionName: String): List<String> {
        return requiredArgsByFunction[functionName].orEmpty()
    }

    fun requiredFunction(context: CoachContext, safety: JSONObject): String {
        if (requiresRefusal(context, safety)) return "refuse_unsupported_question"
        safety.optJSONObject("output_contract")
            ?.optString("required_function")
            ?.takeIf { it in LiteRtToolCallParser.allowed }
            ?.let { return it }
        safety.optJSONObject("router_contract")
            ?.optString("required_function")
            ?.takeIf { it in LiteRtToolCallParser.allowed }
            ?.let { return it }
        context.warnings.firstOrNull { it.functionName in LiteRtToolCallParser.allowed }
            ?.let { return it.functionName }
        safetyFunctionFromEvidence(safety)?.let { return it }
        val priorityFlag = context.qualityFlags.firstOrNull { it.status in setOf("CRITICAL", "WARNING") }
        return when (priorityFlag?.rule) {
            1 -> "correct_knee_alignment"
            2 -> "correct_spinal_alignment"
            3 -> "correct_joint_angle"
            4 -> "correct_asymmetry"
            5 -> "warn_com_offset"
            6 -> "warn_rapid_movement"
            7 -> "increase_range_of_motion"
            8 -> "correct_spinal_alignment"
            else -> "positive_reinforcement"
        }
    }

    fun requiresRefusal(context: CoachContext, safety: JSONObject): Boolean {
        val policy = safety.optString("required_response_policy").lowercase()
        if ("refuse" in policy || "unsupported" in policy) return true
        if (context.evidenceCard.verdict in setOf("NOT_SUPPORTED", "LOW_CONFIDENCE", "VIEW_LIMITED")) return true
        val tracking = safety.optJSONObject("person_tracking_state")
        if (tracking != null) {
            val judgmentAllowed = tracking.optBoolean("judgment_allowed", true)
            val hardJudgmentAllowed = if (tracking.has("hard_judgment_allowed")) {
                tracking.optBoolean("hard_judgment_allowed", true)
            } else {
                true
            }
            val state = tracking.optString("state").lowercase()
            if (!judgmentAllowed || !hardJudgmentAllowed || state != "observed") {
                return true
            }
        }
        val question = safety.optString("user_question").lowercase()
        if (question.isBlank()) return false
        val blockedTerms = listOf(
            "fall risk",
            "fall-risk",
            "sarcopenia",
            "diagnosis",
            "diagnose",
            "injury",
            "rehab",
            "rehabilitation",
            "clinical",
            "heart rate",
            "muscle activation",
            "force",
        )
        return blockedTerms.any { it in question }
    }

    private fun safetyFunctionFromEvidence(safety: JSONObject): String? {
        val ledger = safety.optJSONArray("evidence_ledger") ?: safety.optJSONArray("evidence_dag_compact") ?: return null
        for (i in 0 until ledger.length()) {
            val node = ledger.optJSONObject(i) ?: continue
            val status = node.optString("status")
            val metric = node.optString("metric")
            if (status !in setOf("WARNING", "CRITICAL")) continue
            if ("velocity" in metric) return "warn_rapid_movement"
            if ("knee" in metric && "alignment" in metric) return "correct_knee_alignment"
            if ("spine" in metric || "trunk" in metric || "neck" in metric) return "correct_spinal_alignment"
            if ("rom" in metric || "range" in metric) return "increase_range_of_motion"
        }
        return null
    }
}

internal object LiteRtToolResultGuard {
    fun validateAndRepair(
        context: CoachContext,
        safety: JSONObject,
        result: LLMBridge.FunctionCallResult,
    ): LLMBridge.FunctionCallResult {
        if (!result.success) return result
        val requiredFunction = LiteRtOutputContract.requiredFunction(context, safety)
        val functionName = if (requiredFunction in LiteRtToolCallParser.allowed) {
            requiredFunction
        } else {
            result.functionName
        }
        val modelArgs = runCatching { JSONObject(result.argsJson) }.getOrElse { JSONObject() }
        val repairedArgs = LiteRtArgsCompiler.compile(functionName, modelArgs, context, safety)
        val evidenceRefs = jsonStringList(repairedArgs.optJSONArray("evidence_refs"))
        return result.copy(
            functionName = functionName,
            argsJson = repairedArgs.toString(),
            selectionBasis = repairedArgs.optString("selection_basis", result.selectionBasis),
            evidenceRefs = evidenceRefs.ifEmpty { result.evidenceRefs },
            errorMessage = "",
        )
    }
}

internal object LiteRtRealtimeFastPath {
    private const val BACKEND_NAME = "litert-lm:realtime_fast"

    private val realtimeFunctions = setOf(
        "correct_knee_alignment",
        "correct_spinal_alignment",
        "correct_joint_angle",
        "correct_asymmetry",
        "warn_com_offset",
        "warn_rapid_movement",
        "increase_range_of_motion",
    )

    fun maybeHandle(
        context: CoachContext,
        safety: JSONObject,
        reasoningMode: ModelReasoningMode,
        modelPath: String,
    ): LLMBridge.FunctionCallResult? {
        if (reasoningMode != ModelReasoningMode.OFF) return null

        val functionName = LiteRtOutputContract.requiredFunction(context, safety)
        val isRealtimeSafetyCue = functionName in realtimeFunctions
        val isBoundaryRefusal = functionName == "refuse_unsupported_question" &&
            LiteRtOutputContract.requiresRefusal(context, safety)
        if (!isRealtimeSafetyCue && !isBoundaryRefusal) return null

        val args = LiteRtArgsCompiler.compile(
            functionName = functionName,
            modelArgs = JSONObject(),
            context = context,
            safety = safety,
        )
        val evidenceRefs = jsonStringList(args.optJSONArray("evidence_refs"))
        return LLMBridge.FunctionCallResult(
            success = true,
            functionName = functionName,
            argsJson = args.toString(),
            backend = BACKEND_NAME,
            selectionBasis = args.optString("selection_basis", "Deterministic realtime evidence path."),
            evidenceRefs = evidenceRefs,
            modelInfoJson = liteRtRealtimeFastModelInfoJson(modelPath, functionName),
            rawResponse = JSONObject()
                .put("skipped_litert_generation", true)
                .put("function", functionName)
                .put("args", args)
                .toString(),
            inferenceTimeMs = 0.0,
            errorMessage = "",
        )
    }
}

internal object LiteRtArgsCompiler {
    fun compile(
        functionName: String,
        modelArgs: JSONObject,
        context: CoachContext,
        safety: JSONObject,
    ): JSONObject {
        val args = JSONObject(modelArgs.toString())
        val requiredArgs = LiteRtOutputContract.requiredArgs(functionName)
        val refs = validEvidenceRefs(args.optJSONArray("evidence_refs"), context, safety)
        when (functionName) {
            "warn_rapid_movement" -> compileRapidMovement(args, context, refs)
            "positive_reinforcement" -> compilePositive(args, context, refs)
            "refuse_unsupported_question" -> compileRefusal(args, context, safety, refs)
            "correct_knee_alignment" -> compileKneeAlignment(args, context, refs)
            "create_care_activity_log" -> compileCareActivityLog(args, context, safety, refs)
        }
        requiredArgs.forEach { key ->
            if (!args.has(key) || args.isNull(key)) {
                args.put(key, defaultArgValue(key, functionName, context, refs))
            }
        }
        if (requiredArgs.contains("evidence_refs") || args.has("evidence_refs")) {
            args.put("evidence_refs", JSONArray(refs))
        }
        if (!args.has("selection_basis")) {
            args.put("selection_basis", selectionBasisFor(functionName, context))
        }
        return args
    }

    private fun compileRapidMovement(args: JSONObject, context: CoachContext, refs: List<String>) {
        val joint = context.warnings.firstOrNull()?.joint.orEmpty()
            .ifBlank { context.qualityFlags.firstOrNull { it.rule == 6 }?.joint.orEmpty() }
            .ifBlank { args.optString("joint") }
            .ifBlank { "knee" }
        val velocity = context.metrics["${joint}_peak_velocity_deg_s"]?.toDouble()
            ?: context.metrics["knee_peak_velocity_deg_s"]?.toDouble()
            ?: context.qualityFlags.firstOrNull { it.rule == 6 }?.value?.toDouble()
            ?: numericArg(args, "velocity")
            ?: 0.0
        args.put("joint", joint)
        args.put("velocity", velocity)
        args.put("evidence_refs", JSONArray(refs.ifEmpty { listOf("metric.${context.exercise}.${joint}_velocity") }))
        args.put("selection_basis", selectionBasisFor("warn_rapid_movement", context))
    }

    private fun compilePositive(args: JSONObject, context: CoachContext, refs: List<String>) {
        args.put("pattern", context.pattern.ifBlank { context.exercise.ifBlank { args.optString("pattern") } })
        args.put("streak", context.cleanStreak.coerceAtLeast(1))
        if (!args.has("primary_muscles") && context.muscle?.primary?.isNotEmpty() == true) {
            args.put("primary_muscles", JSONArray(context.muscle.primary))
        }
        if (!args.has("coach_cue")) args.put("coach_cue", "Repeat that same tempo.")
        args.put("evidence_refs", JSONArray(refs))
        args.put("selection_basis", "Clean evidence window with no active safety warning.")
    }

    private fun compileRefusal(
        args: JSONObject,
        context: CoachContext,
        safety: JSONObject,
        refs: List<String>,
    ) {
        args.put("reason", refusalReason(safety))
        args.put(
            "safe_alternative",
            args.optString("safe_alternative").ifBlank {
                "I can summarize visible movement quality and camera-limited evidence only."
            },
        )
        args.put(
            "selection_basis",
            "The request is outside the app evidence boundary for pose-only movement feedback.",
        )
        args.put("evidence_refs", JSONArray(refs.ifEmpty { context.evidenceCard.evidenceRefs }))
        args.put("refusal_level", 4)
    }

    private fun compileKneeAlignment(args: JSONObject, context: CoachContext, refs: List<String>) {
        args.put("side", args.optString("side").ifBlank { "bilateral" })
        args.put("ratio", numericArg(args, "ratio") ?: context.metrics["knee_alignment_ratio"]?.toDouble() ?: 0.0)
        args.put("severity", args.optString("severity").ifBlank { "moderate" })
        args.put("evidence_refs", JSONArray(refs))
        args.put("selection_basis", selectionBasisFor("correct_knee_alignment", context))
    }

    private fun compileCareActivityLog(
        args: JSONObject,
        context: CoachContext,
        safety: JSONObject,
        refs: List<String>,
    ) {
        val session = safety.optJSONObject("session_summary") ?: JSONObject()
        val activity = displayActivityLabel(
            session.optString("main_exercise")
                .ifBlank { session.optString("activity") }
                .ifBlank { context.exercise }
                .ifBlank { context.pattern }
                .ifBlank { "movement practice" },
        )
        val durationSec = session.optInt("duration_seconds", 0).coerceAtLeast(0)
        val reps = session.optInt("total_reps", context.repCount).coerceAtLeast(0)
        if (!args.has("headline") || args.optString("headline").isBlank()) {
            args.put("headline", "${activity.replaceFirstChar { it.uppercase() }} summary")
        }
        if (!args.has("what_was_completed") || args.optString("what_was_completed").isBlank()) {
            args.put("what_was_completed", completedCareLogText(activity, durationSec, reps))
        }
        if (!args.has("observations") || args.optString("observations").isBlank()) {
            args.put("observations", observationFallback(session))
        }
        if (!args.has("next_session_focus") && args.optString("next_focus").isNotBlank()) {
            args.put("next_session_focus", args.optString("next_focus"))
        }
        if (!args.has("next_session_focus") || args.optString("next_session_focus").isBlank()) {
            args.put("next_session_focus", "Keep the next set slow, supported, and easy to see in the camera view.")
        }
        val eventKinds = careLogEventKinds(safety)
        if (!args.has("not_judged") || args.optString("not_judged").isBlank()) {
            args.put("not_judged", careLogNotJudged(eventKinds))
        }
        if (!args.has("caregiver_note") || args.optString("caregiver_note").isBlank()) {
            args.put("caregiver_note", "Structured activity log only; not a health assessment.")
        }
        if (!args.has("selection_basis") || args.optString("selection_basis").isBlank()) {
            args.put("selection_basis", careLogSelectionBasis(eventKinds, refs))
        }
        args.put("evidence_refs", JSONArray(refs))
    }

    private fun completedCareLogText(activity: String, durationSec: Int, reps: Int): String {
        val details = buildList {
            if (durationSec > 0) add("for ${durationSec}s")
            if (reps > 0) add("with $reps reps")
        }
        return if (details.isEmpty()) {
            "Completed $activity."
        } else {
            "Completed $activity ${details.joinToString(" ")}."
        }
    }

    private fun observationFallback(session: JSONObject): String {
        val safetyEvents = session.optInt("safety_events", 0)
        val viewLimited = session.optInt("view_limited_count", 0)
        return when {
            safetyEvents > 0 && viewLimited > 0 ->
                "Visible movement evidence included $safetyEvents safety-monitor event(s), with $viewLimited view-limited sample(s)."
            safetyEvents > 0 ->
                "Visible movement evidence included $safetyEvents safety-monitor event(s)."
            viewLimited > 0 ->
                "Some movement samples were view-limited, so the summary stays conservative."
            else ->
                "Visible movement evidence was summarized from the completed session."
        }
    }

    private fun careLogEventKinds(safety: JSONObject): List<String> {
        val kinds = linkedSetOf<String>()
        val eventIndex = safety.optJSONArray("event_index")
        if (eventIndex != null) {
            for (i in 0 until eventIndex.length()) {
                eventIndex.optJSONObject(i)?.optString("kind")?.takeIf { it.isNotBlank() }?.let { kinds += it }
            }
        }
        val session = safety.optJSONObject("session_summary") ?: JSONObject()
        if (session.optInt("safety_events", 0) > 0) kinds += "safety_event"
        if (session.optInt("view_limited_count", 0) > 0) kinds += "view_limited"
        if (session.optInt("low_confidence_count", 0) > 0) kinds += "low_confidence"
        val cannotJudge = safety.optJSONObject("capability_contract")?.optJSONArray("cannot_judge")
        if (cannotJudge != null) {
            for (i in 0 until cannotJudge.length()) {
                val text = listOf(
                    cannotJudge.optJSONObject(i)?.optString("metric").orEmpty(),
                    cannotJudge.optJSONObject(i)?.optString("reason").orEmpty(),
                ).joinToString(" ")
                categorizeCannotJudge(text)?.let { kinds += it }
            }
        }
        return kinds.toList()
    }

    private fun careLogNotJudged(eventKinds: List<String>): String {
        val notes = linkedSetOf<String>()
        if ("view_limited" in eventKinds) {
            notes += "Some camera-limited samples were not used for hard judgment."
        }
        if ("low_confidence" in eventKinds) {
            notes += "Low-confidence pose samples were not used for hard judgment."
        }
        if ("medical_or_force_or_sensor" in eventKinds) {
            notes += "This does not assess fall risk, sarcopenia, rehabilitation progress, heart rate, force, or clinical status."
        }
        if (notes.isEmpty()) {
            notes += "This is movement-quality feedback only, not a medical or sensor-based assessment."
        }
        return notes.joinToString(" ")
    }

    private fun careLogSelectionBasis(eventKinds: List<String>, refs: List<String>): String {
        val primary = eventKinds.firstOrNull()?.replace('_', ' ') ?: "compact session evidence"
        val ref = refs.firstOrNull()?.let { " using $it" }.orEmpty()
        return "Summarized from $primary$ref."
    }

    private fun categorizeCannotJudge(text: String): String? {
        val lower = text.lowercase()
        return when {
            listOf("medical", "diagnosis", "fall", "force", "emg", "heart", "sensor", "muscle activation")
                .any { it in lower } -> "medical_or_force_or_sensor"
            "view" in lower || "visible" in lower || "camera" in lower -> "view_limited"
            "confidence" in lower || "low_conf" in lower -> "low_confidence"
            else -> null
        }
    }

    private fun displayActivityLabel(raw: String): String {
        return raw
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { "movement practice" }
    }

    private fun defaultArgValue(
        key: String,
        functionName: String,
        context: CoachContext,
        refs: List<String>,
    ): Any {
        return when (key) {
            "evidence_refs", "objective_evidence_refs", "subjective_evidence_refs", "evidence_ids" -> JSONArray(refs)
            "selection_basis" -> selectionBasisFor(functionName, context)
            "joint" -> context.warnings.firstOrNull()?.joint ?: context.qualityFlags.firstOrNull()?.joint ?: "joint"
            "velocity", "ratio", "deviation", "current", "left", "right", "distance", "target", "confidence" -> 0.0
            "streak" -> context.cleanStreak.coerceAtLeast(1)
            "refusal_level" -> 4
            "pattern", "exercise" -> context.pattern.ifBlank { context.exercise }
            "safe_range" -> "supported movement range"
            "safe_alternative" -> "Use visible movement-quality evidence only."
            "reason" -> "insufficient_evidence"
            "scope" -> "EVIDENCE_FOR_SESSION"
            "type" -> "TREND_NOTE"
            "proposed_value" -> JSONObject()
            "prompt_keys", "input_modes", "expected_response_modes" -> JSONArray()
            "response_schema" -> JSONObject()
            "answer_matched", "movement_completed", "needed_rest", "discomfort_reported" -> false
            else -> ""
        }
    }

    private fun validEvidenceRefs(
        modelRefs: JSONArray?,
        context: CoachContext,
        safety: JSONObject,
    ): List<String> {
        val allowed = evidenceRefsFrom(context, safety)
        val fromModel = jsonStringList(modelRefs).filter { allowed.isEmpty() || it in allowed }
        return fromModel.ifEmpty { allowed.take(6) }
    }

    private fun evidenceRefsFrom(context: CoachContext, safety: JSONObject): List<String> {
        val refs = linkedSetOf<String>()
        collectLedgerRefs(safety.optJSONArray("evidence_ledger"), refs)
        collectLedgerRefs(safety.optJSONArray("evidence_dag_compact"), refs)
        collectCapabilityRefs(safety.optJSONObject("capability_contract"), refs)
        refs += context.evidenceCard.evidenceRefs
        refs += context.qualityFlags.map { flag -> flag.evidenceId.ifBlank { flag.id } }
        if (refs.isEmpty()) refs += context.metrics.keys.map { "metric.${context.exercise}.$it" }
        return refs.filter { it.isNotBlank() }
    }

    private fun collectLedgerRefs(arr: JSONArray?, refs: MutableSet<String>) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { refs += it }
        }
    }

    private fun collectCapabilityRefs(contract: JSONObject?, refs: MutableSet<String>) {
        if (contract == null) return
        listOf("can_judge", "cannot_judge").forEach { key ->
            val arr = contract.optJSONArray(key) ?: return@forEach
            for (i in 0 until arr.length()) {
                val itemRefs = arr.optJSONObject(i)?.optJSONArray("evidence_refs") ?: continue
                refs += jsonStringList(itemRefs)
            }
        }
    }

    private fun numericArg(args: JSONObject, key: String): Double? {
        if (!args.has(key) || args.isNull(key)) return null
        val value = args.opt(key)
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun refusalReason(safety: JSONObject): String {
        val text = listOf(
            safety.optString("user_question"),
            safety.optString("required_response_policy"),
        ).joinToString(" ").lowercase()
        return when {
            "fall" in text -> "fall_risk_prediction"
            "sarcopenia" in text -> "sarcopenia_detection"
            "injury" in text -> "injury_prediction"
            "heart" in text -> "heart_rate_status"
            "rehab" in text -> "rehabilitation_prescription"
            "force" in text || "activation" in text -> "force_or_emg_claim"
            "diagnos" in text || "medical" in text -> "medical_diagnosis"
            else -> "insufficient_evidence"
        }
    }

    private fun selectionBasisFor(functionName: String, context: CoachContext): String {
        return when (functionName) {
            "positive_reinforcement" -> "Clean evidence window with no active safety warning."
            "warn_rapid_movement" -> context.warnings.firstOrNull()?.message
                ?: context.qualityFlags.firstOrNull { it.rule == 6 }?.reason
                ?: "Rapid-movement evidence crossed the controlled-tempo gate."
            "refuse_unsupported_question" -> "The request is outside the app evidence boundary."
            else -> context.warnings.firstOrNull()?.message
                ?: context.qualityFlags.firstOrNull()?.reason
                ?: "Highest-priority evidence flag selected."
        }
    }
}

private fun jsonStringList(arr: JSONArray?): List<String> {
    if (arr == null) return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}

internal object LiteRtToolCallParser {
    internal val allowed = setOf(
        "correct_knee_alignment",
        "correct_spinal_alignment",
        "correct_joint_angle",
        "correct_asymmetry",
        "warn_com_offset",
        "warn_rapid_movement",
        "increase_range_of_motion",
        "positive_reinforcement",
        "ask_subjective_checkin",
        "record_subjective_checkin",
        "create_persona_activity_report",
        "read_memory",
        "request_memory_update",
        "summarize_trend",
        "refuse_unsupported_question",
        "create_care_activity_log",
        "select_dual_task_prompt",
        "record_dual_task_result",
    )

    fun parse(
        candidates: List<LiteRtToolCandidate>,
        backend: String,
        modelInfoJson: String,
        rawResponse: String,
        inferenceTimeMs: Double,
    ): LLMBridge.FunctionCallResult {
        if (hasThoughtLeak(rawResponse)) {
            return liteRtUnavailable(
                backend = backend,
                error = "thought_leak_detected",
                modelInfo = modelInfoJson,
                rawResponse = rawResponse,
            )
        }
        val selected = candidates.firstOrNull { it.name in allowed }
            ?: return liteRtUnavailable(
                backend = backend,
                error = "litert_lm_no_valid_tool_call",
                modelInfo = modelInfoJson,
                rawResponse = rawResponse,
            )
        val args = JSONObject(selected.arguments)
        val refs = parseEvidenceRefs(args.optJSONArray("evidence_refs"))
        return LLMBridge.FunctionCallResult(
            success = true,
            functionName = selected.name,
            argsJson = args.toString(),
            backend = backend,
            selectionBasis = args.optString("selection_basis", "LiteRT-LM selected ${selected.name} from evidence."),
            evidenceRefs = refs,
            modelInfoJson = modelInfoJson,
            rawResponse = rawResponse,
            inferenceTimeMs = inferenceTimeMs,
            errorMessage = "",
        )
    }

    fun parseRaw(
        rawResponse: String,
        backend: String,
        modelInfoJson: String,
        inferenceTimeMs: Double,
    ): LLMBridge.FunctionCallResult {
        if (hasThoughtLeak(rawResponse)) {
            return liteRtUnavailable(
                backend = backend,
                error = "thought_leak_detected",
                modelInfo = modelInfoJson,
                rawResponse = rawResponse,
            )
        }
        val obj = parseFirstJsonObject(rawResponse)
            ?: return liteRtUnavailable(
                backend = backend,
                error = "litert_lm_json_parse_failed",
                modelInfo = modelInfoJson,
                rawResponse = rawResponse,
            )
        val functionName = obj.optString("function")
            .ifBlank { obj.optString("name") }
            .ifBlank { obj.optString("tool") }
        val args = obj.optJSONObject("args")
            ?: obj.optJSONObject("arguments")
            ?: JSONObject()
        return parse(
            candidates = listOf(LiteRtToolCandidate(functionName, args.toMap())),
            backend = backend,
            modelInfoJson = modelInfoJson,
            rawResponse = rawResponse,
            inferenceTimeMs = inferenceTimeMs,
        )
    }

    internal fun hasThoughtLeak(text: String): Boolean {
        val normalized = text.lowercase()
        return normalized.contains("<think") ||
            normalized.contains("</think") ||
            normalized.contains("<|think|") ||
            normalized.contains("<|thinking|") ||
            normalized.contains("\"analysis\"") ||
            normalized.contains("chain-of-thought")
    }

    private fun parseEvidenceRefs(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun parseFirstJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        runCatching { return JSONObject(trimmed) }
        val start = trimmed.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until trimmed.length) {
            val ch = trimmed[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth += 1
                !inString && ch == '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return runCatching { JSONObject(trimmed.substring(start, i + 1)) }.getOrNull()
                    }
                }
            }
        }
        return null
    }
}

private fun JSONObject.toMap(): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    keys().forEach { key ->
        out[key] = opt(key).toMapValue()
    }
    return out
}

private fun JSONArray.toListValue(): List<Any?> {
    return buildList {
        for (i in 0 until length()) {
            add(opt(i).toMapValue())
        }
    }
}

private fun Any?.toMapValue(): Any? {
    return when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> toMap()
        is JSONArray -> toListValue()
        else -> this
    }
}

private fun liteRtUnavailable(
    backend: String,
    error: String,
    modelInfo: String = "{}",
    rawResponse: String = "",
): LLMBridge.FunctionCallResult {
    return LLMBridge.FunctionCallResult(
        success = false,
        functionName = "",
        argsJson = "{}",
        backend = backend,
        selectionBasis = "",
        evidenceRefs = emptyList(),
        modelInfoJson = modelInfo,
        rawResponse = rawResponse,
        inferenceTimeMs = 0.0,
        errorMessage = error,
    )
}

private fun liteRtModelInfoJson(
    backend: String,
    modelPath: String,
    initTimeMs: Long? = null,
): String {
    val modelName = File(modelPath).name
    return JSONObject()
        .put("backend", backend)
        .put("model_path", modelPath)
        .put("model_name", modelName)
        .put("model_source", liteRtModelSource(modelName))
        .put("fine_tuned_for_gemmafit", modelName.startsWith("gemmafit-"))
        .put("tool_policy", "gemmafit_schema_validated")
        .put("init_time_ms", initTimeMs ?: JSONObject.NULL)
        .toString()
}

private fun liteRtRealtimeFastModelInfoJson(
    modelPath: String,
    functionName: String,
): String {
    val modelName = File(modelPath).name
    return JSONObject()
        .put("backend", "litert-lm:realtime_fast")
        .put("model_path", modelPath)
        .put("model_name", modelName)
        .put("model_source", liteRtModelSource(modelName))
        .put("fine_tuned_for_gemmafit", modelName.startsWith("gemmafit-"))
        .put("tool_policy", "gemmafit_schema_validated")
        .put("prompt_profile", "realtime_fast")
        .put("required_function", functionName)
        .put("skipped_litert_generation", true)
        .put("init_time_ms", JSONObject.NULL)
        .toString()
}

private fun liteRtModelSource(modelName: String): String {
    return when (modelName) {
        "mobile_actions_q8_ekv1024.litertlm" -> "litert-community/functiongemma-270m-ft-mobile-actions"
        "gemmafit-v5-e2b-evidence-router.litertlm" -> "gemmafit-v5-e2b-evidence-router"
        "gemma-4-E2B-it.litertlm" -> "litert-community/gemma-4-E2B-it-litert-lm"
        "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm" -> "litert-community/gemma-4-E2B-it-litert-lm"
        "gemmafit-v3-evidence-router.litertlm" -> "gemmafit-v3-evidence-router"
        "gemmafit-v2-fc.litertlm" -> "gemmafit-v2-fc"
        else -> "unknown"
    }
}

internal object GemmaFitLiteRtTools {
    fun all(toolExecutions: MutableList<JSONObject>? = null): List<OpenApiTool> = listOf(
        toolSpec(
            name = "correct_knee_alignment",
            description = "Coach knee tracking when reliable knee alignment evidence crosses the safety gate.",
            properties = mapOf(
                "side" to "left, right, or bilateral",
                "ratio" to "Observed knee-to-ankle alignment ratio",
                "severity" to "low, moderate, or severe",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "correct_spinal_alignment",
            description = "Coach trunk or neck alignment when supported by view and confidence evidence.",
            properties = mapOf(
                "deviation" to "Observed angular deviation in degrees",
                "region" to "trunk, spine, neck, or body_line",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "correct_joint_angle",
            description = "Coach conservative joint control when a joint angle is near an unsafe endpoint.",
            properties = mapOf(
                "joint" to "Relevant joint name",
                "current" to "Observed current angle in degrees",
                "safe_range" to "Human-readable safe range",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "correct_asymmetry",
            description = "Coach left-right control only when the exercise and evidence make symmetry applicable.",
            properties = mapOf(
                "joint" to "Relevant joint name",
                "left" to "Left side observed value",
                "right" to "Right side observed value",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "warn_com_offset",
            description = "Coach balance or center-of-mass drift when supported by the current movement template.",
            properties = mapOf(
                "direction" to "Observed drift direction",
                "distance" to "Observed offset or ratio",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "warn_rapid_movement",
            description = "Coach tempo when smoothed velocity evidence crosses the rapid-movement gate.",
            properties = mapOf(
                "joint" to "Relevant moving joint",
                "velocity" to "Observed angular velocity",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "increase_range_of_motion",
            description = "Coach range of motion when the exercise template defines a supported ROM target.",
            properties = mapOf(
                "joint" to "Primary range-limited joint",
                "current" to "Observed current ROM or angle",
                "target" to "Supported target ROM or angle",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "positive_reinforcement",
            description = "Give evidence-aware positive coaching when the window is clean and no safety gate is active.",
            properties = mapOf(
                "pattern" to "Movement pattern or exercise label",
                "streak" to "Clean frame or rep streak",
                "primary_muscles" to "Pose-estimated primary load focus, not activation",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "read_memory",
            description = "Request a closed-set local memory slice; the app controls the returned data.",
            properties = mapOf(
                "scope" to "PROFILE, CALIBRATION, TRENDS_7D, TRENDS_30D, or EVIDENCE_FOR_SESSION",
                "exercise" to "Optional exercise key",
                "session_id" to "Caregiver-flow session id only",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "request_memory_update",
            description = "Propose a structured memory write; the app policy engine validates before storing.",
            properties = mapOf(
                "request_id" to "Idempotency key",
                "type" to "PROFILE, CALIBRATION, or TREND_NOTE",
                "proposed_value" to "Structured payload only, no medical claim",
                "evidence_ids" to "Evidence ids supporting the proposal",
                "confidence" to "Confidence from 0.0 to 1.0",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "summarize_trend",
            description = "Summarize app-provided trend aggregates without clinical claims.",
            properties = mapOf(
                "scope" to "TRENDS_7D or TRENDS_30D",
                "exercise" to "Exercise key",
                "focus" to "consistency, tempo, range_of_motion, or camera_quality",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "refuse_unsupported_question",
            description = "Refuse unsupported medical, fall-risk, sarcopenia, injury, force, or diagnosis questions.",
            properties = mapOf(
                "reason" to "Unsupported claim category",
                "safe_alternative" to "Pose-based, non-clinical alternative",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "create_care_activity_log",
            description = "Create a non-diagnostic senior care activity log from app-provided evidence.",
            properties = mapOf(
                "headline" to "Short activity-log headline",
                "what_was_completed" to "Completed movement, reps, and duration",
                "observations" to "Visible movement-quality observations only",
                "not_judged" to "Unsupported judgments not assessed",
                "next_session_focus" to "One safe next-session focus",
                "caregiver_note" to "Non-clinical caregiver note",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "select_dual_task_prompt",
            description = "Select a safe low-impact dual-task prompt using bounded answer options.",
            properties = mapOf(
                "prompt_text_key" to "Localized prompt key",
                "prompt_args" to "Bounded prompt parameters",
                "expected_response_modes" to "gesture and/or voice",
                "expected_movement" to "Expected low-impact movement answer",
            ),
            toolExecutions = toolExecutions,
        ),
        toolSpec(
            name = "record_dual_task_result",
            description = "Record a bounded dual-task attempt result without cognitive diagnosis.",
            properties = mapOf(
                "prompt_id" to "Prompt id",
                "response_mode" to "gesture or voice",
                "recognized_speech" to "Bounded speech answer after parser acceptance",
                "asr_confidence" to "Speech recognizer confidence after bounded parsing",
                "answer_matched" to "Whether the bounded answer matched",
                "movement_completed" to "Whether the expected movement was completed",
                "fallback_reason" to "Fallback reason, if any",
            ),
            toolExecutions = toolExecutions,
        ),
    )

    fun sessionSummary(toolExecutions: MutableList<JSONObject>? = null): List<OpenApiTool> = listOf(
        toolSpec(
            name = "create_care_activity_log",
            description = "Create a non-diagnostic senior care activity log from app-provided evidence.",
            properties = mapOf(
                "headline" to "Short activity-log headline",
                "what_was_completed" to "Completed movement, reps, and duration",
                "observations" to "Visible movement-quality observations only",
                "not_judged" to "Unsupported judgments not assessed",
                "next_session_focus" to "One safe next-session focus",
                "caregiver_note" to "Non-clinical caregiver note",
            ),
            toolExecutions = toolExecutions,
        )
    )

    private fun toolSpec(
        name: String,
        description: String,
        properties: Map<String, String>,
        toolExecutions: MutableList<JSONObject>? = null,
    ): OpenApiTool = object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            val props = JSONObject()
            properties.forEach { (key, desc) ->
                props.put(
                    key,
                    JSONObject()
                        .put("type", if (key in numericFields) "NUMBER" else "STRING")
                        .put("description", desc)
                )
            }
            props.put(
                "coach_cue",
                JSONObject()
                    .put("type", "STRING")
                    .put("description", "Short trainer cue under 12 words, no diagnosis.")
            )
            props.put(
                "next_focus",
                JSONObject()
                    .put("type", "STRING")
                    .put("description", "One evidence-based next-set focus for the user, no diagnosis.")
            )
            props.put(
                "selection_basis",
                JSONObject()
                    .put("type", "STRING")
                    .put("description", "Brief evidence reason for selecting this tool.")
            )
            props.put(
                "evidence_refs",
                JSONObject()
                    .put("type", "ARRAY")
                    .put("items", JSONObject().put("type", "STRING"))
                    .put("description", "Evidence ids or metric names used for selection.")
            )
            return JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", props))
                .toString()
        }

        override fun execute(paramsJsonString: String): String {
            val params = runCatching { JSONObject(paramsJsonString) }.getOrElse { JSONObject() }
            toolExecutions?.add(
                JSONObject()
                    .put("function", name)
                    .put("params", params)
            )
            return JSONObject()
                .put("accepted", true)
                .put("function", name)
                .toString()
        }
    }

    private val numericFields = setOf(
        "ratio",
        "deviation",
        "current",
        "left",
        "right",
        "distance",
        "velocity",
        "target",
        "streak",
        "confidence",
    )
}

internal object LiteRtConstrainedToolResult {
    fun rawFunctionCallFrom(
        executions: List<JSONObject>,
        toolCalls: JSONArray,
        fallbackContent: String,
    ): String {
        val executed = executions.lastOrNull()
        val executedFunction = executed?.optString("function").orEmpty()
        val executedArgs = executed?.optJSONObject("params")
        if (executedFunction.isNotBlank() && executedArgs != null && executedArgs.length() > 0) {
            return JSONObject()
                .put("function", executedFunction)
                .put("args", executedArgs)
                .toString()
        }

        for (index in toolCalls.length() - 1 downTo 0) {
            val call = toolCalls.optJSONObject(index) ?: continue
            val function = call.optString("name").ifBlank { call.optString("function") }
            val args = call.optJSONObject("arguments") ?: call.optJSONObject("args")
            if (function.isNotBlank() && args != null && args.length() > 0) {
                return JSONObject()
                    .put("function", function)
                    .put("args", args)
                    .toString()
            }
        }

        return fallbackContent
    }
}
