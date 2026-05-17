package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Layer2TemporalInterpreterTest {
    @Test
    fun chairSitToStandCompletesOneRep() {
        val outputs = runKneeSequence(
            activity = "chair_sit_to_stand",
            angles = listOf(170f, 150f, 116f, 96f, 112f, 136f, 160f, 168f),
        )

        assertTrue(outputs.any { it.event == Layer2Event.REP_STARTED })
        assertEquals(Layer2Event.REP_COMPLETED, outputs.last().event)
        assertEquals(1, outputs.last().repCount)
        assertEquals(Layer2Phase.STANDING_STABILIZED, outputs.last().phase)
        assertTrue(outputs.last().rulePolicy.allowHardJudgment)
        assertTrue("layer2.event.rep_completed" in outputs.last().evidenceRefs)
    }

    @Test
    fun partialChairSitToStandDoesNotCountRep() {
        val outputs = runKneeSequence(
            activity = "chair_sit_to_stand",
            angles = listOf(170f, 150f, 120f, 110f, 126f, 138f),
        )

        assertEquals(0, outputs.last().repCount)
        assertFalse(outputs.any { it.event == Layer2Event.REP_COMPLETED })
    }

    @Test
    fun lowConfidenceAbstains() {
        val interpreter = Layer2TemporalInterpreter()
        val output = interpreter.update(
            Layer2FrameFeatures(
                timestampMs = 0L,
                activityHint = "chair_sit_to_stand",
                poseConfidence = 0.42f,
                metrics = mapOf("knee_angle" to 170f),
            )
        )

        assertEquals(Layer2Phase.ABSTAIN, output.phase)
        assertEquals(Layer2Event.ABSTAIN, output.event)
        assertEquals("low_pose_confidence", output.abstainReason)
        assertFalse(output.rulePolicy.allowHardJudgment)
    }

    @Test
    fun predictedTrackingBlocksHardJudgment() {
        val interpreter = Layer2TemporalInterpreter()
        val output = interpreter.update(
            Layer2FrameFeatures(
                timestampMs = 0L,
                activityHint = "chair_sit_to_stand",
                personTrackingState = PersonTrackingState.PREDICTED,
                metrics = mapOf("knee_angle" to 170f),
            )
        )

        assertEquals(Layer2Phase.ABSTAIN, output.phase)
        assertEquals("person_not_observed", output.abstainReason)
        assertFalse(output.rulePolicy.allowHardJudgment)
    }

    @Test
    fun nonSeniorLungeDemotesToUnknownMonitorOnly() {
        val outputs = runKneeSequence(
            activity = "lunge",
            angles = listOf(170f, 148f, 112f, 104f, 132f),
            metric = "front_knee_angle",
        )
        val last = outputs.last()

        assertEquals("unknown", last.activityHypothesis.label)
        assertEquals(Layer2Event.NONE, last.event)
        assertTrue(last.nonSeniorLabelDemoted)
        assertFalse(last.rulePolicy.allowHardJudgment)
        assertEquals("non_senior_activity_demoted", last.abstainReason)
        assertTrue("layer2.normalizer.non_senior_label_demoted" in last.evidenceRefs)
    }

    @Test
    fun basketballJumpShotDemotesWithoutSubActions() {
        val interpreter = Layer2TemporalInterpreter()
        val frames = listOf(
            mapOf("knee_angle" to 170f),
            mapOf("knee_angle" to 145f),
            mapOf("knee_angle" to 162f),
            mapOf("knee_angle" to 168f, "shoulder_angle" to 132f),
            mapOf("knee_angle" to 168f, "shoulder_angle" to 138f, "elbow_angle" to 166f),
        )
        val outputs = frames.mapIndexed { index, metrics ->
            interpreter.update(
                Layer2FrameFeatures(
                    timestampMs = index * 120L,
                    activityHint = "basketball_jump_shot",
                    activityConfidence = 0.86f,
                    poseConfidence = 0.9f,
                    metrics = metrics,
                )
            )
        }
        val last = outputs.last()

        assertEquals("unknown", last.activityHypothesis.label)
        assertEquals(Layer2Event.NONE, last.event)
        assertTrue(last.nonSeniorLabelDemoted)
        assertFalse(last.rulePolicy.allowHardJudgment)
        assertTrue(last.subActions.isEmpty())
    }

    @Test
    fun supportedSquatCompletesRepButStaysMonitorOnlyForDemoP0() {
        val outputs = runKneeSequence(
            activity = "supported_squat",
            angles = listOf(170f, 150f, 116f, 96f, 112f, 136f, 160f, 168f),
            extraMetrics = mapOf("support_contact_proxy" to 1f),
        )
        val bottom = outputs.first { it.phase == Layer2Phase.SQUAT_BOTTOM }
        val last = outputs.last()

        assertEquals("supported_squat", last.activityHypothesis.label)
        assertEquals(Layer2Event.REP_COMPLETED, last.event)
        assertEquals(1, last.repCount)
        assertFalse(bottom.rulePolicy.allowHardJudgment)
        assertEquals("monitor_only", last.rulePolicy.outputState)
        assertTrue(1 in last.rulePolicy.blockedRules)
        assertTrue(last.rulePolicy.notes.any { it.contains("supported_squat_is_monitor_only") })
    }

    @Test
    fun bodyweightOrGobletSquatCompletesRepAndIsJudgeable() {
        val outputs = runKneeSequence(
            activity = "bodyweight_or_goblet_squat",
            angles = listOf(170f, 150f, 116f, 96f, 112f, 136f, 160f, 168f),
        )
        val last = outputs.last()

        assertEquals("bodyweight_or_goblet_squat", last.activityHypothesis.label)
        assertEquals(Layer2Event.REP_COMPLETED, last.event)
        assertEquals(1, last.repCount)
        assertEquals(Layer2Phase.STANDING_STABILIZED, last.phase)
        assertTrue(last.rulePolicy.allowHardJudgment)
        assertEquals("judgeable", last.rulePolicy.outputState)
    }

    @Test
    fun setupTransitionDoesNotCountRepOrTriggerEvent() {
        val outputs = runKneeSequence(
            activity = "setup_transition",
            angles = listOf(170f, 140f, 100f, 165f, 170f),
        )
        val last = outputs.last()

        assertEquals("setup_transition", last.activityHypothesis.label)
        assertEquals(Layer2Phase.SETUP, last.phase)
        assertEquals(Layer2Event.NONE, last.event)
        assertEquals(0, last.repCount)
        assertFalse(last.rulePolicy.allowHardJudgment)
    }

    @Test
    fun balanceHoldStartsAndCompletesAfterTargetDuration() {
        val interpreter = Layer2TemporalInterpreter()
        val outputs = (0..5).map { second ->
            interpreter.update(
                Layer2FrameFeatures(
                    timestampMs = second * 1_000L,
                    activityHint = "balance_hold",
                    poseConfidence = 0.9f,
                    metrics = mapOf("sway_norm" to 0.03f),
                )
            )
        }

        assertEquals(Layer2Event.BALANCE_HOLD_STARTED, outputs.first().event)
        assertEquals(Layer2Event.BALANCE_HOLD_COMPLETED, outputs.last().event)
        assertEquals(5_000L, outputs.last().holdDurationMs)
        assertFalse(outputs.last().rulePolicy.allowHardJudgment)
        assertTrue(outputs.last().evidenceRefs.contains("layer2.event.balance_hold_completed"))
    }

    @Test
    fun balanceUnstableResetsHoldAndStaysMonitorOnly() {
        val interpreter = Layer2TemporalInterpreter()
        interpreter.update(
            Layer2FrameFeatures(
                timestampMs = 0L,
                activityHint = "balance_hold",
                poseConfidence = 0.9f,
                metrics = mapOf("sway_norm" to 0.03f),
            )
        )
        val output = interpreter.update(
            Layer2FrameFeatures(
                timestampMs = 1_000L,
                activityHint = "balance_hold",
                poseConfidence = 0.9f,
                metrics = mapOf("sway_norm" to 0.12f),
            )
        )

        assertEquals(Layer2Phase.BALANCE_UNSTABLE, output.phase)
        assertEquals(Layer2Event.MONITOR_ONLY, output.event)
        assertEquals("balance_unstable_monitor_only", output.abstainReason)
        assertEquals(0L, output.holdDurationMs)
        assertFalse(output.rulePolicy.allowHardJudgment)
    }

    @Test
    fun temporalAnalyzerLayer2AndSchedulerDeferCleanRep() {
        val temporal = TemporalMotionAnalyzer()
        val layer2 = Layer2TemporalInterpreter()
        val angles = listOf(175f, 160f, 130f, 100f, 85f, 105f, 135f, 170f, 172f)
        var layer2Output = Layer2Output(
            timestampMs = 0L,
            activityHypothesis = Layer2ActivityHypothesis("unknown", 0f, emptyList()),
            phase = Layer2Phase.UNKNOWN,
            event = Layer2Event.NONE,
            confidence = 0f,
            rulePolicy = Layer2RulePolicy.monitorOnly("test_initial"),
            evidenceRefs = emptyList(),
        )
        var temporalResult = TemporalMotionAnalyzer.Result()
        var sawTemporalWindow = false

        angles.forEachIndexed { index, angle ->
            temporalResult = temporal.addSample(
                frameIndex = index,
                timestampMs = index * 400L,
                exercise = "squat",
                metrics = mapOf("knee_angle" to angle),
                confidenceFloor = 0.84f,
            )
            layer2Output = layer2.update(
                Layer2FrameFeatures(
                    timestampMs = index * 400L,
                    activityHint = "chair_sit_to_stand",
                    activityConfidence = 0.91f,
                    poseConfidence = 0.84f,
                    metrics = mapOf("knee_angle" to angle),
                )
            )
            if (temporalResult.motionFeatureWindow != null) {
                sawTemporalWindow = true
            }
        }

        assertTrue(sawTemporalWindow)
        assertEquals(Layer2Event.REP_COMPLETED, layer2Output.event)
        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.REP_COMPLETED,
                confidenceFloor = layer2Output.confidence,
                capabilityJudgmentAllowed = layer2Output.rulePolicy.allowHardJudgment,
            )
        )

        assertEquals(ModelInvocationDecision.DEFER_TO_SESSION_END, plan.decision)
    }

    @Test
    fun highConfidenceWarningCanCallE2B() {
        val layer2Output = runKneeSequence(
            activity = "chair_sit_to_stand",
            angles = listOf(170f, 150f, 116f, 96f, 112f, 136f, 160f, 168f),
        ).last()

        val plan = ModelInvocationScheduler.plan(
            ModelInvocationRequest(
                trigger = ModelInvocationTrigger.REP_COMPLETED,
                confidenceFloor = layer2Output.confidence,
                capabilityJudgmentAllowed = layer2Output.rulePolicy.allowHardJudgment,
                hasCriticalOrWarningEvidence = true,
                needsLanguageExplanation = true,
            )
        )

        assertEquals(ModelInvocationDecision.CALL_E2B_NOW, plan.decision)
    }

    private fun runKneeSequence(
        activity: String,
        angles: List<Float>,
        metric: String = "knee_angle",
        extraMetrics: Map<String, Float> = emptyMap(),
    ): List<Layer2Output> {
        val interpreter = Layer2TemporalInterpreter()
        return angles.mapIndexed { index, angle ->
            interpreter.update(
                Layer2FrameFeatures(
                    timestampMs = index * 120L,
                    activityHint = activity,
                    activityConfidence = 0.9f,
                    poseConfidence = 0.9f,
                    metrics = mapOf(metric to angle) + extraMetrics,
                )
            )
        }
    }
}
