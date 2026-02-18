package com.cellclaw.approval

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApprovalQueue @Inject constructor() {

    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<ApprovalResult>>()

    private val _requests = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val requests: StateFlow<List<ApprovalRequest>> = _requests.asStateFlow()

    /**
     * Submit an approval request and suspend until the user responds.
     */
    suspend fun request(request: ApprovalRequest): ApprovalResult {
        val id = request.id
        val deferred = CompletableDeferred<ApprovalResult>()
        pendingApprovals[id] = deferred
        _requests.value = _requests.value + request

        return try {
            deferred.await()
        } finally {
            pendingApprovals.remove(id)
            _requests.value = _requests.value.filter { it.id != id }
        }
    }

    /**
     * Respond to an approval request (called from UI or notification action).
     */
    fun respond(requestId: String, result: ApprovalResult) {
        pendingApprovals[requestId]?.complete(result)
    }

    fun respondAll(result: ApprovalResult) {
        pendingApprovals.forEach { (_, deferred) -> deferred.complete(result) }
    }
}

data class ApprovalRequest(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val parameters: JsonObject,
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ApprovalResult {
    APPROVED, DENIED, ALWAYS_ALLOW
}
