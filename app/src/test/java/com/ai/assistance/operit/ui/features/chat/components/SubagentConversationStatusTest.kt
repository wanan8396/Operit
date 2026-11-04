package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.stats.SubagentProgress
import com.ai.assistance.operit.data.stats.SubagentProgressStore
import com.ai.assistance.operit.data.stats.SubagentToolProgress
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentConversationStatusTest {
    @Test
    fun mapsAllStatusesWithoutTreatingRetriesOrCancellationAsSuccess() {
        val statuses = mapOf(
            "running" to SubagentConversationStatus.RUNNING,
            "retrying" to SubagentConversationStatus.RETRYING,
            "completed" to SubagentConversationStatus.COMPLETED,
            "failed" to SubagentConversationStatus.FAILED,
            "timed_out" to SubagentConversationStatus.TIMED_OUT,
            "cancelled" to SubagentConversationStatus.CANCELLED,
        )
        statuses.forEach { (raw, expected) ->
            assertEquals(expected, SubagentConversationStatus.fromStatus(raw))
            assertEquals(raw == "running" || raw == "retrying", expected.isActive)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownStatus() {
        SubagentConversationStatus.fromStatus("success-looking-but-unknown")
    }

    @Test
    fun currentRoundProjectionRetainsTaskIdentityThroughRetryThenSuccess() {
        val chatId = UUID.randomUUID().toString()
        val otherChatId = UUID.randomUUID().toString()
        try {
            val round = SubagentProgressStore.beginRound(chatId)
            SubagentProgressStore.start(chatId, SubagentProgress("inspect", "Explore", "审查计划协议"), round)
            SubagentProgressStore.start(chatId, SubagentProgress("remove", "general-purpose", "移除计划按钮"), round)
            val otherRound = SubagentProgressStore.beginRound(otherChatId)
            SubagentProgressStore.start(otherChatId, SubagentProgress("other", "Explore", "Other chat"), otherRound)
            SubagentProgressStore.tool(chatId, "inspect", SubagentToolProgress("tool", "read_file", "started"))
            assertEquals("read_file", SubagentProgressStore.sessions.value.getValue(chatId).first().tools.single().name)
            SubagentProgressStore.finish(chatId, "remove", "completed")
            SubagentProgressStore.retry(chatId, "inspect", 1L, "temporary failure")
            val retry = SubagentProgressStore.sessions.value.getValue(chatId).first()
            assertEquals("审查计划协议", retry.description)
            assertTrue(SubagentConversationStatus.fromStatus(retry.status).isActive)
            SubagentProgressStore.beginAttempt(chatId, "inspect", 2L)
            SubagentProgressStore.finish(chatId, "inspect", "completed")
            val completed = SubagentProgressStore.sessions.value.getValue(chatId)
            assertEquals(listOf("inspect", "remove"), completed.map { it.agentId })
            assertEquals(2L, completed.first().attempt)
            assertTrue(completed.all { SubagentConversationStatus.fromStatus(it.status) == SubagentConversationStatus.COMPLETED })
            val nextRound = SubagentProgressStore.beginRound(chatId)
            assertTrue(SubagentProgressStore.sessions.value.getValue(chatId).isEmpty())
            SubagentProgressStore.start(chatId, SubagentProgress("next", "Explore", "New review"), nextRound)
            SubagentProgressStore.retry(chatId, "inspect", 3L, "late error")
            SubagentProgressStore.start(chatId, SubagentProgress("late", "Explore", "Old round"), round)
            assertEquals("next", SubagentProgressStore.sessions.value.getValue(chatId).single().agentId)
            assertEquals("other", SubagentProgressStore.sessions.value.getValue(otherChatId).single().agentId)
            SubagentProgressStore.finish(chatId, "next", "cancelled")
            assertFalse(SubagentConversationStatus.fromStatus(SubagentProgressStore.sessions.value.getValue(chatId).single().status).isActive)
        } finally {
            SubagentProgressStore.clear(chatId)
            SubagentProgressStore.clear(otherChatId)
        }
    }
}
