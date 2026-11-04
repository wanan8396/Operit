package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.api.chat.enhance.ToolExecutionScope
import com.ai.assistance.operit.core.tools.defaultTool.standard.SubagentRetryRunner
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubagentParentLifecycleTest {
    @Test
    fun disconnectedParentTerminatesOnlyItsActiveChildrenAndPendingTools() {
        val chat = UUID.randomUUID().toString()
        val otherChat = UUID.randomUUID().toString()
        try {
            val execution = SubagentProgressStore.beginExecution(chat)
            val round = SubagentProgressStore.beginRound(chat, execution)
            SubagentProgressStore.start(chat, SubagentProgress("done", "Explore", "Done"), round)
            SubagentProgressStore.finish(chat, "done", "completed")
            SubagentProgressStore.start(chat, SubagentProgress("live", "Explore", "Read"), round)
            SubagentProgressStore.tool(chat, "live", SubagentToolProgress("pending", "read_file", "started"))
            SubagentProgressStore.start(chat, SubagentProgress("retry", "Explore", "Retry"), round)
            SubagentProgressStore.retry(chat, "retry", 1L, "connection reset")
            val otherExecution = SubagentProgressStore.beginExecution(otherChat)
            val otherRound = SubagentProgressStore.beginRound(otherChat, otherExecution)
            SubagentProgressStore.start(otherChat, SubagentProgress("other", "Explore", "Independent"), otherRound)

            SubagentProgressStore.endExecution(chat, execution, "failed")

            val agents = SubagentProgressStore.sessions.value.getValue(chat)
            assertEquals(listOf("completed", "failed", "failed"), agents.map { it.status })
            assertEquals("failed", agents[1].tools.single().status)
            assertFalse(chat in SubagentProgressStore.conversationSessions.value)
            assertTrue(otherChat in SubagentProgressStore.conversationSessions.value)
        } finally {
            SubagentProgressStore.clear(chat)
            SubagentProgressStore.clear(otherChat)
        }
    }

    @Test
    fun stopInvalidatesPendingStartsAndLateRetryCannotReviveCancelledChild() {
        val chat = UUID.randomUUID().toString()
        try {
            val execution = SubagentProgressStore.beginExecution(chat)
            val round = SubagentProgressStore.beginRound(chat, execution)
            SubagentProgressStore.start(chat, SubagentProgress("live", "Explore", "Inspect"), round)
            SubagentProgressStore.endExecution(chat, execution)
            val stopped = SubagentProgressStore.sessions.value.getValue(chat)

            SubagentProgressStore.retry(chat, "live", 1L, "late failure")
            SubagentProgressStore.beginAttempt(chat, "live", 2L)
            SubagentProgressStore.tool(chat, "live", SubagentToolProgress("late", "read_file", "started"))
            SubagentProgressStore.finish(chat, "live", "completed")
            SubagentProgressStore.start(chat, SubagentProgress("late-start", "Explore", "Delayed"), round)
            SubagentProgressStore.endExecution(chat, execution, "failed")

            assertEquals(stopped, SubagentProgressStore.sessions.value.getValue(chat))
            assertFalse(chat in SubagentProgressStore.conversationSessions.value)
            try {
                SubagentProgressStore.beginRound(chat, execution)
                fail("An ended parent must not start another delegation round")
            } catch (_: CancellationException) {
                // Expected: stale execution is cancelled, not silently attached to a new parent.
            }
        } finally { SubagentProgressStore.clear(chat) }
    }

    @Test
    fun oldParentFinalizerCannotTerminateReconnectedTurnOrItsNewRound() {
        val chat = UUID.randomUUID().toString()
        try {
            val oldExecution = SubagentProgressStore.beginExecution(chat)
            val oldRound = SubagentProgressStore.beginRound(chat, oldExecution)
            SubagentProgressStore.start(chat, SubagentProgress("old", "Explore", "Old"), oldRound)
            val newExecution = SubagentProgressStore.beginExecution(chat)
            assertEquals("cancelled", SubagentProgressStore.sessions.value.getValue(chat).single().status)
            val newRound = SubagentProgressStore.beginRound(chat, newExecution)
            SubagentProgressStore.start(chat, SubagentProgress("new", "Explore", "New"), newRound)

            SubagentProgressStore.endExecution(chat, oldExecution, "failed")
            SubagentProgressStore.start(chat, SubagentProgress("old-late", "Explore", "Late"), oldRound)
            SubagentProgressStore.retry(chat, "old", 3L, "stale")
            SubagentProgressStore.finish(chat, "old", "failed")

            assertEquals(listOf("new"), SubagentProgressStore.sessions.value.getValue(chat).map { it.agentId })
            assertTrue(SubagentProgressStore.conversationSessions.value.getValue(chat).single().isActive)
            SubagentProgressStore.endExecution(chat, newExecution)
            assertFalse(chat in SubagentProgressStore.conversationSessions.value)
        } finally { SubagentProgressStore.clear(chat) }
    }

    @Test
    fun childDisconnectAndModelHopsKeepSameParentExecutionUntilSuccessfulResume() {
        val chat = UUID.randomUUID().toString()
        try {
            val execution = SubagentProgressStore.beginExecution(chat)
            val firstRound = SubagentProgressStore.beginRound(chat, execution)
            SubagentProgressStore.start(chat, SubagentProgress("first", "Explore", "First"), firstRound)
            SubagentProgressStore.tool(chat, "first", SubagentToolProgress("read", "read_file", "result"))
            SubagentProgressStore.retry(chat, "first", 1L, "connection lost")
            assertEquals("retrying", SubagentProgressStore.conversationSessions.value.getValue(chat).single().status)
            SubagentProgressStore.beginAttempt(chat, "first", 2L)
            assertEquals(1L, SubagentProgressStore.sessions.value.getValue(chat).single().toolCallCount)
            SubagentProgressStore.finish(chat, "first", "completed")
            // Switching the parent's model or waiting for its next hop is not endExecution.
            val secondRound = SubagentProgressStore.beginRound(chat, execution)
            SubagentProgressStore.start(chat, SubagentProgress("second", "Explore", "Second"), secondRound)
            assertTrue(SubagentProgressStore.conversationSessions.value.getValue(chat).single().isActive)
            SubagentProgressStore.endExecution(chat, execution)
            assertFalse(chat in SubagentProgressStore.conversationSessions.value)
        } finally { SubagentProgressStore.clear(chat) }
    }

    @Test
    fun clearRejectsLateStartsAndOldExecutionAfterSessionDeletion() {
        val chat = UUID.randomUUID().toString()
        try {
            val execution = SubagentProgressStore.beginExecution(chat)
            val round = SubagentProgressStore.beginRound(chat, execution)
            SubagentProgressStore.clear(chat)
            SubagentProgressStore.start(chat, SubagentProgress("late", "Explore", "Late"), round)
            SubagentProgressStore.endExecution(chat, execution)
            assertFalse(chat in SubagentProgressStore.sessions.value)
            try {
                SubagentProgressStore.beginRound(chat, execution)
                fail("A deleted session cannot accept an old parent")
            } catch (_: CancellationException) { }
        } finally { SubagentProgressStore.clear(chat) }
    }

    @Test
    fun cancellingRealRetryWaitJoinsChildAndRemovesConversationProjection() = runBlocking {
        val chat = UUID.randomUUID().toString()
        val execution = SubagentProgressStore.beginExecution(chat)
        val round = SubagentProgressStore.beginRound(chat, execution)
        val waiting = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        var attempts = 0
        val parent = launch {
            try {
                ToolExecutionScope().run {
                    SubagentProgressStore.start(chat, SubagentProgress("live", "Explore", "Task"), round)
                    try {
                        SubagentRetryRunner.run<Unit>(
                            onFailure = { attempt, error ->
                                SubagentProgressStore.retry(chat, "live", attempt, error.message.orEmpty())
                                waiting.complete(Unit)
                            },
                        ) { attempt, _ ->
                            attempts++
                            SubagentProgressStore.beginAttempt(chat, "live", attempt)
                            throw IOException("disconnected")
                        }
                    } finally {
                        SubagentProgressStore.finish(chat, "live", "cancelled")
                        finalized.complete(Unit)
                    }
                }
            } finally {
                SubagentProgressStore.endExecution(chat, execution)
            }
        }
        try {
            withTimeout(5_000L) { waiting.await() }
            assertEquals("retrying", SubagentProgressStore.conversationSessions.value.getValue(chat).single().status)
            withTimeout(5_000L) { parent.cancelAndJoin() }
            assertTrue(finalized.isCompleted)
            assertEquals(1, attempts)
            assertEquals("cancelled", SubagentProgressStore.sessions.value.getValue(chat).single().status)
            assertFalse(chat in SubagentProgressStore.conversationSessions.value)
        } finally {
            parent.cancelAndJoin()
            SubagentProgressStore.clear(chat)
        }
    }
}
