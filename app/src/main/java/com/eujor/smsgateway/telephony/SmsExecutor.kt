package com.eujor.smsgateway.telephony

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.eujor.smsgateway.data.AppConfig
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.gateway.GatewayApi
import org.json.JSONObject

object SmsExecutor {
    fun send(context: Context, config: AppConfig, jobId: String, requestId: String, recipient: String, message: String, simSlot: Int) {
        val manager = smsManager(context, simSlot)
        val sentIntent = PendingIntent.getBroadcast(
            context, requestId.hashCode(),
            Intent(context, SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_SENT)
                .putExtra(SmsStatusReceiver.EXTRA_JOB_ID, jobId)
                .putExtra(SmsStatusReceiver.EXTRA_REQUEST_ID, requestId)
                .putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, recipient),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deliveryIntent = PendingIntent.getBroadcast(
            context, requestId.hashCode() xor 0x51A7,
            Intent(context, SmsStatusReceiver::class.java).setAction(SmsStatusReceiver.ACTION_DELIVERED)
                .putExtra(SmsStatusReceiver.EXTRA_JOB_ID, jobId)
                .putExtra(SmsStatusReceiver.EXTRA_REQUEST_ID, requestId)
                .putExtra(SmsStatusReceiver.EXTRA_RECIPIENT, recipient),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.sendTextMessage(recipient, null, message, sentIntent, deliveryIntent)
        LogStore.append(context, "SMS submitted to Android for " + mask(recipient) + " (job " + jobId + ").")
        runCatching {
            GatewayApi.postResult(config, jobId, requestId, "sms", "accepted",
                JSONObject().put("recipient", recipient).put("sim_slot", simSlot))
        }.onFailure { LogStore.append(context, "SMS acceptance report could not be queued.") }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context, simSlot: Int): SmsManager {
        require(simSlot in 1..4) { "SIM slot must be between 1 and 4." }
        val subscriptions = SubscriptionManager.from(context).activeSubscriptionInfoList.orEmpty()
        val selected = subscriptions.firstOrNull { it.simSlotIndex == simSlot - 1 }
            ?: throw IllegalStateException("SIM slot " + simSlot + " is not active or cannot be read. Check SIM and Phone permissions.")
        return SmsManager.getSmsManagerForSubscriptionId(selected.subscriptionId)
    }

    private fun mask(number: String): String =
        if (number.length <= 4) "****" else number.take(3) + "****" + number.takeLast(3)
}
