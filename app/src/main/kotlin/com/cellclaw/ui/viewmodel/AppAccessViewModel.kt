package com.cellclaw.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import com.cellclaw.agent.AccessMode
import com.cellclaw.agent.AppAccessPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable,
    val isFinancial: Boolean = false
)

@HiltViewModel
class AppAccessViewModel @Inject constructor(
    private val appAccessPolicy: AppAccessPolicy,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val overrides: StateFlow<Set<String>> = appAccessPolicy.overrides
    val mode: StateFlow<AccessMode> = appAccessPolicy.mode

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    init {
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val apps = resolveInfos
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName } // Exclude self
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo),
                    isFinancial = appAccessPolicy.isFinancialApp(appInfo.packageName)
                )
            }
            .sortedBy { it.appName.lowercase() }

        _installedApps.value = apps
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleApp(packageName: String) {
        val isCurrentlyOverridden = appAccessPolicy.isAppOverridden(packageName)
        appAccessPolicy.setAppOverridden(packageName, !isCurrentlyOverridden)
    }

    fun setMode(mode: AccessMode) {
        appAccessPolicy.setMode(mode)
    }

    fun isAppAllowed(packageName: String): Boolean {
        return appAccessPolicy.isAppAllowed(packageName)
    }
}
