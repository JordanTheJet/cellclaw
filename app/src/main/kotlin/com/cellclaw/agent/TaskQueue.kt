package com.cellclaw.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskQueue @Inject constructor() {

    private val _tasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = _tasks.asStateFlow()

    fun enqueue(description: String, priority: TaskPriority = TaskPriority.NORMAL): AgentTask {
        val task = AgentTask(
            id = UUID.randomUUID().toString(),
            description = description,
            priority = priority,
            status = TaskStatus.PENDING
        )
        _tasks.value = _tasks.value + task
        return task
    }

    fun dequeue(): AgentTask? {
        val sorted = _tasks.value
            .filter { it.status == TaskStatus.PENDING }
            .sortedBy { it.priority.ordinal }
        val next = sorted.firstOrNull() ?: return null
        updateStatus(next.id, TaskStatus.IN_PROGRESS)
        return next
    }

    fun updateStatus(taskId: String, status: TaskStatus) {
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) it.copy(status = status) else it
        }
    }

    fun remove(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
    }

    fun clear() {
        _tasks.value = emptyList()
    }
}

data class AgentTask(
    val id: String,
    val description: String,
    val priority: TaskPriority,
    val status: TaskStatus
)

enum class TaskPriority { HIGH, NORMAL, LOW }

enum class TaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }
