package com.eujor.smsgateway.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Ordinary incoming SMS is intentionally never forwarded to the receiver. */
class IncomingSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
