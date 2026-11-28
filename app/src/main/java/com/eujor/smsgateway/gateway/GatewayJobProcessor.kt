package com.eujor.smsgateway.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.eujor.smsgateway.data.AppConfig
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.telephony.SmsExecutor
import com.eujor.smsgateway.telephony.UssdAccessibilityService
import com.eujor.smsgateway.telephony.UssdChainState
import com.eujor.smsgateway.telephony.UssdExecutor
import org.json.JSONArray
import org.json.JSONObject

enum class JobProcessOutcome { FINISHED, WAITING_FOR_SMS, WAITING_FOR_USSD, FAILED }

object GatewayJobProcessor {
    fun process(context: Context, config: AppConfig, job: JSONObject): JobProcessOutcome {
        val jobId = job.optString("job_id")
        val requestId = job.optString("request_id")
        val type = job.optString("type").lowercase()
        // Remote jobs must state their action explicitly. Never infer "execute"
        // for a USSD job: a malformed/incomplete server payload must fail closed.
        val action = job.optString("action").trim().lowercase()
        val simSlot = job.optInt("sim_slot", 1)

        try {
            require(jobId.isNotBlank()) { "Remote job is missing job_id." }
            require(requestId.isNotBlank()) { "Remote job is missing request_id." }
            require(action.isNotBlank()) { "Remote job is missing an explicit action; no operation was executed." }
            require(job.optString("device_id") == config.deviceId) { "Remote job device_id does not match this device." }
            require(simSlot in 1..4) { "Remote job sim_slot must be between 1 and 4." }
            val expiresAt = job.optString("expires_at")
            if (expiresAt.isNotBlank()) {
                val expiry = java.time.Instant.parse(expiresAt)
                require(expiry.isAfter(java.time.Instant.now())) { "Remote job has expired." }
            }

            when {
                type == "sms" && action == "send" -> {
                    val to = job.optString("to")
                    val message = job.optString("message")
                    require(to.matches(Regex("^\\+?[0-9]{7,15}$"))) { "Invalid SMS recipient." }
                    require(message.isNotBlank()) { "SMS message is empty." }
                    require(ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                        "SMS permission has not been granted on this device."
                    }
                    SmsExecutor.send(context, config, jobId, requestId, to, message, simSlot)
                    return JobProcessOutcome.WAITING_FOR_SMS
                }
                type == "ussd" && action == "execute" -> {
                    val code = job.optString("code")
                    require(code.startsWith("*") && code.endsWith("#")) { "Invalid USSD code." }
                    // Match the app's optional-chain behavior: blank entries are ignored.
                    // An empty chain means direct USSD; a populated chain means interactive USSD.
                    val inputs = readInputs(job.optJSONArray("inputs")).filter { it.isNotBlank() }
                    require(ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                        "Phone permission is required for USSD."
                    }
                    require(!UssdChainState.isActive()) { "Another USSD session is already active." }

                    val trackingId = job.optString("tracking_id").ifBlank { job.optString("ussd_tracking_id") }
                    require(trackingId.isNotBlank()) { "Server USSD job is missing tracking_id." }
                    UssdChainState.begin(requestId, jobId, simSlot, inputs, trackingId)
                    if (inputs.isNotEmpty()) {
                        require(UssdAccessibilityService.isEnabled(context)) {
                            "Enable EUJOR USSD Session Assistant before chained USSD."
                        }
                        UssdExecutor.startInteractive(context, code, simSlot)
                        LogStore.append(context, "Started interactive USSD job " + jobId + " with " + inputs.size + " queued inputs; values redacted.")
                    } else {
                        LogStore.append(context, "Started direct USSD job " + jobId + " with no input chain.")
                        UssdExecutor.sendDirect(context, config, jobId, requestId, code, simSlot, UssdChainState.trackingId)
                    }
                    return JobProcessOutcome.WAITING_FOR_USSD
                }
                type == "ussd" && action == "continue" -> {
                    val sessionId = job.optString("session_id")
                    require(sessionId.isNotBlank() && job.has("input") && job.opt("input") is String) {
                        "USSD continuation requires session_id and a string input."
                    }
                    require(UssdChainState.matches(sessionId)) { "No active session matches the supplied session_id." }
                    val trackingId = job.optString("tracking_id").ifBlank { job.optString("ussd_tracking_id") }
                    require(trackingId.isNotBlank() && UssdChainState.matchesTracking(trackingId)) { "USSD tracking_id does not match the active session." }
                    UssdAccessibilityService.submitInput(sessionId, job.getString("input"))
                    GatewayApi.postResult(
                        context, config, jobId, requestId, "ussd", "input_queued",
                        JSONObject().put("session_id", sessionId).put("tracking_id", trackingId)
                    )
                }
                type == "ussd" && action == "cancel" -> {
                    val sessionId = job.optString("session_id")
                    require(UssdChainState.matches(sessionId)) { "No active session matches the supplied session_id." }
                    val trackingId = job.optString("tracking_id").ifBlank { job.optString("ussd_tracking_id") }
                    require(trackingId.isNotBlank() && UssdChainState.matchesTracking(trackingId)) { "USSD tracking_id does not match the active session." }
                    UssdChainState.finish("cancelled")
                    val lastResponse = com.eujor.smsgateway.telephony.UssdResultStore.latest
                    GatewayApi.postResult(context, config, jobId, requestId, "ussd", "cancelled",
                        JSONObject()
                            .put("session_id", sessionId)
                            .put("response", lastResponse)
                            .put("last_response", lastResponse)
                            .put("session_ended", true)
                            .put("end_reason", "cancelled")
                            .put("carrier_outcome", com.eujor.smsgateway.telephony.UssdResultStore.classifyCarrierOutcome(lastResponse))
                            .put("tracking_id", trackingId))
                }
                else -> throw IllegalArgumentException("Unsupported job type/action: " + type + "/" + action)
            }
            return JobProcessOutcome.FINISHED
        } catch (e: Exception) {
            if (type == "ussd" && UssdChainState.matches(requestId)) UssdChainState.finish("failed")
            val message = e.message ?: "Could not process the request."
            LogStore.append(context, "Job " + (jobId.ifBlank { "unknown" }) + " failed: " + message)
            if (jobId.isNotBlank() && requestId.isNotBlank()) {
                runCatching {
                    GatewayApi.postResult(
                        context, config, jobId, requestId, type.ifBlank { "unknown" }, "failed",
                        JSONObject().put("error", message)
                    )
                }
            }
            return JobProcessOutcome.FAILED
        }
    }

    private fun readInputs(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        require(array.length() <= 20) { "USSD input chain may contain at most 20 entries." }
        return (0 until array.length()).map { index ->
            val value = array.opt(index)
            require(value is String) { "Every USSD input must be a string." }
            require(value.length <= 500) { "USSD input " + (index + 1) + " is too long." }
            value
        }
    }
}
