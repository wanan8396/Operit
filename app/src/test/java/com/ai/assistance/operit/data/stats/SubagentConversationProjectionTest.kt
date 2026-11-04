package com.ai.assistance.operit.data.stats

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentConversationProjectionTest {
    @Test
    fun allTerminalOutcomesReleaseConversationButRemainInInputDetails() {
        listOf("completed", "failed", "timed_out", "cancelled").forEach { terminal ->
            val chatId = UUID.randomUUID().toString()
            try {
                val round = SubagentProgressStore.beginRound(chatId)
                SubagentProgressStore.start(chatId, SubagentProgress("a", "Explore", "Inspect"), round)
                SubagentProgressStore.start(chatId, SubagentProgress("b", "Explore", "Verify"), round)
                SubagentProgressStore.finish(chatId, "a", terminal)
                assertEquals(2, SubagentProgressStore.conversationSessions.value.getValue(chatId).size)
                SubagentProgressStore.retry(chatId, "b", 1L, "retry")
                assertEquals("retrying", SubagentProgressStore.conversationSessions.value.getValue(chatId).last().status)
                SubagentProgressStore.beginAttempt(chatId, "b", 2L)
                SubagentProgressStore.finish(chatId, "b", terminal)
                assertFalse(SubagentProgressStore.conversationSessions.value.containsKey(chatId))
                val details = SubagentProgressStore.sessions.value.getValue(chatId)
                assertEquals(2, details.size)
                assertEquals(terminal, details.last().status)
                assertEquals(1L, details.last().attempt - 1L)
                SubagentProgressStore.beginRound(chatId)
                assertTrue(SubagentProgressStore.sessions.value.getValue(chatId).isEmpty())
                SubagentProgressStore.start(chatId, SubagentProgress("late", "Explore", "Old round"), round)
                assertTrue(SubagentProgressStore.sessions.value.getValue(chatId).isEmpty())
            } finally {
                SubagentProgressStore.clear(chatId)
                assertFalse(SubagentProgressStore.conversationSessions.value.containsKey(chatId))
                assertFalse(SubagentProgressStore.sessions.value.containsKey(chatId))
            }
        }
    }
}