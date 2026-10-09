package com.snapnest.dispatch

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class JobActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.snapnest.dispatch.ACCEPT_JOB"
        const val ACTION_DECLINE = "com.snapnest.dispatch.DECLINE_JOB"
        const val ACTION_START = "com.snapnest.dispatch.START_TRIP"
        const val ACTION_COMPLETE = "com.snapnest.dispatch.COMPLETE_TRIP"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val bookingId = intent.getStringExtra("booking_id").orEmpty()
                val pickup = intent.getStringExtra("pickup").orEmpty()
                val destination = intent.getStringExtra("destination").orEmpty()
                if (bookingId.isBlank()) return@Thread

                val store = SessionStore(context)
                val manager = context.getSystemService(NotificationManager::class.java)
                when (intent.action) {
                    ACTION_ACCEPT -> {
                        DriverApi.respondToOffer(store, bookingId, true)
                        manager.cancel(DriverLocationService.OFFER_ID)
                        context.startForegroundService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_BUSY))
                        showAccepted(context, bookingId, pickup, destination)
                    }
                    ACTION_DECLINE -> {
                        DriverApi.respondToOffer(store, bookingId, false)
                        manager.cancel(DriverLocationService.OFFER_ID)
                    }
                    ACTION_START -> {
                        DriverApi.updateTrip(store, bookingId, "start")
                        showInProgress(context, bookingId, pickup, destination)
                    }
                    ACTION_COMPLETE -> {
                        DriverApi.updateTrip(store, bookingId, "complete")
                        manager.cancel(DriverLocationService.ACCEPTED_JOB_ID)
                        context.startForegroundService(Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_AVAILABLE))
                        manager.notify(
                            2703,
                            Notification.Builder(context, DriverLocationService.CHANNEL_LOCATION)
                                .setSmallIcon(android.R.drawable.checkbox_on_background)
                                .setContentTitle("Trip complete")
                                .setContentText("You are available for the next job")
                                .setAutoCancel(true)
                                .build()
                        )
                    }
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

    private fun showAccepted(context: Context, bookingId: String, pickup: String, destination: String) {
        val start = tripAction(context, bookingId, pickup, destination, ACTION_START, "START TRIP", 2710)
        val open = openApp(context)
        context.getSystemService(NotificationManager::class.java).notify(
            DriverLocationService.ACCEPTED_JOB_ID,
            Notification.Builder(context, DriverLocationService.CHANNEL_LOCATION)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Job accepted · head to pickup")
                .setContentText("$pickup → $destination")
                .setStyle(Notification.BigTextStyle().bigText("Pickup: $pickup\nDestination: $destination"))
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "START TRIP", start).build())
                .build()
        )
    }

    private fun showInProgress(context: Context, bookingId: String, pickup: String, destination: String) {
        val complete = tripAction(context, bookingId, pickup, destination, ACTION_COMPLETE, "COMPLETE", 2711)
        context.getSystemService(NotificationManager::class.java).notify(
            DriverLocationService.ACCEPTED_JOB_ID,
            Notification.Builder(context, DriverLocationService.CHANNEL_LOCATION)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Trip in progress")
                .setContentText(destination)
                .setStyle(Notification.BigTextStyle().bigText("Destination: $destination"))
                .setContentIntent(openApp(context))
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "COMPLETE TRIP", complete).build())
                .build()
        )
    }

    private fun tripAction(context: Context, bookingId: String, pickup: String, destination: String, action: String, label: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, JobActionReceiver::class.java)
            .setAction(action)
            .putExtra("booking_id", bookingId)
            .putExtra("pickup", pickup)
            .putExtra("destination", destination)
        return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        2700,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
