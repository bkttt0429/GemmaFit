package com.gemmafit.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON payload returned by `read_memory(scope, exercise?)`
 * tool calls and pre-warms the cache when an exercise starts.
 *
 * Tool round-trip pattern (implementation_plan.md §11.6):
 *   1. App pre-warms profile + calibration + trends_7d in memory when
 *      the user opens an exercise.
 *   2. Per-frame coaching prompts contain only the StructuredMotionReport
 *      — no memory.
 *   3. When the user asks a question (or Gemma decides it needs
 *      context), Gemma emits read_memory(scope=...). The app returns
 *      the cached slice; no SQLite query.
 *
 * Output JSON uses compact short keys to fit tight token budgets.
 */
class MemoryAwarePromptBuilder(
    private val store: MemoryStore,
) {

    /** In-memory cache populated by [preWarm]. */
    private var profileCache:     UserProfileMemory? = null
    private var calibrationCache: CalibrationMemory? = null
    private var trends7dCache:    TrendAggregate?    = null
    private var cachedExercise:   String?            = null

    /**
     * Called when the user opens an exercise. Hits the store once,
     * then later `read_memory` calls are O(1) lookups.
     */
    suspend fun preWarm(exercise: String) {
        profileCache     = store.loadProfile()
        calibrationCache = store.loadCalibration(exercise)
        trends7dCache    = store.aggregateTrend(exercise, windowDays = 7)
        cachedExercise   = exercise
    }

    /** Drop the cache (e.g. after `clearAllMemory`). */
    fun invalidate() {
        profileCache = null
        calibrationCache = null
        trends7dCache = null
        cachedExercise = null
    }

    /**
     * Handle a tool call from Gemma. Returns the JSON string the
     * model receives in the tool-result turn.
     */
    suspend fun handleReadMemory(
        scope: MemoryScope,
        exercise: String? = cachedExercise,
    ): String {
        return when (scope) {
            MemoryScope.PROFILE              -> profileJson()
            MemoryScope.CALIBRATION          -> calibrationJson(exercise)
            MemoryScope.TRENDS_7D            -> trendsJson(exercise, windowDays = 7)
            MemoryScope.TRENDS_30D           -> trendsJson(exercise, windowDays = 30)
            MemoryScope.EVIDENCE_FOR_SESSION -> error("EVIDENCE_FOR_SESSION is caregiver-only")
        }
    }

    // ── JSON shaping (compact) ───────────────────────────────────────

    private suspend fun profileJson(): String {
        val p = profileCache ?: store.loadProfile().also { profileCache = it }
        return JSONObject(mapOf(
            "lang"   to p.language,
            "vs"     to p.voiceSpeed,
            "fs"     to p.fontScale,
            "senior" to p.assistedMode,
            "cue"    to p.cuePreference.name,
        )).toString()
    }

    private suspend fun calibrationJson(exercise: String?): String {
        if (exercise == null) return "{}"
        val c = if (exercise == cachedExercise) calibrationCache
            else store.loadCalibration(exercise)
        c ?: return JSONObject(mapOf("ex" to exercise, "calibrated" to false)).toString()
        return JSONObject(mapOf(
            "ex"      to c.exercise,
            "rom"     to c.baselineRomProxy,
            "tempo"   to c.baselineTempoSec,
            "support" to c.supportType.name,
            "view"    to c.cameraSetupHint.angleBucket,
            "since"   to c.sessionsSinceCalibration,
        )).toString()
    }

    private suspend fun trendsJson(exercise: String?, windowDays: Int): String {
        if (exercise == null) return "{}"
        val t = if (windowDays == 7 && exercise == cachedExercise) trends7dCache
            else store.aggregateTrend(exercise, windowDays)
        t ?: return JSONObject(mapOf("ex" to exercise, "n" to 0)).toString()
        return JSONObject(mapOf(
            "ex"     to t.exercise,
            "win"    to t.windowDays,
            "n"      to t.sessions,
            "reps"   to t.totalReps,
            "warn"   to t.warningsCount,
            "lowc"   to t.lowConfidenceCount,
            "na"     to t.notApplicableCount,
            "notes"  to JSONArray(t.commonTrendNotes.map { it.name }),
        )).toString()
    }
}
