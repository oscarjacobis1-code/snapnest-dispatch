package com.snapnest.dispatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DriverApi {
    data class Session(val accessToken: String, val driverId: String)

    private fun connection(url: String, method: String, token: String? = null): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            doInput = true
        }
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (conn.responseCode !in 200..299) throw IllegalStateException(json.optString("error", "Request failed (${conn.responseCode})"))
        return json
    }

    fun login(baseUrl: String, email: String, password: String): Session {
        val conn = connection("${baseUrl.trimEnd('/')}/api/auth/login", "POST")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { writer -> writer.write(JSONObject().put("email", email).put("password", password).toString()) }
        val json = readJson(conn)
        val driver = json.optJSONObject("driver") ?: throw IllegalStateException("This account is not linked to a driver.")
        return Session(json.getString("accessToken"), driver.getString("id"))
    }

    fun postLocation(baseUrl: String, token: String, driverId: String, lat: Double, lng: Double, accuracyM: Float) {
        val conn = connection("${baseUrl.trimEnd('/')}/api/drivers/$driverId/location", "POST", token)
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { writer -> writer.write(JSONObject().put("lat", lat).put("lng", lng).put("accuracyM", accuracyM.toDouble()).toString()) }
        readJson(conn)
    }

    fun postStatus(baseUrl: String, token: String, driverId: String, status: String) {
        val conn = connection("${baseUrl.trimEnd('/')}/api/drivers/$driverId/status", "POST", token)
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { writer -> writer.write(JSONObject().put("status", status).toString()) }
        readJson(conn)
    }
}
