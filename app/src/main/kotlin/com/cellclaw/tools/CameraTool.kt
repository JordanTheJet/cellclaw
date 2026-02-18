package com.cellclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class CameraSnapTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "camera.snap"
    override val description = "Take a photo using the device camera."
    override val parameters = ToolParameters(
        properties = mapOf(
            "facing" to ParameterProperty("string", "Camera facing: front or back",
                enum = listOf("front", "back"))
        )
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val imageFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "CELLCLAW_$timestamp.jpg"
            )

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                val photoUri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", imageFile
                )
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            ToolResult.success(buildJsonObject {
                put("photo_path", imageFile.absolutePath)
                put("status", "camera_opened")
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to open camera: ${e.message}")
        }
    }
}

class CameraRecordTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "camera.record"
    override val description = "Start video recording using the device camera."
    override val parameters = ToolParameters(
        properties = mapOf(
            "duration_seconds" to ParameterProperty("integer", "Maximum recording duration in seconds"),
            "facing" to ParameterProperty("string", "Camera facing: front or back",
                enum = listOf("front", "back"))
        )
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                val duration = params["duration_seconds"]?.jsonPrimitive?.intOrNull
                if (duration != null) {
                    putExtra(MediaStore.EXTRA_DURATION_LIMIT, duration)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)

            ToolResult.success(buildJsonObject {
                put("status", "recording_started")
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to start recording: ${e.message}")
        }
    }
}
