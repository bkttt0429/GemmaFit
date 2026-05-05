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
    }
}
