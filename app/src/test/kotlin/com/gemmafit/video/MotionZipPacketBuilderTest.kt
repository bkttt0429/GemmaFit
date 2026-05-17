package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionZipPacketBuilderTest {
    @Test
    fun repEventPacketPreservesExtremaConfidenceAndEvidenceRefs() {
        val window = MotionFeatureWindow(
            windowId = "motion.rep.1",
            trigger = "REP_COMPLETED",
            windowMs = 3_200L,
            exercise = "chair_sit_to_stand",
            source = listOf("temporal_motion_analyzer", "pose_sequence"),
            features = MotionFeatureValues(
                hipVerticalDisplacement = 0.13f,
                kneeAngleMin = 78f,
                kneeAngleMax = 166f,
                primaryAngleMin = 78f,
                primaryAngleMax = 166f,
                rangeOfMotionDeg = 88f,
                repDurationMs = 3_200L,
                peakVelocityDegS = 42f,
                velocityPeak = "low",
                stabilizationMs = 800L,
                confidenceFloor = 0.82f,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = "controlled",
                phaseSequenceEstimate = listOf("low_stable", "upward_transition", "high_stable"),
                repCompleted = true,
            ),
            evidenceRefs = listOf(
                "metric.motion.rom",
                "metric.motion.confidence_floor",
            ),
            limits = listOf("derived_from_single_camera_pose", "no_force_or_grf"),
        )
        val layer2Output = Layer2Output(
            timestampMs = 9_600L,
            activityHypothesis = Layer2ActivityHypothesis(
                label = "chair_sit_to_stand",
                confidence = 0.91f,
                source = listOf("user_selected_task", "derived_motion_features"),
            ),
            phase = Layer2Phase.STANDING_STABILIZED,
            event = Layer2Event.REP_COMPLETED,
            confidence = 0.88f,
            repCount = 1,
            rulePolicy = Layer2RulePolicy.controlledStrength(),
            evidenceRefs = listOf(
                "layer2.phase.standing_stabilized",
                "layer2.event.rep_completed",
            ),
        )

        val packet = MotionZipPacketBuilder.fromRepEvent(window, layer2Output, framesKept = 24)
        val block = packet.compressedSparseBlocks.single()

        assertEquals("motion_zip_v4_v1", packet.schemaVersion)
        assertEquals("REP_COMPLETED", packet.trigger)
        assertEquals(1, packet.heavilyCompressedSummary.completedReps)
        assertEquals("controlled", packet.heavilyCompressedSummary.tempoBand)
        assertEquals(0.82f, packet.heavilyCompressedSummary.confidenceFloor, 0.001f)
        assertEquals(listOf(6_400L, 9_600L), block.timeRangeMs)
        assertEquals(78f, block.preservedExtrema["knee_angle_min"])
        assertEquals(166f, block.preservedExtrema["knee_angle_max"])
        assertEquals(42f, block.preservedExtrema["peak_velocity_deg_s"])
        assertEquals(0.82f, block.preservedExtrema["confidence_floor"])
        assertTrue("low_stable" in block.tokens)
        assertTrue("rep_completed" in block.tokens)
        assertTrue("metric.motion.rom" in packet.evidenceRefs)
        assertTrue("layer2.event.rep_completed" in packet.evidenceRefs)
        assertTrue("confidence_floor" in packet.safetyPreserved)
        assertTrue("unsupported_claim_boundaries" in packet.safetyPreserved)
        assertTrue("no_joint_force_or_grf" in packet.limits)
        assertTrue("no_emg_or_muscle_activation" in packet.limits)
    }

    @Test
    fun balanceHoldCompletedCountsStabilityEvent() {
        val window = MotionFeatureWindow(
            windowId = "motion.balance.1",
            trigger = "BALANCE_HOLD_COMPLETED",
            windowMs = 5_000L,
            exercise = "balance_hold",
            source = listOf("layer2_temporal_interpreter"),
            features = MotionFeatureValues(
                primaryAngleMin = 160f,
                primaryAngleMax = 166f,
                rangeOfMotionDeg = 6f,
                repDurationMs = 5_000L,
                peakVelocityDegS = 8f,
                velocityPeak = "low",
                confidenceFloor = 0.9f,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = "hold",
                phaseSequenceEstimate = listOf("balance_holding"),
                repCompleted = false,
            ),
            evidenceRefs = listOf("metric.motion.confidence_floor"),
        )
        val layer2Output = Layer2Output(
            timestampMs = 5_000L,
            activityHypothesis = Layer2ActivityHypothesis(
                label = "balance_hold",
                confidence = 0.9f,
                source = listOf("activity_hint", "derived_motion_features"),
            ),
            phase = Layer2Phase.BALANCE_HOLDING,
            event = Layer2Event.BALANCE_HOLD_COMPLETED,
            confidence = 0.78f,
            holdDurationMs = 5_000L,
            rulePolicy = Layer2RulePolicy.balanceMonitorOnly(),
            evidenceRefs = listOf(
                "layer2.phase.balance_holding",
                "layer2.event.balance_hold_completed",
            ),
        )

        val packet = MotionZipPacketBuilder.fromRepEvent(window, layer2Output, framesKept = 8)

        assertEquals(1, packet.heavilyCompressedSummary.stabilityEvents)
        assertEquals("balance_hold_completed", packet.heavilyCompressedSummary.event)
        assertTrue("layer2.event.balance_hold_completed" in packet.evidenceRefs)
    }

    @Test
    fun packetCarriesMonitorOrAbstainStateInsteadOfHardJudgment() {
        val window = MotionFeatureWindow(
            windowId = "motion.rep.2",
            trigger = "REP_COMPLETED",
            windowMs = 1_200L,
            exercise = "unknown",
            source = listOf("temporal_motion_analyzer"),
            features = MotionFeatureValues(
                primaryAngleMin = 100f,
                primaryAngleMax = 150f,
                rangeOfMotionDeg = 50f,
                repDurationMs = 1_200L,
                peakVelocityDegS = 80f,
                velocityPeak = "moderate",
                confidenceFloor = 0.48f,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = "brisk",
                phaseSequenceEstimate = listOf("movement_start", "movement_end"),
                repCompleted = true,
            ),
            evidenceRefs = listOf("metric.motion.confidence_floor"),
        )
        val layer2Output = Layer2Output(
            timestampMs = 2_000L,
            activityHypothesis = Layer2ActivityHypothesis("unknown", 0.2f, listOf("activity_hint_low_confidence")),
            phase = Layer2Phase.ABSTAIN,
            event = Layer2Event.ABSTAIN,
            confidence = 0f,
            abstainReason = "low_pose_confidence",
            rulePolicy = Layer2RulePolicy.abstain("low_pose_confidence"),
            evidenceRefs = listOf("layer2.event.abstain"),
        )

        val packet = MotionZipPacketBuilder.fromRepEvent(window, layer2Output)
        val block = packet.compressedSparseBlocks.single()

        assertEquals("abstain", packet.heavilyCompressedSummary.outputState)
        assertEquals("low_pose_confidence", block.abstainReason)
        assertEquals("abstain", block.rulePolicyState)
        assertFalse(packet.limits.any { it == "force" || it == "emg" })
        assertTrue("metric.motion.confidence_floor" in packet.evidenceRefs)
        assertTrue("layer2.event.abstain" in packet.evidenceRefs)
    }

    @Test
    fun lowConfidenceFloorAbstainsPacketEvenWhenLayer2WasJudgeable() {
        val window = MotionFeatureWindow(
            windowId = "motion.video.lunge.low_visibility",
            trigger = "VIDEO_ANALYSIS_SUMMARY",
            windowMs = 1_600L,
            exercise = "lunge",
            source = listOf("video_pose_samples"),
            features = MotionFeatureValues(
                kneeAngleMin = 26.7f,
                kneeAngleMax = 178.9f,
                primaryAngleMin = 26.7f,
                primaryAngleMax = 178.9f,
                rangeOfMotionDeg = 152.2f,
                repDurationMs = 1_600L,
                peakVelocityDegS = 776.4f,
                velocityPeak = "high",
                confidenceFloor = 0.5145f,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = "mixed_with_high_velocity_events",
                phaseSequenceEstimate = listOf("lunge_like_unilateral_motion", "monitor_only"),
                repCompleted = false,
            ),
            evidenceRefs = listOf(
                "metric.motion.peak_velocity",
                "metric.motion.confidence_floor",
            ),
        )
        val layer2Output = Layer2Output(
            timestampMs = 8_609L,
            activityHypothesis = Layer2ActivityHypothesis(
                label = "lunge",
                confidence = 0.84f,
                source = listOf("derived_motion_features"),
            ),
            phase = Layer2Phase.RETURNING,
            event = Layer2Event.MONITOR_ONLY,
            confidence = 0.7f,
            rulePolicy = Layer2RulePolicy.monitorOnly(
                "unilateral_phase_blocks_bilateral_symmetry_hard_warning",
            ),
            evidenceRefs = listOf(
                "layer2.activity.lunge",
                "layer2.event.monitor_only",
            ),
        )

        val packet = MotionZipPacketBuilder.fromRepEvent(window, layer2Output, framesKept = 2)
        val block = packet.compressedSparseBlocks.single()

        assertEquals("abstain", packet.heavilyCompressedSummary.outputState)
        assertEquals("abstain", block.rulePolicyState)
        assertEquals("low_keypoint_visibility", block.abstainReason)
        assertEquals(0.5145f, block.preservedExtrema["confidence_floor"])
        assertTrue("metric.motion.peak_velocity" in packet.evidenceRefs)
        assertTrue("layer2.event.monitor_only" in packet.evidenceRefs)
    }

    @Test
    fun sessionPacketMergesSparseBlocksAndReportsUiStatus() {
        val judgeable = testPacket(
            id = "motion.rep.clean",
            confidenceFloor = 0.82f,
            policy = Layer2RulePolicy.controlledStrength(),
            event = Layer2Event.REP_COMPLETED,
            phase = Layer2Phase.STANDING_STABILIZED,
            evidenceRef = "layer2.event.rep_completed",
        )
        val abstain = testPacket(
            id = "motion.rep.low_visibility",
            confidenceFloor = 0.42f,
            policy = Layer2RulePolicy.controlledStrength(),
            event = Layer2Event.MONITOR_ONLY,
            phase = Layer2Phase.RETURNING,
            evidenceRef = "layer2.event.monitor_only",
        )

        val session = MotionZipPacketBuilder.fromSessionPackets(
            windowId = "video.session.motionzip",
            packets = listOf(judgeable, abstain),
        )
        val status = MotionZipPacketBuilder.statusForPacket(session, source = "unit_test")

        requireNotNull(session)
        assertEquals("VIDEO_ANALYSIS_SUMMARY", session.trigger)
        assertEquals(2, session.compressedSparseBlocks.size)
        assertEquals(2, status.blockCount)
        assertEquals(1, status.judgeableBlocks)
        assertEquals(1, status.abstainBlocks)
        assertEquals("abstain", status.latestOutputState)
        assertEquals("low_keypoint_visibility", status.latestAbstainReason)
        assertTrue("layer2.event.rep_completed" in session.evidenceRefs)
        assertTrue("layer2.event.monitor_only" in session.evidenceRefs)
    }

    private fun testPacket(
        id: String,
        confidenceFloor: Float,
        policy: Layer2RulePolicy,
        event: Layer2Event,
        phase: Layer2Phase,
        evidenceRef: String,
    ): MotionZipPacket {
        val window = MotionFeatureWindow(
            windowId = id,
            trigger = "REP_COMPLETED",
            windowMs = 1_600L,
            exercise = "chair_sit_to_stand",
            source = listOf("unit_test"),
            features = MotionFeatureValues(
                kneeAngleMin = 80f,
                kneeAngleMax = 165f,
                primaryAngleMin = 80f,
                primaryAngleMax = 165f,
                rangeOfMotionDeg = 85f,
                repDurationMs = 1_600L,
                peakVelocityDegS = 40f,
                velocityPeak = "low",
                confidenceFloor = confidenceFloor,
            ),
            derivedLabels = MotionDerivedLabels(
                tempoBand = "controlled",
                phaseSequenceEstimate = listOf("low_stable", "high_stable"),
                repCompleted = event == Layer2Event.REP_COMPLETED,
            ),
            evidenceRefs = listOf("metric.motion.confidence_floor"),
        )
        val layer2 = Layer2Output(
            timestampMs = 4_000L,
            activityHypothesis = Layer2ActivityHypothesis(
                label = "chair_sit_to_stand",
                confidence = 0.9f,
                source = listOf("unit_test"),
            ),
            phase = phase,
            event = event,
            confidence = 0.8f,
            rulePolicy = policy,
            evidenceRefs = listOf(evidenceRef),
        )
        return MotionZipPacketBuilder.fromRepEvent(window, layer2)
    }
}
