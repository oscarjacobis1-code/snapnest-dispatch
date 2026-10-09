package com.snapnest.dispatch

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DriverApi {
    data class ActiveOffer(
        val bookingId: String,
        val pickup: String,
        val destination: String,
        val passengerName: String,
        val passengers: Int,
        val notes: String,
        val expiresAt: Long?
    )

    private class ApiException(val status: Int, message: String) : IllegalStateException(message)

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
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (status !in 200..299) throw ApiException(status, json.optString("error", "Request failed ($status)"))
        return json
    }

    private fun sessionFromJson(baseUrl: String, json: JSONObject): SessionStore.Session {
        val driver = json.optJSONObject("driver") ?: throw IllegalStateException("This account is not linked to a driver.")
        val access = json.optString("accessToken")
        val refresh = json.optString("refreshToken")
        if (access.isBlank() || refresh.isBlank()) throw IllegalStateException("The server did not return a complete driver session.")
        return SessionStore.Session(baseUrl.trimEnd('/'), access, refresh, driver.getString("id"))
    }

    fun login(baseUrl: String, email: String, password: String): SessionStore.Session {
        val cleanBase = baseUrl.trimEnd('/')
        val conn = connection("$cleanBase/api/auth/login", "POST")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { writer ->
            writer.write(JSONObject().put("email", email).put("password", password).toString())
        }
        return sessionFromJson(cleanBase, readJson(conn))
    }

    private fun refresh(session: SessionStore.Session): SessionStore.Session {
        val conn = connection("${session.baseUrl}/api/auth/refresh", "POST")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { writer ->
            writer.write(JSONObject().put("refreshToken", session.refreshToken).toString())
        }
        return sessionFromJson(session.baseUrl, readJson(conn))
    }

    @Synchronized
    private fun <T> withRefresh(store: SessionStore, request: (SessionStore.Session) -> T): T {
        val initial = store.load() ?: throw IllegalStateException("Driver session expired. Sign in again.")
        try {
            return request(initial)
        } catch (error: ApiException) {
            if (error.status != 401) throw error
        }

        val refreshed = try {
            refresh(initial)
        } catch (error: Exception) {
            store.clear()
            throw IllegalStateException("Driver session expired. Sign in again.", error)
        }
        store.save(refreshed)
        return try {
            request(refreshed)
        } catch (error: ApiException) {
            if (error.status == 401) store.clear()
            throw error
        }
    }

    fun postLocation(store: SessionStore, lat: Double, lng: Double, accuracyM: Float) {
        withRefresh(store) { session ->
            val conn = connection("${session.baseUrl}/api/drivers/${session.driverId}/location", "POST", session.accessToken)
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("lat", lat).put("lng", lng).put("accuracyM", accuracyM.toDouble()).toString())
            }
            readJson(conn)
        }
    }

    fun postStatus(store: SessionStore, status: String) {
        withRefresh(store) { session ->
            val conn = connection("${session.baseUrl}/api/drivers/${session.driverId}/status", "POST", session.accessToken)
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("status", status).toString())
            }
            readJson(conn)
        }
    }

    fun activeOffer(store: SessionStore): ActiveOffer? = withRefresh(store) { session ->
        val conn = connection("${session.baseUrl}/api/drivers/${session.driverId}/active-offer", "GET", session.accessToken)
        val offer = readJson(conn).optJSONObject("offer") ?: return@withRefresh null
        val pickup = offer.optJSONObject("pickup")?.optString("label").orEmpty()
        val destination = offer.optJSONObject("destination")?.optString("label").orEmpty()
        val expires = if (offer.has("offerExpiresAt") && !offer.isNull("offerExpiresAt")) offer.optLong("offerExpiresAt") else null
        ActiveOffer(
            bookingId = offer.getString("id"),
            pickup = pickup,
            destination = destination,
            passengerName = offer.optString("passengerName", "Guest"),
            passengers = offer.optInt("passengers", 1),
            notes = offer.optString("notes", ""),
            expiresAt = expires
        )
    }

    fun respondToOffer(store: SessionStore, bookingId: String, accept: Boolean) {
        withRefresh(store) { session ->
            val conn = connection("${session.baseUrl}/api/bookings/$bookingId/offer-response", "POST", session.accessToken)
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().put("driverId", session.driverId).put("accept", accept).toString())
            }
            readJson(conn)
        }
    }
}
