package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import org.json.JSONObject
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
    fun visualContextIsPreservedInSafetyPacketEvidenceRefs() {
        val context = SessionCoachRenderer.contextFrom(
            SessionSummary(
                totalFrames = 96,
                totalReps = 4,
                avgFormScore = 82f,
                detection = ExerciseDetection(mainExercise = "supported_chair_squat", confidence = 0.9f),
                visualContext = SessionVisualContext(
                    env = SessionVisualContext.ENV_OUTDOOR,
                    support = SessionVisualContext.SUPPORT_CHAIR,
                    person = SessionVisualContext.PERSON_VISIBLE,
                    overlayReadable = true,
                    limited = false,
                    evidenceRefs = listOf(
                        SessionVisualContext.REF_ENV,
                        SessionVisualContext.REF_SUPPORT,
                        SessionVisualContext.REF_PERSON,
                        SessionVisualContext.REF_OVERLAY,
                        SessionVisualContext.REF_LIMITED,
                    ),
                ),
            ),
        )

        val packet = JSONObject(SessionCoachRenderer.buildSafetyJson(context))
        val visual = packet.getJSONObject("visual_context")
        val evidenceRefs = packet.getJSONArray("evidence_refs").toString()

        assertEquals("outdoor", visual.getString("env"))
        assertEquals("chair", visual.getString("support"))
        assertEquals("visible", visual.getString("person"))
        assertEquals(true, visual.getBoolean("overlay_readable"))
        assertEquals(false, visual.getBoolean("limited"))
        assertTrue(evidenceRefs.contains(SessionVisualContext.REF_SUPPORT))
        assertTrue(evidenceRefs.contains(SessionVisualContext.REF_OVERLAY))
    }

    @Test
    fun seniorHeroSummaryUsesCaregiverFriendlyCopy() {
        val context = SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 81,
                totalReps = 0,
                avgFormScore = 83f,
                detection = ExerciseDetection(mainExercise = "lunge", confidence = 0.88f),
                viewLimitedCount = 19,
                lowConfidenceCount = 4,
            ),
            seniorHeroMode = true,
        )

        val insight = SessionCoachRenderer.render(context)
        val combined = listOf(
            insight.headline,
            insight.whatISaw,
            insight.whyItMatters,
            insight.notJudged,
            insight.nextFocus,
        ).joinToString(" ").lowercase()

        assertTrue(insight.headline.contains("Senior Hero review"))
        assertTrue(insight.whatISaw.contains("81 frames from a supported home strength practice"))
        assertFalse(insight.whatISaw.contains("reviewed frames of"))
        assertTrue(insight.notJudged.contains("fall risk"))
        assertTrue(insight.notJudged.contains("joint force"))
        assertFalse(combined.contains("fall risk score"))
        assertFalse(combined.contains("rehabilitation progress. improved"))
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
            modelInfoJson = """{"model_path":"/data/user/0/com.gemmafit/files/models/gemma-4-e2b-it-official.litertlm","init_time_ms":9700,"attempt_count":2,"first_token_ms":3200,"constrained_decoding":true}""",
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
        assertEquals("gemma-4-e2b-it-official.litertlm", insight.modelFileName)
        assertEquals("/data/user/0/com.gemmafit/files/models/gemma-4-e2b-it-official.litertlm", insight.modelPath)
        assertEquals(9700L, insight.initTimeMs)
        assertEquals(2, insight.attemptCount)
        assertEquals(3200L, insight.firstTokenTimeMs)
        assertTrue(insight.constrainedDecoding)
    }

    @Test
    fun modelCareActivityLogFieldsRenderAsLocalGemmaNarrative() {
        val context = SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 81,
                totalReps = 3,
                avgFormScore = 89f,
                detection = ExerciseDetection(mainExercise = "chair_sit_to_stand", confidence = 0.93f),
                viewLimitedCount = 2,
            ),
            seniorHeroMode = true,
        ).copy(evidenceRefs = listOf("metric.senior.reps", "metric.senior.tempo"))
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "create_care_activity_log",
            argsJson = """
                {
                  "headline":"Local Gemma care log",
                  "what_was_completed":"Three supported sit-to-stand reps were completed during the reviewed window.",
                  "observations":"The tempo evidence stayed controlled, and the chair stayed available as support. Two frames were camera-limited, so the summary stays focused on visible movement.",
                  "not_judged":"This is an activity log, not a medical assessment.",
                  "next_session_focus":"Use the same chair setup and pause briefly at the top of each stand.",
                  "caregiver_note":"Local Gemma summarized only app-provided movement evidence.",
                  "evidence_refs":["metric.senior.reps","metric.senior.tempo"],
                  "selection_basis":"Completion and tempo evidence were available."
                }
            """.trimIndent(),
            backend = "litert-lm:raw:gpu",
            selectionBasis = "Completion and tempo evidence were available.",
            evidenceRefs = listOf("metric.senior.reps", "metric.senior.tempo"),
            modelInfoJson = "{}",
            rawResponse = "{}",
            inferenceTimeMs = 2400.0,
        )

        val insight = SessionCoachRenderer.render(context, result)

        assertEquals("Local Gemma care log", insight.headline)
        assertTrue(insight.whatISaw.contains("Three supported sit-to-stand reps"))
        assertTrue(insight.whatISaw.contains("tempo evidence stayed controlled"))
        assertEquals("Local Gemma summarized only app-provided movement evidence.", insight.whyItMatters)
        assertEquals("This is an activity log, not a medical assessment.", insight.notJudged)
        assertEquals("Use the same chair setup and pause briefly at the top of each stand.", insight.nextFocus)
        assertEquals("litert-lm:raw:gpu", insight.backend)
        assertFalse(insight.fallback)
    }

    @Test
    fun sessionSummaryPromptUsesSlimSingleToolContract() {
        val context = SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 81,
                totalReps = 3,
                avgFormScore = 89f,
                durationSeconds = 18,
                detection = ExerciseDetection(mainExercise = "chair_sit_to_stand", confidence = 0.93f),
                viewLimitedCount = 2,
                safetyEvents = listOf(
                    SafetyEvent(
                        rule = 2,
                        functionName = "correct_spinal_alignment",
                        description = "Trunk control monitor event.",
                        severity = "low",
                        joint = "spine",
                        frameIndex = 48,
                    )
                ),
            ),
            seniorHeroMode = true,
        ).copy(
            evidenceRefs = listOf(
                "metric.senior.reps",
                "metric.senior.tempo",
                "metric.senior.stability_events",
            )
        )
        val safety = JSONObject(SessionCoachRenderer.buildSafetyJson(context))
        val packet = LiteRtEvidencePromptRenderer.buildEvidencePacket(
            SessionCoachRenderer.toCoachContext(context),
            safety,
            ModelReasoningMode.SUMMARY_OPTIONAL,
        )
        val fullPrompt = LiteRtEvidencePromptRenderer.buildPrompt(packet)
        val slimPrompt = LiteRtEvidencePromptRenderer.buildSessionSummaryPrompt(packet)

        assertTrue(slimPrompt.length < fullPrompt.length)
        assertTrue("slim prompt length=${slimPrompt.length}", slimPrompt.length < 5_000)
        assertTrue(slimPrompt.contains("compressed_session_memory"))
        assertTrue(slimPrompt.contains("create_care_activity_log"))
        assertFalse(slimPrompt.contains("care_log_context"))
        assertFalse(slimPrompt.contains("correct_spinal_alignment"))
        assertFalse(slimPrompt.contains("ask_subjective_checkin"))
    }

    @Test
    fun localLiteRtErrorsMapToCleanFallbackBackendLabel() {
        val context = SessionCoachRenderer.contextFrom(
            SessionSummary(
                totalFrames = 81,
                totalReps = 3,
                avgFormScore = 89f,
                detection = ExerciseDetection(mainExercise = "chair_sit_to_stand", confidence = 0.93f),
            )
        )
        val result = LLMBridge.FunctionCallResult(
            success = false,
            functionName = "create_care_activity_log",
            argsJson = "{}",
            backend = "litert-lm:isolated:cpu",
            errorMessage = "Input token ids are too long. Exceeding the maximum number of tokens allowed: 3073 >= 2048",
            selectionBasis = "local model failed",
            evidenceRefs = emptyList(),
            modelInfoJson = "{}",
            rawResponse = "{}",
            inferenceTimeMs = 10.0,
        )

        val insight = SessionCoachRenderer.render(context, result)

        assertTrue(insight.fallback)
        assertEquals("fallback:local_model_unavailable", insight.backend)
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
        val parsed = JSONObject(json)

        assertTrue(json.contains("capability_contract"))
        assertTrue(json.contains("metric.squat.depth"))
        assertEquals("SESSION_SUMMARY", parsed.getString("trigger"))
        assertEquals(
            "create_care_activity_log",
            parsed.getJSONObject("output_contract").getString("required_function"),
        )
        assertFalse(json.contains("landmarks"))
        assertFalse(json.contains("raw_video"))
    }

    @Test
    fun summarySafetyJsonIncludesCompactActivityContext() {
        val context = SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 40,
                totalReps = 2,
                avgFormScore = 91f,
                durationSeconds = 12,
                detection = ExerciseDetection(mainExercise = "unknown", confidence = 0.4f),
                activityContext = ActivityContext(
                    state = ActivityContextState.LOCKED,
                    taskLabel = "chair_sit_to_stand",
                    confidence = 0.94f,
                    templateScores = mapOf("chair_sit_to_stand" to 0.94f, "supported_squat" to 0.21f),
                    evidenceRefs = listOf("activity_context.locked"),
                ),
            ),
            seniorHeroMode = true,
        )

        val json = SessionCoachRenderer.buildSafetyJson(context)
        val parsed = JSONObject(json)
        val activityContext = parsed.getJSONObject("activity_context")

        assertEquals("locked", activityContext.getString("state"))
        assertEquals("chair_sit_to_stand", activityContext.getString("task_label"))
        assertTrue(activityContext.getJSONArray("evidence_refs").toString().contains("activity_context.locked"))
        assertFalse(json.contains("template_scores"))
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
    fun thoughtLeakForcesDeterministicFallback() {
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
            selectionBasis = "Clean evidence.",
            evidenceRefs = listOf("metric.squat.depth"),
            modelInfoJson = "{}",
            rawResponse = "<think>I should infer risk.</think>{}",
            inferenceTimeMs = 10.0,
        )

        val validated = SessionCoachRenderer.validateModelResult(context, result)

        assertFalse(validated.success)
        assertEquals("thought_leak_detected", validated.errorMessage)
    }

    @Test
    fun forbiddenClaimInCareLogForcesDeterministicFallback() {
        val context = SessionCoachContext(
            totalFrames = 40,
            totalReps = 2,
            avgFormScore = 91f,
            durationSeconds = 12,
            mainExercise = "chair_sit_to_stand",
            exerciseConfidence = 0.9f,
            detectedExercises = mapOf("chair_sit_to_stand" to 40),
            safetyEvents = emptyList(),
            formScores = emptyList(),
            viewLimitedCount = 0,
            lowConfidenceCount = 0,
            notApplicableCounts = emptyMap(),
            muscleFocusDistribution = emptyMap(),
            repHistory = emptyList(),
            evidenceRefs = listOf("metric.senior.reps"),
        )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "create_care_activity_log",
            argsJson = """{"headline":"Care log","what_was_completed":"Two reps completed.","observations":"Fall risk improved.","evidence_refs":["metric.senior.reps"]}""",
            backend = "litert-lm:gpu",
            selectionBasis = "bad claim",
            evidenceRefs = listOf("metric.senior.reps"),
            modelInfoJson = "{}",
            rawResponse = "{}",
            inferenceTimeMs = 10.0,
        )

        val validated = SessionCoachRenderer.validateModelResult(context, result)

        assertFalse(validated.success)
        assertEquals("forbidden_claim_detected", validated.errorMessage)
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

    @Test
    fun missingCitedRefsBlockHardCoachingResult() {
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
        )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "positive_reinforcement",
            argsJson = "{}",
            backend = "litert-lm:gpu",
            selectionBasis = "no bounded refs",
            evidenceRefs = emptyList(),
            modelInfoJson = "{}",
            rawResponse = "{}",
            inferenceTimeMs = 10.0,
        )

        val validated = SessionCoachRenderer.validateModelResult(context, result)

        assertFalse(validated.success)
        assertEquals("missing_evidence_refs", validated.errorMessage)
    }

    @Test
    fun refusalCanReturnWithoutHardEvidenceRefs() {
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
        )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "refuse_unsupported_question",
            argsJson = """{"reason":"insufficient_evidence"}""",
            backend = "litert-lm:gpu",
            selectionBasis = "safe refusal",
            evidenceRefs = emptyList(),
            modelInfoJson = "{}",
            rawResponse = "{}",
            inferenceTimeMs = 10.0,
        )

        val validated = SessionCoachRenderer.validateModelResult(context, result)

        assertTrue(validated.success)
        assertEquals("refuse_unsupported_question", validated.functionName)
    }
}
