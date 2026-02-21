package com.cellclaw.scheduler

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_tasks")
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "prompt") val prompt: String,
    @ColumnInfo(name = "interval_minutes") val intervalMinutes: Int,
    @ColumnInfo(name = "initial_delay_minutes") val initialDelayMinutes: Int = 0,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "last_run") val lastRun: Long? = null,
    @ColumnInfo(name = "work_id") val workId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
