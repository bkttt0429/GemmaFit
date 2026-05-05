package com.gemmafit.memory

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryAwarePromptBuilderTest {
    @Test
    fun prewarm_avoids_store_calls_for_subsequent_reads() = runBlocking {
        val store = storeWithData()
        val builder = MemoryAwarePromptBuilder(store)

        builder.preWarm("chair_sit_to_stand")
        builder.handleReadMemory(MemoryScope.PROFILE)
        builder.handleReadMemory(MemoryScope.CALIBRATION)
        builder.handleReadMemory(MemoryScope.TRENDS_7D)

        assertEquals(1, store.loadProfileCalls)
        assertEquals(1, store.loadCalibrationCalls)
        assertEquals(1, store.aggregateTrendCalls)
    }

    @Test
    fun compact_json_keys_match_schema() = runBlocking {
        val builder = MemoryAwarePromptBuilder(storeWithData())

        val json = JSONObject(builder.handleReadMemory(MemoryScope.TRENDS_7D, "chair_sit_to_stand"))

        assertEquals("chair_sit_to_stand", json.getString("ex"))
        assertEquals(7, json.getInt("win"))
        assertTrue(json.has("reps"))
        assertTrue(json.has("warn"))
        assertTrue(json.has("lowc"))
        assertTrue(json.has("na"))
        assertTrue(json.has("notes"))
    }

    @Test
    fun evidence_for_session_scope_throws_outside_caregiver_flow() = runBlocking {
        val builder = MemoryAwarePromptBuilder(storeWithData())

        try {
            builder.handleReadMemory(MemoryScope.EVIDENCE_FOR_SESSION)
            fail("Expected EVIDENCE_FOR_SESSION to be blocked outside caregiver flow.")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("caregiver-only"))
        }
    }

    private fun storeWithData(): InMemoryMemoryStore {
        return InMemoryMemoryStore().apply {
            calibrations["chair_sit_to_stand"] = CalibrationMemory(
                exercise = "chair_sit_to_stand",
                baselineRomProxy = 0.7,
                baselineTempoSec = 2.5,
                cameraSetupHint = CameraSetupHint("mid", "frontal", "ok"),
                supportType = SupportType.CHAIR,
                capturedAt = 1L,
                sessionsSinceCalibration = 2,
                cleanRepsCollected = 18,
                lowConfidenceStreak = 0,
            )
            sessions["s1"] = SessionSummary(
                sessionId = "s1",
                date = "2026-05-05",
                mode = AppMode.SENIOR,
                exercise = "chair_sit_to_stand",
                reps = 8,
                durationSec = 60,
                warningsCount = 1,
                lowConfidenceCount = 0,
                notApplicableCount = 2,
                trendNotes = listOf(TrendNote.TEMPO_STABLE),
                evidenceCardIds = listOf("ev-1"),
            )
        }
    }
}
