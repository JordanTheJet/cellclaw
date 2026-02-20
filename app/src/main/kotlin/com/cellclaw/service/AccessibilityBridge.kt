package com.cellclaw.service

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
}
