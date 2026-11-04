package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.PlanModelStage
import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepStatus

/** Durable phase backed by the same transaction source as PlanStepStore. */
object PlanModelStageStore {
    fun initialize(context: android.content.Context) = PlanStepStore.initialize(context)
    fun read(chatId: String): PlanModelStage? = PlanStepStore.readState(chatId).stage
    fun update(chatId: String, stage: PlanModelStage?) = PlanStepStore.updateStage(chatId, stage)
    /** Clear only phase. PlanStepStore.clear clears both phase and steps. */
    fun clear(chatId: String) { update(chatId, null) }
    fun resolve(rawStage: String?, steps: List<PlanStep>): PlanModelStage? {
        if (rawStage != null) return when (rawStage) {
            "generation" -> PlanModelStage.GENERATION
            "implementation" -> PlanModelStage.IMPLEMENTATION
            "review" -> PlanModelStage.REVIEW
            "chat" -> null
            else -> throw IllegalArgumentException("model_stage must be generation, implementation, review or chat")
        }
        return when {
            steps.isEmpty() -> null
            steps.all { it.status == PlanStepStatus.COMPLETED } -> PlanModelStage.REVIEW
            steps.any { it.status == PlanStepStatus.IN_PROGRESS } -> PlanModelStage.IMPLEMENTATION
            else -> PlanModelStage.GENERATION
        }
    }
}
