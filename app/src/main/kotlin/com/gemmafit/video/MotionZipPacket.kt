package com.gemmafit.video

import kotlin.math.max

/**
 * Safety-preserving temporal evidence compression for debug and future model prompts.
 *
 * This is inspired by hybrid long-context compression, but it is an app-side
 * packet format. It does not change Gemma/E2B architecture and does not store
 * raw video, full skeleton streams, force, GRF, EMG, or clinical labels.
 */
data class MotionZipPacket(
    val schemaVersion: String = "motion_zip_v4_v1",
    val windowId: String,
    val trigger: String,
    val slidingWindow: MotionZipSlidingWindow,
    val compressedSparseBlocks: List<MotionZipEventBlock>,
    val heavilyCompressedSummary: MotionZipSummary,
    val activityContext: ActivityContext = ActivityContext.unknown(),
    val safetyPreserved: List<String>,
    val evidenceRefs: List<String>,
    val limits: List<String>,
) {
    fun toDebugMap(): Map<String, Any?> {
        return mapOf(
            "schema_version" to schemaVersion,
            "window_id" to windowId,
            "trigger" to trigger,
            "sliding_window" to slidingWindow.toDebugMap(),
            "compressed_sparse_blocks" to compressedSparseBlocks.map { it.toDebugMap() },
            "heavily_compressed_summary" to heavilyCompressedSummary.toDebugMap(),
            "activity_context" to activityContext.toDebugMap(),
            "safety_preserved" to safetyPreserved,
            "evidence_refs" to evidenceRefs,
            "limits" to limits,
        )
    }
}

data class MotionZipSlidingWindow(
    val lastMs: Long,
    val framesKept: Int,
    val reason: String = "recent_motion_context",
) {
    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "last_ms" to lastMs,
            "frames_kept" to framesKept,
            "reason" to reason,
        )
    }
}

data class MotionZipEventBlock(
    val blockId: String,
    val compressionMode: String = "csa_like_event_block",
    val timeRangeMs: List<Long>,
    val tokens: List<String>,
    val preservedExtrema: Map<String, Any>,
    val eventScore: Float,
    val rulePolicyState: String,
    val abstainReason: String? = null,
    val evidenceRefs: List<String>,
) {
    fun toDebugMap(): Map<String, Any?> {
        return mapOf(
            "block_id" to blockId,
            "compression_mode" to compressionMode,
            "time_range_ms" to timeRangeMs,
            "tokens" to tokens,
            "preserved_extrema" to preservedExtrema,
            "event_score" to eventScore,
            "rule_policy_state" to rulePolicyState,
            "abstain_reason" to abstainReason,
            "evidence_refs" to evidenceRefs,
        )
    }
}

data class MotionZipSummary(
    val completedReps: Int,
    val tempoBand: String,
    val event: String,
    val stabilityEvents: Int,
    val confidenceFloor: Float,
    val outputState: String,
) {
    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "completed_reps" to completedReps,
            "tempo_band" to tempoBand,
            "event" to event,
            "stability_events" to stabilityEvents,
            "confidence_floor" to confidenceFloor,
            "output_state" to outputState,
        )
    }
}

data class MotionZipUiState(
    val enabled: Boolean = false,
    val blockCount: Int = 0,
    val judgeableBlocks: Int = 0,
    val monitorOnlyBlocks: Int = 0,
    val abstainBlocks: Int = 0,
    val latestOutputState: String = "",
    val latestAbstainReason: String = "",
    val source: String = "",
) {
    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "enabled" to enabled,
            "block_count" to blockCount,
            "judgeable_blocks" to judgeableBlocks,
            "monitor_only_blocks" to monitorOnlyBlocks,
            "abstain_blocks" to abstainBlocks,
            "latest_output_state" to latestOutputState,
            "latest_abstain_reason" to latestAbstainReason,
            "source" to source,
        )
    }
}

object MotionZipPacketBuilder {
    private const val MIN_PACKET_CONFIDENCE_FLOOR = 0.55f
    private const val LOW_VISIBILITY_ABSTAIN_REASON = "low_keypoint_visibility"

    private val safetyPreservedFields = listOf(
        "confidence_floor",
        "angle_extrema",
        "velocity_peak",
        "event_boundary",
        "phase_or_primitive_tokens",
        "rule_policy_state",
        "evidence_refs",
        "unsupported_claim_boundaries",
    )

    fun fromRepEvent(
        motionFeatureWindow: MotionFeatureWindow,
        layer2Output: Layer2Output,
        activityContext: ActivityContext = ActivityContext.unknown(),
        recentWindowMs: Long = 1_600L,
        framesKept: Int = 0,
    ): MotionZipPacket {
        val evidenceRefs = (
            motionFeatureWindow.evidenceRefs +
                layer2Output.evidenceRefs
            )
            .filter { it.isNotBlank() }
            .distinct()
        val block = buildEventBlock(
            motionFeatureWindow = motionFeatureWindow,
            layer2Output = layer2Output,
            evidenceRefs = evidenceRefs,
        )
        return MotionZipPacket(
            windowId = motionFeatureWindow.windowId,
            trigger = motionFeatureWindow.trigger,
            slidingWindow = MotionZipSlidingWindow(
                lastMs = recentWindowMs,
                framesKept = framesKept.coerceAtLeast(0),
            ),
            compressedSparseBlocks = listOf(block),
            heavilyCompressedSummary = MotionZipSummary(
                completedReps = layer2Output.repCount,
                tempoBand = motionFeatureWindow.derivedLabels.tempoBand,
                event = layer2Output.event.wireName,
                stabilityEvents = if (layer2Output.isBalanceStabilityEvent()) 1 else 0,
                confidenceFloor = motionFeatureWindow.features.confidenceFloor,
                outputState = effectiveOutputState(motionFeatureWindow, layer2Output),
            ),
            activityContext = activityContext,
            safetyPreserved = safetyPreservedFields,
            evidenceRefs = evidenceRefs,
            limits = (
                motionFeatureWindow.limits +
                    listOf(
                        "single_camera_pose_proxy",
                        "no_joint_force_or_grf",
                        "no_emg_or_muscle_activation",
                        "no_medical_or_fall_risk_claim",
                    )
                ).distinct(),
        )
    }

    fun fromSessionPackets(
        windowId: String,
        packets: List<MotionZipPacket>,
    ): MotionZipPacket? {
        val blocks = packets.flatMap { it.compressedSparseBlocks }
        if (blocks.isEmpty()) return null
        val evidenceRefs = packets.flatMap { it.evidenceRefs }
            .filter { it.isNotBlank() }
            .distinct()
        val limits = packets.flatMap { it.limits }
            .plus(
                listOf(
                    "single_camera_pose_proxy",
                    "no_joint_force_or_grf",
                    "no_emg_or_muscle_activation",
                    "no_medical_or_fall_risk_claim",
                )
            )
            .filter { it.isNotBlank() }
            .distinct()
        val completedReps = packets.maxOfOrNull { it.heavilyCompressedSummary.completedReps } ?: 0
        val confidenceFloor = packets.minOfOrNull { it.heavilyCompressedSummary.confidenceFloor } ?: 0f
        return MotionZipPacket(
            windowId = windowId,
            trigger = "VIDEO_ANALYSIS_SUMMARY",
            slidingWindow = MotionZipSlidingWindow(
                lastMs = packets.maxOfOrNull { it.slidingWindow.lastMs } ?: 0L,
                framesKept = blocks.size,
                reason = "session_sparse_event_blocks",
            ),
            compressedSparseBlocks = blocks,
            heavilyCompressedSummary = MotionZipSummary(
                completedReps = completedReps,
                tempoBand = dominantTempoBand(packets),
                event = "session_summary",
                stabilityEvents = packets.sumOf { it.heavilyCompressedSummary.stabilityEvents },
                confidenceFloor = confidenceFloor,
                outputState = sessionOutputState(blocks),
            ),
            activityContext = ActivityContext.aggregate(packets.map { it.activityContext }),
            safetyPreserved = safetyPreservedFields,
            evidenceRefs = evidenceRefs,
            limits = limits,
        )
    }

    fun statusForPacket(
        packet: MotionZipPacket?,
        source: String,
    ): MotionZipUiState {
        if (packet == null) return MotionZipUiState(source = source)
        val blocks = packet.compressedSparseBlocks
        val latest = blocks.lastOrNull()
        return MotionZipUiState(
            enabled = true,
            blockCount = blocks.size,
            judgeableBlocks = blocks.count { it.rulePolicyState == "judgeable" },
            monitorOnlyBlocks = blocks.count { it.rulePolicyState == "monitor_only" },
            abstainBlocks = blocks.count { it.rulePolicyState == "abstain" },
            latestOutputState = latest?.rulePolicyState ?: packet.heavilyCompressedSummary.outputState,
            latestAbstainReason = latest?.abstainReason.orEmpty(),
            source = source,
        )
    }

    fun toBoundedE2BPrompt(packet: MotionZipPacket): Map<String, Any?> {
        return mapOf(
            "schema_version" to "motion_zip_e2b_prompt_v1",
            "system" to (
                "Return exactly one JSON function call. Use only the provided motion_zip_packet " +
                    "evidence_refs and limits. Do not diagnose, predict fall risk, estimate force, " +
                    "GRF, joint moments, EMG, muscle activation, heart-rate status, ligament load, or clinical status."
                ),
            "allowed_functions" to listOf(
                "create_persona_activity_report",
                "refuse_unsupported_question",
            ),
            "recommended_function" to "create_persona_activity_report",
            "input" to mapOf(
                "trigger" to packet.trigger,
                "motion_zip_packet" to packet.toDebugMap(),
                "capability_contract" to mapOf(
                    "can_judge" to listOf(
                        "pose_visibility_proxy",
                        "motion_velocity_proxy",
                        "angle_extrema_proxy",
                        "monitor_or_abstain_state",
                    ),
                    "cannot_judge" to listOf(
                        "fall_risk_prediction",
                        "force",
                        "grf",
                        "joint_moment",
                        "emg",
                        "muscle_activation",
                        "heart_rate_status",
                        "medical_diagnosis",
                    ),
                ),
            ),
        )
    }

    private fun dominantTempoBand(packets: List<MotionZipPacket>): String {
        return packets
            .groupingBy { it.heavilyCompressedSummary.tempoBand }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            .orEmpty()
            .ifBlank { "unknown" }
    }

    private fun sessionOutputState(blocks: List<MotionZipEventBlock>): String {
        return when {
            blocks.all { it.rulePolicyState == "abstain" } -> "abstain"
            blocks.any { it.rulePolicyState == "judgeable" } -> "judgeable"
            blocks.any { it.rulePolicyState == "monitor_only" } -> "monitor_only"
            else -> "monitor_only"
        }
    }

    private fun buildEventBlock(
        motionFeatureWindow: MotionFeatureWindow,
        layer2Output: Layer2Output,
        evidenceRefs: List<String>,
    ): MotionZipEventBlock {
        val endMs = layer2Output.timestampMs
        val startMs = max(0L, endMs - motionFeatureWindow.windowMs)
        val abstainReason = effectiveAbstainReason(motionFeatureWindow, layer2Output)
        return MotionZipEventBlock(
            blockId = "${motionFeatureWindow.windowId}.block.${layer2Output.event.wireName}",
            timeRangeMs = listOf(startMs, endMs),
            tokens = motionTokens(motionFeatureWindow, layer2Output),
            preservedExtrema = preservedExtrema(motionFeatureWindow),
            eventScore = layer2Output.confidence.coerceIn(0f, 1f),
            rulePolicyState = if (abstainReason != null) "abstain" else layer2Output.rulePolicy.outputState,
            abstainReason = abstainReason,
            evidenceRefs = evidenceRefs,
        )
    }

    private fun effectiveOutputState(
        motionFeatureWindow: MotionFeatureWindow,
        layer2Output: Layer2Output,
    ): String {
        return if (effectiveAbstainReason(motionFeatureWindow, layer2Output) != null) {
            "abstain"
        } else {
            layer2Output.rulePolicy.outputState
        }
    }

    private fun Layer2Output.isBalanceStabilityEvent(): Boolean {
        return event == Layer2Event.STABILITY_MONITOR ||
            event == Layer2Event.BALANCE_HOLD_STARTED ||
            event == Layer2Event.BALANCE_HOLD_COMPLETED ||
            phase == Layer2Phase.BALANCE_UNSTABLE
    }

    private fun effectiveAbstainReason(
        motionFeatureWindow: MotionFeatureWindow,
        layer2Output: Layer2Output,
    ): String? {
        return layer2Output.abstainReason
            ?: if (motionFeatureWindow.features.confidenceFloor < MIN_PACKET_CONFIDENCE_FLOOR) {
                LOW_VISIBILITY_ABSTAIN_REASON
            } else {
                null
            }
    }

    private fun motionTokens(
        motionFeatureWindow: MotionFeatureWindow,
        layer2Output: Layer2Output,
    ): List<String> {
        val tokens = linkedSetOf<String>()
        motionFeatureWindow.derivedLabels.phaseSequenceEstimate
            .filter { it.isNotBlank() && it != "unknown" }
            .forEach { tokens += it }
        if (layer2Output.phase != Layer2Phase.UNKNOWN) {
            tokens += layer2Output.phase.wireName
        }
        if (layer2Output.event != Layer2Event.NONE) {
            tokens += layer2Output.event.wireName
        }
        layer2Output.subActions
            .map { it.label }
            .filter { it.isNotBlank() }
            .forEach { tokens += it }
        return tokens.ifEmpty { listOf("motion_window") }.toList()
    }

    private fun preservedExtrema(window: MotionFeatureWindow): Map<String, Any> {
        return buildMap {
            window.features.kneeAngleMin?.let { put("knee_angle_min", it) }
            window.features.kneeAngleMax?.let { put("knee_angle_max", it) }
            window.features.hipVerticalDisplacement?.let { put("hip_vertical_displacement", it) }
            put("primary_angle_min", window.features.primaryAngleMin)
            put("primary_angle_max", window.features.primaryAngleMax)
            put("range_of_motion_deg", window.features.rangeOfMotionDeg)
            put("peak_velocity_deg_s", window.features.peakVelocityDegS)
            put("velocity_peak", window.features.velocityPeak)
            put("confidence_floor", window.features.confidenceFloor)
            window.features.stabilizationMs?.let { put("stabilization_ms", it) }
        }
    }
}
