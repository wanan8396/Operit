package com.ai.assistance.operit.data.stats

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentProgressStoreTest {
    @Test
    fun isolatesSessionsAndUpdatesToolLifecycle() {
        val firstChat = UUID.randomUUID().toString()
        val secondChat = UUID.randomUUID().toString()
        val agent = SubagentProgress("agent-1", "Explore", "Inspect files")
        try {
            SubagentProgressStore.start(firstChat, agent)
            SubagentProgressStore.start(secondChat, agent.copy(agentId = "agent-2"))
            SubagentProgressStore.tool(
                firstChat,
                "agent-1",
                SubagentToolProgress("tool-1", "read_file", "started")
            )
            SubagentProgressStore.tool(
                firstChat,
                "agent-1",
                SubagentToolProgress("tool-1", "read_file", "result")
            )
            SubagentProgressStore.finish(firstChat, "agent-1", "completed")

            val first = SubagentProgressStore.sessions.value.getValue(firstChat).single()
            val second = SubagentProgressStore.sessions.value.getValue(secondChat).single()
            assertEquals("completed", first.status)
            assertEquals(listOf(SubagentToolProgress("tool-1", "read_file", "result")), first.tools)
            assertEquals("running", second.status)
            assertTrue(second.tools.isEmpty())
        } finally {
            SubagentProgressStore.clear(firstChat)
            SubagentProgressStore.clear(secondChat)
        }
    }

    @Test fun cancellationFinishesToolsAndLateEventsDoNotRestoreClearedSession() {
        val chatId = UUID.randomUUID().toString()
        try {
            SubagentProgressStore.start(chatId, SubagentProgress("agent", "Explore", "Inspect"))
            SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("tool", "read_file", "started"))
            SubagentProgressStore.finish(chatId, "agent", "cancelled")
            val agent = SubagentProgressStore.sessions.value.getValue(chatId).single()
            assertEquals("cancelled", agent.status)
            assertEquals("cancelled", agent.tools.single().status)
            SubagentProgressStore.clear(chatId)
            SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("tool", "read_file", "result"))
            SubagentProgressStore.finish(chatId, "agent", "completed")
            assertTrue(chatId !in SubagentProgressStore.sessions.value)
        } finally {
            SubagentProgressStore.clear(chatId)
        }
    }

    @Test
    fun finishClosesPendingToolsAndIgnoresLateToolEvents() {
        for (status in listOf("completed", "failed", "timed_out", "cancelled")) {
            val chatId = UUID.randomUUID().toString()
            try {
                SubagentProgressStore.start(chatId, SubagentProgress("agent", "Explore", "Inspect"))
                SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("tool", "read_file", "scheduled"))
                SubagentProgressStore.finish(chatId, "agent", status)
                val finished = SubagentProgressStore.sessions.value.getValue(chatId).single()
                assertEquals(status, finished.status)
                assertTrue(finished.tools.single().isTerminal)
                assertEquals(if (status == "completed") "error" else status, finished.tools.single().status)
                SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("tool", "read_file", "started"))
                assertEquals(finished, SubagentProgressStore.sessions.value.getValue(chatId).single())
            } finally {
                SubagentProgressStore.clear(chatId)
            }
        }
        assertTrue(!SubagentToolProgress("tool", "read_file", "started").isTerminal)
        assertTrue(!SubagentToolProgress("tool", "read_file", "scheduled").isTerminal)
    }

    @Test
    fun newRoundReplacesOldCardsAndKeepsEveryChildInTheBatch() {
        val chatId = UUID.randomUUID().toString()
        val otherChatId = UUID.randomUUID().toString()
        try {
            val oldRound = SubagentProgressStore.beginRound(chatId)
            SubagentProgressStore.start(chatId, SubagentProgress("old", "Explore", "Old task"), oldRound)
            SubagentProgressStore.finish(chatId, "old", "failed")
            val otherRound = SubagentProgressStore.beginRound(otherChatId)
            SubagentProgressStore.start(otherChatId, SubagentProgress("other", "Explore", "Other chat"), otherRound)
            val newRound = SubagentProgressStore.beginRound(chatId)
            assertTrue(SubagentProgressStore.sessions.value.getValue(chatId).isEmpty())
            SubagentProgressStore.start(chatId, SubagentProgress("first", "Explore", "First"), newRound)
            SubagentProgressStore.finish(chatId, "first", "completed")
            SubagentProgressStore.start(chatId, SubagentProgress("second", "Explore", "Second"), newRound)
            assertEquals(listOf("first", "second"), SubagentProgressStore.sessions.value.getValue(chatId).map { it.agentId })
            assertEquals("other", SubagentProgressStore.sessions.value.getValue(otherChatId).single().agentId)
            // Late starts, tool events and finalizers from the old round cannot restore its cards.
            SubagentProgressStore.start(chatId, SubagentProgress("late", "Explore", "Late old child"), oldRound)
            SubagentProgressStore.tool(chatId, "old", SubagentToolProgress("late-tool", "read_file", "result"))
            SubagentProgressStore.finish(chatId, "old", "completed")
            assertEquals(listOf("first", "second"), SubagentProgressStore.sessions.value.getValue(chatId).map { it.agentId })
            SubagentProgressStore.clear(chatId)
            SubagentProgressStore.start(chatId, SubagentProgress("late-new", "Explore", "After clear"), newRound)
            assertTrue(chatId !in SubagentProgressStore.sessions.value)
        } finally {
            SubagentProgressStore.clear(chatId)
            SubagentProgressStore.clear(otherChatId)
        }
    }

    @Test
    fun retryUpdatesOneCardAndCannotRestoreSupersededCards() {
        val chatId = UUID.randomUUID().toString()
        try {
            val round = SubagentProgressStore.beginRound(chatId)
            SubagentProgressStore.start(chatId, SubagentProgress("agent", "Explore", "Task"), round)
            SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("tool", "read_file", "started"))
            SubagentProgressStore.retry(chatId, "agent", 1L, "connection failed")
            val retry = SubagentProgressStore.sessions.value.getValue(chatId).single()
            assertEquals("retrying", retry.status)
            assertEquals("connection failed", retry.lastError)
            assertTrue(retry.tools.single().isTerminal)
            SubagentProgressStore.beginAttempt(chatId, "agent", 2L)
            val next = SubagentProgressStore.sessions.value.getValue(chatId).single()
            assertEquals("running", next.status)
            assertEquals(2L, next.attempt)
            assertEquals(retry.tools, next.tools)
            assertEquals(1L, next.toolCallCount)
            SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("second", "read_file", "scheduled"))
            SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("second", "read_file", "started"))
            SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("second", "read_file", "result"))
            assertEquals(2L, SubagentProgressStore.sessions.value.getValue(chatId).single().toolCallCount)
            SubagentProgressStore.beginRound(chatId)
            SubagentProgressStore.retry(chatId, "agent", 2L, "late failure")
            SubagentProgressStore.beginAttempt(chatId, "agent", 3L)
            assertTrue(SubagentProgressStore.sessions.value.getValue(chatId).isEmpty())
        } finally { SubagentProgressStore.clear(chatId) }
    }

    @Test
    fun terminalStatusCannotBeChangedByLateAttemptRetryOrFinish() {
        for (status in listOf("completed", "failed", "timed_out", "cancelled")) {
            val chatId = UUID.randomUUID().toString()
            try {
                val round = SubagentProgressStore.beginRound(chatId)
                SubagentProgressStore.start(chatId, SubagentProgress("agent", "Explore", "Inspect"), round)
                SubagentProgressStore.finish(chatId, "agent", status)
                val terminal = SubagentProgressStore.sessions.value.getValue(chatId)
                SubagentProgressStore.retry(chatId, "agent", 9L, "late disconnect")
                SubagentProgressStore.beginAttempt(chatId, "agent", 10L)
                SubagentProgressStore.finish(chatId, "agent", "completed")
                assertEquals(terminal, SubagentProgressStore.sessions.value.getValue(chatId))
                assertTrue(chatId !in SubagentProgressStore.conversationSessions.value)
            } finally { SubagentProgressStore.clear(chatId) }
        }
    }

    @Test
    fun boundsToolHistory() {
        val chatId = UUID.randomUUID().toString()
        try {
            SubagentProgressStore.start(chatId, SubagentProgress("agent", "general-purpose", "Test"))
            repeat(70) { index ->
                SubagentProgressStore.tool(
                    chatId,
                    "agent",
                    SubagentToolProgress("tool-$index", "read_file", "result")
                )
            }
            assertEquals(70L, SubagentProgressStore.sessions.value.getValue(chatId).single().toolCallCount)
            SubagentProgressStore.tool(chatId, "agent", SubagentToolProgress("tool-0", "read_file", "result"))
            assertEquals(70L, SubagentProgressStore.sessions.value.getValue(chatId).single().toolCallCount)
            SubagentProgressStore.retry(chatId, "agent", 1L, "interrupted")
            SubagentProgressStore.beginAttempt(chatId, "agent", 2L)
            assertEquals(70L, SubagentProgressStore.sessions.value.getValue(chatId).single().toolCallCount)
            assertEquals(64, SubagentProgressStore.sessions.value.getValue(chatId).single().tools.size)
            assertEquals("tool-69", SubagentProgressStore.sessions.value.getValue(chatId).single().tools.last().id)
        } finally {
            SubagentProgressStore.clear(chatId)
        }
    }
}
