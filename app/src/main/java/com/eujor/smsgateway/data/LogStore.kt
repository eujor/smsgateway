package com.eujor.smsgateway.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.text.ParseException

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

    fun cleanupOlderThan(context: Context, cutoffMillis: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val retained = prefs.getString(KEY, "").orEmpty().lines().filter { line ->
            if (line.isBlank()) return@filter false
            val timestamp = Regex("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})]")
                .find(line)?.groupValues?.getOrNull(1)
            if (timestamp == null) return@filter true
            val parsed = try { formatter.parse(timestamp)?.time } catch (_: ParseException) { null }
            parsed == null || parsed >= cutoffMillis
        }.takeLast(MAX_LINES)
        prefs.edit().putString(KEY, retained.joinToString("\n")).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
