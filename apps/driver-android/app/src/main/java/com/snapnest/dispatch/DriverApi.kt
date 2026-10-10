package com.snapnest.dispatch

import org.json.JSONArray
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
        val expiresAt: Long?,
        val pickupLat: Double? = null,
        val pickupLng: Double? = null,
        val destinationLat: Double? = null,
        val destinationLng: Double? = null
    )

    data class ActiveTrip(
        val bookingId: String,
        val pickup: String,
        val destination: String,
        val passengerName: String,
        val passengers: Int,
        val notes: String,
        val status: String,
        val pickupLat: Double? = null,
        val pickupLng: Double? = null,
        val destinationLat: Double? = null,
        val destinationLng: Double? = null
    )

    data class DriverSnapshot(
        val driverName: String,
        val vehicle: String,
        val driverStatus: String,
        val offer: ActiveOffer?,
        val trip: ActiveTrip?
    )

    data class PttConnection(
        val enabled: Boolean,
        val provider: String,
        val serverUrl: String = "",
        val participantToken: String = "",
        val roomName: String = ""
    )

    data class PttLease(
        val granted: Boolean,
        val leaseToken: String = "",
        val expiresAt: Long = 0L
    )

    private class ApiException(val status: Int, message: String) : IllegalStateException(message)

    private fun connection(url: String, method: String, token: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            doInput = true
        }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        if (status !in 200..299) throw ApiException(status, json.optString("error", "Request failed ($status)"))
        return json
    }

    private fun nullableDouble(json: JSONObject?, key: String): Double? {
        if (json == null || !json.has(key) || json.isNull(key)) return null
        return json.optDouble(key).takeIf { !it.isNaN() }
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

    fun login(baseUrl: String, email: String, password: String): SessionStore.Session {
        val cleanBase = baseUrl.trimEnd('/')
        val conn = connection("$cleanBase/api/auth/login", "POST")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { it.write(JSONObject().put("email", email).put("password", password).toString()) }
        return sessionFromJson(cleanBase, readJson(conn))
    }

    private fun refresh(session: SessionStore.Session): SessionStore.Session {
        val conn = connection("${session.baseUrl}/api/auth/refresh", "POST")
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { it.write(JSONObject().put("refreshToken", session.refreshToken).toString()) }
        val refreshed = sessionFromJson(session.baseUrl, readJson(conn))
        return refreshed.copy(
            driverName = refreshed.driverName.ifBlank { session.driverName },
            vehicle = refreshed.vehicle.ifBlank { session.vehicle }
        )
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
            conn.outputStream.bufferedWriter().use {
                it.write(JSONObject().put("lat", lat).put("lng", lng).put("accuracyM", accuracyM.toDouble()).toString())
            }
            readJson(conn)
        }
    }

    fun postStatus(store: SessionStore, status: String) {
        withRefresh(store) { session ->
            val conn = connection("${session.baseUrl}/api/drivers/${session.driverId}/status", "POST", session.accessToken)
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { it.write(JSONObject().put("status", status).toString()) }
            readJson(conn)
        }
    }

    fun activeOffer(store: SessionStore): ActiveOffer? = withRefresh(store) { session ->
        val conn = connection("${session.baseUrl}/api/drivers/${session.driverId}/active-offer", "GET", session.accessToken)
        val offer = readJson(conn).optJSONObject("offer") ?: return@withRefresh null
        parseOffer(offer)
    }

    private fun parseOffer(offer: JSONObject): ActiveOffer {
        val pickup = offer.optJSONObject("pickup")
        val destination = offer.optJSONObject("destination")
        val expires = if (offer.has("offerExpiresAt") && !offer.isNull("offerExpiresAt")) offer.optLong("offerExpiresAt") else null
        return ActiveOffer(
            bookingId = offer.getString("id"),
            pickup = pickup?.optString("label").orEmpty(),
            destination = destination?.optString("label").orEmpty(),
            passengerName = offer.optString("passengerName", "Guest"),
            passengers = offer.optInt("passengers", 1),
            notes = offer.optString("notes", ""),
            expiresAt = expires,
            pickupLat = nullableDouble(pickup, "lat"),
            pickupLng = nullableDouble(pickup, "lng"),
            destinationLat = nullableDouble(destination, "lat"),
            destinationLng = nullableDouble(destination, "lng")
        )
    }

    private fun parseTrip(booking: JSONObject): ActiveTrip {
        val pickup = booking.optJSONObject("pickup")
        val destination = booking.optJSONObject("destination")
        return ActiveTrip(
            bookingId = booking.getString("id"),
            pickup = pickup?.optString("label").orEmpty(),
            destination = destination?.optString("label").orEmpty(),
            passengerName = booking.optString("passengerName", "Guest"),
            passengers = booking.optInt("passengers", 1),
            notes = booking.optString("notes", ""),
            status = booking.optString("status", "assigned"),
            pickupLat = nullableDouble(pickup, "lat"),
            pickupLng = nullableDouble(pickup, "lng"),
            destinationLat = nullableDouble(destination, "lat"),
            destinationLng = nullableDouble(destination, "lng")
        )
    }

    fun driverSnapshot(store: SessionStore): DriverSnapshot = withRefresh(store) { session ->
        val conn = connection("${session.baseUrl}/api/state", "GET", session.accessToken)
        val json = readJson(conn)
        val drivers = json.optJSONArray("drivers") ?: JSONArray()

        var driver: JSONObject? = null
        for (i in 0 until drivers.length()) {
            val candidate = drivers.optJSONObject(i) ?: continue
            if (candidate.optString("id") == session.driverId) {
                driver = candidate
                break
            }
        }

        val bookings = json.optJSONArray("bookings") ?: JSONArray()
        var offer: ActiveOffer? = null
        var trip: ActiveTrip? = null
        for (i in 0 until bookings.length()) {
            val booking = bookings.optJSONObject(i) ?: continue
            when (booking.optString("status")) {
                "offering" -> {
                    val offeredTo = booking.optString("currentOfferDriverId")
                    if (offer == null && offeredTo == session.driverId) offer = parseOffer(booking)
                }
                "assigned", "in_progress" -> {
                    val assignedTo = booking.optString("assignedDriverId")
                    if (trip == null && assignedTo == session.driverId) trip = parseTrip(booking)
                }
            }
        }

        DriverSnapshot(
            driverName = driver?.optString("name").orEmpty().ifBlank { session.driverName.ifBlank { "Driver" } },
            vehicle = driver?.optString("vehicle").orEmpty().ifBlank { session.vehicle },
            driverStatus = driver?.optString("status").orEmpty().ifBlank { if (trip != null) "busy" else if (offer != null) "offered" else "available" },
            offer = offer,
            trip = trip
        )
    }

    fun respondToOffer(store: SessionStore, bookingId: String, accept: Boolean) {
        withRefresh(store) { session ->
            val conn = connection("${session.baseUrl}/api/bookings/$bookingId/offer-response", "POST", session.accessToken)
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use {
                it.write(JSONObject().put("driverId", session.driverId).put("accept", accept).toString())
            }
            readJson(conn)
        }
    }

    fun updateTrip(store: SessionStore, bookingId: String, action: String) {
        require(action == "start" || action == "complete") { "Invalid trip action" }
        withRefresh(store) { session ->
            val conn = connection("${session.baseUrl}/api/bookings/$bookingId/$action", "POST", session.accessToken)
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { it.write(JSONObject().put("driverId", session.driverId).toString()) }
            readJson(conn)
        }
    }

    fun pttConnection(store: SessionStore): PttConnection = withRefresh(store) { session ->
        val conn = connection("${session.baseUrl}/api/ptt/token", "GET", session.accessToken)
        val json = readJson(conn)
        PttConnection(
            enabled = json.optBoolean("enabled", false),
            provider = json.optString("provider", "none"),
            serverUrl = json.optString("serverUrl", ""),
            participantToken = json.optString("participantToken", ""),
            roomName = json.optString("roomName", "")
        )
    }

    fun acquirePttFloor(store: SessionStore): PttLease = pttFloorRequest(store, "acquire", null)

    fun heartbeatPttFloor(store: SessionStore, leaseToken: String): PttLease =
        pttFloorRequest(store, "heartbeat", leaseToken)

    fun releasePttFloor(store: SessionStore, leaseToken: String): Boolean = withRefresh(store) { session ->
        val conn = connection("${session.baseUrl}/api/ptt/floor/release", "POST", session.accessToken)
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use { it.write(JSONObject().put("leaseToken", leaseToken).toString()) }
        readJson(conn).optBoolean("released", false)
    }

    private fun pttFloorRequest(store: SessionStore, action: String, leaseToken: String?): PttLease = withRefresh(store) { session ->
        val conn = connection("${session.baseUrl}/api/ptt/floor/$action", "POST", session.accessToken)
        conn.doOutput = true
        conn.outputStream.bufferedWriter().use {
            it.write(if (leaseToken == null) "{}" else JSONObject().put("leaseToken", leaseToken).toString())
        }
        val json = readJson(conn)
        PttLease(
            granted = json.optBoolean("granted", false),
            leaseToken = json.optString("leaseToken", ""),
            expiresAt = json.optLong("expiresAt", 0L)
        )
    }
}
