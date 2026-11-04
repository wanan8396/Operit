package com.ai.assistance.operit.core.chat

import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind

/** Replace only our own typed turn; never strip lookalike text out of user history. */
internal object PlanContextInjection {
    private const val METADATA_KEY = "operit.persisted_plan_context"

    fun remove(history: List<PromptTurn>): List<PromptTurn> =
        history.filterNot { it.kind == PromptTurnKind.SYSTEM && it.metadata[METADATA_KEY] == true }

    fun attach(history: List<PromptTurn>, planContext: String): List<PromptTurn> {
        val turns = remove(history).toMutableList()
        if (planContext.isNotEmpty()) {
            val position = turns.indexOfFirst { it.kind != PromptTurnKind.SYSTEM }
                .let { if (it < 0) turns.size else it }
            turns.add(position, PromptTurn(
                kind = PromptTurnKind.SYSTEM,
                content = planContext,
                metadata = mapOf(METADATA_KEY to true)
            ))
        }
        return turns
    }
}
