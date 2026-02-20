package com.cellclaw.scheduler

import androidx.room.*

@Dao
interface ScheduledTaskDao {
    @Insert
    suspend fun insert(task: ScheduledTaskEntity): Long

    @Update
    suspend fun update(task: ScheduledTaskEntity)

    @Query("SELECT * FROM scheduled_tasks ORDER BY created_at DESC")
    suspend fun getAll(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getById(id: Long): ScheduledTaskEntity?

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE scheduled_tasks SET last_run = :timestamp WHERE id = :id")
    suspend fun updateLastRun(id: Long, timestamp: Long)

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
