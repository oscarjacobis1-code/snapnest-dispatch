package com.snapnest.dispatch

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(41, notification("Driver available · PTT ready"))
        showBubble()
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            text = "HOLD\nTO TALK"
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.WHITE)
            background = bubbleBackground(Color.rgb(22, 128, 59))
            elevation = 12f
            setPadding(14, 14, 14, 14)
        }
        params = WindowManager.LayoutParams(
            170, 170,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 360
        }

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var talking = false

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        talking = true
                        bubble.text = "TALKING…"
                        bubble.background = bubbleBackground(Color.rgb(37, 88, 184))
                        // TODO: start explicit WebRTC/audio transmit session.
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - initialTouchX) > 18 || abs(event.rawY - initialTouchY) > 18) {
                            talking = false
                            bubble.text = "HOLD\nTO TALK"
                            bubble.background = bubbleBackground(Color.rgb(22, 128, 59))
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(bubble, params)
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (talking) {
                            // TODO: stop transmit immediately on release.
                            talking = false
                        }
                        bubble.text = "HOLD\nTO TALK"
                        bubble.background = bubbleBackground(Color.rgb(22, 128, 59))
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(bubble, params)
    }

    override fun onDestroy() {
        if (::bubble.isInitialized) windowManager.removeView(bubble)
        super.onDestroy()
    }

    private fun bubbleBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "driver-duty")
            .setContentTitle("SnapNest Dispatch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("driver-duty", "Driver duty", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
