package com.cellclaw.service.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cellclaw.service.CellClawService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "Device booted, checking auto-start preference")

        val prefs = context.getSharedPreferences("cellclaw_config", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_boot", false)

        if (autoStart) {
            Log.d(TAG, "Auto-starting CellClaw service")
            val serviceIntent = Intent(context, CellClawService::class.java).apply {
                action = CellClawService.ACTION_START
            }
            context.startForegroundService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
