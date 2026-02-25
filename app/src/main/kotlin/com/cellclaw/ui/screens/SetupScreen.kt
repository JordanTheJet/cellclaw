package com.cellclaw.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cellclaw.agent.PermissionProfile
import com.cellclaw.ui.viewmodel.SetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val selectedProfile by viewModel.selectedProfile.collectAsState()
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
                progress = { (currentStep + 1) / 4f },
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
                        "Choose autonomy level",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "How much freedom should CellClaw have? You can change this anytime in Settings.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    for (profile in PermissionProfile.entries) {
                        val isSelected = selectedProfile == profile
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectProfile(profile) }
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        profile.displayName,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        profile.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    RadioButton(selected = true, onClick = null)
                                } else {
                                    RadioButton(
                                        selected = false,
                                        onClick = { viewModel.selectProfile(profile) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { currentStep = 3 },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Continue")
                    }
                }

                3 -> {
                    val context = LocalContext.current
                    val lifecycleOwner = LocalLifecycleOwner.current

                    // Re-check permissions when user returns from settings
                    var overlayGranted by remember {
                        mutableStateOf(Settings.canDrawOverlays(context))
                    }
                    var accessibilityGranted by remember {
                        mutableStateOf(isAccessibilityEnabled(context))
                    }
                    var notifListenerGranted by remember {
                        mutableStateOf(isNotificationListenerEnabled(context))
                    }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                overlayGranted = Settings.canDrawOverlays(context)
                                accessibilityGranted = isAccessibilityEnabled(context)
                                notifListenerGranted = isNotificationListenerEnabled(context)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    Text(
                        "Permissions",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "CellClaw needs these permissions to work. Tap each one to open settings.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Overlay permission
                    PermissionRow(
                        title = "Display over other apps",
                        description = "Required for the floating bubble overlay",
                        granted = overlayGranted,
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Accessibility permission
                    PermissionRow(
                        title = "Accessibility service",
                        description = "Required for screen reading and app automation",
                        granted = accessibilityGranted,
                        onClick = { openAccessibilitySettings(context) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Notification listener
                    PermissionRow(
                        title = "Notification listener",
                        description = "Required to read and act on notifications",
                        granted = notifListenerGranted,
                        onClick = {
                            context.startActivity(
                                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val allGranted = overlayGranted && accessibilityGranted && notifListenerGranted

                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = allGranted
                    ) {
                        Text("Start CellClaw")
                    }

                    if (!allGranted) {
                        Text(
                            "Grant all permissions above to continue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (granted)
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
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (granted) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                TextButton(onClick = onClick) { Text("Grant") }
            }
        }
    }
}

private fun openAccessibilitySettings(context: android.content.Context) {
    val componentName = ComponentName(context, "com.cellclaw.service.CellClawAccessibility")

    // Try direct detail page first (AOSP API 36+), catch if OEM doesn't support it
    if (Build.VERSION.SDK_INT >= 36) {
        try {
            context.startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAIL_SETTINGS").apply {
                    putExtra(Intent.EXTRA_COMPONENT_NAME, componentName.flattenToString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        } catch (_: Exception) { /* OEM doesn't support, fall through */ }
    }

    // Fallback: generic accessibility settings
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val expectedComponent = ComponentName(context, "com.cellclaw.service.CellClawNotificationListener")
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return flat.split(':').any {
        ComponentName.unflattenFromString(it) == expectedComponent
    }
}

private fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val expectedComponent = ComponentName(context, "com.cellclaw.service.CellClawAccessibility")
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any {
        ComponentName.unflattenFromString(it) == expectedComponent
    }
}

