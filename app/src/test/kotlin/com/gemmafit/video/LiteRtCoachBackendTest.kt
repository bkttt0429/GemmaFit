package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class LiteRtCoachBackendTest {
    @Test
    fun parserAcceptsAllowedToolCall() {
        val result = LiteRtToolCallParser.parse(
            candidates = listOf(
                LiteRtToolCandidate(
                    name = "positive_reinforcement",
                    arguments = mapOf(
                        "pattern" to "squat",
                        "streak" to 30,
                        "selection_basis" to "clean rep",
                        "evidence_refs" to listOf("knee_angle", "hip_angle"),
                        "coach_cue" to "Clean squat rep with coordinated hip and knee motion.",
                        "next_focus" to "Keep the ascent tempo controlled.",
                    ),
                )
            ),
            backend = "litert-lm:gpu",
            modelInfoJson = "{}",
            rawResponse = "raw",
            inferenceTimeMs = 42.0,
        )

        assertTrue(result.success)
        assertEquals("positive_reinforcement", result.functionName)
        assertEquals("litert-lm:gpu", result.backend)
        assertEquals("clean rep", result.selectionBasis)
        assertEquals(listOf("knee_angle", "hip_angle"), result.evidenceRefs)
        val args = JSONObject(result.argsJson)
        assertEquals(
            "Clean squat rep with coordinated hip and knee motion.",
            args.getString("coach_cue"),
        )
        assertEquals("Keep the ascent tempo controlled.", args.getString("next_focus"))
    }

    @Test
    fun parserRejectsMissingToolCall() {
        val result = LiteRtToolCallParser.parse(
            candidates = emptyList(),
            backend = "litert-lm:cpu",
            modelInfoJson = "{}",
            rawResponse = "free text",
            inferenceTimeMs = 13.0,
        )

        assertFalse(result.success)
        assertEquals("litert_lm_no_valid_tool_call", result.errorMessage)
        assertEquals("litert-lm:cpu", result.backend)
    }

    @Test
    fun parserRejectsThoughtLeakBeforeAcceptingToolCall() {
        val result = LiteRtToolCallParser.parse(
            candidates = listOf(
                LiteRtToolCandidate(
                    name = "positive_reinforcement",
                    arguments = mapOf(
                        "selection_basis" to "clean rep",
                        "evidence_refs" to listOf("metric.rep.clean"),
                    ),
                )
            ),
            backend = "litert-lm:gpu",
            modelInfoJson = "{}",
            rawResponse = "<think>private reasoning</think>",
            inferenceTimeMs = 13.0,
        )

        assertFalse(result.success)
        assertEquals("thought_leak_detected", result.errorMessage)
    }

    @Test
    fun parserAcceptsMemoryToolCall() {
        val result = LiteRtToolCallParser.parse(
            candidates = listOf(
                LiteRtToolCandidate(
                    name = "read_memory",
                    arguments = mapOf(
                        "scope" to "TRENDS_7D",
                        "exercise" to "chair_sit_to_stand",
                    ),
                )
            ),
            backend = "litert-lm:gpu",
            modelInfoJson = "{}",
            rawResponse = "raw",
            inferenceTimeMs = 9.0,
        )

        assertTrue(result.success)
        assertEquals("read_memory", result.functionName)
    }

    @Test
    fun parserAcceptsRawJsonToolCall() {
        val result = LiteRtToolCallParser.parseRaw(
            rawResponse = """
                {"function":"positive_reinforcement","args":{"pattern":"squat","streak":30,"evidence_refs":["metric.squat.depth"]}}
            """.trimIndent(),
            backend = "litert-lm:raw:gpu",
            modelInfoJson = "{}",
            inferenceTimeMs = 11.0,
        )

        assertTrue(result.success)
        assertEquals("positive_reinforcement", result.functionName)
        assertEquals("litert-lm:raw:gpu", result.backend)
        assertEquals(listOf("metric.squat.depth"), result.evidenceRefs)
    }

    @Test
    fun constrainedToolExecutionBuildsParseableFunctionCall() {
        val executions = mutableListOf<JSONObject>()
        val tool = GemmaFitLiteRtTools.sessionSummary(executions).single()

        tool.execute(
            """
                {
                  "headline":"Care log",
                  "what_was_completed":"Two sit-to-stand reps were completed.",
                  "observations":"The visible reps stayed controlled.",
                  "not_judged":"This is not a medical assessment.",
                  "next_session_focus":"Keep the chair centered.",
                  "caregiver_note":"Activity log only.",
                  "evidence_refs":["metric.session.total_reps"],
                  "selection_basis":"Session evidence was available."
                }
            """.trimIndent()
        )
        val raw = LiteRtConstrainedToolResult.rawFunctionCallFrom(
            executions = executions,
            toolCalls = JSONArray(),
            fallbackContent = "fallback",
        )
        val parsed = LiteRtToolCallParser.parseRaw(
            rawResponse = raw,
            backend = "litert-lm:isolated:gpu",
            modelInfoJson = "{}",
            inferenceTimeMs = 10.0,
        )

        assertTrue(parsed.success)
        assertEquals("create_care_activity_log", parsed.functionName)
        assertEquals(listOf("metric.session.total_reps"), parsed.evidenceRefs)
    }

    @Test
    fun parserRejectsRawTextWithoutJson() {
        val result = LiteRtToolCallParser.parseRaw(
            rawResponse = "I would encourage the user to keep going.",
            backend = "litert-lm:raw:gpu",
            modelInfoJson = "{}",
            inferenceTimeMs = 11.0,
        )

        assertFalse(result.success)
        assertEquals("litert_lm_json_parse_failed", result.errorMessage)
    }

    @Test
    fun trainingStylePromptIncludesOutputContract() {
        val prompt = LiteRtEvidencePromptRenderer.build(
            context = rapidMovementContext(),
            safety = rapidMovementSafety(),
            reasoningMode = ModelReasoningMode.OFF,
        )

        assertTrue(prompt.contains("<|turn>system"))
        assertTrue(prompt.contains("Tool contract:\nAllowed function names:"))
        assertTrue(prompt.contains("Required output function: warn_rapid_movement"))
        assertTrue(prompt.contains("Required args: joint,velocity,evidence_refs,selection_basis"))
        assertTrue(prompt.contains("E2B evidence packet:\n```json"))
        assertTrue(prompt.contains("\"output_contract\""))
        assertFalse(prompt.contains("\"rules\""))
    }

    @Test
    fun resultGuardRepairsRapidMovementArgs() {
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "warn_rapid_movement",
            argsJson = """{"movement":"squat","reason":"too fast"}""",
            backend = "litert-lm:raw:gpu",
            rawResponse = """{"function":"warn_rapid_movement","args":{"movement":"squat","reason":"too fast"}}""",
            inferenceTimeMs = 12.0,
        )

        val repaired = LiteRtToolResultGuard.validateAndRepair(
            context = rapidMovementContext(),
            safety = rapidMovementSafety(),
            result = result,
        )

        assertTrue(repaired.success)
        assertEquals("warn_rapid_movement", repaired.functionName)
        val args = JSONObject(repaired.argsJson)
        assertEquals("knee", args.getString("joint"))
        assertEquals(238.0, args.getDouble("velocity"), 0.001)
        assertEquals(listOf("metric.squat.knee_velocity"), repaired.evidenceRefs)
    }

    @Test
    fun resultGuardReplacesInvalidCareLogEvidenceRefs() {
        val safety = JSONObject()
            .put("trigger", "SESSION_SUMMARY")
            .put(
                "evidence_ledger",
                JSONArray(
                    listOf(
                        JSONObject().put("id", "metric.session.duration_seconds"),
                        JSONObject().put("id", "metric.session.total_reps"),
                        JSONObject().put("id", "metric.session.view_limited_count"),
                    )
                ),
            )
            .put(
                "output_contract",
                JSONObject()
                    .put("required_function", "create_care_activity_log")
                    .put("required_args", JSONArray(LiteRtOutputContract.requiredArgs("create_care_activity_log"))),
            )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "create_care_activity_log",
            argsJson = """
                {
                  "headline": "Session summary",
                  "what_was_completed": "Completed visible movement.",
                  "observations": "Camera view was limited.",
                  "not_judged": "This is not a medical assessment.",
                  "next_session_focus": "Keep the area clear.",
                  "caregiver_note": "Activity log only.",
                  "evidence_refs": ["metric.session.duration_seconds", "metric.session.avg_form_score", "fake.ref"]
                }
            """.trimIndent(),
            backend = "litert-lm:raw:gpu",
            rawResponse = "{}",
            inferenceTimeMs = 12.0,
        )

        val repaired = LiteRtToolResultGuard.validateAndRepair(
            context = baseContext(),
            safety = safety,
            result = result,
        )

        assertTrue(repaired.success)
        val args = JSONObject(repaired.argsJson)
        assertEquals(
            listOf("metric.session.duration_seconds"),
            jsonArrayStrings(args.getJSONArray("evidence_refs")),
        )
        assertEquals(listOf("metric.session.duration_seconds"), repaired.evidenceRefs)
    }

    @Test
    fun resultGuardFillsCareLogFieldsWhenModelEmitsCompactArgs() {
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "create_care_activity_log",
            argsJson = """
                {
                  "headline": "Supported practice completed",
                  "observations": "The session stayed controlled and easy to follow.",
                  "next_session_focus": "Keep the chair centered in the camera view.",
                  "evidence_refs": ["metric.session.duration_seconds", "metric.session.total_reps"]
                }
            """.trimIndent(),
            backend = "litert-lm:raw:gpu",
            rawResponse = "{}",
            inferenceTimeMs = 12.0,
        )

        val repaired = LiteRtToolResultGuard.validateAndRepair(
            context = baseContext(),
            safety = careLogSafety(),
            result = result,
        )

        assertTrue(repaired.success)
        val args = JSONObject(repaired.argsJson)
        LiteRtOutputContract.requiredArgs("create_care_activity_log").forEach { key ->
            assertTrue("missing care log arg: $key", args.has(key))
        }
        assertEquals("Supported practice completed", args.getString("headline"))
        assertEquals("The session stayed controlled and easy to follow.", args.getString("observations"))
        assertEquals("Keep the chair centered in the camera view.", args.getString("next_session_focus"))
        assertEquals("Structured activity log only; not a health assessment.", args.getString("caregiver_note"))
        assertTrue(args.getString("what_was_completed").contains("chair sit to stand"))
        assertTrue(args.getString("not_judged").contains("fall risk"))
        assertEquals(
            listOf("metric.session.duration_seconds", "metric.session.total_reps"),
            jsonArrayStrings(args.getJSONArray("evidence_refs")),
        )
    }

    @Test
    fun resultGuardPreservesCareLogModelNarrativeFields() {
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "create_care_activity_log",
            argsJson = """
                {
                  "headline": "Three steady reps",
                  "observations": "The second rep was the smoothest visible repetition.",
                  "next_focus": "Pause briefly at the top before sitting.",
                  "evidence_refs": ["metric.session.duration_seconds"]
                }
            """.trimIndent(),
            backend = "litert-lm:raw:gpu",
            rawResponse = "{}",
            inferenceTimeMs = 12.0,
        )

        val repaired = LiteRtToolResultGuard.validateAndRepair(
            context = baseContext(),
            safety = careLogSafety(),
            result = result,
        )

        val args = JSONObject(repaired.argsJson)
        assertEquals("Three steady reps", args.getString("headline"))
        assertEquals("The second rep was the smoothest visible repetition.", args.getString("observations"))
        assertEquals("Pause briefly at the top before sitting.", args.getString("next_session_focus"))
        assertTrue(args.getString("selection_basis").isNotBlank())
    }

    @Test
    fun resultGuardOverridesUnsupportedMedicalClaimToRefusal() {
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "correct_knee_alignment",
            argsJson = """{"knee_alignment":"keep knees in line"}""",
            backend = "litert-lm:raw:gpu",
            rawResponse = """{"function":"correct_knee_alignment","args":{"knee_alignment":"keep knees in line"}}""",
            inferenceTimeMs = 12.0,
        )

        val repaired = LiteRtToolResultGuard.validateAndRepair(
            context = unsupportedMedicalContext(),
            safety = unsupportedMedicalSafety(),
            result = result,
        )

        assertTrue(repaired.success)
        assertEquals("refuse_unsupported_question", repaired.functionName)
        val args = JSONObject(repaired.argsJson)
        assertEquals("fall_risk_prediction", args.getString("reason"))
        assertEquals(4.0, args.getDouble("refusal_level"), 0.001)
        assertEquals(listOf("capability.medical_claim.blocked"), repaired.evidenceRefs)
    }

    @Test
    fun realtimeFastPathHandlesRapidMovementWithoutGeneration() {
        val result = LiteRtRealtimeFastPath.maybeHandle(
            context = rapidMovementContext(),
            safety = rapidMovementSafety(),
            reasoningMode = ModelReasoningMode.OFF,
            modelPath = "/tmp/gemmafit-v5-e2b-evidence-router.litertlm",
        )

        assertTrue(result?.success == true)
        assertEquals("litert-lm:realtime_fast", result?.backend)
        assertEquals("warn_rapid_movement", result?.functionName)
        assertEquals(0.0, result?.inferenceTimeMs ?: -1.0, 0.001)
        val args = JSONObject(result?.argsJson ?: "{}")
        assertEquals("knee", args.getString("joint"))
        assertEquals(238.0, args.getDouble("velocity"), 0.001)
        assertTrue(JSONObject(result?.modelInfoJson ?: "{}").getBoolean("skipped_litert_generation"))
    }

    @Test
    fun realtimeFastPathHandlesUnsupportedRefusalWithoutGeneration() {
        val result = LiteRtRealtimeFastPath.maybeHandle(
            context = unsupportedMedicalContext(),
            safety = unsupportedMedicalSafety(),
            reasoningMode = ModelReasoningMode.OFF,
            modelPath = "/tmp/gemmafit-v5-e2b-evidence-router.litertlm",
        )

        assertTrue(result?.success == true)
        assertEquals("litert-lm:realtime_fast", result?.backend)
        assertEquals("refuse_unsupported_question", result?.functionName)
        val args = JSONObject(result?.argsJson ?: "{}")
        assertEquals("fall_risk_prediction", args.getString("reason"))
        assertEquals(4.0, args.getDouble("refusal_level"), 0.001)
    }

    @Test
    fun realtimeFastPathDoesNotHandleSummaryMode() {
        val result = LiteRtRealtimeFastPath.maybeHandle(
            context = rapidMovementContext(),
            safety = rapidMovementSafety(),
            reasoningMode = ModelReasoningMode.SUMMARY_OPTIONAL,
            modelPath = "/tmp/gemmafit-v5-e2b-evidence-router.litertlm",
        )

        assertNull(result)
    }

    @Test
    fun hardJudgmentMirrorFalseForcesRefusal() {
        val safety = rapidMovementSafety()
            .put(
                "person_tracking_state",
                JSONObject()
                    .put("state", "observed")
                    .put("judgment_allowed", true)
                    .put("hard_judgment_allowed", false),
            )
        val result = LLMBridge.FunctionCallResult(
            success = true,
            functionName = "warn_rapid_movement",
            argsJson = """{"joint":"knee","velocity":238,"evidence_refs":["metric.squat.knee_velocity"]}""",
            backend = "litert-lm:raw:gpu",
            rawResponse = """{"function":"warn_rapid_movement","args":{}}""",
            inferenceTimeMs = 12.0,
        )

        val repaired = LiteRtToolResultGuard.validateAndRepair(
            context = rapidMovementContext(),
            safety = safety,
            result = result,
        )

        assertEquals("refuse_unsupported_question", repaired.functionName)
        val args = JSONObject(repaired.argsJson)
        assertEquals(4.0, args.getDouble("refusal_level"), 0.001)
    }

    @Test
    fun retryPromptKeepsSameEvidencePacketAndJsonOnlyContract() {
        val packet = LiteRtEvidencePromptRenderer.buildEvidencePacket(
            context = rapidMovementContext(),
            safety = rapidMovementSafety(),
            reasoningMode = ModelReasoningMode.OFF,
        )
        val retryPrompt = LiteRtEvidencePromptRenderer.buildRetryPrompt(
            packet = packet,
            firstError = "litert_lm_json_parse_failed",
        )

        assertTrue(retryPrompt.contains("Previous output was rejected"))
        assertTrue(retryPrompt.contains("Retry once using the exact same E2B evidence packet"))
        assertTrue(retryPrompt.contains(packet.toString()))
        assertTrue(retryPrompt.contains("Return exactly one JSON object whose first key is \"function\""))
    }

    @Test
    fun retryPolicyMarksParseFailureAfterRetry() {
        val result = LLMBridge.FunctionCallResult(
            success = false,
            functionName = "",
            argsJson = "{}",
            backend = "litert-lm:raw:gpu",
            rawResponse = "not json",
            inferenceTimeMs = 4.0,
            errorMessage = "litert_lm_json_parse_failed",
        )

        assertTrue(LiteRtRetryPolicy.shouldRetry(result))
        assertEquals(
            "litert_lm_json_parse_failed_after_retry",
            LiteRtRetryPolicy.afterRetryError(result.errorMessage),
        )
    }

    @Test
    fun resolverSelectsFirstExistingLiteRtCandidate() {
        val selected = CoachModelResolver.firstExisting(
            candidates = listOf("missing.litertlm", "models/gemmafit-v2-fc.litertlm"),
            exists = { it.startsWith("models/") },
        )

        assertEquals("models/gemmafit-v2-fc.litertlm", selected)
    }

    @Test
    fun resolverPrefersOfficialE2BByDefault() {
        assertEquals(
            "gemma-4-E2B-it.litertlm",
            CoachModelResolver.liteRtModelPriority().first(),
        )
        assertTrue(CoachModelResolver.liteRtModelPriority().contains("gemmafit-v5-e2b-evidence-router.litertlm"))
    }

    @Test
    fun resolverMovesOfficialAliasToFront() {
        assertEquals(
            listOf("gemma-4-E2B-it.litertlm", "gemma4_2b_v09_obfus_fix_all_modalities_thinking.litertlm"),
            CoachModelResolver.liteRtRequestedModelNames("official"),
        )
        assertEquals(
            "gemma-4-E2B-it.litertlm",
            CoachModelResolver.liteRtModelPriority("official").first(),
        )
    }

    @Test
    fun resolverMovesV5AliasToFront() {
        assertEquals(
            "gemmafit-v5-e2b-evidence-router.litertlm",
            CoachModelResolver.liteRtModelPriority("v5").first(),
        )
    }

    @Test
    fun resolverAcceptsExplicitLiteRtFileNameAsOverride() {
        val customName = "custom-router.litertlm"

        assertEquals(customName, CoachModelResolver.liteRtRequestedModelNames(customName).single())
        assertEquals(customName, CoachModelResolver.liteRtModelPriority(customName).first())
    }

    @Test
    fun resolverReturnsNullWhenNoLiteRtCandidateExists() {
        val selected = CoachModelResolver.firstExisting(
            candidates = listOf("a.litertlm", "b.litertlm"),
            exists = { false },
        )

        assertNull(selected)
    }

    private fun baseContext(): CoachContext {
        return CoachContext(
            exercise = "squat",
            movementPhase = "summary",
            pattern = "squat",
            repCount = 3,
            cleanStreak = 30,
            metrics = mapOf(
                "squat_depth" to 0.82f,
                "tempo" to 2.4f,
            ),
            muscle = MuscleFocusResult(
                primary = listOf("quadriceps", "glutes"),
                secondary = listOf("hamstrings", "core"),
                pattern = "squat",
                confidence = "pose_estimated",
            ),
            warnings = emptyList(),
            qualityFlags = emptyList(),
            notApplicableFlags = emptyList(),
            evidenceCard = EvidenceCard(
                verdict = "OK",
                reason = "Clean movement window.",
                evidence = listOf(EvidenceItem("depth", "0.82")),
                evidenceRefs = listOf("metric.squat.depth"),
                capabilityCanJudge = listOf("squat_depth", "tempo"),
                capabilityCannotJudge = listOf("joint_force", "muscle_activation"),
            ),
        )
    }

    private fun jsonArrayStrings(arr: JSONArray): List<String> {
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun rapidMovementContext(): CoachContext {
        return baseContext().copy(
            cleanStreak = 0,
            metrics = mapOf(
                "squat_depth" to 0.72f,
                "tempo" to 0.8f,
                "knee_peak_velocity_deg_s" to 238f,
            ),
            warnings = listOf(
                SafetyWarning(
                    rule = 6,
                    functionName = "warn_rapid_movement",
                    message = "Peak knee velocity crossed the controlled-tempo gate.",
                    severity = "high",
                    joint = "knee",
                )
            ),
            qualityFlags = listOf(
                QualityFlag(
                    id = "rapid_movement.knee_velocity",
                    evidenceId = "metric.squat.knee_velocity",
                    status = "WARNING",
                    value = 238f,
                    threshold = 180f,
                    evidence = "Peak knee angular velocity was 238 deg/s after smoothing.",
                    reason = "tempo_too_fast_for_safe_coaching",
                    rule = 6,
                    joint = "knee",
                )
            ),
            evidenceCard = EvidenceCard(
                verdict = "WARNING",
                reason = "Knee angular velocity exceeded the controlled-tempo gate.",
                evidence = listOf(EvidenceItem("knee peak velocity", "238 deg/s")),
                evidenceRefs = listOf("metric.squat.knee_velocity"),
                capabilityCanJudge = listOf("tempo", "knee_peak_velocity_deg_s"),
                capabilityCannotJudge = listOf("joint_force", "muscle_activation"),
            ),
        )
    }

    private fun unsupportedMedicalContext(): CoachContext {
        return baseContext().copy(
            exercise = "chair_sit_to_stand",
            pattern = "chair_sit_to_stand",
            evidenceCard = EvidenceCard(
                verdict = "NOT_SUPPORTED",
                reason = "The user asked for a fall-risk or sarcopenia judgment.",
                evidence = listOf(EvidenceItem("unsupported request", "fall risk / sarcopenia judgment")),
                evidenceRefs = listOf("capability.medical_claim.blocked"),
                capabilityCanJudge = listOf("visible_movement_quality", "tempo"),
                capabilityCannotJudge = listOf("fall_risk", "sarcopenia", "medical_diagnosis", "muscle_activation"),
            ),
        )
    }

    private fun rapidMovementSafety(): JSONObject {
        return JSONObject()
            .put("trigger", "DEBUG_LITERT_EFFECT_RAPID_MOVEMENT")
            .put(
                "evidence_dag_compact",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("id", "metric.squat.knee_velocity")
                            .put("metric", "knee_peak_velocity_deg_s")
                            .put("value", 238)
                            .put("status", "WARNING"),
                    )
                ),
            )
    }

    private fun careLogSafety(): JSONObject {
        return JSONObject()
            .put("trigger", "SESSION_SUMMARY")
            .put(
                "session_summary",
                JSONObject()
                    .put("main_exercise", "chair_sit_to_stand")
                    .put("duration_seconds", 96)
                    .put("total_reps", 3)
                    .put("safety_events", 1)
                    .put("view_limited_count", 2),
            )
            .put(
                "evidence_ledger",
                JSONArray(
                    listOf(
                        JSONObject().put("id", "metric.session.duration_seconds"),
                        JSONObject().put("id", "metric.session.total_reps"),
                        JSONObject().put("id", "gate.view_limited.session"),
                    ),
                ),
            )
            .put(
                "capability_contract",
                JSONObject()
                    .put(
                        "cannot_judge",
                        JSONArray(
                            listOf(
                                JSONObject()
                                    .put("metric", "fall_risk_prediction")
                                    .put("reason", "medical_or_force_boundary")
                                    .put("evidence_refs", JSONArray(listOf("gate.view_limited.session"))),
                            ),
                        ),
                    ),
            )
            .put(
                "output_contract",
                JSONObject()
                    .put("required_function", "create_care_activity_log")
                    .put("required_args", JSONArray(LiteRtOutputContract.requiredArgs("create_care_activity_log"))),
            )
    }

    private fun unsupportedMedicalSafety(): JSONObject {
        return JSONObject()
            .put("trigger", "DEBUG_LITERT_EFFECT_UNSUPPORTED_MEDICAL")
            .put("user_question", "Does this mean I have high fall risk or sarcopenia?")
            .put("required_response_policy", "refuse_unsupported_medical_claim_and_offer_pose_based_alternative")
            .put(
                "evidence_dag_compact",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("id", "capability.medical_claim.blocked")
                            .put("metric", "fall_risk_sarcopenia")
                            .put("status", "NOT_SUPPORTED"),
                    )
                ),
            )
    }
}
