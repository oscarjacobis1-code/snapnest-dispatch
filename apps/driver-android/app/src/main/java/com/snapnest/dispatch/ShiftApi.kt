package com.snapnest.dispatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ShiftSummary(
    val active: Boolean,
    val startedAt: String,
    val endedAt: String,
    val durationMinutes: Int,
    val completed: Int,
    val cancelled: Int,
    val noShows: Int,
    val trips: Int
)

object ShiftApi {
    fun summary(store: SessionStore): ShiftSummary {
        DriverApi.driverSnapshot(store)
        val session = store.load() ?: throw IllegalStateException("Driver session expired. Sign in again.")
        val conn = (URL("${session.baseUrl}/api/drivers/${session.driverId}/shift").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Accept", "application/json")
        }
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (status !in 200..299) throw IllegalStateException(json.optString("error", "Could not load shift summary."))
        val shift = json.optJSONObject("shift") ?: JSONObject()
        return ShiftSummary(
            active = shift.optBoolean("active", false),
            startedAt = shift.optString("startedAt", ""),
            endedAt = shift.optString("endedAt", ""),
            durationMinutes = shift.optInt("durationMinutes", 0),
            completed = shift.optInt("completed", 0),
            cancelled = shift.optInt("cancelled", 0),
            noShows = shift.optInt("noShows", 0),
            trips = shift.optInt("trips", 0)
        )
    }
}
