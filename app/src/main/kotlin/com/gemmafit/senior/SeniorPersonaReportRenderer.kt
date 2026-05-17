package com.gemmafit.senior

import com.gemmafit.memory.RefusalValidator

/**
 * Deterministic fallback for v4.1 multi-persona reports.
 *
 * Subjective check-ins are self-report evidence. They can contextualize a
 * caregiver report, but they never become heart-rate, diagnosis, or clinical
 * progress claims.
 */
object SeniorPersonaReportRenderer {
    fun render(
        context: CareLogContext,
        checkIn: SubjectiveCheckIn,
        persona: ReportPersona,
        backend: String = "fallback",
    ): PersonaActivityReport {
        val reportText = when (persona) {
            ReportPersona.SENIOR -> seniorText(context, checkIn)
            ReportPersona.CAREGIVER -> caregiverText(context, checkIn)
            ReportPersona.PROFESSIONAL_SHARE -> professionalShareText(context, checkIn)
        }
        val boundary = if (checkIn.discomfortReported || checkIn.neededRest || checkIn.breathlessness == SubjectiveLevel.STRONG) {
            "Self-reported discomfort or strong effort means the user should stop, rest, notify a caregiver, and seek professional help if symptoms persist."
        } else {
            "Structured activity report only; not a medical assessment."
        }
        return sanitize(
            PersonaActivityReport(
                persona = persona,
                reportText = reportText,
                objectiveEvidenceRefs = context.evidenceRefs,
                subjectiveEvidenceRefs = checkIn.evidenceRefs,
                boundaryNote = boundary,
                selectionBasis = "Objective movement evidence is app-generated; exertion and soreness are user self-report.",
                backend = backend,
                fallback = backend == "fallback",
            ),
        )
    }

    private fun seniorText(context: CareLogContext, checkIn: SubjectiveCheckIn): String {
        return "Completed ${context.completedReps} ${context.activity.displayName()} rep(s). " +
            "You reported ${checkIn.breathlessness.displayName()} breathlessness and " +
            "${checkIn.legSoreness.displayName()} leg soreness. " +
            "Next time, keep the same slow supported pace and stop if anything feels uncomfortable."
    }

    private fun caregiverText(context: CareLogContext, checkIn: SubjectiveCheckIn): String {
        val stability = if (context.stabilityEvents > 0) {
            " ${context.stabilityEvents} visible stability proxy event(s) were recorded."
        } else {
            " No visible stability proxy event was recorded."
        }
        val rest = if (checkIn.neededRest) {
            " The user reported needing rest."
        } else {
            ""
        }
        return "Completed ${context.completedReps} ${context.activity.displayName()} rep(s).$stability " +
            "Self-report after activity: ${checkIn.breathlessness.displayName()} breathlessness and " +
            "${checkIn.legSoreness.displayName()} leg soreness.$rest Keep the area clear and stay nearby if support is needed."
    }

    private fun professionalShareText(context: CareLogContext, checkIn: SubjectiveCheckIn): String {
        return "Structured home activity summary: completed ${context.completedReps} " +
            "${context.activity.displayName()} rep(s) in ${context.durationSec} seconds. " +
            "Visible movement evidence included ${context.stabilityEvents} stability proxy event(s). " +
            "Self-report after activity: RPE ${checkIn.rpe0To10 ?: "not recorded"}, " +
            "${checkIn.breathlessness.displayName()} breathlessness, and " +
            "${checkIn.legSoreness.displayName()} leg soreness. " +
            "This report does not assess fall risk, sarcopenia, rehabilitation progress, heart rate, force, or clinical status."
    }

    private fun sanitize(report: PersonaActivityReport): PersonaActivityReport {
        val joined = listOf(
            report.reportText,
            report.boundaryNote,
            report.selectionBasis,
        ).joinToString(" ")
        return if (RefusalValidator.isClean(joined)) {
            report
        } else {
            report.copy(
                reportText = "Structured activity completion and self-reported exertion were summarized from app evidence.",
                boundaryNote = "Unsupported medical or clinical judgments were not assessed.",
                fallback = true,
            )
        }
    }

    private fun SeniorActivity.displayName(): String = when (this) {
        SeniorActivity.CHAIR_SIT_TO_STAND -> "chair sit-to-stand"
        SeniorActivity.SEATED_LEG_RAISE -> "seated leg raise"
        SeniorActivity.BALANCE_HOLD -> "balance hold"
        SeniorActivity.STEP_TOUCH -> "step touch"
        SeniorActivity.SUPPORTED_SQUAT -> "supported squat"
    }

    private fun SubjectiveLevel.displayName(): String = when (this) {
        SubjectiveLevel.NONE -> "no"
        SubjectiveLevel.MILD -> "mild"
        SubjectiveLevel.MODERATE -> "moderate"
        SubjectiveLevel.STRONG -> "strong"
    }
}
