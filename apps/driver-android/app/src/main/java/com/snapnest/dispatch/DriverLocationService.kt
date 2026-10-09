package com.snapnest.dispatch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder

class DriverLocationService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private lateinit var sessionStore: SessionStore

    override fun onCreate() {
        super.onCreate()
        val channelId = "dispatch-location"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Driver location", NotificationManager.IMPORTANCE_LOW))
        val notification = android.app.Notification.Builder(this, channelId)
            .setContentTitle("SnapNest Dispatch")
            .setContentText("Location sharing is active while you are on duty")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        startForeground(2402, notification)

        sessionStore = SessionStore(this)
        if (sessionStore.load() == null) {
            stopSelf()
            return
        }

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
            .forEach { locationManager.requestLocationUpdates(it, 10_000L, 10f, this) }
    }

    override fun onLocationChanged(location: Location) {
        Thread {
            runCatching {
                DriverApi.postLocation(sessionStore, location.latitude, location.longitude, location.accuracy)
            }.onFailure {
                if (sessionStore.load() == null) stopSelf()
            }
        }.start()
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onBind(intent: android.content.Intent?): IBinder? = null
    override fun onDestroy() {
        if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        super.onDestroy()
    }
}
