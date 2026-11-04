package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.PlanModelStage
import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepPriority
import com.ai.assistance.operit.data.model.PlanStepStatus
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Explicit test backend, never used by production initialization. */
internal class RecordingPlanBackend(var document: String? = null) : PlanStateBackend {
    val commits = mutableListOf<String>()
    var failSave = false
    var failLoad = false
    var failAfterSave = false
    override fun load(): String? {
        if (failLoad) throw IOException("read denied")
        return document
    }
    override fun save(document: String) {
        if (failSave) throw IOException("disk full")
        commits.add(document)
        this.document = document
        if (failAfterSave) throw IOException("commit confirmation failed")
    }
}

class PlanStatePersistenceTest {
    private val pending = listOf(PlanStep("Inspect 中文\nquoted \"result\"", PlanStepStatus.PENDING, PlanStepPriority.HIGH))
    private val done = listOf(PlanStep("Inspect", PlanStepStatus.COMPLETED, PlanStepPriority.LOW))

    @Test fun reconstructsAllChatsAndEmptyBootstrapPhase() {
        val backend = RecordingPlanBackend()
        val first = PlanStateRepository(backend)
        first.update("../../unsafe/chat", pending, PlanModelStage.IMPLEMENTATION)
        first.update("bootstrap", emptyList(), PlanModelStage.GENERATION)
        first.update("review", done, PlanModelStage.REVIEW)
        val restored = PlanStateRepository(backend)
        assertEquals(PlanStateSnapshot(pending, PlanModelStage.IMPLEMENTATION), restored.read("../../unsafe/chat"))
        assertEquals(PlanModelStage.GENERATION, restored.read("bootstrap").stage)
        assertTrue(restored.read("bootstrap").steps.isEmpty())
        assertEquals(done, restored.plans.value["review"])
        assertFalse(restored.plans.value.containsKey("bootstrap"))
        assertEquals(PlanStateSnapshot(emptyList(), null), restored.read("absent"))
    }

    @Test fun oneCommitContainsBothNewStepsAndPhase() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        assertTrue(store.update("chat", pending, PlanModelStage.GENERATION).isEmpty())
        assertEquals(pending, store.update("chat", done, PlanModelStage.REVIEW))
        assertEquals(2, backend.commits.size)
        assertEquals(PlanStateSnapshot(done, PlanModelStage.REVIEW), PlanStateCodec.decode(backend.commits.last())["chat"])
    }

    @Test fun legacyWritesPreserveOtherHalfAndClearDeletesDurably() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        store.update("a", pending, PlanModelStage.IMPLEMENTATION)
        store.update("b", done, PlanModelStage.REVIEW)
        store.updateSteps("a", emptyList())
        assertEquals(PlanModelStage.IMPLEMENTATION, PlanStateRepository(backend).read("a").stage)
        store.updateSteps("a", pending)
        store.updateStage("a", null)
        assertEquals(pending, store.read("a").steps)
        store.clear("a")
        val restored = PlanStateRepository(backend)
        assertEquals(PlanStateSnapshot(emptyList(), null), restored.read("a"))
        assertEquals(PlanStateSnapshot(done, PlanModelStage.REVIEW), restored.read("b"))
        assertFalse(PlanStateCodec.decode(backend.document!!).containsKey("a"))
    }

    @Test fun failedCommitDoesNotPublishAndPoisonsAuthorityUntilRestart() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        store.update("chat", pending, PlanModelStage.GENERATION)
        val oldDocument = backend.document
        backend.failSave = true
        expectFailure<PlanPersistenceException> { store.update("chat", done, PlanModelStage.REVIEW) }
        assertEquals(oldDocument, backend.document)
        assertEquals(pending, store.plans.value["chat"])
        expectFailure<IllegalStateException> { store.read("chat") }
        expectFailure<IllegalStateException> { store.formatPlanContext("chat") }
        expectFailure<IllegalStateException> { store.clear("chat") }
        backend.failSave = false
        assertEquals(PlanStateSnapshot(pending, PlanModelStage.GENERATION), PlanStateRepository(backend).read("chat"))
    }

    @Test fun uncertainCommitBlocksReadsUntilRestartRestoresTheDurableSnapshot() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        store.update("chat", pending, PlanModelStage.GENERATION)
        backend.failAfterSave = true
        expectFailure<PlanPersistenceException> { store.update("chat", done, PlanModelStage.REVIEW) }
        assertEquals(pending, store.plans.value["chat"])
        expectFailure<IllegalStateException> { store.read("chat") }
        assertEquals(PlanStateSnapshot(done, PlanModelStage.REVIEW), PlanStateRepository(backend).read("chat"))
    }
    @Test fun invalidContentRejectsBeforeCommitAndDoesNotPoisonRepository() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        val invalid = listOf(PlanStep(" ", PlanStepStatus.PENDING))
        expectFailure<IllegalArgumentException> { store.update("chat", invalid, PlanModelStage.GENERATION) }
        assertTrue(backend.commits.isEmpty())
        store.update("chat", pending, PlanModelStage.GENERATION)
        assertEquals(pending, store.read("chat").steps)
    }
    @Test fun simultaneousLegacyWritesPreserveBothHalvesOfTheSameChat() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(
                Callable { store.updateSteps("chat", done); Unit },
                Callable { store.updateStage("chat", PlanModelStage.REVIEW); Unit }
            )
            executor.invokeAll(tasks).forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(PlanStateSnapshot(done, PlanModelStage.REVIEW), PlanStateRepository(backend).read("chat"))
        } finally { executor.shutdownNow() }
    }
    @Test fun corruptOrUnreadableStorageIsAnExplicitInitializationFailure() {
        expectFailure<PlanPersistenceException> { PlanStateRepository(RecordingPlanBackend("not json")) }
        expectFailure<PlanPersistenceException> { PlanStateRepository(RecordingPlanBackend().apply { failLoad = true }) }
        val valid = PlanStateCodec.encode(mapOf("chat" to PlanStateSnapshot(pending, PlanModelStage.GENERATION)))
        val unsupported = JSONObject(valid).put("version", 2).toString()
        expectFailure<PlanPersistenceException> { PlanStateRepository(RecordingPlanBackend(unsupported)) }
        for (field in listOf("phase", "steps")) {
            val corrupt = JSONObject(valid)
            corrupt.getJSONObject("chats").getJSONObject("chat").put(field, "invalid")
            expectFailure<PlanPersistenceException> { PlanStateRepository(RecordingPlanBackend(corrupt.toString())) }
        }
        for (field in listOf("status", "priority", "content")) {
            val corrupt = JSONObject(valid)
            corrupt.getJSONObject("chats").getJSONObject("chat").getJSONArray("steps").getJSONObject(0)
                .put(field, if (field == "content") 123 else "invalid")
            expectFailure<PlanPersistenceException> { PlanStateRepository(RecordingPlanBackend(corrupt.toString())) }
        }
    }

    @Test fun snapshotsAndFlowCannotBeMutatedByCaller() {
        val store = PlanStateRepository(RecordingPlanBackend())
        val caller = pending.toMutableList()
        store.update("chat", caller, PlanModelStage.GENERATION)
        caller.clear()
        assertEquals(pending, store.read("chat").steps)
        expectFailure<UnsupportedOperationException> { (store.read("chat").steps as MutableList<PlanStep>).clear() }
        expectFailure<UnsupportedOperationException> { (store.plans.value as MutableMap<String, List<PlanStep>>).clear() }
    }

    @Test fun contextUsesRestoredAuthorityAndDoesNotMutateState() {
        val backend = RecordingPlanBackend()
        PlanStateRepository(backend).update("chat", done, PlanModelStage.REVIEW)
        val store = PlanStateRepository(backend)
        val context = store.formatPlanContext("chat")
        assertTrue(context.contains("authoritative_plan_state"))
        assertTrue(context.contains("\"phase\":\"review\""))
        assertTrue(context.contains("completed"))
        assertTrue(context.contains("Do not repeat completed operations"))
        assertTrue(context.contains("subagent must not overwrite"))
        assertEquals(1, backend.commits.size)
        assertEquals("", store.formatPlanContext("absent"))
        store.update("bootstrap", emptyList(), PlanModelStage.GENERATION)
        assertTrue(store.formatPlanContext("bootstrap").contains("generation"))
    }

    @Test fun blankIdRejectedWithoutACommit() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        expectFailure<IllegalArgumentException> { store.update(" ", pending, PlanModelStage.GENERATION) }
        expectFailure<IllegalArgumentException> { store.read("") }
        assertTrue(backend.commits.isEmpty())
    }

    @Test fun concurrentTransactionsDoNotLoseChatsOrExposeMixedSnapshots() {
        val backend = RecordingPlanBackend()
        val store = PlanStateRepository(backend)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 20).map { index -> Callable {
                val id = "chat-$index"
                store.update(id, pending, PlanModelStage.GENERATION)
                store.update(id, done, PlanModelStage.REVIEW)
                assertEquals(PlanStateSnapshot(done, PlanModelStage.REVIEW), store.read(id))
            } }
            executor.invokeAll(tasks).forEach { it.get(5, TimeUnit.SECONDS) }
            val restored = PlanStateRepository(backend)
            for (index in 0 until 20) assertEquals(PlanStateSnapshot(done, PlanModelStage.REVIEW), restored.read("chat-$index"))
            for (commit in backend.commits) {
                PlanStateCodec.decode(commit).values.forEach { snapshot ->
                    assertTrue(snapshot == PlanStateSnapshot(pending, PlanModelStage.GENERATION) ||
                        snapshot == PlanStateSnapshot(done, PlanModelStage.REVIEW))
                }
            }
        } finally { executor.shutdownNow() }
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
