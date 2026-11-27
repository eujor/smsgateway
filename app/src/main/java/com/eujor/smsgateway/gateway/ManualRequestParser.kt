package com.eujor.smsgateway.gateway

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

data class GatewayRequest(
    val requestId: String,
    val jobId: String,
    val type: String,
    val action: String,
    val simSlot: Int,
    val recipient: String = "",
    val message: String = "",
    val ussdCode: String = "",
    val inputs: List<String> = emptyList(),
    val sessionId: String = ""
)

sealed class RequestParseResult {
    data class Valid(val request: GatewayRequest) : RequestParseResult()
    data class Invalid(val issue: String) : RequestParseResult()
}

object ManualRequestParser {
    fun parse(raw: String): RequestParseResult {
        if (raw.isBlank()) return RequestParseResult.Invalid("JSON is empty. Paste a request object first.")
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return RequestParseResult.Invalid("JSON syntax error: " + (e.message ?: "invalid JSON"))
        }

        val typeValue = root.opt("type")
        if (typeValue !is String) return RequestParseResult.Invalid("Field 'type' is required and must be a string.")
        val type = typeValue.trim().lowercase()
        if (type != "sms" && type != "ussd") {
            return RequestParseResult.Invalid("Field 'type' must be either 'sms' or 'ussd'.")
        }

        val actionValue = root.opt("action")
        if (root.has("action") && actionValue !is String) {
            return RequestParseResult.Invalid("Field 'action' must be a string.")
        }
        val action = (actionValue as? String).orEmpty()
            .ifBlank { if (type == "sms") "send" else "execute" }.lowercase()
        if (type == "sms" && action != "send") return RequestParseResult.Invalid("SMS action must be 'send'.")
        if (type == "ussd" && action !in setOf("execute", "continue", "cancel")) {
            return RequestParseResult.Invalid("USSD action must be 'execute', 'continue' or 'cancel'.")
        }

        val simValue = root.opt("sim_slot")
        if (simValue != null && simValue !is Number) {
            return RequestParseResult.Invalid("Field 'sim_slot' must be a JSON number between 1 and 4.")
        }
        val simSlot = (simValue as? Number)?.toInt() ?: 1
        if (simSlot !in 1..4 || (simValue is Number && simValue.toDouble() != simSlot.toDouble())) {
            return RequestParseResult.Invalid("Field 'sim_slot' must be a whole number between 1 and 4.")
        }

        val requestId = (root.opt("request_id") as? String)?.takeIf { it.isNotBlank() }
            ?: "manual-" + UUID.randomUUID()
        val jobId = (root.opt("job_id") as? String)?.takeIf { it.isNotBlank() } ?: requestId
        val sessionId = root.opt("session_id") as? String ?: ""

        if (type == "sms") {
            val toValue = root.opt("to")
            val messageValue = root.opt("message")
            if (toValue !is String) return RequestParseResult.Invalid("Field 'to' is required and must be a string.")
            if (messageValue !is String) return RequestParseResult.Invalid("Field 'message' is required and must be a string.")
            val to = toValue.trim()
            if (!to.matches(Regex("^\\+?[0-9]{7,15}$"))) {
                return RequestParseResult.Invalid("Field 'to' must be a 7–15 digit phone number, optionally prefixed with +.")
            }
            if (messageValue.isBlank()) return RequestParseResult.Invalid("Field 'message' cannot be empty.")
            if (messageValue.length > 5000) return RequestParseResult.Invalid("Field 'message' is too long (maximum 5,000 characters).")
            return RequestParseResult.Valid(
                GatewayRequest(requestId, jobId, type, action, simSlot, recipient = to, message = messageValue)
            )
        }

        if (action == "continue") {
            if (sessionId.isBlank()) return RequestParseResult.Invalid("Field 'session_id' is required for USSD continue.")
            val inputValue = root.opt("input")
            if (inputValue !is String) return RequestParseResult.Invalid("Field 'input' is required and must be a string for USSD continue.")
            if (inputValue.length > 500) return RequestParseResult.Invalid("Field 'input' is too long (maximum 500 characters).")
            return RequestParseResult.Valid(
                GatewayRequest(requestId, jobId, type, action, simSlot, inputs = listOf(inputValue), sessionId = sessionId)
            )
        }

        if (action == "cancel") {
            if (sessionId.isBlank()) return RequestParseResult.Invalid("Field 'session_id' is required for USSD cancel.")
            return RequestParseResult.Valid(
                GatewayRequest(requestId, jobId, type, action, simSlot, sessionId = sessionId)
            )
        }

        val codeValue = root.opt("code")
        if (codeValue !is String) return RequestParseResult.Invalid("Field 'code' is required and must be a string.")
        val code = codeValue.trim()
        if (code.length !in 3..80 || !code.startsWith("*") || !code.endsWith("#") ||
            code.any { !it.isDigit() && it != '*' && it != '#' }
        ) return RequestParseResult.Invalid("Field 'code' must be a USSD string such as '*144#'.")

        val rawInputs = when {
            root.has("inputs") -> root.opt("inputs")
            root.has("input_chain") -> root.opt("input_chain")
            else -> JSONArray()
        }
        if (rawInputs !is JSONArray) return RequestParseResult.Invalid("Field 'inputs' must be a JSON array of strings.")
        if (rawInputs.length() > 20) return RequestParseResult.Invalid("Field 'inputs' may contain at most 20 entries.")
        val inputs = mutableListOf<String>()
        for (index in 0 until rawInputs.length()) {
            val item = rawInputs.opt(index)
            if (item !is String) return RequestParseResult.Invalid("Every item in 'inputs' must be a string.")
            if (item.length > 500) return RequestParseResult.Invalid("Input item " + (index + 1) + " is too long (maximum 500 characters).")
            inputs += item
        }
        return RequestParseResult.Valid(
            GatewayRequest(requestId, jobId, type, action, simSlot, ussdCode = code, inputs = inputs)
        )
    }
}
