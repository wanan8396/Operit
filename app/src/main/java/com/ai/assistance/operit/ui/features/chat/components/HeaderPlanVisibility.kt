package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepStatus

/** Show the whole current plan while unfinished; hiding must not erase persisted history. */
internal fun visibleHeaderPlanSteps(steps: List<PlanStep>): List<PlanStep> =
    if (steps.any { it.status != PlanStepStatus.COMPLETED }) steps else emptyList()
