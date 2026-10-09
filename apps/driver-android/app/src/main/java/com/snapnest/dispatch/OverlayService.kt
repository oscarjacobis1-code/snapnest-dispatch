package com.snapnest.dispatch

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        const val ACTION_AVAILABLE = "com.snapnest.dispatch.overlay.AVAILABLE"
        const val ACTION_BUSY = "com.snapnest.dispatch.overlay.BUSY"
        private val SNAPNEST_NAVY = Color.rgb(13, 42, 64)
        private val SNAPNEST_ORANGE = Color.rgb(239, 106, 0)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: TextView
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var pttClient: PttClient
    private val mainHandler = Handler(Looper.getMainLooper())
    private var busyMode = false
    private var pttState = PttClient.State.CONNECTING

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(41, notification("Connecting driver radio…"))
        showBubble()
        pttClient = PttClient(this, SessionStore(this)) { state ->
            mainHandler.post {
                pttState = state
                if (::bubble.isInitialized) applyVisualState()
                val text = when (state) {
                    PttClient.State.READY -> if (busyMode) "On job · PTT ready" else "Driver available · PTT ready"
                    PttClient.State.TALKING -> "Transmitting to dispatcher"
                    PttClient.State.BUSY -> "Radio busy · another speaker has the floor"
                    PttClient.State.CONNECTING -> "Connecting driver radio…"
                    PttClient.State.OFFLINE -> "PTT offline · dispatch app still active"
                }
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(41, notification(text))
            }
        }
        pttClient.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BUSY -> setBusyMode(true)
            ACTION_AVAILABLE -> setBusyMode(false)
        }
        return START_STICKY
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            setTextColor(Color.WHITE)
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
        applyVisualState()

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var talkRequested = false

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        talkRequested = true
                        bubble.text = "WAIT…"
                        bubble.background = bubbleBackground(SNAPNEST_NAVY, SNAPNEST_ORANGE)
                        pttClient.beginTalk()
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - initialTouchX) > 18 || abs(event.rawY - initialTouchY) > 18) {
                            if (talkRequested) pttClient.endTalk()
                            talkRequested = false
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            applyVisualState()
                            windowManager.updateViewLayout(bubble, params)
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (talkRequested) pttClient.endTalk()
                        talkRequested = false
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(bubble, params)
    }

    private fun setBusyMode(busy: Boolean) {
        busyMode = busy
        if (!::bubble.isInitialized) return
        params.width = if (busy) 124 else 170
        params.height = if (busy) 124 else 170
        applyVisualState()
        windowManager.updateViewLayout(bubble, params)
    }

    private fun applyVisualState() {
        when (pttState) {
            PttClient.State.TALKING -> {
                bubble.text = "TALKING…"
                bubble.background = bubbleBackground(SNAPNEST_ORANGE, Color.WHITE)
            }
            PttClient.State.BUSY -> {
                bubble.text = "RADIO\nBUSY"
                bubble.background = bubbleBackground(Color.rgb(71, 85, 105), Color.rgb(148, 163, 184))
            }
            PttClient.State.OFFLINE -> {
                bubble.text = "PTT\nOFFLINE"
                bubble.background = bubbleBackground(Color.rgb(153, 27, 27), Color.rgb(248, 113, 113))
            }
            PttClient.State.CONNECTING -> {
                bubble.text = "PTT…"
                bubble.background = bubbleBackground(SNAPNEST_NAVY, SNAPNEST_ORANGE)
            }
            PttClient.State.READY -> {
                bubble.text = if (busyMode) "PTT" else "HOLD\nTO TALK"
                bubble.background = bubbleBackground(
                    if (busyMode) Color.rgb(180, 110, 5) else Color.rgb(22, 128, 59),
                    if (busyMode) SNAPNEST_ORANGE else Color.WHITE
                )
            }
        }
    }

    override fun onDestroy() {
        if (::pttClient.isInitialized) pttClient.close()
        if (::bubble.isInitialized) windowManager.removeView(bubble)
        super.onDestroy()
    }

    private fun bubbleBackground(color: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(4, stroke)
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
