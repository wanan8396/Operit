package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.data.model.ChatMessage
import org.junit.Assert.*
import org.junit.Test

class SessionContextRetrieverTest {
    @Test fun relevantResultsRespectBudgetAndKeepSourceIndexes() {
        val history = listOf(ChatMessage("user", "unrelated", 10), ChatMessage("ai", "needle answer", 11))
        val result = SessionContextRetriever.extract(history, SessionContextQuery(SessionContextStrategy.RELEVANT, "needle", 80))
        assertEquals(listOf(1), result.excerpts.map { it.reference.index })
        assertEquals(11L, result.excerpts.single().reference.timestamp)
        assertTrue(result.text.length <= 80)
    }
    @Test fun hiddenRolesThinkingAndToolBlocksAreExcluded() {
        val history = listOf(ChatMessage("system", "secret needle", 1), ChatMessage("ai", "visible <think>secret needle</think> okay <tool name=\"x\">private</tool>", 2))
        val result = SessionContextRetriever.extract(history, SessionContextQuery(SessionContextStrategy.HANDOFF, budgetChars = 1000))
        assertFalse(result.text.contains("secret"))
        assertFalse(result.text.contains("private"))
        assertTrue(result.text.contains("visible"))
    }
    @Test fun nestedAndUnfinishedProtocolBlocksNeverLeakTheirTails() {
        val samples = listOf(
            "visible <think>outer<thinking>nested</thinking> secret</think> done" to "visible  done",
            "visible <tool_result><tool>nested</tool> private</tool_result> done" to "visible  done",
            "visible <THINKING mode=\"hidden\">unfinished secret" to "visible",
            "visible <meta provider=\"openai:responses_reasoning\">secret</meta> done" to "visible  done",
            "visible <system-reminder>secret</system-reminder> done" to "visible  done",
            "visible <tool name=\"unfinished\"" to "visible",
            "visible <status type=\"done\"/> done" to "visible  done"
        )
        samples.forEach { (input, expected) ->
            assertEquals(expected, SessionContextRetriever.visibleContent(input))
        }
    }

    @Test fun handoffUsesRecentMessagesButRendersInSourceOrder() {
        val history = (0 until 8).map { ChatMessage("user", "entry-$it", 1) }
        val result = SessionContextRetriever.extract(history,
            SessionContextQuery(SessionContextStrategy.HANDOFF, budgetChars = 2000, maxMessages = 3))
        assertEquals(listOf(5, 6, 7), result.excerpts.map { it.reference.index })
        assertEquals(listOf(1L, 1L, 1L), result.excerpts.map { it.reference.timestamp })
        assertTrue(result.truncated)
        assertTrue(result.text.contains("timestamp=1"))
        assertFalse(result.text.contains(" id="))
    }

    @Test fun relevantWindowIncludesAMatchAtTheEndOfALongMessage() {
        val history = listOf(ChatMessage("ai", "prefix ".repeat(3000) + "needle suffix", 1))
        val result = SessionContextRetriever.extract(history,
            SessionContextQuery(SessionContextStrategy.RELEVANT, "needle", 200, maxMessageChars = 80))
        assertTrue(result.text.contains("needle"))
        assertTrue(result.excerpts.single().truncated)
    }

    @Test fun tinyBudgetsAndEmojiDoNotCreateBrokenSurrogates() {
        val history = listOf(ChatMessage("user", "😀".repeat(100), 1))
        for (budget in 1..100) {
            val result = SessionContextRetriever.extract(history,
                SessionContextQuery(SessionContextStrategy.HANDOFF, budgetChars = budget))
            assertTrue(result.text.length <= budget)
            result.excerpts.forEach { assertFalse(it.content.last().isHighSurrogate()) }
        }
    }

    @Test fun noKeywordMatchIsEmptyRatherThanUnrelatedHistory() {
        val result = SessionContextRetriever.extract(listOf(ChatMessage("user", "hello", 1)),
            SessionContextQuery(SessionContextStrategy.RELEVANT, "missing"))
        assertEquals(0, result.selectedCount)
        assertEquals("", result.text)
        assertFalse(result.truncated)
    }

    @Test fun relevantRequiresQueryAndBoundsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { SessionContextQuery(SessionContextStrategy.RELEVANT) }
        assertThrows(IllegalArgumentException::class.java) { SessionContextQuery(SessionContextStrategy.HANDOFF, budgetChars = 0) }
    }
}
