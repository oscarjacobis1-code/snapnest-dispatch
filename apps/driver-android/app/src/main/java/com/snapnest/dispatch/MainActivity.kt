package com.snapnest.dispatch

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    lateinit var sessionStore: SessionStore
        private set

    private var pendingDutyStatus: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D2A40.toInt()
        window.navigationBarColor = 0xFF071A27.toInt()
        sessionStore = SessionStore(this)
        setContent { SnapNestDriverApp(this) }
    }

    fun enableDuty(status: String = "available") {
        if (sessionStore.load() == null) return
        pendingDutyStatus = status
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 2401)
            return
        }
        startDutyServices(status)
        pendingDutyStatus = null
    }

    private fun startDutyServices(status: String) {
        when (status) {
            "available", "offered" -> {
                startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_AVAILABLE))
                startForegroundService(Intent(this, DriverLocationService::class.java))
            }
            "busy" -> {
                startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_BUSY))
                startForegroundService(Intent(this, DriverLocationService::class.java))
            }
            "unavailable" -> {
                stopService(Intent(this, DriverLocationService::class.java))
                startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_UNAVAILABLE))
            }
            "offline" -> stopDutyServices()
        }
    }

    fun ensureDutyForStatus(status: String) {
        if (sessionStore.load() == null || !Settings.canDrawOverlays(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (status in setOf("available", "offered", "busy") && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        startDutyServices(status)
    }

    fun updateDriverStatus(status: String, onResult: (Result<Unit>) -> Unit) {
        Thread {
            val result = runCatching {
                DriverApi.postStatus(sessionStore, status)
                startDutyServices(status)
            }
            runOnUiThread { onResult(result) }
        }.start()
    }

    fun logout(onDone: () -> Unit) {
        Thread {
            runCatching { DriverApi.postStatus(sessionStore, "offline") }
            runOnUiThread {
                stopDutyServices()
                sessionStore.clear()
                onDone()
            }
        }.start()
    }

    fun stopDutyServices() {
        stopService(Intent(this, DriverLocationService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        getSystemService(NotificationManager::class.java).cancel(DriverLocationService.OFFER_ID)
        getSystemService(NotificationManager::class.java).cancel(DriverLocationService.ACCEPTED_JOB_ID)
    }

    fun navigate(lat: Double?, lng: Double?, label: String) {
        val target = if (lat != null && lng != null) "$lat,$lng" else label
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(target)}")).apply { setPackage("com.google.android.apps.maps") }
        try { startActivity(google) }
        catch (_: ActivityNotFoundException) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(target)}"))) }
    }

    fun contactPassenger(phone: String, message: Boolean) {
        if (phone.isBlank()) return
        val uri = if (message) Uri.parse("smsto:${Uri.encode(phone)}") else Uri.parse("tel:${Uri.encode(phone)}")
        startActivity(Intent(if (message) Intent.ACTION_SENDTO else Intent.ACTION_DIAL, uri))
    }

    fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    fun lastKnownLocation(): Pair<Double, Double>? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val location = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
            ?: return null
        return location.latitude to location.longitude
    }

    override fun onResume() {
        super.onResume()
        val status = pendingDutyStatus ?: return
        if (Settings.canDrawOverlays(this)) enableDuty(status)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 2401) return
        val status = pendingDutyStatus ?: "available"
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) enableDuty(status)
    }
}
