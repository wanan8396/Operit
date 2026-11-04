package com.ai.assistance.operit.core.tools.defaultTool.standard

enum class SubagentProfile(val wireName: String) {
    GENERAL_PURPOSE("general-purpose"),
    EXPLORE("Explore");

    companion object {
        fun parse(value: String): SubagentProfile = values().firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown subagent_type: $value")
    }
}

/** Enforced before permissions, package activation and execution, not just in the prompt. */
object SubagentPolicy {
    private val disallowed = setOf(
        "run_subagent", "run_subagents", "subagent_run", "Agent", "Task", "update_plan",
        "EnterPlanMode", "ExitPlanMode", "enter_plan_mode", "exit_plan_mode", "read_session_context"
    )
    val exploreTools = setOf(
        "list_files", "read_file", "read_file_part", "find_files", "grep_code",
        "grep_context", "visit_web", "query_memory", "get_memory_by_title", "read_plan"
    )

    fun denial(toolName: String, isSubTask: Boolean, profile: SubagentProfile?): String? {
        if (!isSubTask) return null
        if (toolName.substringAfterLast(':') in disallowed) {
            return "Child agents cannot delegate, change the parent plan, or retrieve primary-session history"
        }
        if (profile == SubagentProfile.EXPLORE && toolName !in exploreTools) {
            return "Explore is read-only; tool $toolName is not allowed"
        }
        return null
    }
}
