package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.util.ChatUtils
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a token count came from.
 * ESTIMATE and LOCAL_TOKENIZER are client side guesses; only SERVER is provider reported.
 */
enum class ContextTokenSource { ESTIMATE, LOCAL_TOKENIZER, SERVER }

data class ContextTokenCount(val tokens: Long, val source: ContextTokenSource) {
    init { require(tokens >= 0) { "tokens must be non-negative" } }

    val isServerReported: Boolean get() = source == ContextTokenSource.SERVER
}

enum class ContextSectionKind {
    CHARACTER, SYSTEM, TOOL_DEFINITIONS, USER, ASSISTANT, TOOL_RESULTS, SUMMARY, OTHER
}

/**
 * Points at one recorded message without keeping its content.
 * [messageId] historically holds the message timestamp: it is NOT a unique identifier,
 * so callers must present it as a timestamp and never as a key.
 */
data class ContextMessageReference(val index: Int, val messageId: Long, val role: String) {
    init {
        require(index >= 0)
        require(role.isNotBlank() && role.length <= 64 && role.none { it.isISOControl() })
    }

    val timestamp: Long get() = messageId
}

data class ContextSection(
    val kind: ContextSectionKind,
    val count: ContextTokenCount?,
    val message: ContextMessageReference? = null
)

data class ContextWindowLimits(val modelWindowTokens: Long?, val compressionThresholdTokens: Long?) {
    init {
        require(modelWindowTokens == null || modelWindowTokens > 0)
        require(compressionThresholdTokens == null || compressionThresholdTokens > 0)
        require(modelWindowTokens == null || compressionThresholdTokens == null ||
            compressionThresholdTokens <= modelWindowTokens)
    }
}

data class ContextRequestDiagnostics(
    val sections: List<ContextSection>,
    val total: ContextTokenCount?,
    val limits: ContextWindowLimits,
    val recordedAt: Long
) {
    init { require(recordedAt >= 0) }
}

data class ContextCompressionDiagnostics(
    val reason: String,
    val before: ContextTokenCount?,
    val after: ContextTokenCount?,
    val coveredMessages: List<ContextMessageReference>,
    val recordedAt: Long
) {
    init {
        require(reason.isNotBlank() && reason.length <= 2048)
        require(recordedAt >= 0)
        require(coveredMessages.map { it.index }.distinct().size == coveredMessages.size)
    }
}

data class ContextDiagnostics(
    val request: ContextRequestDiagnostics? = null,
    val compression: ContextCompressionDiagnostics? = null
)

/**
 * Per-category rollup of a request snapshot.
 * Long histories are summarized here so the UI never renders hundreds of rows.
 * [unknownSectionCount] keeps "not recorded" distinguishable from a real zero.
 */
data class ContextSectionAggregate(
    val kind: ContextSectionKind,
    val sectionCount: Int,
    val unknownSectionCount: Int,
    val recordedTokens: Long,
    val sources: Set<ContextTokenSource>,
    val firstIndex: Int?,
    val lastIndex: Int?,
    val firstTimestamp: Long?,
    val lastTimestamp: Long?
) {
    init {
        require(sectionCount > 0)
        require(unknownSectionCount in 0..sectionCount)
        require(recordedTokens >= 0)
    }

    val isFullyRecorded: Boolean get() = unknownSectionCount == 0
    val hasServerReportedSection: Boolean get() = ContextTokenSource.SERVER in sources
}

/**
 * Compressed index span instead of an unbounded message list.
 * [roleCounts] is role -> number of covered messages.
 */
data class ContextCompressionCoverage(
    val messageCount: Int,
    val firstIndex: Int,
    val lastIndex: Int,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val roleCounts: Map<String, Int>
) {
    init {
        require(messageCount > 0)
        require(firstIndex >= 0 && lastIndex >= firstIndex)
        require(roleCounts.isNotEmpty())
        require(roleCounts.values.all { it > 0 })
    }
}

/** Groups sections by kind, preserving first-seen order. */
fun ContextRequestDiagnostics.aggregateByKind(): List<ContextSectionAggregate> {
    val grouped = LinkedHashMap<ContextSectionKind, MutableList<ContextSection>>()
    sections.forEach { grouped.getOrPut(it.kind) { mutableListOf() }.add(it) }
    val aggregates = grouped.entries.map { (kind, group) ->
        var recorded = 0L
        val sources = LinkedHashSet<ContextTokenSource>()
        var unknown = 0
        var firstIndex = -1
        var lastIndex = -1
        var firstTimestamp = 0L
        var lastTimestamp = 0L
        group.forEach { section ->
            val count = section.count
            if (count == null) {
                unknown++
            } else {
                recorded += count.tokens
                sources.add(count.source)
            }
            val reference = section.message
            if (reference != null) {
                if (firstIndex < 0 || reference.index < firstIndex) {
                    firstIndex = reference.index
                    firstTimestamp = reference.timestamp
                }
                if (reference.index > lastIndex) {
                    lastIndex = reference.index
                    lastTimestamp = reference.timestamp
                }
            }
        }
        ContextSectionAggregate(
            kind = kind,
            sectionCount = group.size,
            unknownSectionCount = unknown,
            recordedTokens = recorded,
            sources = Collections.unmodifiableSet(sources),
            firstIndex = if (firstIndex < 0) null else firstIndex,
            lastIndex = if (lastIndex < 0) null else lastIndex,
            firstTimestamp = if (firstIndex < 0) null else firstTimestamp,
            lastTimestamp = if (lastIndex < 0) null else lastTimestamp
        )
    }
    return Collections.unmodifiableList(aggregates)
}

/**
 * Collapses covered messages into a single span plus a role histogram.
 * Returns null when nothing was covered: callers must show "unknown", never a zero range.
 */
fun ContextCompressionDiagnostics.coverageRange(): ContextCompressionCoverage? {
    if (coveredMessages.isEmpty()) return null
    val ordered = coveredMessages.sortedBy { it.index }
    val head = ordered.first()
    val tail = ordered.last()
    val roleCounts = LinkedHashMap<String, Int>()
    ordered.forEach { roleCounts[it.role] = (roleCounts[it.role] ?: 0) + 1 }
    return ContextCompressionCoverage(
        messageCount = ordered.size,
        firstIndex = head.index,
        lastIndex = tail.index,
        firstTimestamp = head.timestamp,
        lastTimestamp = tail.timestamp,
        roleCounts = Collections.unmodifiableMap(roleCounts)
    )
}

/**
 * In-memory, chat scoped snapshots. No raw prompt/tool content is retained:
 * only section kind, token counts and a message index/timestamp reference are kept.
 */
object ContextDiagnosticsStore {
    private val mutableSessions = MutableStateFlow<Map<String, ContextDiagnostics>>(emptyMap())
    val sessions: StateFlow<Map<String, ContextDiagnostics>> = mutableSessions.asStateFlow()

    fun read(chatId: String): ContextDiagnostics? {
        validateChatId(chatId)
        return sessions.value[chatId]
    }

    @Synchronized
    fun recordRequest(
        chatId: String,
        sections: List<ContextSection>,
        limits: ContextWindowLimits = ContextWindowLimits(null, null),
        total: ContextTokenCount? = null,
        recordedAt: Long = System.currentTimeMillis()
    ) {
        validateChatId(chatId)
        require(recordedAt >= 0)
        val snapshot = ContextRequestDiagnostics(
            Collections.unmodifiableList(ArrayList(sections)),
            total,
            limits,
            recordedAt
        )
        val previous = mutableSessions.value[chatId]
        publish(chatId, ContextDiagnostics(snapshot, previous?.compression))
    }

    /**
     * Text only estimates, not wire totals. A null estimator explicitly records unknown counts
     * instead of guessing zero. Split CHARACTER and SYSTEM sections at the prompt builder by
     * calling the sections overload. A provider calculateInputTokens may also only estimate,
     * so callers must never label it SERVER on their own.
     */
    fun recordRequest(
        chatId: String,
        history: List<ChatMessage>,
        tools: String? = null,
        limits: ContextWindowLimits = ContextWindowLimits(null, null),
        total: ContextTokenCount? = null,
        estimator: ((String) -> Long)? = ChatUtils::estimateTokenCount,
        recordedAt: Long = System.currentTimeMillis()
    ) {
        validateChatId(chatId)
        val sections = history.mapIndexed { index, message ->
            val kind = when (message.sender) {
                "system" -> ContextSectionKind.SYSTEM
                "user" -> ContextSectionKind.USER
                "ai", "assistant" -> ContextSectionKind.ASSISTANT
                "tool" -> ContextSectionKind.TOOL_RESULTS
                "summary" -> ContextSectionKind.SUMMARY
                else -> ContextSectionKind.OTHER
            }
            ContextSection(
                kind = kind,
                count = estimator?.let {
                    ContextTokenCount(it(message.content), ContextTokenSource.ESTIMATE)
                },
                message = ContextMessageReference(index, message.timestamp, message.sender)
            )
        }.toMutableList()
        if (tools != null) {
            sections += ContextSection(
                kind = ContextSectionKind.TOOL_DEFINITIONS,
                count = estimator?.let { ContextTokenCount(it(tools), ContextTokenSource.ESTIMATE) }
            )
        }
        recordRequest(chatId, sections, limits, total, recordedAt)
    }

    @Synchronized
    fun recordCompression(
        chatId: String,
        reason: String,
        before: ContextTokenCount?,
        after: ContextTokenCount?,
        coveredMessages: List<ContextMessageReference>,
        recordedAt: Long = System.currentTimeMillis()
    ) {
        validateChatId(chatId)
        val compression = ContextCompressionDiagnostics(
            reason,
            before,
            after,
            Collections.unmodifiableList(ArrayList(coveredMessages)),
            recordedAt
        )
        publish(chatId, ContextDiagnostics(mutableSessions.value[chatId]?.request, compression))
    }

    @Synchronized
    fun clear(chatId: String) {
        validateChatId(chatId)
        mutableSessions.value = Collections.unmodifiableMap(mutableSessions.value - chatId)
    }

    private fun publish(chatId: String, diagnostics: ContextDiagnostics) {
        mutableSessions.value = Collections.unmodifiableMap(
            mutableSessions.value + (chatId to diagnostics)
        )
    }

    private fun validateChatId(chatId: String) {
        require(chatId.isNotBlank() && chatId.length <= 512 && chatId.none { it.isISOControl() })
    }
}