package com.snapnest.dispatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class DriverJobService : Service() {
    companion object {
        const val CHANNEL_DUTY = "dispatch-job-watch"
        const val CHANNEL_OFFERS = "dispatch-job-offers"
        const val FOREGROUND_ID = 2600
        const val OFFER_ID = 2601
    }

    private lateinit var sessionStore: SessionStore
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var task: ScheduledFuture<*>? = null
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
        task = scheduler.scheduleWithFixedDelay(::checkForOffer, 0, 4, TimeUnit.SECONDS)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DUTY, "Dispatch connection", NotificationManager.IMPORTANCE_LOW)
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
            2600,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_DUTY)
            .setContentTitle("SnapNest Dispatch")
            .setContentText("Connected and listening for jobs")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
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

    override fun onDestroy() {
        task?.cancel(true)
        scheduler.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
