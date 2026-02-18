package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cellclaw.approval.ApprovalPolicyManager
import com.cellclaw.approval.ApprovalQueue
import com.cellclaw.approval.ApprovalRequest
import com.cellclaw.approval.ApprovalResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ApprovalViewModel @Inject constructor(
    private val approvalQueue: ApprovalQueue,
    private val policyManager: ApprovalPolicyManager
) : ViewModel() {

    val requests: StateFlow<List<ApprovalRequest>> = approvalQueue.requests

    fun respond(requestId: String, result: ApprovalResult) {
        val request = requests.value.find { it.id == requestId } ?: return
        policyManager.handleResult(request.toolName, result)
        approvalQueue.respond(requestId, result)
    }
}
