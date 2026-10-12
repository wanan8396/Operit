package com.ai.assistance.operit.data.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single parent model request, using caller-supplied monotonic timestamps. */
data class RequestPerformance(
    val startedAtMs: Long,
    val firstContentAtMs: Long? = null,
    val sampledAtMs: Long = startedAtMs,
    val outputTokens: Long = 0L,
    val finished: Boolean = false,
) {
    val firstTokenLatencyMs: Long?
        get() = firstContentAtMs?.let { (it - startedAtMs).coerceAtLeast(0L) }
    val tokensPerSecond: Double?
        get() {
            val first = firstContentAtMs ?: return null
            val duration = sampledAtMs - first
            if (duration <= 0L || outputTokens <= 0L) return null
            return outputTokens.toDouble() * 1000.0 / duration.toDouble()
        }
}

data class RequestPerformanceAverage(
    val tokensPerSecond: Double? = null,
    val firstTokenLatencyMs: Double? = null,
)

/** Only aggregate sums/counts are persisted; clocks and content never leave memory. */
data class RequestPerformanceAggregate(
    val rateSum: Double = 0.0,
    val rateCount: Long = 0L,
    val latencySum: Double = 0.0,
    val latencyCount: Long = 0L,
) {
    val isValid: Boolean
        get() = rateSum.isFinite() && latencySum.isFinite() && rateSum >= 0.0 &&
            latencySum >= 0.0 && rateCount >= 0L && latencyCount >= 0L &&
            (if (rateCount == 0L) rateSum == 0.0 else rateSum > 0.0) &&
            (latencyCount > 0L || latencySum == 0.0)

    fun average() = RequestPerformanceAverage(
        tokensPerSecond = if (rateCount > 0L) rateSum / rateCount else null,
        firstTokenLatencyMs = if (latencyCount > 0L) latencySum / latencyCount else null,
    )

    fun add(sample: RequestPerformance): RequestPerformanceAggregate {
        val rate = sample.tokensPerSecond?.takeIf { it.isFinite() && it > 0.0 }
        val latency = sample.firstTokenLatencyMs?.toDouble()
        val addRate = rate != null && rateCount < Long.MAX_VALUE && (rateSum + rate).isFinite()
        val addLatency = latency != null && latencyCount < Long.MAX_VALUE && (latencySum + latency).isFinite()
        return copy(
            rateSum = if (addRate) rateSum + requireNotNull(rate) else rateSum,
            rateCount = if (addRate) rateCount + 1L else rateCount,
            latencySum = if (addLatency) latencySum + requireNotNull(latency) else latencySum,
            latencyCount = if (addLatency) latencyCount + 1L else latencyCount,
        )
    }
}

/** Versioned preference value per chat, independent of Android and tolerant of corrupt data. */
object RequestPerformanceCodec {
    fun encode(value: RequestPerformanceAggregate): String {
        require(value.isValid)
        return "1|${value.rateSum}|${value.rateCount}|${value.latencySum}|${value.latencyCount}"
    }

    fun decode(value: String?): RequestPerformanceAggregate? = try {
        val parts = value?.split('|').orEmpty()
        if (parts.size != 5 || parts[0] != "1") null else {
            RequestPerformanceAggregate(
                parts[1].toDouble(), parts[2].toLong(), parts[3].toDouble(), parts[4].toLong(),
            ).takeIf { it.isValid }
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

interface RequestPerformancePersistence {
    fun load(): Map<String, RequestPerformanceAggregate>
    fun save(values: Map<String, RequestPerformanceAggregate>)
    fun clear(chatId: String)
}

/** Instance-based to test process restarts; the singleton uses the same implementation. */
open class RequestPerformanceTracker {
    private val state = MutableStateFlow<Map<String, RequestPerformance>>(emptyMap())
    val sessions: StateFlow<Map<String, RequestPerformance>> = state.asStateFlow()
    private var persistence: RequestPerformancePersistence? = null
    private var aggregates: Map<String, RequestPerformanceAggregate> = emptyMap()

    @Synchronized
    fun initialize(store: RequestPerformancePersistence) {
        if (persistence != null) return
        val loaded = store.load().filter { (id, value) -> id.isNotBlank() && value.isValid }
        aggregates = loaded + aggregates
        persistence = store
    }

    /** Sampled UI reads a snapshot rather than collecting the per-token flow. */
    @Synchronized
    fun averageSnapshot(chatId: String): RequestPerformanceAverage =
        aggregates[chatId]?.average() ?: RequestPerformanceAverage()

    @Synchronized
    fun begin(chatId: String, nowMs: Long) {
        state.value = state.value + (chatId to RequestPerformance(nowMs))
    }

    @Synchronized
    fun firstContent(chatId: String, nowMs: Long) {
        val current = state.value[chatId] ?: return
        if (current.firstContentAtMs != null || current.finished) return
        state.value = state.value + (chatId to current.copy(firstContentAtMs = nowMs, sampledAtMs = nowMs))
    }

    @Synchronized
    fun tokens(chatId: String, outputTokens: Long, nowMs: Long) {
        val current = state.value[chatId] ?: return
        if (current.finished) return
        state.value = state.value + (chatId to current.copy(outputTokens = outputTokens.coerceAtLeast(0L), sampledAtMs = nowMs))
    }

    @Synchronized
    fun complete(chatId: String, outputTokens: Long, nowMs: Long) {
        val current = state.value[chatId] ?: return
        if (current.finished) return
        val sample = current.copy(outputTokens = outputTokens.coerceAtLeast(0L), sampledAtMs = nowMs, finished = true)
        state.value = state.value + (chatId to sample)
        val old = aggregates[chatId] ?: RequestPerformanceAggregate()
        val updated = old.add(sample)
        if (updated == old) return
        aggregates = aggregates + (chatId to updated)
        // Only completed requests write; streamed tokens and UI refreshes never do.
        persistence?.save(aggregates)
    }

    /** Cancelled/failed samples do not enter successful-request averages. */
    @Synchronized
    fun stop(chatId: String) {
        val current = state.value[chatId] ?: return
        if (!current.finished) state.value = state.value + (chatId to current.copy(finished = true))
    }

    @Synchronized
    fun clear(chatId: String) {
        state.value = state.value - chatId
        aggregates = aggregates - chatId
        persistence?.clear(chatId)
    }
}

object RequestPerformanceStore : RequestPerformanceTracker()
