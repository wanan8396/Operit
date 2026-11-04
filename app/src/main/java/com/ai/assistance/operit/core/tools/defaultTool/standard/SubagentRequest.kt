package com.ai.assistance.operit.core.tools.defaultTool.standard

/** ZCode synchronous Agent input, with finite Operit execution budgets. */
data class SubagentRequest(
    val description: String,
    val prompt: String,
    val profile: SubagentProfile,
    val maxToolCalls: Int,
    val timeoutSeconds: Int,
) {
    companion object {
        fun parse(parameters: List<Pair<String, String>>): SubagentRequest {
            val allowed = setOf("description", "prompt", "subagent_type", "run_in_background",
                "max_tool_calls", "timeout_seconds")
            require(parameters.all { it.first in allowed }) { "Unsupported subagent parameter" }
            require(parameters.map { it.first }.distinct().size == parameters.size) { "Duplicate subagent parameter" }
            val params = parameters.toMap()
            val description = params["description"]?.trim().orEmpty()
            require(description.isNotEmpty() && description.length <= 200) { "description must contain 1..200 characters" }
            val prompt = params["prompt"]?.trim().orEmpty()
            require(prompt.isNotEmpty() && prompt.length <= 48_000) { "prompt must contain 1..48000 characters" }
            val background = params["run_in_background"] ?: "false"
            require(background == "true" || background == "false") { "run_in_background must be a boolean" }
            require(background == "false") { "Background agents are not supported by this runtime" }
            fun bounded(name: String, default: Int, range: IntRange): Int {
                val raw = params[name] ?: return default
                val value = raw.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer")
                require(value in range) { "$name must be in $range" }
                return value
            }
            return SubagentRequest(description, prompt,
                SubagentProfile.parse(params["subagent_type"] ?: "general-purpose"),
                bounded("max_tool_calls", 8, 1..32), bounded("timeout_seconds", 180, 10..600))
        }
    }
}
