package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.ToolResult
import org.junit.Assert.*
import org.junit.Test

class SubagentBatchOutputTest {
    @Test
    fun preservesSuccessfulPayloadAndFailedSiblingInInputOrder() {
        val requests = listOf(request("first"), request("second"))
        val success = SubagentOutput.completed("id-1", requests[0], "done", 2, 20, 10, 15)
        val output = SubagentBatchOutput.completed(
            requests,
            listOf(
                ToolResult("run_subagent", true, StringResultData(success.toString())),
                ToolResult("run_subagent", false, StringResultData(""), "Subagent timed out after 10s"),
            ),
            100,
        )
        assertEquals("completed_with_errors", output.getString("status"))
        assertEquals(2, output.getInt("totalAgents"))
        assertEquals(1, output.getInt("succeeded"))
        assertEquals(1, output.getInt("failed"))
        assertEquals(100L, output.getLong("totalDurationMs"))
        val items = output.getJSONArray("results")
        assertEquals(0, items.getJSONObject(0).getInt("index"))
        assertEquals("id-1", items.getJSONObject(0).getJSONObject("result").getString("agentId"))
        assertEquals("second", items.getJSONObject(1).getString("description"))
        assertFalse(items.getJSONObject(1).getBoolean("success"))
        assertTrue(items.getJSONObject(1).getString("error").contains("timed out"))
    }

    @Test
    fun allSuccessfulChildrenProduceCompletedBatch() {
        val request = request("first")
        val success = SubagentOutput.completed("id", request, "ok", 0, 2, 1, 1)
        val output = SubagentBatchOutput.completed(
            listOf(request),
            listOf(ToolResult("run_subagent", true, StringResultData(success.toString()))),
            3,
        )
        assertEquals("completed", output.getString("status"))
        assertEquals(0, output.getInt("failed"))
    }

    private fun request(name: String) = SubagentRequest(name, "Inspect", SubagentProfile.EXPLORE, 4, 10)
}