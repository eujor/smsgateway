package com.eujor.smsgateway.data

import android.content.Context
import android.telephony.TelephonyManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

object LogStore {
    private const val PREFS = "eujor_gateway_logs"
    private const val KEY = "lines"
    private const val MAX_LINES = 300
    private val logTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.US)

    fun append(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val timestamp = logTimeFormat.withZone(detectedZone(context)).format(Instant.now())
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
        val retained = prefs.getString(KEY, "").orEmpty().lines().filter { line ->
            if (line.isBlank()) return@filter false
            val timestamp = Regex("^\\[([^]]+)]").find(line)?.groupValues?.getOrNull(1)
                ?: return@filter true
            val parsed = runCatching {
                OffsetDateTime.parse(timestamp, logTimeFormat).toInstant().toEpochMilli()
            }.getOrElse {
                // Retain support for entries written before timezone offsets were added.
                runCatching {
                    val legacy = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    legacy.timeZone = TimeZone.getTimeZone(detectedZone(context))
                    legacy.parse(timestamp)?.time
                }.getOrNull()
            }
            parsed == null || parsed >= cutoffMillis
        }.takeLast(MAX_LINES)
        prefs.edit().putString(KEY, retained.joinToString("\n")).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /**
     * Prefer the phone's configured timezone. If it is UTC/GMT but the SIM/network
     * identifies a country with exactly one timezone, use that regional timezone.
     * For countries with multiple timezones, the phone's configured timezone is
     * retained because a country code cannot determine the correct city/region.
     */
    private fun detectedZone(context: Context): ZoneId {
        val systemZone = ZoneId.systemDefault()
        val systemIsUtc = systemZone.id.equals("UTC", true) ||
            systemZone.id.equals("GMT", true) ||
            systemZone.id.equals("Etc/UTC", true) ||
            systemZone.id.equals("Etc/GMT", true)

        val telephonyCountry = runCatching {
            val manager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            manager?.networkCountryIso?.takeIf { it.isNotBlank() }
                ?: manager?.simCountryIso?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val localeCountry = Locale.getDefault().country
        val country = telephonyCountry?.takeIf { it.length == 2 }
            ?: localeCountry.takeIf { it.length == 2 }

        if (systemIsUtc && !country.isNullOrBlank()) {
            val countryZones = runCatching {
                TimeZone.getAvailableIDs(country.uppercase(Locale.ROOT))
                    .mapNotNull { id -> runCatching { ZoneId.of(id) }.getOrNull() }
                    .distinct()
            }.getOrDefault(emptyList())
            if (countryZones.size == 1) return countryZones.first()
        }
        return systemZone
    }
}
