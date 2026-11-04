package com.ai.assistance.operit.core.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextSummaryPolicyTest {
    @Test
    fun summaryBoundaryIncludesTrailingUserAndDoesNotConsumeLaterMetadata() {
        assertEquals(0, summaryInsertionPosition(emptyList()))
        assertEquals(1, summaryInsertionPosition(listOf("user")))
        assertEquals(2, summaryInsertionPosition(listOf("user", "ai")))
        assertEquals(3, summaryInsertionPosition(listOf("user", "ai", "user")))
        assertEquals(4, summaryInsertionPosition(listOf("summary", "user", "ai", "user", "system")))
        assertEquals(0, summaryInsertionPosition(listOf("summary", "system")))
    }

    @Test
    fun maximumContextModeUsesMaximumWindowWithoutIntegerOverflow() {
        assertEquals(32 * 1024, summaryContextWindowTokens(32f, 128f, false))
        assertEquals(128 * 1024, summaryContextWindowTokens(32f, 128f, true))
        assertEquals(0, summaryContextWindowTokens(0f, 128f, false))
        assertEquals(Int.MAX_VALUE, summaryContextWindowTokens(Float.MAX_VALUE, Float.MAX_VALUE, true))
    }

    @Test
    fun continuationRequiresRebuiltRequestBelowCompressionBoundary() {
        assertTrue(canContinueAfterSummary(799, 1000, 0.8))
        assertFalse(canContinueAfterSummary(800, 1000, 0.8))
        assertFalse(canContinueAfterSummary(1200, 1000, 0.8))
        assertTrue(canContinueAfterSummary(1200, 0, 0.8))
    }
}