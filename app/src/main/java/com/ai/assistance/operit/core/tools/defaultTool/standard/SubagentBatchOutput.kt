package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.data.model.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/** A completed batch contains every child result, including failures, in submission order. */
internal object SubagentBatchOutput {
    fun completed(
        requests: List<SubagentRequest>,
        results: List<ToolResult>,
        durationMs: Long,
    ): JSONObject {
        require(requests.isNotEmpty() && requests.size == results.size)
        require(durationMs >= 0)
        val succeeded = results.count { it.success }
        val items = JSONArray()
        requests.zip(results).forEachIndexed { index, (request, result) ->
            val item = JSONObject()
                .put("index", index)
                .put("description", request.description)
                .put("agentType", request.profile.wireName)
                .put("success", result.success)
            if (result.success) {
                item.put("result", JSONObject(result.result.toString()))
            } else {
                item.put("error", result.error ?: JSONObject.NULL)
            }
            items.put(item)
        }
        return JSONObject()
            .put("status", if (succeeded == results.size) "completed" else "completed_with_errors")
            .put("totalAgents", results.size)
            .put("succeeded", succeeded)
            .put("failed", results.size - succeeded)
            .put("totalDurationMs", durationMs)
            .put("results", items)
    }
}
