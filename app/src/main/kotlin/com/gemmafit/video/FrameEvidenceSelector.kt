package com.gemmafit.video

import kotlin.math.abs

/**
 * Selects compact visual evidence for low-frequency multimodal sidecars.
 *
 * Coordinates follow image convention: smaller y is higher in the frame and
 * larger y is lower in the frame. Positive hipVelocityY means moving downward.
 */
data class FrameEvidenceCandidate(
    val frameIndex: Int,
    val timestampMs: Long,
    val phase: String = PHASE_UNKNOWN,
    val poseConfidence: Float,
    val fullBodyVisibility: Float,
    val subjectObserved: Boolean,
    val subjectStable: Boolean,
    val hipY: Float? = null,
    val hipVelocityY: Float? = null,
    val blurScore: Float? = null,
    val hasWarning: Boolean = false,
    val warningIds: List<String> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
) {
    companion object {
        const val PHASE_TOP = "top"
        const val PHASE_DESCENT = "descent"
        const val PHASE_BOTTOM = "bottom"
        const val PHASE_ASCENT = "ascent"
        const val PHASE_UNKNOWN = "unknown"
    }
}

data class SelectedEvidenceFrames(
    val sceneAnchor: FrameEvidenceCandidate?,
    val top: FrameEvidenceCandidate?,
    val descent: FrameEvidenceCandidate?,
    val bottom: FrameEvidenceCandidate?,
    val ascent: FrameEvidenceCandidate?,
    val warningFrame: FrameEvidenceCandidate?,
    val panelConfidence: String,
    val selectionBasis: List<String>,
) {
    fun phaseFrames(): List<FrameEvidenceCandidate> {
        return listOfNotNull(top, descent, bottom, ascent)
    }

    fun allSelectedFrames(): List<FrameEvidenceCandidate> {
        return listOfNotNull(sceneAnchor, top, descent, bottom, ascent, warningFrame)
            .distinctBy { it.frameIndex }
    }
}

data class FrameEvidenceSelectionOptions(
    val minPoseConfidence: Float = 0.6f,
    val minFullBodyVisibility: Float = 0.6f,
    val minTimestampGapMs: Long = 120L,
)

object FrameEvidenceSelector {
    const val CONFIDENCE_HIGH = "high"
    const val CONFIDENCE_MEDIUM = "medium"
    const val CONFIDENCE_LOW = "low"
    const val PANEL_CONFIDENCE_HIGH = CONFIDENCE_HIGH
    const val PANEL_CONFIDENCE_MEDIUM = CONFIDENCE_MEDIUM
    const val PANEL_CONFIDENCE_LOW = CONFIDENCE_LOW

    fun select(
        candidates: List<FrameEvidenceCandidate>,
        warningTimestampMs: Long? = null,
        options: FrameEvidenceSelectionOptions = FrameEvidenceSelectionOptions(),
    ): SelectedEvidenceFrames {
        val eligible = candidates
            .filter { it.isEligible(options) }
            .distinctBy { it.frameIndex }
            .sortedBy { it.timestampMs }

        if (eligible.isEmpty()) {
            return SelectedEvidenceFrames(
                sceneAnchor = null,
                top = null,
                descent = null,
                bottom = null,
                ascent = null,
                warningFrame = null,
                panelConfidence = CONFIDENCE_LOW,
                selectionBasis = listOf("no_eligible_frames"),
            )
        }

        val stats = SelectionStats.from(eligible)
        val phaseSelected = mutableListOf<FrameEvidenceCandidate>()
        val basis = mutableListOf("filtered_low_confidence_or_unstable_frames")

        val sceneAnchor = eligible.maxByOrNull { candidate ->
            candidate.qualityScore() - if (candidate.hasWarning) 0.08f else 0f
        }
        sceneAnchor?.let {
            basis += "scene_anchor_highest_quality"
        }

        val top = pickBestPhaseFrame(
            eligible = eligible,
            selected = phaseSelected,
            options = options,
            scorer = { it.topScore(stats) },
            fallbackScorer = { it.uniformCoverageScore(eligible, 0f) },
        )
        top?.let {
            phaseSelected += it
            basis += "top_phase_or_high_hip_frame"
        }

        val descent = pickBestPhaseFrame(
            eligible = eligible,
            selected = phaseSelected,
            options = options,
            scorer = { it.descentScore(stats) },
            fallbackScorer = { it.uniformCoverageScore(eligible, 0.35f) },
        )
        descent?.let {
            phaseSelected += it
            basis += "descent_downward_velocity_frame"
        }

        val bottom = pickBestPhaseFrame(
            eligible = eligible,
            selected = phaseSelected,
            options = options,
            scorer = { it.bottomScore(stats) },
            fallbackScorer = { it.uniformCoverageScore(eligible, 0.65f) },
        )
        bottom?.let {
            phaseSelected += it
            basis += "bottom_low_hip_low_velocity_frame"
        }

        val ascent = pickBestPhaseFrame(
            eligible = eligible,
            selected = phaseSelected,
            options = options,
            scorer = { it.ascentScore(stats) },
            fallbackScorer = { it.uniformCoverageScore(eligible, 1f) },
        )
        ascent?.let {
            phaseSelected += it
            basis += "ascent_upward_velocity_frame"
        }

        val warningFrame = selectWarningFrame(
            eligible = eligible,
            selected = listOfNotNull(sceneAnchor) + phaseSelected,
            warningTimestampMs = warningTimestampMs,
            options = options,
        )
        warningFrame?.let {
            basis += "warning_timestamp_covered"
        }

        val phaseFrames = listOf(top, descent, bottom, ascent)
        val labeledPhaseCount = phaseFrames.count { it?.phase?.isKnownPhase() == true }
        val selectedPhaseCount = phaseFrames.count { it != null }
        val usedFallback = selectedPhaseCount > labeledPhaseCount || selectedPhaseCount < 4
        val panelConfidence = when {
            labeledPhaseCount == 4 && sceneAnchor != null -> CONFIDENCE_HIGH
            usedFallback -> CONFIDENCE_LOW
            selectedPhaseCount >= 3 && sceneAnchor != null -> CONFIDENCE_MEDIUM
            else -> CONFIDENCE_LOW
        }
        if (usedFallback) basis += "fallback_sparse_sampling_or_derived_phase"
        basis += "panel_confidence_$panelConfidence"

        return SelectedEvidenceFrames(
            sceneAnchor = sceneAnchor,
            top = top,
            descent = descent,
            bottom = bottom,
            ascent = ascent,
            warningFrame = warningFrame,
            panelConfidence = panelConfidence,
            selectionBasis = basis.distinct(),
        )
    }

    private fun FrameEvidenceCandidate.isEligible(options: FrameEvidenceSelectionOptions): Boolean =
        subjectObserved &&
            subjectStable &&
            poseConfidence >= options.minPoseConfidence &&
            fullBodyVisibility >= options.minFullBodyVisibility

    private fun pickBestPhaseFrame(
        eligible: List<FrameEvidenceCandidate>,
        selected: List<FrameEvidenceCandidate>,
        options: FrameEvidenceSelectionOptions,
        scorer: (FrameEvidenceCandidate) -> Float,
        fallbackScorer: (FrameEvidenceCandidate) -> Float,
    ): FrameEvidenceCandidate? {
        val nonDuplicate = eligible.filterNot { candidate ->
            selected.any { it.frameIndex == candidate.frameIndex }
        }
        if (nonDuplicate.isEmpty()) return null

        val withGap = nonDuplicate.filter { candidate ->
            selected.none { abs(it.timestampMs - candidate.timestampMs) < options.minTimestampGapMs }
        }
        val bestWithGap = withGap.maxByOrNull(scorer)
        val bestWithGapScore = bestWithGap?.let(scorer) ?: Float.NEGATIVE_INFINITY
        val bestOverall = nonDuplicate.maxByOrNull(scorer)
        val bestOverallScore = bestOverall?.let(scorer) ?: Float.NEGATIVE_INFINITY

        if (
            bestWithGap != null &&
            bestWithGapScore > MIN_PHASE_SCORE &&
            (bestWithGapScore >= STRONG_PHASE_SCORE || bestOverallScore < STRONG_PHASE_SCORE)
        ) {
            return bestWithGap
        }
        if (bestOverall != null && bestOverallScore >= STRONG_PHASE_SCORE) {
            return bestOverall
        }
        if (bestWithGap != null && bestWithGapScore > MIN_PHASE_SCORE) {
            return bestWithGap
        }
        return withGap.ifEmpty { nonDuplicate }.maxByOrNull(fallbackScorer)
    }

    private fun selectWarningFrame(
        eligible: List<FrameEvidenceCandidate>,
        selected: List<FrameEvidenceCandidate>,
        warningTimestampMs: Long?,
        options: FrameEvidenceSelectionOptions,
    ): FrameEvidenceCandidate? {
        val warningCandidates = eligible.filter { it.hasWarning || it.warningIds.isNotEmpty() }
        if (warningCandidates.isEmpty()) return null
        val nonDuplicate = warningCandidates.filterNot { candidate ->
            selected.any { it.frameIndex == candidate.frameIndex }
        }
        val pool = nonDuplicate.ifEmpty { warningCandidates }
        val withGap = pool.filter { candidate ->
            selected.none { abs(it.timestampMs - candidate.timestampMs) < options.minTimestampGapMs }
        }.ifEmpty { pool }

        return if (warningTimestampMs != null) {
            withGap.minByOrNull { abs(it.timestampMs - warningTimestampMs) }
        } else {
            withGap.maxByOrNull { it.qualityScore() }
        }
    }

    private fun FrameEvidenceCandidate.qualityScore(): Float {
        val blurPenalty = (blurScore ?: 0f).coerceIn(0f, 1f) * 0.05f
        return poseConfidence.coerceIn(0f, 1f) * 0.45f +
            fullBodyVisibility.coerceIn(0f, 1f) * 0.35f +
            0.15f +
            0.05f -
            blurPenalty
    }

    private fun FrameEvidenceCandidate.topScore(stats: SelectionStats): Float =
        phaseScore(FrameEvidenceCandidate.PHASE_TOP) +
            stats.highInFrameScore(hipY) +
            stats.stillnessScore(hipVelocityY) +
            qualityScore() * QUALITY_PHASE_WEIGHT

    private fun FrameEvidenceCandidate.bottomScore(stats: SelectionStats): Float =
        phaseScore(FrameEvidenceCandidate.PHASE_BOTTOM) +
            stats.lowInFrameScore(hipY) +
            stats.stillnessScore(hipVelocityY) +
            qualityScore() * QUALITY_PHASE_WEIGHT

    private fun FrameEvidenceCandidate.descentScore(stats: SelectionStats): Float =
        phaseScore(FrameEvidenceCandidate.PHASE_DESCENT) +
            stats.downwardVelocityScore(hipVelocityY) +
            qualityScore() * QUALITY_PHASE_WEIGHT

    private fun FrameEvidenceCandidate.ascentScore(stats: SelectionStats): Float =
        phaseScore(FrameEvidenceCandidate.PHASE_ASCENT) +
            stats.upwardVelocityScore(hipVelocityY) +
            qualityScore() * QUALITY_PHASE_WEIGHT

    private fun FrameEvidenceCandidate.phaseScore(expected: String): Float =
        when {
            phase.equals(expected, ignoreCase = true) -> 2.0f
            phase.equals(FrameEvidenceCandidate.PHASE_UNKNOWN, ignoreCase = true) -> 0.0f
            else -> -0.25f
        }

    private fun FrameEvidenceCandidate.uniformCoverageScore(
        eligible: List<FrameEvidenceCandidate>,
        targetProgress: Float,
    ): Float {
        if (eligible.size <= 1) return qualityScore()
        val first = eligible.first().timestampMs
        val last = eligible.last().timestampMs
        val span = (last - first).coerceAtLeast(1L).toFloat()
        val progress = ((timestampMs - first).toFloat() / span).coerceIn(0f, 1f)
        return qualityScore() + (1f - abs(progress - targetProgress).coerceIn(0f, 1f))
    }

    private fun String.isKnownPhase(): Boolean =
        equals(FrameEvidenceCandidate.PHASE_TOP, ignoreCase = true) ||
            equals(FrameEvidenceCandidate.PHASE_DESCENT, ignoreCase = true) ||
            equals(FrameEvidenceCandidate.PHASE_BOTTOM, ignoreCase = true) ||
            equals(FrameEvidenceCandidate.PHASE_ASCENT, ignoreCase = true)

    private data class SelectionStats(
        val minHipY: Float,
        val maxHipY: Float,
        val maxDownwardVelocity: Float,
        val maxUpwardVelocity: Float,
        val maxAbsVelocity: Float,
    ) {
        fun highInFrameScore(hipY: Float?): Float {
            val y = hipY ?: return 0f
            val range = (maxHipY - minHipY).takeIf { it > EPSILON } ?: return 0f
            return ((maxHipY - y) / range).coerceIn(0f, 1f)
        }

        fun lowInFrameScore(hipY: Float?): Float {
            val y = hipY ?: return 0f
            val range = (maxHipY - minHipY).takeIf { it > EPSILON } ?: return 0f
            return ((y - minHipY) / range).coerceIn(0f, 1f)
        }

        fun downwardVelocityScore(velocity: Float?): Float {
            val v = velocity ?: return 0f
            if (v <= 0f || maxDownwardVelocity <= EPSILON) return 0f
            return (v / maxDownwardVelocity).coerceIn(0f, 1f)
        }

        fun upwardVelocityScore(velocity: Float?): Float {
            val v = velocity ?: return 0f
            if (v >= 0f || maxUpwardVelocity <= EPSILON) return 0f
            return ((-v) / maxUpwardVelocity).coerceIn(0f, 1f)
        }

        fun stillnessScore(velocity: Float?): Float {
            val v = abs(velocity ?: return 0f)
            if (maxAbsVelocity <= EPSILON) return 1f
            return (1f - (v / maxAbsVelocity).coerceIn(0f, 1f))
        }

        companion object {
            fun from(candidates: List<FrameEvidenceCandidate>): SelectionStats {
                val hipValues = candidates.mapNotNull { it.hipY }
                val velocities = candidates.mapNotNull { it.hipVelocityY }
                val downward = velocities.filter { it > 0f }
                val upward = velocities.filter { it < 0f }.map { -it }
                return SelectionStats(
                    minHipY = hipValues.minOrNull() ?: 0f,
                    maxHipY = hipValues.maxOrNull() ?: 0f,
                    maxDownwardVelocity = downward.maxOrNull() ?: 0f,
                    maxUpwardVelocity = upward.maxOrNull() ?: 0f,
                    maxAbsVelocity = velocities.maxOfOrNull { abs(it) } ?: 0f,
                )
            }
        }
    }

    private const val QUALITY_PHASE_WEIGHT = 0.35f
    private const val MIN_PHASE_SCORE = 0.2f
    private const val STRONG_PHASE_SCORE = 1.75f
    private const val EPSILON = 0.0001f
}
