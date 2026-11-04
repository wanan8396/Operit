package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.PlanModelStage
import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepStatus
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PlanModelStageStoreTest {
    @Test fun isolatesSessionsAndClearsWithPlan() {
        val store = PlanStateRepository(RecordingPlanBackend())
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        try {
            assertNull(store.read(a).stage)
            store.updateStage(a, PlanModelStage.GENERATION)
            store.updateStage(b, PlanModelStage.IMPLEMENTATION)
            assertEquals(PlanModelStage.GENERATION, store.read(a).stage)
            assertEquals(PlanModelStage.IMPLEMENTATION, store.read(b).stage)
            store.clear(a)
            assertNull(store.read(a).stage)
            assertEquals(PlanModelStage.IMPLEMENTATION, store.read(b).stage)
            store.updateStage(b, null)
            assertNull(store.read(b).stage)
        } finally {
            store.clear(a)
            store.clear(b)
        }
    }

    @Test fun absentWirePhaseDerivesFromTheCompleteChecklist() {
        assertNull(PlanModelStageStore.resolve(null, emptyList()))
        assertEquals(PlanModelStage.GENERATION, PlanModelStageStore.resolve(null, listOf(step(PlanStepStatus.PENDING))))
        assertEquals(PlanModelStage.IMPLEMENTATION, PlanModelStageStore.resolve(null, listOf(step(PlanStepStatus.IN_PROGRESS), step(PlanStepStatus.PENDING))))
        assertEquals(PlanModelStage.REVIEW, PlanModelStageStore.resolve(null, listOf(step(PlanStepStatus.COMPLETED))))
    }

    @Test fun explicitWirePhaseOverridesProgressIncludingEmptyBootstrap() {
        assertEquals(PlanModelStage.GENERATION, PlanModelStageStore.resolve("generation", emptyList()))
        assertEquals(PlanModelStage.IMPLEMENTATION, PlanModelStageStore.resolve("implementation", listOf(step(PlanStepStatus.PENDING))))
        assertEquals(PlanModelStage.REVIEW, PlanModelStageStore.resolve("review", listOf(step(PlanStepStatus.IN_PROGRESS))))
        assertNull(PlanModelStageStore.resolve("chat", listOf(step(PlanStepStatus.COMPLETED))))
    }

    @Test fun invalidPhaseDoesNotMutateExistingState() {
        val store = PlanStateRepository(RecordingPlanBackend())
        val id = UUID.randomUUID().toString()
        try {
            store.updateStage(id, PlanModelStage.GENERATION)
            try {
                val invalid = PlanModelStageStore.resolve("invalid", emptyList())
                store.updateStage(id, invalid)
                fail("Invalid phase was accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(PlanModelStage.GENERATION, store.read(id).stage)
        } finally { store.clear(id) }
    }

    private fun step(status: PlanStepStatus) = PlanStep("Work", status)
}
