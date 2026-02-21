package com.cellclaw.service

import android.app.NotificationManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cellclaw.CellClawApp
import com.cellclaw.R
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import com.cellclaw.tools.ScreenCaptureTool
import com.cellclaw.tools.VisionAnalyzeTool
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CellClawTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun agentLoop(): AgentLoop
        fun screenCaptureTool(): ScreenCaptureTool
        fun visionAnalyzeTool(): VisionAnalyzeTool
    }

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getEntryPoint(): TileEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()

        val tile = qsTile ?: return
        val entryPoint = getEntryPoint()
        val agentLoop = entryPoint.agentLoop()

        // Don't capture if agent isn't running
        if (agentLoop.state.value == AgentState.ERROR) {
            return
        }

        // Set tile to capturing state
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = "Capturing..."
        tile.updateTile()

        tileScope.launch {
            try {
                val screenCapture = entryPoint.screenCaptureTool()
                val visionAnalyze = entryPoint.visionAnalyzeTool()

                val captureResult = screenCapture.execute(
                    buildJsonObject { put("include_base64", false) }
                )
                if (!captureResult.success) {
                    postResultNotification("Screenshot failed: ${captureResult.error}")
                    restoreTile()
                    return@launch
                }

                val filePath = captureResult.data?.let { data ->
                    (data as? JsonObject)?.get("file_path")
                        ?.let { (it as? JsonPrimitive)?.content }
                }
                if (filePath == null) {
                    postResultNotification("Screenshot failed: no file path")
                    restoreTile()
                    return@launch
                }

                val analyzeResult = visionAnalyze.execute(buildJsonObject {
                    put("file_path", filePath)
                    put("question", "Describe what you see on the screen. What app is open? What is the user looking at?")
                })

                if (analyzeResult.success) {
                    val analysis = analyzeResult.data?.let { data ->
                        (data as? JsonObject)?.get("analysis")
                            ?.let { (it as? JsonPrimitive)?.content }
                    } ?: "Analysis complete"
                    postResultNotification(analysis)
                } else {
                    postResultNotification("Analysis failed: ${analyzeResult.error}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tile screenshot+explain failed: ${e.message}")
                postResultNotification("Error: ${e.message}")
            } finally {
                restoreTile()
            }
        }
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        try {
            val agentLoop = getEntryPoint().agentLoop()
            when (agentLoop.state.value) {
                AgentState.IDLE -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "CellClaw"
                }
                AgentState.THINKING -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Thinking..."
                }
                AgentState.EXECUTING_TOOLS -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Working..."
                }
                AgentState.WAITING_APPROVAL -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Approval"
                }
                AgentState.PAUSED -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Paused"
                }
                AgentState.ERROR -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Error"
                }
            }
        } catch (e: Exception) {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "CellClaw"
        }
        tile.updateTile()
    }

    private fun restoreTile() {
        try {
            updateTileState()
        } catch (_: Exception) {}
    }

    private fun postResultNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CellClawApp.CHANNEL_ALERTS)
            .setContentTitle("Screen Analysis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(TILE_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "CellClawTile"
        private const val TILE_NOTIFICATION_ID = 200
    }
}
