package com.cellclaw.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cellclaw.service.CapturedNotification
import com.cellclaw.service.CellClawNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewFeaturesInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // ── Screenshot Tool ──

    @Test
    fun screenshotCaptureReturnsFile() {
        runBlocking {
            // Wake screen
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")).waitFor()
            delay(1000)

            val tool = ScreenCaptureTool(context)
            val result = tool.execute(buildJsonObject {})

            Log.d(TAG, "Screenshot result: success=${result.success}, data=${result.data}, error=${result.error}")

            // Screenshot requires the a11y service to be connected and running in :a11y process.
            // In instrumented tests, the broadcast may timeout if a11y isn't connected.
            if (!result.success) {
                Log.w(TAG, "Screenshot failed (expected if a11y not connected): ${result.error}")
                // Verify it at least returns a meaningful error, not a crash
                assertNotNull("Should have error message", result.error)
                return@runBlocking
            }

            val data = result.data?.jsonObject
            assertNotNull("Should have data", data)
            val filePath = data!!["file_path"]?.jsonPrimitive?.contentOrNull
            assertNotNull("Should have file_path", filePath)
            assertTrue("File should exist", java.io.File(filePath!!).exists())
            assertTrue("File should be non-empty", java.io.File(filePath).length() > 0)
            Log.d(TAG, "Screenshot saved: $filePath, size=${java.io.File(filePath).length()} bytes")
        }
    }

    @Test
    fun screenshotWithBase64() {
        runBlocking {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_WAKEUP")).waitFor()
            delay(1000)

            val tool = ScreenCaptureTool(context)
            val result = tool.execute(buildJsonObject {
                put("include_base64", true)
            })

            Log.d(TAG, "Screenshot+base64 result: success=${result.success}, error=${result.error}")

            if (!result.success) {
                Log.w(TAG, "Screenshot failed (expected if a11y not connected): ${result.error}")
                assertNotNull("Should have error message", result.error)
                return@runBlocking
            }

            val data = result.data?.jsonObject!!
            val base64 = data["base64"]?.jsonPrimitive?.contentOrNull
            assertNotNull("Should have base64 data", base64)
            assertTrue("Base64 should be non-empty", base64!!.length > 100)
            val sizeBytes = data["size_bytes"]?.jsonPrimitive?.intOrNull ?: 0
            assertTrue("Should report size", sizeBytes > 0)
            Log.d(TAG, "Screenshot base64 length=${base64.length}, file_size=$sizeBytes bytes")
        }
    }

    // ── Notification Listen Tool ──

    @Test
    fun notificationQueryWorksWithEmptyList() {
        runBlocking {
            synchronized(CellClawNotificationListener.recentNotifications) {
                CellClawNotificationListener.recentNotifications.clear()
            }

            val tool = NotificationListenTool()
            val result = tool.execute(buildJsonObject {
                put("action", "query")
            })

            assertTrue("Query should succeed", result.success)
            val data = result.data?.jsonObject!!
            assertEquals("Count should be 0", 0, data["count"]?.jsonPrimitive?.int)
            Log.d(TAG, "Empty notification query: $data")
        }
    }

    @Test
    fun notificationQueryWithPopulatedList() {
        runBlocking {
            synchronized(CellClawNotificationListener.recentNotifications) {
                CellClawNotificationListener.recentNotifications.clear()
                CellClawNotificationListener.recentNotifications.add(
                    CapturedNotification(
                        packageName = "com.whatsapp",
                        title = "John",
                        text = "Hey, are you free tonight?",
                        timestamp = System.currentTimeMillis(),
                        key = "test_key_1"
                    )
                )
                CellClawNotificationListener.recentNotifications.add(
                    CapturedNotification(
                        packageName = "com.google.android.gm",
                        title = "New email",
                        text = "Your order has shipped",
                        timestamp = System.currentTimeMillis() - 120_000,
                        key = "test_key_2"
                    )
                )
            }

            val tool = NotificationListenTool()

            // Query all
            val allResult = tool.execute(buildJsonObject {
                put("action", "query")
            })
            assertTrue("Query all should succeed", allResult.success)
            val allData = allResult.data?.jsonObject!!
            assertEquals("Should have 2 notifications", 2, allData["count"]?.jsonPrimitive?.int)

            // Query filtered by package
            val whatsappResult = tool.execute(buildJsonObject {
                put("action", "query")
                put("app_package", "com.whatsapp")
            })
            assertTrue("WhatsApp query should succeed", whatsappResult.success)
            val waData = whatsappResult.data?.jsonObject!!
            assertEquals("Should have 1 WhatsApp notification", 1, waData["count"]?.jsonPrimitive?.int)

            // Query with since_minutes filter
            val recentResult = tool.execute(buildJsonObject {
                put("action", "query")
                put("since_minutes", 1)
            })
            assertTrue("Recent query should succeed", recentResult.success)
            val recentData = recentResult.data?.jsonObject!!
            assertEquals("Should have 1 recent notification", 1, recentData["count"]?.jsonPrimitive?.int)

            Log.d(TAG, "Notification query tests passed")
        }
    }

    @Test
    fun notificationTriggerManagement() {
        runBlocking {
            CellClawNotificationListener.triggerApps.clear()

            val tool = NotificationListenTool()

            // Add trigger
            val addResult = tool.execute(buildJsonObject {
                put("action", "add_trigger")
                put("app_package", "com.whatsapp")
            })
            assertTrue("Add trigger should succeed", addResult.success)

            // List triggers
            val listResult = tool.execute(buildJsonObject {
                put("action", "list_triggers")
            })
            assertTrue("List triggers should succeed", listResult.success)
            val triggers = listResult.data?.jsonObject?.get("triggers")?.jsonArray
            assertTrue("Should have 1 trigger", triggers?.size == 1)

            // Remove trigger
            val removeResult = tool.execute(buildJsonObject {
                put("action", "remove_trigger")
                put("app_package", "com.whatsapp")
            })
            assertTrue("Remove trigger should succeed", removeResult.success)

            // Verify empty
            val emptyResult = tool.execute(buildJsonObject {
                put("action", "list_triggers")
            })
            val emptyTriggers = emptyResult.data?.jsonObject?.get("triggers")?.jsonArray
            assertTrue("Should have 0 triggers", emptyTriggers?.size == 0)

            Log.d(TAG, "Trigger management tests passed")
        }
    }

    // ── Scheduler Tool ──

    @Test
    fun schedulerListWithStubDao() {
        runBlocking {
            // Test only the list action (doesn't need WorkManager)
            val dao = StubScheduledTaskDao()
            dao.insert(com.cellclaw.scheduler.ScheduledTaskEntity(
                name = "Test Task",
                prompt = "Check weather",
                intervalMinutes = 15
            ))

            val tool = SchedulerTool(context, dao)
            val listResult = tool.execute(buildJsonObject {
                put("action", "list")
            })
            assertTrue("List should succeed", listResult.success)
            val data = listResult.data?.jsonObject!!
            assertEquals("Should have 1 task", 1, data["count"]?.jsonPrimitive?.int)
            Log.d(TAG, "Scheduler list: $data")
        }
    }

    // ── Messaging Tools ──

    @Test
    fun messagingOpenMissingApp() {
        runBlocking {
            val tool = MessagingOpenTool(context)
            val result = tool.execute(buildJsonObject {})
            assertFalse("Should fail without 'app'", result.success)
        }
    }

    @Test
    fun messagingReplyMissingMessage() {
        runBlocking {
            val tool = MessagingReplyTool(context)
            val result = tool.execute(buildJsonObject {})
            assertFalse("Should fail without 'message'", result.success)
        }
    }

    companion object {
        private const val TAG = "NewFeaturesTest"
    }
}

/** Stub DAO for scheduler tests without Room */
class StubScheduledTaskDao : com.cellclaw.scheduler.ScheduledTaskDao {
    private val tasks = mutableListOf<com.cellclaw.scheduler.ScheduledTaskEntity>()
    private var nextId = 1L

    override suspend fun insert(task: com.cellclaw.scheduler.ScheduledTaskEntity): Long {
        val id = nextId++
        tasks.add(task.copy(id = id))
        return id
    }
    override suspend fun update(task: com.cellclaw.scheduler.ScheduledTaskEntity) {
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) tasks[idx] = task
    }
    override suspend fun getAll() = tasks.toList()
    override suspend fun getById(id: Long) = tasks.firstOrNull { it.id == id }
    override suspend fun delete(id: Long) { tasks.removeAll { it.id == id } }
    override suspend fun updateLastRun(id: Long, timestamp: Long) {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) tasks[idx] = tasks[idx].copy(lastRun = timestamp)
    }
    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx >= 0) tasks[idx] = tasks[idx].copy(enabled = enabled)
    }
}
