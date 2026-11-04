package com.ai.assistance.operit.api.chat.enhance

import org.junit.Assert.*
import org.junit.Test

class ToolCallBudgetTest {
    @Test fun aRejectedBatchDoesNotPartiallyConsumeBudget() {
        val budget = ToolCallBudget(3)
        assertTrue(budget.reserve(2))
        assertFalse(budget.reserve(2))
        assertEquals(2, budget.usedCalls())
        assertTrue(budget.reserve(1))
        assertFalse(budget.reserve(1))
        assertTrue(budget.reserve(0))
    }

    @Test fun parentRequestsRemainUnboundedByDefault() {
        val budget = ToolCallBudget(null)
        assertTrue(budget.reserve(100))
        assertEquals(100, budget.usedCalls())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectInvalidLimit() { ToolCallBudget(33) }

    @Test(expected = IllegalArgumentException::class)
    fun rejectNegativeBatchSize() { ToolCallBudget(8).reserve(-1) }

    @Test fun concurrentReservationsCannotExceedTheLimit() {
        val budget = ToolCallBudget(8)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(32) { Thread { if (budget.reserve(1)) accepted.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(8, accepted.get())
        assertEquals(8, budget.usedCalls())
    }
}