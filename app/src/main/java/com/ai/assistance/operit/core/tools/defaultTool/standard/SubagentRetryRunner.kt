package com.ai.assistance.operit.core.tools.defaultTool.standard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Retry child execution failures without restarting successful siblings. Cancellation always wins. */
internal object SubagentRetryRunner {
    suspend fun <T> run(
        onFailure: suspend (attempt: Long, error: Exception) -> Unit,
        waitBeforeRetry: suspend (attempt: Long) -> Unit = { attempt ->
            delay((attempt.coerceAtMost(30L) * 1_000L))
        },
        operation: suspend (attempt: Long, previousError: String?) -> T,
    ): T {
        var attempt = 1L
        var previousError: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return operation(attempt, previousError)
            } catch (error: TimeoutCancellationException) {
                // Only a child's own timeout is retryable; parent cancellation is propagated.
                currentCoroutineContext().ensureActive()
                previousError = error.message ?: "Subagent attempt timed out"
                onFailure(attempt, error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                previousError = error.message ?: error.javaClass.simpleName
                onFailure(attempt, error)
            }
            waitBeforeRetry(attempt)
            currentCoroutineContext().ensureActive()
            attempt = (attempt + 1L).coerceAtLeast(attempt)
        }
    }
}
