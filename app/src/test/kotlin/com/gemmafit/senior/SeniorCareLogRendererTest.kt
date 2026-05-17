package com.gemmafit.senior

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeniorCareLogRendererTest {
    @Test
    fun renders_non_diagnostic_care_log_with_evidence() {
        val context = CareLogContext(
            sessionId = "session-1",
            activity = SeniorActivity.CHAIR_SIT_TO_STAND,
            durationSec = 180,
            completedReps = 12,
            stabilityEvents = 2,
            avgFormScore = 86,
            activityContext = ActivityContext(
                activityFamily = ActivityFamily.SENIOR_STRENGTH,
                taskLabel = "chair_sit_to_stand",
                confidence = 0.9,
            ),
            motionContext = MotionContext(
                tempoBand = TempoBand.CONTROLLED,
                supportPattern = SupportPattern.SUPPORTED_STANCE,
                phase = SeniorPhase.COMPLETE,
            ),
            evidenceRefs = listOf("metric.senior.reps", "metric.senior.stability_events"),
        )

        val log = SeniorCareLogRenderer.render(context)

        assertTrue(log.whatWasCompleted.contains("12"))
        assertTrue(log.observations.contains("stability proxy"))
        assertTrue(log.evidenceRefs.contains("metric.senior.reps"))
        assertFalse(log.observations.contains("fall risk", ignoreCase = true))
        assertTrue(log.notJudged.contains("does not assess fall risk"))
    }

    @Test
    fun persona_report_combines_objective_and_self_report_without_heart_rate_claim() {
        val context = baseContext()
        val checkIn = SubjectiveCheckIn(
            sessionId = "session-1",
            rpe0To10 = 4,
            breathlessness = SubjectiveLevel.MILD,
            legSoreness = SubjectiveLevel.MILD,
            evidenceRefs = listOf("subjective.rpe", "subjective.breathlessness", "subjective.leg_soreness"),
        )

        val report = SeniorPersonaReportRenderer.render(
            context = context,
            checkIn = checkIn,
            persona = ReportPersona.CAREGIVER,
        )

        assertTrue(report.reportText.contains("Self-report"))
        assertTrue(report.objectiveEvidenceRefs.contains("metric.senior.reps"))
        assertTrue(report.subjectiveEvidenceRefs.contains("subjective.breathlessness"))
        assertFalse(report.reportText.contains("heart rate stable", ignoreCase = true))
        assertFalse(report.reportText.contains("fall risk", ignoreCase = true))
    }

    @Test
    fun persona_report_adds_stop_boundary_for_discomfort_without_diagnosis() {
        val report = SeniorPersonaReportRenderer.render(
            context = baseContext(),
            checkIn = SubjectiveCheckIn(
                sessionId = "session-1",
                breathlessness = SubjectiveLevel.STRONG,
                neededRest = true,
                discomfortReported = true,
            ),
            persona = ReportPersona.PROFESSIONAL_SHARE,
        )

        assertTrue(report.boundaryNote.contains("stop"))
        assertTrue(report.boundaryNote.contains("professional help"))
        assertFalse(report.boundaryNote.contains("diagnosis", ignoreCase = true))
    }

    private fun baseContext() = CareLogContext(
        sessionId = "session-1",
        activity = SeniorActivity.CHAIR_SIT_TO_STAND,
        durationSec = 180,
        completedReps = 12,
        stabilityEvents = 1,
        avgFormScore = 86,
        activityContext = ActivityContext(
            activityFamily = ActivityFamily.SENIOR_STRENGTH,
            taskLabel = "chair_sit_to_stand",
            confidence = 0.9,
        ),
        motionContext = MotionContext(
            tempoBand = TempoBand.CONTROLLED,
            supportPattern = SupportPattern.SUPPORTED_STANCE,
            phase = SeniorPhase.COMPLETE,
        ),
        evidenceRefs = listOf("metric.senior.reps", "metric.senior.tempo"),
    )
}
