package com.cellclaw.approval

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ApprovalQueueTest {

    private lateinit var queue: ApprovalQueue

    @Before
    fun setup() {
        queue = ApprovalQueue()
    }

    @Test
    fun `request adds to pending list`() = runTest {
        val request = ApprovalRequest(
            toolName = "sms.send",
            parameters = JsonObject(emptyMap()),
            description = "Send SMS?"
        )

        val job = launch {
            queue.request(request)
        }

        // Give the coroutine time to suspend
        delay(50)
        assertEquals(1, queue.requests.value.size)
        assertEquals("sms.send", queue.requests.value[0].toolName)

        // Respond to unblock
        queue.respond(request.id, ApprovalResult.APPROVED)
        job.join()
    }

    @Test
    fun `respond unblocks waiting request`() = runTest {
        val request = ApprovalRequest(
            toolName = "script.exec",
            parameters = JsonObject(emptyMap()),
            description = "Run script?"
        )

        val result = async {
            queue.request(request)
        }

        delay(50)
        queue.respond(request.id, ApprovalResult.DENIED)

        assertEquals(ApprovalResult.DENIED, result.await())
    }

    @Test
    fun `request is removed after response`() = runTest {
        val request = ApprovalRequest(
            toolName = "test",
            parameters = JsonObject(emptyMap()),
            description = "Test?"
        )

        val job = launch {
            queue.request(request)
        }

        delay(50)
        assertEquals(1, queue.requests.value.size)

        queue.respond(request.id, ApprovalResult.APPROVED)
        job.join()

        assertEquals(0, queue.requests.value.size)
    }

    @Test
    fun `respondAll approves everything`() = runTest {
        val r1 = ApprovalRequest(id = "1", toolName = "a", parameters = JsonObject(emptyMap()), description = "A?")
        val r2 = ApprovalRequest(id = "2", toolName = "b", parameters = JsonObject(emptyMap()), description = "B?")

        val result1 = async { queue.request(r1) }
        val result2 = async { queue.request(r2) }

        delay(50)
        queue.respondAll(ApprovalResult.APPROVED)

        assertEquals(ApprovalResult.APPROVED, result1.await())
        assertEquals(ApprovalResult.APPROVED, result2.await())
    }
}
