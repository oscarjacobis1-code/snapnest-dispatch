package com.snapnest.dispatch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class JobActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val bookingId = intent.getStringExtra("booking_id").orEmpty()
                val accept = intent.getBooleanExtra("accept", false)
                val pickup = intent.getStringExtra("pickup").orEmpty()
                val destination = intent.getStringExtra("destination").orEmpty()
                if (bookingId.isBlank()) return@Thread

                val store = SessionStore(context)
                DriverApi.respondToOffer(store, bookingId, accept)
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.cancel(DriverLocationService.OFFER_ID)
                if (accept) {
                    context.startForegroundService(
                        Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_BUSY)
                    )
                    val open = PendingIntent.getActivity(
                        context,
                        2700,
                        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    manager.notify(
                        DriverLocationService.ACCEPTED_JOB_ID,
                        Notification.Builder(context, DriverLocationService.CHANNEL_LOCATION)
                            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                            .setContentTitle("Job accepted")
                            .setContentText("$pickup → $destination")
                            .setStyle(Notification.BigTextStyle().bigText("Pickup: $pickup\nDestination: $destination"))
                            .setContentIntent(open)
                            .setOngoing(true)
                            .build()
                    )
                }
            } catch (error: Exception) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.notify(
                    2702,
                    Notification.Builder(context, DriverLocationService.CHANNEL_OFFERS)
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setContentTitle("Could not update job")
                        .setContentText(error.message ?: "Open SnapNest Dispatch and try again.")
                        .setAutoCancel(true)
                        .build()
                )
            } finally {
                pending.finish()
            }
        }.start()
    }
}
