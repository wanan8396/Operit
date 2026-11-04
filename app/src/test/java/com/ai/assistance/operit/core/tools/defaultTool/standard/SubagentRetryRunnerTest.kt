package com.ai.assistance.operit.core.tools.defaultTool.standard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubagentRetryRunnerTest {
    @Test fun retriesPastAnOrdinaryLimitAndCarriesThePreviousError() = runBlocking {
        val failures = mutableListOf<Long>()
        val result = SubagentRetryRunner.run(
            onFailure = { attempt, _ -> failures += attempt },
            waitBeforeRetry = {},
        ) { attempt, previous ->
            if (attempt > 1L) assertEquals("failure ${attempt - 1L}", previous)
            if (attempt <= 40L) throw IllegalStateException("failure $attempt")
            "done"
        }
        assertEquals("done", result)
        assertEquals((1L..40L).toList(), failures)
    }

    @Test fun childTimeoutRetriesThenCompletes() = runBlocking {
        var failures = 0
        val result = SubagentRetryRunner.run(
            onFailure = { _, _ -> failures++ }, waitBeforeRetry = {},
        ) { attempt, _ ->
            if (attempt == 1L) withTimeout(10L) { awaitCancellation() }
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(1, failures)
    }

    @Test fun successfulSiblingIsNotRestartedWhenAnotherChildRetries() = runBlocking {
        val counts = intArrayOf(0, 0)
        val results = (0..1).map { index -> async {
            SubagentRetryRunner.run(onFailure = { _, _ -> }, waitBeforeRetry = {}) { _, _ ->
                counts[index]++
                if (index == 1 && counts[index] < 4) throw IllegalStateException("transient")
                "child-$index"
            }
        } }.awaitAll()
        assertEquals(listOf("child-0", "child-1"), results)
        assertEquals(1, counts[0])
        assertEquals(4, counts[1])
    }

    @Test fun cancellationIsNotRetried() = runBlocking {
        var failures = 0
        try {
            SubagentRetryRunner.run<Unit>(onFailure = { _, _ -> failures++ }, waitBeforeRetry = {}) { _, _ ->
                throw CancellationException("parent cancelled")
            }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) { }
        assertEquals(0, failures)
    }

    @Test fun parentCancellationInterruptsRetryWait() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        var attempts = 0
        val job = launch {
            SubagentRetryRunner.run<Unit>(
                onFailure = { _, _ -> },
                waitBeforeRetry = { waiting.complete(Unit); awaitCancellation() },
            ) { _, _ -> attempts++; throw IllegalStateException("retry") }
        }
        waiting.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
    }
}
