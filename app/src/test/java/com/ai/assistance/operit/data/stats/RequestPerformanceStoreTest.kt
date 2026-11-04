package com.ai.assistance.operit.data.stats
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
class RequestPerformanceStoreTest {
    @Test fun missingSamplesAreNotZero() {
        val sample = RequestPerformance(1000)
        assertNull(sample.firstTokenLatencyMs)
        assertNull(sample.tokensPerSecond)
        assertNull(sample.copy(firstContentAtMs = 2000, sampledAtMs = 2000, outputTokens = 4).tokensPerSecond)
    }
    @Test fun firstChunkAndRateUseRequestClock() {
        val id = UUID.randomUUID().toString()
        try {
            RequestPerformanceStore.begin(id, 1000)
            RequestPerformanceStore.tokens(id, 10, 1400)
            RequestPerformanceStore.firstContent(id, 1500)
            RequestPerformanceStore.firstContent(id, 2000)
            RequestPerformanceStore.complete(id, 60, 3500)
            val sample = RequestPerformanceStore.sessions.value.getValue(id)
            assertEquals(500L, sample.firstTokenLatencyMs!!)
            assertEquals(30.0, sample.tokensPerSecond!!, 0.001)
            assertTrue(sample.finished)
            RequestPerformanceStore.tokens(id, 999, 9000)
            assertEquals(sample, RequestPerformanceStore.sessions.value.getValue(id))
            RequestPerformanceStore.begin(id, 10000)
            assertNull(RequestPerformanceStore.sessions.value.getValue(id).firstTokenLatencyMs)
            assertNull(RequestPerformanceStore.sessions.value.getValue(id).tokensPerSecond)
        } finally { RequestPerformanceStore.clear(id) }
    }
    @Test fun sessionsAndCancellationAreIsolated() {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        try {
            RequestPerformanceStore.begin(first, 1000)
            RequestPerformanceStore.begin(second, 3000)
            RequestPerformanceStore.stop(first)
            assertTrue(RequestPerformanceStore.sessions.value.getValue(first).finished)
            assertFalse(RequestPerformanceStore.sessions.value.getValue(second).finished)
            assertNull(RequestPerformanceStore.sessions.value.getValue(first).firstTokenLatencyMs)
        } finally { RequestPerformanceStore.clear(first); RequestPerformanceStore.clear(second) }
    }
}
