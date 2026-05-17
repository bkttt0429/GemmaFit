package com.gemmafit.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measures the session-summary E2B prompt against the LiteRT-LM ctx2048
 * conversion budget. The bundled gemmafit-v5 .litertlm is exported with
 * `cache_length=2048` and `prefill_lengths=[1024]`, so the input prompt
 * must comfortably fit into the prefill graph or generation will fail with
 * `Input token ids are too long`.
 *
 * Char budget below uses a conservative ~4 chars per Gemma token estimate.
 */
class LiteRtSessionSummaryPromptTest {

    /**
     * Budget includes the v2 locale instruction (~30 chars) and locale-pinned
     * one-shot example (~280 chars for zh-TW worst case) at the END of the
     * user turn. ~875 tokens hard ceiling -> ~3500 chars; still well under
     * the LiteRT-LM prefill_lengths=[1024] cap exported with the bundled
     * gemmafit-v5 .litertlm.
     */
    private val maxPromptChars = 3500

    @Test
    fun realisticSeniorChairSquatPromptStaysWithinPrefillBudget() {
        val context = chairSquatContext()
        val safetyJson = SessionCoachRenderer.buildSafetyJson(context)
        val safety = JSONObject(safetyJson)
        val coachContext = SessionCoachRenderer.toCoachContext(context)
        val packet = LiteRtEvidencePromptRenderer.buildEvidencePacket(
            context = coachContext,
            safety = safety,
            reasoningMode = ModelReasoningMode.OFF,
        )
        val prompt = LiteRtEvidencePromptRenderer.buildSessionSummaryPrompt(packet)

        println("session_summary_prompt_chars=${prompt.length}")
        assertTrue(
            "Slim session summary prompt is ${prompt.length} chars; budget is $maxPromptChars",
            prompt.length <= maxPromptChars,
        )
    }

    @Test
    fun promptDeclaresOnlyCreateCareActivityLog() {
        val prompt = renderPrompt(chairSquatContext())
        assertTrue(prompt.contains("create_care_activity_log"))
        // No other tools should be advertised in the slim path.
        listOf(
            "correct_knee_alignment",
            "warn_rapid_movement",
            "positive_reinforcement",
            "create_persona_activity_report",
            "summarize_trend",
        ).forEach { tool ->
            assertFalse("Tool $tool should not appear in slim session summary prompt", prompt.contains(tool))
        }
    }

    @Test
    fun promptKeepsCannotJudgeCategoriesShort() {
        val prompt = renderPrompt(chairSquatContext())
        // Long "required_evidence" arrays from the full capability contract should not leak in.
        assertFalse(prompt.contains("required_evidence"))
        assertFalse(prompt.contains("confidence_ceiling"))
        assertFalse(prompt.contains("motion_feature_window"))
        assertFalse(prompt.contains("evidence_ledger"))
        assertFalse(prompt.contains("schema_version"))
        assertFalse(prompt.contains("care_log_context"))
        assertFalse(prompt.contains("avg_form_score"))
        assertFalse(prompt.contains("low_confidence_count"))
    }

    @Test
    fun promptUsesV2PacketShape() {
        val packet = sessionPromptPacket(renderPrompt(chairSquatContext()))
        assertTrue(packet.has("compressed_session_memory"))
        assertTrue(packet.has("event_index"))
        assertTrue(packet.has("output_contract"))
        assertFalse(packet.has("care_log_context"))
        assertFalse(packet.has("motion_feature_window"))
        assertFalse(packet.has("activity_context"))
    }

    @Test
    fun ambiguousActivityContextSurvivesCompactPrompt() {
        val context = SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 72,
                totalReps = 3,
                avgFormScore = 82f,
                durationSeconds = 24,
                detection = ExerciseDetection(
                    mainExercise = "supported_chair_squat",
                    confidence = 0.72f,
                    detectedExercises = mapOf("supported_chair_squat" to 44, "chair_sit_to_stand" to 28),
                ),
                activityContext = ActivityContext(
                    state = ActivityContextState.AMBIGUOUS,
                    taskLabel = null,
                    confidence = 0.76f,
                    ambiguityNote = "chair_vs_squat_scores_within_margin",
                    evidenceRefs = listOf("activity_context.ambiguous"),
                ),
            ),
            seniorHeroMode = true,
        )

        val packet = sessionPromptPacket(renderPrompt(context, includeNarrativePacket = false))
        val memory = packet.getJSONObject("compressed_session_memory")

        assertFalse(packet.has("activity_context"))
        assertEquals("ambiguous_activity", memory.getString("activity"))
        assertEquals("ambiguous", memory.getString("activity_context_state"))
        assertEquals("chair_vs_squat_scores_within_margin", memory.getString("activity_context_note"))
        assertTrue(packet.getJSONArray("evidence_refs").toString().contains("activity_context.ambiguous"))
    }

    @Test
    fun visualContextIsCompressedIntoSessionMemory() {
        val context = SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 96,
                totalReps = 4,
                avgFormScore = 82f,
                durationSeconds = 32,
                detection = ExerciseDetection(
                    mainExercise = "supported_chair_squat",
                    confidence = 0.9f,
                    detectedExercises = mapOf("supported_chair_squat" to 96),
                ),
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
                    ),
                ),
            ),
            seniorHeroMode = true,
        )

        val packet = sessionPromptPacket(renderPrompt(context, includeNarrativePacket = false))
        val memory = packet.getJSONObject("compressed_session_memory")

        assertFalse(packet.has("visual_context"))
        assertEquals("outdoor", memory.getString("visual_env"))
        assertEquals("chair", memory.getString("visual_support"))
        assertEquals("visible", memory.getString("visual_person"))
        assertEquals(true, memory.getBoolean("visual_overlay_readable"))
        assertEquals(false, memory.getBoolean("visual_limited"))
    }

    @Test
    fun includesRepSummariesWhenNarrativePacketOn() {
        val packet = sessionPromptPacket(renderPrompt(chairSquatContext(), includeNarrativePacket = true))
        assertTrue(packet.has("rep_summaries"))
        assertTrue(packet.has("session_trend"))
        assertTrue(packet.has("quality_cues"))
        assertTrue(packet.has("baseline_comparison"))
        assertTrue(packet.optJSONArray("rep_summaries")!!.length() <= 4)
        val firstRep = packet.optJSONArray("rep_summaries")!!.getJSONObject(0)
        listOf(
            "rep",
            "quality_note",
            "tempo_band",
            "duration_ms",
            "rom_deg",
            "peak_velocity_deg_s",
            "smoothness_proxy",
            "warning_names",
            "evidence_ref",
        ).forEach { key ->
            assertTrue("rep_summaries item should include $key", firstRep.has(key))
        }
        val cues = packet.getJSONObject("quality_cues")
        assertTrue(cues.has("best"))
        assertTrue(cues.has("focus"))
    }

    @Test
    fun omitsRepSummariesWhenNarrativePacketOff() {
        val packet = sessionPromptPacket(renderPrompt(chairSquatContext(), includeNarrativePacket = false))
        assertFalse(packet.has("rep_summaries"))
        assertFalse(packet.has("session_trend"))
        assertFalse(packet.has("quality_cues"))
    }

    @Test
    fun evidenceRefsCapAtFour() {
        val packet = sessionPromptPacket(renderPrompt(worstCaseContext()))
        assertTrue(packet.optJSONArray("evidence_refs")!!.length() <= 4)
    }

    @Test
    fun eventIndexCapAtFour() {
        val packet = sessionPromptPacket(renderPrompt(worstCaseContext()))
        assertTrue(packet.optJSONArray("event_index")!!.length() <= 4)
    }

    @Test
    fun outputContractPinsCareLogFunction() {
        val packet = sessionPromptPacket(renderPrompt(chairSquatContext()))
        assertEquals(
            "create_care_activity_log",
            packet.optJSONObject("output_contract")!!.optString("function"),
        )
        assertEquals(4, packet.optJSONObject("output_contract")!!.optJSONArray("required_args")!!.length())
    }

    /**
     * Mirrors the bloated session that previously produced 3073 tokens (before
     * the slim builder existed): max safety events, full capability matrix,
     * many not-applicable categories, long descriptions. Guards against
     * regressions if anyone re-introduces full-packet rendering on this path.
     */
    @Test
    fun worstCaseSessionStillFitsPrefillBudget() {
        val context = worstCaseContext()
        val prompt = renderPrompt(context)
        println("worst_case_prompt_chars=${prompt.length}")
        assertTrue(
            "Worst-case session summary prompt is ${prompt.length} chars; budget is $maxPromptChars",
            prompt.length <= maxPromptChars,
        )
    }

    private fun worstCaseContext(): SessionCoachContext {
        return SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 540,
                totalReps = 12,
                avgFormScore = 64f,
                durationSeconds = 180,
                detection = ExerciseDetection(
                    mainExercise = "supported_chair_squat",
                    confidence = 0.83f,
                    detectedExercises = mapOf(
                        "supported_chair_squat" to 460,
                        "lunge" to 50,
                        "push_up" to 30,
                    ),
                ),
                safetyEvents = (1..8).map { i ->
                    SafetyEvent(
                        rule = (i % 8) + 1,
                        functionName = listOf(
                            "correct_spinal_alignment",
                            "warn_rapid_movement",
                            "correct_knee_alignment",
                            "warn_com_offset",
                        )[i % 4],
                        description = "Trunk angle drifted forward beyond the supported range during descent and the system flagged it on rep $i.",
                        severity = if (i % 3 == 0) "high" else "medium",
                        joint = listOf("trunk", "knee", "hip", "spine")[i % 4],
                        frameIndex = i * 32,
                    )
                },
                viewLimitedCount = 28,
                lowConfidenceCount = 14,
                notApplicableCounts = mapOf(
                    "knee_valgus_fppa" to 32,
                    "bilateral_asymmetry" to 18,
                    "hip_drop" to 11,
                    "ankle_dorsiflexion" to 9,
                    "shoulder_protraction" to 6,
                    "elbow_lockout" to 4,
                ),
                muscleFocusDistribution = mapOf(
                    "quadriceps" to 240,
                    "gluteus_maximus" to 180,
                    "erector_spinae" to 110,
                    "hamstrings" to 80,
                    "core_stabilizers" to 65,
                ),
                capabilityContract = CapabilityContract(
                    canJudge = (1..6).map { i ->
                        CapabilityJudgment(
                            metric = "judgeable_metric_$i",
                            reason = "supported_strength_template_metric_with_a_reasonably_descriptive_label_$i",
                            confidenceCeiling = 0.85f,
                            requiredEvidence = listOf("reliable_pose", "stable_view", "single_subject"),
                            evidenceRefs = listOf(
                                "metric.supported_chair_squat.judgeable_metric_$i",
                                "gate.warning.judgeable_metric_$i",
                            ),
                        )
                    },
                    cannotJudge = (1..8).map { i ->
                        CapabilityJudgment(
                            metric = "blocked_metric_$i",
                            reason = "outside_app_evidence_boundary_with_descriptive_reason_$i",
                            requiredEvidence = listOf("clinical_assessment", "force_plate_or_emg"),
                            evidenceRefs = listOf("capability.blocked_metric_$i.blocked"),
                        )
                    },
                ),
                evidenceRefs = (1..16).map { "evidence.bloat_test.entry_$it" },
            ),
            seniorHeroMode = true,
        )
    }

    private fun renderPrompt(
        context: SessionCoachContext,
        includeNarrativePacket: Boolean = true,
    ): String {
        val safetyJson = SessionCoachRenderer.buildSafetyJson(context)
        val safety = JSONObject(safetyJson)
        val coachContext = SessionCoachRenderer.toCoachContext(context)
        val packet = LiteRtEvidencePromptRenderer.buildEvidencePacket(
            context = coachContext,
            safety = safety,
            reasoningMode = ModelReasoningMode.OFF,
        )
        return LiteRtEvidencePromptRenderer.buildSessionSummaryPrompt(
            packet = packet,
            includeNarrativePacket = includeNarrativePacket,
        )
    }

    private fun sessionPromptPacket(prompt: String): JSONObject {
        // The prompt contains multiple JSON snippets (few-shot example and the
        // actual evidence packet). org.json.JSONObject doesn't preserve key
        // order, so the packet may not start with "trigger". Instead, scan
        // for every top-level balanced JSON object and return the first one
        // whose parsed root has trigger == SESSION_SUMMARY. Robust to prompt
        // restructuring and to undefined JSONObject serialization order.
        var i = 0
        while (i < prompt.length) {
            if (prompt[i] != '{') { i += 1; continue }
            val parsed = tryParseBalancedObject(prompt, i)
            if (parsed != null) {
                val (json, endExclusive) = parsed
                if (json.optString("trigger") == "SESSION_SUMMARY") return json
                i = endExclusive
            } else {
                i += 1
            }
        }
        throw AssertionError("session summary prompt should contain SESSION_SUMMARY packet")
    }

    private fun tryParseBalancedObject(text: String, start: Int): Pair<JSONObject, Int>? {
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            when {
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth += 1
                !inString && c == '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return runCatching {
                            JSONObject(text.substring(start, i + 1))
                        }.getOrNull()?.let { it to (i + 1) }
                    }
                }
            }
        }
        return null
    }

    private fun chairSquatContext(): SessionCoachContext {
        return SessionCoachRenderer.contextFrom(
            summary = SessionSummary(
                totalFrames = 81,
                totalReps = 4,
                avgFormScore = 78f,
                durationSeconds = 27,
                detection = ExerciseDetection(
                    mainExercise = "supported_chair_squat",
                    confidence = 0.91f,
                    detectedExercises = mapOf("supported_chair_squat" to 81),
                ),
                safetyEvents = listOf(
                    SafetyEvent(
                        rule = 2,
                        functionName = "correct_spinal_alignment",
                        description = "Trunk angle drifted forward beyond the supported range during descent.",
                        severity = "medium",
                        joint = "trunk",
                        frameIndex = 32,
                    ),
                    SafetyEvent(
                        rule = 6,
                        functionName = "warn_rapid_movement",
                        description = "Knee angular velocity briefly exceeded the controlled-tempo gate.",
                        severity = "low",
                        joint = "knee",
                        frameIndex = 58,
                    ),
                ),
                viewLimitedCount = 4,
                lowConfidenceCount = 2,
                notApplicableCounts = mapOf(
                    "knee_valgus_fppa" to 12,
                    "bilateral_asymmetry" to 8,
                ),
                muscleFocusDistribution = mapOf(
                    "quadriceps" to 64,
                    "gluteus_maximus" to 49,
                    "erector_spinae" to 28,
                ),
                repHistory = listOf(
                    RepRecord(
                        repNumber = 1,
                        formQuality = 88f,
                        rangeOfMotionDeg = 72f,
                        hadViolations = false,
                        traceSummary = RepTraceSummary(
                            repNumber = 1,
                            exercise = "supported_chair_squat",
                            tempoSec = 1.8f,
                            romProxyDeg = 72f,
                            peakVelocityDegS = 85f,
                            smoothnessProxy = 0.82f,
                            lateralSwayProxy = 0.04f,
                            pathDeviationFromBaseline = 0.02f,
                            confidenceCoverage = 0.91f,
                        ),
                        warningNames = listOf("correct_spinal_alignment"),
                    ),
                    RepRecord(
                        repNumber = 2,
                        formQuality = 76f,
                        rangeOfMotionDeg = 66f,
                        hadViolations = false,
                        traceSummary = RepTraceSummary(
                            repNumber = 2,
                            exercise = "supported_chair_squat",
                            tempoSec = 2.1f,
                            romProxyDeg = 66f,
                            peakVelocityDegS = 78f,
                            smoothnessProxy = 0.74f,
                            lateralSwayProxy = 0.08f,
                            pathDeviationFromBaseline = 0.03f,
                            confidenceCoverage = 0.88f,
                        ),
                    ),
                    RepRecord(
                        repNumber = 3,
                        formQuality = 69f,
                        rangeOfMotionDeg = 61f,
                        hadViolations = true,
                        traceSummary = RepTraceSummary(
                            repNumber = 3,
                            exercise = "supported_chair_squat",
                            tempoSec = 2.6f,
                            romProxyDeg = 61f,
                            peakVelocityDegS = 96f,
                            smoothnessProxy = 0.65f,
                            lateralSwayProxy = 0.19f,
                            pathDeviationFromBaseline = 0.06f,
                            confidenceCoverage = 0.82f,
                        ),
                    ),
                    RepRecord(
                        repNumber = 4,
                        formQuality = 73f,
                        rangeOfMotionDeg = 64f,
                        hadViolations = false,
                        traceSummary = RepTraceSummary(
                            repNumber = 4,
                            exercise = "supported_chair_squat",
                            tempoSec = 2.4f,
                            romProxyDeg = 64f,
                            peakVelocityDegS = 73f,
                            smoothnessProxy = 0.7f,
                            lateralSwayProxy = 0.1f,
                            pathDeviationFromBaseline = 0.04f,
                            confidenceCoverage = 0.86f,
                        ),
                    ),
                ),
                personalTraceEnvelope = PersonalTraceEnvelope(
                    exercise = "supported_chair_squat",
                    cleanRepCount = 3,
                    avgTempoSec = 2.0f,
                    avgRomProxyDeg = 67f,
                    avgLateralSwayProxy = 0.07f,
                    avgSmoothnessProxy = 0.76f,
                ),
                capabilityContract = CapabilityContract(
                    canJudge = listOf(
                        CapabilityJudgment(
                            metric = "trunk_uprightness",
                            reason = "supported_strength_template_metric",
                            confidenceCeiling = 0.85f,
                            requiredEvidence = listOf("reliable_pose"),
                            evidenceRefs = listOf("metric.supported_chair_squat.trunk_uprightness"),
                        ),
                        CapabilityJudgment(
                            metric = "front_knee_angle",
                            reason = "supported_strength_template_metric",
                            confidenceCeiling = 0.85f,
                            requiredEvidence = listOf("reliable_pose"),
                            evidenceRefs = listOf("metric.supported_chair_squat.front_knee_angle"),
                        ),
                        CapabilityJudgment(
                            metric = "tempo",
                            reason = "supported_strength_template_metric",
                            confidenceCeiling = 0.8f,
                            requiredEvidence = listOf("reliable_pose"),
                            evidenceRefs = listOf("metric.supported_chair_squat.tempo"),
                        ),
                        CapabilityJudgment(
                            metric = "stability",
                            reason = "supported_strength_template_metric",
                            confidenceCeiling = 0.8f,
                            requiredEvidence = listOf("reliable_pose"),
                            evidenceRefs = listOf("metric.supported_chair_squat.stability"),
                        ),
                    ),
                    cannotJudge = listOf(
                        CapabilityJudgment(
                            metric = "fall_risk",
                            reason = "outside_app_evidence_boundary",
                            requiredEvidence = listOf("clinical_assessment"),
                            evidenceRefs = listOf("capability.fall_risk.blocked"),
                        ),
                        CapabilityJudgment(
                            metric = "joint_force",
                            reason = "outside_app_evidence_boundary",
                            requiredEvidence = listOf("force_plate"),
                            evidenceRefs = listOf("capability.joint_force.blocked"),
                        ),
                        CapabilityJudgment(
                            metric = "muscle_activation_percentage",
                            reason = "outside_app_evidence_boundary",
                            requiredEvidence = listOf("emg_sensor"),
                            evidenceRefs = listOf("capability.muscle_activation_percentage.blocked"),
                        ),
                        CapabilityJudgment(
                            metric = "clinical_diagnosis",
                            reason = "outside_app_evidence_boundary",
                            requiredEvidence = listOf("licensed_clinician"),
                            evidenceRefs = listOf("capability.clinical_diagnosis.blocked"),
                        ),
                        CapabilityJudgment(
                            metric = "rehabilitation_progress",
                            reason = "outside_app_evidence_boundary",
                            requiredEvidence = listOf("clinical_assessment"),
                            evidenceRefs = listOf("capability.rehabilitation_progress.blocked"),
                        ),
                        CapabilityJudgment(
                            metric = "heart_rate",
                            reason = "outside_app_evidence_boundary",
                            requiredEvidence = listOf("hr_sensor"),
                            evidenceRefs = listOf("capability.heart_rate.blocked"),
                        ),
                    ),
                ),
                evidenceRefs = listOf(
                    "metric.supported_chair_squat.trunk_uprightness",
                    "metric.supported_chair_squat.front_knee_angle",
                    "metric.supported_chair_squat.tempo",
                    "metric.supported_chair_squat.stability",
                    "gate.warning.correct_spinal_alignment",
                    "gate.warning.warn_rapid_movement",
                    "gate.view_limited.session",
                    "gate.not_applicable.knee_valgus_fppa",
                ),
            ),
            seniorHeroMode = true,
        )
    }
}
