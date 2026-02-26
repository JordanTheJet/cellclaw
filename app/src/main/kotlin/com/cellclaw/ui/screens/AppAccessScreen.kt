package com.cellclaw.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cellclaw.agent.AccessMode
import com.cellclaw.ui.viewmodel.AppAccessViewModel
import com.cellclaw.ui.viewmodel.AppInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAccessScreen(
    onBack: () -> Unit,
    viewModel: AppAccessViewModel = hiltViewModel()
) {
    val installedApps by viewModel.installedApps.collectAsState()
    val overrides by viewModel.overrides.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    val summaryText = when (mode) {
        AccessMode.ALL_ON -> {
            val blocked = overrides.size
            if (blocked == 0) "All apps allowed"
            else "$blocked app${if (blocked != 1) "s" else ""} blocked"
        }
        AccessMode.SMART -> {
            val financialCount = installedApps.count { it.isFinancial }
            val manualOverrides = overrides.size
            if (manualOverrides == 0) "$financialCount financial app${if (financialCount != 1) "s" else ""} auto-blocked"
            else "$financialCount financial auto-blocked, $manualOverrides manual override${if (manualOverrides != 1) "s" else ""}"
        }
        AccessMode.ALL_OFF -> {
            val allowed = overrides.size
            if (allowed == 0) "All apps blocked"
            else "$allowed app${if (allowed != 1) "s" else ""} allowed"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Access") },
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
        ) {
            // Header
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Control which apps CellClaw can interact with.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                // Mode selector
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Default Mode",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            AccessMode.entries.forEachIndexed { index, accessMode ->
                                SegmentedButton(
                                    selected = mode == accessMode,
                                    onClick = { viewModel.setMode(accessMode) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = AccessMode.entries.size
                                    )
                                ) {
                                    Text(accessMode.displayName, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            mode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Search bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                // Summary
                Text(
                    summaryText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // App list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    AppAccessRow(
                        app = app,
                        isAllowed = viewModel.isAppAllowed(app.packageName),
                        mode = mode,
                        onToggle = { viewModel.toggleApp(app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppAccessRow(
    app: AppInfo,
    isAllowed: Boolean,
    mode: AccessMode,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bitmap = remember(app.icon) { drawableToBitmap(app.icon) }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = app.appName,
            modifier = Modifier.size(40.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    app.appName,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (mode == AccessMode.SMART && app.isFinancial) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            "Financial",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = isAllowed,
            onCheckedChange = { onToggle() }
        )
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 48
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 48
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
