package com.cellclaw.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.cellclaw.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class AppInstallTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appConfig: AppConfig
) : Tool {
    override val name = "app.install"
    override val description = """Open the Google Play Store to install an app.
After calling this, use screen.read to see the Play Store page, then app.automate to tap the Install button.
Monitor installation progress with screen.read until the button changes to "Open".
Provide either a package_name (e.g. "com.ubercab") or an app_name to search for."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "package_name" to ParameterProperty("string", "Package name (e.g. com.ubercab, com.spotify.music)"),
            "app_name" to ParameterProperty("string", "App name to search for in Play Store (e.g. Uber, Spotify)")
        )
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val packageName = params["package_name"]?.jsonPrimitive?.contentOrNull
        val appName = params["app_name"]?.jsonPrimitive?.contentOrNull

        if (packageName == null && appName == null) {
            return ToolResult.error("Provide either 'package_name' or 'app_name'")
        }

        // Check if auto-install is disabled
        if (!appConfig.autoInstallApps) {
            val name = appName ?: packageName ?: "the app"
            return ToolResult.error(
                "App installation is disabled in settings. " +
                "Please ask the user to install $name manually from the Play Store."
            )
        }

        // Check if already installed
        if (packageName != null && isInstalled(packageName)) {
            return ToolResult.success(buildJsonObject {
                put("already_installed", true)
                put("package_name", packageName)
                put("message", "App is already installed. Use app.launch to open it.")
            })
        }

        return try {
            if (packageName != null) {
                openPlayStorePage(packageName)
                ToolResult.success(buildJsonObject {
                    put("opened_play_store", true)
                    put("package_name", packageName)
                    put("message", "Play Store opened to $packageName. Use screen.read to see the page, then app.automate to tap Install.")
                })
            } else {
                openPlayStoreSearch(appName!!)
                ToolResult.success(buildJsonObject {
                    put("opened_play_store", true)
                    put("search_query", appName)
                    put("message", "Play Store search opened for '$appName'. Use screen.read to find the app, then app.automate to tap on it and install.")
                })
            }
        } catch (e: Exception) {
            ToolResult.error("Failed to open Play Store: ${e.message}")
        }
    }

    private fun isInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openPlayStorePage(packageName: String) {
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(marketIntent)
        } catch (e: ActivityNotFoundException) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }

    private fun openPlayStoreSearch(query: String) {
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(marketIntent)
        } catch (e: ActivityNotFoundException) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$query")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }
}
