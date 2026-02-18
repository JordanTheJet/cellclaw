package com.cellclaw.service.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        for (sms in messages) {
            val sender = sms.displayOriginatingAddress
            val body = sms.messageBody

            Log.d(TAG, "SMS received from $sender: ${body.take(50)}")

            // Notify the agent loop about the incoming SMS
            val notifyIntent = Intent("com.cellclaw.SMS_RECEIVED").apply {
                putExtra("sender", sender)
                putExtra("body", body)
                putExtra("timestamp", sms.timestampMillis)
                setPackage(context.packageName)
            }
            context.sendBroadcast(notifyIntent)
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"
    }
}
