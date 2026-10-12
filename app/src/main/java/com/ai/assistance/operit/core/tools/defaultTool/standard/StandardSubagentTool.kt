package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.config.SystemPromptConfig
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.FunctionalConfigManager
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** One parent-call-scoped delegated agent. No detached job or persisted hidden conversation. */
class StandardSubagentTool(context: Context) : ToolExecutor {
    private val appContext = context.applicationContext

    override fun invoke(tool: AITool): ToolResult {
        // A synchronous/package bridge loses coroutine cancellation; fail instead of orphaning work.
        return failure(tool, "run_subagent requires the native streaming tool execution path")
    }

    override fun invokeAndStream(tool: AITool): Flow<ToolResult> = invokeInRound(tool, null)

    internal fun invokeInRound(tool: AITool, batchRoundId: String?): Flow<ToolResult> = flow {
        val parent = ToolExecutionManager.currentToolRuntimeContext()
        if (parent == null || parent.isSubTask) {
            emit(failure(tool, "Only a parent agent can delegate a subtask; nested delegation is disabled"))
            return@flow
        }
        val request = try {
            SubagentRequest.parse(tool.parameters.map { it.name to it.value })
        } catch (error: IllegalArgumentException) {
            emit(failure(tool, error.message ?: "Invalid subagent parameters"))
            return@flow
        }
        // Delegations are parent-scoped; no global count or concurrency quota is imposed.
        val id = UUID.randomUUID().toString()
        var service: EnhancedAIService? = null
        val toolCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val startedAt = System.nanoTime()
        var finalStatus = "cancelled"
        val parentChatId = parent.callerChatId
        if (parentChatId.isNullOrBlank()) {
            emit(failure(tool, "Missing parent chat identity"))
            return@flow
        }
        val roundId = batchRoundId ?: com.ai.assistance.operit.data.stats.SubagentProgressStore.beginRound(parentChatId, parent.parentExecutionId)
        val continuation = SubagentContinuation()
        com.ai.assistance.operit.data.stats.SubagentProgressStore.start(parentChatId,
            com.ai.assistance.operit.data.stats.SubagentProgress(id, request.profile.wireName, request.description), roundId)
        try {
            val result = SubagentRetryRunner.run(
                onFailure = { attempt, error ->
                    com.ai.assistance.operit.data.stats.SubagentProgressStore.retry(
                        parentChatId, id, attempt, error.message ?: error.javaClass.simpleName
                    )
                },
            ) { attempt, previousError ->
                com.ai.assistance.operit.data.stats.SubagentProgressStore.beginAttempt(parentChatId, id, attempt)
                val childToolIds = mutableMapOf<Pair<String, IntRange>, String>()
                try {
                    val mapping = FunctionalConfigManager(appContext)
                        .getConfigMappingForFunction(FunctionType.SUBAGENT)
                    val configId = mapping.configId
                    val modelIndex = mapping.modelIndex
                    // Model overrides only apply to CHAT, which also runs the full tool-enabled chat pipeline.
                    // Read the dedicated mapping once so context budgeting and execution use the same model.
                    val child = EnhancedAIService.createSubagentInstance(appContext, request.profile)
                    service = child
                    if (attempt == 1L) emit(ToolResult(tool.name, true, StringResultData(
                        JSONObject().put("agentId", id).put("agentType", request.profile.wireName)
                            .put("description", request.description).put("status", "running")
                            .put("message", appContext.getString(R.string.chat_subagent_running)).toString()
                    )))
                    withTimeout(request.timeoutSeconds * 1000L) {
                        val config = child.getModelConfigForFunction(FunctionType.CHAT, configId, modelIndex)
                        val maxContextTokens = (config.contextLength.toDouble() * 1024.0).toInt()
                        require(maxContextTokens > 0) { "Subagent model context length must be configured" }
                        require(config.summaryTokenThreshold.toDouble() > 0.0 && config.summaryTokenThreshold.toDouble() <= 1.0) {
                            "Invalid subagent context threshold"
                        }
                        val resumeHistory = continuation.snapshotForResume()
                        val prompt = buildString {
                            if (resumeHistory.isEmpty()) {
                                appendLine("Complete this delegated task and return a concise result to the parent agent:")
                                appendLine(request.prompt)
                            } else {
                                appendLine("Continue the SAME delegated task from recorded history and completed tool results. Do not restart or repeat completed operations.")
                            }
                            // Explicit reference only: the child owns no authority over the parent plan.
                            appendLine("Parent plan reference only: perform only this delegated task, not unrelated parent steps.")
                            appendLine(com.ai.assistance.operit.data.stats.PlanStepStore.formatPlanContext(parentChatId))
                            if (previousError != null) {
                                appendLine("Execution was interrupted: ${previousError.take(2000)}")
                                appendLine("Verify tools marked outcome unknown before further action. Never blindly replay an interrupted write, upload, submit or other irreversible action. If its outcome cannot be verified, report the blocker.")
                            }
                            if (request.profile == SubagentProfile.EXPLORE) {
                                appendLine("READ-ONLY MODE: search and read existing files only. No shell, packages, changes or downloads.")
                                appendLine("Allowed tools: ${SubagentPolicy.exploreTools.joinToString()}.")
                            }
                            appendLine("Do not delegate again. This attempt has a tool-call limit of ${request.maxToolCalls}; task-wide tool counts remain cumulative across attempts.")
                            appendLine("Do not ask the user questions. If blocked, state the blocker and stop.")
                            appendLine("Return findings and verification, not hidden reasoning or raw tool markup.")
                        }
                        child.sendMessage(EnhancedAIService.SendMessageOptions(
                            message = prompt,
                            maxTokens = maxContextTokens,
                            tokenUsageThreshold = config.summaryTokenThreshold.toDouble(),
                            chatId = "subagent:$id",
                            chatHistory = resumeHistory,
                            workspacePath = parent.workspacePath,
                            workspaceEnv = parent.workspaceEnv,
                            functionType = FunctionType.CHAT,
                            isSubTask = true,
                            roleCardId = parent.callerCardId,
                            enableMemoryAutoUpdate = false,
                            notifyReplyOverride = false,
                            customSystemPromptTemplate = SystemPromptConfig.SUBTASK_AGENT_PROMPT_TEMPLATE,
                            chatModelConfigIdOverride = configId,
                            chatModelIndexOverride = modelIndex,
                            memorySpaceIdOverride = parent.memorySpaceId,
                            maxToolCalls = request.maxToolCalls,
                            onChildHistoryCheckpoint = continuation::checkpoint,
                            onChildToolBatchStart = { turns, invocations ->
                                // Identical text in a later model round is a new invocation.
                                synchronized(childToolIds) { childToolIds.clear() }
                                continuation.beginBatch(turns, invocations)
                            },
                            onChildToolBatchCommitted = continuation::commitBatch,
                            onChildToolEvent = { invocation, status, result ->
                                continuation.toolEvent(invocation, status, result)
                                val callId = synchronized(childToolIds) {
                                    childToolIds.getOrPut(invocation.rawText to invocation.responseLocation) {
                                        toolCalls.incrementAndGet()
                                        "tool_subagent_${id}_${UUID.randomUUID()}"
                                    }
                                }
                                val name = if (invocation.tool.name == "package_proxy" || invocation.tool.name == "proxy") {
                                    invocation.tool.parameters.firstOrNull { it.name == "tool_name" }?.value.orEmpty()
                                } else invocation.tool.name
                                com.ai.assistance.operit.data.stats.SubagentProgressStore.tool(parentChatId, id,
                                    com.ai.assistance.operit.data.stats.SubagentToolProgress(callId, name, status))
                            },
                        )).collect { currentCoroutineContext().ensureActive() }
                        currentCoroutineContext().ensureActive()
                        child.getSubagentFailure()?.let { throw IllegalStateException(it) }
                        val reply = child.getSubagentFinalReply()
                            ?: throw IllegalStateException("Subagent did not produce a final answer")
                        val summary = ChatUtils.removeThinkingContent(reply)
                            .replace(ChatMarkupRegex.statusTag, "")
                            .replace(ChatMarkupRegex.statusSelfClosingTag, "")
                            .trim().take(16_000)
                        require(summary.isNotBlank()) { "Subagent returned no usable summary" }
                        SubagentOutput.completed(id, request, summary, toolCalls.get(),
                            (System.nanoTime() - startedAt) / 1_000_000,
                            child.getCurrentInputTokenCount(), child.getCurrentOutputTokenCount())
                    }
                } finally {
                    // Every attempt owns a fresh child service and finite budget. Close it before
                    // retrying, even when its model stream fails or the parent is cancelled.
                    withContext(NonCancellable) {
                        val attemptService = service
                        service = null
                        attemptService?.closeSubagentInstance()
                    }
                }
            }
            finalStatus = "completed"
            emit(ToolResult(tool.name, true, StringResultData(result.toString())))
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            finalStatus = "timed_out"
            emit(failure(tool, "Subagent timed out after ${request.timeoutSeconds}s"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            finalStatus = "failed"
            AppLogger.e(TAG, "Delegated task failed: $id", error)
            emit(failure(tool, error.message ?: error.javaClass.simpleName))
        } finally {
            try {
                withContext(NonCancellable) { service?.closeSubagentInstance() }
            } finally {
                com.ai.assistance.operit.data.stats.SubagentProgressStore.finish(parentChatId, id, finalStatus)
            }
        }
    }

    private fun failure(tool: AITool, reason: String) =
        ToolResult(tool.name, false, StringResultData(""), reason)

    companion object {
        private const val TAG = "StandardSubagentTool"
    }
}