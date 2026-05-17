package com.gemmafit.functions

import org.json.JSONArray
import org.json.JSONObject

/**
 * Function-calling registry for GemmaFit's bounded local router.
 *
 * The model may select one of these tools, but app-side policy remains
 * authoritative: capability gates, evidence refs, and memory writes are still
 * validated after inference.
 */
object FunctionRegistry {
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
            createCareActivityLog,
            askSubjectiveCheckin,
            recordSubjectiveCheckin,
            createPersonaActivityReport,
            selectDualTaskPrompt,
            recordDualTaskResult,
        )
    }

    val correctKneeAlignment: Map<String, Any> = function(
        name = "correct_knee_alignment",
        description = "Guide knee tracking only when frontal knee evidence is judgeable.",
        properties = mapOf(
            "side" to stringProp("Which knee is affected", enum = listOf("left", "right", "bilateral")),
            "ratio" to numberProp("Knee distance / ankle distance ratio"),
            "severity" to stringProp("Severity level", enum = listOf("mild", "moderate", "severe")),
        ),
        required = listOf("side", "ratio", "severity"),
    )

    val correctSpinalAlignment: Map<String, Any> = function(
        name = "correct_spinal_alignment",
        description = "Guide trunk, spine, or neck alignment from readable pose evidence.",
        properties = mapOf(
            "deviation" to numberProp("Deviation from neutral in degrees"),
            "region" to stringProp(
                "Affected spine region",
                enum = listOf("lumbar", "thoracic", "cervical", "full_spine"),
            ),
        ),
        required = listOf("deviation", "region"),
    )

    val correctJointAngle: Map<String, Any> = function(
        name = "correct_joint_angle",
        description = "Warn about supported joint endpoint or overextension evidence.",
        properties = mapOf(
            "joint" to stringProp("Affected joint name"),
            "current" to numberProp("Current joint angle in degrees"),
            "safe_range" to arrayProp("Safe angle range [min, max] in degrees", itemType = "number", minItems = 2, maxItems = 2),
        ),
        required = listOf("joint", "current", "safe_range"),
    )

    val correctAsymmetry: Map<String, Any> = function(
        name = "correct_asymmetry",
        description = "Guide left-right control only when symmetry is applicable to the task.",
        properties = mapOf(
            "joint" to stringProp("Joint name"),
            "left" to numberProp("Left side value"),
            "right" to numberProp("Right side value"),
        ),
        required = listOf("joint", "left", "right"),
    )

    val warnComOffset: Map<String, Any> = function(
        name = "warn_com_offset",
        description = "Warn about center-of-mass proxy drift when the rule is applicable.",
        properties = mapOf(
            "direction" to stringProp(
                "Direction of COM offset",
                enum = listOf("forward", "backward", "left", "right", "forward_left", "forward_right"),
            ),
            "distance" to numberProp("Normalized offset distance"),
        ),
        required = listOf("direction", "distance"),
    )

    val warnRapidMovement: Map<String, Any> = function(
        name = "warn_rapid_movement",
        description = "Warn about excessively fast controlled movement when smoothed angular velocity is above 600 deg/s.",
        properties = mapOf(
            "joint" to stringProp("Joint exhibiting rapid movement"),
            "velocity" to numberProp("Angular velocity in deg/s"),
        ),
        required = listOf("joint", "velocity"),
    )

    val increaseRangeOfMotion: Map<String, Any> = function(
        name = "increase_range_of_motion",
        description = "Encourage range of motion only when the template defines a supported ROM target.",
        properties = mapOf(
            "joint" to stringProp("Joint with insufficient ROM"),
            "current" to numberProp("Current range of motion in degrees"),
            "target" to numberProp("Target range of motion in degrees"),
        ),
        required = listOf("joint", "current", "target"),
    )

    val positiveReinforcement: Map<String, Any> = function(
        name = "positive_reinforcement",
        description = "Give evidence-aware positive coaching when no safety gate is active.",
        properties = mapOf(
            "pattern" to stringProp("Movement pattern label"),
            "primary_muscles" to arrayProp("Pose-estimated load focus, not activation", itemType = "string"),
            "streak" to integerProp("Consecutive clean frames or reps"),
        ),
        required = listOf("pattern", "streak"),
    )

    val readMemory: Map<String, Any> = function(
        name = "read_memory",
        description = "Request a closed-set local memory slice. The app chooses what is returned.",
        properties = mapOf(
            "scope" to stringProp(
                "Closed memory scope",
                enum = listOf("PROFILE", "CALIBRATION", "TRENDS_7D", "TRENDS_30D", "EVIDENCE_FOR_SESSION"),
            ),
            "exercise" to stringProp("Optional exercise key"),
            "session_id" to stringProp("Only valid for caregiver-flow evidence reads"),
        ),
        required = listOf("scope"),
    )

    val requestMemoryUpdate: Map<String, Any> = function(
        name = "request_memory_update",
        description = "Propose a structured memory write. The app validates before storing.",
        properties = mapOf(
            "request_id" to stringProp("Idempotency key"),
            "type" to stringProp(
                "Memory update type",
                enum = listOf("PROFILE", "CALIBRATION", "TREND_NOTE", "CARE_ACTIVITY_LOG", "DUAL_TASK_RESULT"),
            ),
            "proposed_value" to objectProp("Structured payload; no medical or diagnostic prose"),
            "evidence_ids" to arrayProp("Evidence ids supporting this proposed write", itemType = "string"),
            "confidence" to numberProp("Confidence from 0.0 to 1.0"),
        ),
        required = listOf("request_id", "type", "proposed_value", "confidence"),
    )

    val summarizeTrend: Map<String, Any> = function(
        name = "summarize_trend",
        description = "Summarize a local trend using app-provided aggregate slices only.",
        properties = mapOf(
            "scope" to stringProp("Trend window", enum = listOf("TRENDS_7D", "TRENDS_30D")),
            "exercise" to stringProp("Exercise key"),
            "focus" to stringProp(
                "Non-clinical summary focus",
                enum = listOf("consistency", "tempo", "range_of_motion", "camera_quality", "completion"),
            ),
        ),
        required = listOf("scope", "exercise"),
    )

    val refuseUnsupportedQuestion: Map<String, Any> = function(
        name = "refuse_unsupported_question",
        description = "Refuse medical, fall-risk, sarcopenia, injury, force, EMG, or diagnosis questions.",
        properties = mapOf(
            "reason" to stringProp(
                "Unsupported claim category",
                enum = listOf(
                    "medical_diagnosis",
                    "fall_risk_prediction",
                    "sarcopenia_detection",
                    "injury_prediction",
                    "force_or_emg_claim",
                    "rehabilitation_prescription",
                    "clinical_improvement_claim",
                    "insufficient_evidence",
                ),
            ),
            "safe_alternative" to stringProp("Pose-based, non-clinical alternative the app may display"),
            "evidence_refs" to arrayProp("Evidence ids for the blocked capability", itemType = "string"),
            "refusal_level" to integerProp("0-4 refusal gradient"),
        ),
        required = listOf("reason"),
    )

    val createCareActivityLog: Map<String, Any> = function(
        name = "create_care_activity_log",
        description = "Create a non-diagnostic caregiver activity log from app-provided senior evidence.",
        properties = mapOf(
            "headline" to stringProp("Short activity-log headline"),
            "what_was_completed" to stringProp("Completed movement, reps, and duration"),
            "observations" to stringProp("Visible movement-quality observations only"),
            "not_judged" to stringProp("Unsupported judgments that were not assessed"),
            "next_session_focus" to stringProp("One safe next-session focus"),
            "caregiver_note" to stringProp("Non-clinical caregiver note"),
            "selection_basis" to stringProp("Why this tool was selected"),
            "evidence_refs" to arrayProp("Existing evidence ids used by this log", itemType = "string"),
        ),
        required = listOf(
            "headline",
            "what_was_completed",
            "observations",
            "not_judged",
            "next_session_focus",
            "evidence_refs",
        ),
    )

    val askSubjectiveCheckin: Map<String, Any> = function(
        name = "ask_subjective_checkin",
        description = "Ask bounded post-session exertion questions using buttons, bounded voice, or caregiver-assisted input.",
        properties = mapOf(
            "prompt_keys" to arrayProp("Localized check-in prompt keys", itemType = "string"),
            "input_modes" to arrayProp("Allowed input modes", itemType = "string"),
            "response_schema" to objectProp("Bounded response schema for RPE, breathlessness, soreness, rest, and discomfort"),
            "selection_basis" to stringProp("Why subjective check-in is appropriate after this session"),
            "evidence_refs" to arrayProp("Existing objective evidence ids that show a session completed", itemType = "string"),
        ),
        required = listOf("prompt_keys", "input_modes", "response_schema", "evidence_refs"),
    )

    val recordSubjectiveCheckin: Map<String, Any> = function(
        name = "record_subjective_checkin",
        description = "Record bounded self-reported exertion without converting it into medical or heart-rate claims.",
        properties = mapOf(
            "rpe_0_10" to integerProp("Self-reported perceived exertion, 0 to 10"),
            "breathlessness" to stringProp("Self-reported breathlessness", enum = listOf("none", "mild", "moderate", "strong")),
            "leg_soreness" to stringProp("Self-reported leg soreness", enum = listOf("none", "mild", "moderate", "strong")),
            "needed_rest" to boolProp("Whether the user reported needing rest"),
            "discomfort_reported" to boolProp("Whether the user reported dizziness, chest tightness, pain, or discomfort"),
            "safety_boundary" to stringProp("Stop/rest/caregiver/professional-help boundary when needed"),
            "selection_basis" to stringProp("Evidence basis for recording self-report"),
            "evidence_refs" to arrayProp("Existing subjective self-report evidence ids", itemType = "string"),
        ),
        required = listOf(
            "rpe_0_10",
            "breathlessness",
            "leg_soreness",
            "needed_rest",
            "discomfort_reported",
            "evidence_refs",
        ),
    )

    val createPersonaActivityReport: Map<String, Any> = function(
        name = "create_persona_activity_report",
        description = "Create a non-diagnostic activity report for senior, caregiver, or professional-share personas.",
        properties = mapOf(
            "persona" to stringProp("Report persona", enum = listOf("senior", "caregiver", "professional_share")),
            "report_text" to stringProp("Persona-appropriate report text with objective and self-report evidence only"),
            "objective_evidence_refs" to arrayProp("Existing objective app evidence ids", itemType = "string"),
            "subjective_evidence_refs" to arrayProp("Existing subjective self-report evidence ids", itemType = "string"),
            "boundary_note" to stringProp("Non-clinical boundary and stop/rest guidance when needed"),
            "selection_basis" to stringProp("Why this persona report was selected"),
        ),
        required = listOf(
            "persona",
            "report_text",
            "objective_evidence_refs",
            "subjective_evidence_refs",
            "boundary_note",
            "selection_basis",
        ),
    )

    val selectDualTaskPrompt: Map<String, Any> = function(
        name = "select_dual_task_prompt",
        description = "Select a safe low-impact dual-task prompt from bounded activity evidence.",
        properties = mapOf(
            "prompt_text_key" to stringProp("Localized prompt key"),
            "prompt_args" to objectProp("Bounded prompt parameters"),
            "expected_response_modes" to arrayProp("gesture and/or voice", itemType = "string"),
            "expected_movement" to stringProp("Expected low-impact movement answer"),
            "coach_cue" to stringProp("Short spoken instruction"),
            "selection_basis" to stringProp("Why this prompt is safe and supported"),
            "next_focus" to stringProp("One simple focus for the item"),
            "evidence_refs" to arrayProp("Existing evidence ids supporting the prompt", itemType = "string"),
        ),
        required = listOf("prompt_text_key", "expected_response_modes", "expected_movement", "evidence_refs"),
    )

    val recordDualTaskResult: Map<String, Any> = function(
        name = "record_dual_task_result",
        description = "Record a bounded dual-task attempt result without cognitive diagnosis.",
        properties = mapOf(
            "prompt_id" to stringProp("Prompt id"),
            "response_mode" to stringProp("gesture or voice", enum = listOf("gesture", "voice")),
            "recognized_speech" to stringProp("Bounded speech answer after parser acceptance"),
            "asr_confidence" to numberProp("Speech recognizer confidence after bounded parsing"),
            "answer_matched" to boolProp("Whether the bounded answer matched"),
            "movement_completed" to boolProp("Whether the expected movement was completed"),
            "fallback_reason" to stringProp("Fallback reason, if any"),
            "selection_basis" to stringProp("Evidence basis for the result"),
            "evidence_refs" to arrayProp("Existing evidence ids supporting the result", itemType = "string"),
        ),
        required = listOf("prompt_id", "response_mode", "answer_matched", "movement_completed", "evidence_refs"),
    )

    fun buildToolsJson(): String {
        return (toJsonValue(allTools) as JSONArray).toString(2)
    }

    private fun function(
        name: String,
        description: String,
        properties: Map<String, Map<String, Any>>,
        required: List<String>,
    ): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required,
            ),
        ),
    )

    private fun stringProp(description: String, enum: List<String>? = null): Map<String, Any> {
        return buildMap {
            put("type", "string")
            put("description", description)
            enum?.let { put("enum", it) }
        }
    }

    private fun numberProp(description: String): Map<String, Any> = mapOf(
        "type" to "number",
        "description" to description,
    )

    private fun integerProp(description: String): Map<String, Any> = mapOf(
        "type" to "integer",
        "description" to description,
    )

    private fun boolProp(description: String): Map<String, Any> = mapOf(
        "type" to "boolean",
        "description" to description,
    )

    private fun objectProp(description: String): Map<String, Any> = mapOf(
        "type" to "object",
        "description" to description,
    )

    private fun arrayProp(
        description: String,
        itemType: String,
        minItems: Int? = null,
        maxItems: Int? = null,
    ): Map<String, Any> {
        return buildMap {
            put("type", "array")
            put("items", mapOf("type" to itemType))
            put("description", description)
            minItems?.let { put("minItems", it) }
            maxItems?.let { put("maxItems", it) }
        }
    }

    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().apply {
                value.forEach { (key, child) -> put(key.toString(), toJsonValue(child)) }
            }
            is Iterable<*> -> JSONArray().apply {
                value.forEach { child -> put(toJsonValue(child)) }
            }
            else -> value
        }
    }
}
