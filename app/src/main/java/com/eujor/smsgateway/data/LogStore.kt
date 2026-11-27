package com.eujor.smsgateway.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogStore {
    private const val PREFS = "eujor_gateway_logs"
    private const val KEY = "lines"
    private const val MAX_LINES = 300

    fun append(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val safe = message
            .replace(Regex("(?i)(api[_ -]?key|authorization|bearer)[:= ]+[^ ,;]+"), "credential=[redacted]")
            .take(400)
        val lines = (prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() } +
            "[$timestamp] $safe").takeLast(MAX_LINES)
        prefs.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }.reversed()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
