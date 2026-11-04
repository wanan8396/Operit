package com.ai.assistance.operit.core.chat

/** The summary covers all user/assistant messages in its snapshot, including a trailing user turn. */
internal fun summaryInsertionPosition(senders: List<String>): Int =
    senders.indexOfLast { it == "user" || it == "ai" } + 1

internal fun summaryContextWindowTokens(
    contextLength: Float,
    maxContextLength: Float,
    enableMaxContextMode: Boolean,
): Int {
    val length = if (enableMaxContextMode) maxContextLength else contextLength
    return (length.toDouble() * 1024).toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** Resume only when the rebuilt request is below the same model's summarization boundary. */
internal fun canContinueAfterSummary(tokens: Long, maxTokens: Int, threshold: Double): Boolean =
    maxTokens <= 0 || tokens.toDouble() / maxTokens.toDouble() < threshold
