package com.example.gnssbridge

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class BackendClient {

    companion object {
        private const val BACKEND_URL =
            "https://gnss-accuracy-visualizer.onrender.com/api/gnss"
    }

    private val client = OkHttpClient()

    suspend fun sendGnssData(data: GnssData): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("latitude", data.latitude ?: JSONObject.NULL)
                    put("longitude", data.longitude ?: JSONObject.NULL)
                    put("altitude", data.altitude ?: JSONObject.NULL)
                    put("accuracy", data.accuracy ?: JSONObject.NULL)
                    put("northError", data.northError ?: JSONObject.NULL)
                    put("eastError", data.eastError ?: JSONObject.NULL)
                    put("verticalError", data.verticalError ?: JSONObject.NULL)
                    put("satellites", data.satellites ?: JSONObject.NULL)
                    put("hdop", data.hdop ?: JSONObject.NULL)
                    put("fixType", data.fixType ?: JSONObject.NULL)
                    put("connected", data.connected)
                    put("status", data.status)
                    put("timestamp", data.timestamp)
                }
                postJson(json)
            } catch (_: Exception) {
                false
            }
        }

    suspend fun sendPdrData(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        accuracy: Double,
        satellites: Int,
        hdop: Double,
        fixType: String,
        connected: Boolean,
        status: String,
        positionSource: String,
        speed: Double,
        course: Double
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("latitude", latitude)
                put("longitude", longitude)
                put("altitude", if (altitude.isFinite()) altitude else JSONObject.NULL)
                put("accuracy", if (accuracy.isFinite()) accuracy else JSONObject.NULL)
                put("northError", JSONObject.NULL)
                put("eastError", JSONObject.NULL)
                put("verticalError", JSONObject.NULL)
                put("satellites", if (satellites >= 0) satellites else JSONObject.NULL)
                put("hdop", if (hdop.isFinite()) hdop else JSONObject.NULL)
                put("fixType", fixType)
                put("positionSource", positionSource)
                put("connected", connected)
                put("status", status)
                put("timestamp", java.time.Instant.now().toString())
                put("speed", if (speed.isFinite()) speed else JSONObject.NULL)
                put("course", if (course.isFinite()) course else JSONObject.NULL)
            }
            postJson(json)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun sendAndroidLocation(location: Location): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("latitude", location.latitude)
                    put("longitude", location.longitude)
                    put("altitude", if (location.hasAltitude()) location.altitude else JSONObject.NULL)
                    put("accuracy", if (location.hasAccuracy()) location.accuracy else JSONObject.NULL)
                    put("northError", JSONObject.NULL)
                    put("eastError", JSONObject.NULL)
                    put("verticalError", JSONObject.NULL)
                    put("satellites", JSONObject.NULL)
                    put("hdop", JSONObject.NULL)
                    put("fixType", "Android Location")
                    put("connected", true)
                    put("status", "Android Location")
                    put("timestamp", java.time.Instant.ofEpochMilli(location.time).toString())
                    put("speed", if (location.hasSpeed()) location.speed else JSONObject.NULL)
                    put("course", if (location.hasBearing()) location.bearing.toDouble() else JSONObject.NULL)
                }
                postJson(json)
            } catch (_: Exception) {
                false
            }
        }

    private fun postJson(json: JSONObject): Boolean {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(BACKEND_URL)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    suspend fun sendConnectionStatus(
        connected: Boolean,
        status: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("connected", connected)
                put("status", status)
                put("timestamp", java.time.Instant.now().toString())
            }
            postJson(json)
        } catch (_: Exception) {
            false
        }
    }
}
