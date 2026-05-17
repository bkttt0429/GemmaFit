package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPoseQualityGateTest {
    @Test
    fun doesNotHideSkeletonForExerciseKeypointVisibilityPreview() {
        val flags = listOf(
            QualityFlag(
                id = "exercise_keypoint_visibility",
                status = "VIEW_LIMITED",
                value = 0.42f,
                threshold = 0.65f,
                evidence = "pose_quality_gate",
                reason = "exercise_keypoint_visibility_below_threshold",
            )
        )

        assertFalse(OverlayPoseQualityGate.shouldHideSkeleton(flags))
    }

    @Test
    fun hidesSkeletonForCriticalKeypointVisibilityFailure() {
        val flags = listOf(
            QualityFlag(
                id = "critical_keypoint_visibility",
                status = "VIEW_LIMITED",
                value = 0.12f,
                threshold = 0.35f,
                evidence = "pose_quality_gate",
                reason = "critical_keypoint_visibility_below_threshold",
            )
        )

        assertTrue(OverlayPoseQualityGate.shouldHideSkeleton(flags))
        assertEquals(
            "critical_keypoint_visibility_below_threshold",
            OverlayPoseQualityGate.hideReason(flags),
        )
    }

    @Test
    fun hidesSkeletonForGenericLowConfidencePose() {
        val flags = listOf(
            QualityFlag(
                id = "pose_confidence",
                status = "LOW_CONFIDENCE",
                value = 0.51f,
                threshold = 0.7f,
                evidence = "pose_presence_gate",
                reason = "low_confidence",
            )
        )

        assertTrue(OverlayPoseQualityGate.shouldHideSkeleton(flags))
        assertEquals(
            "low_confidence",
            OverlayPoseQualityGate.hideReason(flags),
        )
    }

    @Test
    fun hidesSkeletonForLowConfidenceEvidenceCard() {
        val card = EvidenceCard(
            verdict = "LOW_CONFIDENCE",
            reason = "Low overall landmark visibility (0.597048 < 0.6). Adjust camera angle, distance, or lighting.",
        )

        assertTrue(OverlayPoseQualityGate.shouldHideSkeleton(emptyList(), card))
    }

    @Test
    fun doesNotHideSkeletonForExerciseTemplateUncertainty() {
        val flags = listOf(
            QualityFlag(
                id = "exercise_template",
                status = "VIEW_LIMITED",
                value = 1f,
                threshold = 0.45f,
                evidence = "heuristic_exercise_detection",
                reason = "exercise_not_identified_with_enough_confidence",
            )
        )

        assertFalse(OverlayPoseQualityGate.shouldHideSkeleton(flags))
    }

    @Test
    fun doesNotHideSkeletonWhenEvidenceCardReportsExerciseKeypointVisibilityPreview() {
        val card = EvidenceCard(
            verdict = "VIEW_LIMITED",
            reason = "exercise_keypoint_visibility_below_threshold",
        )

        assertFalse(OverlayPoseQualityGate.shouldHideSkeleton(emptyList(), card))
        assertEquals(
            "exercise_keypoint_visibility_below_threshold",
            OverlayPoseQualityGate.hideReason(emptyList(), card),
        )
    }

    @Test
    fun hidesSkeletonWhenLimitedViewHasMissingLowerBodyKeypoints() {
        val landmarks = fullBodyLandmarks().toMutableList()
        for (index in intArrayOf(25, 26, 27, 28)) {
            landmarks[index] = landmarks[index].copy(visibility = 0.05f)
        }
        val flags = listOf(
            QualityFlag(
                id = "exercise_template",
                status = "VIEW_LIMITED",
                value = 1f,
                threshold = 0.45f,
                evidence = "heuristic_exercise_detection",
                reason = "exercise_not_identified_with_enough_confidence",
            )
        )

        assertTrue(
            OverlayPoseQualityGate.shouldHideSkeleton(
                landmarks = landmarks,
                qualityFlags = flags,
                evidenceCard = EvidenceCard(),
            )
        )
        assertEquals(
            "lower_body_keypoints_unreliable",
            OverlayPoseQualityGate.hideReason(
                landmarks = landmarks,
                qualityFlags = flags,
                evidenceCard = EvidenceCard(),
            )
        )
    }

    @Test
    fun hidesSkeletonForCollapsedTorsoGeometryEvenWithoutQualityFlag() {
        val landmarks = fullBodyLandmarks().toMutableList()
        for (index in intArrayOf(11, 12, 23, 24)) {
            landmarks[index] = PoseLandmarkData(0.50f, 0.50f, 0f, 0.9f)
        }

        assertTrue(
            OverlayPoseQualityGate.shouldHideSkeleton(
                landmarks = landmarks,
                qualityFlags = emptyList(),
                evidenceCard = EvidenceCard(),
            )
        )
        assertEquals(
            "pose_geometry_invalid",
            OverlayPoseQualityGate.hideReason(
                landmarks = landmarks,
                qualityFlags = emptyList(),
                evidenceCard = EvidenceCard(),
            )
        )
    }

    private fun fullBodyLandmarks(): List<PoseLandmarkData> {
        val landmarks = MutableList(33) { PoseLandmarkData(0.5f, 0.5f, 0f, 0.85f) }
        landmarks[11] = PoseLandmarkData(0.42f, 0.25f, 0f, 0.9f)
        landmarks[12] = PoseLandmarkData(0.58f, 0.25f, 0f, 0.9f)
        landmarks[23] = PoseLandmarkData(0.44f, 0.52f, 0f, 0.9f)
        landmarks[24] = PoseLandmarkData(0.56f, 0.52f, 0f, 0.9f)
        landmarks[25] = PoseLandmarkData(0.43f, 0.72f, 0f, 0.9f)
        landmarks[26] = PoseLandmarkData(0.57f, 0.72f, 0f, 0.9f)
        landmarks[27] = PoseLandmarkData(0.42f, 0.90f, 0f, 0.9f)
        landmarks[28] = PoseLandmarkData(0.58f, 0.90f, 0f, 0.9f)
        return landmarks
    }
}
