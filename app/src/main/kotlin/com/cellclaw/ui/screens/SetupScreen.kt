package com.cellclaw.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.ui.viewmodel.SetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    var apiKey by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var currentStep by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Welcome to CellClaw") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Step indicator
            LinearProgressIndicator(
                progress = { (currentStep + 1) / 3f },
                modifier = Modifier.fillMaxWidth()
            )

            when (currentStep) {
                0 -> {
                    Text(
                        "Set up your AI provider",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "CellClaw uses cloud AI to process your requests. Enter your Anthropic API key to get started.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("Anthropic API Key") },
                        leadingIcon = { Icon(Icons.Default.Key, null) },
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    if (showApiKey) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    "Toggle visibility"
                                )
                            }
                        },
                        visualTransformation = if (showApiKey) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            viewModel.saveApiKey(apiKey)
                            currentStep = 1
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = apiKey.startsWith("sk-")
                    ) {
                        Text("Continue")
                    }
                }

                1 -> {
                    Text(
                        "Tell CellClaw about you",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "This helps CellClaw personalize its responses.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("Your name") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            viewModel.saveUserName(userName)
                            currentStep = 2
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }

                    TextButton(
                        onClick = { currentStep = 2 },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip")
                    }
                }

                2 -> {
                    Text(
                        "Permissions",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "CellClaw needs permissions to access phone features. You'll be prompted when each feature is first used. Sensitive actions (sending SMS, making calls, running scripts) always ask for your approval by default.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Default approval policies:", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            PolicyRow("Read SMS/Contacts/Calendar", "Auto-approve")
                            PolicyRow("Send SMS", "Ask first")
                            PolicyRow("Phone calls", "Ask first")
                            PolicyRow("Run scripts", "Ask first")
                            PolicyRow("App automation", "Ask first")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        "You can change these anytime in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Start CellClaw")
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyRow(feature: String, policy: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(feature, style = MaterialTheme.typography.bodyMedium)
        Text(
            policy,
            style = MaterialTheme.typography.bodyMedium,
            color = if (policy == "Auto-approve") MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.tertiary
        )
    }
}
