package com.eujor.smsgateway.gateway

import android.content.Context
import android.os.Build
import com.eujor.smsgateway.BuildConfig
import com.eujor.smsgateway.data.AppConfig
import com.eujor.smsgateway.data.LogStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

object GatewayApi {
    fun remoteConfigured(config: AppConfig): Boolean =
        config.serverUrl.startsWith("https://", true) && config.apiKey.isNotBlank()

    fun agentConfigured(config: AppConfig): Boolean =
        config.apiKey.isNotBlank() && (remoteConfigured(config) ||
            (config.heartbeatEnabled && config.heartbeatUrl.startsWith("https://", true)))

    fun testConnection(config: AppConfig): String {
        val response = request(config, "/api/v1/agent/health", "GET", null, 10_000)
        return "Receiver server responded (HTTP " + response.first + ")."
    }

    fun heartbeat(config: AppConfig) {
        if (!config.heartbeatEnabled) return
        val endpoint = config.heartbeatUrl.trim()
        if (!endpoint.startsWith("https://", true)) {
            throw IOException("Heartbeat is enabled but its complete HTTPS URL is missing or invalid.")
        }
        val body = JSONObject()
            .put("device_id", config.deviceId)
            .put("device_name", config.deviceName)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("timestamp", Instant.now().toString())
        requestUrl(config, endpoint, "POST", body, 10_000)
    }

    fun nextJobs(config: AppConfig): List<JSONObject> {
        val device = URLEncoder.encode(config.deviceId, Charsets.UTF_8.name())
        val response = request(config, "/api/v1/agent/jobs/next?device_id=" + device + "&wait_seconds=20&limit=20", "GET", null, 25_000)
        if (response.first == 204 || response.second.isBlank()) return emptyList()
        val json = JSONObject(response.second)
        val batch = json.optJSONArray("jobs")
        if (batch != null) {
            return (0 until batch.length()).mapNotNull { batch.optJSONObject(it) }.take(20)
        }
        val single = json.optJSONObject("job")
        if (single != null) return listOf(single)
        return if (json.has("job") && json.isNull("job")) emptyList() else listOf(json)
    }

    fun nextJob(config: AppConfig): JSONObject? = nextJobs(config).firstOrNull()

    /**
     * Results are first persisted in the encrypted local outbox. The sender
     * deletes them only after an HTTP 200 response with the matching event ID.
     */
    fun postResult(context: Context, config: AppConfig, jobId: String, requestId: String, type: String, status: String, result: JSONObject = JSONObject()) {
        queueResult(context, config, jobId, requestId, type, status, result)
    }

    // Kept as a compatibility name for receiver-triggered events; enqueue is durable and non-blocking.
    fun postResultBlocking(context: Context, config: AppConfig, jobId: String, requestId: String, type: String, status: String, result: JSONObject = JSONObject()) {
        queueResult(context, config, jobId, requestId, type, status, result)
    }

    private fun queueResult(context: Context, config: AppConfig, jobId: String, requestId: String, type: String, status: String, result: JSONObject) {
        if (!remoteConfigured(config)) return
        val body = resultEnvelope(config, jobId, requestId, type, status, result)
        val outbox = ResultOutbox(context)
        try {
            val eventId = outbox.enqueue(config, body)
            LogStore.append(context, "Callback event queued locally: " + eventId)
        } finally {
            outbox.close()
        }
        ResultOutboxWorker.kick(context)
    }

    internal fun deliverQueuedResult(event: PendingResultEvent) {
        val response = request(event.config, "/api/v1/agent/results", "POST", event.body, 12_000)
        if (response.first != 200) {
            throw IOException("Callback acknowledgement must be HTTP 200; received HTTP " + response.first + ".")
        }
        val acknowledgement = try {
            JSONObject(response.second)
        } catch (_: Exception) {
            throw IOException("Callback acknowledgement must be a JSON object.")
        }
        if (!acknowledgement.optBoolean("success", false)) {
            throw IOException("Callback acknowledgement did not confirm success=true.")
        }
        if (acknowledgement.optString("event_id") != event.eventId) {
            throw IOException("Callback acknowledgement event_id did not match the submitted event.")
        }
    }

    private fun resultEnvelope(config: AppConfig, jobId: String, requestId: String, type: String, status: String, result: JSONObject) =
        JSONObject()
            .put("protocol_version", "1")
            .put("device_id", config.deviceId)
            .put("tracking_id", result.optString("tracking_id").takeIf { it.isNotBlank() })
            .put("job_id", jobId)
            .put("request_id", requestId)
            .put("type", type)
            .put("status", status)
            .put("result", result)
            .put("timestamp", Instant.now().toString())

    private fun request(config: AppConfig, path: String, method: String, body: JSONObject?, timeoutMs: Int): Pair<Int, String> {
        val base = config.serverUrl.trim().trimEnd('/')
        if (!base.startsWith("https://", true)) throw IOException("Receiver server URL must use HTTPS.")
        return requestUrl(config, base + path, method, body, timeoutMs)
    }

    private fun requestUrl(config: AppConfig, endpoint: String, method: String, body: JSONObject?, timeoutMs: Int): Pair<Int, String> {
        if (!endpoint.startsWith("https://", true)) throw IOException("Endpoint URL must use HTTPS.")
        if (config.apiKey.isBlank()) throw IOException("Device API key is missing.")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
            setRequestProperty("X-Device-ID", config.deviceId)
            body?.optString("tracking_id")?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("X-USSD-Tracking-ID", it)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && code != 204) throw IOException("Receiver server returned HTTP " + code + ".")
            return code to response
        } finally {
            connection.disconnect()
        }
    }
}
