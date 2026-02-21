package com.cellclaw.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val selectedProvider by viewModel.selectedProvider.collectAsState()
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
            LinearProgressIndicator(
                progress = { (currentStep + 1) / 3f },
                modifier = Modifier.fillMaxWidth()
            )

            when (currentStep) {
                0 -> {
                    Text(
                        "Choose your AI provider",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "CellClaw supports multiple AI providers. Pick one and enter your API key.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    // Provider selection cards
                    for (provider in viewModel.availableProviders) {
                        val isSelected = selectedProvider == provider.type
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.selectProvider(provider.type)
                                    apiKey = ""
                                }
                                .then(
                                    if (isSelected) Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(12.dp)
                                    ) else Modifier
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        provider.displayName,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Default model: ${provider.defaultModel}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    RadioButton(selected = true, onClick = null)
                                } else {
                                    RadioButton(selected = false, onClick = {
                                        viewModel.selectProvider(provider.type)
                                        apiKey = ""
                                    })
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = {
                            Text(
                                when (selectedProvider) {
                                    "anthropic" -> "Anthropic API Key"
                                    "openai" -> "OpenAI API Key"
                                    "gemini" -> "Google AI API Key"
                                    else -> "API Key"
                                }
                            )
                        },
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

                    val keyHint = when (selectedProvider) {
                        "anthropic" -> "Starts with sk-ant-"
                        "openai" -> "Starts with sk-"
                        "gemini" -> "Starts with AIza"
                        else -> ""
                    }
                    if (keyHint.isNotEmpty()) {
                        Text(
                            keyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            viewModel.saveApiKey(selectedProvider, apiKey)
                            currentStep = 1
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = apiKey.length >= 10
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
