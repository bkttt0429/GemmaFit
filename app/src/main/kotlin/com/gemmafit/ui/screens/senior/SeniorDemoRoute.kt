package com.gemmafit.ui.screens.senior

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import com.gemmafit.debug.GemmaFitDebugApi
import com.gemmafit.senior.ActivityContext
import com.gemmafit.senior.ActivityFamily
import com.gemmafit.senior.CareLogContext
import com.gemmafit.senior.CheckInInputSource
import com.gemmafit.senior.DualTaskAttempt
import com.gemmafit.senior.DualTaskDifficulty
import com.gemmafit.senior.MotionContext
import com.gemmafit.senior.ReportPersona
import com.gemmafit.senior.ResponseMode
import com.gemmafit.senior.SeniorActivity
import com.gemmafit.senior.SeniorCareLogRenderer
import com.gemmafit.senior.SeniorDualTaskVoiceState
import com.gemmafit.senior.SeniorJson
import com.gemmafit.senior.SeniorPersonaReportRenderer
import com.gemmafit.senior.SeniorPhase
import com.gemmafit.senior.SubjectiveCheckIn
import com.gemmafit.senior.SubjectiveLevel
import com.gemmafit.senior.SupportPattern
import com.gemmafit.senior.TempoBand

private enum class SeniorDemoPage {
    HOME,
    LIVE,
    DUAL_TASK,
    CHECKIN,
    CARE_LOG,
    PERSONA_REPORT,
}

@Composable
fun SeniorDemoRoute(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(SeniorDemoPage.HOME) }
    var dualTaskState by remember { mutableStateOf(SeniorDualTaskUiState()) }
    val careContext = remember { demoCareLogContext() }
    val checkIn = remember { demoSubjectiveCheckIn() }
    val careLog = remember(careContext) { SeniorCareLogRenderer.render(careContext) }
    val personaReport = remember(careContext, checkIn) {
        SeniorPersonaReportRenderer.render(
            context = careContext,
            checkIn = checkIn,
            persona = ReportPersona.CAREGIVER,
        )
    }

    LaunchedEffect(careContext, checkIn, careLog, personaReport) {
        GemmaFitDebugApi.updateCareLogState(
            mapOf(
                "source" to "senior_demo_route",
                "care_log_context" to SeniorJson.careLogContext(careContext),
                "care_activity_log" to SeniorJson.careActivityLog(careLog),
                "backend" to careLog.backend,
                "fallback" to careLog.fallback,
                "evidence_refs" to careLog.evidenceRefs,
            ),
        )
        GemmaFitDebugApi.updateSubjectiveCheckinState(
            mapOf(
                "source" to "senior_demo_route",
                "subjective_checkin" to SeniorJson.subjectiveCheckIn(checkIn),
                "evidence_refs" to checkIn.evidenceRefs,
                "input_source" to checkIn.inputSource.name.lowercase(),
            ),
        )
        GemmaFitDebugApi.updatePersonaReportState(
            mapOf(
                "source" to "senior_demo_route",
                "persona_report" to SeniorJson.personaActivityReport(personaReport),
                "backend" to personaReport.backend,
                "fallback" to personaReport.fallback,
                "objective_evidence_refs" to personaReport.objectiveEvidenceRefs,
                "subjective_evidence_refs" to personaReport.subjectiveEvidenceRefs,
            ),
        )
        GemmaFitDebugApi.updateDualTaskState(
            mapOf(
                "source" to "senior_demo_route",
                "prompt_id" to dualTaskState.promptId,
                "response_mode" to dualTaskState.responseMode,
                "voice_status" to dualTaskState.voiceStatus,
                "fallback_reason" to dualTaskState.fallbackReason,
                "evidence_refs" to listOf("capability.gesture.arm_raise"),
            ),
        )
    }

    when (page) {
        SeniorDemoPage.HOME -> SeniorHomeScreen(
            onStartExercise = { page = SeniorDemoPage.LIVE },
            onOpenTrends = { page = SeniorDemoPage.PERSONA_REPORT },
            onStopReset = onExit,
            options = defaultDemoOptions(),
            modifier = modifier,
        )

        SeniorDemoPage.LIVE -> SeniorLiveCoachScreen(
            state = SeniorLiveCoachUiState(
                exerciseTitle = "Senior Hero Demo",
                repCount = careContext.completedReps,
                currentCue = "Completed ${careContext.completedReps} controlled sit-to-stand reps. Try the dual-task prompt next.",
                trustStatus = "OK",
                sourceLabel = "pose_rules",
            ),
            onStopReset = onExit,
            onRepeatCue = { page = SeniorDemoPage.DUAL_TASK },
            onOpenEvidence = { page = SeniorDemoPage.CARE_LOG },
            modifier = modifier,
        )

        SeniorDemoPage.DUAL_TASK -> SeniorDualTaskScreen(
            state = dualTaskState,
            onStopReset = onExit,
            onRepeatPrompt = {
                dualTaskState = dualTaskState.copy(voiceStatus = "Prompt repeated by TTS only.")
                writeDualTaskDebug(dualTaskState)
            },
            onUseGestureFallback = {
                dualTaskState = dualTaskState.copy(
                    responseMode = "gesture",
                    fallbackReason = "gesture_fallback_selected",
                    voiceStatus = "gesture fallback ready",
                )
                writeDualTaskDebug(dualTaskState)
                page = SeniorDemoPage.CHECKIN
            },
            onVoiceAttempt = { attempt ->
                dualTaskState = dualTaskState.fromAttempt(attempt)
                writeDualTaskDebug(dualTaskState, attempt)
                if (attempt.answerMatched || attempt.fallbackReason.isNotBlank()) {
                    page = SeniorDemoPage.CHECKIN
                }
            },
            onVoiceStateChange = { voiceState ->
                dualTaskState = dualTaskState.fromVoiceState(voiceState)
                writeDualTaskDebug(dualTaskState, voiceState.attempt)
            },
            modifier = modifier,
        )

        SeniorDemoPage.CHECKIN -> SeniorSubjectiveCheckinScreen(
            state = SeniorSubjectiveCheckinUiState(
                prompt = "How did that activity feel?",
                breathlessness = "mild",
                legSoreness = "mild",
                neededRest = false,
                discomfortReported = false,
                inputMode = "buttons",
                evidenceRefs = checkIn.evidenceRefs,
            ),
            onStopReset = onExit,
            onRepeatPrompt = { page = SeniorDemoPage.CARE_LOG },
            onUseButtons = { page = SeniorDemoPage.CARE_LOG },
            modifier = modifier,
        )

        SeniorDemoPage.CARE_LOG -> SeniorCareLogScreen(
            state = SeniorCareLogUiState(
                headline = careLog.headline,
                whatWasCompleted = careLog.whatWasCompleted,
                observations = careLog.observations,
                notJudged = careLog.notJudged,
                nextSessionFocus = careLog.nextSessionFocus,
                backend = careLog.backend,
                functionName = careLog.functionName,
                evidenceRefs = careLog.evidenceRefs,
                fallback = careLog.fallback,
            ),
            onStopReset = onExit,
            onExport = { page = SeniorDemoPage.PERSONA_REPORT },
            modifier = modifier,
        )

        SeniorDemoPage.PERSONA_REPORT -> SeniorPersonaReportScreen(
            state = SeniorPersonaReportUiState(
                persona = personaReport.persona.name.lowercase(),
                reportText = personaReport.reportText,
                boundaryNote = personaReport.boundaryNote,
                objectiveEvidenceRefs = personaReport.objectiveEvidenceRefs,
                subjectiveEvidenceRefs = personaReport.subjectiveEvidenceRefs,
                backend = personaReport.backend,
                fallback = personaReport.fallback,
            ),
            onStopReset = onExit,
            onExport = { page = SeniorDemoPage.HOME },
            modifier = modifier,
        )
    }
}

private fun defaultDemoOptions(): List<SeniorExerciseOption> {
    return listOf(
        SeniorExerciseOption(
            id = "chair_sit_to_stand",
            title = "Run Senior Hero",
            subtitle = "Sit-to-stand, dual-task, care log",
            icon = Icons.Filled.AccessibilityNew,
        ),
        SeniorExerciseOption(
            id = "dual_task",
            title = "Dual-task Voice",
            subtitle = "A/B bounded voice with gesture fallback",
            icon = Icons.Filled.VolumeUp,
        ),
        SeniorExerciseOption(
            id = "care_log",
            title = "Caregiver Report",
            subtitle = "Non-diagnostic activity summary",
            icon = Icons.Filled.Share,
        ),
    )
}

private fun demoCareLogContext(): CareLogContext {
    return CareLogContext(
        sessionId = "senior-demo-session",
        activity = SeniorActivity.CHAIR_SIT_TO_STAND,
        durationSec = 180,
        completedReps = 12,
        missedReps = 1,
        stabilityEvents = 1,
        lowConfidenceCount = 0,
        viewLimitedCount = 0,
        avgFormScore = 86,
        activityContext = ActivityContext(
            activityFamily = ActivityFamily.SENIOR_STRENGTH,
            taskLabel = "chair_sit_to_stand",
            confidence = 0.91,
            source = "senior_demo_route",
        ),
        motionContext = MotionContext(
            tempoBand = TempoBand.CONTROLLED,
            supportPattern = SupportPattern.SUPPORTED_STANCE,
            phase = SeniorPhase.COMPLETE,
            stabilityProxy = 0.18,
            momentumProxy = 0.2,
        ),
        capabilityContractJson = """
            {
              "can_judge":[
                {"metric":"rep_completion","evidence_refs":["metric.senior.reps"]},
                {"metric":"tempo_consistency","evidence_refs":["metric.senior.tempo"]},
                {"metric":"stability_proxy","evidence_refs":["metric.senior.stability_events"]}
              ],
              "cannot_judge":[
                {"metric":"fall_risk_prediction","reason":"non_diagnostic_app"},
                {"metric":"sarcopenia_detection","reason":"non_diagnostic_app"}
              ]
            }
        """.trimIndent(),
        evidenceRefs = listOf(
            "metric.senior.reps",
            "metric.senior.tempo",
            "metric.senior.stability_events",
        ),
    )
}

private fun demoSubjectiveCheckIn(): SubjectiveCheckIn {
    return SubjectiveCheckIn(
        sessionId = "senior-demo-session",
        inputSource = CheckInInputSource.BUTTONS,
        rpe0To10 = 4,
        breathlessness = SubjectiveLevel.MILD,
        legSoreness = SubjectiveLevel.MILD,
        neededRest = false,
        discomfortReported = false,
    )
}

private fun SeniorDualTaskUiState.fromVoiceState(
    voiceState: SeniorDualTaskVoiceState,
): SeniorDualTaskUiState {
    return copy(
        responseMode = if (voiceState.answerMatched || voiceState.fallbackReason.isNotBlank()) "voice" else responseMode,
        isListening = voiceState.isListening,
        recognizedSpeech = voiceState.recognizedSpeech,
        asrConfidence = voiceState.asrConfidence,
        answerMatched = voiceState.answerMatched,
        fallbackReason = voiceState.fallbackReason,
        voiceStatus = voiceState.voiceStatus,
    )
}

private fun SeniorDualTaskUiState.fromAttempt(
    attempt: DualTaskAttempt,
): SeniorDualTaskUiState {
    return copy(
        responseMode = attempt.responseMode.name.lowercase(),
        detectedGesture = attempt.detectedGesture,
        recognizedSpeech = attempt.recognizedSpeech,
        asrConfidence = attempt.asrConfidence,
        answerMatched = attempt.answerMatched,
        fallbackReason = attempt.fallbackReason,
        voiceStatus = if (attempt.answerMatched) "Voice answer accepted: ${attempt.recognizedSpeech}" else "Voice unavailable, use gesture",
    )
}

private fun writeDualTaskDebug(
    state: SeniorDualTaskUiState,
    attempt: DualTaskAttempt? = null,
) {
    GemmaFitDebugApi.updateDualTaskState(
        mapOf(
            "source" to "senior_demo_route",
            "prompt_id" to state.promptId,
            "prompt" to state.prompt,
            "answer_options" to state.answerOptions,
            "allowed_response_modes" to state.allowedResponseModes,
            "expected_movement" to state.expectedMovement,
            "response_mode" to state.responseMode,
            "detected_gesture" to state.detectedGesture,
            "voice_status" to state.voiceStatus,
            "is_listening" to state.isListening,
            "recognized_speech" to state.recognizedSpeech,
            "asr_confidence" to state.asrConfidence,
            "answer_matched" to state.answerMatched,
            "fallback_reason" to state.fallbackReason,
            "attempt" to attempt?.let { SeniorJson.dualTaskAttempt(it) },
            "evidence_refs" to (attempt?.evidenceRefs ?: listOf("capability.gesture.arm_raise")),
            "unsupported" to listOf("cognitive_diagnosis", "dementia_screening"),
        ),
    )
}
