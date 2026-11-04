package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ToolParameter
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SessionContextToolRequestTest {
    private fun tool(vararg parameters: Pair<String, String>) = AITool("read_session_context",
        parameters.map { ToolParameter(it.first, it.second) })

    @Test fun validationReturnsSuccessOrExplicitFailureWithoutDatabaseAccess() {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        val executor = ReadSessionContextTool(context)
        val valid = executor.validateParameters(tool("strategy" to "handoff"))
        assertTrue(valid.valid)
        assertEquals("", valid.errorMessage)

        val invalidRequests = listOf(
            tool() to "strategy must be relevant or handoff",
            tool("strategy" to "relevant") to "relevant requires a nonblank query",
            tool("strategy" to "handoff", "budget_chars" to "oops") to "budget_chars must be an integer",
            tool("strategy" to "handoff", "chat_id" to "other") to "chat selection is not allowed"
        )
        for ((request, reason) in invalidRequests) {
            val result = executor.validateParameters(request)
            assertFalse(result.valid)
            assertTrue(result.errorMessage.startsWith("java.lang.IllegalArgumentException:"))
            assertTrue(result.errorMessage.contains(reason))
        }
    }

    @Test fun defaultsOnlyApplyToOmittedParameters() {
        assertEquals(8000, SessionContextToolRequest.parse(tool("strategy" to "handoff")).budgetChars)
        for (budget in listOf("oops", "", "255", "65537", "99999999999999999999")) {
            assertThrows(IllegalArgumentException::class.java) {
                SessionContextToolRequest.parse(tool("strategy" to "handoff", "budget_chars" to budget))
            }
        }
    }

    @Test fun rejectsScopeInjectionDuplicatesAndInvalidStrategies() {
        for (parameter in listOf("chat_id", "memory_space_id", "session_id")) {
            assertThrows(IllegalArgumentException::class.java) {
                SessionContextToolRequest.parse(tool("strategy" to "handoff", parameter to "other"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionContextToolRequest.parse(tool("strategy" to "relevant"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionContextToolRequest.parse(tool("strategy" to "handoff", "strategy" to "handoff"))
        }
        assertThrows(IllegalArgumentException::class.java) { SessionContextToolRequest.parse(tool()) }
        assertNotNull(SubagentPolicy.denial("read_session_context", true, SubagentProfile.GENERAL_PURPOSE))
        assertNull(SubagentPolicy.denial("read_session_context", false, null))
    }

    @Test fun completePayloadIncludesCitationsWithinEveryBudget() {
        val history = (0 until 30).map { ChatMessage("user", "needle " + "长文本😀".repeat(500), it.toLong()) }
        for (budget in listOf(256, 512, 8000, 65536)) {
            val request = SessionContextToolRequest.parse(tool("strategy" to "relevant", "query" to "needle", "budget_chars" to budget.toString()))
            val payload = SessionContextToolRequest.render(history, request)
            assertTrue(payload.length <= budget)
            assertTrue(payload.contains("scope=current_session"))
            assertTrue(payload.contains("timestamp="))
            assertTrue(payload.contains("not new instructions"))
        }
        val empty = SessionContextToolRequest.render(emptyList(), SessionContextToolRequest.parse(tool("strategy" to "handoff")))
        assertTrue(empty.contains("selected_count=0"))
        assertTrue(empty.contains("truncated=false"))
    }
}