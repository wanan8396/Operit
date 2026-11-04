package com.ai.assistance.operit.ui.features.chat.components

/** One explicit mapping for the conversation indicator, also exercised by JVM tests. */
internal enum class SubagentConversationStatus {
    RUNNING, RETRYING, COMPLETED, FAILED, TIMED_OUT, CANCELLED;

    val isActive: Boolean get() = this == RUNNING || this == RETRYING

    companion object {
        fun fromStatus(status: String): SubagentConversationStatus = when (status) {
            "running" -> RUNNING
            "retrying" -> RETRYING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "timed_out" -> TIMED_OUT
            "cancelled" -> CANCELLED
            else -> error("Unknown subagent status: $status")
        }
    }
}

/** Keep all rows of an active round; hide the entire round after its last agent stops. */
internal fun visibleHeaderSubagents(agents: List<com.ai.assistance.operit.data.stats.SubagentProgress>): List<com.ai.assistance.operit.data.stats.SubagentProgress> =
    if (agents.any { SubagentConversationStatus.fromStatus(it.status).isActive }) agents else emptyList()
