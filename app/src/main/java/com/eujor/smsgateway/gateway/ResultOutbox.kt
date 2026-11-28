package com.eujor.smsgateway.gateway

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.eujor.smsgateway.data.AppConfig
import com.eujor.smsgateway.data.LogStore
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PendingCallbackSummary(val eventId:String,val jobId:String,val requestId:String,val status:String,val createdAt:Long,val nextAttemptAt:Long,val attempts:Int)

data class PendingResultEvent(
    val eventId: String,
    val config: AppConfig,
    val body: JSONObject,
    val createdAt: Long,
    val expiresAt: Long,
    val nextAttemptAt: Long
)

/**
 * Durable callback outbox. This database is deliberately separate from the
 * request queue and diagnostic logs, so 02:00 maintenance cannot clear it.
 */
class ResultOutbox(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE_RESULTS (
                event_id TEXT NOT NULL PRIMARY KEY,
                payload_encrypted TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                next_attempt_at INTEGER NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX idx_result_outbox_created ON $TABLE_RESULTS(created_at)")
        db.execSQL("CREATE INDEX idx_result_outbox_expiry ON $TABLE_RESULTS(expires_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This is a new, independent database. Future schema changes must use explicit migrations.
    }

    @Synchronized
    fun enqueue(config: AppConfig, resultBody: JSONObject): String {
        require(config.serverUrl.startsWith("https://", true) && config.apiKey.isNotBlank()) {
            "A configured HTTPS receiver and device API key are required."
        }
        val eventId = com.eujor.smsgateway.data.UuidV7.generate()
        val body = JSONObject(resultBody.toString()).put("event_id", eventId)
        val securePayload = JSONObject()
            .put("server_url", config.serverUrl.trim().trimEnd('/'))
            .put("api_key", config.apiKey)
            .put("device_id", config.deviceId)
            .put("device_name", config.deviceName)
            .put("body", body)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("event_id", eventId)
            put("payload_encrypted", encrypt(securePayload.toString()))
            put("created_at", now)
            put("expires_at", now + MAX_AGE_MS)
            put("next_attempt_at", now)
            put("attempts", 0)
        }
        writableDatabase.insertOrThrow(TABLE_RESULTS, null, values)
        return eventId
    }

    @Synchronized
    fun nextPending(now: Long = System.currentTimeMillis(), excluded: Set<String> = emptySet()): PendingResultEvent? {
        writableDatabase.delete(TABLE_RESULTS, "expires_at<=?", arrayOf(now.toString()))
        readableDatabase.query(
            TABLE_RESULTS,
            arrayOf("event_id", "payload_encrypted", "created_at", "expires_at", "next_attempt_at"),
            "next_attempt_at<=? AND expires_at>?", arrayOf(now.toString(), now.toString()),
            null, null, "created_at ASC, rowid ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getString(0)
                if (eventId in excluded) continue
                val payload = try {
                    JSONObject(decrypt(cursor.getString(1)))
                } catch (_: Exception) {
                    writableDatabase.delete(TABLE_RESULTS, "event_id=?", arrayOf(eventId))
                    LogStore.append(contextForLog, "An unreadable callback record was discarded.")
                    continue
                }
                val config = AppConfig(
                    serverUrl = payload.optString("server_url"),
                    apiKey = payload.optString("api_key"),
                    deviceId = payload.optString("device_id"),
                    deviceName = payload.optString("device_name")
                )
                return PendingResultEvent(
                    eventId = eventId,
                    config = config,
                    body = payload.optJSONObject("body") ?: JSONObject(),
                    createdAt = cursor.getLong(2),
                    expiresAt = cursor.getLong(3),
                    nextAttemptAt = cursor.getLong(4)
                )
            }
        }
        return null
    }

    @Synchronized
    fun pendingUssdCallbacks(now: Long = System.currentTimeMillis()): List<PendingCallbackSummary> {
        writableDatabase.delete(TABLE_RESULTS, "expires_at<=?", arrayOf(now.toString()))
        val items = mutableListOf<PendingCallbackSummary>()
        readableDatabase.query(TABLE_RESULTS, arrayOf("event_id","payload_encrypted","created_at","next_attempt_at","attempts"), null,null,null,null,"created_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                val payload = runCatching { JSONObject(decrypt(cursor.getString(1))) }.getOrNull() ?: continue
                val body = payload.optJSONObject("body") ?: continue
                // Include progress events (screen_updated/input_queued/input_submitted) as well
                // as final events so every unconfirmed USSD callback can be killed.
                if (body.optString("type") != "ussd") continue
                val status = body.optString("status").ifBlank { "pending" }
                items += PendingCallbackSummary(cursor.getString(0), body.optString("job_id"), body.optString("request_id"), status, cursor.getLong(2), cursor.getLong(3), cursor.getInt(4))
            }
        }
        return items
    }

    @Synchronized
    fun cancelPending(eventId: String) {
        writableDatabase.delete(TABLE_RESULTS, "event_id=?", arrayOf(eventId))
    }

    @Synchronized
    fun markDelivered(eventId: String) {
        writableDatabase.delete(TABLE_RESULTS, "event_id=?", arrayOf(eventId))
    }

    @Synchronized
    fun recordFailedAttempt(eventId: String) {
        val attempts = android.database.DatabaseUtils.longForQuery(
            writableDatabase,
            "SELECT attempts FROM $TABLE_RESULTS WHERE event_id=?",
            arrayOf(eventId)
        ).toInt()
        val nextBatchAttempt = attempts + 1
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            if (nextBatchAttempt >= MAX_ATTEMPTS_PER_BATCH) {
                put("attempts", 0)
                put("next_attempt_at", now + BATCH_COOLDOWN_MS)
            } else {
                put("attempts", nextBatchAttempt)
                put("next_attempt_at", now + RETRY_INTERVAL_MS)
            }
        }
        writableDatabase.update(TABLE_RESULTS, values, "event_id=?", arrayOf(eventId))
    }

    @Synchronized
    fun resetRetryScheduleAfterNetworkRestore() {
        val values = ContentValues().apply {
            put("attempts", 0)
            put("next_attempt_at", System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_RESULTS, values, null, null)
    }

    @Synchronized
    fun pendingCount(now: Long = System.currentTimeMillis()): Int {
        writableDatabase.delete(TABLE_RESULTS, "expires_at<=?", arrayOf(now.toString()))
        return android.database.DatabaseUtils.longForQuery(
            readableDatabase,
            "SELECT COUNT(*) FROM $TABLE_RESULTS",
            null
        ).toInt()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val packed = cipher.iv + cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.DEFAULT)
        require(packed.size > 12) { "Encrypted callback record is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
        return String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8)
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    // The helper stores only the application context, never credentials in plaintext outside encryption.
    private val contextForLog: Context = context.applicationContext

    companion object {
        private const val DATABASE_NAME = "eujor_gateway_result_outbox.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_RESULTS = "gateway_result_outbox"
        private const val KEY_ALIAS = "eujor_smsgateway_result_outbox_v1"
        private const val MAX_AGE_MS = 12L * 60L * 60L * 1000L
        private const val MAX_ATTEMPTS_PER_BATCH = 6
        private const val RETRY_INTERVAL_MS = 10_000L
        private const val BATCH_COOLDOWN_MS = 5L * 60L * 1000L
    }
}

object ResultOutboxWorker {
    private val monitor = Object()
    private val cancelledEventIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var active = false
    private var kickRequested = false

    fun cancel(context: Context, eventId: String) {
        cancelledEventIds.add(eventId)
        ResultOutbox(context).use { it.cancelPending(eventId) }
        synchronized(monitor) { monitor.notifyAll() }
    }

    fun kick(context: Context) {
        synchronized(monitor) {
            kickRequested = true
            if (active) { monitor.notifyAll(); return }
            active = true
            kickRequested = false
            startLocked(context.applicationContext)
        }
    }

    private fun startLocked(context: Context) {
        Thread({ runWorker(context) }, "EujorResultOutbox").apply { isDaemon = true; start() }
    }

    private fun runWorker(context: Context) {
        val outbox = ResultOutbox(context)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (isValidatedNetwork(connectivity)) {
                        outbox.resetRetryScheduleAfterNetworkRestore()
                        synchronized(monitor) { monitor.notifyAll() }
                    }
                }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        outbox.resetRetryScheduleAfterNetworkRestore()
                        synchronized(monitor) { monitor.notifyAll() }
                    }
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            while (true) {
                val networkReady = isValidatedNetwork(connectivity)
                if (networkReady) {
                    while (inFlight.size < 4) {
                        val event = outbox.nextPending(excluded = inFlight) ?: break
                        if (!inFlight.add(event.eventId)) continue
                        executor.submit {
                            try {
                                if (cancelledEventIds.remove(event.eventId)) return@submit
                                GatewayApi.deliverQueuedResult(event)
                                outbox.markDelivered(event.eventId)
                                LogStore.append(
                                    context,
                                    "Receiver confirmed callback: type=" + event.body.optString("type", "unknown") +
                                        " status=" + event.body.optString("status", "unknown") +
                                        " job_id=" + event.body.optString("job_id") +
                                        " event_id=" + event.eventId + " HTTP=200."
                                )
                            } catch (_: Exception) {
                                outbox.recordFailedAttempt(event.eventId)
                                LogStore.append(context, "Callback " + event.eventId + " was not confirmed; it remains queued for retry.")
                            } finally {
                                inFlight.remove(event.eventId)
                                cancelledEventIds.remove(event.eventId)
                                synchronized(monitor) { monitor.notifyAll() }
                            }
                        }
                    }
                }
                val pending = outbox.pendingCount()
                if (pending == 0 && inFlight.isEmpty()) break
                synchronized(monitor) { monitor.wait(if (networkReady) 500L else 10_000L) }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            LogStore.append(context, "Callback outbox worker paused: " + (e.message ?: "worker error"))
        } finally {
            if (callback != null) runCatching { connectivity.unregisterNetworkCallback(callback) }
            executor.shutdown()
            runCatching { executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS) }
            if (!executor.isTerminated) executor.shutdownNow()
            val pendingAfterWorker = runCatching { outbox.pendingCount() > 0 }.getOrDefault(false)
            outbox.close()
            synchronized(monitor) {
                active = false
                val restart = kickRequested || pendingAfterWorker
                kickRequested = false
                if (restart) {
                    active = true
                    startLocked(context)
                }
            }
        }
    }

    private fun isValidatedNetwork(connectivity: ConnectivityManager): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
