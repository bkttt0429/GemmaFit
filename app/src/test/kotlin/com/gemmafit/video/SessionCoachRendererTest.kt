package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoachRendererTest {
    @Test
    fun cleanSquatSummaryIsEvidenceAwarePositiveInsight() {
        val context = SessionCoachRenderer.contextFrom(
            SessionSummary(
                totalFrames = 90,
                totalReps = 3,
                avgFormScore = 96f,
                detection = ExerciseDetection(
                    mainExercise = "squat",
                    confidence = 0.94f,
                    detectedExercises = mapOf("squat" to 90),
                ),
                muscleFocusDistribution = mapOf(
                    "quadriceps" to 88,
                    "gluteus_maximus" to 81,
                ),
            )
        )

        val insight = SessionCoachRenderer.render(context)

        assertEquals("positive_reinforcement", insight.functionName)
        assertTrue(insight.whatISaw.contains("average form score of 96"))
        assertTrue(insight.whyItMatters.contains("checked movement evidence"))
        assertTrue(insight.notJudged.contains("muscle activation percentage"))
        assertFalse(insight.whatISaw.contains("Nice depth"))
    }

    @Test
    fun warningHeavySummaryUsesEvidenceGatedCorrectionWithoutMedicalClaim() {
        val context = SessionCoachRenderer.contextFrom(
            SessionSummary(
                totalFrames = 120,
                totalReps = 4,
                avgFormScore = 72f,
                detection = ExerciseDetection(mainExercise = "squat", confidence = 0.9f),
                safetyEvents = listOf(
                    SafetyEvent(
                        rule = 1,
                        functionName = "correct_knee_alignment",
                        description = "Knee tracking drifted inward.",
                        severity = "medium",
                        joint = "knee",
                        frameIndex = 42,
                    ),
                    SafetyEvent(
                        rule = 1,
                        functionName = "correct_knee_alignment",
                        description = "Knee tracking drifted inward.",
                        severity = "medium",
                        joint = "knee",
                        frameIndex = 56,
                    ),
                ),
            )
        )

        val insight = SessionCoachRenderer.render(context)
        val combined = listOf(
            insight.headline,
            insight.whatISaw,
            insight.whyItMatters,
            insight.notJudged,
            insight.nextFocus,
        ).joinToString(" ").lowercase()

        assertEquals("correct_knee_alignment", insight.functionName)
        assertTrue(insight.whyItMatters.contains("repeatable biomechanics evidence"))
        assertFalse(combined.contains("diagnosis"))
        assertFalse(combined.contains("injury"))
        assertFalse(combined.contains("medical"))
    }

    @Test
    fun summaryChoosesDominantSafetyFunctionInsteadOfFirstEvent() {
        val context = SessionCoachRenderer.contextFrom(
            SessionSummary(
                totalFrames = 120,
                totalReps = 1,
                avgFormScore = 46f,
                detection = ExerciseDetection(mainExercise = "deadlift", confidence = 0.7f),
                safetyEvents = listOf(
                    SafetyEvent(
                        rule = 1,
                        functionName = "correct_knee_alignment",
                        description = "Knee alignment proxy was limited by view.",
                        severity = "high",
                        joint = "knee",
                        frameIndex = 12,
                    ),
                    SafetyEvent(
                        rule = 2,
                        functionName = "correct_spinal_alignment",
                        description = "Torso angle drifted during the hinge.",
                        severity = "high",
                        joint = "spine",
                        frameIndex = 20,
                    ),
                    SafetyEvent(
                        rule = 2,
                        functionName = "correct_spinal_alignment",
                        description = "Torso angle drifted during the hinge.",
                        severity = "high",
                        joint = "spine",
                        frameIndex = 40,
                    ),
                ),
            )
        )

        val insight = SessionCoachRenderer.render(context)

        assertEquals("correct_spinal_alignment", insight.functionName)
        assertTrue(insight.nextFocus.contains("brace first"))
        assertTrue(insight.whatISaw.contains("deadlift"))
    }

    @Test
    fun limitedEvidenceSummaryExplainsSkippedJudgments() {
        val context = SessionCoachRenderer.contextFrom(
            SessionSummary(
                totalFrames = 81,
                totalReps = 2,
                avgFormScore = 83f,
                detection = ExerciseDetection(mainExercise = "squat", confidence = 0.88f),
                viewLimitedCount = 19,
                lowConfidenceCount = 4,
                notApplicableCounts = mapOf("knee_valgus_fppa" to 19),
            )
        )

        val insight = SessionCoachRenderer.render(context)

        assertEquals("refuse_unsupported_question", insight.functionName)
        assertTrue(insight.headline.contains("camera-limited evidence"))
        assertTrue(insight.whatISaw.contains("view-limited frames"))
        assertTrue(insight.notJudged.contains("knee valgus fppa"))
        assertTrue(insight.notJudged.contains("camera-limited view"))
    }

    @Test
    fun modelResultCanProvideNextFocusAndEvidenceRefs() {
        val context = SessionCoachRenderer.contextFrom(
            SessionSummary(
                totalFrames = 60,
                totalReps = 2,
                avgFormScore = 98f,
                detection = ExerciseDetection(mainExercise = "squat", confidence = 0.96f),
            )
        )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "positive_reinforcement",
            argsJson = """{"next_focus":"Keep the ascent tempo controlled while holding the same depth."}""",
            backend = "litert-lm:gpu",
            selectionBasis = "Clean summary evidence with stable score.",
            evidenceRefs = listOf("avg_form_score", "total_reps", "tempo"),
            modelInfoJson = "{}",
            rawResponse = "raw",
            inferenceTimeMs = 128.0,
        )

        val insight = SessionCoachRenderer.render(context, result)

        assertEquals("litert-lm:gpu", insight.backend)
        assertFalse(insight.fallback)
        assertEquals("positive_reinforcement", insight.functionName)
        assertEquals(
            "Keep the ascent tempo controlled while holding the same depth.",
            insight.nextFocus,
        )
        assertEquals(listOf("avg_form_score", "total_reps", "tempo"), insight.evidenceRefs)
    }

    @Test
    fun summarySafetyJsonIncludesCapabilityContractWithoutRawPose() {
        val context = SessionCoachContext(
            totalFrames = 40,
            totalReps = 2,
            avgFormScore = 91f,
            durationSeconds = 12,
            mainExercise = "squat",
            exerciseConfidence = 0.9f,
            detectedExercises = mapOf("squat" to 40),
            safetyEvents = emptyList(),
            formScores = emptyList(),
            viewLimitedCount = 0,
            lowConfidenceCount = 0,
            notApplicableCounts = emptyMap(),
            muscleFocusDistribution = emptyMap(),
            repHistory = emptyList(),
            capabilityContract = CapabilityContract(
                canJudge = listOf(
                    CapabilityJudgment(
                        metric = "squat_depth",
                        confidenceCeiling = 0.9f,
                        evidenceRefs = listOf("metric.squat.depth"),
                    )
                ),
                cannotJudge = listOf(
                    CapabilityJudgment(
                        metric = "joint_force",
                        reason = "single_camera_proxy",
                        requiredEvidence = listOf("force_plate_or_inverse_dynamics"),
                    )
                ),
            ),
            evidenceRefs = listOf("metric.squat.depth"),
        )

        val json = SessionCoachRenderer.buildSafetyJson(context)

        assertTrue(json.contains("capability_contract"))
        assertTrue(json.contains("metric.squat.depth"))
        assertFalse(json.contains("landmarks"))
        assertFalse(json.contains("raw_video"))
    }

    @Test
    fun invalidEvidenceRefsForceDeterministicFallback() {
        val context = SessionCoachContext(
            totalFrames = 40,
            totalReps = 2,
            avgFormScore = 91f,
            durationSeconds = 12,
            mainExercise = "squat",
            exerciseConfidence = 0.9f,
            detectedExercises = mapOf("squat" to 40),
            safetyEvents = emptyList(),
            formScores = emptyList(),
            viewLimitedCount = 0,
            lowConfidenceCount = 0,
            notApplicableCounts = emptyMap(),
            muscleFocusDistribution = emptyMap(),
            repHistory = emptyList(),
            evidenceRefs = listOf("metric.squat.depth"),
        )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "positive_reinforcement",
            argsJson = "{}",
            backend = "litert-lm:gpu",
            selectionBasis = "bad ref",
            evidenceRefs = listOf("metric.other.fake"),
            modelInfoJson = "{}",
            rawResponse = "{}",
            inferenceTimeMs = 10.0,
        )

        val validated = SessionCoachRenderer.validateModelResult(context, result)

        assertFalse(validated.success)
        assertEquals("invalid_evidence_refs", validated.errorMessage)
    }

    @Test
    fun capabilityContractBlocksUnsupportedToolSelection() {
        val context = SessionCoachContext(
            totalFrames = 40,
            totalReps = 2,
            avgFormScore = 91f,
            durationSeconds = 12,
            mainExercise = "squat",
            exerciseConfidence = 0.9f,
            detectedExercises = mapOf("squat" to 40),
            safetyEvents = emptyList(),
            formScores = emptyList(),
            viewLimitedCount = 20,
            lowConfidenceCount = 0,
            notApplicableCounts = mapOf("frontal_knee_valgus" to 20),
            muscleFocusDistribution = emptyMap(),
            repHistory = emptyList(),
            capabilityContract = CapabilityContract(
                canJudge = listOf(CapabilityJudgment(metric = "squat_depth")),
                cannotJudge = listOf(CapabilityJudgment(metric = "frontal_knee_valgus", reason = "side_view")),
            ),
        )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "correct_knee_alignment",
            argsJson = "{}",
            backend = "litert-lm:gpu",
            selectionBasis = "side view knee claim",
            evidenceRefs = emptyList(),
            modelInfoJson = "{}",
            rawResponse = "{}",
            inferenceTimeMs = 10.0,
        )

        val validated = SessionCoachRenderer.validateModelResult(context, result)

        assertFalse(validated.success)
        assertEquals("refuse_unsupported_question", validated.functionName)
        assertEquals("capability_contract_blocked", validated.errorMessage)
    }
}
