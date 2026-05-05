package com.gemmafit.video

import android.app.Application
import android.util.Log
import com.gemmafit.jni.LLMBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface CoachInferenceBackend : AutoCloseable {
    val name: String

    suspend fun isAvailable(): Boolean

    suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
    ): LLMBridge.FunctionCallResult

    override fun close() = Unit
}

class CoachInferenceRouter(
    private val app: Application,
) : AutoCloseable {
    private val liteRtBackend = LiteRtLmCoachBackend(app)
    private val llamaBackend = LlamaCppCoachBackend(app)
    private val fallbackBackend = DeterministicCoachBackend()

    suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
    ): LLMBridge.FunctionCallResult {
        val liteRtResult = runBackendIfAvailable(liteRtBackend, context, safetyJson)
        if (liteRtResult?.success == true) return liteRtResult

        val llamaResult = runBackendIfAvailable(llamaBackend, context, safetyJson)
        if (llamaResult?.success == true) return llamaResult

        return liteRtResult
            ?: llamaResult
            ?: fallbackBackend.runInference(context, safetyJson)
    }

    suspend fun runSessionInference(
        context: SessionCoachContext,
    ): LLMBridge.FunctionCallResult {
        val result = runInference(
            context = SessionCoachRenderer.toCoachContext(context),
            safetyJson = SessionCoachRenderer.buildSafetyJson(context),
        )
        return SessionCoachRenderer.validateModelResult(context, result)
    }

    override fun close() {
        liteRtBackend.close()
        llamaBackend.close()
        fallbackBackend.close()
    }

    private suspend fun runBackendIfAvailable(
        backend: CoachInferenceBackend,
        context: CoachContext,
        safetyJson: String,
    ): LLMBridge.FunctionCallResult? {
        if (!backend.isAvailable()) return null
        return try {
            backend.runInference(context, safetyJson)
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
        val output = LLMBridge.runInference(
            movementPatternJson = CoachInsightRenderer.buildMovementPromptJson(context),
            safetyJson = safetyJson,
            muscleJson = CoachInsightRenderer.buildMusclePromptJson(context),
            modelPath = modelPath,
        )
        val parsed = LLMBridge.parseFunctionCall(output)
        parsed.copy(inferenceTimeMs = parsed.inferenceTimeMs.takeIf { it > 0.0 }
            ?: (System.currentTimeMillis() - started).toDouble())
    }
}

class DeterministicCoachBackend : CoachInferenceBackend {
    override val name: String = "fallback"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
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
