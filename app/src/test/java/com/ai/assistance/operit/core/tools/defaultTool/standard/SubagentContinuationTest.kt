package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentContinuationTest {
    private val user = PromptTurn(PromptTurnKind.USER, "Complete original delegated task")
    private val assistant = PromptTurn(PromptTurnKind.ASSISTANT, "Recorded tool calls")
    private fun call(name: String, position: Int) =
        ToolInvocation(AITool(name), "call:$name:$position", position..position + 4)

    @Test fun checkpointRetainsTaskAndRemovesRebuiltSystemPrompt() {
        val continuation = SubagentContinuation()
        continuation.checkpoint(listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "old system prompt"), user, assistant,
        ))
        assertEquals(listOf(user, assistant), continuation.snapshotForResume())
    }

    @Test fun interruptedBatchRetainsSuccessfulSiblingAndDistinguishesUnknownOutcome() {
        val continuation = SubagentContinuation()
        val completed = call("read_file", 1)
        val unknown = call("edit_file", 10)
        val unstarted = call("upload_file", 20)
        continuation.beginBatch(listOf(user, assistant), listOf(completed, unknown, unstarted))
        continuation.toolEvent(completed, "started", null)
        continuation.toolEvent(completed, "result", ToolResult("read_file", true, StringResultData("retained evidence")))
        continuation.toolEvent(unknown, "started", null)
        // Service teardown can publish the same history before the batch has committed.
        continuation.checkpoint(listOf(user, assistant))
        val snapshot = continuation.snapshotForResume()
        assertEquals(listOf(user, assistant), snapshot.take(2))
        assertEquals(PromptTurnKind.TOOL_RESULT, snapshot.last().kind)
        assertTrue(snapshot.last().content.contains("retained evidence"))
        assertTrue(snapshot.last().content.contains("outcome unknown"))
        assertTrue(snapshot.last().content.contains("was not executed"))
        assertEquals(snapshot, continuation.snapshotForResume())
    }

    @Test fun copiedPackageInvocationRetainsResult() {
        val continuation = SubagentContinuation()
        val original = call("package_proxy", 1)
        continuation.beginBatch(listOf(user, assistant), listOf(original))
        val injected = original.copy(tool = original.tool.copy(
            parameters = listOf(ToolParameter("caller_chat_id", "subagent:test")),
        ))
        continuation.toolEvent(injected, "started", null)
        continuation.toolEvent(injected, "result", ToolResult("package_proxy", true, StringResultData("copied invocation result")))
        val result = continuation.snapshotForResume().last().content
        assertTrue(result.contains("copied invocation result"))
        assertFalse(result.contains("outcome unknown"))
    }

    @Test fun sameNamedCallsUseSourceLocationInsteadOfName() {
        val continuation = SubagentContinuation()
        val first = call("read_file", 1)
        val second = call("read_file", 10)
        continuation.beginBatch(listOf(user, assistant), listOf(first, second))
        continuation.toolEvent(second, "result", ToolResult("read_file", true, StringResultData("second-only evidence")))
        val result = continuation.snapshotForResume().last().content
        assertTrue(result.contains("second-only evidence"))
        assertTrue(result.contains("was not executed"))
    }

    @Test fun committedBatchIsNotAppendedAgainOnResume() {
        val continuation = SubagentContinuation()
        val invocation = call("read_file", 1)
        continuation.beginBatch(listOf(user, assistant), listOf(invocation))
        val committed = listOf(user, assistant, PromptTurn(PromptTurnKind.TOOL_RESULT, "committed results"))
        continuation.commitBatch(committed)
        assertEquals(committed, continuation.snapshotForResume())
        continuation.beginBatch(committed + assistant, listOf(invocation))
        continuation.toolEvent(invocation, "result", ToolResult("read_file", true, StringResultData("next round evidence")))
        val resumed = continuation.snapshotForResume()
        assertEquals(committed, resumed.take(3))
        assertTrue(resumed.last().content.contains("next round evidence"))
    }

    @Test fun deniedInvocationRetainsItsActualError() {
        val continuation = SubagentContinuation()
        val invocation = call("edit_file", 1)
        continuation.beginBatch(listOf(user, assistant), listOf(invocation))
        continuation.toolEvent(invocation, "error", ToolResult("edit_file", false, StringResultData(""), "permission denied"))
        val result = continuation.snapshotForResume().last().content
        assertTrue(result.contains("permission denied"))
        assertFalse(result.contains("outcome unknown"))
    }
}
