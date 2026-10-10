package com.snapnest.dispatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object SupportApi {
    data class Result(val ticketId: String, val attachmentUrl: String?)

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (status !in 200..299) throw IllegalStateException(json.optString("error", "Support request failed ($status)"))
        return json
    }

    private fun activeSession(store: SessionStore): SessionStore.Session {
        // Snapshot goes through DriverApi's refresh path first so the following
        // upload/ticket calls use a current access token.
        DriverApi.driverSnapshot(store)
        return store.load() ?: throw IllegalStateException("Driver session expired. Sign in again.")
    }

    private fun uploadScreenshot(session: SessionStore.Session, bytes: ByteArray): String {
        val conn = (URL("${session.baseUrl}/api/support/attachment").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(bytes) }
        return readJson(conn).getString("attachmentUrl")
    }

    fun report(store: SessionStore, subject: String, description: String, screenshot: ByteArray? = null): Result {
        require(subject.isNotBlank()) { "Subject is required." }
        require(description.isNotBlank()) { "Tell us what happened." }
        val session = activeSession(store)
        val snapshot = DriverApi.driverSnapshot(store)
        val attachmentUrl = screenshot?.let { uploadScreenshot(session, it) }
        val bookingId = snapshot.trip?.bookingId ?: snapshot.offer?.bookingId
        val conn = (URL("${session.baseUrl}/api/support").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${session.accessToken}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        val payload = JSONObject()
            .put("subject", subject.trim())
            .put("description", description.trim())
            .put("category", "app")
            .put("priority", "normal")
        if (!bookingId.isNullOrBlank()) payload.put("bookingId", bookingId)
        if (!attachmentUrl.isNullOrBlank()) payload.put("attachmentUrl", attachmentUrl)
        conn.outputStream.bufferedWriter().use { it.write(payload.toString()) }
        val ticket = readJson(conn)
        return Result(ticket.optString("id"), attachmentUrl)
    }
}
