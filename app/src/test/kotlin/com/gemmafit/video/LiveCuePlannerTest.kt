package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCuePlannerTest {
    @Test
    fun repeatedWarningRotatesCueVariant() {
        val planner = LiveCuePlanner()
        val context = rapidMovementContext(repCount = 1)
        val deterministic = rapidMovementInsight()

        val first = planner.plan(context, deterministic, timestampMs = 1_000L)
        val second = planner.plan(context, deterministic, timestampMs = 6_000L)

        assertNotEquals(first.insight.message, second.insight.message)
        assertNotEquals(first.variantId, second.variantId)
        assertEquals("repeated_warning", second.outcome)
        assertEquals(LiveCueRewriteEvent.WARNING_PERSISTED, second.rewriteRequest?.event)
    }

    @Test
    fun repeatedIntentUsesSecondAndThirdVariants() {
        val planner = LiveCuePlanner()
        val context = rapidMovementContext(repCount = 1)
        val deterministic = rapidMovementInsight()

        val first = planner.plan(context, deterministic, timestampMs = 1_000L)
        val second = planner.plan(context, deterministic, timestampMs = 4_000L)
        val third = planner.plan(context, deterministic, timestampMs = 8_000L)

        assertEquals("rapid_0", first.variantId)
        assertEquals("rapid_1", second.variantId)
        assertEquals("rapid_2", third.variantId)
        assertTrue(listOf(first.insight.message, second.insight.message, third.insight.message).distinct().size >= 2)
    }

    @Test
    fun warningThenCleanRepProducesImprovementCue() {
        val planner = LiveCuePlanner()
        planner.plan(rapidMovementContext(repCount = 1), rapidMovementInsight(), timestampMs = 1_000L)

        val plan = planner.plan(
            cleanContext(repCount = 2),
            positiveInsight(),
            timestampMs = 12_000L,
        )

        assertEquals("improved", plan.outcome)
        assertTrue(plan.insight.message.contains("smoother") || plan.insight.message.contains("controlled"))
        assertEquals(LiveCueRewriteEvent.IMPROVEMENT_AFTER_WARNING, plan.rewriteRequest?.event)
    }

    @Test
    fun highPriorityCueWinsWithinSameRep() {
        val planner = LiveCuePlanner()
        val warning = planner.plan(
            rapidMovementContext(repCount = 1),
            rapidMovementInsight(priority = "high"),
            timestampMs = 1_000L,
        )

        val positive = planner.plan(
            cleanContext(repCount = 1),
            positiveInsight(),
            timestampMs = 3_000L,
        )

        assertEquals("same_rep_highest_priority_retained", positive.outcome)
        assertEquals(warning.insight.message, positive.insight.message)
        assertEquals("high", positive.insight.priority)
    }

    @Test
    fun limitedTrackingDoesNotCreateRewriteRequest() {
        val planner = LiveCuePlanner()
        val context = cleanContext(repCount = 1).copy(
            qualityFlags = listOf(
                QualityFlag(
                    id = "LOW_CONFIDENCE",
                    status = "LOW_CONFIDENCE",
                    value = 0.35f,
                    threshold = 0.6f,
                    evidence = "Pose confidence below threshold.",
                )
            ),
            evidenceCard = EvidenceCard(
                verdict = "LOW_CONFIDENCE",
                reason = "Pose tracking is not stable enough.",
            ),
        )

        val plan = planner.plan(
            context,
            CoachInsight(
                message = "Tracking is limited.",
                priority = "low",
                functionName = "warn_poor_visibility",
            ),
            timestampMs = 1_000L,
        )

        assertEquals("limited", plan.outcome)
        assertNull(plan.rewriteRequest)
    }

    @Test
    fun rewriteValidatorRejectsInvalidOrUnsafeResponses() {
        val request = LiveCueRewriteRequest(
            event = LiveCueRewriteEvent.IMPROVEMENT_AFTER_WARNING,
            intent = "positive_reinforcement",
            evidenceKey = "metric.tempo",
            phase = "completed",
            repNumber = 2,
            variantId = "improve_tempo_0",
            baseMessage = "Good rep.",
            plannedMessage = "That was smoother. Keep this tempo for the next rep.",
            priority = "low",
            tone = "encouraging",
            timestampMs = 2_000L,
        )

        assertFalse(LiveCueRewriteValidator.validate("not-json", request).accepted)
        assertFalse(
            LiveCueRewriteValidator.validate(
                """{"message":"Your injury risk is lower now.","tone":"encouraging","variant_id":"improve_tempo_0"}""",
                request,
            ).accepted
        )
        assertFalse(
            LiveCueRewriteValidator.validate(
                """{"message":"That was smoother.","tone":"encouraging","variant_id":"changed"}""",
                request,
            ).accepted
        )
        assertTrue(
            LiveCueRewriteValidator.validate(
                """{"message":"That was smoother. Keep that tempo.","tone":"encouraging","variant_id":"improve_tempo_0"}""",
                request,
            ).accepted
        )
    }

    @Test
    fun liveFrameStillSkipsButImprovementEventIsLowFrequencyCall() {
        val liveFrame = ModelInvocationScheduler.plan(
            ModelInvocationRequest(trigger = ModelInvocationTrigger.LIVE_FRAME)
        )
        val improvement = ModelInvocationScheduler.plan(
            ModelInvocationRequest(trigger = ModelInvocationTrigger.IMPROVEMENT_AFTER_WARNING)
        )

        assertEquals(ModelInvocationDecision.SKIP_DETERMINISTIC, liveFrame.decision)
        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, improvement.decision)
        assertEquals(ModelContextBudget.EVENT_COMPACT, improvement.contextBudget)
    }

    @Test
    fun liveCueModelPolicyOnlyMapsLowFrequencyEvents() {
        assertNull(LiveCueModelPolicy.triggerFor(LiveCueRewriteEvent.NONE))
        assertEquals(
            ModelInvocationTrigger.REP_COMPLETED,
            LiveCueModelPolicy.triggerFor(LiveCueRewriteEvent.REP_COMPLETED),
        )
        assertEquals(
            ModelInvocationTrigger.WARNING_PERSISTED,
            LiveCueModelPolicy.triggerFor(LiveCueRewriteEvent.WARNING_PERSISTED),
        )
        assertEquals(
            ModelInvocationTrigger.IMPROVEMENT_AFTER_WARNING,
            LiveCueModelPolicy.triggerFor(LiveCueRewriteEvent.IMPROVEMENT_AFTER_WARNING),
        )
        assertEquals(
            ModelInvocationTrigger.SESSION_MICRO_SUMMARY,
            LiveCueModelPolicy.triggerFor(LiveCueRewriteEvent.SESSION_MICRO_SUMMARY),
        )
    }

    private fun cleanContext(repCount: Int = 0): CoachContext =
        CoachContext(
            exercise = "chair_sit_to_stand",
            movementPhase = "completed",
            pattern = "bipedal_knee_dominant_sagittal",
            repCount = repCount,
            cleanStreak = 30,
            metrics = mapOf("tempo_sec" to 2.4f),
            muscle = null,
            warnings = emptyList(),
            qualityFlags = emptyList(),
            notApplicableFlags = emptyList(),
            evidenceCard = EvidenceCard(
                verdict = "OK",
                reason = "Clean movement window.",
                evidence = listOf(EvidenceItem("tempo", "2.4 sec")),
                evidenceRefs = listOf("metric.tempo"),
            ),
        )

    private fun rapidMovementContext(repCount: Int): CoachContext =
        cleanContext(repCount).copy(
            cleanStreak = 0,
            warnings = listOf(
                SafetyWarning(
                    rule = 6,
                    functionName = "warn_rapid_movement",
                    message = "Peak velocity crossed the controlled-tempo gate.",
                    severity = "high",
                    joint = "knee",
                )
            ),
            qualityFlags = listOf(
                QualityFlag(
                    id = "rapid_movement.knee_velocity",
                    evidenceId = "metric.knee_velocity",
                    status = "WARNING",
                    value = 238f,
                    threshold = 180f,
                    evidence = "Peak knee angular velocity was 238 deg/s.",
                    reason = "tempo_too_fast",
                    rule = 6,
                    joint = "knee",
                )
            ),
            evidenceCard = EvidenceCard(
                verdict = "WARNING",
                reason = "Knee angular velocity exceeded the controlled-tempo gate.",
                evidence = listOf(EvidenceItem("knee peak velocity", "238 deg/s")),
                evidenceRefs = listOf("metric.knee_velocity"),
            ),
        )

    private fun rapidMovementInsight(priority: String = "medium"): CoachInsight =
        CoachInsight(
            message = "That transition was rushed. Own the turn-around before you add speed.",
            priority = priority,
            localizationKey = "coach.fallback.warn_rapid_movement",
            functionName = "warn_rapid_movement",
            evidenceRefs = listOf("metric.knee_velocity"),
        )

    private fun positiveInsight(): CoachInsight =
        CoachInsight(
            message = "Good control. Keep the same rhythm.",
            priority = "low",
            localizationKey = "coach.fallback.positive_reinforcement",
            functionName = "positive_reinforcement",
            evidenceRefs = listOf("metric.tempo"),
        )
}
