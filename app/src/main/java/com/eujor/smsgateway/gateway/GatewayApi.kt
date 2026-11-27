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
import java.util.concurrent.Executors

object GatewayApi {
    private val resultQueue = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EujorGatewayResults").apply { isDaemon = true }
    }

    fun remoteConfigured(config: AppConfig): Boolean =
        config.serverUrl.startsWith("https://", true) && config.apiKey.isNotBlank()

    fun testConnection(config: AppConfig): String {
        val response = request(config, "/api/v1/agent/health", "GET", null, 10_000)
        return "Receiver server responded (HTTP " + response.first + ")."
    }

    fun registerDevice(config: AppConfig, context: Context) {
        val body = JSONObject()
            .put("protocol_version", "1")
            .put("device_id", config.deviceId)
            .put("device_name", config.deviceName)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("android_version", Build.VERSION.RELEASE)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("capabilities", JSONArray(listOf("sms.send", "sms.receive", "sms.sent_report", "sms.delivery_report", "ussd.execute", "ussd.chain", "ussd.continue")))
        val response = request(config, "/api/v1/agent/devices/register", "POST", body, 12_000)
        val status = runCatching { JSONObject(response.second).optString("device_status") }.getOrDefault("")
        if (status != "approved") throw IOException("Device registration is not approved by the receiver server.")
        LogStore.append(context, "Device registration approved by receiver server.")
    }

    fun heartbeat(config: AppConfig) {
        val body = JSONObject()
            .put("device_id", config.deviceId)
            .put("device_name", config.deviceName)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("timestamp", Instant.now().toString())
        request(config, "/api/v1/agent/devices/heartbeat", "POST", body, 10_000)
    }

    fun nextJob(config: AppConfig): JSONObject? {
        val device = URLEncoder.encode(config.deviceId, Charsets.UTF_8.name())
        val response = request(config, "/api/v1/agent/jobs/next?device_id=" + device + "&wait_seconds=20", "GET", null, 25_000)
        if (response.first == 204 || response.second.isBlank()) return null
        val json = JSONObject(response.second)
        return if (json.optJSONObject("job") != null) json.optJSONObject("job") else if (json.isNull("job")) null else json
    }

    fun postResult(config: AppConfig, jobId: String, requestId: String, type: String, status: String, result: JSONObject = JSONObject()) {
        if (!remoteConfigured(config)) return
        val body = resultEnvelope(config, jobId, requestId, type, status, result)
        resultQueue.execute {
            runCatching { request(config, "/api/v1/agent/results", "POST", body, 12_000) }
        }
    }

    fun postResultBlocking(config: AppConfig, jobId: String, requestId: String, type: String, status: String, result: JSONObject = JSONObject()) {
        if (!remoteConfigured(config)) return
        request(config, "/api/v1/agent/results", "POST", resultEnvelope(config, jobId, requestId, type, status, result), 12_000)
    }

    private fun resultEnvelope(config: AppConfig, jobId: String, requestId: String, type: String, status: String, result: JSONObject) =
        JSONObject()
            .put("protocol_version", "1")
            .put("device_id", config.deviceId)
            .put("job_id", jobId)
            .put("request_id", requestId)
            .put("type", type)
            .put("status", status)
            .put("result", result)
            .put("timestamp", Instant.now().toString())

    private fun request(config: AppConfig, path: String, method: String, body: JSONObject?, timeoutMs: Int): Pair<Int, String> {
        val base = config.serverUrl.trim().trimEnd('/')
        if (!base.startsWith("https://", true)) throw IOException("Receiver server URL must use HTTPS.")
        if (config.apiKey.isBlank()) throw IOException("Device API key is missing.")
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 8_000
            readTimeout = timeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
            setRequestProperty("X-Device-ID", config.deviceId)
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
