package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubagentBatchRequestTest {
    @Test
    fun acceptsIndependentProfilesAndBudgetsInInputOrder() {
        val tasks = JSONArray()
            .put(task("search").put("subagent_type", "Explore").put("max_tool_calls", 3))
            .put(task("implement").put("timeout_seconds", 600).put("run_in_background", false))
        val requests = parse(tasks.toString())
        assertEquals(listOf("search", "implement"), requests.map { it.description })
        assertEquals(SubagentProfile.EXPLORE, requests[0].profile)
        assertEquals(3, requests[0].maxToolCalls)
        assertEquals(SubagentProfile.GENERAL_PURPOSE, requests[1].profile)
        assertEquals(600, requests[1].timeoutSeconds)
    }

    @Test
    fun doesNotImposeAnAgentCountQuota() {
        val tasks = JSONArray()
        repeat(100) { tasks.put(task("same task")) }
        assertEquals(100, parse(tasks.toString()).size)
    }

    @Test
    fun rejectsInvalidTasksBeforeReturningAnyBatch() {
        val invalid = listOf(
            "[]", "{}", "[", "[null]", "[true]",
            JSONArray().put(task().put("description", 7)).toString(),
            JSONArray().put(task().put("prompt", JSONObject.NULL)).toString(),
            JSONArray().put(task().put("subagent_type", "unknown")).toString(),
            JSONArray().put(task().put("max_tool_calls", "8")).toString(),
            JSONArray().put(task().put("max_tool_calls", 1.5)).toString(),
            JSONArray().put(task().put("timeout_seconds", 0)).toString(),
            JSONArray().put(task().put("run_in_background", "false")).toString(),
            JSONArray().put(task().put("run_in_background", true)).toString(),
            JSONArray().put(task().put("role_card_id", "other")).toString(),
            JSONArray().put(task("valid")).put(task("invalid").put("prompt", " ")).toString(),
        )
        invalid.forEach { raw ->
            try {
                parse(raw)
                fail("Expected rejection for $raw")
            } catch (expected: IllegalArgumentException) {
                assertFalse(expected.message.isNullOrBlank())
            }
        }
    }

    @Test
    fun rejectsMissingUnknownAndDuplicateOuterFields() {
        val raw = JSONArray().put(task()).toString()
        val invalid = listOf(
            emptyList(),
            listOf("task" to raw),
            listOf("tasks" to raw, "tasks" to raw),
            listOf("tasks" to raw, "run_in_background" to "true"),
        )
        invalid.forEach { params ->
            try {
                SubagentBatchRequest.parse(params)
                fail("Expected parameter rejection")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("tasks"))
            }
        }
    }

    private fun task(name: String = "Inspect") =
        JSONObject().put("description", name).put("prompt", "Inspect files independently")

    private fun parse(raw: String) = SubagentBatchRequest.parse(listOf("tasks" to raw))
}