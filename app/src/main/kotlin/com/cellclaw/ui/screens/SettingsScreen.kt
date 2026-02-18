package com.cellclaw.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.agent.ToolApprovalPolicy
import com.cellclaw.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val model by viewModel.model.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val autoStartOnBoot by viewModel.autoStartOnBoot.collectAsState()
    val policies by viewModel.policies.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Provider section
            Text("AI Provider", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("API Key")
                        Text(
                            if (hasApiKey) "Configured" else "Not set",
                            color = if (hasApiKey) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    var modelInput by remember { mutableStateOf(model) }
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        label = { Text("Model") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (modelInput != model) {
                        TextButton(onClick = { viewModel.setModel(modelInput) }) {
                            Text("Save")
                        }
                    }
                }
            }

            // User section
            Text("User", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var nameInput by remember { mutableStateOf(userName) }
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Your Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (nameInput != userName) {
                        TextButton(onClick = { viewModel.setUserName(nameInput) }) {
                            Text("Save")
                        }
                    }
                }
            }

            // Service section
            Text("Service", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Start on boot")
                        Switch(
                            checked = autoStartOnBoot,
                            onCheckedChange = { viewModel.setAutoStartOnBoot(it) }
                        )
                    }
                }
            }

            // Approval policies
            Text("Tool Approval Policies", style = MaterialTheme.typography.titleMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    for ((tool, policy) in policies.entries.sortedBy { it.key }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                tool,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            PolicyChip(
                                policy = policy,
                                onToggle = { viewModel.togglePolicy(tool) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyChip(policy: ToolApprovalPolicy, onToggle: () -> Unit) {
    FilterChip(
        selected = policy == ToolApprovalPolicy.AUTO,
        onClick = onToggle,
        label = {
            Text(
                when (policy) {
                    ToolApprovalPolicy.AUTO -> "Auto"
                    ToolApprovalPolicy.ASK -> "Ask"
                    ToolApprovalPolicy.DENY -> "Deny"
                }
            )
        }
    )
}
