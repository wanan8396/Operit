package com.ai.assistance.operit.api.chat.enhance

/** Reserves a complete model tool batch before any member is executed. */
class ToolCallBudget(private val limit: Int?) {
    init { require(limit == null || limit in 1..32) { "maxToolCalls must be in 1..32" } }
    private var used = 0

    @Synchronized
    fun reserve(count: Int): Boolean {
        require(count >= 0)
        if (limit != null && count > limit - used) return false
        used = if (Int.MAX_VALUE - used < count) Int.MAX_VALUE else used + count
        return true
    }

    @Synchronized
    fun usedCalls(): Int = used
}
