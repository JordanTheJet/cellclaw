package com.cellclaw.scheduler

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cellclaw.agent.AgentLoop
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ScheduledTaskWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val agentLoop: AgentLoop,
    private val scheduledTaskDao: ScheduledTaskDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("task_id", -1)
        val prompt = inputData.getString("prompt") ?: return Result.failure()

        Log.d(TAG, "Executing scheduled task $taskId: $prompt")

        return try {
            agentLoop.submitMessage("[Scheduled Task] $prompt")
            if (taskId > 0) {
                scheduledTaskDao.updateLastRun(taskId, System.currentTimeMillis())
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled task $taskId failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskWorker"
    }
}
