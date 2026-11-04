package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import org.junit.Assert.*
import org.junit.Test

class PlanModelStageValidationTest {
    private val executor = UpdatePlanTool()

    @Test fun acceptsAllExplicitPhasesAndLegacyPayloads() {
        for (stage in listOf(null, "generation", "implementation", "review", "chat")) {
            assertTrue(executor.validateParameters(request("[]", stage)).valid)
            assertTrue(executor.validateParameters(request("""[{"content":"Work","status":"pending"}]""", stage)).valid)
        }
    }

    @Test fun rejectsInvalidPhaseBeforeToolExecution() {
        val validation = executor.validateParameters(request("[]", "unexpected"))
        assertFalse(validation.valid)
        assertTrue(validation.errorMessage.contains("model_stage"))
    }

    @Test fun rejectsInvalidChecklistEvenWithValidPhase() {
        assertFalse(executor.validateParameters(request("{}", "generation")).valid)
        assertFalse(executor.validateParameters(request("""[{"content":"Work","status":"invalid"}]""", "implementation")).valid)
    }

    private fun request(todos: String, stage: String?): AITool = AITool(
        "update_plan",
        listOf(ToolParameter("todos", todos)) + if (stage == null) emptyList() else listOf(ToolParameter("model_stage", stage)),
    )
}
