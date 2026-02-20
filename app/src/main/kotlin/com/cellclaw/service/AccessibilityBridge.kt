package com.cellclaw.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton bridge for bidirectional communication between
 * AppAutomateTool (caller) and CellClawAccessibility (executor).
 *
 * The tool sends a request, the accessibility service executes it
 * and posts the result back through this bridge.
 */
object AccessibilityBridge {

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    @Volatile
    var isServiceConnected: Boolean = false
        private set

    /** Called by CellClawAccessibility when the service connects */
    fun onServiceConnected() {
        isServiceConnected = true
    }

    /** Called by CellClawAccessibility when the service disconnects */
    fun onServiceDisconnected() {
        isServiceConnected = false
        // Fail all pending requests
        pendingRequests.forEach { (id, deferred) ->
            deferred.complete(buildJsonObject {
                put("error", "Accessibility service disconnected")
            })
        }
        pendingRequests.clear()
    }

    /**
     * Submit a request from the tool side and wait for the result.
     * Returns the result JSON from the accessibility service.
     */
    suspend fun request(timeoutMs: Long = 10_000): Pair<String, CompletableDeferred<JsonObject>> {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred
        return Pair(id, deferred)
    }

    /**
     * Await result for a specific request ID.
     */
    suspend fun awaitResult(id: String, timeoutMs: Long = 10_000): JsonObject {
        val deferred = pendingRequests[id] ?: return buildJsonObject {
            put("error", "Request not found: $id")
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            buildJsonObject { put("error", "Timeout waiting for accessibility result") }
        } finally {
            pendingRequests.remove(id)
        }
    }

    /**
     * Called by CellClawAccessibility to post results back.
     */
    fun postResult(requestId: String, result: JsonObject) {
        pendingRequests[requestId]?.complete(result)
    }

    /**
     * Create a new pending request and return its ID.
     */
    fun createRequest(): String {
        val id = UUID.randomUUID().toString()
        pendingRequests[id] = CompletableDeferred()
        return id
    }
}
