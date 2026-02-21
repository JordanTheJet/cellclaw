package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cellclaw.service.AccessibilityBridge
import com.cellclaw.service.CellClawAccessibility
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class MessagingOpenTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "messaging.open"
    override val description = """Open a chat in a messaging app (WhatsApp, Telegram, Instagram).
For WhatsApp: provide phone_number to open a direct chat (with country code, e.g. +1234567890).
For other apps or to search by contact name: provide app and contact_name, then use screen.read to navigate."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "app" to ParameterProperty("string", "Messaging app to open",
                enum = listOf("whatsapp", "telegram", "instagram", "messages")),
            "phone_number" to ParameterProperty("string", "Phone number for WhatsApp direct chat (with country code)"),
            "contact_name" to ParameterProperty("string", "Contact name to search for in the app")
        ),
        required = listOf("app")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val app = params["app"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'app' parameter")
        val phoneNumber = params["phone_number"]?.jsonPrimitive?.contentOrNull
        val contactName = params["contact_name"]?.jsonPrimitive?.contentOrNull

        return try {
            when (app) {
                "whatsapp" -> openWhatsApp(phoneNumber, contactName)
                "telegram" -> openApp("org.telegram.messenger", contactName)
                "instagram" -> openApp("com.instagram.android", contactName)
                "messages" -> openApp("com.google.android.apps.messaging", contactName)
                else -> ToolResult.error("Unsupported app: $app")
            }
        } catch (e: Exception) {
            ToolResult.error("Failed to open $app: ${e.message}")
        }
    }

    private fun openWhatsApp(phoneNumber: String?, contactName: String?): ToolResult {
        if (phoneNumber != null) {
            val cleanNumber = phoneNumber.replace(Regex("[^+\\d]"), "")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return ToolResult.success(buildJsonObject {
                put("opened", true)
                put("app", "whatsapp")
                put("phone_number", cleanNumber)
                put("message", "WhatsApp chat opened for $cleanNumber. Use messaging.read to see the chat.")
            })
        }

        return openApp("com.whatsapp", contactName)
    }

    private fun openApp(packageName: String, contactName: String?): ToolResult {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        } else {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "am", "start", "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "--package", packageName
            ))
            if (proc.waitFor() != 0) {
                return ToolResult.error("App not installed: $packageName")
            }
        }

        val instructions = if (contactName != null) {
            "App opened. Use screen.read to see the UI, then use app.automate to search for '$contactName' and open their chat."
        } else {
            "App opened. Use screen.read to see the current screen."
        }

        return ToolResult.success(buildJsonObject {
            put("opened", true)
            put("app", packageName)
            if (contactName != null) put("contact_name", contactName)
            put("message", instructions)
        })
    }
}

class MessagingReadTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "messaging.read"
    override val description = """Read the current chat screen in a messaging app.
Parses visible messages into a structured list with sender, text, and timestamp.
Must have a chat open in the foreground."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "wait_ms" to ParameterProperty("integer", "Optional delay in ms before reading (default 500)")
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val waitMs = params["wait_ms"]?.jsonPrimitive?.longOrNull ?: 500
            val (receiver, deferred) = AccessibilityBridge.createReceiver()

            val action = if (waitMs > 0) "wait_and_read" else "read_screen"
            val intent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("result_receiver", receiver)
                putExtra("action", action)
                if (waitMs > 0) putExtra("delay_ms", waitMs)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            val result = AccessibilityBridge.awaitResult(deferred, waitMs + 5000)

            if (result.containsKey("error") && !result.containsKey("elements")) {
                return ToolResult.error(result["error"]?.jsonPrimitive?.contentOrNull ?: "Read failed")
            }

            // Parse elements into chat messages
            val elements = result["elements"]?.jsonArray ?: JsonArray(emptyList())
            val packageName = result["package"]?.jsonPrimitive?.contentOrNull ?: ""
            val messages = parseChatMessages(elements, packageName)

            ToolResult.success(buildJsonObject {
                put("app_package", packageName)
                put("message_count", messages.size)
                putJsonArray("messages") {
                    messages.forEach { add(it) }
                }
                put("raw_element_count", elements.size)
            })
        } catch (e: Exception) {
            ToolResult.error("Chat read failed: ${e.message}")
        }
    }

    private fun parseChatMessages(elements: JsonArray, packageName: String): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()

        for (element in elements) {
            val obj = element.jsonObject
            val text = obj["text"]?.jsonPrimitive?.contentOrNull ?: continue
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: ""
            val desc = obj["desc"]?.jsonPrimitive?.contentOrNull ?: ""
            val bounds = obj["bounds"]?.jsonObject

            // Skip very short texts, buttons without meaningful text, etc.
            if (text.length < 2 && !text.matches(Regex("[\\p{Emoji}]+"))) continue

            // Heuristic: elements that look like chat messages
            if (type.contains("TextView", ignoreCase = true) ||
                type.contains("Text", ignoreCase = true) ||
                type.contains("View", ignoreCase = true)) {

                val centerX = bounds?.get("centerX")?.jsonPrimitive?.intOrNull ?: 0
                val screenWidth = 1080 // Approximate
                val isMine = centerX > screenWidth / 2

                messages.add(buildJsonObject {
                    put("text", text)
                    put("is_mine", isMine)
                    if (desc.isNotEmpty()) put("description", desc)
                })
            }
        }

        return messages
    }
}

class MessagingReplyTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "messaging.reply"
    override val description = """Type and send a message in the currently open chat.
Types the message into the input field and taps the send button.
Must have a chat open in the foreground with a visible input field."""
    override val parameters = ToolParameters(
        properties = mapOf(
            "message" to ParameterProperty("string", "The message text to send")
        ),
        required = listOf("message")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val message = params["message"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.error("Missing 'message' parameter")

        return try {
            // Step 1: Find and tap the input field
            val (findReceiver, findDeferred) = AccessibilityBridge.createReceiver()
            val findIntent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("result_receiver", findReceiver)
                putExtra("action", "read_screen")
                setPackage(context.packageName)
            }
            context.sendBroadcast(findIntent)
            val screenResult = AccessibilityBridge.awaitResult(findDeferred)

            // Step 2: Type the message
            val (typeReceiver, typeDeferred) = AccessibilityBridge.createReceiver()
            val typeIntent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("result_receiver", typeReceiver)
                putExtra("action", "type")
                putExtra("text", message)
                setPackage(context.packageName)
            }
            context.sendBroadcast(typeIntent)
            val typeResult = AccessibilityBridge.awaitResult(typeDeferred)

            val typeSuccess = typeResult["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!typeSuccess) {
                // Fallback: try tapping on input field first, then type
                val (tapReceiver, tapDeferred) = AccessibilityBridge.createReceiver()
                val tapIntent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                    putExtra("result_receiver", tapReceiver)
                    putExtra("action", "tap")
                    putExtra("text", "Type a message")
                    setPackage(context.packageName)
                }
                context.sendBroadcast(tapIntent)
                AccessibilityBridge.awaitResult(tapDeferred)

                // Retry type
                val (retryReceiver, retryDeferred) = AccessibilityBridge.createReceiver()
                val retryIntent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                    putExtra("result_receiver", retryReceiver)
                    putExtra("action", "type")
                    putExtra("text", message)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(retryIntent)
                val retryResult = AccessibilityBridge.awaitResult(retryDeferred)

                val retrySuccess = retryResult["success"]?.jsonPrimitive?.booleanOrNull ?: false
                if (!retrySuccess) {
                    return ToolResult.error("Could not type message: no focused input field found")
                }
            }

            // Small delay for UI to update
            kotlinx.coroutines.delay(300)

            // Step 3: Tap the send button
            val (sendReceiver, sendDeferred) = AccessibilityBridge.createReceiver()
            val sendIntent = Intent(CellClawAccessibility.ACTION_COMMAND).apply {
                putExtra("result_receiver", sendReceiver)
                putExtra("action", "tap")
                putExtra("text", "Send")
                setPackage(context.packageName)
            }
            context.sendBroadcast(sendIntent)
            val sendResult = AccessibilityBridge.awaitResult(sendDeferred)

            ToolResult.success(buildJsonObject {
                put("sent", true)
                put("message", message)
            })
        } catch (e: Exception) {
            ToolResult.error("Reply failed: ${e.message}")
        }
    }
}
