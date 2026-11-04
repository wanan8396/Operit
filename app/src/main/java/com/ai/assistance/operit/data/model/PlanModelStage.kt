package com.ai.assistance.operit.data.model

/** Persisted phase: generation/review use the planner; implementation uses the selected chat model. */
enum class PlanModelStage {
    GENERATION, IMPLEMENTATION, REVIEW;
}
