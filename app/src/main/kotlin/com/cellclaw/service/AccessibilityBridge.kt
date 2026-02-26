package com.cellclaw.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Bridge for communication between tools and the CellClawAccessibility service.
 *
 * Uses Android's ResultReceiver for cross-process communication so it works
 * both when tools run in the app process (normal usage) and when they run
 * in the test instrumentation process (androidTest).
 */
object AccessibilityBridge {

    @Volatile
    var isServiceConnected: Boolean = false
        private set

    fun onServiceConnected() {
        isServiceConnected = true
    }

    fun onServiceDisconnected() {
        isServiceConnected = false
    }

    /**
     * Create a ResultReceiver + CompletableDeferred pair for a request.
     * The ResultReceiver is passed in the broadcast intent; the accessibility
     * service sends results back through it, which completes the deferred.
     */
    fun createReceiver(): Pair<ResultReceiver, CompletableDeferred<JsonObject>> {
        val deferred = CompletableDeferred<JsonObject>()
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val jsonStr = resultData?.getString("result")
                if (jsonStr != null) {
                    try {
                        deferred.complete(Json.decodeFromString(jsonStr))
                    } catch (e: Exception) {
                        deferred.complete(buildJsonObject {
                            put("error", "Failed to parse result: ${e.message}")
                        })
                    }
                } else {
                    deferred.complete(buildJsonObject {
                        put("error", "Empty result from accessibility service")
                    })
                }
            }
        }
        return Pair(receiver, deferred)
    }

    /**
     * Await result with timeout.
     */
    suspend fun awaitResult(deferred: CompletableDeferred<JsonObject>, timeoutMs: Long = 10_000): JsonObject {
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Timeout waiting for accessibility result") }
        }
    }

    /**
     * Query the accessibility service for the foreground app's package name.
     * Returns null if the service is not connected or the query fails.
     */
    suspend fun getForegroundPackage(context: Context): String? {
        if (!isServiceConnected) return null
        val (receiver, deferred) = createReceiver()
        val intent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
            putExtra("result_receiver", receiver)
            putExtra("action", "get_foreground_package")
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        val result = awaitResult(deferred, 3_000)
        val pkg = result["package"]?.jsonPrimitive?.contentOrNull
        return if (pkg != null && pkg != "unknown") pkg else null
    }
}
