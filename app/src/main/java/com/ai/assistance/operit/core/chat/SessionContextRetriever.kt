package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.stats.ContextMessageReference
import com.ai.assistance.operit.util.ChatMarkupRegex
import java.util.Locale

/** RELEVANT is deterministic keyword matching, not semantic/vector retrieval. */
enum class SessionContextStrategy { RELEVANT, HANDOFF }

data class SessionContextQuery(
    val strategy: SessionContextStrategy,
    val query: String = "",
    val budgetChars: Int = 8000,
    val maxMessages: Int = 20,
    val maxMessageChars: Int = 2000
) {
    init {
        require(query.length <= 2048 && query.none { it.isISOControl() }) { "query must be at most 2048 characters without control characters" }
        require(strategy != SessionContextStrategy.RELEVANT || query.isNotBlank()) { "relevant requires a nonblank query" }
        require(budgetChars in 1..65536) { "Invalid excerpt budget" }
        require(maxMessages in 1..200) { "Invalid message limit" }
        require(maxMessageChars in 1..65536) { "Invalid per-message budget" }
    }
}

data class SessionContextExcerpt(
    val reference: ContextMessageReference,
    val content: String,
    val truncated: Boolean
)

data class SessionContextResult(
    val excerpts: List<SessionContextExcerpt>,
    val selectedCount: Int,
    val truncated: Boolean,
    /** Includes index/timestamp/role headings and separators; UTF-16 length <= budgetChars. */
    val text: String
)

/** Caller supplies one complete ordered persisted snapshot; never renumber after filtering. */
object SessionContextRetriever {
    private val visibleRoles = setOf("user", "ai", "assistant", "summary")
    private val hiddenTag = Regex(
        """<(/?)(think(?:ing)?|meta|system(?:[_-]reminder)?|assistant_role|authoritative_plan_state|search|status|${ChatMarkupRegex.TOOL_RESULT_TAG_NAME_REGEX_SOURCE}|${ChatMarkupRegex.TOOL_TAG_NAME_REGEX_SOURCE})\b[^>]*(?:>|\z)""",
        RegexOption.IGNORE_CASE
    )

    fun extract(history: List<ChatMessage>, request: SessionContextQuery): SessionContextResult {
        require(history.size <= 100000) { "history exceeds extraction limit" }
        val terms = request.query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }
        val candidates = history.mapIndexedNotNull { index, message ->
            if (message.sender !in visibleRoles) return@mapIndexedNotNull null
            val content = visibleContent(message.content)
            if (content.isBlank()) return@mapIndexedNotNull null
            val matches = if (request.strategy == SessionContextStrategy.RELEVANT) {
                terms.map { content.indexOf(it, ignoreCase = true) }.filter { it >= 0 }
            } else emptyList()
            if (request.strategy == SessionContextStrategy.RELEVANT && matches.isEmpty()) return@mapIndexedNotNull null
            Candidate(ContextMessageReference(index, message.timestamp, message.sender), content,
                matches.size, matches.minOrNull())
        }
        val ranked = when (request.strategy) {
            SessionContextStrategy.RELEVANT -> candidates.sortedWith(
                compareByDescending<Candidate> { it.score }.thenByDescending { it.reference.index })
            SessionContextStrategy.HANDOFF -> candidates.sortedByDescending { it.reference.index }
        }
        val chosen = mutableListOf<SessionContextExcerpt>()
        var used = 0
        for (candidate in ranked.take(request.maxMessages)) {
            val overhead = heading(candidate.reference).length + if (chosen.isEmpty()) 0 else 2
            val available = minOf(request.budgetChars - used - overhead, request.maxMessageChars)
            if (available <= 0) break
            var start = if (candidate.firstMatch == null) 0 else (candidate.firstMatch - available / 4).coerceAtLeast(0)
            if (start > 0 && candidate.content[start].isLowSurrogate() && candidate.content[start - 1].isHighSurrogate()) start--
            var end = minOf(start + available, candidate.content.length)
            if (end > start && end < candidate.content.length &&
                candidate.content[end - 1].isHighSurrogate() && candidate.content[end].isLowSurrogate()) end--
            if (end == start) break
            val content = candidate.content.substring(start, end)
            chosen += SessionContextExcerpt(candidate.reference, content, start > 0 || end < candidate.content.length)
            used += overhead + content.length
        }
        val ordered = chosen.sortedBy { it.reference.index }
        val text = ordered.joinToString("\n\n") { heading(it.reference) + it.content }
        check(text.length <= request.budgetChars)
        return SessionContextResult(ordered, ordered.size,
            ordered.size < candidates.size || ordered.any { it.truncated }, text)
    }

    private fun heading(reference: ContextMessageReference): String =
        "[index=${reference.index} timestamp=${reference.timestamp} role=${reference.role}]\n"

    /** Stack-based redaction handles nested and incomplete blocks without exposing their tails. */
    internal fun visibleContent(raw: String): String {
        val result = StringBuilder()
        val stack = mutableListOf<String>()
        var cursor = 0
        hiddenTag.findAll(raw).forEach { tag ->
            val closing = tag.groupValues[1] == "/"
            val name = tag.groupValues[2].lowercase(Locale.ROOT)
            val selfClosing = tag.value.endsWith("/>")
            if (stack.isEmpty()) result.append(raw, cursor, tag.range.first)
            if (!closing && !selfClosing) {
                stack.add(name)
            } else if (closing && stack.lastOrNull() == name) {
                stack.removeAt(stack.lastIndex)
            }
            cursor = tag.range.last + 1
        }
        if (stack.isEmpty()) result.append(raw, cursor, raw.length)
        return result.toString().trim()
    }

    private data class Candidate(
        val reference: ContextMessageReference,
        val content: String,
        val score: Int,
        val firstMatch: Int?
    )
}
