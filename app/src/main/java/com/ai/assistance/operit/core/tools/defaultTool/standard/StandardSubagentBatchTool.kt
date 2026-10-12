package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** One native call fans out all independent tasks; no detached background jobs are created. */
class StandardSubagentBatchTool(context: Context) : ToolExecutor {
    private val childExecutor = StandardSubagentTool(context.applicationContext)

    override fun invoke(tool: AITool): ToolResult =
        failure(tool, "run_subagents requires the native streaming tool execution path")

    override fun invokeAndStream(tool: AITool): Flow<ToolResult> = flow {
        val parent = ToolExecutionManager.currentToolRuntimeContext()
        if (parent == null || parent.isSubTask || parent.callerChatId.isNullOrBlank()) {
            emit(failure(tool, "Only a parent agent with a chat identity can delegate a batch"))
            return@flow
        }
        val requests = try {
            SubagentBatchRequest.parse(tool.parameters.map { it.name to it.value })
        } catch (error: IllegalArgumentException) {
            emit(failure(tool, error.message ?: "Invalid subagent batch parameters"))
            return@flow
        }
        val roundId = com.ai.assistance.operit.data.stats.SubagentProgressStore.beginRound(
            requireNotNull(parent.callerChatId), parent.parentExecutionId,
        )
        val roundExecutor = object : ToolExecutor {
            override fun invoke(childTool: AITool): ToolResult = childExecutor.invoke(childTool)
            override fun invokeAndStream(childTool: AITool): Flow<ToolResult> =
                childExecutor.invokeInRound(childTool, roundId)
        }
        val startedAt = System.nanoTime()
        val results = SubagentBatchRunner.run(requests, roundExecutor)
        // Valid children retry internally until successful or cancelled. Preserve ordered results
        // and any delegation/lifecycle errors rather than hiding them in one generic error.
        val output = SubagentBatchOutput.completed(
            requests, results, (System.nanoTime() - startedAt) / 1_000_000,
        )
        emit(ToolResult(tool.name, true, StringResultData(output.toString())))
    }

    private fun failure(tool: AITool, reason: String) =
        ToolResult(tool.name, false, StringResultData(""), reason)
}