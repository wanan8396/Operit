package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.stats.ContextSectionKind
import com.ai.assistance.operit.data.stats.ContextTokenSource
import org.junit.Assert.*
import org.junit.Test

class ContextDiagnosticsBuilderTest {
    @Test fun attributesOnlyRecordedTextAndNeverInventsMessageIds() {
        val turns = listOf(
            PromptTurn(PromptTurnKind.SYSTEM, "base<assistant_role source=\"character_card\">role</assistant_role>tail<tool_definitions>tools</tool_definitions>"),
            PromptTurn(PromptTurnKind.USER, "question"),
            PromptTurn(PromptTurnKind.ASSISTANT, "answer"),
            PromptTurn(PromptTurnKind.TOOL_CALL, "call"),
            PromptTurn(PromptTurnKind.TOOL_RESULT, "output"),
            PromptTurn(PromptTurnKind.SUMMARY, "summary")
        )
        val sections = ContextDiagnosticsBuilder.sections(turns, listOf(ToolPrompt("read_plan", "read")))
        assertEquals(1, sections.count { it.kind == ContextSectionKind.CHARACTER })
        assertEquals(2, sections.count { it.kind == ContextSectionKind.TOOL_DEFINITIONS })
        assertEquals(2, sections.count { it.kind == ContextSectionKind.ASSISTANT })
        assertEquals(1, sections.count { it.kind == ContextSectionKind.TOOL_RESULTS })
        assertEquals(1, sections.count { it.kind == ContextSectionKind.SUMMARY })
        assertTrue(sections.all { it.message == null })
        assertTrue(sections.all { it.count?.source == ContextTokenSource.ESTIMATE })
    }

    @Test fun unmarkedCustomSystemIsNotGuessedToBeACharacter() {
        val sections = ContextDiagnosticsBuilder.sections(listOf(PromptTurn(PromptTurnKind.SYSTEM, "custom prompt")), null)
        assertEquals(ContextSectionKind.SYSTEM, sections.single().kind)
    }

    @Test fun compressionIncludesCarriedSummaryAndOnlyNewEligibleMessages() {
        val history = listOf(
            ChatMessage("user", "old", 1),
            ChatMessage("summary", "carried", 2),
            ChatMessage("system", "hidden", 3),
            ChatMessage("user", "new user", 4),
            ChatMessage("ai", "new answer", 4),
            ChatMessage("tool", "not selected by summary", 5)
        )
        val result = ContextDiagnosticsBuilder.compression("manual", history, ChatMessage("summary", "new summary", 6), 100)
        assertEquals(listOf(1, 3, 4), result.coveredMessages.map { it.index })
        assertEquals(listOf(2L, 4L, 4L), result.coveredMessages.map { it.timestamp })
        assertEquals(ContextTokenSource.ESTIMATE, result.before?.source)
        assertEquals(ContextDiagnosticsBuilder.estimate("new summary"), result.after)
        assertEquals(100L, result.recordedAt)
    }

    @Test fun compressionWithoutPreviousSummaryUsesOnlyEligibleInputSnapshot() {
        // An anchored manual insertion passes a selected snapshot, not the whole chat.
        val selected = listOf(
            ChatMessage("system", "hidden", 1),
            ChatMessage("user", "selected question", 2),
            ChatMessage("ai", "selected answer", 2),
            ChatMessage("tool", "hidden result", 3)
        )
        val result = ContextDiagnosticsBuilder.compression("manual", selected,
            ChatMessage("summary", "selected summary", 4), 100)
        assertEquals(listOf(1, 2), result.coveredMessages.map { it.index })
        assertEquals(listOf(2L, 2L), result.coveredMessages.map { it.timestamp })
        assertEquals(ContextDiagnosticsBuilder.estimate("selected question").tokens +
            ContextDiagnosticsBuilder.estimate("selected answer").tokens, result.before?.tokens)
    }

    @Test fun emptyCompressionSnapshotDoesNotInventCoverage() {
        val result = ContextDiagnosticsBuilder.compression("manual", emptyList(),
            ChatMessage("summary", "summary", 4), 100)
        assertTrue(result.coveredMessages.isEmpty())
        assertEquals(0L, result.before?.tokens)
    }

    @Test fun disabledAndInvalidThresholdsAreUnknownNotZero() {
        assertNull(ContextDiagnosticsBuilder.limits(0, 0.8).modelWindowTokens)
        assertNull(ContextDiagnosticsBuilder.limits(1000, null).compressionThresholdTokens)
        assertNull(ContextDiagnosticsBuilder.limits(1000, 1.5).compressionThresholdTokens)
        assertEquals(801L, ContextDiagnosticsBuilder.limits(1001, 0.8).compressionThresholdTokens)
    }
}