package com.snapnest.dispatch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DriverLocationService : Service(), LocationListener {
    companion object {
        const val CHANNEL_LOCATION = "dispatch-location"
        const val CHANNEL_OFFERS = "dispatch-job-offers"
        const val FOREGROUND_ID = 2402
        const val OFFER_ID = 2601
        const val ACCEPTED_JOB_ID = 2701
    }

    private lateinit var locationManager: LocationManager
    private lateinit var sessionStore: SessionStore
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var offerTask: ScheduledFuture<*>? = null
    private var currentOfferId: String? = null

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(this)
        if (sessionStore.load() == null) {
            stopSelf()
            return
        }

        createChannels()
        startForeground(FOREGROUND_ID, dutyNotification())
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        startLocationUpdates()
        offerTask = scheduler.scheduleWithFixedDelay(::checkForOffer, 0, 4, TimeUnit.SECONDS)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LOCATION, "Driver duty", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_OFFERS, "Incoming taxi jobs", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New trip offers requiring driver response"
                enableVibration(true)
            }
        )
    }

    private fun dutyNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            2402,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_LOCATION)
            .setContentTitle("SnapNest Dispatch")
            .setContentText("On duty · location and job connection active")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
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

    private fun checkForOffer() {
        if (sessionStore.load() == null) {
            stopSelf()
            return
        }
        runCatching { DriverApi.activeOffer(sessionStore) }
            .onSuccess { offer ->
                if (offer == null) {
                    if (currentOfferId != null) getSystemService(NotificationManager::class.java).cancel(OFFER_ID)
                    currentOfferId = null
                } else if (offer.bookingId != currentOfferId) {
                    currentOfferId = offer.bookingId
                    showOffer(offer)
                }
            }
            .onFailure {
                if (sessionStore.load() == null) stopSelf()
            }
    }

    private fun showOffer(offer: DriverApi.ActiveOffer) {
        val accept = actionIntent(offer, true, 2602)
        val decline = actionIntent(offer, false, 2603)
        val open = PendingIntent.getActivity(
            this,
            2604,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val details = buildString {
            append(offer.pickup)
            append(" → ")
            append(offer.destination)
            if (offer.passengerName.isNotBlank()) append("\n${offer.passengerName} · ${offer.passengers} passenger(s)")
            if (offer.notes.isNotBlank()) append("\n${offer.notes}")
        }
        val notification = Notification.Builder(this, CHANNEL_OFFERS)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("New taxi job")
            .setContentText("${offer.pickup} → ${offer.destination}")
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setContentIntent(open)
            .setAutoCancel(false)
            .setTimeoutAfter(30_000)
            .addAction(Notification.Action.Builder(null, "DECLINE", decline).build())
            .addAction(Notification.Action.Builder(null, "ACCEPT", accept).build())
            .build()
        getSystemService(NotificationManager::class.java).notify(OFFER_ID, notification)
    }

    private fun actionIntent(offer: DriverApi.ActiveOffer, accept: Boolean, requestCode: Int): PendingIntent {
        val intent = Intent(this, JobActionReceiver::class.java)
            .setAction(if (accept) "com.snapnest.dispatch.ACCEPT_JOB" else "com.snapnest.dispatch.DECLINE_JOB")
            .putExtra("booking_id", offer.bookingId)
            .putExtra("pickup", offer.pickup)
            .putExtra("destination", offer.destination)
            .putExtra("accept", accept)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        offerTask?.cancel(true)
        scheduler.shutdownNow()
        getSystemService(NotificationManager::class.java).cancel(OFFER_ID)
        if (::locationManager.isInitialized) locationManager.removeUpdates(this)
        super.onDestroy()
    }
}
