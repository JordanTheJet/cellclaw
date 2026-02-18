package com.cellclaw.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.approval.ApprovalRequest
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.ui.viewmodel.ApprovalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalScreen(
    onBack: () -> Unit,
    viewModel: ApprovalViewModel = hiltViewModel()
) {
    val requests by viewModel.requests.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pending Approvals") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (requests.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    "No pending approvals",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(requests, key = { it.id }) { request ->
                    ApprovalCard(
                        request = request,
                        onApprove = { viewModel.respond(request.id, ApprovalResult.APPROVED) },
                        onDeny = { viewModel.respond(request.id, ApprovalResult.DENIED) },
                        onAlwaysAllow = { viewModel.respond(request.id, ApprovalResult.ALWAYS_ALLOW) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onAlwaysAllow: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = request.toolName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = request.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = request.parameters.toString().take(200),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) {
                    Text("Deny")
                }
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                    Text("Approve")
                }
                TextButton(onClick = onAlwaysAllow) {
                    Text("Always")
                }
            }
        }
    }
}
