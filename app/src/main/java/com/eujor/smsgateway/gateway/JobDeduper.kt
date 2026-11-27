package com.eujor.smsgateway.gateway

import android.content.Context
import org.json.JSONArray

object JobDeduper {
    private const val PREFS = "eujor_job_dedup"
    private const val KEY = "recent_job_ids"
    private const val MAX_IDS = 500

    @Synchronized
    fun claim(context: Context, jobId: String): Boolean {
        if (jobId.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = runCatching { JSONArray(prefs.getString(KEY, "[]")) }.getOrElse { JSONArray() }
        for (index in 0 until current.length()) {
            if (current.optString(index) == jobId) return false
        }
        val next = JSONArray()
        val start = (current.length() - MAX_IDS + 1).coerceAtLeast(0)
        for (index in start until current.length()) next.put(current.optString(index))
        next.put(jobId)
        return prefs.edit().putString(KEY, next.toString()).commit()
    }
}
