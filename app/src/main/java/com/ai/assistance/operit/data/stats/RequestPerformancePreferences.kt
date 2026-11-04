package com.ai.assistance.operit.data.stats

import android.content.Context

/** Dedicated preference file contains only per-chat sums and counts. */
class RequestPerformancePreferences(context: Context) : RequestPerformancePersistence {
    private val preferences = context.applicationContext.getSharedPreferences(
        "request_performance_averages_v1", Context.MODE_PRIVATE,
    )

    override fun load(): Map<String, RequestPerformanceAggregate> =
        preferences.all.mapNotNull { (id, value) ->
            RequestPerformanceCodec.decode(value as? String)?.let { id to it }
        }.toMap()

    override fun save(values: Map<String, RequestPerformanceAggregate>) {
        // apply updates memory synchronously, then serializes disk writes off the UI thread.
        val editor = preferences.edit()
        values.forEach { (id, value) -> editor.putString(id, RequestPerformanceCodec.encode(value)) }
        editor.apply()
    }

    override fun clear(chatId: String) {
        preferences.edit().remove(chatId).apply()
    }
}
