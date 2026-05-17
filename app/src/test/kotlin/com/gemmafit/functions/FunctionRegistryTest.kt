package com.gemmafit.functions

import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FunctionRegistryTest {
    @Test
    fun registry_includes_memory_tools() {
        val names = FunctionRegistry.allTools.map {
            ((it["function"] as Map<*, *>)["name"] as String)
        }.toSet()

        assertTrue("read_memory" in names)
        assertTrue("request_memory_update" in names)
        assertTrue("summarize_trend" in names)
        assertTrue("refuse_unsupported_question" in names)
        assertTrue("create_care_activity_log" in names)
        assertTrue("ask_subjective_checkin" in names)
        assertTrue("record_subjective_checkin" in names)
        assertTrue("create_persona_activity_report" in names)
        assertTrue("select_dual_task_prompt" in names)
        assertTrue("record_dual_task_result" in names)
    }

    @Test
    fun buildToolsJson_returnsParseableJsonArray() {
        val tools = JSONArray(FunctionRegistry.buildToolsJson())

        assertEquals(FunctionRegistry.allTools.size, tools.length())
        val names = (0 until tools.length()).map { index ->
            tools.getJSONObject(index)
                .getJSONObject("function")
                .getString("name")
        }.toSet()

        assertTrue("correct_spinal_alignment" in names)
        assertTrue("refuse_unsupported_question" in names)
        assertTrue("create_care_activity_log" in names)
        assertTrue("create_persona_activity_report" in names)
    }

    @Test
    fun recordDualTaskResult_schemaIncludesBoundedVoiceFields() {
        val tool = FunctionRegistry.recordDualTaskResult["function"] as Map<*, *>
        val parameters = tool["parameters"] as Map<*, *>
        val properties = parameters["properties"] as Map<*, *>

        assertTrue("recognized_speech" in properties)
        assertTrue("asr_confidence" in properties)
    }
}
