package com.eujor.smsgateway.data

import android.content.Context
import com.eujor.smsgateway.gateway.LocalJobQueue
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object DailyMaintenance {
    private const val PREFS = "eujor_gateway_maintenance"
    private const val LAST_RUN = "last_cleanup_date"

    @Synchronized
    fun runIfDue(context: Context, existingQueue: LocalJobQueue? = null) {
        val calendar = Calendar.getInstance()
        if (calendar.get(Calendar.HOUR_OF_DAY) < 2) return

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getString(LAST_RUN, "") == today) return

        val queue = existingQueue ?: LocalJobQueue(context)
        try {
            queue.cleanupOlderThan(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            LogStore.cleanupOlderThan(context, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
            preferences.edit().putString(LAST_RUN, today).apply()
            LogStore.append(context, "Daily maintenance completed: old queue records and logs cleaned.")
        } finally {
            if (existingQueue == null) queue.close()
        }
    }
}
