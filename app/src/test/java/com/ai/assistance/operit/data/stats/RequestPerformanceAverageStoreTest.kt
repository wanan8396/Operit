package com.ai.assistance.operit.data.stats

import org.junit.Assert.*
import org.junit.Test

class RequestPerformanceAverageStoreTest {
    private class MemoryPersistence : RequestPerformancePersistence {
        val encoded = mutableMapOf<String, String>()
        var writes = 0
        var loads = 0
        override fun load(): Map<String, RequestPerformanceAggregate> {
            loads++
            return encoded.mapNotNull { (id, value) -> RequestPerformanceCodec.decode(value)?.let { id to it } }.toMap()
        }
        override fun save(values: Map<String, RequestPerformanceAggregate>) {
            writes++
            values.forEach { (id, value) -> encoded[id] = RequestPerformanceCodec.encode(value) }
        }
        override fun clear(chatId: String) { encoded.remove(chatId) }
    }

    private fun finish(store: RequestPerformanceTracker, chat: String, start: Long, latency: Long, duration: Long, tokens: Long) {
        store.begin(chat, start)
        store.firstContent(chat, start + latency)
        store.complete(chat, tokens, start + latency + duration)
    }

    @Test fun successfulRequestsUseArithmeticMeanRatherThanTokenOrDurationWeighting() {
        val store = RequestPerformanceTracker()
        finish(store, "chat", 1000, 500, 2000, 60) // 30 token/s
        finish(store, "chat", 10000, 1500, 1000, 50) // 50 token/s
        val mean = store.averageSnapshot("chat")
        assertEquals(40.0, mean.tokensPerSecond!!, 0.0001)
        assertEquals(1000.0, mean.firstTokenLatencyMs!!, 0.0001)
        assertEquals(RequestPerformanceAverage(), store.averageSnapshot("other"))
    }

    @Test fun restartRetainsMeansButDoesNotRestoreMonotonicRequestClocks() {
        val persistence = MemoryPersistence()
        val first = RequestPerformanceTracker().apply { initialize(persistence) }
        finish(first, "chat", 1000, 500, 2000, 60)
        val second = RequestPerformanceTracker().apply { initialize(persistence); initialize(persistence) }
        assertEquals(2, persistence.loads)
        assertTrue(second.sessions.value.isEmpty())
        assertEquals(first.averageSnapshot("chat"), second.averageSnapshot("chat"))
        finish(second, "chat", 0, 1500, 1000, 50)
        assertEquals(40.0, second.averageSnapshot("chat").tokensPerSecond!!, 0.0001)
        assertEquals(1000.0, second.averageSnapshot("chat").firstTokenLatencyMs!!, 0.0001)
    }

    @Test fun clearRemovesOnlyOneChatsPersistedAverageAcrossRestart() {
        val persistence = MemoryPersistence()
        val first = RequestPerformanceTracker().apply { initialize(persistence) }
        finish(first, "one", 0, 100, 1000, 20)
        finish(first, "two", 0, 200, 1000, 40)
        first.clear("one")
        val second = RequestPerformanceTracker().apply { initialize(persistence) }
        assertEquals(RequestPerformanceAverage(), second.averageSnapshot("one"))
        assertEquals(40.0, second.averageSnapshot("two").tokensPerSecond!!, 0.0001)
    }

    @Test fun chunksSnapshotsAndRepeatedCompletionDoNotAddSamplesOrWrites() {
        val persistence = MemoryPersistence()
        val store = RequestPerformanceTracker().apply { initialize(persistence) }
        store.begin("chat", 0)
        store.firstContent("chat", 100)
        repeat(100) { store.tokens("chat", it.toLong(), 100L + it); store.averageSnapshot("chat") }
        assertEquals(0, persistence.writes)
        assertEquals(RequestPerformanceAverage(), store.averageSnapshot("chat"))
        store.complete("chat", 50, 1100)
        val mean = store.averageSnapshot("chat")
        store.complete("chat", 999, 99999)
        store.stop("chat")
        store.tokens("chat", 9999, 999999)
        assertEquals(1, persistence.writes)
        assertEquals(mean, store.averageSnapshot("chat"))
    }

    @Test fun stoppedOrMissingContentDoesNotContributeFakeZero() {
        val persistence = MemoryPersistence()
        val store = RequestPerformanceTracker().apply { initialize(persistence) }
        finish(store, "chat", 0, 100, 1000, 20)
        val mean = store.averageSnapshot("chat")
        store.begin("chat", 2000)
        store.firstContent("chat", 2200)
        store.tokens("chat", 90, 3000)
        store.stop("chat")
        store.complete("chat", 90, 3200)
        assertEquals(mean, store.averageSnapshot("chat"))
        store.begin("missing", 0)
        store.complete("missing", 50, 1000)
        assertEquals(RequestPerformanceAverage(), store.averageSnapshot("missing"))
        assertEquals(1, persistence.writes)
    }

    @Test fun validZeroLatencyAndMissingRateAreCountedIndependently() {
        val store = RequestPerformanceTracker()
        finish(store, "chat", 0, 0, 0, 20)
        assertNull(store.averageSnapshot("chat").tokensPerSecond)
        assertEquals(0.0, store.averageSnapshot("chat").firstTokenLatencyMs!!, 0.0001)
        finish(store, "chat", 1000, 500, 1000, 20)
        assertEquals(20.0, store.averageSnapshot("chat").tokensPerSecond!!, 0.0001)
        assertEquals(250.0, store.averageSnapshot("chat").firstTokenLatencyMs!!, 0.0001)
    }

    @Test fun corruptPreferenceRecordsAreIgnoredWithoutLosingValidChats() {
        val persistence = MemoryPersistence()
        persistence.encoded["good"] = RequestPerformanceCodec.encode(RequestPerformanceAggregate(20.0, 1, 100.0, 1))
        persistence.encoded["bad"] = "1|NaN|1|100.0|1"
        val store = RequestPerformanceTracker().apply { initialize(persistence) }
        assertEquals(20.0, store.averageSnapshot("good").tokensPerSecond!!, 0.0001)
        assertEquals(RequestPerformanceAverage(), store.averageSnapshot("bad"))
        for (bad in listOf(null, "", "2|1|1|1|1", "1|Infinity|1|0|1", "1|5|0|0|0", "1|0|1|0|0", "1|1|-1|0|0", "1|1|1|-1|1", "1|1|1|10|0")) {
            assertNull(RequestPerformanceCodec.decode(bad))
        }
    }

    @Test fun aggregateCounterAndSumOverflowDoesNotProduceInvalidPersistence() {
        val aggregate = RequestPerformanceAggregate(Double.MAX_VALUE, Long.MAX_VALUE, Double.MAX_VALUE, Long.MAX_VALUE)
        val updated = aggregate.add(RequestPerformance(0, 1, 2, Long.MAX_VALUE, true))
        assertEquals(aggregate, updated)
        assertTrue(updated.isValid)
    }
}
