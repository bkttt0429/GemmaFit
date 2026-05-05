package com.gemmafit.video

import android.app.Application
import com.gemmafit.jni.LLMBridge
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.tool
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
    private var conversation: Conversation? = null
    private var activeBackendName: String = "litert-lm:uninitialized"
    private var modelPath: String? = null
    private var initTimeMs: Long? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        CoachModelResolver.resolveLiteRtModelPath(app) != null
    }

    override suspend fun runInference(
        context: CoachContext,
        safetyJson: String,
    ): LLMBridge.FunctionCallResult = withContext(Dispatchers.IO) {
        val path = CoachModelResolver.resolveLiteRtModelPath(app)
            ?: return@withContext unavailable("fallback", "litert_model_file_not_found")
        val prompt = buildPrompt(context, safetyJson)
        val started = System.currentTimeMillis()
        val response = try {
            withTimeout(LITERT_TIMEOUT_MS) {
                ensureConversation(path).sendMessage(prompt)
            }
        } catch (e: Exception) {
            return@withContext unavailable(
                backend = activeBackendName.takeIf { it != "litert-lm:uninitialized" } ?: "litert-lm",
                error = e.message ?: "litert_lm_inference_failed",
                modelInfo = modelInfoJson(activeBackendName, path, initTimeMs),
            )
        }
        val elapsed = (System.currentTimeMillis() - started).toDouble()
        LiteRtToolCallParser.parse(
            candidates = response.toolCalls.map { LiteRtToolCandidate.from(it) },
            backend = activeBackendName,
            modelInfoJson = modelInfoJson(activeBackendName, path, initTimeMs),
            rawResponse = response.toString(),
            inferenceTimeMs = elapsed,
        )
    }

    override fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    private suspend fun ensureConversation(path: String): Conversation {
        return initMutex.withLock {
            val existing = conversation
            if (existing != null && modelPath == path && existing.isAlive) return@withLock existing

            close()
            modelPath = path
            val backends = listOf(
                "litert-lm:gpu" to Backend.GPU(),
                "litert-lm:cpu" to Backend.CPU(),
            )
            var lastError: Throwable? = null
            for ((label, backend) in backends) {
                try {
                    val started = System.currentTimeMillis()
                    val createdEngine = Engine(
                        EngineConfig(
                            modelPath = path,
                            backend = backend,
                            maxNumTokens = LITERT_MAX_TOKENS,
                            cacheDir = File(app.cacheDir, "litert-lm").absolutePath,
                        )
                    )
                    createdEngine.initialize()
                    val createdConversation = createdEngine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                            tools = GemmaFitTools.all().map { tool(it) },
                            samplerConfig = SamplerConfig(
                                topK = 8,
                                topP = 0.85,
                                temperature = 0.15,
                                seed = 7,
                            ),
                            automaticToolCalling = false,
                        )
                    )
                    engine = createdEngine
                    conversation = createdConversation
                    activeBackendName = label
                    initTimeMs = System.currentTimeMillis() - started
                    return@withLock createdConversation
                } catch (e: Throwable) {
                    lastError = e
                    close()
                }
            }
            throw IllegalStateException(lastError?.message ?: "litert_lm_init_failed")
        }
    }

    private fun buildPrompt(context: CoachContext, safetyJson: String): String {
        return JSONObject()
            .put("task", "select_one_gemmafit_function_call")
            .put("movement", JSONObject(CoachInsightRenderer.buildMovementPromptJson(context)))
            .put("safety", runCatching { JSONObject(safetyJson) }.getOrElse { JSONObject().put("raw", safetyJson) })
            .put("muscle", JSONObject(CoachInsightRenderer.buildMusclePromptJson(context)))
            .put(
                "rules",
                JSONArray(
                    listOf(
                        "Use exactly one provided tool.",
                        "Do not make medical, injury, joint-force, or muscle-activation claims.",
                        "Respect VIEW_LIMITED and LOW_CONFIDENCE gates.",
                        "Use only metrics listed under capability_contract.can_judge.",
                        "Cite evidence_refs from the provided Evidence DAG only.",
                        "Prefer positive_reinforcement only when no safety evidence is active.",
                    )
                )
            )
            .toString()
    }

    private companion object {
        const val LITERT_TIMEOUT_MS = 15_000L
        const val LITERT_MAX_TOKENS = 768
        const val SYSTEM_INSTRUCTION =
            "You are GemmaFit's local tool selector. Select exactly one function call from the registered tools. " +
                "Use only movement-quality evidence. Never override biomechanics safety gates."
    }
}

internal data class LiteRtToolCandidate(
    val name: String,
    val arguments: Map<String, Any?>,
) {
    companion object {
        fun from(call: ToolCall): LiteRtToolCandidate {
            return LiteRtToolCandidate(call.name, call.arguments)
        }
    }
}

internal object LiteRtToolCallParser {
    private val allowed = setOf(
        "correct_knee_alignment",
        "correct_spinal_alignment",
        "correct_joint_angle",
        "correct_asymmetry",
        "warn_com_offset",
        "warn_rapid_movement",
        "increase_range_of_motion",
        "positive_reinforcement",
        "read_memory",
        "request_memory_update",
        "summarize_trend",
        "refuse_unsupported_question",
    )

    fun parse(
        candidates: List<LiteRtToolCandidate>,
        backend: String,
        modelInfoJson: String,
        rawResponse: String,
        inferenceTimeMs: Double,
    ): LLMBridge.FunctionCallResult {
        val selected = candidates.firstOrNull { it.name in allowed }
            ?: return unavailable(
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

    private fun parseEvidenceRefs(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
}

private object GemmaFitTools {
    fun all(): List<OpenApiTool> = listOf(
        toolSpec(
            name = "correct_knee_alignment",
            description = "Coach knee tracking when reliable knee alignment evidence crosses the safety gate.",
            properties = mapOf(
                "side" to "left, right, or bilateral",
                "ratio" to "Observed knee-to-ankle alignment ratio",
                "severity" to "low, moderate, or severe",
            ),
        ),
        toolSpec(
            name = "correct_spinal_alignment",
            description = "Coach trunk or neck alignment when supported by view and confidence evidence.",
            properties = mapOf(
                "deviation" to "Observed angular deviation in degrees",
                "region" to "trunk, spine, neck, or body_line",
            ),
        ),
        toolSpec(
            name = "correct_joint_angle",
            description = "Coach conservative joint control when a joint angle is near an unsafe endpoint.",
            properties = mapOf(
                "joint" to "Relevant joint name",
                "current" to "Observed current angle in degrees",
                "safe_range" to "Human-readable safe range",
            ),
        ),
        toolSpec(
            name = "correct_asymmetry",
            description = "Coach left-right control only when the exercise and evidence make symmetry applicable.",
            properties = mapOf(
                "joint" to "Relevant joint name",
                "left" to "Left side observed value",
                "right" to "Right side observed value",
            ),
        ),
        toolSpec(
            name = "warn_com_offset",
            description = "Coach balance or center-of-mass drift when supported by the current movement template.",
            properties = mapOf(
                "direction" to "Observed drift direction",
                "distance" to "Observed offset or ratio",
            ),
        ),
        toolSpec(
            name = "warn_rapid_movement",
            description = "Coach tempo when smoothed velocity evidence crosses the rapid-movement gate.",
            properties = mapOf(
                "joint" to "Relevant moving joint",
                "velocity" to "Observed angular velocity",
            ),
        ),
        toolSpec(
            name = "increase_range_of_motion",
            description = "Coach range of motion when the exercise template defines a supported ROM target.",
            properties = mapOf(
                "joint" to "Primary range-limited joint",
                "current" to "Observed current ROM or angle",
                "target" to "Supported target ROM or angle",
            ),
        ),
        toolSpec(
            name = "positive_reinforcement",
            description = "Give evidence-aware positive coaching when the window is clean and no safety gate is active.",
            properties = mapOf(
                "pattern" to "Movement pattern or exercise label",
                "streak" to "Clean frame or rep streak",
                "primary_muscles" to "Pose-estimated primary load focus, not activation",
            ),
        ),
        toolSpec(
            name = "read_memory",
            description = "Request a closed-set local memory slice; the app controls the returned data.",
            properties = mapOf(
                "scope" to "PROFILE, CALIBRATION, TRENDS_7D, TRENDS_30D, or EVIDENCE_FOR_SESSION",
                "exercise" to "Optional exercise key",
                "session_id" to "Caregiver-flow session id only",
            ),
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
        ),
        toolSpec(
            name = "summarize_trend",
            description = "Summarize app-provided trend aggregates without clinical claims.",
            properties = mapOf(
                "scope" to "TRENDS_7D or TRENDS_30D",
                "exercise" to "Exercise key",
                "focus" to "consistency, tempo, range_of_motion, or camera_quality",
            ),
        ),
        toolSpec(
            name = "refuse_unsupported_question",
            description = "Refuse unsupported medical, fall-risk, sarcopenia, injury, force, or diagnosis questions.",
            properties = mapOf(
                "reason" to "Unsupported claim category",
                "safe_alternative" to "Pose-based, non-clinical alternative",
            ),
        ),
    )

    private fun toolSpec(
        name: String,
        description: String,
        properties: Map<String, String>,
    ): OpenApiTool = object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String {
            val props = JSONObject()
            properties.forEach { (key, desc) ->
                props.put(
                    key,
                    JSONObject()
                        .put("type", if (key in numericFields) "number" else "string")
                        .put("description", desc)
                )
            }
            props.put(
                "coach_cue",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Short trainer cue under 12 words, no diagnosis.")
            )
            props.put(
                "next_focus",
                JSONObject()
                    .put("type", "string")
                    .put("description", "One evidence-based next-set focus for the user, no diagnosis.")
            )
            props.put(
                "selection_basis",
                JSONObject()
                    .put("type", "string")
                    .put("description", "Brief evidence reason for selecting this tool.")
            )
            props.put(
                "evidence_refs",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                    .put("description", "Evidence ids or metric names used for selection.")
            )
            return JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject().put("type", "object").put("properties", props))
                .toString()
        }

        override fun execute(paramsJsonString: String): String {
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
