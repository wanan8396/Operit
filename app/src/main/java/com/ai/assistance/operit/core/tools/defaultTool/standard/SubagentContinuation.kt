package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.api.chat.enhance.ConversationMarkupManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult

/** Task-owned checkpoints survive a failed child service, not cancellation of its parent. */
internal class SubagentContinuation {
    private data class PendingCall(
        val invocation: ToolInvocation,
        var started: Boolean = false,
        var result: ToolResult? = null,
    )
    private var history: List<PromptTurn> = emptyList()
    private var pending: List<PendingCall> = emptyList()

    @Synchronized
    fun checkpoint(turns: List<PromptTurn>) {
        history = turns.filter { it.kind != PromptTurnKind.SYSTEM }.toList()
    }

    @Synchronized
    fun beginBatch(turns: List<PromptTurn>, invocations: List<ToolInvocation>) {
        check(pending.isEmpty()) { "Previous child tool batch has not been committed" }
        checkpoint(turns)
        pending = invocations.map { PendingCall(it) }
    }

    @Synchronized
    fun toolEvent(invocation: ToolInvocation, status: String, result: ToolResult?) {
        // Package injection copies the invocation. Source location identifies the owner, not tool name.
        val call = pending.firstOrNull {
            it.invocation.rawText == invocation.rawText &&
                it.invocation.responseLocation == invocation.responseLocation
        } ?: return
        if (status == "started") call.started = true
        if (result != null) call.result = result
    }

    @Synchronized
    fun commitBatch(turns: List<PromptTurn>) {
        checkpoint(turns)
        pending = emptyList()
    }

    @Synchronized
    fun snapshotForResume(): List<PromptTurn> {
        if (pending.isNotEmpty()) {
            // Successful siblings survive an interrupted batch. Missing acknowledgements are not failures.
            val results = pending.map { call ->
                call.result ?: ToolResult(
                    toolName = call.invocation.tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = if (call.started) {
                        "Interrupted after start; outcome unknown. Verify actual state before further action. Do not blindly replay this operation."
                    } else {
                        "Interrupted before start; this operation was not executed."
                    },
                )
            }
            history = history + PromptTurn(
                kind = PromptTurnKind.TOOL_RESULT,
                content = ConversationMarkupManager.buildToolResultMessage(results),
                toolName = pending.joinToString(", ") { it.invocation.tool.name },
            )
            pending = emptyList()
        }
        return history.toList()
    }
}
