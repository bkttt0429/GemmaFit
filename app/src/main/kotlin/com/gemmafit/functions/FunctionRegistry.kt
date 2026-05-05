package com.gemmafit.functions

import org.json.JSONArray
import org.json.JSONObject

/**
 * FunctionRegistry - biomechanics and memory Function Calling tools for Gemma 4.
 *
 * Defines JSON schemas that Gemma 4 selects from based on biomechanics
 * input (movement pattern + safety anomalies + muscle focus).
 *
 * Each function schema follows OpenAI-compatible Function Calling format,
 * usable with llama.cpp's grammar-constrained generation.
 */
object FunctionRegistry {

    // ?? Tool definitions ??????????????????????????????????????????????

    val allTools: List<Map<String, Any>> by lazy {
        listOf(
            correctKneeAlignment,
            correctSpinalAlignment,
            correctJointAngle,
            correctAsymmetry,
            warnComOffset,
            warnRapidMovement,
            increaseRangeOfMotion,
            positiveReinforcement,
            readMemory,
            requestMemoryUpdate,
            summarizeTrend,
            refuseUnsupportedQuestion,
        )
    }

    val correctKneeAlignment: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "correct_knee_alignment",
            "description" to "Guide user to align knees with toes. Triggered when knee/ankle distance ratio < 0.8",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "side" to mapOf(
                        "type" to "string",
                        "enum" to listOf("left", "right", "bilateral"),
                        "description" to "Which knee is affected"
                    ),
                    "ratio" to mapOf(
                        "type" to "number",
                        "description" to "Knee distance / ankle distance ratio"
                    ),
                    "severity" to mapOf(
                        "type" to "string",
                        "enum" to listOf("mild", "moderate", "severe"),
                        "description" to "Severity level"
                    ),
                ),
                "required" to listOf("side", "ratio", "severity"),
            ),
        ),
    )

    val correctSpinalAlignment: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "correct_spinal_alignment",
            "description" to "Guide user to maintain neutral spine. Triggered when spine or neck deviation > 15簞",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "deviation" to mapOf(
                        "type" to "number",
                        "description" to "Deviation from neutral in degrees"
                    ),
                    "region" to mapOf(
                        "type" to "string",
                        "enum" to listOf("lumbar", "thoracic", "cervical", "full_spine"),
                        "description" to "Affected spine region"
                    ),
                ),
                "required" to listOf("deviation", "region"),
            ),
        ),
    )

    val correctJointAngle: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "correct_joint_angle",
            "description" to "Warn about joint overextension or locking. Triggered when joint angle ??0簞 or 180簞 簣 5簞",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "joint" to mapOf(
                        "type" to "string",
                        "description" to "Affected joint name (e.g. left_knee, right_elbow)"
                    ),
                    "current" to mapOf(
                        "type" to "number",
                        "description" to "Current joint angle in degrees"
                    ),
                    "safe_range" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "number"),
                        "minItems" to 2,
                        "maxItems" to 2,
                        "description" to "Safe angle range [min, max] in degrees"
                    ),
                ),
                "required" to listOf("joint", "current", "safe_range"),
            ),
        ),
    )

    val correctAsymmetry: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "correct_asymmetry",
            "description" to "Guide user to balance left-right symmetry. Triggered when bilateral joint angle difference > 10簞",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "joint" to mapOf(
                        "type" to "string",
                        "description" to "Joint name (e.g. knee, hip, shoulder)"
                    ),
                    "left" to mapOf(
                        "type" to "number",
                        "description" to "Left side angle in degrees"
                    ),
                    "right" to mapOf(
                        "type" to "number",
                        "description" to "Right side angle in degrees"
                    ),
                ),
                "required" to listOf("joint", "left", "right"),
            ),
        ),
    )

    val warnComOffset: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "warn_com_offset",
            "description" to "Warn about center of mass shifting outside support base",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "direction" to mapOf(
                        "type" to "string",
                        "enum" to listOf("forward", "backward", "left", "right", "forward_left", "forward_right"),
                        "description" to "Direction of COM offset"
                    ),
                    "distance" to mapOf(
                        "type" to "number",
                        "description" to "Offset distance from support polygon edge (normalized 0-1)"
                    ),
                ),
                "required" to listOf("direction", "distance"),
            ),
        ),
    )

    val warnRapidMovement: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "warn_rapid_movement",
            "description" to "Warn about excessively fast joint movement. Triggered when angular velocity > 600 deg/s",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "joint" to mapOf(
                        "type" to "string",
                        "description" to "Joint name exhibiting rapid movement"
                    ),
                    "velocity" to mapOf(
                        "type" to "number",
                        "description" to "Angular velocity in deg/s"
                    ),
                ),
                "required" to listOf("joint", "velocity"),
            ),
        ),
    )

    val increaseRangeOfMotion: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "increase_range_of_motion",
            "description" to "Encourage deeper movement. Triggered when ROM < 50% of expected",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "joint" to mapOf(
                        "type" to "string",
                        "description" to "Joint with insufficient ROM"
                    ),
                    "current" to mapOf(
                        "type" to "number",
                        "description" to "Current range of motion in degrees"
                    ),
                    "target" to mapOf(
                        "type" to "number",
                        "description" to "Target range of motion in degrees"
                    ),
                ),
                "required" to listOf("joint", "current", "target"),
            ),
        ),
    )

    val positiveReinforcement: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "positive_reinforcement",
            "description" to "Praise user when no safety rules are triggered for 30+ consecutive frames",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "pattern" to mapOf(
                        "type" to "string",
                        "description" to "Movement pattern label"
                    ),
                    "primary_muscles" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "Estimated primary muscle groups"
                    ),
                    "streak" to mapOf(
                        "type" to "integer",
                        "description" to "Consecutive clean frames (30+ = praise)"
                    ),
                ),
                "required" to listOf("pattern", "streak"),
            ),
        ),
    )

    val readMemory: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "read_memory",
            "description" to "Request a closed-set local memory slice. The app chooses what is returned.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "scope" to mapOf(
                        "type" to "string",
                        "enum" to listOf("PROFILE", "CALIBRATION", "TRENDS_7D", "TRENDS_30D", "EVIDENCE_FOR_SESSION"),
                        "description" to "Closed memory scope requested by the model"
                    ),
                    "exercise" to mapOf(
                        "type" to "string",
                        "description" to "Optional exercise key for calibration or trend scope"
                    ),
                    "session_id" to mapOf(
                        "type" to "string",
                        "description" to "Only valid for caregiver-flow evidence reads"
                    ),
                ),
                "required" to listOf("scope"),
            ),
        ),
    )

    val requestMemoryUpdate: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "request_memory_update",
            "description" to "Propose a structured memory write. The app policy engine validates before storing.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "request_id" to mapOf(
                        "type" to "string",
                        "description" to "Idempotency key"
                    ),
                    "type" to mapOf(
                        "type" to "string",
                        "enum" to listOf("PROFILE", "CALIBRATION", "TREND_NOTE"),
                        "description" to "Memory update type"
                    ),
                    "proposed_value" to mapOf(
                        "type" to "object",
                        "description" to "Structured payload; no freeform medical or diagnostic prose"
                    ),
                    "evidence_ids" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "Evidence ids supporting this proposed write"
                    ),
                    "confidence" to mapOf(
                        "type" to "number",
                        "description" to "Model confidence in the proposed update, 0.0 to 1.0"
                    ),
                ),
                "required" to listOf("request_id", "type", "proposed_value", "confidence"),
            ),
        ),
    )

    val summarizeTrend: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "summarize_trend",
            "description" to "Summarize a memory trend using app-provided aggregate slices only.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "scope" to mapOf(
                        "type" to "string",
                        "enum" to listOf("TRENDS_7D", "TRENDS_30D"),
                        "description" to "Trend window to summarize"
                    ),
                    "exercise" to mapOf(
                        "type" to "string",
                        "description" to "Exercise key for the trend summary"
                    ),
                    "focus" to mapOf(
                        "type" to "string",
                        "enum" to listOf("consistency", "tempo", "range_of_motion", "camera_quality"),
                        "description" to "Non-clinical summary focus"
                    ),
                ),
                "required" to listOf("scope", "exercise"),
            ),
        ),
    )

    val refuseUnsupportedQuestion: Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to "refuse_unsupported_question",
            "description" to "Refuse medical, fall-risk, sarcopenia, injury, force, or diagnosis questions.",
            "parameters" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "reason" to mapOf(
                        "type" to "string",
                        "enum" to listOf(
                            "medical_diagnosis",
                            "fall_risk_prediction",
                            "sarcopenia_detection",
                            "injury_prediction",
                            "force_or_emg_claim",
                            "insufficient_evidence",
                        ),
                        "description" to "Unsupported claim category"
                    ),
                    "safe_alternative" to mapOf(
                        "type" to "string",
                        "description" to "Pose-based, non-clinical alternative the app may display"
                    ),
                ),
                "required" to listOf("reason"),
            ),
        ),
    )

    // ?? Utility ???????????????????????????????????????????????????????

    /**
     * Build the full system prompt with Function Calling schema for llama.cpp.
     */
    fun buildToolsJson(): String {
        return (toJsonValue(allTools) as JSONArray).toString(2)
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, child) ->
                    put(key.toString(), toJsonValue(child))
                }
            }
            is Iterable<*> -> JSONArray().apply {
                value.forEach { child -> put(toJsonValue(child)) }
            }
            else -> value
        }
    }

    private fun buildParamsJson(params: Map<*, *>, indent: Int = 4): String {
        val sb = StringBuilder()
        val pad = " ".repeat(indent)
        sb.append("{\n")
        sb.append("$pad\"type\": \"${params["type"]}\",\n")

        if (params.containsKey("properties")) {
            sb.append("$pad\"properties\": {\n")
            val props = params["properties"] as Map<*, *>
            var first = true
            for ((key, value) in props) {
                if (!first) sb.append(",\n")
                first = false
                sb.append("$pad  \"$key\": ")
                sb.append(buildPropertyJson(value as Map<*, *>, indent + 2))
            }
            sb.append("\n$pad},\n")
        }

        if (params.containsKey("required")) {
            val required = params["required"] as List<*>
            val items = required.joinToString(", ") { "\"$it\"" }
            sb.append("$pad\"required\": [$items]\n")
        }

        sb.append("${" ".repeat(indent - 2)}}")
        return sb.toString()
    }

    private fun buildPropertyJson(prop: Map<*, *>, indent: Int): String {
        val sb = StringBuilder()
        sb.append("{ \"type\": \"${prop["type"]}\"")

        prop["description"]?.let {
            sb.append(", \"description\": \"$it\"")
        }
        prop["enum"]?.let {
            val items = (it as List<*>).joinToString(", ") { v -> "\"$v\"" }
            sb.append(", \"enum\": [$items]")
        }
        prop["items"]?.let {
            sb.append(", \"items\": { \"type\": \"${(it as Map<*, *>)["type"]}\" }")
        }
        prop["minItems"]?.let {
            sb.append(", \"minItems\": $it")
        }
        prop["maxItems"]?.let {
            sb.append(", \"maxItems\": $it")
        }

        sb.append(" }")
        return sb.toString()
    }
}
