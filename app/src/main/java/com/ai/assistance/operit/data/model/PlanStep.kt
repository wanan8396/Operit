package com.ai.assistance.operit.data.model

/**
 * One step of the session working plan, mirroring the checklist model used by ZCode's TodoWrite:
 * a session-scoped, model-authored plan that is rendered to the user as live progress.
 *
 * Status is deliberately a three-value scale; the UI reserves "in progress" for a static marker so
 * it never reads as a loading spinner.
 */
enum class PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED;

    companion object {
        /** Accepts the model-facing wire values; anything else is rejected by the parser. */
        fun fromWireValue(value: String): PlanStepStatus? =
            when (value.trim().lowercase().replace('-', '_')) {
                "pending" -> PENDING
                "in_progress" -> IN_PROGRESS
                "completed" -> COMPLETED
                else -> null
            }

        fun toWireValue(status: PlanStepStatus): String =
            when (status) {
                PENDING -> "pending"
                IN_PROGRESS -> "in_progress"
                COMPLETED -> "completed"
            }
    }
}

/** Priority is display-only metadata; it never changes execution order. */
enum class PlanStepPriority {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun fromWireValue(value: String): PlanStepPriority? =
            when (value.trim().lowercase()) {
                "high" -> HIGH
                "medium" -> MEDIUM
                "low" -> LOW
                else -> null
            }

        fun toWireValue(priority: PlanStepPriority): String =
            when (priority) {
                HIGH -> "high"
                MEDIUM -> "medium"
                LOW -> "low"
            }
    }
}

/**
 * A single plan step. [content] is the model-authored task text; it is stored verbatim so the user
 * sees exactly what the model committed to.
 */
data class PlanStep(
    val content: String,
    val status: PlanStepStatus,
    val priority: PlanStepPriority = PlanStepPriority.MEDIUM,
)

/** Aggregate counters, kept in sync with the list so the collapsed header needs no recomputation. */
data class PlanStepSummary(
    val total: Int,
    val pending: Int,
    val inProgress: Int,
    val completed: Int,
) {
    companion object {
        fun of(steps: List<PlanStep>): PlanStepSummary =
            PlanStepSummary(
                total = steps.size,
                pending = steps.count { it.status == PlanStepStatus.PENDING },
                inProgress = steps.count { it.status == PlanStepStatus.IN_PROGRESS },
                completed = steps.count { it.status == PlanStepStatus.COMPLETED },
            )
    }
}