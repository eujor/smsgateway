package com.eujor.smsgateway.telephony

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.data.SecureConfigStore
import com.eujor.smsgateway.gateway.GatewayApi
import org.json.JSONObject

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val recipient = intent.getStringExtra(EXTRA_RECIPIENT).orEmpty()
        val delivery = intent.action == ACTION_DELIVERED
        val status = when {
            resultCode == Activity.RESULT_OK && delivery -> "delivered"
            resultCode == Activity.RESULT_OK -> "sent"
            delivery -> "delivery_report_failed"
            else -> "send_failed"
        }
        if (!delivery) SmsOperationState.finish(jobId, status)
        LogStore.append(context, "SMS " + status + " for " + mask(recipient) + ".")
        val pending = goAsync()
        Thread {
            try {
                GatewayApi.postResultBlocking(
                    context, SecureConfigStore.load(context), jobId, requestId, "sms", status,
                    JSONObject().put("recipient", recipient).put("android_result_code", resultCode)
                )
            } catch (_: Exception) {
                LogStore.append(context, "SMS status report could not reach receiver server.")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun mask(number: String): String =
        if (number.length <= 4) "****" else number.take(3) + "****" + number.takeLast(3)

    companion object {
        const val ACTION_SENT = "com.eujor.smsgateway.action.SMS_SENT"
        const val ACTION_DELIVERED = "com.eujor.smsgateway.action.SMS_DELIVERED"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_RECIPIENT = "recipient"
    }
}
