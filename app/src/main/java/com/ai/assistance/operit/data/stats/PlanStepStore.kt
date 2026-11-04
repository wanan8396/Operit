package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.model.PlanModelStage
import com.ai.assistance.operit.data.model.PlanStep
import kotlinx.coroutines.flow.StateFlow

/** Durable chat-scoped plans. Initialize in Application before accessing either Store. */
object PlanStepStore {
    @Volatile private var initialized = false
    private lateinit var repository: PlanStateRepository

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        repository = PlanStateRepository(AtomicPlanStateBackend(context.applicationContext))
        initialized = true
    }

    private fun repository(): PlanStateRepository {
        check(initialized) { "PlanStepStore.initialize(context) must run before plan access" }
        return repository
    }

    val plans: StateFlow<Map<String, List<PlanStep>>> get() = repository().plans

    /** Legacy steps-only writes preserve phase in the same durable snapshot. */
    fun update(key: String, steps: List<PlanStep>): List<PlanStep> = repository().updateSteps(key, steps)

    /** Use one transaction for model-authored changes to both steps and phase. */
    fun update(key: String, steps: List<PlanStep>, stage: PlanModelStage?): List<PlanStep> =
        repository().update(key, steps, stage)

    fun read(key: String): List<PlanStep> = readState(key).steps.toList()
    fun readState(chatId: String): PlanStateSnapshot = repository().read(chatId)
    fun clear(key: String) { repository().clear(key) }
    fun formatPlanContext(chatId: String): String = repository().formatPlanContext(chatId)
    internal fun updateStage(chatId: String, stage: PlanModelStage?) {
        repository().updateStage(chatId, stage)
    }
}
