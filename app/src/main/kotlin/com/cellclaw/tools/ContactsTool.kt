package com.cellclaw.tools

import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject

class ContactsSearchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "contacts.search"
    override val description = "Search contacts by name or phone number."
    override val parameters = ToolParameters(
        properties = mapOf(
            "query" to ParameterProperty("string", "Search query (name or number)"),
            "limit" to ParameterProperty("integer", "Max results (default 10)")
        ),
        required = listOf("query")
    )
    override val requiresApproval = false

    override suspend fun execute(params: JsonObject): ToolResult {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'query' parameter")
        val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 10

        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                arrayOf("%$query%", "%$query%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            val contacts = buildJsonArray {
                cursor?.use {
                    var count = 0
                    while (it.moveToNext() && count < limit) {
                        add(buildJsonObject {
                            put("name", it.getString(0) ?: "")
                            put("number", it.getString(1) ?: "")
                            put("type", when (it.getInt(2)) {
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "mobile"
                                ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "home"
                                ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "work"
                                else -> "other"
                            })
                        })
                        count++
                    }
                }
            }

            ToolResult.success(contacts)
        } catch (e: Exception) {
            ToolResult.error("Failed to search contacts: ${e.message}")
        }
    }
}

class ContactsAddTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "contacts.add"
    override val description = "Add a new contact with name and phone number."
    override val parameters = ToolParameters(
        properties = mapOf(
            "name" to ParameterProperty("string", "Contact display name"),
            "number" to ParameterProperty("string", "Phone number"),
            "email" to ParameterProperty("string", "Email address (optional)")
        ),
        required = listOf("name", "number")
    )
    override val requiresApproval = true

    override suspend fun execute(params: JsonObject): ToolResult {
        val name = params["name"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'name' parameter")
        val number = params["number"]?.jsonPrimitive?.content
            ?: return ToolResult.error("Missing 'number' parameter")
        val email = params["email"]?.jsonPrimitive?.contentOrNull

        return try {
            val ops = ArrayList<android.content.ContentProviderOperation>()

            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            // Name
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )

            // Phone
            ops.add(
                android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )

            // Email
            if (email != null) {
                ops.add(
                    android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .build()
                )
            }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)

            ToolResult.success(buildJsonObject {
                put("added", true)
                put("name", name)
                put("number", number)
            })
        } catch (e: Exception) {
            ToolResult.error("Failed to add contact: ${e.message}")
        }
    }
}
