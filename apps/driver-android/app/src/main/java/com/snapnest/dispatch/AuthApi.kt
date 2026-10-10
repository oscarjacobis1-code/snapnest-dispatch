package com.snapnest.dispatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AuthApi {
    const val CALLBACK_URL = "snapnestdispatch://auth/callback"

    private fun connection(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            doInput = true
        }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (status !in 200..299) throw IllegalStateException(json.optString("error", "Request failed ($status)"))
        return json
    }

    private fun sessionFromJson(baseUrl: String, json: JSONObject): SessionStore.Session {
        val driver = json.optJSONObject("driver") ?: throw IllegalStateException("This account is not linked to a driver.")
        val access = json.optString("accessToken")
        val refresh = json.optString("refreshToken")
        if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("The server did not return a complete driver session.")
        return SessionStore.Session(
            baseUrl = baseUrl.trimEnd('/'),
            accessToken = access,
            refreshToken = refresh,
            driverId = driver.getString("id"),
            driverName = driver.optString("display_name", driver.optString("name", "Driver")),
            vehicle = driver.optString("vehicle_plate", driver.optString("vehicle", ""))
        )
    }

    fun oauthUrl(baseUrl: String, provider: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        val encodedRedirect = URLEncoder.encode(CALLBACK_URL, StandardCharsets.UTF_8.toString())
        val encodedProvider = URLEncoder.encode(provider, StandardCharsets.UTF_8.toString())
        val conn = connection("$cleanBase/api/auth/oauth-url?provider=$encodedProvider&redirectTo=$encodedRedirect", "GET")
        val url = readJson(conn).optString("url")
        if (url.isBlank()) throw IllegalStateException("SSO provider did not return a sign-in URL.")
        return url
    }

    fun sessionFromTokens(baseUrl: String, accessToken: String, refreshToken: String, expiresIn: Long? = null): SessionStore.Session {
        val cleanBase = baseUrl.trimEnd('/')
        val conn = connection("$cleanBase/api/auth/session", "POST")
        conn.doOutput = true
        val body = JSONObject()
            .put("accessToken", accessToken)
            .put("refreshToken", refreshToken)
        if (expiresIn != null) body.put("expiresIn", expiresIn)
        conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
        return sessionFromJson(cleanBase, readJson(conn))
    }

    fun requestPasswordReset(baseUrl: String, email: String) {
        val cleanBase = baseUrl.trimEnd('/')
        val conn = connection("$cleanBase/api/auth/password-reset", "POST")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use {
            it.write(JSONObject().put("email", email.trim()).put("redirectTo", CALLBACK_URL).toString())
        }
        readJson(conn)
    }

    fun updatePassword(baseUrl: String, accessToken: String, password: String) {
        val cleanBase = baseUrl.trimEnd('/')
        val conn = connection("$cleanBase/api/auth/password-update", "POST")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use {
            it.write(JSONObject().put("accessToken", accessToken).put("password", password).toString())
        }
        readJson(conn)
    }
}
