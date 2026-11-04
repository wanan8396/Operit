package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubagentBatchRunnerTest {
    @Test
    fun everyChildStartsBeforeAnyChildFinishesAndResultsKeepInputOrder() = runTest {
        val requests = List(6) { request("task-$it") }
        val release = List(requests.size) { CompletableDeferred<Unit>() }
        val started = mutableListOf<AITool>()
        val finished = mutableListOf<String>()
        val executor = fake { tool ->
            val index = tool.parameters.single { it.name == "description" }.value.removePrefix("task-").toInt()
            started.add(tool)
            emit(result("running-$index"))
            release[index].await()
            finished.add("task-$index")
            emit(result("task-$index"))
        }
        val batch = async { SubagentBatchRunner.run(requests, executor) }
        runCurrent()
        // A sequential loop or the former two-agent quota fails here without relying on timing.
        assertEquals(6, started.size)
        assertTrue(finished.isEmpty())
        assertFalse(batch.isCompleted)
        started.forEachIndexed { index, tool ->
            assertEquals("run_subagent", tool.name)
            val params = tool.parameters.associate { it.name to it.value }
            assertEquals(requests[index].prompt, params["prompt"])
            assertEquals("Explore", params["subagent_type"])
            assertEquals("false", params["run_in_background"])
            assertEquals("4", params["max_tool_calls"])
            assertEquals("30", params["timeout_seconds"])
        }
        release.indices.reversed().forEach { index ->
            release[index].complete(Unit)
            runCurrent()
        }
        assertEquals(requests.reversed().map { it.description }, finished)
        assertEquals(requests.map { it.description }, batch.await().map { it.result.toString() })
    }

    @Test
    fun cancellingParentJoinsEveryChildCleanup() = runTest {
        var running = 0
        var cleaned = 0
        val executor = fake {
            running++
            try {
                awaitCancellation()
            } finally {
                running--
                cleaned++
            }
        }
        val batch = async { SubagentBatchRunner.run(List(12) { request("same task") }, executor) }
        runCurrent()
        assertEquals(12, running)
        batch.cancelAndJoin()
        assertEquals(0, running)
        assertEquals(12, cleaned)
        assertTrue(batch.isCancelled)
    }

    @Test
    fun failureResultDoesNotStopSuccessfulSiblings() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val executor = fake { tool ->
            val name = tool.parameters.single { it.name == "description" }.value
            started.add(name)
            if (name == "bad") {
                emit(ToolResult("run_subagent", false, StringResultData(""), "child timed out"))
            } else {
                release.await()
                emit(result(name))
            }
        }
        val batch = async {
            SubagentBatchRunner.run(listOf(request("bad"), request("good")), executor)
        }
        runCurrent()
        assertEquals(listOf("bad", "good"), started)
        assertFalse(batch.isCompleted)
        release.complete(Unit)
        val results = batch.await()
        assertEquals(listOf(false, true), results.map { it.success })
        assertEquals("child timed out", results[0].error)
        assertEquals("good", results[1].result.toString())
    }

    @Test
    fun multipleBatchesShareNoConcurrencyGate() = runTest {
        var started = 0
        val release = CompletableDeferred<Unit>()
        val executor = fake {
            started++
            release.await()
            emit(result("done"))
        }
        val first = async { SubagentBatchRunner.run(List(20) { request("a-$it") }, executor) }
        val second = async { SubagentBatchRunner.run(List(20) { request("b-$it") }, executor) }
        runCurrent()
        assertEquals(40, started)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals(20, first.await().size)
        assertEquals(20, second.await().size)
    }

    @Test
    fun thrownFlowEmptyFlowAndCleanupFailureAreIsolatedToTheirOwnChild() = runTest {
        val names = listOf("throws", "empty", "cleanup", "good")
        val executor = fake { tool ->
            val name = tool.parameters.single { it.name == "description" }.value
            when (name) {
                "throws" -> throw IllegalStateException("request failed")
                "empty" -> Unit
                "cleanup" -> try {
                    emit(result("partial"))
                } finally {
                    throw IllegalStateException("cleanup failed")
                }
                else -> emit(result("ok"))
            }
        }
        val results = SubagentBatchRunner.run(names.map(::request), executor)
        assertEquals(listOf(false, false, false, true), results.map { it.success })
        assertEquals("request failed", results[0].error)
        assertFalse(results[1].error.isNullOrBlank())
        assertEquals("cleanup failed", results[2].error)
        assertEquals("ok", results[3].result.toString())
    }

    private fun request(description: String) = SubagentRequest(
        description, "Inspect independently: $description", SubagentProfile.EXPLORE, 4, 30,
    )

    private fun result(text: String) = ToolResult("run_subagent", true, StringResultData(text))

    private fun fake(block: suspend kotlinx.coroutines.flow.FlowCollector<ToolResult>.(AITool) -> Unit) =
        object : ToolExecutor {
            override fun invoke(tool: AITool): ToolResult = error("Blocking invocation is forbidden")
            override fun invokeAndStream(tool: AITool): Flow<ToolResult> = flow { block(tool) }
        }
}