package com.ai.assistance.operit.api.chat.enhance

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionScopeTest {
    @Test
    fun callerCancellationCancelsToolAndWaitsForFinally() = runBlocking {
        val scope = ToolExecutionScope()
        val started = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val caller = launch {
            scope.run {
                try {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                } finally {
                    finalized.complete(Unit)
                }
            }
        }
        try {
            withTimeout(2_000) { started.await() }
            caller.cancelAndJoin()
            withTimeout(2_000) { finalized.await() }
            assertTrue(caller.isCompleted)
        } finally {
            caller.cancelAndJoin()
        }
    }

    @Test
    fun siblingFailureCancelsParallelTool() = runBlocking {
        val scope = ToolExecutionScope()
        val started = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        try {
            withTimeout(2_000) {
                val failure = runCatching {
                    coroutineScope {
                        launch {
                            scope.run {
                                try {
                                    started.complete(Unit)
                                    CompletableDeferred<Unit>().await()
                                } finally {
                                    finalized.complete(Unit)
                                }
                            }
                        }
                        started.await()
                        launch { throw IOException("sibling failed") }
                    }
                }.exceptionOrNull()
                assertTrue(failure is IOException)
            }
            withTimeout(2_000) { finalized.await() }
            assertTrue(finalized.isCompleted)
        } finally {
            // coroutineScope joins/cancels both children before reaching here.
        }
    }

    @Test
    fun cancelAllStopsMultipleCallsButDoesNotPermanentlyCancelScope() = runBlocking {
        val scope = ToolExecutionScope()
        val started = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val finalized = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val calls = (1..3).map {
            async {
                scope.run {
                    try {
                        if (started.incrementAndGet() == 3) allStarted.complete(Unit)
                        release.await()
                    } finally {
                        finalized.incrementAndGet()
                    }
                }
            }
        }
        try {
            withTimeout(2_000) { allStarted.await() }
            scope.cancelAll()
            withTimeout(2_000) { calls.joinAll() }
            assertTrue(calls.all { it.isCancelled })
            assertEquals(3, finalized.get())
            assertEquals("usable after cancelAll", "ok", scope.run { "ok" })
        } finally {
            release.complete(Unit)
            calls.forEach { it.cancel() }
            calls.forEach { runCatching { withTimeout(2_000) { it.await() } } }
        }
    }

    @Test
    fun failedToolDoesNotPoisonFollowingCallAndPropagatesIOException() = runBlocking {
        val scope = ToolExecutionScope()
        val failure = IOException("tool failure")
        val caught = runCatching { scope.run<String> { throw failure } }.exceptionOrNull()
        // Debug stacktrace recovery may copy IOException while preserving its type/message.
        assertTrue(caught is IOException)
        assertEquals(failure.message, caught?.message)
        assertEquals("next", scope.run { "next" })
    }

    @Test
    fun returnsValueAndPropagatesFailure() = runBlocking {
        val scope = ToolExecutionScope()
        assertEquals(42, scope.run { 42 })
        val failure = IllegalStateException("expected")
        val caught = runCatching { scope.run<Int> { throw failure } }.exceptionOrNull()
        assertTrue(caught is IllegalStateException)
        assertEquals(failure.message, caught?.message)
        assertFalse(scope.run { false })
    }
}
