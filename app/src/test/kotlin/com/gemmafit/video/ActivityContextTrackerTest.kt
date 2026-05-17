package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityContextTrackerTest {
    @Test
    fun locksChairSitToStandAfterConsistentWindows() {
        val tracker = ActivityContextTracker()

        val first = tracker.update(chairObservation(timestampMs = 0L))
        val second = tracker.update(chairObservation(timestampMs = 2_000L))

        assertEquals(ActivityContextState.CALIBRATING, first.state)
        assertEquals(ActivityContextState.LOCKED, second.state)
        assertEquals("chair_sit_to_stand", second.taskLabel)
        assertTrue(second.confidence > 0.9f)
        assertTrue("activity_context.locked" in second.evidenceRefs)
    }

    @Test
    fun locksSupportedSquatAfterConsistentWindows() {
        val tracker = ActivityContextTracker()

        tracker.update(supportedSquatObservation(timestampMs = 0L))
        val locked = tracker.update(supportedSquatObservation(timestampMs = 1_600L))

        assertEquals(ActivityContextState.LOCKED, locked.state)
        assertEquals("supported_squat", locked.taskLabel)
        assertTrue(locked.templateScores.getValue("supported_squat") > 0.9f)
    }

    @Test
    fun locksBodyweightOrGobletSquatAfterConsistentWindows() {
        val tracker = ActivityContextTracker()

        tracker.update(bodyweightSquatObservation(timestampMs = 0L))
        val locked = tracker.update(bodyweightSquatObservation(timestampMs = 1_600L))

        assertEquals(ActivityContextState.LOCKED, locked.state)
        assertEquals("bodyweight_or_goblet_squat", locked.taskLabel)
        assertTrue(locked.templateScores.getValue("bodyweight_or_goblet_squat") > 0.85f)
    }

    @Test
    fun ambiguousChairVsSquatDoesNotFalseLock() {
        val tracker = ActivityContextTracker()

        val ambiguous = tracker.update(
            ActivityContextObservation(
                timestampMs = 1_200L,
                layer2Label = "chair_sit_to_stand",
                confidence = 0.9f,
                supportPattern = "hand_support",
                bottomDwellMs = 300L,
                trunkLeanDeg = 31f,
                hipVerticalDisplacement = 0.12f,
                phaseSequence = listOf("descending", "seated_low", "squat_bottom", "rising"),
                evidenceRefs = listOf("metric.activity.ambiguous.fixture"),
            )
        )

        assertEquals(ActivityContextState.AMBIGUOUS, ambiguous.state)
        assertNull(ambiguous.taskLabel)
        assertEquals("chair_vs_squat_scores_within_margin", ambiguous.ambiguityNote)
        assertTrue("activity_context.ambiguous" in ambiguous.evidenceRefs)
    }

    @Test
    fun preRepChairSetupCalibratesWithoutLocking() {
        val tracker = ActivityContextTracker()

        val first = tracker.observePreRepCandidate(preRepChairObservation(timestampMs = 0L))
        val second = tracker.observePreRepCandidate(preRepChairObservation(timestampMs = 240L))

        assertEquals(ActivityContextState.CALIBRATING, first?.state)
        assertEquals(ActivityContextState.CALIBRATING, second?.state)
        assertEquals("chair_sit_to_stand", second?.taskLabel)
        assertEquals("chair_sts_pre_rep_setup_evidence", second?.ambiguityNote)
        assertTrue("activity_context.calibrating.pre_rep_chair_sts" in (second?.evidenceRefs ?: emptyList()))
    }

    @Test
    fun motionZipPacketCarriesActivityContext() {
        val packet = MotionZipPacketBuilder.fromRepEvent(
            motionFeatureWindow = chairWindow(),
            layer2Output = chairLayer2Output(),
            activityContext = ActivityContext(
                state = ActivityContextState.LOCKED,
                taskLabel = "chair_sit_to_stand",
                confidence = 0.96f,
                templateScores = mapOf("chair_sit_to_stand" to 0.96f, "supported_squat" to 0.2f),
                evidenceRefs = listOf("activity_context.locked"),
            ),
        )

        assertEquals(ActivityContextState.LOCKED, packet.activityContext.state)
        assertEquals("chair_sit_to_stand", packet.activityContext.taskLabel)
        val debugMap = packet.toDebugMap()
        val activityContext = debugMap["activity_context"] as Map<*, *>
        assertEquals("locked", activityContext["state"])
        assertEquals("chair_sit_to_stand", activityContext["task_label"])
    }

    private fun chairObservation(timestampMs: Long): ActivityContextObservation {
        return ActivityContextObservation(
            timestampMs = timestampMs,
            layer2Label = "chair_sit_to_stand",
            confidence = 0.92f,
            supportPattern = "chair",
            bottomDwellMs = 720L,
            trunkLeanDeg = 42f,
            hipVerticalDisplacement = 0.15f,
            phaseSequence = listOf("descending", "seated_low", "rising", "standing_stabilized"),
            evidenceRefs = listOf("metric.activity.chair.fixture"),
        )
    }

    private fun preRepChairObservation(timestampMs: Long): ActivityContextObservation {
        return ActivityContextObservation(
            timestampMs = timestampMs,
            layer2Label = "setup_transition",
            confidence = 0.72f,
            supportPattern = "chair",
            phaseSequence = listOf("standing_stabilized"),
            evidenceRefs = listOf("visual_context.support"),
        )
    }

    private fun supportedSquatObservation(timestampMs: Long): ActivityContextObservation {
        return ActivityContextObservation(
            timestampMs = timestampMs,
            layer2Label = "supported_squat",
            confidence = 0.91f,
            supportPattern = "hand_support",
            bottomDwellMs = 180L,
            trunkLeanDeg = 20f,
            hipVerticalDisplacement = 0.22f,
            phaseSequence = listOf("descending", "squat_bottom", "rising", "standing_stabilized"),
            evidenceRefs = listOf("metric.activity.squat.fixture"),
        )
    }

    private fun bodyweightSquatObservation(timestampMs: Long): ActivityContextObservation {
        return ActivityContextObservation(
            timestampMs = timestampMs,
            layer2Label = "bodyweight_or_goblet_squat",
            confidence = 0.9f,
            supportPattern = "none",
            bottomDwellMs = 180L,
            trunkLeanDeg = 24f,
            hipVerticalDisplacement = 0.24f,
            phaseSequence = listOf("descending", "squat_bottom", "rising", "standing_stabilized"),
            evidenceRefs = listOf("metric.activity.bodyweight_squat.fixture"),
        )
    }

    private fun chairWindow(): MotionFeatureWindow {
        return MotionFeatureWindow(
            windowId = "motion.rep.chair.1",
            trigger = "REP_COMPLETED",
            windowMs = 2_400L,
            exercise = "chair_sit_to_stand",
            source = listOf("unit_test"),
            features = MotionFeatureValues(
                hipVerticalDisplacement = 0.14f,
                kneeAngleMin = 88f,
                kneeAngleMax = 170f,
                primaryAngleMin = 88f,
                primaryAngleMax = 170f,
                rangeOfMotionDeg = 82f,
                repDurationMs = 2_400L,
                peakVelocityDegS = 110f,
                velocityPeak = "controlled",
                stabilizationMs = 700L,
                confidenceFloor = 0.88f,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = "controlled",
                phaseSequenceEstimate = listOf("descending", "seated_low", "rising", "standing_stabilized"),
                repCompleted = true,
                supportPattern = "chair",
            ),
            evidenceRefs = listOf("metric.activity.chair.fixture"),
        )
    }

    private fun chairLayer2Output(): Layer2Output {
        return Layer2Output(
            timestampMs = 2_400L,
            activityHypothesis = Layer2ActivityHypothesis(
                label = "chair_sit_to_stand",
                confidence = 0.92f,
                source = listOf("unit_test"),
            ),
            phase = Layer2Phase.STANDING_STABILIZED,
            event = Layer2Event.REP_COMPLETED,
            confidence = 0.9f,
            repCount = 1,
            rulePolicy = Layer2RulePolicy.controlledStrength(),
            evidenceRefs = listOf("layer2.event.rep_completed"),
        )
    }
}
