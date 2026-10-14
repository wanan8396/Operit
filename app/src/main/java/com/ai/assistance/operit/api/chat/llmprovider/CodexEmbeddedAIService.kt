package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.codex.CodexCoreManager
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.asStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.flow

/**
 * Operit 原生内置 Codex 智能体执行服务。
 * 实现 AIService 标准接口，将聊天交互直接委托给本地持久化 Codex 智能体内核。
 */
class CodexEmbeddedAIService(
    private val context: Context,
    private val modelName: String = "o3-mini",
    private val customCwd: String? = null
) : AIService {

    companion object {
        private const val TAG = "CodexEmbeddedAIService"
    }

    private val codexManager = CodexCoreManager.getInstance(context)
    private val threadSessionMap = ConcurrentHashMap<String, String>()

    private val _inputTokens = AtomicLong(0)
    private val _cachedTokens = AtomicLong(0)
    private val _outputTokens = AtomicLong(0)

    override val inputTokenCount: Long get() = _inputTokens.get()
    override val cachedInputTokenCount: Long get() = _cachedTokens.get()
    override val outputTokenCount: Long get() = _outputTokens.get()
    override val providerModel: String get() = "CODEX:$modelName"

    override fun resetTokenCounts() {
        _inputTokens.set(0)
        _cachedTokens.set(0)
        _outputTokens.set(0)
    }

    override fun cancelStreaming() {
        // 可选中断当前 turn
    }

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return try {
            val models = codexManager.listModels()
            val list = models.map { id ->
                ModelOption(
                    id = id,
                    name = id,
                    displayName = "$id (Codex Native Agent)",
                    ownedBy = "codex"
                )
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun testConnection(context: Context): Result<String> {
        return try {
            codexManager.ensureStarted()
            val models = codexManager.listModels()
            Result.success("Codex 核心就绪，可用模型: " + models.joinToString(", "))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Long {
        var totalChars = 0L
        for (turn in chatHistory) {
            totalChars += turn.content.length
        }
        return totalChars / 4
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Long, cachedInput: Long, output: Long) -> Unit,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        recordTokenUsage: Boolean,
        onUsageFinalized: (suspend (attempt: Int?) -> Unit)?
    ): Stream<String> {
        return flow {
            codexManager.ensureStarted()

            // 提取最新用户输入
            val lastUserTurn = chatHistory.lastOrNull { it.role.equals("user", ignoreCase = true) }
            val promptText = lastUserTurn?.content ?: "Hello"

            // 维护对应会话的 Thread ID
            val sessionKey = "operit_active_chat"
            var threadId = threadSessionMap[sessionKey]
            if (threadId == null) {
                threadId = codexManager.startThread(cwd = customCwd, model = modelName)
                threadSessionMap[sessionKey] = threadId
            }

            // 触发 Codex 执行 Turn
            val activeTurnId = codexManager.startTurn(threadId, promptText, model = modelName)

            // 监听并流式返回 Codex 事件
            var isTurnActive = true
            val eventsFlow = codexManager.events

            eventsFlow.collect { event ->
                if (event.threadId == threadId) {
                    if (!event.turnId.isNullOrEmpty() && event.turnId != activeTurnId) {
                        return@collect
                    }

                    when (event.method) {
                        "item/agentMessage/delta" -> {
                            event.delta?.let { chunk ->
                                if (chunk.isNotEmpty()) {
                                    _outputTokens.addAndGet(chunk.length / 4L)
                                    emit(chunk)
                                }
                            }
                        }
                        "item/reasoning/textDelta" -> {
                            event.delta?.let { rDelta ->
                                if (rDelta.isNotEmpty()) {
                                    emit("💭 *$rDelta* ")
                                }
                            }
                        }
                        "item/started" -> {
                            if (event.command != null) {
                                emit("\n\n> ⚙️ **Codex 执行命令**: `${event.command}`\n```bash\n")
                            }
                        }
                        "item/commandExecution/outputDelta" -> {
                            event.output?.let { out ->
                                emit(out)
                            }
                        }
                        "item/completed" -> {
                            if (event.command != null) {
                                emit("\n```\n> ✅ *命令执行完成*\n\n")
                            }
                        }
                        "turn/completed", "turn/aborted" -> {
                            isTurnActive = false
                            return@collect
                        }
                    }
                }
            }
        }.asStream()
    }
}