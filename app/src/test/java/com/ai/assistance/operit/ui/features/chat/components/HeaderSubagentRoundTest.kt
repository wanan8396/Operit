package com.ai.assistance.operit.ui.features.chat.components
import com.ai.assistance.operit.data.stats.SubagentProgress
import org.junit.Assert.*
import org.junit.Test
class HeaderSubagentRoundTest {
    @Test fun staysUntilLastAgentFinishesThenReturnsNextRound() {
        val done = SubagentProgress("1", "Explore", "One", "completed")
        val running = SubagentProgress("2", "Explore", "Two")
        assertEquals(listOf(done, running), visibleHeaderSubagents(listOf(done, running)))
        assertTrue(visibleHeaderSubagents(listOf(done, running.copy(status = "completed"))).isEmpty())
        assertEquals(listOf(running.copy(agentId = "3")), visibleHeaderSubagents(listOf(running.copy(agentId = "3"))))
    }
    @Test fun retryIsActiveButTerminalOutcomesAreHidden() {
        val retry = SubagentProgress("1", "Explore", "One", "retrying", attempt = 50)
        assertEquals(listOf(retry), visibleHeaderSubagents(listOf(retry)))
        for (status in listOf("failed", "timed_out", "cancelled", "completed")) {
            assertTrue(visibleHeaderSubagents(listOf(retry.copy(status = status))).isEmpty())
        }
        assertTrue(visibleHeaderSubagents(emptyList()).isEmpty())
    }
}
