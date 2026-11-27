package com.eujor.smsgateway.telephony

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.eujor.smsgateway.data.AppConfig
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.gateway.GatewayApi
import org.json.JSONObject

object UssdExecutor {
    fun sendDirect(context: Context, config: AppConfig, jobId: String, requestId: String, code: String, simSlot: Int, trackingId: String = "") {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "Direct USSD requires Android 8.0 (API 26) or newer." }
        val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val manager = telephonyManagerForSlot(context, base, simSlot)
        manager.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                val text = response.toString()
                UssdResultStore.update(text)
                LogStore.append(context, "USSD response received for job " + jobId + ".")
                runCatching {
                    GatewayApi.postResult(context, config, jobId, requestId, "ussd", "completed",
                        JSONObject()
                        .put("code", code)
                        .put("response", text)
                        .put("last_response", text)
                        .put("session_ended", true)
                        .put("end_reason", "direct_response")
                        .put("carrier_outcome", UssdResultStore.classifyCarrierOutcome(text))
                        .put("sim_slot", simSlot)
                        .put("tracking_id", trackingId.takeIf { it.isNotBlank() }))
                }.onFailure { LogStore.append(context, "USSD result could not be sent to receiver server.") }
                UssdResultStore.finish(text)
                UssdChainState.finish("completed")
            }

            override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                UssdResultStore.finish("USSD request failed. Android failure code: " + failureCode)
                UssdChainState.finish("failed")
                LogStore.append(context, "USSD request failed (Android code " + failureCode + ").")
                runCatching {
                    GatewayApi.postResult(context, config, jobId, requestId, "ussd", "failed",
                        JSONObject()
                            .put("response", UssdResultStore.latest)
                            .put("last_response", UssdResultStore.latest)
                            .put("session_ended", true)
                            .put("end_reason", "android_request_failed")
                            .put("carrier_outcome", "android_request_failed")
                            .put("failure_code", failureCode)
                            .put("tracking_id", trackingId.takeIf { it.isNotBlank() }))
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun startInteractive(context: Context, code: String, simSlot: Int) {
        val subscriptions = SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty()
        val selected = subscriptions.firstOrNull { it.simSlotIndex == simSlot - 1 }
            ?: throw IllegalStateException(
                "SIM slot " + simSlot + " is not active or cannot be read. Check SIM and Phone permissions."
            )

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

        // Resolve the requested subscription to the system calling account.
        // Android 11+ provides a public API for this mapping. On Android 8-10,
        // Telecom's SIM phone-account ID is the subscription ID.
        val phoneAccount = telecomManager.callCapablePhoneAccounts.firstOrNull { handle ->
            val accountSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { telephonyManager.getSubscriptionId(handle) }
                    .getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            } else {
                handle.id.toIntOrNull() ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }
            accountSubscriptionId == selected.subscriptionId
        } ?: throw IllegalStateException(
            "Android could not find a calling account for SIM slot " + simSlot +
                ". Check that the SIM is enabled for calls and that Phone permissions are granted."
        )

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(code))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccount)
        }
        context.startActivity(intent)
    }

    private fun telephonyManagerForSlot(context: Context, base: TelephonyManager, simSlot: Int): TelephonyManager {
        val subscriptions = SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty()
        val selected = subscriptions.firstOrNull { it.simSlotIndex == simSlot - 1 }
            ?: throw IllegalStateException("SIM slot " + simSlot + " is not active or cannot be read. Check SIM and Phone permissions.")
        return base.createForSubscriptionId(selected.subscriptionId)
    }
}
