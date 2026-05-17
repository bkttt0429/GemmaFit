package com.gemmafit.senior

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject

@Keep
enum class SeniorActivity {
    CHAIR_SIT_TO_STAND,
    SEATED_LEG_RAISE,
    BALANCE_HOLD,
    STEP_TOUCH,
    SUPPORTED_SQUAT,
}

@Keep
enum class ActivityFamily {
    SENIOR_STRENGTH,
    DUAL_TASK,
    CARE_LOG,
    GENERAL_FITNESS,
}

@Keep
enum class ResponseMode { GESTURE, VOICE }

@Keep
enum class CheckInInputSource { BUTTONS, VOICE, CAREGIVER_ASSISTED }

@Keep
enum class SeniorUseMode {
    STANDARD_SELF_GUIDED,
    ASSISTED,
    DEMENTIA_FRIENDLY_SELF_GUIDED,
}

@Keep
enum class SeniorSupportState {
    READY,
    SETUP_NEEDED,
    USER_LEFT_ACTIVITY_AREA,
    NO_RESPONSE_AFTER_CUE,
    MULTI_PERSON_AMBIGUOUS,
    SESSION_PAUSED_FOR_SUPPORT,
}

@Keep
enum class SeniorInteractionAction {
    CONTINUE_SESSION,
    REPEAT_SIMPLE_CUE,
    PAUSE_FOR_SETUP,
    PAUSE_FOR_SUPPORT,
    END_SESSION_SUMMARY,
}

@Keep
enum class SubjectiveLevel { NONE, MILD, MODERATE, STRONG }

@Keep
enum class ReportPersona { SENIOR, CAREGIVER, PROFESSIONAL_SHARE }

@Keep
enum class DualTaskTheme {
    HOLIDAY,
    DAILY_LIFE,
    CATEGORY,
    ARITHMETIC,
    ORIENTATION,
}

@Keep
enum class DualTaskDifficulty { EASY, MEDIUM }

@Keep
enum class TempoBand { CONTROLLED, SLOW, VARIABLE, UNKNOWN }

@Keep
enum class SupportPattern { SEATED, SUPPORTED_STANCE, DOUBLE_STANCE, SINGLE_LEG, UNKNOWN }

@Keep
enum class SeniorPhase { SETUP, MOVING, HOLDING, STABILIZATION, COMPLETE, UNKNOWN }

@Keep
data class ActivityContext(
    val activityFamily: ActivityFamily,
    val taskLabel: String,
    val responseMode: ResponseMode? = null,
    val difficulty: DualTaskDifficulty? = null,
    val confidence: Double = 0.0,
    val source: String = "deterministic_context",
)

@Keep
data class MotionContext(
    val tempoBand: TempoBand = TempoBand.CONTROLLED,
    val supportPattern: SupportPattern = SupportPattern.UNKNOWN,
    val phase: SeniorPhase = SeniorPhase.UNKNOWN,
    val stabilityProxy: Double? = null,
    val momentumProxy: Double? = null,
)

@Keep
data class CareLogContext(
    val schemaVersion: String = "care_log_v1",
    val sessionId: String,
    val activity: SeniorActivity,
    val durationSec: Int,
    val completedReps: Int,
    val missedReps: Int = 0,
    val stabilityEvents: Int = 0,
    val lowConfidenceCount: Int = 0,
    val viewLimitedCount: Int = 0,
    val avgFormScore: Int = 0,
    val activityContext: ActivityContext,
    val motionContext: MotionContext,
    val capabilityContractJson: String = "{}",
    val supportEventCounts: SeniorSupportEventCounts = SeniorSupportEventCounts(),
    val evidenceRefs: List<String> = emptyList(),
    val unsupportedJudgments: List<String> = SENIOR_UNSUPPORTED_JUDGMENTS,
)

@Keep
data class SeniorSupportEventCounts(
    val lowConfidencePauses: Int = 0,
    val leftActivityAreaPauses: Int = 0,
    val noResponsePauses: Int = 0,
    val multiPersonAmbiguityPauses: Int = 0,
    val setupNeededPauses: Int = 0,
    val repeatedCueCount: Int = 0,
) {
    fun toJsonObject(): JSONObject {
        return JSONObject()
            .put("low_confidence_pauses", lowConfidencePauses)
            .put("left_activity_area_pauses", leftActivityAreaPauses)
            .put("no_response_pauses", noResponsePauses)
            .put("multi_person_ambiguity_pauses", multiPersonAmbiguityPauses)
            .put("setup_needed_pauses", setupNeededPauses)
            .put("repeated_cue_count", repeatedCueCount)
    }
}

@Keep
data class SubjectiveCheckIn(
    val schemaVersion: String = "subjective_checkin_v1",
    val sessionId: String,
    val inputSource: CheckInInputSource = CheckInInputSource.BUTTONS,
    val rpe0To10: Int? = null,
    val breathlessness: SubjectiveLevel = SubjectiveLevel.NONE,
    val legSoreness: SubjectiveLevel = SubjectiveLevel.NONE,
    val neededRest: Boolean = false,
    val discomfortReported: Boolean = false,
    val evidenceRefs: List<String> = SUBJECTIVE_EVIDENCE_REFS,
)

@Keep
data class CareActivityLog(
    val headline: String,
    val whatWasCompleted: String,
    val observations: String,
    val notJudged: String,
    val nextSessionFocus: String,
    val caregiverNote: String,
    val backend: String = "fallback",
    val functionName: String = "create_care_activity_log",
    val evidenceRefs: List<String> = emptyList(),
    val fallback: Boolean = true,
)

@Keep
data class PersonaActivityReport(
    val persona: ReportPersona,
    val reportText: String,
    val objectiveEvidenceRefs: List<String> = emptyList(),
    val subjectiveEvidenceRefs: List<String> = emptyList(),
    val boundaryNote: String = "Structured activity report only; not a medical assessment.",
    val selectionBasis: String = "",
    val backend: String = "fallback",
    val functionName: String = "create_persona_activity_report",
    val fallback: Boolean = true,
)

@Keep
data class DualTaskSessionPlan(
    val schemaVersion: String = "dual_task_v1",
    val theme: DualTaskTheme,
    val difficulty: DualTaskDifficulty,
    val promptTextKey: String,
    val promptArgs: Map<String, String> = emptyMap(),
    val expectedMovement: String,
    val answerOptions: List<String>,
    val allowedResponseModes: List<ResponseMode>,
    val fallbackResponseMode: ResponseMode = ResponseMode.GESTURE,
    val safetyBoundary: List<String> = listOf("low_impact", "seated_or_supported"),
)

@Keep
data class DualTaskAttempt(
    val promptId: String,
    val responseMode: ResponseMode,
    val detectedGesture: String = "none",
    val recognizedSpeech: String = "",
    val answerMatched: Boolean = false,
    val movementCompleted: Boolean = false,
    val poseConfidence: Double = 0.0,
    val asrConfidence: Double = 0.0,
    val fallbackReason: String = "",
    val evidenceRefs: List<String> = emptyList(),
)

object SeniorJson {
    fun careLogContext(context: CareLogContext): JSONObject {
        return JSONObject()
            .put("schema_version", context.schemaVersion)
            .put("session_id", context.sessionId)
            .put("activity", context.activity.jsonName())
            .put("duration_sec", context.durationSec)
            .put("completed_reps", context.completedReps)
            .put("missed_reps", context.missedReps)
            .put("stability_events", context.stabilityEvents)
            .put("low_confidence_count", context.lowConfidenceCount)
            .put("view_limited_count", context.viewLimitedCount)
            .put("avg_form_score", context.avgFormScore)
            .put("activity_context", activityContext(context.activityContext))
            .put("motion_context", motionContext(context.motionContext))
            .put("capability_contract", runCatching { JSONObject(context.capabilityContractJson) }.getOrElse { JSONObject() })
            .put("support_event_counts", context.supportEventCounts.toJsonObject())
            .put("evidence_refs", JSONArray(context.evidenceRefs))
            .put("unsupported_judgments", JSONArray(context.unsupportedJudgments))
    }

    fun subjectiveCheckIn(checkIn: SubjectiveCheckIn): JSONObject {
        return JSONObject()
            .put("schema_version", checkIn.schemaVersion)
            .put("session_id", checkIn.sessionId)
            .put("input_source", checkIn.inputSource.jsonName())
            .put("rpe_0_10", checkIn.rpe0To10 ?: JSONObject.NULL)
            .put("breathlessness", checkIn.breathlessness.jsonName())
            .put("leg_soreness", checkIn.legSoreness.jsonName())
            .put("needed_rest", checkIn.neededRest)
            .put("discomfort_reported", checkIn.discomfortReported)
            .put("evidence_refs", JSONArray(checkIn.evidenceRefs))
    }

    fun careActivityLog(log: CareActivityLog): JSONObject {
        return JSONObject()
            .put("headline", log.headline)
            .put("what_was_completed", log.whatWasCompleted)
            .put("observations", log.observations)
            .put("not_judged", log.notJudged)
            .put("next_session_focus", log.nextSessionFocus)
            .put("caregiver_note", log.caregiverNote)
            .put("backend", log.backend)
            .put("function_name", log.functionName)
            .put("evidence_refs", JSONArray(log.evidenceRefs))
            .put("fallback", log.fallback)
    }

    fun personaActivityReport(report: PersonaActivityReport): JSONObject {
        return JSONObject()
            .put("persona", report.persona.jsonName())
            .put("report_text", report.reportText)
            .put("objective_evidence_refs", JSONArray(report.objectiveEvidenceRefs))
            .put("subjective_evidence_refs", JSONArray(report.subjectiveEvidenceRefs))
            .put("boundary_note", report.boundaryNote)
            .put("selection_basis", report.selectionBasis)
            .put("backend", report.backend)
            .put("function_name", report.functionName)
            .put("fallback", report.fallback)
    }

    fun dualTaskPlan(plan: DualTaskSessionPlan): JSONObject {
        return JSONObject()
            .put("schema_version", plan.schemaVersion)
            .put("theme", plan.theme.jsonName())
            .put("difficulty", plan.difficulty.jsonName())
            .put("prompt_text_key", plan.promptTextKey)
            .put("prompt_args", JSONObject(plan.promptArgs))
            .put("expected_movement", plan.expectedMovement)
            .put("answer_options", JSONArray(plan.answerOptions))
            .put("allowed_response_modes", JSONArray(plan.allowedResponseModes.map { it.jsonName() }))
            .put("fallback_response_mode", plan.fallbackResponseMode.jsonName())
            .put("safety_boundary", JSONArray(plan.safetyBoundary))
    }

    fun dualTaskAttempt(attempt: DualTaskAttempt): JSONObject {
        return JSONObject()
            .put("prompt_id", attempt.promptId)
            .put("response_mode", attempt.responseMode.jsonName())
            .put("detected_gesture", attempt.detectedGesture)
            .put("recognized_speech", attempt.recognizedSpeech)
            .put("answer_matched", attempt.answerMatched)
            .put("movement_completed", attempt.movementCompleted)
            .put("pose_confidence", attempt.poseConfidence)
            .put("asr_confidence", attempt.asrConfidence)
            .put("fallback_reason", attempt.fallbackReason)
            .put("evidence_refs", JSONArray(attempt.evidenceRefs))
    }

    private fun activityContext(context: ActivityContext): JSONObject {
        return JSONObject()
            .put("activity_family", context.activityFamily.jsonName())
            .put("task_label", context.taskLabel)
            .put("response_mode", context.responseMode?.jsonName() ?: JSONObject.NULL)
            .put("difficulty", context.difficulty?.jsonName() ?: JSONObject.NULL)
            .put("confidence", context.confidence)
            .put("source", context.source)
    }

    private fun motionContext(context: MotionContext): JSONObject {
        return JSONObject()
            .put("tempo_band", context.tempoBand.jsonName())
            .put("support_pattern", context.supportPattern.jsonName())
            .put("phase", context.phase.jsonName())
            .put("stability_proxy", context.stabilityProxy ?: JSONObject.NULL)
            .put("momentum_proxy", context.momentumProxy ?: JSONObject.NULL)
    }
}

val SENIOR_UNSUPPORTED_JUDGMENTS: List<String> = listOf(
    "fall_risk_prediction",
    "sarcopenia_detection",
    "rehabilitation_prescription",
    "muscle_mass_estimate",
    "clinical_improvement_claim",
    "heart_rate_assessment",
)

val SUBJECTIVE_EVIDENCE_REFS: List<String> = listOf(
    "subjective.rpe",
    "subjective.breathlessness",
    "subjective.leg_soreness",
    "subjective.needed_rest",
    "subjective.discomfort_reported",
)

fun SeniorActivity.jsonName(): String = name.lowercase()
fun ActivityFamily.jsonName(): String = name.lowercase()
fun ResponseMode.jsonName(): String = name.lowercase()
fun CheckInInputSource.jsonName(): String = name.lowercase()
fun SeniorUseMode.jsonName(): String = name.lowercase()
fun SeniorSupportState.jsonName(): String = name.lowercase()
fun SeniorInteractionAction.jsonName(): String = name.lowercase()
fun SubjectiveLevel.jsonName(): String = name.lowercase()
fun ReportPersona.jsonName(): String = name.lowercase()
fun DualTaskTheme.jsonName(): String = name.lowercase()
fun DualTaskDifficulty.jsonName(): String = name.lowercase()
fun TempoBand.jsonName(): String = name.lowercase()
fun SupportPattern.jsonName(): String = name.lowercase()
fun SeniorPhase.jsonName(): String = name.lowercase()
