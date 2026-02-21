package com.cellclaw.tools

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import java.util.Calendar
import javax.inject.Inject

class CalendarQueryTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "calendar.query"
    override val description = "Query calendar events. Can filter by date range."
    override val parameters = ToolParameters(
        properties = mapOf(
            "days_ahead" to ParameterProperty("integer", "Number of days ahead to query (default 7)"),
            "query" to ParameterProperty("string", "Search text in event title/description"),
            "limit" to ParameterProperty("integer", "Max events to return (default 20)")
        )
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val daysAhead = params["days_ahead"]?.jsonPrimitive?.intOrNull ?: 7
        val query = params["query"]?.jsonPrimitive?.contentOrNull
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 20

        return try {
            val now = System.currentTimeMillis()
            val end = now + daysAhead * 24L * 60 * 60 * 1000

            val uri = CalendarContract.Events.CONTENT_URI
            var selection = "(${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?)"
            val selectionArgs = mutableListOf(now.toString(), end.toString())

            if (query != null) {
                selection += " AND (${CalendarContract.Events.TITLE} LIKE ?)"
                selectionArgs.add("%$query%")
            }

            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.ALL_DAY
                ),
                selection,
                selectionArgs.toTypedArray(),
                "${CalendarContract.Events.DTSTART} ASC"
            )

            val events = buildJsonArray {
                cursor?.use {
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        add(buildJsonObject {
                            put("id", it.getLong(0))
                            put("title", it.getString(1) ?: "")
                            put("description", it.getString(2) ?: "")
                            put("start", it.getLong(3))
                            put("end", it.getLong(4))
                            put("location", it.getString(5) ?: "")
                            put("all_day", it.getInt(6) == 1)
                        })
                        count++
                    }
                }
            }

            ToolResult.success(events)
        } catch (e: Exception) {
            ToolResult.error("Failed to query calendar: ${e.message}")
        }
    }
}

class CalendarCreateTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "calendar.create"
    override val description = "Create a new calendar event."
    override val parameters = ToolParameters(
        properties = mapOf(
            "title" to ParameterProperty("string", "Event title"),
            "description" to ParameterProperty("string", "Event description"),
            "start_time" to ParameterProperty("integer", "Start time as Unix timestamp in milliseconds"),
            "end_time" to ParameterProperty("integer", "End time as Unix timestamp in milliseconds"),
            "location" to ParameterProperty("string", "Event location"),
            "all_day" to ParameterProperty("boolean", "Whether this is an all-day event")
        ),
        required = listOf("title", "start_time")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val title = params["title"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'title' parameter")
        val startTime = params["start_time"]?.jsonPrimitive?.longOrNull
            ?: return ToolResult.error("Missing 'start_time' parameter")
        val endTime = params["end_time"]?.jsonPrimitive?.longOrNull
            ?: (startTime + 3600000) // default 1 hour
        val description = params["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val location = params["location"]?.jsonPrimitive?.contentOrNull ?: ""
        val allDay = params["all_day"]?.jsonPrimitive?.booleanOrNull ?: false

        return try {
            // Get default calendar ID
            val calCursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.IS_PRIMARY} = 1",
                null, null
            )
            val calendarId = calCursor?.use {
                if (it.moveToFirst()) it.getLong(0) else 1L
            } ?: 1L

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)

            ToolResult.success(buildJsonObject {
                put("created", true)
                put("title", title)
                put("event_uri", uri.toString())
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to create event: ${e.message}")
        }
    }
}
