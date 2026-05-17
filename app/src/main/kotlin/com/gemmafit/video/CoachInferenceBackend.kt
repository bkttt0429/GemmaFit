package com.gemmafit.video

import android.app.Application
import android.net.Uri
import android.util.Log
import com.gemmafit.BuildConfig
import com.gemmafit.jni.LLMBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

interface CoachInferenceBackend : AutoCloseable {
    val name: String

    suspend fun isAvailable(): Boolean

    suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode = ModelReasoningMode.OFF,
    ): LLMBridge.FunctionCallResult

    suspend fun runInferenceStreaming(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode = ModelReasoningMode.OFF,
        streamObserver: CoachInferenceStreamObserver? = null,
    ): LLMBridge.FunctionCallResult {
        streamObserver?.onUpdate(
            CoachInferenceStreamUpdate(
                phase = SessionCoachStreamPhase.QUEUED,
                backend = name,
            )
        )
        return runInference(context, safetyJson, reasoningMode)
    }

    override fun close() = Unit
}

class CoachInferenceRouter(
    private val app: Application,
) : AutoCloseable {
    private val isolatedLiteRtBackend = IsolatedLiteRtSessionCoachBackend(app)
    private val liteRtBackend = LiteRtLmCoachBackend(app)
    private val llamaBackend = LlamaCppCoachBackend(app)
    private val fallbackBackend = DeterministicCoachBackend()

    suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode = ModelReasoningMode.OFF,
    ): LLMBridge.FunctionCallResult {
        val liteRtResult = runBackendIfAvailable(liteRtBackend, context, safetyJson, reasoningMode)
        if (liteRtResult?.success == true) return liteRtResult

        val llamaResult = runBackendIfAvailable(llamaBackend, context, safetyJson, reasoningMode)
        if (llamaResult?.success == true) return llamaResult

        return liteRtResult
            ?: llamaResult
            ?: fallbackBackend.runInference(context, safetyJson, ModelReasoningMode.OFF)
    }

    suspend fun runSessionInference(
        context: SessionCoachContext,
        reasoningMode: ModelReasoningMode = ModelReasoningMode.SUMMARY_OPTIONAL,
        streamObserver: CoachInferenceStreamObserver? = null,
    ): LLMBridge.FunctionCallResult {
        val coachContext = SessionCoachRenderer.toCoachContext(context)
        val safetyJson = SessionCoachRenderer.buildSafetyJson(context)

        val isolatedResult = runBackendIfAvailable(
            isolatedLiteRtBackend,
            coachContext,
            safetyJson,
            reasoningMode,
            streamObserver,
        )
        if (isolatedResult?.success == true) return SessionCoachRenderer.validateModelResult(context, isolatedResult)

        val llamaResult = runBackendIfAvailable(
            llamaBackend,
            coachContext,
            safetyJson,
            reasoningMode,
            streamObserver,
        )
        if (llamaResult?.success == true) return SessionCoachRenderer.validateModelResult(context, llamaResult)

        val fallback = isolatedResult
            ?: llamaResult
            ?: fallbackBackend.runInference(coachContext, safetyJson, ModelReasoningMode.OFF)
        return SessionCoachRenderer.validateModelResult(context, fallback)
    }

    override fun close() {
        isolatedLiteRtBackend.close()
        liteRtBackend.close()
        llamaBackend.close()
        fallbackBackend.close()
    }

    private suspend fun runBackendIfAvailable(
        backend: CoachInferenceBackend,
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode,
        streamObserver: CoachInferenceStreamObserver? = null,
    ): LLMBridge.FunctionCallResult? {
        if (!backend.isAvailable()) return null
        return try {
            backend.runInferenceStreaming(context, safetyJson, reasoningMode, streamObserver)
        } catch (e: Exception) {
            Log.w("GemmaFit.CoachAI", "${backend.name} failed: ${e.message}")
            LLMBridge.FunctionCallResult(
                success = false,
                functionName = "",
                argsJson = "{}",
                backend = backend.name,
                selectionBasis = "",
                evidenceRefs = emptyList(),
                modelInfoJson = "{}",
                rawResponse = "",
                inferenceTimeMs = 0.0,
                errorMessage = e.message ?: "${backend.name}_failed",
            )
        }
    }
}

class IsolatedLiteRtSessionCoachBackend(
    private val app: Application,
) : CoachInferenceBackend {
    override val name: String = "litert-lm:isolated"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        BuildConfig.DEBUG && CoachModelResolver.resolveLiteRtModelPath(app) != null
    }

    override suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode,
    ): LLMBridge.FunctionCallResult = withContext(Dispatchers.IO) {
        runIsolatedInference(context, safetyJson, reasoningMode, null)
    }

    override suspend fun runInferenceStreaming(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode,
        streamObserver: CoachInferenceStreamObserver?,
    ): LLMBridge.FunctionCallResult = withContext(Dispatchers.IO) {
        streamObserver?.onUpdate(
            CoachInferenceStreamUpdate(
                phase = SessionCoachStreamPhase.QUEUED,
                backend = name,
                constrainedDecoding = false,
            )
        )
        runIsolatedInference(context, safetyJson, reasoningMode, streamObserver)
    }

    private suspend fun runIsolatedInference(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode,
        streamObserver: CoachInferenceStreamObserver?,
    ): LLMBridge.FunctionCallResult {
        val modelPath = CoachModelResolver.resolveLiteRtModelPath(app)
            ?: return unavailable(name, "litert_model_file_not_found")
        val safety = runCatching { JSONObject(safetyJson) }.getOrElse { JSONObject() }
        val packet = LiteRtEvidencePromptRenderer.buildEvidencePacket(context, safety, reasoningMode)
        val prompt = LiteRtEvidencePromptRenderer.buildSessionSummaryPrompt(packet)
        val streamRequested = streamObserver != null
        val requestFile = File(File(app.filesDir, "debug").apply { mkdirs() }, REQUEST_FILE_NAME)
        val request = JSONObject()
            .put("profile", "session_summary")
            .put("created_at_ms", System.currentTimeMillis())
            .put("model_path", modelPath)
            .put("prompt_chars", prompt.length)
            .put("constrained_decoding", !streamRequested)
            .put("stream_requested", streamRequested)
            .put("prompt", prompt)
        requestFile.writeText(request.toString())

        val started = System.currentTimeMillis()
        streamObserver?.onUpdate(
            CoachInferenceStreamUpdate(
                phase = SessionCoachStreamPhase.PREFILL,
                backend = name,
                constrainedDecoding = false,
            )
        )
        val endpointUri = Uri.parse(
            buildString {
                append("content://com.gemmafit.debug/litert_prompt_infer?file=")
                append(REQUEST_FILE_NAME)
                if (streamRequested) append("&stream=true")
            }
        )
        val root = try {
            if (streamRequested) {
                withTimeout(ISOLATED_TIMEOUT_MS) {
                    readStreamingPromptPayload(endpointUri, streamObserver)
                }
            } else {
                val payload = withTimeout(ISOLATED_TIMEOUT_MS) {
                    app.contentResolver.openInputStream(endpointUri)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                }
                runCatching { JSONObject(payload ?: "") }.getOrElse { error ->
                    return unavailable(
                        backend = name,
                        error = "isolated_litert_invalid_payload:${error.message ?: "unknown"}",
                        modelInfo = isolatedModelInfo(modelPath, prompt.length),
                        rawResponse = payload.orEmpty().take(2_000),
                    )
                }
            }
        } catch (e: Exception) {
            return unavailable(
                backend = name,
                error = e.message ?: "isolated_litert_provider_failed",
                modelInfo = isolatedModelInfo(modelPath, prompt.length),
            )
        }
        streamObserver?.onUpdate(
            CoachInferenceStreamUpdate(
                phase = SessionCoachStreamPhase.VALIDATING,
                backend = name,
                constrainedDecoding = false,
            )
        )
        val rawResponse = root.optString("raw_response")
        if (!root.optBoolean("success", false) || rawResponse.isBlank()) {
            return unavailable(
                backend = root.optString("backend", name).ifBlank { name },
                error = root.optString("error", "isolated_litert_generation_failed"),
                modelInfo = isolatedModelInfo(modelPath, prompt.length, root),
                rawResponse = root.toString(),
            )
        }
        val parsed = LiteRtToolCallParser.parseRaw(
            rawResponse = rawResponse,
            backend = root.optString("backend", name).ifBlank { name },
            modelInfoJson = isolatedModelInfo(modelPath, prompt.length, root),
            inferenceTimeMs = root.optDouble("elapsed_ms", (System.currentTimeMillis() - started).toDouble()),
        )
        val repaired = LiteRtToolResultGuard.validateAndRepair(context, packet, parsed)
        streamObserver?.onUpdate(
            CoachInferenceStreamUpdate(
                phase = SessionCoachStreamPhase.COMPLETE,
                backend = repaired.backend,
                partialText = rawResponse.take(2_000),
                tokenCount = root.optInt("stream_token_count", 0),
                firstTokenTimeMs = root.optLong("first_token_ms").takeIf { it > 0L },
                constrainedDecoding = root.optBoolean("constrained_decoding", false),
                error = repaired.errorMessage,
            )
        )
        return repaired
    }

    private fun readStreamingPromptPayload(
        endpointUri: Uri,
        streamObserver: CoachInferenceStreamObserver?,
    ): JSONObject {
        var finalPayload: JSONObject? = null
        val partial = StringBuilder()
        app.contentResolver.openInputStream(endpointUri)
            ?.bufferedReader()
            ?.useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val event = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                    when (event.optString("event")) {
                        "prefill" -> {
                            streamObserver?.onUpdate(
                                CoachInferenceStreamUpdate(
                                    phase = SessionCoachStreamPhase.PREFILL,
                                    backend = event.optString("backend", name),
                                    constrainedDecoding = false,
                                )
                            )
                        }
                        "token" -> {
                            val delta = event.optString("text_delta")
                            if (delta.isNotBlank()) partial.append(delta)
                            streamObserver?.onUpdate(
                                CoachInferenceStreamUpdate(
                                    phase = SessionCoachStreamPhase.STREAMING,
                                    backend = event.optString("backend", name),
                                    partialText = partial.toString().take(1_000),
                                    tokenCount = event.optInt("stream_token_count", 0),
                                    firstTokenTimeMs = event.optLong("first_token_ms").takeIf { it > 0L },
                                    constrainedDecoding = false,
                                )
                            )
                        }
                        "error" -> {
                            finalPayload = event.optJSONObject("payload")
                        }
                        "done" -> {
                            finalPayload = event.optJSONObject("payload") ?: finalPayload
                        }
                    }
                }
            }
        return finalPayload ?: JSONObject()
            .put("success", false)
            .put("backend", name)
            .put("error", "isolated_litert_stream_missing_done")
    }

    private fun isolatedModelInfo(
        modelPath: String,
        promptChars: Int,
        payload: JSONObject? = null,
    ): String {
        val modelFile = File(modelPath)
        return JSONObject()
            .put("backend", payload?.optString("backend", name) ?: name)
            .put("model_path", modelPath)
            .put("model_name", modelFile.name)
            .put("model_size_bytes", modelFile.length())
            .put("prompt_chars", promptChars)
            .put("isolated_process", true)
            .put("provider_elapsed_ms", payload?.optLong("elapsed_ms") ?: JSONObject.NULL)
            .put("engine_create_ms", payload?.optLong("engine_create_ms") ?: JSONObject.NULL)
            .put("engine_initialize_ms", payload?.optLong("engine_initialize_ms") ?: JSONObject.NULL)
            .put("session_create_ms", payload?.optLong("session_create_ms") ?: JSONObject.NULL)
            .put("generate_content_ms", payload?.optLong("generate_content_ms") ?: JSONObject.NULL)
            .put("total_elapsed_ms", payload?.optLong("total_elapsed_ms") ?: JSONObject.NULL)
            .put("reused_engine", payload?.optBoolean("reused_engine") ?: JSONObject.NULL)
            .put("constrained_decoding", payload?.optBoolean("constrained_decoding") ?: JSONObject.NULL)
            .put("constrained_decoding_ms", payload?.optLong("constrained_decoding_ms") ?: JSONObject.NULL)
            .put("constrained_tool_call_observed", payload?.optBoolean("constrained_tool_call_observed") ?: JSONObject.NULL)
            .put("stream_requested", payload?.optBoolean("stream_requested") ?: JSONObject.NULL)
            .put("stream_token_count", payload?.optInt("stream_token_count") ?: JSONObject.NULL)
            .put("first_token_ms", payload?.optLong("first_token_ms") ?: JSONObject.NULL)
            .toString()
    }

    private companion object {
        const val REQUEST_FILE_NAME = "litert_session_prompt_request.json"
        const val ISOLATED_TIMEOUT_MS = 120_000L
    }
}

class LlamaCppCoachBackend(
    private val app: Application,
) : CoachInferenceBackend {
    override val name: String = "llama.cpp"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        LLMBridge.validateModel(CoachModelResolver.resolveGgufModelPath(app))
    }

    override suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode,
    ): LLMBridge.FunctionCallResult = withContext(Dispatchers.IO) {
        val modelPath = CoachModelResolver.resolveGgufModelPath(app)
        if (!LLMBridge.validateModel(modelPath)) {
            return@withContext unavailable(
                backend = "fallback",
                error = "model_file_not_found",
                modelInfo = LLMBridge.getModelInfo(modelPath),
            )
        }
        val started = System.currentTimeMillis()
        val safety = parseSafetyJson(safetyJson)
        val canonicalPacket = LiteRtEvidencePromptRenderer.buildEvidencePacket(context, safety, reasoningMode)
        val output = LLMBridge.runInference(
            movementPatternJson = canonicalMovementWrapper(context),
            safetyJson = canonicalPacket.toString(),
            muscleJson = canonicalMuscleWrapper(context),
            modelPath = modelPath,
        )
        val parsed = LLMBridge.parseFunctionCall(output)
        parsed.copy(inferenceTimeMs = parsed.inferenceTimeMs.takeIf { it > 0.0 }
            ?: (System.currentTimeMillis() - started).toDouble())
    }

    private fun canonicalMovementWrapper(context: CoachContext): String {
        return JSONObject()
            .put("input_format", "canonical_e2b_packet_in_safety_json")
            .put("canonical_packet_ref", "safety_json")
            .put("pattern", context.pattern)
            .put("exercise", context.exercise)
            .put("phase", context.movementPhase)
            .put("rep_count", context.repCount)
            .toString()
    }

    private fun canonicalMuscleWrapper(context: CoachContext): String {
        val muscle = context.muscle
        return JSONObject()
            .put("input_format", "canonical_e2b_packet_in_safety_json")
            .put("canonical_packet_ref", "safety_json")
            .put("estimated_primary", org.json.JSONArray(muscle?.primary ?: emptyList<String>()))
            .put("estimated_secondary", org.json.JSONArray(muscle?.secondary ?: emptyList<String>()))
            .put("boundary", "pose_estimated_load_focus_not_muscle_activation")
            .toString()
    }
}

class DeterministicCoachBackend : CoachInferenceBackend {
    override val name: String = "fallback"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
        reasoningMode: ModelReasoningMode,
    ): LLMBridge.FunctionCallResult {
        return unavailable(
            backend = "fallback",
            error = "deterministic_fallback",
            rawResponse = safetyJson,
            selectionBasis = "No local model backend completed; deterministic evidence renderer used.",
        )
    }
}

internal fun unavailable(
    backend: String,
    error: String,
    modelInfo: String = "{}",
    rawResponse: String = "",
    selectionBasis: String = "",
): LLMBridge.FunctionCallResult {
    return LLMBridge.FunctionCallResult(
        success = false,
        functionName = "",
        argsJson = "{}",
        backend = backend,
        selectionBasis = selectionBasis,
        evidenceRefs = emptyList(),
        modelInfoJson = modelInfo,
        rawResponse = rawResponse,
        inferenceTimeMs = 0.0,
        errorMessage = error,
    )
}

internal fun modelInfoJson(
    backend: String,
    modelPath: String,
    initTimeMs: Long? = null,
): String {
    return JSONObject()
        .put("backend", backend)
        .put("model_path", modelPath)
        .put("init_time_ms", initTimeMs ?: JSONObject.NULL)
        .toString()
}
