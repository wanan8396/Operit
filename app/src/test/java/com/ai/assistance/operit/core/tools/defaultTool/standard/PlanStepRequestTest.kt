package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.data.model.PlanStepPriority
import com.ai.assistance.operit.data.model.PlanStepStatus
import org.junit.Assert.*
import org.junit.Test

class PlanStepRequestTest {
    @Test fun parsesAndRoundTrips() {
        val steps = PlanStepRequest.parse("""[{"content":"Inspect","status":"in_progress","priority":"high"}]""")
        assertEquals(PlanStepStatus.IN_PROGRESS, steps.single().status)
        assertEquals(PlanStepPriority.HIGH, steps.single().priority)
        assertEquals(steps, PlanStepRequest.parse(PlanStepRequest.toJson(steps).toString()))
    }

    @Test fun emptyListClearsPlan() {
        assertTrue(PlanStepRequest.parse("[]").isEmpty())
    }

    @Test fun priorityDefaultsToMedium() {
        assertEquals(PlanStepPriority.MEDIUM,
            PlanStepRequest.parse("""[{"content":"Inspect","status":"pending"}]""").single().priority)
    }

    @Test fun rejectsInvalidPayloads() {
        val invalid = listOf("", "{}", "[null]", "[1]",
            """[{"content":12,"status":"pending"}]""",
            """[{"content":" ","status":"pending"}]""",
            """[{"content":"Inspect","status":"invalid"}]""",
            """[{"content":"Inspect","status":"pending","priority":null}]""")
        invalid.forEach { raw ->
            try {
                PlanStepRequest.parse(raw)
                fail("Accepted invalid payload: $raw")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooManySteps() {
        val step = """{"content":"Inspect","status":"pending"}"""
        PlanStepRequest.parse((1..51).joinToString(prefix = "[", postfix = "]") { step })
    }
}
