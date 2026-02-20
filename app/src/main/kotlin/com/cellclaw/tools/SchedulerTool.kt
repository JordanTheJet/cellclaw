package com.cellclaw.tools

import android.content.Context
import androidx.work.*
import com.cellclaw.scheduler.ScheduledTaskDao
import com.cellclaw.scheduler.ScheduledTaskEntity
import com.cellclaw.scheduler.ScheduledTaskWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class SchedulerTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduledTaskDao: ScheduledTaskDao
) : Tool {
    override val name = "schedule.manage"
    override val description = """Create and manage scheduled tasks that run agent prompts on a timer.
Actions:
- create: Create a new scheduled task (min interval 15 minutes for recurring)
- list: List all scheduled tasks
- cancel: Cancel/delete a scheduled task by id
- toggle: Enable or disable a task by id"""
    override val parameters = ToolParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Action to perform",
                enum = listOf("create", "list", "cancel", "toggle")),
            "name" to ParameterProperty("string", "Name for the scheduled task"),
            "prompt" to ParameterProperty("string", "The prompt/message to send to the agent when triggered"),
            "interval_minutes" to ParameterProperty("integer", "Interval between runs in minutes (min 15 for recurring, 0 for one-shot)"),
            "delay_minutes" to ParameterProperty("integer", "Initial delay before first run in minutes (default 0)"),
            "task_id" to ParameterProperty("integer", "Task ID for cancel/toggle actions")
        ),
        required = listOf("action")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val action = params["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'action' parameter")

        return when (action) {
            "create" -> createTask(params)
            "list" -> listTasks()
            "cancel" -> cancelTask(params)
            "toggle" -> toggleTask(params)
            else -> ToolResult.error("Unknown action: $action")
        }
    }

    private suspend fun createTask(params: JsonObject): ToolResult {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'name' parameter")
        val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'prompt' parameter")
        val intervalMinutes = params["interval_minutes"]?.jsonPrimitive?.intOrNull ?: 0
        val delayMinutes = params["delay_minutes"]?.jsonPrimitive?.intOrNull ?: 0

        val workManager = WorkManager.getInstance(context)
        val workId = UUID.randomUUID().toString()

        val inputData = workDataOf(
            "prompt" to prompt,
            "task_id" to 0L // Will be updated after insert
        )

        if (intervalMinutes > 0) {
            // Recurring: WorkManager minimum is 15 minutes
            val actualInterval = maxOf(intervalMinutes, 15)
            val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
                actualInterval.toLong(), TimeUnit.MINUTES
            )
                .setInputData(inputData)
                .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
                .addTag(workId)
                .build()

            workManager.enqueueUniquePeriodicWork(
                workId,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        } else {
            // One-shot
            val request = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
                .setInputData(inputData)
                .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
                .addTag(workId)
                .build()

            workManager.enqueue(request)
        }

        val entity = ScheduledTaskEntity(
            name = name,
            prompt = prompt,
            intervalMinutes = intervalMinutes,
            initialDelayMinutes = delayMinutes,
            workId = workId
        )
        val id = scheduledTaskDao.insert(entity)

        // Update inputData with the real task_id
        // (The worker will use it to update lastRun)

        return ToolResult.success(buildJsonObject {
            put("created", true)
            put("task_id", id)
            put("name", name)
            put("interval_minutes", if (intervalMinutes > 0) maxOf(intervalMinutes, 15) else 0)
            put("type", if (intervalMinutes > 0) "recurring" else "one-shot")
        })
    }

    private suspend fun listTasks(): ToolResult {
        val tasks = scheduledTaskDao.getAll()
        return ToolResult.success(buildJsonObject {
            put("count", tasks.size)
            putJsonArray("tasks") {
                for (task in tasks) {
                    add(buildJsonObject {
                        put("id", task.id)
                        put("name", task.name)
                        put("prompt", task.prompt)
                        put("interval_minutes", task.intervalMinutes)
                        put("enabled", task.enabled)
                        put("last_run", task.lastRun)
                        put("created_at", task.createdAt)
                    })
                }
            }
        })
    }

    private suspend fun cancelTask(params: JsonObject): ToolResult {
        val taskId = params["task_id"]?.jsonPrimitive?.longOrNull
            ?: return ToolResult.error("Missing 'task_id' parameter")

        val task = scheduledTaskDao.getById(taskId)
            ?: return ToolResult.error("Task not found: $taskId")

        task.workId?.let {
            WorkManager.getInstance(context).cancelAllWorkByTag(it)
        }
        scheduledTaskDao.delete(taskId)

        return ToolResult.success(buildJsonObject {
            put("cancelled", true)
            put("task_id", taskId)
            put("name", task.name)
        })
    }

    private suspend fun toggleTask(params: JsonObject): ToolResult {
        val taskId = params["task_id"]?.jsonPrimitive?.longOrNull
            ?: return ToolResult.error("Missing 'task_id' parameter")

        val task = scheduledTaskDao.getById(taskId)
            ?: return ToolResult.error("Task not found: $taskId")

        val newEnabled = !task.enabled
        scheduledTaskDao.setEnabled(taskId, newEnabled)

        if (!newEnabled) {
            task.workId?.let {
                WorkManager.getInstance(context).cancelAllWorkByTag(it)
            }
        } else if (task.intervalMinutes > 0) {
            // Re-enqueue
            val inputData = workDataOf(
                "prompt" to task.prompt,
                "task_id" to taskId
            )
            val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(
                maxOf(task.intervalMinutes, 15).toLong(), TimeUnit.MINUTES
            )
                .setInputData(inputData)
                .addTag(task.workId ?: UUID.randomUUID().toString())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                task.workId ?: UUID.randomUUID().toString(),
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
        }

        return ToolResult.success(buildJsonObject {
            put("task_id", taskId)
            put("name", task.name)
            put("enabled", newEnabled)
        })
    }
}
