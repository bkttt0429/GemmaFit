package com.gemmafit.video

import org.json.JSONArray
import org.json.JSONObject

data class MultimodalEvidencePacket(
    val schemaVersion: String = SCHEMA_VERSION,
    val trigger: ModelInvocationTrigger,
    val selectedFrames: SelectedEvidenceFrames,
    val deterministicVerdict: String,
    val deterministicReason: String,
    val evidenceRefs: List<String>,
    val tags: Map<String, String> = emptyMap(),
    val limits: List<String> = DEFAULT_LIMITS,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val panelConfidence: String
        get() = selectedFrames.panelConfidence

    val availableEvidenceRefs: Set<String>
        get() = (
            evidenceRefs +
                selectedFrames.allSelectedFrames().flatMap { it.evidenceRefs } +
                selectedFrames.allSelectedFrames().flatMap { candidate ->
                    candidate.warningIds.map { "warning.$it" }
                }
            )
            .filter { it.isNotBlank() }
            .toSet()

    fun toJson(): JSONObject {
        return JSONObject()
            .put("schema_version", schemaVersion)
            .put("trigger", trigger.name)
            .put("deterministic_verdict", deterministicVerdict)
            .put("deterministic_reason", deterministicReason)
            .put("panel_confidence", panelConfidence)
            .put("selected_frames", selectedFrames.toJson())
            .put("evidence_refs", JSONArray(evidenceRefs))
            .put("available_evidence_refs", JSONArray(availableEvidenceRefs.toList()))
            .put("tags", JSONObject(tags))
            .put("limits", JSONArray(limits))
            .put("created_at_ms", createdAtMs)
    }

    companion object {
        const val SCHEMA_VERSION = "multimodal_evidence_packet_v1"
        val DEFAULT_LIMITS = listOf(
            "sidecar_not_live_safety_path",
            "no_raw_video_storage",
            "no_full_frame_history_storage",
            "no_full_landmark_stream_storage",
            "no_medical_or_clinical_claims",
            "no_force_load_emg_or_heart_rate_claims",
            "deterministic_verdict_cannot_be_changed",
        )
    }
}

enum class MultimodalEvidenceAction {
    DISABLED,
    SKIP,
    BUILD_PANEL_ONLY,
    CALL_BACKEND,
}

data class MultimodalEvidencePlan(
    val action: MultimodalEvidenceAction,
    val buildPanel: Boolean,
    val callBackend: Boolean,
    val reason: String,
) {
    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "action" to action.name,
            "build_panel" to buildPanel,
            "call_backend" to callBackend,
            "reason" to reason,
        )
    }

    companion object {
        fun disabled(reason: String = "multimodal_sidecar_feature_disabled"): MultimodalEvidencePlan {
            return MultimodalEvidencePlan(
                action = MultimodalEvidenceAction.DISABLED,
                buildPanel = false,
                callBackend = false,
                reason = reason,
            )
        }

        fun skipped(reason: String): MultimodalEvidencePlan {
            return MultimodalEvidencePlan(
                action = MultimodalEvidenceAction.SKIP,
                buildPanel = false,
                callBackend = false,
                reason = reason,
            )
        }

        fun panelOnly(reason: String): MultimodalEvidencePlan {
            return MultimodalEvidencePlan(
                action = MultimodalEvidenceAction.BUILD_PANEL_ONLY,
                buildPanel = true,
                callBackend = false,
                reason = reason,
            )
        }

        fun callBackend(reason: String): MultimodalEvidencePlan {
            return MultimodalEvidencePlan(
                action = MultimodalEvidenceAction.CALL_BACKEND,
                buildPanel = true,
                callBackend = true,
                reason = reason,
            )
        }
    }
}

object MultimodalEvidenceTriggerPolicy {
    fun plan(request: ModelInvocationRequest): MultimodalEvidencePlan {
        if (!request.multimodalEvidencePanelEnabled) {
            return MultimodalEvidencePlan.disabled()
        }
        if (request.deviceState.lowBattery || request.deviceState.highThermalLoad || request.deviceState.modelDisabled) {
            return MultimodalEvidencePlan.skipped("device_budget_or_model_disabled")
        }
        return when (request.trigger) {
            ModelInvocationTrigger.LIVE_FRAME -> MultimodalEvidencePlan.skipped("live_frame_never_uses_multimodal")
            ModelInvocationTrigger.SETUP_CHECK -> MultimodalEvidencePlan.skipped(
                "setup_check_not_multimodal_backend_trigger"
            )
            ModelInvocationTrigger.REP_COMPLETED -> {
                if (request.debugRepMultimodalPanel) {
                    MultimodalEvidencePlan.panelOnly("rep_completed_debug_panel_only")
                } else {
                    MultimodalEvidencePlan.skipped("rep_completed_v1_debug_only")
                }
            }
            ModelInvocationTrigger.WARNING_PERSISTED -> {
                if (!request.hasCriticalOrWarningEvidence || !request.needsLanguageExplanation) {
                    MultimodalEvidencePlan.skipped("warning_does_not_need_multimodal_explanation")
                } else if (request.multimodalBackendAvailable) {
                    MultimodalEvidencePlan.callBackend("warning_persisted_optional_sidecar")
                } else {
                    MultimodalEvidencePlan.panelOnly("warning_persisted_panel_fallback")
                }
            }
            ModelInvocationTrigger.USER_QUESTION -> {
                if (request.multimodalBackendAvailable) {
                    MultimodalEvidencePlan.callBackend("user_question_scene_context")
                } else {
                    MultimodalEvidencePlan.panelOnly("user_question_panel_fallback")
                }
            }
            ModelInvocationTrigger.SESSION_ENDED -> {
                if (request.multimodalBackendAvailable) {
                    MultimodalEvidencePlan.callBackend("session_ended_multimodal_summary")
                } else {
                    MultimodalEvidencePlan.panelOnly("session_ended_panel_fallback")
                }
            }
            ModelInvocationTrigger.CAREGIVER_EXPORT -> {
                if (request.multimodalBackendAvailable) {
                    MultimodalEvidencePlan.callBackend("caregiver_export_multimodal_summary")
                } else {
                    MultimodalEvidencePlan.panelOnly("caregiver_export_panel_fallback")
                }
            }
            ModelInvocationTrigger.IMPROVEMENT_AFTER_WARNING,
            ModelInvocationTrigger.SESSION_MICRO_SUMMARY,
            ModelInvocationTrigger.SUBJECT_LOST,
            ModelInvocationTrigger.USER_LEFT_ACTIVITY_AREA,
            ModelInvocationTrigger.NO_RESPONSE_AFTER_CUE,
            ModelInvocationTrigger.MULTI_PERSON_AMBIGUOUS,
            -> MultimodalEvidencePlan.skipped("trigger_not_allowed_for_multimodal")
        }
    }
}

private fun SelectedEvidenceFrames.toJson(): JSONObject {
    return JSONObject()
        .put("scene_anchor", sceneAnchor?.toJson() ?: JSONObject.NULL)
        .put("top", top?.toJson() ?: JSONObject.NULL)
        .put("descent", descent?.toJson() ?: JSONObject.NULL)
        .put("bottom", bottom?.toJson() ?: JSONObject.NULL)
        .put("ascent", ascent?.toJson() ?: JSONObject.NULL)
        .put("warning_frame", warningFrame?.toJson() ?: JSONObject.NULL)
        .put("panel_confidence", panelConfidence)
        .put("selection_basis", JSONArray(selectionBasis))
}

private fun FrameEvidenceCandidate.toJson(): JSONObject {
    return JSONObject()
        .put("frame_index", frameIndex)
        .put("timestamp_ms", timestampMs)
        .put("phase", phase)
        .put("pose_confidence", poseConfidence.toDouble())
        .put("full_body_visibility", fullBodyVisibility.toDouble())
        .put("subject_observed", subjectObserved)
        .put("subject_stable", subjectStable)
        .put("hip_y", hipY?.toDouble() ?: JSONObject.NULL)
        .put("hip_velocity_y", hipVelocityY?.toDouble() ?: JSONObject.NULL)
        .put("blur_score", blurScore?.toDouble() ?: JSONObject.NULL)
        .put("has_warning", hasWarning)
        .put("warning_ids", JSONArray(warningIds))
        .put("evidence_refs", JSONArray(evidenceRefs))
}
