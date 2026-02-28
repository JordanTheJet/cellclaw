package com.cellclaw.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CellClaw Guide") },
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
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            GuideSection(
                title = "\u26A1 Shake Shortcut",
                body = "Shake your phone 3 times quickly (within 1.5s) to activate voice commands. " +
                    "A 3-second cooldown prevents accidental re-triggers."
            )

            GuideSection(
                title = "\uD83C\uDFA8 Overlay Bubble Colors",
                body = null,
                items = listOf(
                    "\uD83D\uDFE2 Green" to "Ready & idle",
                    "\uD83D\uDFE0 Orange" to "Thinking or working",
                    "\uD83D\uDFE1 Yellow" to "Needs your approval",
                    "\u26AA Grey" to "Paused",
                    "\uD83D\uDFE5 Dark Red" to "Error occurred"
                )
            )

            GuideSection(
                title = "\uD83D\uDD35 Logo",
                body = "The floating bubble uses the CellClaw target icon. " +
                    "Its background color changes in real-time to reflect the current agent state."
            )

            GuideSection(
                title = "\uD83D\uDC46 Overlay Actions",
                body = null,
                items = listOf(
                    "Single Tap" to "Open quick-reply text panel",
                    "Double Tap" to "Open the full CellClaw app",
                    "Long Press" to "Show stop button to exit overlay",
                    "Drag" to "Move the bubble anywhere on screen"
                )
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GuideSection(
    title: String,
    body: String?,
    items: List<Pair<String, String>> = emptyList()
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items.forEach { (label, desc) ->
                Spacer(Modifier.height(4.dp))
                Text("$label — $desc", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
