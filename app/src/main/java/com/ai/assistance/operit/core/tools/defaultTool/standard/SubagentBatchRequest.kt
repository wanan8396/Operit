package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.json.JSONArray
import org.json.JSONException

/** Validate the entire batch before launching any child; one malformed task rejects the batch. */
object SubagentBatchRequest {
    fun parse(parameters: List<Pair<String, String>>): List<SubagentRequest> {
        require(parameters.size == 1 && parameters.single().first == "tasks") {
            "run_subagents requires exactly one tasks parameter"
        }
        val raw = parameters.single().second.trim()
        require(raw.startsWith("[") && raw.endsWith("]")) { "tasks must be a JSON array" }
        val tasks = try {
            JSONArray(raw)
        } catch (error: JSONException) {
            throw IllegalArgumentException("tasks must be a valid JSON array", error)
        }
        require(tasks.length() > 0) { "tasks must contain at least one task" }

        // There is no batch-size quota. Each child retains its own input, time and tool budgets.
        return List(tasks.length()) { index ->
            val task = tasks.optJSONObject(index)
                ?: throw IllegalArgumentException("tasks[$index] must be an object")
            val fields = task.keys().asSequence().map { name ->
                val value = task.get(name)
                val text = when (name) {
                    "description", "prompt", "subagent_type" -> {
                        require(value is String) { "tasks[$index].$name must be a string" }
                        value
                    }
                    "run_in_background" -> {
                        require(value is Boolean) { "tasks[$index].$name must be a boolean" }
                        value.toString()
                    }
                    "max_tool_calls", "timeout_seconds" -> {
                        require(value is Int || value is Long) {
                            "tasks[$index].$name must be an integer"
                        }
                        value.toString()
                    }
                    else -> throw IllegalArgumentException("Unsupported tasks[$index] parameter: $name")
                }
                name to text
            }.toList()
            try {
                SubagentRequest.parse(fields)
            } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("tasks[$index]: ${error.message}", error)
            }
        }
    }
}
