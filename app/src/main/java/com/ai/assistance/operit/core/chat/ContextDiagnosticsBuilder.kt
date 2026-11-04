package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ContextCompressionDiagnostics
import com.ai.assistance.operit.data.stats.ContextMessageReference
import com.ai.assistance.operit.data.stats.ContextSection
import com.ai.assistance.operit.data.stats.ContextSectionKind
import com.ai.assistance.operit.data.stats.ContextTokenCount
import com.ai.assistance.operit.data.stats.ContextTokenSource
import com.ai.assistance.operit.data.stats.ContextWindowLimits
import com.ai.assistance.operit.util.ChatUtils
import kotlin.math.ceil

/** Text attribution only: protocol/media overhead and provider tokenization can differ. */
internal object ContextDiagnosticsBuilder {
    private val taggedSystemSection = Regex(
        """<(assistant_role|tool_definitions)\b[^>]*>.*?</\1\s*>""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun estimate(text: String) = ContextTokenCount(
        ChatUtils.estimateTokenCount(text), ContextTokenSource.ESTIMATE
    )

    fun sections(history: List<PromptTurn>, tools: List<ToolPrompt>?): List<ContextSection> {
        val sections = mutableListOf<ContextSection>()
        history.forEach { turn ->
            if (turn.kind == PromptTurnKind.SYSTEM) {
                var cursor = 0
                taggedSystemSection.findAll(turn.content).forEach { match ->
                    if (match.range.first > cursor) {
                        sections.add(ContextSection(ContextSectionKind.SYSTEM,
                            estimate(turn.content.substring(cursor, match.range.first))))
                    }
                    val kind = when (match.groupValues[1]) {
                        "assistant_role" -> ContextSectionKind.CHARACTER
                        "tool_definitions" -> ContextSectionKind.TOOL_DEFINITIONS
                        else -> error("Unexpected prompt section")
                    }
                    sections.add(ContextSection(kind, estimate(match.value)))
                    cursor = match.range.last + 1
                }
                if (cursor < turn.content.length) {
                    sections.add(ContextSection(ContextSectionKind.SYSTEM,
                        estimate(turn.content.substring(cursor))))
                }
            } else {
                val kind = when (turn.kind) {
                    PromptTurnKind.USER -> ContextSectionKind.USER
                    PromptTurnKind.ASSISTANT, PromptTurnKind.TOOL_CALL -> ContextSectionKind.ASSISTANT
                    PromptTurnKind.TOOL_RESULT -> ContextSectionKind.TOOL_RESULTS
                    PromptTurnKind.SUMMARY -> ContextSectionKind.SUMMARY
                    PromptTurnKind.SYSTEM -> error("System turns are attributed separately")
                }
                // Prepared turns have no stable persisted-message identity. Do not invent one.
                sections.add(ContextSection(kind, estimate(turn.content)))
            }
        }
        tools?.forEach { tool ->
            sections.add(ContextSection(ContextSectionKind.TOOL_DEFINITIONS, estimate(tool.toString())))
        }
        return sections
    }

    fun limits(windowTokens: Int, threshold: Double?): ContextWindowLimits {
        val window = windowTokens.takeIf { it > 0 }?.toLong()
        val boundary = if (window != null && threshold != null && threshold > 0.0 && threshold <= 1.0) {
            ceil(window * threshold).toLong()
        } else null
        return ContextWindowLimits(window, boundary)
    }

    /** Same source selection as AIMessageManager.summarizeMemory, including the carried summary. */
    fun compression(
        reason: String,
        history: List<ChatMessage>,
        summary: ChatMessage,
        recordedAt: Long = System.currentTimeMillis()
    ): ContextCompressionDiagnostics {
        val lastSummaryIndex = history.indexOfLast { it.sender == "summary" }
        val covered = history.withIndex().filter { (index, message) ->
            index == lastSummaryIndex ||
                (index > lastSummaryIndex && (message.sender == "user" || message.sender == "ai"))
        }
        // These two counts describe the replaced source text and the resulting summary, not
        // two model request totals: the summary builder also condenses tool/media payloads.
        return ContextCompressionDiagnostics(
            reason = reason,
            before = ContextTokenCount(covered.sumOf { estimate(it.value.content).tokens }, ContextTokenSource.ESTIMATE),
            after = estimate(summary.content),
            coveredMessages = covered.map { (index, message) ->
                ContextMessageReference(index, message.timestamp, message.sender)
            },
            recordedAt = recordedAt
        )
    }
}
