package com.gemmafit.video

/**
 * Low-cost activity context tracker for senior P0 flows.
 *
 * This tracker disambiguates only the small set of senior activities that need
 * different wording or evidence labels. It never changes live safety verdicts
 * and never emits clinical or fall-risk labels.
 */
class ActivityContextTracker {
    private val recentTopLabels = ArrayDeque<String>()
    private var current: ActivityContext = ActivityContext.unknown()

    fun reset() {
        recentTopLabels.clear()
        current = ActivityContext.unknown()
    }

    fun peek(): ActivityContext = current

    fun observePreRepCandidate(observation: ActivityContextObservation): ActivityContext? {
        if (observation.confidence < MIN_OBSERVATION_CONFIDENCE) return null
        if (current.state == ActivityContextState.LOCKED || current.state == ActivityContextState.AMBIGUOUS) {
            return current
        }

        val scores = scoreTemplates(observation)
        val chairSetupScore = chairPreRepSetupScore(observation)
        val supportedSquatScore = scores[SUPPORTED_SQUAT] ?: 0f
        if (chairSetupScore < MIN_PRE_REP_CHAIR_SCORE) return null
        if (supportedSquatScore >= MIN_SECONDARY_TEMPLATE_SCORE &&
            supportedSquatScore - chairSetupScore > AMBIGUOUS_SCORE_MARGIN
        ) {
            return null
        }

        recentTopLabels.clear()
        current = ActivityContext(
            state = ActivityContextState.CALIBRATING,
            taskLabel = CHAIR_SIT_TO_STAND,
            confidence = minOf(0.82f, maxOf(observation.confidence, chairSetupScore)).coerceIn(0f, 1f),
            ambiguityNote = "chair_sts_pre_rep_setup_evidence",
            templateScores = scores + (CHAIR_SIT_TO_STAND to maxOf(chairSetupScore, scores[CHAIR_SIT_TO_STAND] ?: 0f)),
            evidenceRefs = evidenceRefs(observation, "activity_context.calibrating.pre_rep_chair_sts"),
        )
        return current
    }

    fun update(observation: ActivityContextObservation): ActivityContext {
        if (observation.confidence < MIN_OBSERVATION_CONFIDENCE) {
            current = ActivityContext(
                state = if (current.state == ActivityContextState.LOCKED) {
                    ActivityContextState.SUSPECT_SWITCH
                } else {
                    ActivityContextState.CALIBRATING
                },
                taskLabel = current.taskLabel,
                confidence = observation.confidence.coerceIn(0f, 1f),
                ambiguityNote = "activity_context_low_confidence",
                templateScores = emptyMap(),
                evidenceRefs = evidenceRefs(observation, "activity_context.low_confidence"),
            )
            return current
        }

        val scores = scoreTemplates(observation)
        val ranked = scores.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull()
        val second = ranked.drop(1).firstOrNull()
        if (top == null || top.value < MIN_TEMPLATE_SCORE) {
            recentTopLabels.clear()
            current = ActivityContext(
                state = ActivityContextState.CALIBRATING,
                taskLabel = null,
                confidence = top?.value ?: 0f,
                ambiguityNote = "activity_context_insufficient_template_score",
                templateScores = scores,
                evidenceRefs = evidenceRefs(observation, "activity_context.calibrating"),
            )
            return current
        }

        val margin = top.value - (second?.value ?: 0f)
        val ambiguous = second != null &&
            second.value >= MIN_SECONDARY_TEMPLATE_SCORE &&
            margin <= AMBIGUOUS_SCORE_MARGIN
        if (ambiguous) {
            recentTopLabels.clear()
            current = ActivityContext(
                state = ActivityContextState.AMBIGUOUS,
                taskLabel = null,
                confidence = top.value.coerceIn(0f, 1f),
                ambiguityNote = "chair_vs_squat_scores_within_margin",
                templateScores = scores,
                evidenceRefs = evidenceRefs(observation, "activity_context.ambiguous"),
            )
            return current
        }

        val topLabel = top.key
        recentTopLabels.addLast(topLabel)
        while (recentTopLabels.size > LOCK_CONFIRMATION_WINDOWS) {
            recentTopLabels.removeFirst()
        }

        val state = when {
            current.state == ActivityContextState.LOCKED &&
                current.taskLabel != null &&
                current.taskLabel != topLabel -> ActivityContextState.SUSPECT_SWITCH
            recentTopLabels.size >= LOCK_CONFIRMATION_WINDOWS &&
                recentTopLabels.all { it == topLabel } -> ActivityContextState.LOCKED
            else -> ActivityContextState.CALIBRATING
        }
        val taskLabel = if (state == ActivityContextState.SUSPECT_SWITCH) current.taskLabel else topLabel
        current = ActivityContext(
            state = state,
            taskLabel = taskLabel,
            confidence = top.value.coerceIn(0f, 1f),
            ambiguityNote = if (state == ActivityContextState.SUSPECT_SWITCH) {
                "activity_context_suspect_switch_pending_confirmation"
            } else {
                null
            },
            templateScores = scores,
            evidenceRefs = evidenceRefs(observation, "activity_context.${state.wireName}"),
        )
        return current
    }

    private fun scoreTemplates(observation: ActivityContextObservation): Map<String, Float> {
        return mapOf(
            CHAIR_SIT_TO_STAND to scoreChairSitToStand(observation),
            SUPPORTED_SQUAT to scoreSupportedSquat(observation),
            BODYWEIGHT_OR_GOBLET_SQUAT to scoreBodyweightOrGobletSquat(observation),
        )
    }

    private fun scoreChairSitToStand(observation: ActivityContextObservation): Float {
        var score = 0f
        if (observation.layer2Label in chairLabels) score += 0.34f
        if (observation.supportPattern in chairSupportPatterns) score += 0.20f
        if ((observation.bottomDwellMs ?: 0L) >= CHAIR_DWELL_MS) score += 0.18f
        if ((observation.trunkLeanDeg ?: 0f) in 30f..55f) score += 0.12f
        if ("seated_low" in observation.phaseSequence) score += 0.10f
        if ((observation.hipVerticalDisplacement ?: 0f) in 0.06f..0.28f) score += 0.06f
        return score.coerceIn(0f, 1f)
    }

    private fun scoreBodyweightOrGobletSquat(observation: ActivityContextObservation): Float {
        var score = 0f
        if (observation.layer2Label in bodyweightSquatLabels) score += 0.38f
        if (observation.supportPattern in noSupportPatterns) score += 0.16f
        if ((observation.bottomDwellMs ?: Long.MAX_VALUE) <= SQUAT_DWELL_MS) score += 0.14f
        val trunkLean = observation.trunkLeanDeg
        if (trunkLean != null && trunkLean in 8f..45f) score += 0.10f
        if (
            "squat_bottom" in observation.phaseSequence ||
            "bottom_position" in observation.phaseSequence ||
            "bottom" in observation.phaseSequence
        ) {
            score += 0.12f
        }
        if ((observation.hipVerticalDisplacement ?: 0f) in 0.08f..0.44f) score += 0.10f
        return score.coerceIn(0f, 1f)
    }

    private fun scoreSupportedSquat(observation: ActivityContextObservation): Float {
        var score = 0f
        if (observation.layer2Label in squatLabels) score += 0.34f
        if (observation.supportPattern in squatSupportPatterns) score += 0.16f
        if ((observation.bottomDwellMs ?: Long.MAX_VALUE) <= SQUAT_DWELL_MS) score += 0.18f
        val trunkLean = observation.trunkLeanDeg
        if (trunkLean != null && trunkLean in 8f..34f) score += 0.12f
        if ("squat_bottom" in observation.phaseSequence || "bottom_position" in observation.phaseSequence) score += 0.10f
        if ((observation.hipVerticalDisplacement ?: 0f) in 0.10f..0.42f) score += 0.10f
        return score.coerceIn(0f, 1f)
    }

    private fun chairPreRepSetupScore(observation: ActivityContextObservation): Float {
        var score = 0f
        if (observation.supportPattern in chairSupportPatterns) score += 0.42f
        if (observation.layer2Label in chairLabels) score += 0.22f
        if (observation.layer2Label == "setup_transition") score += 0.12f
        if ("seated_low" in observation.phaseSequence || "standing_stabilized" in observation.phaseSequence) {
            score += 0.12f
        }
        if ((observation.hipVerticalDisplacement ?: 0f) in 0.02f..0.22f) score += 0.06f
        if (observation.confidence >= 0.70f) score += 0.06f
        return score.coerceIn(0f, 1f)
    }

    private fun evidenceRefs(
        observation: ActivityContextObservation,
        stateRef: String,
    ): List<String> {
        return (
            observation.evidenceRefs +
                listOf(
                    stateRef,
                    "activity_context.template_scores",
                )
            )
            .filter { it.isNotBlank() }
            .distinct()
    }

    companion object {
        private const val CHAIR_SIT_TO_STAND = "chair_sit_to_stand"
        private const val SUPPORTED_SQUAT = "supported_squat"
        private const val BODYWEIGHT_OR_GOBLET_SQUAT = "bodyweight_or_goblet_squat"
        private const val MIN_OBSERVATION_CONFIDENCE = 0.55f
        private const val MIN_TEMPLATE_SCORE = 0.52f
        private const val MIN_PRE_REP_CHAIR_SCORE = 0.42f
        private const val MIN_SECONDARY_TEMPLATE_SCORE = 0.48f
        private const val AMBIGUOUS_SCORE_MARGIN = 0.12f
        private const val LOCK_CONFIRMATION_WINDOWS = 2
        private const val CHAIR_DWELL_MS = 450L
        private const val SQUAT_DWELL_MS = 320L

        private val chairLabels = setOf(CHAIR_SIT_TO_STAND, "sit_to_stand", "senior_sit_to_stand")
        private val squatLabels = setOf(SUPPORTED_SQUAT, "supported_chair_squat")
        private val bodyweightSquatLabels = setOf(
            BODYWEIGHT_OR_GOBLET_SQUAT,
            "bodyweight_squat",
            "goblet_squat",
            "squat",
        )
        private val chairSupportPatterns = setOf("chair", "seat", "chair_support", "hands_on_chair")
        private val squatSupportPatterns = setOf("hand_support", "supported", "rail_support", "counter_support")
        private val noSupportPatterns = setOf("none", "no_support", "unknown")
    }
}

data class ActivityContextObservation(
    val timestampMs: Long,
    val layer2Label: String,
    val confidence: Float,
    val supportPattern: String = "unknown",
    val bottomDwellMs: Long? = null,
    val trunkLeanDeg: Float? = null,
    val hipVerticalDisplacement: Float? = null,
    val phaseSequence: List<String> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
) {
    companion object {
        fun from(
            motionFeatureWindow: MotionFeatureWindow,
            layer2Output: Layer2Output,
        ): ActivityContextObservation {
            return ActivityContextObservation(
                timestampMs = layer2Output.timestampMs,
                layer2Label = layer2Output.activityHypothesis.label,
                confidence = minOf(layer2Output.confidence, motionFeatureWindow.features.confidenceFloor),
                supportPattern = motionFeatureWindow.derivedLabels.supportPattern,
                bottomDwellMs = motionFeatureWindow.features.stabilizationMs,
                hipVerticalDisplacement = motionFeatureWindow.features.hipVerticalDisplacement,
                phaseSequence = motionFeatureWindow.derivedLabels.phaseSequenceEstimate,
                evidenceRefs = (
                    motionFeatureWindow.evidenceRefs +
                        layer2Output.evidenceRefs
                    ).distinct(),
            )
        }
    }
}

data class ActivityContext(
    val state: ActivityContextState,
    val taskLabel: String?,
    val confidence: Float,
    val ambiguityNote: String? = null,
    val templateScores: Map<String, Float> = emptyMap(),
    val evidenceRefs: List<String> = emptyList(),
) {
    fun toDebugMap(): Map<String, Any?> {
        return mapOf(
            "state" to state.wireName,
            "task_label" to taskLabel,
            "confidence" to confidence,
            "ambiguity_note" to ambiguityNote,
            "template_scores" to templateScores,
            "evidence_refs" to evidenceRefs,
        )
    }

    companion object {
        fun unknown(): ActivityContext {
            return ActivityContext(
                state = ActivityContextState.UNKNOWN,
                taskLabel = null,
                confidence = 0f,
                ambiguityNote = null,
                templateScores = emptyMap(),
                evidenceRefs = emptyList(),
            )
        }

        fun aggregate(contexts: List<ActivityContext>): ActivityContext {
            val usable = contexts.filter { it.state != ActivityContextState.UNKNOWN }
            if (usable.isEmpty()) return unknown()
            val ambiguous = usable.lastOrNull { it.state == ActivityContextState.AMBIGUOUS }
            if (ambiguous != null) return ambiguous
            return usable.last()
        }
    }
}

enum class ActivityContextState(val wireName: String) {
    UNKNOWN("unknown"),
    CALIBRATING("calibrating"),
    LOCKED("locked"),
    SUSPECT_SWITCH("suspect_switch"),
    AMBIGUOUS("ambiguous"),
}
