package com.gemmafit.export

import com.gemmafit.memory.AppMode
import com.gemmafit.memory.InMemoryMemoryStore
import com.gemmafit.memory.SessionSummary
import com.gemmafit.memory.TrendNote
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverExportBuilderTest {
    @Test
    fun bundle_contains_disclaimer_text() = runBlocking {
        val bundle = CaregiverExportBuilder(storeWithSession()).build(
            periodStart = "2026-05-01",
            periodEnd = "2026-05-07",
            exercises = listOf("chair_sit_to_stand"),
        )

        assertTrue(bundle.text.contains(CaregiverExportBuilder.DISCLAIMER_TEXT))
        assertTrue(bundle.html.contains("not a medical diagnosis"))
    }

    @Test
    fun mandatory_unsupported_block_present_in_json() = runBlocking {
        val bundle = CaregiverExportBuilder(storeWithSession()).build(
            periodStart = "2026-05-01",
            periodEnd = "2026-05-07",
            exercises = listOf("chair_sit_to_stand"),
        )
        val json = JSONObject(bundle.json)
        val unsupported = json.getJSONArray("unsupported_judgments_acknowledged").toString()

        CaregiverExportBuilder.MANDATORY_UNSUPPORTED.forEach {
            assertTrue(unsupported.contains(it))
        }
        assertTrue(json.getBoolean("no_medical_diagnosis"))
    }

    @Test
    fun support_event_counts_are_exported_without_clinical_claims() = runBlocking {
        val bundle = CaregiverExportBuilder(storeWithSession()).build(
            periodStart = "2026-05-01",
            periodEnd = "2026-05-07",
            exercises = listOf("chair_sit_to_stand"),
        )
        val counts = JSONObject(bundle.json).getJSONObject("support_event_counts")

        assertTrue(counts.getInt("low_confidence_pause") == 1)
        assertTrue(counts.getInt("setup_needed_pause") == 1)
        assertTrue(bundle.text.contains("Support events observed"))
        assertFalse(bundle.text.contains("cognitive decline"))
        assertFalse(bundle.text.contains("dementia score"))
    }

    @Test
    fun html_output_escapes_user_strings() = runBlocking {
        val bundle = CaregiverExportBuilder(storeWithSession()).build(
            periodStart = "<script>",
            periodEnd = "2026-05-07",
            exercises = listOf("chair_sit_to_stand"),
        )

        assertFalse(bundle.html.contains("<script>"))
        assertTrue(bundle.html.contains("&lt;script&gt;"))
    }

    private fun storeWithSession(): InMemoryMemoryStore {
        return InMemoryMemoryStore().apply {
            sessions["s1"] = SessionSummary(
                sessionId = "s1",
                date = "2026-05-05",
                mode = AppMode.SENIOR,
                exercise = "chair_sit_to_stand",
                reps = 10,
                durationSec = 90,
                warningsCount = 0,
                lowConfidenceCount = 1,
                notApplicableCount = 1,
                trendNotes = listOf(TrendNote.TEMPO_STABLE),
                evidenceCardIds = listOf("ev-1"),
            )
        }
    }
}
