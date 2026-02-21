package com.cellclaw.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TaskQueueTest {

    private lateinit var queue: TaskQueue

    @Before
    fun setup() {
        queue = TaskQueue()
    }

    @Test
    fun `enqueue adds task`() {
        queue.enqueue("Test task")
        assertEquals(1, queue.tasks.value.size)
        assertEquals("Test task", queue.tasks.value[0].description)
        assertEquals(TaskStatus.PENDING, queue.tasks.value[0].status)
    }

    @Test
    fun `dequeue returns highest priority pending task`() {
        queue.enqueue("Low priority", TaskPriority.LOW)
        queue.enqueue("High priority", TaskPriority.HIGH)
        queue.enqueue("Normal priority", TaskPriority.NORMAL)

        val next = queue.dequeue()
        assertNotNull(next)
        assertEquals("High priority", next!!.description)
        assertEquals(TaskStatus.IN_PROGRESS, queue.tasks.value.find { it.id == next.id }?.status)
    }

    @Test
    fun `dequeue returns null when empty`() {
        assertNull(queue.dequeue())
    }

    @Test
    fun `dequeue skips non-pending tasks`() {
        val task = queue.enqueue("Task")
        queue.updateStatus(task.id, TaskStatus.COMPLETED)

        assertNull(queue.dequeue())
    }

    @Test
    fun `remove deletes task`() {
        val task = queue.enqueue("To remove")
        assertEquals(1, queue.tasks.value.size)

        queue.remove(task.id)
        assertEquals(0, queue.tasks.value.size)
    }

    @Test
    fun `clear removes all tasks`() {
        queue.enqueue("One")
        queue.enqueue("Two")
        queue.enqueue("Three")

        queue.clear()
        assertEquals(0, queue.tasks.value.size)
    }

    @Test
    fun `updateStatus changes task status`() {
        val task = queue.enqueue("Task")
        queue.updateStatus(task.id, TaskStatus.FAILED)

        assertEquals(TaskStatus.FAILED, queue.tasks.value[0].status)
    }
}
