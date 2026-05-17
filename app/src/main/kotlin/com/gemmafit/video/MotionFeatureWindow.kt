package com.gemmafit.video

/**
 * Compact event-level motion evidence for model prompts and debug surfaces.
 *
 * This intentionally stores derived features only. It does not contain raw
 * video frames, full skeleton sequences, forces, GRF, EMG, or clinical labels.
 */
data class MotionFeatureWindow(
    val schemaVersion: String = "motion_feature_window_v1",
    val windowId: String,
    val trigger: String,
    val windowMs: Long,
    val exercise: String,
    val source: List<String>,
    val features: MotionFeatureValues,
    val derivedLabels: MotionDerivedLabels,
    val evidenceRefs: List<String>,
    val limits: List<String> = emptyList(),
) {
    fun toDebugMap(): Map<String, Any?> {
        return mapOf(
            "schema_version" to schemaVersion,
            "window_id" to windowId,
            "trigger" to trigger,
            "window_ms" to windowMs,
            "exercise" to exercise,
            "source" to source,
            "features" to features.toDebugMap(),
            "derived_labels" to derivedLabels.toDebugMap(),
            "evidence_refs" to evidenceRefs,
            "limits" to limits,
        )
    }
}

data class MotionFeatureValues(
    val hipVerticalDisplacement: Float? = null,
    val kneeAngleMin: Float? = null,
    val kneeAngleMax: Float? = null,
    val primaryAngleMin: Float,
    val primaryAngleMax: Float,
    val rangeOfMotionDeg: Float,
    val repDurationMs: Long,
    val peakVelocityDegS: Float,
    val velocityPeak: String,
    val stabilizationMs: Long? = null,
    val confidenceFloor: Float,
) {
    fun toDebugMap(): Map<String, Any?> {
        return mapOf(
            "hip_vertical_displacement" to hipVerticalDisplacement,
            "knee_angle_min" to kneeAngleMin,
            "knee_angle_max" to kneeAngleMax,
            "primary_angle_min" to primaryAngleMin,
            "primary_angle_max" to primaryAngleMax,
            "range_of_motion_deg" to rangeOfMotionDeg,
            "rep_duration_ms" to repDurationMs,
            "peak_velocity_deg_s" to peakVelocityDegS,
            "velocity_peak" to velocityPeak,
            "stabilization_ms" to stabilizationMs,
            "confidence_floor" to confidenceFloor,
        )
    }
}

data class MotionDerivedLabels(
    val tempoBand: String,
    val phaseSequenceEstimate: List<String>,
    val repCompleted: Boolean,
    val supportPattern: String = "unknown",
) {
    fun toDebugMap(): Map<String, Any> {
        return mapOf(
            "tempo_band" to tempoBand,
            "phase_sequence_estimate" to phaseSequenceEstimate,
            "rep_completed" to repCompleted,
            "support_pattern" to supportPattern,
        )
    }
}
