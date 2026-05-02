package com.gemmafit.functions

/**
 * FunctionRegistry — 8 universal Function Calling tools for Gemma 4.
 *
 * Defines JSON schemas that Gemma 4 selects from based on biomechanics
 * input (movement pattern + safety anomalies + muscle focus).
 *
 * Each function schema follows OpenAI-compatible Function Calling format,
 * usable with llama.cpp's grammar-constrained generation.
 */
object FunctionRegistry {

    // ── Tool definitions ──────────────────────────────────────────────

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
            "description" to "Guide user to maintain neutral spine. Triggered when spine or neck deviation > 15°",
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
            "description" to "Warn about joint overextension or locking. Triggered when joint angle ≈ 0° or 180° ± 5°",
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
            "description" to "Guide user to balance left-right symmetry. Triggered when bilateral joint angle difference > 10°",
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

    // ── Utility ───────────────────────────────────────────────────────

    /**
     * Build the full system prompt with Function Calling schema for llama.cpp.
     */
    fun buildToolsJson(): String {
        val tools = allTools.joinToString(separator = ",\n") { tool ->
            val params = tool.toString()
                .replace("=", ": ")
                .replace("{", "{")
            // Simple approach: use kotlinx.serialization or manual JSON build
            buildString {
                append("{\n")
                append("  \"type\": \"function\",\n")
                val func = (tool["function"] as Map<*, *>)
                val name = func["name"] as String
                val desc = func["description"] as String
                append("  \"function\": {\n")
                append("    \"name\": \"$name\",\n")
                append("    \"description\": \"$desc\",\n")
                append("    \"parameters\": ")
                append(buildParamsJson(func["parameters"] as Map<*, *>))
                append("\n  }\n")
                append("}")
            }
        }
        return "[$tools]"
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
