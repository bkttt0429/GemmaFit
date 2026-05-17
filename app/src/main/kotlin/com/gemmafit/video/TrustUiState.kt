package com.gemmafit.video

import org.json.JSONObject
import java.io.File

enum class ModelReadinessStatus {
    LOCAL_GEMMA_READY,
    TEMPLATE_FALLBACK,
    MODEL_MISSING,
}

data class ModelReadinessSnapshot(
    val status: ModelReadinessStatus,
    val label: String,
    val modelFileName: String = "",
    val modelSizeBytes: Long = 0L,
    val backend: String = "fallback",
    val fallbackReason: String = "",
) {
    companion object {
        fun from(
            liteRtModelPath: String?,
            backend: String,
            fallback: Boolean,
            fallbackReason: String = "",
        ): ModelReadinessSnapshot {
            val file = liteRtModelPath?.let(::File)
            if (file == null || !file.exists() || file.length() <= 0L) {
                return ModelReadinessSnapshot(
                    status = ModelReadinessStatus.MODEL_MISSING,
                    label = "Model missing",
                    backend = backend.ifBlank { "fallback" },
                    fallbackReason = fallbackReason.ifBlank { "litert_model_file_not_found" },
                )
            }
            if (fallback || backend == "fallback") {
                return ModelReadinessSnapshot(
                    status = ModelReadinessStatus.TEMPLATE_FALLBACK,
                    label = "Template fallback",
                    modelFileName = file.name,
                    modelSizeBytes = file.length(),
                    backend = backend.ifBlank { "fallback" },
                    fallbackReason = fallbackReason.ifBlank { "local model did not produce a valid tool call" },
                )
            }
            return ModelReadinessSnapshot(
                status = ModelReadinessStatus.LOCAL_GEMMA_READY,
                label = "Local Gemma ready",
                modelFileName = file.name,
                modelSizeBytes = file.length(),
                backend = backend.ifBlank { "litert-lm" },
                fallbackReason = "",
            )
        }
    }
}

enum class TrustSourceKind {
    POSE_RULES,
    LOCAL_GEMMA,
    TEMPLATE_FALLBACK,
    ABSTAINED,
}

data class TrustSourceBadge(
    val kind: TrustSourceKind,
    val label: String,
)

enum class CoachBoundaryKind {
    NONE,
    EVIDENCE_BACKED,
    MONITOR_ONLY,
    REFUSED,
    FALLBACK,
}

data class CoachBoundaryState(
    val kind: CoachBoundaryKind = CoachBoundaryKind.NONE,
    val title: String = "",
    val summary: String = "",
    val detail: String = "",
    val evidenceRefs: List<String> = emptyList(),
    val sourceLabel: String = "",
) {
    val isActive: Boolean get() = kind != CoachBoundaryKind.NONE
}

object TrustUiMapper {
    fun sourceBadge(
        backend: String,
        fallback: Boolean,
        qualityFlags: List<QualityFlag> = emptyList(),
    ): TrustSourceBadge {
        if (qualityFlags.any { it.status in abstainStatuses }) {
            return TrustSourceBadge(TrustSourceKind.ABSTAINED, "Abstained")
        }
        if (fallback || backend == "fallback" || backend.contains("fallback", ignoreCase = true)) {
            return TrustSourceBadge(TrustSourceKind.TEMPLATE_FALLBACK, "Template fallback")
        }
        if (
            backend.startsWith("litert-lm") ||
            backend.startsWith("llama.cpp") ||
            backend.contains("local", ignoreCase = true)
        ) {
            return TrustSourceBadge(TrustSourceKind.LOCAL_GEMMA, "Local Gemma")
        }
        return TrustSourceBadge(TrustSourceKind.POSE_RULES, "Pose rules")
    }

    fun sourceBadge(sourceLabel: String): TrustSourceBadge {
        val normalized = sourceLabel.lowercase()
        return when {
            normalized.contains("abstain") -> TrustSourceBadge(TrustSourceKind.ABSTAINED, "Abstained")
            normalized.contains("fallback") -> TrustSourceBadge(TrustSourceKind.TEMPLATE_FALLBACK, "Template fallback")
            normalized.contains("litert") || normalized.contains("gemma") -> TrustSourceBadge(TrustSourceKind.LOCAL_GEMMA, "Local Gemma")
            else -> TrustSourceBadge(TrustSourceKind.POSE_RULES, "Pose rules")
        }
    }

    fun whyNotJudgedSummary(
        card: EvidenceCard,
        qualityFlags: List<QualityFlag>,
    ): String {
        return when {
            qualityFlags.any { it.status == "LOW_CONFIDENCE" } -> "Pose confidence too low"
            qualityFlags.any { it.status == "VIEW_LIMITED" } -> "Camera view limited"
            qualityFlags.any { it.status == "NOT_APPLICABLE" } -> "Rule not applicable to this phase"
            card.unsupportedJudgments.isNotEmpty() -> "Medical/force claim blocked"
            else -> card.modelBoundary
        }
    }

    fun coachBoundaryState(live: LiveWorkoutState): CoachBoundaryState {
        val source = sourceBadge(
            backend = live.coachInsight.backend,
            fallback = live.coachInsight.fallback,
            qualityFlags = live.qualityFlags,
        ).label
        val refs = (live.coachInsight.evidenceRefs + live.evidenceCard.evidenceRefs)
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        val reviewReason = live.reviewFrameStatus.noPoseReason.ifBlank { live.evidenceCard.reason }
        return when {
            live.coachInsight.functionName == "refuse_unsupported_question" -> {
                val args = parseArgs(live.coachInsight.argsJson)
                val reason = args.optString("reason")
                    .ifBlank { live.coachInsight.selectionBasis }
                    .ifBlank { "unsupported_question" }
                val alternative = args.optString("safe_alternative")
                    .ifBlank { "Use visible pose evidence only." }
                CoachBoundaryState(
                    kind = CoachBoundaryKind.REFUSED,
                    title = "Refused unsupported claim",
                    summary = friendlyReason(reason),
                    detail = alternative,
                    evidenceRefs = refs,
                    sourceLabel = source,
                )
            }
            live.reviewFrameStatus.poseHiddenByQuality -> {
                CoachBoundaryState(
                    kind = CoachBoundaryKind.MONITOR_ONLY,
                    title = "Pose preview only",
                    summary = "Hard judgment blocked: ${friendlyReason(reviewReason)}",
                    detail = "The skeleton can stay visible for review, but coaching stays monitor-only until keypoints are reliable.",
                    evidenceRefs = refs,
                    sourceLabel = source,
                )
            }
            live.qualityFlags.any { it.status == "LOW_CONFIDENCE" } -> {
                CoachBoundaryState(
                    kind = CoachBoundaryKind.MONITOR_ONLY,
                    title = "Monitor only",
                    summary = "Hard judgment blocked: pose tracking unstable",
                    detail = "Pose confidence was below the threshold, so the app should observe or ask for a clearer view.",
                    evidenceRefs = refs,
                    sourceLabel = source,
                )
            }
            live.qualityFlags.any { it.status == "VIEW_LIMITED" } -> {
                CoachBoundaryState(
                    kind = CoachBoundaryKind.MONITOR_ONLY,
                    title = "Camera-limited",
                    summary = "Hard judgment blocked: camera view limited",
                    detail = "The visible angle or crop is not enough for a confident movement-quality call.",
                    evidenceRefs = refs,
                    sourceLabel = source,
                )
            }
            live.qualityFlags.any { it.status == "NOT_APPLICABLE" } -> {
                CoachBoundaryState(
                    kind = CoachBoundaryKind.MONITOR_ONLY,
                    title = "Rule skipped",
                    summary = "Judgment skipped: rule not applicable",
                    detail = "This rule does not apply to the current movement, phase, or view.",
                    evidenceRefs = refs,
                    sourceLabel = source,
                )
            }
            live.coachInsight.fallback && live.coachInsight.selectionBasis.isNotBlank() -> {
                CoachBoundaryState(
                    kind = CoachBoundaryKind.FALLBACK,
                    title = "Template fallback",
                    summary = friendlyReason(live.coachInsight.selectionBasis),
                    detail = "The UI kept a deterministic cue because the local model did not return a validated tool call.",
                    evidenceRefs = refs,
                    sourceLabel = source,
                )
            }
            refs.isNotEmpty() -> {
                CoachBoundaryState(
                    kind = CoachBoundaryKind.EVIDENCE_BACKED,
                    title = "Evidence-backed",
                    summary = "Based on ${refs.size} bounded evidence ref${if (refs.size == 1) "" else "s"}",
                    detail = live.coachInsight.selectionBasis.ifBlank { live.evidenceCard.reason },
                    evidenceRefs = refs,
                    sourceLabel = source,
                )
            }
            else -> CoachBoundaryState()
        }
    }

    private fun parseArgs(argsJson: String): JSONObject {
        return runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
    }

    private fun friendlyReason(reason: String): String {
        val normalized = reason
            .replace("_", " ")
            .replace("-", " ")
            .trim()
        return when {
            normalized.isBlank() -> "bounded evidence required"
            normalized.contains("json parse failed", ignoreCase = true) -> "model output was not valid JSON"
            normalized.contains("thought leak", ignoreCase = true) -> "model returned reasoning text"
            normalized.contains("evidence refs", ignoreCase = true) -> "missing bounded evidence refs"
            normalized.contains("visibility", ignoreCase = true) -> "keypoints are not reliable enough"
            normalized.contains("confidence", ignoreCase = true) -> "pose confidence is too low"
            normalized.contains("view limited", ignoreCase = true) -> "camera view is limited"
            normalized.contains("capability contract", ignoreCase = true) -> "outside the supported judgment contract"
            normalized.contains("unsupported", ignoreCase = true) -> "outside supported movement feedback"
            else -> normalized
        }
    }

    private val abstainStatuses = setOf("LOW_CONFIDENCE", "VIEW_LIMITED", "NOT_APPLICABLE")
}
