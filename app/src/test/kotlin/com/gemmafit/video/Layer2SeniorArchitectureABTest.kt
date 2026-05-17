package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class Layer2SeniorArchitectureABTest {
    @Test
    fun seniorScopedInterpreterIsMoreControlledThanLegacyMixedActivityLayer() {
        val results = benchmarkCases().map { testCase ->
            CaseComparison(
                name = testCase.name,
                legacy = LegacyLayer2Probe().run(testCase.frames, testCase.riskyForHardJudgment),
                current = CurrentLayer2Probe().run(testCase.frames, testCase.riskyForHardJudgment),
            )
        }

        val legacyTotals = results.map { it.legacy }.sum()
        val currentTotals = results.map { it.current }.sum()

        assertEquals(45, legacyTotals.frames)
        assertEquals(45, currentTotals.frames)

        assertEquals(13, legacyTotals.riskyHardJudgmentFrames)
        assertEquals(0, currentTotals.riskyHardJudgmentFrames)
        assertEquals(0, legacyTotals.nonSeniorDemotedFrames)
        assertEquals(10, currentTotals.nonSeniorDemotedFrames)
        assertEquals(4, legacyTotals.maxSubActionCount)
        assertEquals(0, currentTotals.maxSubActionCount)
        assertEquals(8, legacyTotals.balanceStabilityEvents)
        assertEquals(4, currentTotals.balanceStabilityEvents)
        assertEquals(5, results.single { it.name == "setup_transition" }.legacy.nonNoneEvents)
        assertEquals(0, results.single { it.name == "setup_transition" }.current.nonNoneEvents)

        assertEquals(2, legacyTotals.repCompletedEvents)
        assertEquals(2, currentTotals.repCompletedEvents)
        assertTrue(results.single { it.name == "clean_chair_sit_to_stand" }.current.repCompletedEvents == 1)
        assertTrue(results.single { it.name == "supported_squat" }.current.repCompletedEvents == 1)
    }

    private fun benchmarkCases(): List<BenchmarkCase> {
        return listOf(
            BenchmarkCase(
                name = "clean_chair_sit_to_stand",
                frames = kneeSequence("chair_sit_to_stand", listOf(170f, 150f, 116f, 96f, 112f, 136f, 160f, 168f)),
            ),
            BenchmarkCase(
                name = "partial_chair_sit_to_stand",
                frames = kneeSequence("chair_sit_to_stand", listOf(170f, 150f, 120f, 110f, 126f, 138f)),
            ),
            BenchmarkCase(
                name = "non_senior_lunge",
                riskyForHardJudgment = true,
                frames = kneeSequence(
                    activity = "lunge",
                    angles = listOf(170f, 148f, 112f, 104f, 132f),
                    metric = "front_knee_angle",
                ),
            ),
            BenchmarkCase(
                name = "non_senior_basketball",
                riskyForHardJudgment = true,
                frames = listOf(
                    mapOf("knee_angle" to 170f),
                    mapOf("knee_angle" to 145f),
                    mapOf("knee_angle" to 162f),
                    mapOf("knee_angle" to 168f, "shoulder_angle" to 132f),
                    mapOf("knee_angle" to 168f, "shoulder_angle" to 138f, "elbow_angle" to 166f),
                ).mapIndexed { index, metrics ->
                    Layer2FrameFeatures(
                        timestampMs = index * 120L,
                        activityHint = "basketball_jump_shot",
                        activityConfidence = 0.86f,
                        poseConfidence = 0.9f,
                        metrics = metrics,
                    )
                },
            ),
            BenchmarkCase(
                name = "supported_squat",
                riskyForHardJudgment = true,
                frames = kneeSequence(
                    activity = "supported_squat",
                    angles = listOf(170f, 150f, 116f, 96f, 112f, 136f, 160f, 168f),
                    extraMetrics = mapOf("support_contact_proxy" to 1f),
                ),
            ),
            BenchmarkCase(
                name = "stable_balance_hold",
                frames = (0..5).map { second ->
                    Layer2FrameFeatures(
                        timestampMs = second * 1_000L,
                        activityHint = "balance_hold",
                        activityConfidence = 0.9f,
                        poseConfidence = 0.9f,
                        metrics = mapOf("sway_norm" to 0.03f),
                    )
                },
            ),
            BenchmarkCase(
                name = "unstable_balance_hold",
                frames = listOf(0.03f, 0.12f).mapIndexed { index, sway ->
                    Layer2FrameFeatures(
                        timestampMs = index * 1_000L,
                        activityHint = "balance_hold",
                        activityConfidence = 0.9f,
                        poseConfidence = 0.9f,
                        metrics = mapOf("sway_norm" to sway),
                    )
                },
            ),
            BenchmarkCase(
                name = "setup_transition",
                frames = kneeSequence("setup_transition", listOf(170f, 140f, 100f, 165f, 170f)),
            ),
        )
    }

    private fun kneeSequence(
        activity: String,
        angles: List<Float>,
        metric: String = "knee_angle",
        extraMetrics: Map<String, Float> = emptyMap(),
    ): List<Layer2FrameFeatures> {
        return angles.mapIndexed { index, angle ->
            Layer2FrameFeatures(
                timestampMs = index * 400L,
                activityHint = activity,
                activityConfidence = 0.9f,
                poseConfidence = 0.9f,
                metrics = mapOf(metric to angle) + extraMetrics,
            )
        }
    }

    private data class BenchmarkCase(
        val name: String,
        val frames: List<Layer2FrameFeatures>,
        val riskyForHardJudgment: Boolean = false,
    )

    private data class CaseComparison(
        val name: String,
        val legacy: ProbeSummary,
        val current: ProbeSummary,
    )

    private data class ProbeFrame(
        val activity: String,
        val event: String,
        val allowHardJudgment: Boolean,
        val subActionCount: Int = 0,
        val nonSeniorDemoted: Boolean = false,
        val isBalanceStabilityEvent: Boolean = false,
    )

    private data class ProbeSummary(
        val frames: Int,
        val riskyHardJudgmentFrames: Int,
        val nonSeniorDemotedFrames: Int,
        val maxSubActionCount: Int,
        val balanceStabilityEvents: Int,
        val nonNoneEvents: Int,
        val repCompletedEvents: Int,
    )

    private fun List<ProbeSummary>.sum(): ProbeSummary {
        return ProbeSummary(
            frames = sumOf { it.frames },
            riskyHardJudgmentFrames = sumOf { it.riskyHardJudgmentFrames },
            nonSeniorDemotedFrames = sumOf { it.nonSeniorDemotedFrames },
            maxSubActionCount = maxOf { it.maxSubActionCount },
            balanceStabilityEvents = sumOf { it.balanceStabilityEvents },
            nonNoneEvents = sumOf { it.nonNoneEvents },
            repCompletedEvents = sumOf { it.repCompletedEvents },
        )
    }

    private interface Probe {
        fun run(frames: List<Layer2FrameFeatures>, riskyForHardJudgment: Boolean): ProbeSummary

        fun summarize(frames: List<ProbeFrame>, riskyForHardJudgment: Boolean): ProbeSummary {
            return ProbeSummary(
                frames = frames.size,
                riskyHardJudgmentFrames = if (riskyForHardJudgment) {
                    frames.count { it.allowHardJudgment }
                } else {
                    0
                },
                nonSeniorDemotedFrames = frames.count { it.nonSeniorDemoted },
                maxSubActionCount = frames.maxOfOrNull { it.subActionCount } ?: 0,
                balanceStabilityEvents = frames.count { it.isBalanceStabilityEvent },
                nonNoneEvents = frames.count { it.event != "none" },
                repCompletedEvents = frames.count { it.event == "rep_completed" },
            )
        }
    }

    private class CurrentLayer2Probe : Probe {
        override fun run(frames: List<Layer2FrameFeatures>, riskyForHardJudgment: Boolean): ProbeSummary {
            val interpreter = Layer2TemporalInterpreter()
            val probeFrames = frames.map { frame ->
                val output = interpreter.update(frame)
                ProbeFrame(
                    activity = output.activityHypothesis.label,
                    event = output.event.wireName,
                    allowHardJudgment = output.rulePolicy.allowHardJudgment,
                    subActionCount = output.subActions.size,
                    nonSeniorDemoted = output.nonSeniorLabelDemoted,
                    isBalanceStabilityEvent = output.event == Layer2Event.STABILITY_MONITOR ||
                        output.event == Layer2Event.BALANCE_HOLD_STARTED ||
                        output.event == Layer2Event.BALANCE_HOLD_COMPLETED ||
                        output.phase == Layer2Phase.BALANCE_UNSTABLE,
                )
            }
            return summarize(probeFrames, riskyForHardJudgment)
        }
    }

    private class LegacyLayer2Probe : Probe {
        private var activity = "unknown"
        private var phase = "unknown"
        private var previousFeatures: Layer2FrameFeatures? = null
        private var seenLow = false
        private var seenReturning = false
        private var repMinAngle: Float? = null
        private var repMaxAngle: Float? = null
        private var stableTopFrames = 0
        private val seenSubActions = linkedSetOf<String>()

        override fun run(frames: List<Layer2FrameFeatures>, riskyForHardJudgment: Boolean): ProbeSummary {
            return summarize(frames.map { update(it) }, riskyForHardJudgment)
        }

        private fun update(features: Layer2FrameFeatures): ProbeFrame {
            val normalized = normalizeLegacy(features.activityHint)
            if (normalized != activity && normalized != "unknown") {
                reset()
                activity = normalized
            } else if (activity == "unknown") {
                activity = normalized
            }

            val frame = when (activity) {
                "chair_sit_to_stand" -> updateRep(features, "seated_low", "descending", "rising", hard = true)
                "lunge" -> updateRep(features, "lunge_bottom", "step_or_descent", "returning", hard = true)
                "squat" -> updateRep(features, "bottom_position", "descending", "rising", hard = true)
                "basketball_jump_shot" -> updateBasketball(features)
                "balance_hold" -> ProbeFrame(
                    activity = "balance_hold",
                    event = "stability_monitor",
                    allowHardJudgment = false,
                    isBalanceStabilityEvent = true,
                )
                else -> ProbeFrame(activity = "unknown", event = "monitor_only", allowHardJudgment = false)
            }
            previousFeatures = features
            return frame
        }

        private fun updateRep(
            features: Layer2FrameFeatures,
            lowPhase: String,
            descentPhase: String,
            returnPhase: String,
            hard: Boolean,
        ): ProbeFrame {
            val angle = features.metrics["knee_angle"]
                ?: features.metrics["front_knee_angle"]
                ?: return ProbeFrame(activity, "monitor_only", false)
            val previousAngle = previousFeatures?.metrics?.get("knee_angle")
                ?: previousFeatures?.metrics?.get("front_knee_angle")
            val nextPhase = when {
                angle >= STANDING_ANGLE -> "standing_stabilized"
                angle <= LOW_REP_ANGLE -> lowPhase
                previousAngle != null && angle < previousAngle - ANGLE_DELTA_EPSILON -> descentPhase
                previousAngle != null && angle > previousAngle + ANGLE_DELTA_EPSILON -> returnPhase
                phase != "unknown" -> phase
                else -> "setup"
            }
            val event = updateRepState(angle, nextPhase, setOf(lowPhase), setOf(returnPhase))
            phase = nextPhase
            return ProbeFrame(activity = activity, event = event, allowHardJudgment = hard)
        }

        private fun updateRepState(
            angle: Float,
            nextPhase: String,
            lowPhases: Set<String>,
            returnPhases: Set<String>,
        ): String {
            repMinAngle = repMinAngle?.let { min(it, angle) } ?: angle
            repMaxAngle = repMaxAngle?.let { max(it, angle) } ?: angle
            var event = "none"
            if (
                !seenLow &&
                nextPhase in (lowPhases + setOf("descending", "step_or_descent")) &&
                phase in setOf("unknown", "setup", "standing_stabilized", "monitor_only")
            ) {
                event = "rep_started"
            }
            if (nextPhase in lowPhases) {
                seenLow = true
                stableTopFrames = 0
            }
            if (nextPhase in returnPhases) {
                seenReturning = true
                stableTopFrames = 0
            }
            if (nextPhase == "standing_stabilized") {
                stableTopFrames += 1
            } else if (nextPhase !in lowPhases && nextPhase !in returnPhases) {
                stableTopFrames = 0
            }
            if (seenLow && seenReturning && stableTopFrames >= STABLE_TOP_FRAMES && repRomOk()) {
                event = "rep_completed"
                seenLow = false
                seenReturning = false
                repMinAngle = null
                repMaxAngle = null
                stableTopFrames = 0
            }
            return event
        }

        private fun updateBasketball(features: Layer2FrameFeatures): ProbeFrame {
            val kneeAngle = features.metrics["knee_angle"]
            val previousKnee = previousFeatures?.metrics?.get("knee_angle")
            val shoulderAngle = features.metrics["shoulder_angle"] ?: features.metrics["shoulder_flexion"]
            val elbowAngle = features.metrics["elbow_angle"]
            val nextPhase = when {
                elbowAngle != null && shoulderAngle != null &&
                    elbowAngle >= RELEASE_ELBOW_ANGLE && shoulderAngle >= ARM_LIFT_SHOULDER_ANGLE -> "release_like"
                shoulderAngle != null && shoulderAngle >= ARM_LIFT_SHOULDER_ANGLE -> "arm_lift"
                kneeAngle != null && previousKnee != null && kneeAngle > previousKnee + 2f -> "propulsion"
                kneeAngle != null && kneeAngle <= COUNTERMOVEMENT_KNEE_ANGLE -> "countermovement"
                else -> "monitor_only"
            }
            phase = nextPhase
            when (nextPhase) {
                "countermovement" -> seenSubActions += "countermovement"
                "propulsion" -> seenSubActions += "triple_extension"
                "arm_lift" -> seenSubActions += "arm_lift"
                "release_like" -> seenSubActions += "release_like"
            }
            return ProbeFrame(
                activity = "basketball_jump_shot",
                event = "monitor_only",
                allowHardJudgment = false,
                subActionCount = seenSubActions.size,
            )
        }

        private fun reset() {
            activity = "unknown"
            phase = "unknown"
            previousFeatures = null
            seenLow = false
            seenReturning = false
            repMinAngle = null
            repMaxAngle = null
            stableTopFrames = 0
            seenSubActions.clear()
        }

        private fun repRomOk(): Boolean {
            val minAngle = repMinAngle ?: return false
            val maxAngle = repMaxAngle ?: return false
            return maxAngle - minAngle >= MIN_REP_ROM_DEG
        }

        private fun normalizeLegacy(activityHint: String): String {
            return when (activityHint.lowercase()) {
                "chair_sit_to_stand", "sit_to_stand", "senior_sit_to_stand" -> "chair_sit_to_stand"
                "lunge", "forward_lunge" -> "lunge"
                "basketball_jump_shot", "jump_shot", "basketball_shot" -> "basketball_jump_shot"
                "squat", "supported_squat", "bodyweight_squat" -> "squat"
                "push_up", "pushup", "press_up" -> "push_up"
                "deadlift", "hip_hinge", "hinge" -> "deadlift"
                "balance_hold", "single_leg_balance", "supported_balance" -> "balance_hold"
                else -> "unknown"
            }
        }
    }

    private fun List<ProbeFrame>.toSummary(riskyForHardJudgment: Boolean): ProbeSummary {
        return ProbeSummary(
            frames = size,
            riskyHardJudgmentFrames = if (riskyForHardJudgment) count { it.allowHardJudgment } else 0,
            nonSeniorDemotedFrames = count { it.nonSeniorDemoted },
            maxSubActionCount = maxOfOrNull { it.subActionCount } ?: 0,
            balanceStabilityEvents = count { it.isBalanceStabilityEvent },
            nonNoneEvents = count { it.event != "none" },
            repCompletedEvents = count { it.event == "rep_completed" },
        )
    }

    private companion object {
        const val STANDING_ANGLE = 158f
        const val LOW_REP_ANGLE = 118f
        const val MIN_REP_ROM_DEG = 28f
        const val ANGLE_DELTA_EPSILON = 1f
        const val STABLE_TOP_FRAMES = 2
        const val COUNTERMOVEMENT_KNEE_ANGLE = 150f
        const val ARM_LIFT_SHOULDER_ANGLE = 120f
        const val RELEASE_ELBOW_ANGLE = 155f
    }
}
