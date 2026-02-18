package com.cellclaw.service.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BatteryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BATTERY_LOW -> {
                Log.d(TAG, "Battery low")
                val notifyIntent = Intent("com.cellclaw.BATTERY_LOW").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(notifyIntent)
            }
        }
    }

    companion object {
        private const val TAG = "BatteryReceiver"
    }
}
