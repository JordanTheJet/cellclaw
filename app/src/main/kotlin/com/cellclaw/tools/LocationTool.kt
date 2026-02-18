package com.cellclaw.tools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

class LocationTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {
    override val name = "location.get"
    override val description = "Get the current GPS location with optional reverse geocoding."
    override val parameters = ToolParameters(
        properties = mapOf(
            "geocode" to ParameterProperty("boolean", "Include reverse-geocoded address (default true)")
        )
    )
    override val requiresApproval = false

    @SuppressLint("MissingPermission")
    override suspend fun execute(params: JsonObject): ToolResult {
        val geocode = params["geocode"]?.jsonPrimitive?.booleanOrNull ?: true

        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cancellationToken = CancellationTokenSource()

            val location = suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationToken.token
                ).addOnSuccessListener { loc ->
                    cont.resume(loc)
                }.addOnFailureListener {
                    cont.resume(null)
                }
                cont.invokeOnCancellation { cancellationToken.cancel() }
            } ?: return ToolResult.error("Could not get location")

            val result = buildJsonObject {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy_meters", location.accuracy.toDouble())
                location.altitude.let { put("altitude", it) }

                if (geocode) {
                    try {
                        @Suppress("DEPRECATION")
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        addresses?.firstOrNull()?.let { addr ->
                            put("address", buildJsonObject {
                                put("street", addr.getAddressLine(0) ?: "")
                                put("city", addr.locality ?: "")
                                put("state", addr.adminArea ?: "")
                                put("country", addr.countryName ?: "")
                                put("postal_code", addr.postalCode ?: "")
                            })
                        }
                    } catch (_: Exception) {
                        // Geocoding failed, just return coordinates
                    }
                }
            }

            ToolResult.success(result)
        } catch (e: Exception) {
            ToolResult.error("Location error: ${e.message}")
        }
    }
}
