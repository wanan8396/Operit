package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.assertEquals
import org.junit.Test

class SubagentOutputTest {
    @Test fun completedMatchesSynchronousContract() {
        val request = SubagentRequest("Inspect files", "Find entry point",
            SubagentProfile.EXPLORE, 8, 180)
        val result = SubagentOutput.completed("agent-id", request, "Entry found", 2, 400L, 100L, 20L)
        assertEquals("completed", result.getString("status"))
        assertEquals("agent-id", result.getString("agentId"))
        assertEquals("Explore", result.getString("agentType"))
        assertEquals(request.description, result.getString("description"))
        assertEquals(request.prompt, result.getString("prompt"))
        assertEquals(2, result.getInt("totalToolUseCount"))
        assertEquals(400L, result.getLong("totalDurationMs"))
        assertEquals(120L, result.getLong("totalTokens"))
        val content = result.getJSONArray("content")
        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("Entry found", content.getJSONObject(0).getString("text"))
    }
}
