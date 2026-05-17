package com.gemmafit.senior

import com.gemmafit.memory.RefusalValidator

/**
 * Deterministic fallback for caregiver logs.
 *
 * Local Gemma may select `create_care_activity_log`, but this renderer is the
 * app-owned safe path whenever the model is absent, cites invalid evidence, or
 * produces unsupported language.
 */
object SeniorCareLogRenderer {
    fun render(context: CareLogContext, backend: String = "fallback"): CareActivityLog {
        val activity = context.activity.displayName()
        val duration = if (context.durationSec > 0) {
            " in ${context.durationSec / 60} minute(s)"
        } else {
            ""
        }
        val completion = "Completed ${context.completedReps} $activity rep(s)$duration."
        val missed = if (context.missedReps > 0) {
            " ${context.missedReps} rep(s) were missed or not counted."
        } else {
            ""
        }
        val observation = buildList {
            add("Tempo and completion were summarized from visible movement evidence.")
            if (context.stabilityEvents > 0) {
                add("${context.stabilityEvents} stability proxy event(s) were observed.")
            }
            if (context.lowConfidenceCount > 0 || context.viewLimitedCount > 0) {
                add("Some frames were camera-limited or low-confidence.")
            }
        }.joinToString(" ")
        val notJudged = "This does not assess fall risk, sarcopenia, rehabilitation progress, muscle mass, or clinical improvement."
        val focus = nextFocus(context)
        return sanitize(
            CareActivityLog(
                headline = "Completed $activity session",
                whatWasCompleted = completion + missed,
                observations = observation,
                notJudged = notJudged,
                nextSessionFocus = focus,
                caregiverNote = "Structured activity log only; not a medical assessment.",
                backend = backend,
                evidenceRefs = context.evidenceRefs,
                fallback = backend == "fallback",
            ),
        )
    }

    private fun sanitize(log: CareActivityLog): CareActivityLog {
        val joined = listOf(
            log.headline,
            log.whatWasCompleted,
            log.observations,
            log.notJudged,
            log.nextSessionFocus,
            log.caregiverNote,
        ).joinToString(" ")
        return if (RefusalValidator.isClean(joined)) {
            log
        } else {
            log.copy(
                observations = "Visible movement-quality observations were summarized from app evidence.",
                notJudged = "Unsupported medical or clinical judgments were not assessed.",
                caregiverNote = "Structured activity log only; not a medical assessment.",
                fallback = true,
            )
        }
    }

    private fun nextFocus(context: CareLogContext): String {
        return when {
            context.lowConfidenceCount > 0 || context.viewLimitedCount > 0 ->
                "Keep the full body visible and use the same camera setup next time."
            context.stabilityEvents > 0 ->
                "Keep the area clear and use the same controlled pace."
            context.completedReps == 0 ->
                "Try one supported, low-impact movement when the camera view is stable."
            else ->
                "Repeat the same controlled pace and stop if anything feels uncomfortable."
        }
    }

    private fun SeniorActivity.displayName(): String = when (this) {
        SeniorActivity.CHAIR_SIT_TO_STAND -> "chair sit-to-stand"
        SeniorActivity.SEATED_LEG_RAISE -> "seated leg raise"
        SeniorActivity.BALANCE_HOLD -> "balance hold"
        SeniorActivity.STEP_TOUCH -> "step touch"
        SeniorActivity.SUPPORTED_SQUAT -> "supported squat"
    }
}
