package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.StringResultData
import kotlinx.coroutines.CancellationException
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.last

/** Start every child before awaiting any result. All jobs belong to the invoking parent. */
internal object SubagentBatchRunner {
    suspend fun run(requests: List<SubagentRequest>, executor: ToolExecutor): List<ToolResult> =
        coroutineScope {
            require(requests.isNotEmpty()) { "A subagent batch must not be empty" }
            requests.map { request ->
                async {
                    // Use the same native child lifecycle as single delegation, including cleanup.
                    // Never use invoke/runBlocking: they lose the parent's coroutine cancellation.
                    try {
                        executor.invokeAndStream(
                        AITool(
                            name = "run_subagent",
                            parameters = listOf(
                                ToolParameter("description", request.description),
                                ToolParameter("prompt", request.prompt),
                                ToolParameter("subagent_type", request.profile.wireName),
                                ToolParameter("run_in_background", "false"),
                                ToolParameter("max_tool_calls", request.maxToolCalls.toString()),
                                ToolParameter("timeout_seconds", request.timeoutSeconds.toString()),
                            ),
                        )
                        ).last()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // A failed flow or cleanup belongs to this child, not to its siblings.
                        ToolResult("run_subagent", false, StringResultData(""),
                            error.message ?: error.javaClass.simpleName)
                    }
                }
            }.awaitAll()
        }
}