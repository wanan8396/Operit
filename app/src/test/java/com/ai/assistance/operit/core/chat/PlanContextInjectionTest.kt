package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import org.junit.Assert.*
import org.junit.Test

class PlanContextInjectionTest {
    @Test fun replacesOnlyOwnedSystemTurnAcrossContinuation() {
        val system = PromptTurn(PromptTurnKind.SYSTEM, "system")
        val user = PromptTurn(PromptTurnKind.USER, "literal <authoritative_plan_state>keep me</authoritative_plan_state>")
        val once = PlanContextInjection.attach(listOf(system, user), "plan-v1")
        val twice = PlanContextInjection.attach(once, "plan-v2")
        assertEquals(listOf("system", "plan-v2", user.content), twice.map { it.content })
        assertEquals(once.size, twice.size)
        assertEquals(twice, PlanContextInjection.attach(twice, "plan-v2"))
        assertEquals(listOf(system, user), PlanContextInjection.attach(twice, ""))
    }

    @Test fun emptyBootstrapAndMissingSystemAreExplicit() {
        val task = PromptTurn(PromptTurnKind.USER, "work")
        val history = PlanContextInjection.attach(listOf(task), "generation with zero steps")
        assertEquals(PromptTurnKind.SYSTEM, history.first().kind)
        assertEquals(task, history.last())
        assertEquals(emptyList<PromptTurn>(), PlanContextInjection.attach(emptyList(), ""))
    }

    @Test fun removalPreservesUnrelatedMetadataAndSystemText() {
        val system = PromptTurn(PromptTurnKind.SYSTEM, "<authoritative_plan_state>unowned</authoritative_plan_state>",
            metadata = mapOf("other" to true))
        assertEquals(listOf(system), PlanContextInjection.remove(listOf(system)))
    }
}