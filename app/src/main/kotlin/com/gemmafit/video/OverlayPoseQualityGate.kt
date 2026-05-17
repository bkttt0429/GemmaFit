package com.gemmafit.video

/**
 * Decides whether a detected pose is too misleading to draw as a full skeleton.
 *
 * This is intentionally narrower than the hard-judgment confidence gate for
 * exercise-template uncertainty, but LOW_CONFIDENCE pose frames should not draw
 * a complete skeleton. The overlay is visual evidence; when pose confidence is
 * below the judgment floor, hiding the skeleton is less misleading than drawing
 * a plausible-looking body on the wrong object.
 */
object OverlayPoseQualityGate {
    private val torsoIndices = intArrayOf(11, 12, 23, 24)
    private val lowerBodyIndices = intArrayOf(23, 24, 25, 26, 27, 28)
    private val criticalIndices = intArrayOf(11, 12, 23, 24, 25, 26, 27, 28)

    private val blockingTokens = listOf(
        "critical_keypoint_visibility",
        "critical_landmark_visibility",
        "landmark_geometry_invalid",
        "pose_geometry_invalid",
        "occlusion",
        "occluded",
        "barbell",
        "equipment_blocking",
    )

    private const val DRAW_VISIBILITY_MIN = 0.35f
    private const val MIN_TORSO_VISIBLE = 3
    private const val MIN_CRITICAL_VISIBLE = 5
    private const val MIN_LOWER_VISIBLE_FOR_LIMITED_VIEW = 4
    private const val MIN_CRITICAL_AVG_VISIBILITY = 0.32f
    private const val MIN_TORSO_SPAN = 0.025f

    fun shouldHideSkeleton(qualityFlags: List<QualityFlag>): Boolean {
        return qualityFlags.any { it.blocksOverlaySkeleton() }
    }

    fun shouldHideSkeleton(
        qualityFlags: List<QualityFlag>,
        evidenceCard: EvidenceCard,
    ): Boolean {
        return shouldHideSkeleton(qualityFlags) ||
            !evidenceCard.isExerciseVisibilityPreviewOnly() &&
            evidenceCard.blocksOverlaySkeleton()
    }

    fun shouldHideSkeleton(
        landmarks: List<PoseLandmarkData>,
        qualityFlags: List<QualityFlag>,
        evidenceCard: EvidenceCard,
    ): Boolean {
        if (shouldHideSkeleton(qualityFlags, evidenceCard)) return true
        val issue = landmarkQualityIssue(landmarks) ?: return false
        return issue.hardBlock || isLimitedContext(qualityFlags, evidenceCard)
    }

    fun hideReason(qualityFlags: List<QualityFlag>): String {
        return qualityFlags
            .firstOrNull { flag -> flag.blocksOverlaySkeleton() }
            ?.let { flag ->
                flag.reason.ifBlank {
                    flag.id.ifBlank { "critical_keypoints_unreliable" }
                }
            }
            ?: "critical_keypoints_unreliable"
    }

    fun hideReason(
        qualityFlags: List<QualityFlag>,
        evidenceCard: EvidenceCard,
    ): String {
        if (shouldHideSkeleton(qualityFlags)) {
            return hideReason(qualityFlags)
        }
        return evidenceCard.reason.ifBlank { "critical_keypoints_unreliable" }
    }

    fun hideReason(
        landmarks: List<PoseLandmarkData>,
        qualityFlags: List<QualityFlag>,
        evidenceCard: EvidenceCard,
    ): String {
        if (shouldHideSkeleton(qualityFlags)) {
            return hideReason(qualityFlags)
        }
        if (shouldHideSkeleton(qualityFlags, evidenceCard)) {
            return evidenceCard.reason.ifBlank { "critical_keypoints_unreliable" }
        }
        return landmarkQualityIssue(landmarks)?.reason
            ?: evidenceCard.reason.ifBlank { "critical_keypoints_unreliable" }
    }

    private fun QualityFlag.searchText(): String {
        return listOf(id, evidence, reason, joint)
            .joinToString(" ")
            .lowercase()
    }

    private fun QualityFlag.blocksOverlaySkeleton(): Boolean {
        if (isExerciseVisibilityPreviewOnly()) return false
        val status = status.uppercase()
        if (status == "LOW_CONFIDENCE") return true
        return status == "VIEW_LIMITED" &&
            blockingTokens.any { token -> token in searchText() }
    }

    private fun QualityFlag.isExerciseVisibilityPreviewOnly(): Boolean {
        return searchText().contains("exercise_keypoint_visibility")
    }

    private fun EvidenceCard.searchText(): String {
        return (
            listOf(verdict, reason, modelBoundary) +
                trustFlags +
                evidenceRefs +
                evidence.flatMap { listOf(it.label, it.value) }
            )
            .joinToString(" ")
            .lowercase()
    }

    private fun EvidenceCard.blocksOverlaySkeleton(): Boolean {
        val status = verdict.uppercase()
        if (status == "LOW_CONFIDENCE") return true
        return status == "VIEW_LIMITED" &&
            blockingTokens.any { token -> token in searchText() }
    }

    private fun EvidenceCard.isExerciseVisibilityPreviewOnly(): Boolean {
        return searchText().contains("exercise_keypoint_visibility")
    }

    private fun isLimitedContext(
        qualityFlags: List<QualityFlag>,
        evidenceCard: EvidenceCard,
    ): Boolean {
        return evidenceCard.verdict.uppercase() in setOf("VIEW_LIMITED", "LOW_CONFIDENCE") ||
            qualityFlags.any { it.status.uppercase() in setOf("VIEW_LIMITED", "LOW_CONFIDENCE") } ||
            evidenceCard.searchText().contains("view_limited") ||
            evidenceCard.searchText().contains("exercise_not_identified")
    }

    private fun landmarkQualityIssue(landmarks: List<PoseLandmarkData>): LandmarkQualityIssue? {
        if (landmarks.size < 33) {
            return LandmarkQualityIssue("pose_landmarks_missing", hardBlock = true)
        }

        val torsoVisible = visibleCount(landmarks, torsoIndices)
        if (torsoVisible < MIN_TORSO_VISIBLE) {
            return LandmarkQualityIssue("pose_torso_keypoints_unreliable", hardBlock = true)
        }

        val torsoSpan = maxPairDistance(landmarks, torsoIndices)
        if (torsoSpan < MIN_TORSO_SPAN) {
            return LandmarkQualityIssue("pose_geometry_invalid", hardBlock = true)
        }

        val criticalVisible = visibleCount(landmarks, criticalIndices)
        val criticalAvg = averageVisibility(landmarks, criticalIndices)
        if (criticalVisible < MIN_CRITICAL_VISIBLE && criticalAvg < MIN_CRITICAL_AVG_VISIBILITY) {
            return LandmarkQualityIssue("critical_keypoints_unreliable", hardBlock = true)
        }

        val lowerVisible = visibleCount(landmarks, lowerBodyIndices)
        if (lowerVisible < MIN_LOWER_VISIBLE_FOR_LIMITED_VIEW) {
            return LandmarkQualityIssue("lower_body_keypoints_unreliable", hardBlock = false)
        }

        return null
    }

    private fun visibleCount(landmarks: List<PoseLandmarkData>, indices: IntArray): Int {
        return indices.count { index ->
            val landmark = landmarks.getOrNull(index) ?: return@count false
            landmark.visibility >= DRAW_VISIBILITY_MIN &&
                landmark.x.isFinite() &&
                landmark.y.isFinite() &&
                landmark.x in -0.05f..1.05f &&
                landmark.y in -0.05f..1.05f
        }
    }

    private fun averageVisibility(landmarks: List<PoseLandmarkData>, indices: IntArray): Float {
        var total = 0f
        var count = 0
        for (index in indices) {
            val landmark = landmarks.getOrNull(index) ?: continue
            if (!landmark.visibility.isFinite()) continue
            total += landmark.visibility.coerceIn(0f, 1f)
            count += 1
        }
        return if (count == 0) 0f else total / count
    }

    private fun maxPairDistance(landmarks: List<PoseLandmarkData>, indices: IntArray): Float {
        var maxDistance = 0f
        for (i in indices.indices) {
            val a = landmarks.getOrNull(indices[i]) ?: continue
            if (a.visibility < DRAW_VISIBILITY_MIN) continue
            for (j in i + 1 until indices.size) {
                val b = landmarks.getOrNull(indices[j]) ?: continue
                if (b.visibility < DRAW_VISIBILITY_MIN) continue
                val dx = a.x - b.x
                val dy = a.y - b.y
                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distance.isFinite()) {
                    maxDistance = maxOf(maxDistance, distance)
                }
            }
        }
        return maxDistance
    }

    private data class LandmarkQualityIssue(
        val reason: String,
        val hardBlock: Boolean,
    )
}
