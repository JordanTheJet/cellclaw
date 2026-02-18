package com.cellclaw.service.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        Log.d(TAG, "Phone state: $state, number: $number")

        val notifyIntent = Intent("com.cellclaw.PHONE_STATE").apply {
            putExtra("state", state)
            number?.let { putExtra("number", it) }
            setPackage(context.packageName)
        }
        context.sendBroadcast(notifyIntent)
    }

    companion object {
        private const val TAG = "PhoneStateReceiver"
    }
}
