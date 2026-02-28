package com.cellclaw.ui.components

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PermissionRow(
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

internal fun openAccessibilitySettings(context: android.content.Context) {
    val componentName = ComponentName(context, "com.cellclaw.service.CellClawAccessibility")

    if (Build.VERSION.SDK_INT >= 36) {
        try {
            context.startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAIL_SETTINGS").apply {
                    putExtra(Intent.EXTRA_COMPONENT_NAME, componentName.flattenToString())
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            return
        } catch (_: Exception) { }
    }

    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

internal fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val expectedComponent = ComponentName(context, "com.cellclaw.service.CellClawNotificationListener")
    val flat = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: return false
    return flat.split(':').any {
        ComponentName.unflattenFromString(it) == expectedComponent
    }
}

internal fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val expectedComponent = ComponentName(context, "com.cellclaw.service.CellClawAccessibility")
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any {
        ComponentName.unflattenFromString(it) == expectedComponent
    }
}
