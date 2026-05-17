package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrustUiStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun litertBackendMapsToLocalGemmaBadge() {
        val badge = TrustUiMapper.sourceBadge(
            backend = "litert-lm:gpu",
            fallback = false,
        )

        assertEquals(TrustSourceKind.LOCAL_GEMMA, badge.kind)
        assertEquals("Local Gemma", badge.label)
    }

    @Test
    fun fallbackMapsToTemplateFallbackBadge() {
        val badge = TrustUiMapper.sourceBadge(
            backend = "fallback",
            fallback = true,
        )

        assertEquals(TrustSourceKind.TEMPLATE_FALLBACK, badge.kind)
        assertEquals("Template fallback", badge.label)
    }

    @Test
    fun lowConfidenceMapsToAbstainedBadge() {
        val badge = TrustUiMapper.sourceBadge(
            backend = "litert-lm:gpu",
            fallback = false,
            qualityFlags = listOf(
                QualityFlag(
                    id = "rule_pose_confidence",
                    status = "LOW_CONFIDENCE",
                    value = 0.2f,
                    threshold = 0.5f,
                    evidence = "low confidence",
                )
            ),
        )

        assertEquals(TrustSourceKind.ABSTAINED, badge.kind)
        assertEquals("Abstained", badge.label)
    }

    @Test
    fun missingModelMapsToModelMissingReadiness() {
        val snapshot = ModelReadinessSnapshot.from(
            liteRtModelPath = null,
            backend = "fallback",
            fallback = true,
        )

        assertEquals(ModelReadinessStatus.MODEL_MISSING, snapshot.status)
        assertEquals("Model missing", snapshot.label)
        assertEquals("litert_model_file_not_found", snapshot.fallbackReason)
    }

    @Test
    fun existingModelWithLocalBackendMapsToReadyReadiness() {
        val model = temporaryFolder.newFile("gemma-4-E2B-it.litertlm")
        model.writeText("model")

        val snapshot = ModelReadinessSnapshot.from(
            liteRtModelPath = model.absolutePath,
            backend = "litert-lm:gpu",
            fallback = false,
        )

        assertEquals(ModelReadinessStatus.LOCAL_GEMMA_READY, snapshot.status)
        assertEquals("Local Gemma ready", snapshot.label)
        assertEquals("gemma-4-E2B-it.litertlm", snapshot.modelFileName)
        assertEquals(model.length(), snapshot.modelSizeBytes)
    }

    @Test
    fun whyNotJudgedPrefersLowConfidence() {
        val summary = TrustUiMapper.whyNotJudgedSummary(
            card = EvidenceCard(),
            qualityFlags = listOf(
                QualityFlag(
                    id = "rule_pose_confidence",
                    status = "LOW_CONFIDENCE",
                    value = 0.2f,
                    threshold = 0.5f,
                    evidence = "low confidence",
                )
            ),
        )

        assertEquals("Pose confidence too low", summary)
    }

    @Test
    fun coachBoundaryKeepsPosePreviewSeparateFromHardJudgment() {
        val state = LiveWorkoutState(
            reviewFrameStatus = ReviewFrameStatus(
                poseAvailable = true,
                poseHiddenByQuality = true,
                noPoseReason = "exercise_keypoint_visibility_below_threshold",
            ),
            evidenceCard = EvidenceCard(evidenceRefs = listOf("metric.squat.depth")),
        )

        val boundary = TrustUiMapper.coachBoundaryState(state)

        assertEquals(CoachBoundaryKind.MONITOR_ONLY, boundary.kind)
        assertEquals("Pose preview only", boundary.title)
        assertTrue(boundary.summary.contains("Hard judgment blocked"))
        assertEquals(listOf("metric.squat.depth"), boundary.evidenceRefs)
    }

    @Test
    fun coachBoundaryShowsRefusalReasonAndAlternative() {
        val state = LiveWorkoutState(
            coachInsight = CoachInsight(
                functionName = "refuse_unsupported_question",
                argsJson = """{"reason":"capability_contract_blocked","safe_alternative":"Use visible pose evidence only."}""",
                backend = "litert-lm:gpu",
                fallback = false,
                evidenceRefs = listOf("metric.pose.visibility"),
            ),
        )

        val boundary = TrustUiMapper.coachBoundaryState(state)

        assertEquals(CoachBoundaryKind.REFUSED, boundary.kind)
        assertEquals("Refused unsupported claim", boundary.title)
        assertEquals("outside the supported judgment contract", boundary.summary)
        assertEquals("Use visible pose evidence only.", boundary.detail)
    }
}
