package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.json.JSONArray
import org.json.JSONObject

object SubagentOutput {
    fun completed(id: String, request: SubagentRequest, text: String, calls: Int,
        durationMs: Long, inputTokens: Long, outputTokens: Long): JSONObject {
        require(calls >= 0 && durationMs >= 0 && inputTokens >= 0 && outputTokens >= 0)
        return JSONObject().put("status", "completed").put("agentId", id)
            .put("agentType", request.profile.wireName).put("description", request.description)
            .put("prompt", request.prompt)
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("totalToolUseCount", calls).put("totalDurationMs", durationMs)
            .put("totalTokens", Math.addExact(inputTokens, outputTokens))
    }
}