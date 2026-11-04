package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepStatus
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PlanStepStoreTest {
    private fun repository() = PlanStateRepository(RecordingPlanBackend())
    @Test fun isolatesChatsAndReplacesAtomically() {
        val store = repository()
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        val pending = listOf(PlanStep("Inspect", PlanStepStatus.PENDING))
        val done = listOf(PlanStep("Inspect", PlanStepStatus.COMPLETED))
        try {
            assertTrue(store.updateSteps(a, pending).isEmpty())
            store.updateSteps(b, done)
            assertEquals(pending, store.read(a).steps)
            assertEquals(done, store.read(b).steps)
            assertEquals(pending, store.updateSteps(a, done))
            store.clear(a)
            assertTrue(store.read(a).steps.isEmpty())
            assertEquals(done, store.read(b).steps)
        } finally {
            store.clear(a)
            store.clear(b)
        }
    }

    @Test fun copiesCallerList() {
        val store = repository()
        val id = UUID.randomUUID().toString()
        val list = mutableListOf(PlanStep("Inspect", PlanStepStatus.PENDING))
        try {
            store.updateSteps(id, list)
            list.clear()
            assertEquals(1, store.read(id).steps.size)
        } finally { store.clear(id) }
    }
}
