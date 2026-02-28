package com.cellclaw.ui.screens

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.approval.ApprovalResult
import com.cellclaw.ui.components.isAccessibilityEnabled
import com.cellclaw.ui.components.isNotificationListenerEnabled
import com.cellclaw.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToApprovals: () -> Unit,
    onNavigateToGuide: () -> Unit = {},
    initialMessage: String? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()

    // Auto-send initial message from intent (e.g. adb)
    LaunchedEffect(initialMessage) {
        if (!initialMessage.isNullOrBlank()) {
            viewModel.sendMessage(initialMessage)
        }
    }
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Poll permission status via Settings.Secure (reliable cross-process)
    var accessibilityConnected by remember { mutableStateOf(isAccessibilityEnabled(context)) }
    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifListenerEnabled by remember { mutableStateOf(isNotificationListenerEnabled(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            accessibilityConnected = isAccessibilityEnabled(context)
            overlayGranted = Settings.canDrawOverlays(context)
            notifListenerEnabled = isNotificationListenerEnabled(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CellClaw") },
                actions = {
                    if (pendingApprovals.isNotEmpty()) {
                        BadgedBox(badge = {
                            Badge { Text("${pendingApprovals.size}") }
                        }) {
                            IconButton(onClick = onNavigateToApprovals) {
                                Icon(Icons.Default.Notifications, "Approvals")
                            }
                        }
                    }
                    IconButton(onClick = { viewModel.stopAgent() }) {
                        Icon(Icons.Default.Close, "Stop agent")
                    }
                    IconButton(onClick = onNavigateToGuide) {
                        Icon(Icons.Default.Info, "Guide")
                    }
                    IconButton(onClick = onNavigateToSkills) {
                        Icon(Icons.Default.Star, "Skills")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Notification listener warning
            if (!notifListenerEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Notification listener disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) {
                            Text("Enable")
                        }
                    }
                }
            }

            // Overlay permission warning
            if (!overlayGranted) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Overlay permission required",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }) {
                            Text("Grant")
                        }
                    }
                }
            }

            // Accessibility service warning
            if (!accessibilityConnected) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Accessibility service disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            val cn = ComponentName(context, "com.cellclaw.service.CellClawAccessibility")
                            var opened = false
                            if (android.os.Build.VERSION.SDK_INT >= 36) {
                                try {
                                    context.startActivity(
                                        Intent("android.settings.ACCESSIBILITY_DETAIL_SETTINGS").apply {
                                            putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString())
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                    opened = true
                                } catch (_: Exception) { }
                            }
                            if (!opened) {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) {
                            Text("Enable")
                        }
                    }
                }
            }

            // Messages list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }
            }

            // Auto-scroll on new messages
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }

            // Thinking overlay
            val thinkingText by viewModel.thinkingText.collectAsState()
            val isAgentRunning = agentState != "idle"
            var thinkingExpanded by remember { mutableStateOf(false) }

            AnimatedVisibility(
                visible = isAgentRunning,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (agentState != "waiting_approval") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Shield,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (agentState) {
                                    "thinking" -> "Thinking..."
                                    "executing_tools" -> "Running tools..."
                                    "waiting_approval" -> "Approval needed"
                                    "paused" -> "Paused"
                                    else -> agentState
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (agentState == "waiting_approval")
                                    MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.stopAgent() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Stop",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Inline approval card
                        if (agentState == "waiting_approval" && pendingApprovals.isNotEmpty()) {
                            val request = pendingApprovals.first()
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Allow ${request.toolName}?",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = request.parameters.toString().take(120),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.respondToApproval(request.id, ApprovalResult.DENIED) },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Deny", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Button(
                                            onClick = { viewModel.respondToApproval(request.id, ApprovalResult.APPROVED) },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Approve", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(
                                            onClick = { viewModel.respondToApproval(request.id, ApprovalResult.ALWAYS_ALLOW) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("Always", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        // Thinking text preview
                        if (thinkingText != null && agentState != "waiting_approval") {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = thinkingText ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (thinkingExpanded) Int.MAX_VALUE else 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .clickable { thinkingExpanded = !thinkingExpanded }
                                    .padding(8.dp)
                                    .then(
                                        if (thinkingExpanded) Modifier
                                            .heightIn(max = 200.dp)
                                            .verticalScroll(rememberScrollState())
                                        else Modifier
                                    )
                            )
                        }
                    }
                }
            }

            // Input bar
            val isListening by viewModel.isListening.collectAsState()
            val voiceEnabled = viewModel.voiceEnabled

            val micPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    viewModel.startVoiceInput()
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (isAgentRunning) "Send instructions..." else "Message CellClaw...")
                    },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (voiceEnabled) {
                    IconButton(
                        onClick = {
                            if (isListening) {
                                viewModel.stopVoiceInput()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    ) {
                        Icon(
                            if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isListening) "Stop listening" else "Start voice input",
                            tint = if (isListening) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                            scope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.size - 1)
                                }
                            }
                        }
                    },
                    enabled = inputText.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val bgColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bgColor)
                .padding(12.dp)
        ) {
            if (message.toolName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = textColor.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = message.toolName,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

data class ChatMessage(
    val id: Long,
    val role: String,
    val content: String,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
