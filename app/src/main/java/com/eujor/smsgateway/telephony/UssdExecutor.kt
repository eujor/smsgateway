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
    fun sendDirect(context: Context, config: AppConfig, jobId: String, requestId: String, code: String, simSlot: Int) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { "Direct USSD requires Android 8.0 (API 26) or newer." }
        val base = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val manager = telephonyManagerForSlot(context, base, simSlot)
        manager.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
            override fun onReceiveUssdResponse(telephonyManager: TelephonyManager, request: String, response: CharSequence) {
                val text = response.toString()
                UssdResultStore.update(text)
                LogStore.append(context, "USSD response received for job " + jobId + ".")
                runCatching {
                    GatewayApi.postResult(config, jobId, requestId, "ussd", "completed",
                        JSONObject().put("code", code).put("response", text).put("sim_slot", simSlot))
                }.onFailure { LogStore.append(context, "USSD result could not be sent to receiver server.") }
                UssdResultStore.finish(text)
            }

            override fun onReceiveUssdResponseFailed(telephonyManager: TelephonyManager, request: String, failureCode: Int) {
                UssdResultStore.finish("USSD request failed. Android failure code: " + failureCode)
                LogStore.append(context, "USSD request failed (Android code " + failureCode + ").")
                runCatching {
                    GatewayApi.postResult(config, jobId, requestId, "ussd", "failed",
                        JSONObject().put("failure_code", failureCode))
                }
            }
        }, Handler(Looper.getMainLooper()))
    }

    fun startInteractive(context: Context, code: String, simSlot: Int) {
        val subscriptions = SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty()
        val selected = subscriptions.firstOrNull { it.simSlotIndex == simSlot - 1 }
            ?: throw IllegalStateException("SIM slot " + simSlot + " is not active or cannot be read. Check SIM and Phone permissions.")
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(code))).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, selected.phoneAccountHandle)
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
