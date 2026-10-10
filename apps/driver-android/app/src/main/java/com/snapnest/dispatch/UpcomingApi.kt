package com.snapnest.dispatch

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpcomingApi {
    data class Job(
        val id: String,
        val passengerName: String,
        val passengerPhone: String,
        val passengers: Int,
        val notes: String,
        val pickup: String,
        val destination: String,
        val scheduledFor: String
    )

    fun list(store: SessionStore, limit: Int = 20): List<Job> {
        // This call also refreshes an expired access token using DriverApi's proven session path.
        DriverApi.driverSnapshot(store)
        val session = store.load() ?: throw IllegalStateException("Driver session expired. Sign in again.")
        val url = URL("${session.baseUrl}/api/drivers/${session.driverId}/upcoming?limit=${limit.coerceIn(1, 50)}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
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
        if (status !in 200..299) throw IllegalStateException(json.optString("error", "Could not load upcoming jobs."))
        val rows = json.optJSONArray("bookings") ?: JSONArray()
        return buildList {
            for (i in 0 until rows.length()) {
                val item = rows.optJSONObject(i) ?: continue
                add(
                    Job(
                        id = item.optString("id"),
                        passengerName = item.optString("passengerName", "Guest"),
                        passengerPhone = item.optString("passengerPhone", ""),
                        passengers = item.optInt("passengers", 1),
                        notes = item.optString("notes", ""),
                        pickup = item.optJSONObject("pickup")?.optString("label").orEmpty(),
                        destination = item.optJSONObject("destination")?.optString("label").orEmpty(),
                        scheduledFor = item.optString("scheduledFor")
                    )
                )
            }
        }
    }
}
