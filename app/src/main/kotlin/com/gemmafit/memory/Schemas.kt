package com.gemmafit.memory

import androidx.annotation.Keep

/**
 * Evidence-Bounded Memory schemas.
 *
 * These are the on-device structured records the policy engine reads,
 * writes, and exports. Gemma never mutates these directly — it can only
 * propose changes via [MemoryUpdateRequest] which `MemoryWritePolicy`
 * validates.
 *
 * See implementation_plan.md §11.6.
 */

// ── Enums ────────────────────────────────────────────────────────────

@Keep
enum class AppMode { GENERAL, SENIOR }

@Keep
enum class CueStyle { ENCOURAGING, TERSE, DETAILED }

@Keep
enum class SupportType { CHAIR, WALL, NONE }

@Keep
enum class QualityStatus { OK, MONITOR, WARNING, CRITICAL, NOT_APPLICABLE, LOW_CONFIDENCE, VIEW_LIMITED }

@Keep
enum class TrendNote {
    TEMPO_SLOWING, TEMPO_STABLE, TEMPO_IMPROVING,
    ROM_SHRINKING, ROM_STABLE, ROM_IMPROVING,
    LOW_CONFIDENCE_RISING,
    VIEW_INCONSISTENT,
}

@Keep
enum class MemoryUpdateType { PROFILE, CALIBRATION, TREND_NOTE }

@Keep
enum class ValidationStatus { PENDING, ACCEPTED, REJECTED, NEEDS_REVIEW }

@Keep
enum class MemoryScope {
    PROFILE,
    CALIBRATION,
    TRENDS_7D,
    TRENDS_30D,
    EVIDENCE_FOR_SESSION,
}

// ── Hot config (DataStore Proto) ─────────────────────────────────────

/** Single-user device. Stored as `profile.pb` via DataStore Proto. */
@Keep
data class UserProfileMemory(
    val language: String,                // "zh-TW", "en"
    val voiceSpeed: Float,               // 0.7 .. 1.3
    val fontScale: Float,                // 1.0, 1.5 (Senior default), 2.0
    val assistedMode: Boolean,           // Senior Mode toggle
    val cuePreference: CueStyle,
    val schemaVersion: Int = SCHEMA_V1,
) {
    companion object { const val SCHEMA_V1 = 1 }
}

/** Stored under `calibration/<exercise>.pb`. One file per exercise. */
@Keep
data class CalibrationMemory(
    val exercise: String,
    val baselineRomProxy: Double?,        // null until first calibration captured
    val baselineTempoSec: Double?,
    val cameraSetupHint: CameraSetupHint,
    val supportType: SupportType,
    val capturedAt: Long,                 // epoch ms
    val sessionsSinceCalibration: Int,
    val cleanRepsCollected: Int,          // adaptive recalibration counter
    val lowConfidenceStreak: Int,
)

@Keep
data class CameraSetupHint(
    val distanceBucket: String,           // e.g. "near", "mid", "far"
    val angleBucket: String,              // "frontal", "side", "oblique"
    val lightingBucket: String,           // "dim", "ok", "bright"
)

// ── Append-only log (Room) ───────────────────────────────────────────

@Keep
data class SessionSummary(
    val sessionId: String,                // UUID
    val date: String,                     // ISO yyyy-MM-dd, local timezone
    val mode: AppMode,
    val exercise: String,
    val reps: Int,
    val durationSec: Int,
    val warningsCount: Int,
    val lowConfidenceCount: Int,
    val notApplicableCount: Int,
    val trendNotes: List<TrendNote>,
    val evidenceCardIds: List<String>,
)

@Keep
data class EvidenceMemoryEntry(
    val evidenceCardId: String,
    val sessionId: String,
    val exercise: String,
    val status: QualityStatus,
    val metricId: String,                 // e.g. "sit_to_stand_tempo"
    val value: Double,
    val confidence: Double,
    val unsupportedJudgments: List<String>,  // always populated for Senior Mode
    val createdAt: Long,
)

// ── LLM-facing structures ────────────────────────────────────────────

/**
 * Gemma proposes a memory write via this structure. The policy engine
 * validates before the actual store sees it.
 *
 * `proposedValue` is a structured payload, never freeform prose. Prose
 * fields are limited to closed enums or ≤ 80-char strings to keep
 * refusal-regex tractable.
 */
@Keep
data class MemoryUpdateRequest(
    val requestId: String,                // idempotency key (Gemma-supplied UUID)
    val type: MemoryUpdateType,
    val proposedValue: Map<String, Any?>, // serialized JSON object, schema-checked
    val evidenceIds: List<String>,        // ≥ 1 required for TREND_NOTE
    val confidence: Double,
    var appValidationStatus: ValidationStatus = ValidationStatus.PENDING,
    var rejectionReason: String? = null,
)

// ── Caregiver export ─────────────────────────────────────────────────

@Keep
data class CaregiverSummary(
    val periodStart: String,                       // ISO date
    val periodEnd: String,
    val sessionsCompleted: Int,
    val commonCameraLimitations: List<String>,    // e.g. ["frontal_view_only"]
    val commonCues: List<String>,                  // top training cues
    val unsupportedJudgmentsAcknowledged: List<String>,  // mandatory
    val noMedicalDiagnosis: Boolean = true,        // sentinel — never set false
)
