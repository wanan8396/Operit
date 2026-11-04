package com.ai.assistance.operit.data.preferences
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.PlanModelStage
import org.junit.Assert.assertEquals
import org.junit.Test
class SelectedRequestModelTest {
    private val mappings = mapOf(
        FunctionType.CHAT to FunctionConfigMapping("chat", 2),
        FunctionType.SUBAGENT to FunctionConfigMapping("child", 3),
        FunctionType.PLAN_GENERATION to FunctionConfigMapping("planner", 4),
        FunctionType.PLAN_EXECUTION to FunctionConfigMapping("implementer", 5),
    )
    @Test fun selectedChatModelSurvivesToolContinuations() {
        repeat(4) {
            assertEquals(FunctionConfigMapping("selected", 7), SelectedRequestModel.resolve(FunctionType.CHAT, mappings, "selected", 7, false))
        }
        assertEquals(FunctionConfigMapping("chat", 2), SelectedRequestModel.resolve(FunctionType.CHAT, mappings, null, null, false))
    }
    @Test fun generationAndReviewKeepPlannerImplementationKeepsSelection() {
        for (stage in listOf(PlanModelStage.GENERATION, PlanModelStage.REVIEW)) {
            assertEquals(FunctionConfigMapping("planner", 4), SelectedRequestModel.resolve(FunctionType.CHAT, mappings, "selected", 7, false, stage))
        }
        for (stage in listOf(null, PlanModelStage.IMPLEMENTATION)) {
            assertEquals(FunctionConfigMapping("selected", 7), SelectedRequestModel.resolve(FunctionType.CHAT, mappings, "selected", 7, false, stage))
        }
        assertEquals(FunctionConfigMapping("chat", 2), SelectedRequestModel.resolve(FunctionType.CHAT, mappings, null, null, false, PlanModelStage.IMPLEMENTATION))
    }
    @Test fun stageChangesAtEveryContinuationWithoutMutatingSelection() {
        val stages = listOf(PlanModelStage.GENERATION, PlanModelStage.IMPLEMENTATION, PlanModelStage.REVIEW, null)
        val expected = listOf("planner", "selected", "planner", "selected")
        stages.forEachIndexed { index, stage ->
            assertEquals(expected[index], SelectedRequestModel.resolve(FunctionType.CHAT, mappings, "selected", 7, false, stage).configId)
        }
    }
    @Test fun parentPlanPhaseDoesNotOverrideChildOrOtherFunction() {
        for (stage in PlanModelStage.values()) {
            assertEquals(FunctionConfigMapping("child", 3), SelectedRequestModel.resolve(FunctionType.CHAT, mappings, "selected", 7, true, stage))
            assertEquals(FunctionConfigMapping("planner", 4), SelectedRequestModel.resolve(FunctionType.PLAN_GENERATION, mappings, "selected", 7, false, stage))
        }
    }
    @Test fun childUsesIndependentBindingNotParentSelection() {
        assertEquals(FunctionConfigMapping("child", 3), SelectedRequestModel.resolve(FunctionType.CHAT, mappings, "selected", 7, true))
    }
}
