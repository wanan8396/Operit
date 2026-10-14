package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.codex.CodexCoreManager
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.asStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Official local agent, authenticated only using the selected Operit API key. */
class CodexEmbeddedAIService(
    private val context: Context,
    private val apiEndpoint: String,
    private val apiKeyProvider: ApiKeyProvider,
    private val modelName: String,
    private val customHeaders: Map<String, String> = emptyMap(),
    private val customCwd: String? = null
) : AIService {
    private val manager = CodexCoreManager(context.applicationContext)
    private val serial = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val input = AtomicLong(0)
    private val cached = AtomicLong(0)
    private val output = AtomicLong(0)
    @Volatile private var active: Pair<String, String>? = null
    @Volatile private var activeJob: Job? = null
    override val inputTokenCount: Long get() = input.get()
    override val cachedInputTokenCount: Long get() = cached.get()
    override val outputTokenCount: Long get() = output.get()
    override val providerModel: String get() = "CODEX_API:$modelName"
    override fun resetTokenCounts() { input.set(0); cached.set(0); output.set(0) }
    override fun cancelStreaming() {
        activeJob?.cancel()
        active?.let { (thread, turn) -> scope.launch { runCatching { manager.interrupt(thread, turn) } } }
    }
    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> =
        Result.success(listOf(ModelOption(id = modelName, name = modelName)))

    override suspend fun testConnection(context: Context): Result<String> = try {
        manager.ensureStarted(apiEndpoint, apiKeyProvider.getApiKey(), modelName, customHeaders)
        val thread = manager.startThread("Connection probe only; do not execute commands.", customCwd)
        Result.success("Official Codex app-server ready ($thread). API key/provider configured; model inference not tested.")
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

    override suspend fun calculateInputTokens(chatHistory: List<PromptTurn>, availableTools: List<ToolPrompt>?): Long =
        chatHistory.sumOf { it.content.length.toLong() } / 4L

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
    ): Stream<String> = flow {
        serial.withLock {
            activeJob = currentCoroutineContext()[Job]
            resetTokenCounts()
            var threadId: String? = null
            try {
                manager.ensureStarted(apiEndpoint, apiKeyProvider.getApiKey(), modelName, customHeaders)
                val instructions = chatHistory.filter { it.role.equals("system", true) || it.role.equals("developer", true) }
                    .joinToString("\n\n") { it.content } +
                    "\nYou are running inside Operit. Use only Codex native tools. Do not emit Operit XML tool-call tags. Writes and interactive approvals are currently disabled."
                val conversation = chatHistory.filterNot { it.role.equals("system", true) || it.role.equals("developer", true) }
                require(conversation.isNotEmpty()) { "Codex needs conversation input" }
                // Rehydrate all history into a fresh thread. No cross-chat global thread alias.
                val text = conversation.joinToString("\n\n") { "[${it.role}]\n${it.content}" }
                val thread = manager.startThread(instructions, customCwd)
                threadId = thread
                val events = manager.subscribe(thread) // Subscribe BEFORE turn/start to avoid early-event loss.
                val turn = manager.startTurn(thread, text)
                active = thread to turn
                var complete = false
                while (!complete) {
                    val event = withTimeout(300_000) { events.receive() }
                    val params = event.getJSONObject("params")
                    when (event.optString("method")) {
                        "item/agentMessage/delta" -> emit(params.optString("delta"))
                        "item/reasoning/textDelta", "item/reasoning/summaryTextDelta" ->
                            if (enableThinking) emit(params.optString("delta"))
                        "thread/tokenUsage/updated" -> {
                            val last = params.optJSONObject("tokenUsage")?.optJSONObject("last")
                            if (last != null) {
                                input.set(last.optLong("inputTokens"))
                                cached.set(last.optLong("cachedInputTokens"))
                                output.set(last.optLong("outputTokens"))
                                onTokensUpdated(input.get(), cached.get(), output.get())
                            }
                        }
                        "turn/completed" -> {
                            val state = params.getJSONObject("turn")
                            if (state.getString("id") == turn) {
                                check(state.optString("status") == "completed") {
                                    "Codex turn ${state.optString("status")}; check Responses API/model compatibility"
                                }
                                complete = true
                            }
                        }
                    }
                }
                // No fabricated estimates sent to the accounting callback.
                onUsageFinalized?.invoke(null)
            } finally {
                val unfinished = active
                active = null
                activeJob = null
                if (unfinished != null) withContext(NonCancellable) {
                    runCatching { withTimeout(3_000) { manager.interrupt(unfinished.first, unfinished.second) } }
                }
                threadId?.let(manager::unsubscribe)
            }
        }
    }.asStream()

    override fun release() { cancelStreaming(); manager.release(); scope.cancel() }
}
