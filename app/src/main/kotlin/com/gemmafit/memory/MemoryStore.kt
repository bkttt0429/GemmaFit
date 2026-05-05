package com.gemmafit.memory

/**
 * Persistence interface for evidence-bounded memory.
 *
 * Implementation strategy (see implementation_plan.md §11.6):
 *  - Hot config (`UserProfileMemory`, `CalibrationMemory`) → DataStore Proto
 *  - Append-only log (`SessionSummary`, `EvidenceMemoryEntry`)   → Room SQLite (WAL mode)
 *  - Audit                                                       → JSONL append to audit.log
 *
 * The interface is split from the implementation so tests can swap in
 * an in-memory fake without pulling in androidx.room. The Room/DataStore
 * implementation lives in `MemoryStoreAndroid.kt` (TODO — needs Room
 * + DataStore Gradle dependencies added to app/build.gradle.kts).
 */
interface MemoryStore {

    // ── Hot config ───────────────────────────────────────────────────

    suspend fun loadProfile(): UserProfileMemory
    suspend fun saveProfile(profile: UserProfileMemory)

    suspend fun loadCalibration(exercise: String): CalibrationMemory?
    suspend fun saveCalibration(calibration: CalibrationMemory)

    // ── Append-only log ──────────────────────────────────────────────

    /** Persist a finalized session summary. Idempotent on `sessionId`. */
    suspend fun appendSession(summary: SessionSummary)

    /** Persist evidence cards captured during a session. */
    suspend fun appendEvidence(entries: List<EvidenceMemoryEntry>)

    /** Most recent N session summaries for an exercise, newest first. */
    suspend fun recentSessions(
        exercise: String,
        limit: Int,
        sinceEpochMs: Long? = null,
    ): List<SessionSummary>

    /** Aggregated counts for the trend tool round-trip. */
    suspend fun aggregateTrend(
        exercise: String,
        windowDays: Int,
    ): TrendAggregate

    /** Evidence rows for a single session — caregiver export only. */
    suspend fun evidenceForSession(sessionId: String): List<EvidenceMemoryEntry>

    // ── Audit ────────────────────────────────────────────────────────

    /** Append a single audit entry. Never throws on disk-full — best-effort. */
    suspend fun audit(entry: AuditEntry)

    /** Truncate audit log to retentionDays. Called at app startup. */
    suspend fun truncateAuditOlderThan(retentionDays: Int = 90)

    // ── Memory wipe ──────────────────────────────────────────────────

    /** Implements the Memory & Trends "Clear memory" control. */
    suspend fun clearAllMemory()
}

/**
 * Compact aggregate returned by the `read_memory(scope=TRENDS_*)` tool.
 * Designed to serialize to ~110 tokens of compact JSON.
 */
data class TrendAggregate(
    val exercise: String,
    val windowDays: Int,
    val sessions: Int,
    val totalReps: Int,
    val warningsCount: Int,
    val lowConfidenceCount: Int,
    val notApplicableCount: Int,
    val commonTrendNotes: List<TrendNote>,
)

/**
 * Audit log entry — written for every accepted AND rejected memory
 * write request. The audit log is append-only JSONL.
 */
data class AuditEntry(
    val requestId: String,
    val timestampMs: Long,
    val type: MemoryUpdateType,
    val outcome: ValidationStatus,
    val rejectionReason: String? = null,
    val evidenceIds: List<String> = emptyList(),
    val proposedValuePreview: String,   // truncated to ≤ 200 chars
)
