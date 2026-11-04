package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.*
import org.junit.Test

class SubagentRequestTest {
    private fun tool(vararg parameters: Pair<String, String>) = parameters.toList()
    private fun valid(vararg extra: Pair<String, String>) = (mapOf(
        "description" to "Inspect file",
        "prompt" to "Inspect one file"
    ) + extra.toMap()).toList()

    @Test fun defaultsAreFiniteAndPromptIsRequired() {
        val request = SubagentRequest.parse(valid())
        assertEquals("Inspect file", request.description)
        assertEquals("Inspect one file", request.prompt)
        assertEquals(SubagentProfile.GENERAL_PURPOSE, request.profile)
        assertEquals(8, request.maxToolCalls)
        assertEquals(180, request.timeoutSeconds)
    }

    @Test fun acceptsBoundariesAndExploreProfile() {
        val request = SubagentRequest.parse(valid(
            "subagent_type" to "Explore",
            "max_tool_calls" to "32",
            "timeout_seconds" to "600"
        ))
        assertEquals(SubagentProfile.EXPLORE, request.profile)
        assertEquals(32, request.maxToolCalls)
        assertEquals(600, request.timeoutSeconds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankPrompt() { SubagentRequest.parse(valid("prompt" to " ")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateFields() {
        SubagentRequest.parse(
            tool(
                "description" to "Inspect file",
                "prompt" to "one",
                "prompt" to "two"
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRoleIdentitySuppliedByModel() {
        SubagentRequest.parse(valid("role_card_id" to "other"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPrompt() {
        SubagentRequest.parse(valid("prompt" to "x".repeat(48_001)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDecimalLimit() { SubagentRequest.parse(valid("max_tool_calls" to "1.5")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnlimitedRuntime() { SubagentRequest.parse(valid("timeout_seconds" to "0")) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBackgroundExecution() {
        SubagentRequest.parse(valid("run_in_background" to "true"))
    }
}
