package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepStatus
import com.ai.assistance.operit.data.stats.PlanStateRepository
import com.ai.assistance.operit.data.stats.RecordingPlanBackend
import org.junit.Assert.*
import org.junit.Test

class HeaderPlanVisibilityTest {
    private val pending = PlanStep("Pending", PlanStepStatus.PENDING)
    private val done = PlanStep("Done", PlanStepStatus.COMPLETED)

    @Test fun emptyAndFullyCompletedPlansAreHidden() {
        assertTrue(visibleHeaderPlanSteps(emptyList()).isEmpty())
        assertTrue(visibleHeaderPlanSteps(listOf(done)).isEmpty())
        assertTrue(visibleHeaderPlanSteps(listOf(done, done)).isEmpty())
    }

    @Test fun unfinishedPlanKeepsCompletedRowsAndOrder() {
        val steps = listOf(done, pending, pending.copy(status = PlanStepStatus.IN_PROGRESS))
        assertEquals(steps, visibleHeaderPlanSteps(steps))
    }

    @Test fun restartReplacementCompletionAndDeletionRespectLifecycle() {
        val backend = RecordingPlanBackend()
        val initial = PlanStateRepository(backend)
        initial.updateSteps("chat", listOf(pending))
        val restarted = PlanStateRepository(backend)
        assertEquals(listOf(pending), visibleHeaderPlanSteps(restarted.read("chat").steps))

        val replacement = pending.copy(content = "New plan")
        restarted.updateSteps("chat", listOf(replacement))
        assertEquals(listOf(replacement), visibleHeaderPlanSteps(restarted.read("chat").steps))

        val completed = replacement.copy(status = PlanStepStatus.COMPLETED)
        restarted.updateSteps("chat", listOf(completed))
        val restoredCompleted = PlanStateRepository(backend)
        assertTrue(visibleHeaderPlanSteps(restoredCompleted.read("chat").steps).isEmpty())
        assertEquals(listOf(completed), restoredCompleted.read("chat").steps)

        restoredCompleted.clear("chat")
        assertTrue(PlanStateRepository(backend).read("chat").steps.isEmpty())
    }
}
