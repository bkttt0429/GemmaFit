package com.gemmafit.video

import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic Layer 2 temporal interpreter.
 *
 * This layer turns derived per-frame motion features into bounded activity,
 * phase, sub-action, and event evidence. It does not consume raw video, full
 * skeleton streams, force, GRF, EMG, or clinical labels.
 */
class Layer2TemporalInterpreter {
    private var activity = Layer2Activity.UNKNOWN
    private var phase = Layer2Phase.UNKNOWN
    private var previousFeatures: Layer2FrameFeatures? = null
    private var repCount = 0
    private var seenLow = false
    private var seenReturning = false
    private var repMinAngle: Float? = null
    private var repMaxAngle: Float? = null
    private var stableTopFrames = 0
    private var balanceHoldStartedAtMs: Long? = null
    private var balanceHoldCompleted = false
    private val seenSubActions = linkedMapOf<String, Layer2SubAction>()

    fun reset() {
        activity = Layer2Activity.UNKNOWN
        phase = Layer2Phase.UNKNOWN
        previousFeatures = null
        repCount = 0
        seenLow = false
        seenReturning = false
        repMinAngle = null
        repMaxAngle = null
        stableTopFrames = 0
        balanceHoldStartedAtMs = null
        balanceHoldCompleted = false
        seenSubActions.clear()
    }

    fun update(features: Layer2FrameFeatures): Layer2Output {
        val gateReason = gateReason(features)
        if (gateReason != null) {
            phase = Layer2Phase.ABSTAIN
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.UNKNOWN),
                phase = Layer2Phase.ABSTAIN,
                event = Layer2Event.ABSTAIN,
                confidence = 0f,
                abstainReason = gateReason,
                rulePolicy = Layer2RulePolicy.abstain(gateReason),
            )
        }

        val normalized = normalizeActivity(features.activityHint)
        val nextActivity = normalized.activity
        if (nextActivity != activity) {
            resetStateForActivitySwitch(nextActivity)
        }

        val result = when (activity) {
            Layer2Activity.CHAIR_SIT_TO_STAND -> updateChairSitToStand(features)
            Layer2Activity.SUPPORTED_SQUAT -> updateSupportedSquat(features)
            Layer2Activity.BALANCE_HOLD -> updateBalanceHold(features)
            Layer2Activity.SETUP_TRANSITION -> updateSetupTransition(features)
            Layer2Activity.UNKNOWN -> updateUnknown(features, normalized)
            Layer2Activity.BODYWEIGHT_OR_GOBLET_SQUAT -> updateControlledStrengthRep(
                features,
                Layer2Activity.BODYWEIGHT_OR_GOBLET_SQUAT,
            )
            Layer2Activity.LUNGE -> updateLunge(features)
            Layer2Activity.BASKETBALL_JUMP_SHOT -> updateBasketballJumpShot(features)
            Layer2Activity.SQUAT -> updateControlledStrengthRep(features, Layer2Activity.SQUAT)
            Layer2Activity.PUSH_UP -> updateControlledStrengthRep(features, Layer2Activity.PUSH_UP)
            Layer2Activity.DEADLIFT -> updateControlledStrengthRep(features, Layer2Activity.DEADLIFT)
        }
        previousFeatures = features
        return result
    }

    private fun resetStateForActivitySwitch(nextActivity: Layer2Activity) {
        val previousRepCount = repCount
        reset()
        activity = nextActivity
        repCount = if (nextActivity == Layer2Activity.CHAIR_SIT_TO_STAND) previousRepCount else 0
    }

    private fun updateChairSitToStand(features: Layer2FrameFeatures): Layer2Output {
        val angle = features.primaryKneeAngle()
        if (angle == null) {
            phase = Layer2Phase.MONITOR_ONLY
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.CHAIR_SIT_TO_STAND),
                phase = Layer2Phase.MONITOR_ONLY,
                event = Layer2Event.MONITOR_ONLY,
                confidence = min(features.poseConfidence, 0.5f),
                abstainReason = "missing_knee_angle",
                rulePolicy = Layer2RulePolicy.monitorOnly("missing_knee_angle"),
            )
        }

        val nextPhase = estimateRepPhase(
            angle = angle,
            lowPhase = Layer2Phase.SEATED_LOW,
            descentPhase = Layer2Phase.DESCENDING,
            returnPhase = Layer2Phase.RISING,
        )
        val event = updateRepState(
            angle = angle,
            nextPhase = nextPhase,
            lowPhases = setOf(Layer2Phase.SEATED_LOW),
            returnPhases = setOf(Layer2Phase.RISING),
        )
        phase = nextPhase

        return output(
            features = features,
            activity = activityHypothesis(features, Layer2Activity.CHAIR_SIT_TO_STAND),
            phase = nextPhase,
            event = event,
            confidence = phaseConfidence(features, nextPhase),
            rulePolicy = Layer2RulePolicy.controlledStrength(),
        )
    }

    private fun updateLunge(features: Layer2FrameFeatures): Layer2Output {
        val angle = features.frontKneeAngle()
        if (angle == null) {
            phase = Layer2Phase.MONITOR_ONLY
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.LUNGE),
                phase = Layer2Phase.MONITOR_ONLY,
                event = Layer2Event.MONITOR_ONLY,
                confidence = min(features.poseConfidence, 0.5f),
                abstainReason = "missing_front_knee_angle",
                rulePolicy = Layer2RulePolicy.monitorOnly("missing_front_knee_angle"),
            )
        }

        val nextPhase = estimateRepPhase(
            angle = angle,
            lowPhase = Layer2Phase.LUNGE_BOTTOM,
            descentPhase = Layer2Phase.STEP_OR_DESCENT,
            returnPhase = Layer2Phase.RETURNING,
        )
        val event = updateRepState(
            angle = angle,
            nextPhase = nextPhase,
            lowPhases = setOf(Layer2Phase.LUNGE_BOTTOM),
            returnPhases = setOf(Layer2Phase.RETURNING),
        )
        phase = nextPhase

        val policy = if (nextPhase == Layer2Phase.STANDING_STABILIZED) {
            Layer2RulePolicy.controlledStrength()
        } else {
            Layer2RulePolicy.unilateralMonitor()
        }
        return output(
            features = features,
            activity = activityHypothesis(features, Layer2Activity.LUNGE),
            phase = nextPhase,
            event = event,
            confidence = phaseConfidence(features, nextPhase),
            rulePolicy = policy,
        )
    }

    private fun updateControlledStrengthRep(
        features: Layer2FrameFeatures,
        targetActivity: Layer2Activity,
    ): Layer2Output {
        val angle = when (targetActivity) {
            Layer2Activity.PUSH_UP -> features.elbowAngle()
            Layer2Activity.DEADLIFT -> features.hipDominantAngle()
            else -> features.primaryKneeAngle()
        }
        val previousAngle = when (targetActivity) {
            Layer2Activity.PUSH_UP -> previousFeatures?.elbowAngle()
            Layer2Activity.DEADLIFT -> previousFeatures?.hipDominantAngle()
            else -> previousFeatures?.primaryKneeAngle()
        }
        if (angle == null) {
            val reason = when (targetActivity) {
                Layer2Activity.PUSH_UP -> "missing_elbow_angle"
                Layer2Activity.DEADLIFT -> "missing_hip_hinge_or_knee_angle"
                else -> "missing_knee_angle"
            }
            phase = Layer2Phase.MONITOR_ONLY
            return output(
                features = features,
                activity = activityHypothesis(features, targetActivity),
                phase = Layer2Phase.MONITOR_ONLY,
                event = Layer2Event.MONITOR_ONLY,
                confidence = min(features.poseConfidence, 0.5f),
                abstainReason = reason,
                rulePolicy = Layer2RulePolicy.monitorOnly(reason),
            )
        }

        val nextPhase = estimateRepPhase(
            angle = angle,
            previousAngle = previousAngle,
            lowPhase = Layer2Phase.BOTTOM_POSITION,
            descentPhase = Layer2Phase.DESCENDING,
            returnPhase = Layer2Phase.RISING,
        )
        val event = updateRepState(
            angle = angle,
            nextPhase = nextPhase,
            lowPhases = setOf(Layer2Phase.BOTTOM_POSITION),
            returnPhases = setOf(Layer2Phase.RISING),
        )
        phase = nextPhase

        return output(
            features = features,
            activity = activityHypothesis(features, targetActivity),
            phase = nextPhase,
            event = event,
            confidence = phaseConfidence(features, nextPhase),
            rulePolicy = Layer2RulePolicy.controlledStrength(),
        )
    }

    private fun updateSupportedSquat(features: Layer2FrameFeatures): Layer2Output {
        val angle = features.primaryKneeAngle()
        if (angle == null) {
            phase = Layer2Phase.MONITOR_ONLY
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.SUPPORTED_SQUAT),
                phase = Layer2Phase.MONITOR_ONLY,
                event = Layer2Event.MONITOR_ONLY,
                confidence = min(features.poseConfidence, 0.5f),
                abstainReason = "missing_knee_angle",
                rulePolicy = Layer2RulePolicy.monitorOnly("missing_knee_angle"),
            )
        }

        val nextPhase = estimateRepPhase(
            angle = angle,
            lowPhase = Layer2Phase.SQUAT_BOTTOM,
            descentPhase = Layer2Phase.DESCENDING,
            returnPhase = Layer2Phase.RISING,
        )
        val event = updateRepState(
            angle = angle,
            nextPhase = nextPhase,
            lowPhases = setOf(Layer2Phase.SQUAT_BOTTOM),
            returnPhases = setOf(Layer2Phase.RISING),
        )
        phase = nextPhase

        return output(
            features = features,
            activity = activityHypothesis(features, Layer2Activity.SUPPORTED_SQUAT),
            phase = nextPhase,
            event = event,
            confidence = phaseConfidence(features, nextPhase),
            rulePolicy = Layer2RulePolicy.supportedSquat(features.supportContactProxy()),
        )
    }

    private fun updateBalanceHold(features: Layer2FrameFeatures): Layer2Output {
        val swayNorm = features.swayNorm()
        if (swayNorm == null) {
            phase = Layer2Phase.MONITOR_ONLY
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.BALANCE_HOLD),
                phase = Layer2Phase.MONITOR_ONLY,
                event = Layer2Event.MONITOR_ONLY,
                confidence = min(features.poseConfidence, 0.5f),
                abstainReason = "missing_sway_norm",
                rulePolicy = Layer2RulePolicy.monitorOnly("missing_sway_norm"),
            )
        }

        if (swayNorm >= UNSTABLE_BALANCE_SWAY) {
            balanceHoldStartedAtMs = null
            balanceHoldCompleted = false
            phase = Layer2Phase.BALANCE_UNSTABLE
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.BALANCE_HOLD),
                phase = Layer2Phase.BALANCE_UNSTABLE,
                event = Layer2Event.MONITOR_ONLY,
                confidence = min(features.poseConfidence, 0.68f),
                abstainReason = "balance_unstable_monitor_only",
                rulePolicy = Layer2RulePolicy.balanceMonitorOnly("balance_unstable_monitor_only"),
            )
        }

        val startedAt = balanceHoldStartedAtMs
        if (startedAt == null && swayNorm <= STABLE_BALANCE_SWAY) {
            balanceHoldStartedAtMs = features.timestampMs
            balanceHoldCompleted = false
            phase = Layer2Phase.BALANCE_HOLDING
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.BALANCE_HOLD),
                phase = Layer2Phase.BALANCE_HOLDING,
                event = Layer2Event.BALANCE_HOLD_STARTED,
                confidence = min(features.poseConfidence, 0.78f),
                holdDurationMs = 0L,
                rulePolicy = Layer2RulePolicy.balanceMonitorOnly(),
            )
        }

        val activeStartedAt = balanceHoldStartedAtMs
        if (activeStartedAt != null) {
            val holdDurationMs = (features.timestampMs - activeStartedAt).coerceAtLeast(0L)
            val event = if (!balanceHoldCompleted && holdDurationMs >= BALANCE_HOLD_TARGET_MS) {
                balanceHoldCompleted = true
                Layer2Event.BALANCE_HOLD_COMPLETED
            } else {
                Layer2Event.NONE
            }
            phase = Layer2Phase.BALANCE_HOLDING
            return output(
                features = features,
                activity = activityHypothesis(features, Layer2Activity.BALANCE_HOLD),
                phase = Layer2Phase.BALANCE_HOLDING,
                event = event,
                confidence = min(features.poseConfidence, 0.78f),
                holdDurationMs = holdDurationMs,
                rulePolicy = Layer2RulePolicy.balanceMonitorOnly(),
            )
        }

        phase = Layer2Phase.STANDING_STABILIZED
        return output(
            features = features,
            activity = activityHypothesis(features, Layer2Activity.BALANCE_HOLD),
            phase = Layer2Phase.STANDING_STABILIZED,
            event = Layer2Event.NONE,
            confidence = min(features.poseConfidence, 0.72f),
            rulePolicy = Layer2RulePolicy.balanceMonitorOnly(),
        )
    }

    private fun updateSetupTransition(features: Layer2FrameFeatures): Layer2Output {
        phase = Layer2Phase.SETUP
        return output(
            features = features,
            activity = activityHypothesis(features, Layer2Activity.SETUP_TRANSITION),
            phase = Layer2Phase.SETUP,
            event = Layer2Event.NONE,
            confidence = min(features.poseConfidence, 0.64f),
            abstainReason = "setup_transition_monitor_only",
            rulePolicy = Layer2RulePolicy.monitorOnly("setup_transition_monitor_only"),
        )
    }

    private fun updateBasketballJumpShot(features: Layer2FrameFeatures): Layer2Output {
        val lowParentConfidence = features.activityConfidence < PARENT_CONFIDENCE_THRESHOLD
        val kneeAngle = features.primaryKneeAngle()
        val previousKnee = previousFeatures?.primaryKneeAngle()
        val shoulderAngle = features.metrics["shoulder_angle"] ?: features.metrics["shoulder_flexion"]
        val elbowAngle = features.metrics["elbow_angle"]
        val wristY = features.metrics["wrist_y_norm"]

        val nextPhase = when {
            elbowAngle != null && shoulderAngle != null &&
                elbowAngle >= RELEASE_ELBOW_ANGLE && shoulderAngle >= ARM_LIFT_SHOULDER_ANGLE -> Layer2Phase.RELEASE_LIKE
            shoulderAngle != null && shoulderAngle >= ARM_LIFT_SHOULDER_ANGLE -> Layer2Phase.ARM_LIFT
            wristY != null && wristY <= WRIST_HIGH_Y_NORM -> Layer2Phase.ARM_LIFT
            kneeAngle != null && previousKnee != null && kneeAngle > previousKnee + 2f -> Layer2Phase.PROPULSION
            kneeAngle != null && kneeAngle <= COUNTERMOVEMENT_KNEE_ANGLE -> Layer2Phase.COUNTERMOVEMENT
            previousPhaseWasTerminal() && kneeAngle != null && kneeAngle >= STANDING_ANGLE -> Layer2Phase.LANDING_STABILIZATION
            else -> Layer2Phase.MONITOR_ONLY
        }
        phase = nextPhase
        addBasketballSubAction(nextPhase, lowParentConfidence)

        val activityHypothesis = if (lowParentConfidence) {
            Layer2ActivityHypothesis(
                label = "generic_composite_motion",
                confidence = features.activityConfidence.coerceIn(0f, 1f),
                source = listOf("activity_hint_low_confidence", "derived_motion_features"),
            )
        } else {
            activityHypothesis(features, Layer2Activity.BASKETBALL_JUMP_SHOT)
        }

        return output(
            features = features,
            activity = activityHypothesis,
            phase = nextPhase,
            event = Layer2Event.MONITOR_ONLY,
            confidence = min(features.poseConfidence, max(0.35f, features.activityConfidence)),
            rulePolicy = Layer2RulePolicy.sportCompositeMonitorOnly(
                lowParentConfidence = lowParentConfidence,
                allowLandingMonitor = nextPhase == Layer2Phase.LANDING_STABILIZATION,
            ),
            subActions = seenSubActions.values.toList(),
        )
    }

    private fun updateUnknown(
        features: Layer2FrameFeatures,
        normalized: NormalizedLayer2Activity = normalizeActivity(features.activityHint),
    ): Layer2Output {
        val reason = if (normalized.nonSeniorLabelDemoted) {
            "non_senior_activity_demoted"
        } else {
            "unsupported_or_uncertain_activity"
        }
        phase = Layer2Phase.MONITOR_ONLY
        return output(
            features = features,
            activity = activityHypothesis(
                features = features,
                normalizedActivity = Layer2Activity.UNKNOWN,
                nonSeniorLabelDemoted = normalized.nonSeniorLabelDemoted,
            ),
            phase = Layer2Phase.MONITOR_ONLY,
            event = Layer2Event.NONE,
            confidence = min(features.poseConfidence, 0.4f),
            abstainReason = reason,
            rulePolicy = Layer2RulePolicy.monitorOnly(reason),
            nonSeniorLabelDemoted = normalized.nonSeniorLabelDemoted,
        )
    }

    private fun estimateRepPhase(
        angle: Float,
        previousAngle: Float? = previousFeatures?.primaryKneeAngle()
            ?: previousFeatures?.frontKneeAngle(),
        lowPhase: Layer2Phase,
        descentPhase: Layer2Phase,
        returnPhase: Layer2Phase,
    ): Layer2Phase {
        if (angle >= STANDING_ANGLE) return Layer2Phase.STANDING_STABILIZED
        if (angle <= LOW_REP_ANGLE) return lowPhase

        return when {
            previousAngle != null && angle < previousAngle - ANGLE_DELTA_EPSILON -> descentPhase
            previousAngle != null && angle > previousAngle + ANGLE_DELTA_EPSILON -> returnPhase
            phase != Layer2Phase.UNKNOWN -> phase
            else -> Layer2Phase.SETUP
        }
    }

    private fun updateRepState(
        angle: Float,
        nextPhase: Layer2Phase,
        lowPhases: Set<Layer2Phase>,
        returnPhases: Set<Layer2Phase>,
    ): Layer2Event {
        repMinAngle = repMinAngle?.let { min(it, angle) } ?: angle
        repMaxAngle = repMaxAngle?.let { max(it, angle) } ?: angle

        var event = Layer2Event.NONE
        if (
            !seenLow &&
            nextPhase in (lowPhases + setOf(Layer2Phase.DESCENDING, Layer2Phase.STEP_OR_DESCENT)) &&
            phase in setOf(Layer2Phase.UNKNOWN, Layer2Phase.SETUP, Layer2Phase.STANDING_STABILIZED, Layer2Phase.MONITOR_ONLY)
        ) {
            event = Layer2Event.REP_STARTED
        }
        if (nextPhase in lowPhases) {
            seenLow = true
            stableTopFrames = 0
        }
        if (nextPhase in returnPhases) {
            seenReturning = true
            stableTopFrames = 0
        }
        if (nextPhase == Layer2Phase.STANDING_STABILIZED) {
            stableTopFrames += 1
        } else if (nextPhase !in lowPhases && nextPhase !in returnPhases) {
            stableTopFrames = 0
        }

        if (seenLow && seenReturning && stableTopFrames >= STABLE_TOP_FRAMES && repRomOk()) {
            repCount += 1
            event = Layer2Event.REP_COMPLETED
            seenLow = false
            seenReturning = false
            repMinAngle = null
            repMaxAngle = null
            stableTopFrames = 0
        }
        return event
    }

    private fun repRomOk(): Boolean {
        val minAngle = repMinAngle ?: return false
        val maxAngle = repMaxAngle ?: return false
        return maxAngle - minAngle >= MIN_REP_ROM_DEG
    }

    private fun addBasketballSubAction(phase: Layer2Phase, generic: Boolean) {
        val subAction = when (phase) {
            Layer2Phase.COUNTERMOVEMENT -> if (generic) {
                Layer2SubAction("lower_body_loading", "loading", "lower_body", 0.62f)
            } else {
                Layer2SubAction("countermovement", "loading", "lower_body", 0.72f)
            }
            Layer2Phase.PROPULSION -> if (generic) {
                Layer2SubAction("lower_body_extension", "propulsion", "lower_body", 0.62f)
            } else {
                Layer2SubAction("triple_extension", "propulsion", "lower_body", 0.7f)
            }
            Layer2Phase.ARM_LIFT -> if (generic) {
                Layer2SubAction("upper_body_lift", "arm_lift", "upper_body", 0.62f)
            } else {
                Layer2SubAction("arm_lift", "ball_lift", "upper_body", 0.7f)
            }
            Layer2Phase.RELEASE_LIKE -> if (generic) {
                Layer2SubAction("terminal_arm_extension", "terminal", "upper_body", 0.6f)
            } else {
                Layer2SubAction("release_like", "terminal", "upper_body", 0.65f)
            }
            Layer2Phase.LANDING_STABILIZATION -> Layer2SubAction(
                label = "landing_stabilization",
                phase = "recovery",
                bodyRegion = "whole_body",
                confidence = 0.68f,
            )
            else -> null
        }
        if (subAction != null) seenSubActions[subAction.label] = subAction
    }

    private fun previousPhaseWasTerminal(): Boolean {
        return phase == Layer2Phase.RELEASE_LIKE || seenSubActions.containsKey("release_like")
    }

    private fun gateReason(features: Layer2FrameFeatures): String? {
        if (features.personTrackingState !in allowedTrackingStates) return "person_not_observed"
        if (!features.judgmentAllowed) return "judgment_not_allowed"
        if (features.poseConfidence < MIN_POSE_CONFIDENCE) return "low_pose_confidence"
        return null
    }

    private fun phaseConfidence(features: Layer2FrameFeatures, phase: Layer2Phase): Float {
        var confidence = features.poseConfidence.coerceIn(0f, 1f)
        if (normalizeActivity(features.activityHint).activity == activity) {
            confidence = min(1f, confidence + 0.04f)
        }
        if (phase == Layer2Phase.MONITOR_ONLY) {
            confidence = min(confidence, 0.55f)
        }
        return confidence
    }

    private fun activityHypothesis(
        features: Layer2FrameFeatures,
        normalizedActivity: Layer2Activity,
        nonSeniorLabelDemoted: Boolean = false,
    ): Layer2ActivityHypothesis {
        return Layer2ActivityHypothesis(
            label = normalizedActivity.wireName,
            confidence = if (normalizedActivity == Layer2Activity.UNKNOWN) {
                min(features.activityConfidence, 0.35f)
            } else {
                features.activityConfidence.coerceIn(0f, 1f)
            },
            source = listOfNotNull(
                "activity_hint",
                "derived_motion_features",
                if (nonSeniorLabelDemoted) "non_senior_label_demoted" else null,
            ),
        )
    }

    private fun output(
        features: Layer2FrameFeatures,
        activity: Layer2ActivityHypothesis,
        phase: Layer2Phase,
        event: Layer2Event = Layer2Event.NONE,
        confidence: Float,
        abstainReason: String? = null,
        rulePolicy: Layer2RulePolicy,
        subActions: List<Layer2SubAction> = emptyList(),
        holdDurationMs: Long = 0L,
        nonSeniorLabelDemoted: Boolean = false,
    ): Layer2Output {
        val refs = linkedSetOf(
            "layer2.activity.${activity.label}",
            "layer2.phase.${phase.wireName}",
        )
        if (event != Layer2Event.NONE) refs += "layer2.event.${event.wireName}"
        if (nonSeniorLabelDemoted) refs += "layer2.normalizer.non_senior_label_demoted"
        subActions.forEach { refs += it.evidenceRef }

        return Layer2Output(
            timestampMs = features.timestampMs,
            activityHypothesis = activity,
            phase = phase,
            event = event,
            confidence = confidence.coerceIn(0f, 1f),
            abstainReason = abstainReason,
            repCount = repCount,
            subActions = subActions,
            rulePolicy = rulePolicy,
            evidenceRefs = refs.toList(),
            judgeability = rulePolicy.outputState,
            holdDurationMs = holdDurationMs,
            nonSeniorLabelDemoted = nonSeniorLabelDemoted,
        )
    }

    private fun normalizeActivity(activityHint: String): NormalizedLayer2Activity {
        val normalized = activityHint.lowercase()
        return when (normalized) {
            "chair_sit_to_stand", "sit_to_stand", "senior_sit_to_stand" ->
                NormalizedLayer2Activity(Layer2Activity.CHAIR_SIT_TO_STAND)
            "supported_squat" ->
                NormalizedLayer2Activity(Layer2Activity.SUPPORTED_SQUAT)
            "bodyweight_or_goblet_squat", "bodyweight_squat", "goblet_squat", "squat" ->
                NormalizedLayer2Activity(Layer2Activity.BODYWEIGHT_OR_GOBLET_SQUAT)
            "balance_hold", "single_leg_balance", "supported_balance" ->
                NormalizedLayer2Activity(Layer2Activity.BALANCE_HOLD)
            "setup_transition", "setup", "transition" ->
                NormalizedLayer2Activity(Layer2Activity.SETUP_TRANSITION)
            "lunge", "forward_lunge",
            "basketball_jump_shot", "jump_shot", "basketball_shot",
            "push_up", "pushup", "press_up",
            "deadlift", "hip_hinge", "hinge" ->
                NormalizedLayer2Activity(
                    activity = Layer2Activity.UNKNOWN,
                    nonSeniorLabelDemoted = true,
                )
            else -> NormalizedLayer2Activity(Layer2Activity.UNKNOWN)
        }
    }

    private data class NormalizedLayer2Activity(
        val activity: Layer2Activity,
        val nonSeniorLabelDemoted: Boolean = false,
    )

    private companion object {
        const val MIN_POSE_CONFIDENCE = 0.55f
        const val STANDING_ANGLE = 158f
        const val LOW_REP_ANGLE = 118f
        const val MIN_REP_ROM_DEG = 28f
        const val ANGLE_DELTA_EPSILON = 1f
        const val STABLE_TOP_FRAMES = 2
        const val STABLE_BALANCE_SWAY = 0.045f
        const val UNSTABLE_BALANCE_SWAY = 0.1f
        const val BALANCE_HOLD_TARGET_MS = 5_000L
        const val PARENT_CONFIDENCE_THRESHOLD = 0.6f
        const val COUNTERMOVEMENT_KNEE_ANGLE = 150f
        const val ARM_LIFT_SHOULDER_ANGLE = 120f
        const val RELEASE_ELBOW_ANGLE = 155f
        const val WRIST_HIGH_Y_NORM = 0.35f

        val allowedTrackingStates = setOf(
            PersonTrackingState.OBSERVED,
            PersonTrackingState.SINGLE_PERSON,
            PersonTrackingState.AUTO_LOCKED,
        )
    }
}

data class Layer2FrameFeatures(
    val timestampMs: Long,
    val activityHint: String,
    val activityConfidence: Float = 1f,
    val poseConfidence: Float = 1f,
    val personTrackingState: PersonTrackingState = PersonTrackingState.OBSERVED,
    val judgmentAllowed: Boolean = true,
    val metrics: Map<String, Float> = emptyMap(),
) {
    fun primaryKneeAngle(): Float? {
        return metrics["knee_angle"]
            ?: metrics["front_knee_angle"]
            ?: metrics["left_knee_angle"]
            ?: metrics["right_knee_angle"]
    }

    fun frontKneeAngle(): Float? {
        return metrics["front_knee_angle"] ?: primaryKneeAngle()
    }

    fun hipDominantAngle(): Float? {
        return metrics["hip_hinge"]
            ?: metrics["hip_angle"]
            ?: metrics["primary_angle_deg"]
            ?: primaryKneeAngle()
    }

    fun elbowAngle(): Float? {
        return metrics["elbow_angle"]
            ?: metrics["primary_angle_deg"]
            ?: metrics["shoulder_angle"]
    }

    fun supportContactProxy(): Float? {
        return metrics["support_contact_proxy"]
            ?: metrics["support_contact"]
            ?: metrics["chair_contact_proxy"]
            ?: metrics["hand_support_proxy"]
    }

    fun swayNorm(): Float? {
        return metrics["sway_norm"]
            ?: metrics["lateral_sway_proxy"]
            ?: metrics["balance_sway_norm"]
    }
}

data class Layer2ActivityHypothesis(
    val label: String,
    val confidence: Float,
    val source: List<String>,
) {
    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "label" to label,
            "confidence" to confidence,
            "source" to source,
        )
    }
}

data class Layer2SubAction(
    val label: String,
    val phase: String,
    val bodyRegion: String,
    val confidence: Float,
) {
    val evidenceRef: String = "layer2.sub_action.$label"

    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "label" to label,
            "phase" to phase,
            "body_region" to bodyRegion,
            "confidence" to confidence,
            "evidence_ref" to evidenceRef,
        )
    }
}

data class Layer2RulePolicy(
    val allowHardJudgment: Boolean,
    val allowStrengthTemplateHardWarnings: Boolean,
    val allowBilateralSymmetryHardWarning: Boolean,
    val allowLandingStabilizationMonitor: Boolean,
    val outputState: String,
    val blockedRules: List<Int> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "allow_hard_judgment" to allowHardJudgment,
            "allow_strength_template_hard_warnings" to allowStrengthTemplateHardWarnings,
            "allow_bilateral_symmetry_hard_warning" to allowBilateralSymmetryHardWarning,
            "allow_landing_stabilization_monitor" to allowLandingStabilizationMonitor,
            "output_state" to outputState,
            "blocked_rules" to blockedRules,
            "notes" to notes,
        )
    }

    companion object {
        fun controlledStrength(): Layer2RulePolicy {
            return Layer2RulePolicy(
                allowHardJudgment = true,
                allowStrengthTemplateHardWarnings = true,
                allowBilateralSymmetryHardWarning = true,
                allowLandingStabilizationMonitor = true,
                outputState = "judgeable",
            )
        }

        fun supportedSquat(supportContactProxy: Float?): Layer2RulePolicy {
            val hasSupport = supportContactProxy != null && supportContactProxy >= 0.5f
            return Layer2RulePolicy(
                allowHardJudgment = false,
                allowStrengthTemplateHardWarnings = false,
                allowBilateralSymmetryHardWarning = false,
                allowLandingStabilizationMonitor = false,
                outputState = "monitor_only",
                blockedRules = listOf(1, 4, 6, 7),
                notes = listOfNotNull(
                    "supported_squat_is_monitor_only_for_demo_p0",
                    if (hasSupport) "support_contact_observed_but_not_a_safety_claim" else null,
                ),
            )
        }

        fun unilateralMonitor(): Layer2RulePolicy {
            return Layer2RulePolicy(
                allowHardJudgment = true,
                allowStrengthTemplateHardWarnings = true,
                allowBilateralSymmetryHardWarning = false,
                allowLandingStabilizationMonitor = true,
                outputState = "monitor_only",
                blockedRules = listOf(4),
                notes = listOf("unilateral_phase_blocks_bilateral_symmetry_hard_warning"),
            )
        }

        fun sportCompositeMonitorOnly(
            lowParentConfidence: Boolean,
            allowLandingMonitor: Boolean,
        ): Layer2RulePolicy {
            return Layer2RulePolicy(
                allowHardJudgment = false,
                allowStrengthTemplateHardWarnings = false,
                allowBilateralSymmetryHardWarning = false,
                allowLandingStabilizationMonitor = allowLandingMonitor,
                outputState = "monitor_only",
                blockedRules = listOf(4, 6, 7),
                notes = listOfNotNull(
                    "sport_composite_blocks_strength_template_hard_warnings",
                    if (lowParentConfidence) "parent_task_low_confidence_generic_sub_actions" else null,
                ),
            )
        }

        fun monitorOnly(reason: String): Layer2RulePolicy {
            return Layer2RulePolicy(
                allowHardJudgment = false,
                allowStrengthTemplateHardWarnings = false,
                allowBilateralSymmetryHardWarning = false,
                allowLandingStabilizationMonitor = false,
                outputState = "monitor_only",
                notes = listOf(reason),
            )
        }

        fun balanceMonitorOnly(reason: String = "balance_hold_is_monitor_only_not_fall_risk"): Layer2RulePolicy {
            return Layer2RulePolicy(
                allowHardJudgment = false,
                allowStrengthTemplateHardWarnings = false,
                allowBilateralSymmetryHardWarning = false,
                allowLandingStabilizationMonitor = false,
                outputState = "monitor_only",
                blockedRules = listOf(4, 5),
                notes = listOf(reason),
            )
        }

        fun abstain(reason: String): Layer2RulePolicy {
            return Layer2RulePolicy(
                allowHardJudgment = false,
                allowStrengthTemplateHardWarnings = false,
                allowBilateralSymmetryHardWarning = false,
                allowLandingStabilizationMonitor = false,
                outputState = "abstain",
                notes = listOf(reason),
            )
        }
    }
}

data class Layer2Output(
    val timestampMs: Long,
    val activityHypothesis: Layer2ActivityHypothesis,
    val phase: Layer2Phase,
    val event: Layer2Event,
    val confidence: Float,
    val abstainReason: String? = null,
    val repCount: Int = 0,
    val subActions: List<Layer2SubAction> = emptyList(),
    val rulePolicy: Layer2RulePolicy,
    val evidenceRefs: List<String>,
    val judgeability: String = rulePolicy.outputState,
    val holdDurationMs: Long = 0L,
    val nonSeniorLabelDemoted: Boolean = false,
) {
    fun toDebugMap(): Map<String, Any?> {
        return mapOf(
            "timestamp_ms" to timestampMs,
            "activity_hypothesis" to activityHypothesis.toDebugMap(),
            "phase" to phase.wireName,
            "event" to event.wireName,
            "confidence" to confidence,
            "abstain_reason" to abstainReason,
            "rep_count" to repCount,
            "hold_duration_ms" to holdDurationMs,
            "judgeability" to judgeability,
            "non_senior_label_demoted" to nonSeniorLabelDemoted,
            "sub_actions" to subActions.map { it.toDebugMap() },
            "rule_policy" to rulePolicy.toDebugMap(),
            "evidence_refs" to evidenceRefs,
        )
    }
}

enum class Layer2Activity(val wireName: String) {
    CHAIR_SIT_TO_STAND("chair_sit_to_stand"),
    SUPPORTED_SQUAT("supported_squat"),
    BODYWEIGHT_OR_GOBLET_SQUAT("bodyweight_or_goblet_squat"),
    BALANCE_HOLD("balance_hold"),
    SETUP_TRANSITION("setup_transition"),
    LUNGE("lunge"),
    BASKETBALL_JUMP_SHOT("basketball_jump_shot"),
    SQUAT("squat"),
    PUSH_UP("push_up"),
    DEADLIFT("deadlift"),
    UNKNOWN("unknown"),
}

enum class Layer2Phase(val wireName: String) {
    UNKNOWN("unknown"),
    SETUP("setup"),
    SEATED_LOW("seated_low"),
    SQUAT_BOTTOM("squat_bottom"),
    BOTTOM_POSITION("bottom_position"),
    DESCENDING("descending"),
    RISING("rising"),
    STANDING_STABILIZED("standing_stabilized"),
    STEP_OR_DESCENT("step_or_descent"),
    LUNGE_BOTTOM("lunge_bottom"),
    RETURNING("returning"),
    COUNTERMOVEMENT("countermovement"),
    PROPULSION("propulsion"),
    ARM_LIFT("arm_lift"),
    RELEASE_LIKE("release_like"),
    LANDING_STABILIZATION("landing_stabilization"),
    BALANCE_HOLDING("balance_holding"),
    BALANCE_UNSTABLE("balance_unstable"),
    MONITOR_ONLY("monitor_only"),
    ABSTAIN("abstain"),
}

enum class Layer2Event(val wireName: String) {
    NONE("none"),
    REP_STARTED("rep_started"),
    REP_COMPLETED("rep_completed"),
    STABILITY_MONITOR("stability_monitor"),
    BALANCE_HOLD_STARTED("balance_hold_started"),
    BALANCE_HOLD_COMPLETED("balance_hold_completed"),
    MONITOR_ONLY("monitor_only"),
    ABSTAIN("abstain"),
}
