package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepPriority
import com.ai.assistance.operit.data.model.PlanStepStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict parser for the `todos` argument of the `update_plan` tool.
 *
 * Validation is intentionally total: a single malformed entry rejects the whole payload so the
 * stored plan can never be a half-applied mixture of two model intents. Mirrors the constraints of
 * ZCode's `TodoWriteInputSchema` (content required, status and priority from closed enums).
 */
object PlanStepRequest {

    const val MAX_STEPS = 50
    const val MAX_CONTENT_LENGTH = 500

    /**
     * Parses the raw `todos` parameter (a JSON array string) into a validated list.
     *
     * @throws IllegalArgumentException with a model-actionable message on any violation.
     */
    fun parse(rawTodos: String): List<PlanStep> {
        val trimmed = rawTodos.trim()
        require(trimmed.isNotEmpty()) { "todos must not be blank" }
        require(trimmed.length <= 128_000) { "todos exceeds the payload limit" }
        require(trimmed.startsWith("[") && trimmed.endsWith("]")) { "todos must be a JSON array" }

        val array =
            try {
                JSONArray(trimmed)
            } catch (error: Exception) {
                throw IllegalArgumentException("todos must be a JSON array: ${error.message}", error)
            }

        require(array.length() <= MAX_STEPS) {
            "todos supports at most $MAX_STEPS steps, got ${array.length()}"
        }
        val steps = ArrayList<PlanStep>(array.length())
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index)
                ?: throw IllegalArgumentException("todos[$index] must be a JSON object")
            steps.add(parseStep(entry, index))
        }
        return steps
    }

    private fun parseStep(entry: JSONObject, index: Int): PlanStep {
        val content = (entry.opt("content") as? String)?.trim()
            ?: throw IllegalArgumentException("todos[$index].content must be a string")
        require(content.isNotEmpty()) { "todos[$index].content is required" }
        require(content.length <= MAX_CONTENT_LENGTH) {
            "todos[$index].content exceeds $MAX_CONTENT_LENGTH characters"
        }

        val statusRaw = (entry.opt("status") as? String)?.trim()
            ?: throw IllegalArgumentException("todos[$index].status must be a string")
        val status = PlanStepStatus.fromWireValue(statusRaw)
            ?: throw IllegalArgumentException(
                "todos[$index].status must be one of pending|in_progress|completed"
            )

        val priorityRaw = if (!entry.has("priority")) "medium" else
            (entry.opt("priority") as? String)?.trim()
                ?: throw IllegalArgumentException("todos[$index].priority must be a string")
        val priority = PlanStepPriority.fromWireValue(priorityRaw)
            ?: throw IllegalArgumentException(
                "todos[$index].priority must be one of high|medium|low"
            )

        return PlanStep(content = content, status = status, priority = priority)
    }

    /** Serialises a plan back into the model-facing wire shape (used by the read tool). */
    fun toJson(steps: List<PlanStep>): JSONArray {
        val array = JSONArray()
        steps.forEach { step ->
            array.put(
                JSONObject()
                    .put("content", step.content)
                    .put("status", PlanStepStatus.toWireValue(step.status))
                    .put("priority", PlanStepPriority.toWireValue(step.priority))
            )
        }
        return array
    }
}