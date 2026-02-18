package com.cellclaw.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToApprovals: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val pendingApprovals by viewModel.pendingApprovals.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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

            // Agent state indicator
            if (agentState != "idle") {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = when (agentState) {
                        "thinking" -> "Thinking..."
                        "executing_tools" -> "Executing tools..."
                        "waiting_approval" -> "Waiting for approval..."
                        else -> agentState
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Input bar
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
                    placeholder = { Text("Message CellClaw...") },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 4
                )
                Spacer(modifier = Modifier.width(8.dp))
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
