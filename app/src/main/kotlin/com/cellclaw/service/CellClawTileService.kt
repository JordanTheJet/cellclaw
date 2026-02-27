package com.cellclaw.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cellclaw.agent.AgentLoop
import com.cellclaw.agent.AgentState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class CellClawTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun agentLoop(): AgentLoop
    }

    private fun getEntryPoint(): TileEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        updateTileState()
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

    companion object {
        private const val TAG = "CellClawTile"
    }
}
