package com.cellclaw.tools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsToolInstrumentedTest {

    private lateinit var tool: SettingsTool

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        tool = SettingsTool(context)
    }

    @Test
    fun getBatteryInfo() = runTest {
        val params = buildJsonObject {
            put("info", "battery")
        }
        val result = tool.execute(params)
        assertTrue("Should succeed", result.success)
        val data = result.data?.jsonObject!!
        assertTrue(data.containsKey("battery"))
        val battery = data["battery"]?.jsonObject!!
        assertTrue(battery.containsKey("percentage"))
        assertTrue(battery.containsKey("charging"))
        assertTrue(battery.containsKey("status"))
        val pct = battery["percentage"]?.jsonPrimitive?.int ?: -1
        assertTrue("Battery should be 0-100", pct in 0..100)
    }

    @Test
    fun getWifiInfo() = runTest {
        val params = buildJsonObject {
            put("info", "wifi")
        }
        val result = tool.execute(params)
        assertTrue(result.success)
        val data = result.data?.jsonObject!!
        assertTrue(data.containsKey("network"))
        val network = data["network"]?.jsonObject!!
        assertTrue(network.containsKey("connected"))
        assertTrue(network.containsKey("wifi"))
        assertTrue(network.containsKey("cellular"))
    }

    @Test
    fun getBrightnessInfo() = runTest {
        val params = buildJsonObject {
            put("info", "brightness")
        }
        val result = tool.execute(params)
        assertTrue(result.success)
        val data = result.data?.jsonObject!!
        assertTrue(data.containsKey("display"))
    }

    @Test
    fun getAllSettings() = runTest {
        val result = tool.execute(JsonObject(emptyMap()))
        assertTrue(result.success)
        val data = result.data?.jsonObject!!
        assertTrue("Should have battery", data.containsKey("battery"))
        assertTrue("Should have network", data.containsKey("network"))
        assertTrue("Should have display", data.containsKey("display"))
    }

    @Test
    fun getDefaultIsAll() = runTest {
        val explicitAll = tool.execute(buildJsonObject { put("info", "all") })
        val defaultAll = tool.execute(JsonObject(emptyMap()))

        assertTrue(explicitAll.success)
        assertTrue(defaultAll.success)

        // Both should return all three sections
        val keys1 = explicitAll.data?.jsonObject?.keys ?: emptySet()
        val keys2 = defaultAll.data?.jsonObject?.keys ?: emptySet()
        assertEquals(keys1, keys2)
    }
}
