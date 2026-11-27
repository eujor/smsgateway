package com.eujor.smsgateway.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.eujor.smsgateway.data.LogStore
import com.eujor.smsgateway.data.SecureConfigStore
import com.eujor.smsgateway.gateway.GatewayApi
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        Thread {
            try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent).forEach { sms ->
                    val id = "incoming-" + UUID.randomUUID()
                    val result = JSONObject()
                        .put("from", sms.originatingAddress.orEmpty())
                        .put("message", sms.messageBody.orEmpty())
                        .put("received_at", Instant.now().toString())
                    GatewayApi.postResultBlocking(
                        SecureConfigStore.load(context), id, id, "sms.incoming", "received", result
                    )
                    LogStore.append(context, "Incoming SMS received from " + mask(sms.originatingAddress.orEmpty()) + ".")
                }
            } catch (_: Exception) {
                LogStore.append(context, "Incoming SMS result could not reach receiver server.")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun mask(number: String): String =
        if (number.length <= 4) "****" else number.take(3) + "****" + number.takeLast(3)
}
