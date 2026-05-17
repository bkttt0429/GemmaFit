package com.gemmafit.video

import com.gemmafit.jni.LLMBridge
import com.gemmafit.memory.RefusalValidator
import org.json.JSONArray
import org.json.JSONObject

object MultimodalResultValidator {
    fun validate(
        packet: MultimodalEvidencePacket,
        result: LLMBridge.FunctionCallResult,
    ): LLMBridge.FunctionCallResult {
        if (!result.success) return result
        if (result.functionName !in allowedFunctions) {
            return result.rejected("function_not_allowed", "Multimodal output used a tool outside the sidecar allowlist.")
        }

        val args = runCatching { JSONObject(result.argsJson) }.getOrElse {
            return result.rejected("invalid_args_json", "Multimodal output args were not valid JSON.")
        }
        val citedRefs = (result.evidenceRefs + jsonStringList(args.optJSONArray("evidence_refs")))
            .filter { it.isNotBlank() }
            .distinct()
        if (citedRefs.isEmpty()) {
            return result.rejected("missing_evidence_refs", "Multimodal output did not cite packet evidence.")
        }
        val allowedRefs = packet.availableEvidenceRefs
        if (allowedRefs.isEmpty() || citedRefs.any { it !in allowedRefs }) {
            return result.rejected("invalid_evidence_refs", "Multimodal output cited evidence outside the packet.")
        }
        if (changesDeterministicVerdict(args, packet.deterministicVerdict)) {
            return result.rejected("deterministic_verdict_changed", "Multimodal output tried to change the app verdict.")
        }
        if (createsNewWarning(args, packet)) {
            return result.rejected("new_warning_not_allowed", "Multimodal output tried to create a warning.")
        }
        if (!isCleanSidecarText(result.argsJson, result.functionName)) {
            return result.rejected("forbidden_claim_detected", "Multimodal output crossed the non-clinical boundary.")
        }
        if (
            packet.panelConfidence == FrameEvidenceSelector.CONFIDENCE_LOW &&
            result.functionName != "refuse_unsupported_multimodal_claim" &&
            !usesLowConfidenceLanguage(result.argsJson)
        ) {
            return result.rejected(
                "low_confidence_requires_uncertainty",
                "Low-confidence panels can only support uncertainty or better-view wording.",
            )
        }
        return if (result.evidenceRefs == citedRefs) result else result.copy(evidenceRefs = citedRefs)
    }

    private fun changesDeterministicVerdict(
        args: JSONObject,
        deterministicVerdict: String,
    ): Boolean {
        if (args.optBoolean("changes_verdict", false) || args.optBoolean("overrides_verdict", false)) {
            return true
        }
        val proposed = listOf("verdict", "deterministic_verdict", "safety_verdict")
            .mapNotNull { key -> args.optString(key).takeIf { it.isNotBlank() } }
        return proposed.any { !it.equals(deterministicVerdict, ignoreCase = true) }
    }

    private fun createsNewWarning(
        args: JSONObject,
        packet: MultimodalEvidencePacket,
    ): Boolean {
        if (
            args.optBoolean("creates_warning", false) ||
            args.optBoolean("new_warning", false) ||
            args.optBoolean("adds_warning", false)
        ) {
            return true
        }
        val knownWarnings = packet.selectedFrames.allSelectedFrames()
            .flatMap { it.warningIds }
            .filter { it.isNotBlank() }
            .toSet()
        val citedWarnings = (
            jsonStringList(args.optJSONArray("warning_ids")) +
                jsonStringList(args.optJSONArray("new_warning_ids"))
            ).filter { it.isNotBlank() }
        if (citedWarnings.isEmpty()) return false
        return knownWarnings.isEmpty() || citedWarnings.any { it !in knownWarnings }
    }

    private fun isCleanSidecarText(argsJson: String, functionName: String): Boolean {
        if (!RefusalValidator.isClean(argsJson)) return false
        val lower = stripSafeUnsupportedPhrases(argsJson.lowercase())
        if (functionName == "refuse_unsupported_multimodal_claim") {
            return true
        }
        return additionalForbiddenTerms.none { lower.contains(it) }
    }

    private fun usesLowConfidenceLanguage(argsJson: String): Boolean {
        val lower = argsJson.lowercase()
        return lowConfidenceTerms.any { lower.contains(it) }
    }

    private fun stripSafeUnsupportedPhrases(payload: String): String {
        return safeUnsupportedPhrases.fold(payload) { acc, phrase -> acc.replace(phrase, "") }
    }

    private fun LLMBridge.FunctionCallResult.rejected(
        error: String,
        basis: String,
    ): LLMBridge.FunctionCallResult {
        return copy(
            success = false,
            errorMessage = error,
            selectionBasis = basis,
        )
    }

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optString(index).takeIf { it.isNotBlank() }
        }
    }

    private val allowedFunctions = setOf(
        "propose_scene_context",
        "answer_evidence_question",
        "create_multimodal_session_summary",
        "refuse_unsupported_multimodal_claim",
    )

    private val additionalForbiddenTerms = listOf(
        "force",
        "load",
        "emg",
        "heart rate",
        "heart-rate",
        "clinical progress",
        "clinical improvement",
        "fall risk",
        "fall-risk",
        "sarcopenia",
    )

    private val safeUnsupportedPhrases = listOf(
        "cannot estimate force",
        "cannot estimate force or load",
        "does not estimate force",
        "does not estimate force or load",
        "not estimate force",
        "not estimate force or load",
        "cannot assess fall risk",
        "does not assess fall risk",
        "not assess fall risk",
        "cannot assess sarcopenia",
        "does not assess sarcopenia",
        "not assess sarcopenia",
        "cannot read heart rate",
        "does not read heart rate",
        "not read heart rate",
    )

    private val lowConfidenceTerms = listOf(
        "uncertain",
        "limited",
        "not clear",
        "can't tell",
        "cannot tell",
        "better view",
        "camera view",
        "view is limited",
        "may",
        "might",
    )
}
