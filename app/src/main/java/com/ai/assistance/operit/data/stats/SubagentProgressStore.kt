package com.ai.assistance.operit.data.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SubagentToolProgress(val id: String, val name: String, val status: String) {
    val isTerminal: Boolean
        get() = status in setOf("result", "error", "failed", "timed_out", "cancelled")
}
data class SubagentProgress(
    val agentId: String,
    val agentType: String,
    val description: String,
    val status: String = "running",
    val tools: List<SubagentToolProgress> = emptyList(),
    val attempt: Long = 1L,
    val lastError: String? = null,
    /** Task-wide count, independent of retries and the bounded activity list. */
    val toolCallCount: Long = tools.size.toLong(),
) {
    val isActive: Boolean get() = status == "running" || status == "retrying"
}

/** Parent-session projection only. Never stores child reasoning or raw tool output. */
object SubagentProgressStore {
    private val state = MutableStateFlow<Map<String, List<SubagentProgress>>>(emptyMap())
    val sessions: StateFlow<Map<String, List<SubagentProgress>>> = state.asStateFlow()

    private val rounds = mutableMapOf<String, String>()
    // Ownership is per collected parent execution, not per model hop or UI processing state.
    private val executions = mutableMapOf<String, String>()
    // Deduplicate lifecycle events even after their row leaves the 64-item activity window.
    private val seenToolIds = mutableMapOf<Pair<String, String>, MutableSet<String>>()
    private val conversationState = MutableStateFlow<Map<String, List<SubagentProgress>>>(emptyMap())
    val conversationSessions: StateFlow<Map<String, List<SubagentProgress>>> = conversationState.asStateFlow()

    private fun publish(chatId: String, agents: List<SubagentProgress>) {
        state.value = state.value + (chatId to agents)
        // Completed tasks remain inspectable in input details; only active rounds occupy the conversation tail.
        conversationState.value = if (agents.any { it.isActive }) {
            conversationState.value + (chatId to agents)
        } else {
            conversationState.value - chatId
        }
    }

    /** A new parent turn invalidates delayed callbacks from the preceding collected stream. */
    @Synchronized
    fun beginExecution(chatId: String): String {
        require(chatId.isNotBlank()) { "A chat id is required" }
        terminateActive(chatId, "cancelled")
        rounds.remove(chatId)
        val executionId = java.util.UUID.randomUUID().toString()
        executions[chatId] = executionId
        return executionId
    }

    /** Never allow an old stream finalizer to terminate a reconnected/new parent turn. */
    @Synchronized
    fun endExecution(chatId: String, executionId: String, status: String = "cancelled") {
        require(status in setOf("failed", "cancelled"))
        if (executions[chatId] != executionId) return
        executions.remove(chatId)
        rounds.remove(chatId)
        terminateActive(chatId, status)
    }

    private fun terminateActive(chatId: String, status: String) {
        val agents = state.value[chatId] ?: return
        publish(chatId, agents.map { if (it.isActive) terminal(it, status) else it })
    }

    private fun terminal(agent: SubagentProgress, status: String): SubagentProgress = agent.copy(
        status = status,
        tools = agent.tools.map { tool ->
            if (!tool.isTerminal) tool.copy(status = if (status == "completed") "error" else status)
            else tool
        },
    )

    /** One batch is one round. Start it before launching children, never from each batch child. */
    @Synchronized
    fun beginRound(chatId: String, executionId: String? = null): String {
        require(chatId.isNotBlank()) { "A chat id is required" }
        if (executionId != null && executions[chatId] != executionId) {
            throw kotlinx.coroutines.CancellationException("Parent execution has ended")
        }
        val roundId = java.util.UUID.randomUUID().toString()
        rounds[chatId] = roundId
        seenToolIds.keys.removeAll { it.first == chatId }
        publish(chatId, emptyList())
        return roundId
    }

    @Synchronized
    fun start(chatId: String, agent: SubagentProgress, roundId: String? = null) {
        // A superseded round can still finish cleanup; it must not repopulate the new panel.
        if (roundId != null && rounds[chatId] != roundId) return
        val old = state.value[chatId].orEmpty()
        seenToolIds[chatId to agent.agentId] = agent.tools.mapTo(mutableSetOf()) { it.id }
        publish(chatId, old + agent)
    }

    @Synchronized
    fun beginAttempt(chatId: String, agentId: String, attempt: Long) {
        val agents = state.value[chatId] ?: return
        publish(chatId, agents.map {
            if (it.agentId == agentId && it.isActive) it.copy(status = "running", attempt = attempt) else it
        })
    }

    @Synchronized
    fun retry(chatId: String, agentId: String, attempt: Long, reason: String) {
        val agents = state.value[chatId] ?: return
        publish(chatId, agents.map {
            if (it.agentId == agentId && it.isActive) it.copy(
                status = "retrying", attempt = attempt, lastError = reason.take(2000),
                tools = it.tools.map { tool -> if (!tool.isTerminal) tool.copy(status = "error") else tool }
            ) else it
        })
    }

    @Synchronized
    fun tool(chatId: String, agentId: String, event: SubagentToolProgress) {
        val agents = state.value[chatId] ?: return
        publish(chatId, agents.map { agent ->
            if (agent.agentId != agentId || agent.status != "running") agent else {
                val isNew = checkNotNull(seenToolIds[chatId to agentId]).add(event.id)
                val existing = agent.tools.indexOfFirst { it.id == event.id }
                // Evicted rows must neither inflate the count nor be reinserted by a late event.
                if (!isNew && existing < 0) return@map agent
                val tools = if (existing < 0) agent.tools + event else
                    agent.tools.map { if (it.id == event.id) event else it }
                agent.copy(
                    tools = tools.takeLast(64),
                    toolCallCount = if (!isNew || agent.toolCallCount == Long.MAX_VALUE)
                        agent.toolCallCount else agent.toolCallCount + 1L,
                )
            }
        })
    }

    @Synchronized
    fun finish(chatId: String, agentId: String, status: String) {
        require(status in setOf("completed", "failed", "timed_out", "cancelled"))
        val agents = state.value[chatId] ?: return
        publish(chatId, agents.map {
            // Terminal outcomes are absorbing: a late retry/finalizer cannot revive or overwrite them.
            if (it.agentId == agentId && it.isActive) terminal(it, status) else it
        })
    }

    @Synchronized
    fun clear(chatId: String) {
        rounds.remove(chatId)
        executions.remove(chatId)
        seenToolIds.keys.removeAll { it.first == chatId }
        state.value = state.value - chatId
        conversationState.value = conversationState.value - chatId
    }
}