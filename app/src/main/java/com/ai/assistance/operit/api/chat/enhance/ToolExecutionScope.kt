package com.ai.assistance.operit.api.chat.enhance

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

/** Cancellable tool batches run on IO as children of the collecting parent, never in a detached scope. */
internal class ToolExecutionScope {
    private val activeJobs = ConcurrentHashMap.newKeySet<Job>()

    suspend fun <T> run(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        val batchJob = currentCoroutineContext().job
        activeJobs.add(batchJob)
        try {
            currentCoroutineContext().ensureActive()
            block()
        } finally {
            activeJobs.remove(batchJob)
        }
    }

    /** Cancels current batches only. A later parent turn starts with a healthy caller-owned job. */
    fun cancelAll() {
        activeJobs.toList().forEach { it.cancel() }
    }
}
