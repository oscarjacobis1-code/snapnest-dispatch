package com.snapnest.dispatch

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {
    companion object {
        const val ACTION_AVAILABLE = "com.snapnest.dispatch.overlay.AVAILABLE"
        const val ACTION_BUSY = "com.snapnest.dispatch.overlay.BUSY"
        const val ACTION_UNAVAILABLE = "com.snapnest.dispatch.overlay.UNAVAILABLE"
        private val NAVY = Color.rgb(13, 42, 64)
        private val ORANGE = Color.rgb(239, 106, 0)
        private val GREEN = Color.rgb(45, 214, 123)
        private val AMBER = Color.rgb(242, 163, 33)
        private val RED = Color.rgb(239, 90, 103)
        private val BLUE = Color.rgb(61, 155, 255)
        private val MUTED = Color.rgb(148, 163, 184)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: FrameLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var pttClient: PttClient
    private val mainHandler = Handler(Looper.getMainLooper())
    private var menu: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var busyMode = false
    private var unavailableMode = false
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
                    PttClient.State.READY -> when {
                        unavailableMode -> "Driver unavailable · PTT ready"
                        busyMode -> "On job · PTT ready"
                        else -> "Driver available · PTT ready"
                    }
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
            ACTION_BUSY -> setMode(busy = true, unavailable = false)
            ACTION_AVAILABLE -> setMode(busy = false, unavailable = false)
            ACTION_UNAVAILABLE -> setMode(busy = false, unavailable = true)
        }
        return START_STICKY
    }

    private fun showBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = FrameLayout(this).apply {
            elevation = dp(14).toFloat()
            clipToPadding = false
            addView(SnapNestLogoView(this@OverlayService), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        }
        params = WindowManager.LayoutParams(
            dp(64), dp(64),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(310)
        }
        applyVisualState()

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var downAt = 0L
            var moved = false
            var talkingGesture = false

            val beginTalk = Runnable {
                if (!moved) {
                    talkingGesture = true
                    bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    pttClient.beginTalk()
                }
            }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        downAt = SystemClock.uptimeMillis()
                        moved = false
                        talkingGesture = false
                        mainHandler.postDelayed(beginTalk, 220)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (!moved && (abs(dx) > dp(10) || abs(dy) > dp(10))) {
                            moved = true
                            mainHandler.removeCallbacks(beginTalk)
                            if (talkingGesture) {
                                pttClient.endTalk()
                                talkingGesture = false
                            }
                            closeMenu()
                        }
                        if (moved) {
                            params.x = initialX + dx.toInt()
                            params.y = initialY + dy.toInt()
                            windowManager.updateViewLayout(bubble, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(beginTalk)
                        if (talkingGesture) {
                            pttClient.endTalk()
                            talkingGesture = false
                        } else if (!moved && event.actionMasked == MotionEvent.ACTION_UP && SystemClock.uptimeMillis() - downAt < 420) {
                            toggleMenu()
                        }
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(bubble, params)
    }

    private fun setMode(busy: Boolean, unavailable: Boolean) {
        busyMode = busy
        unavailableMode = unavailable
        if (!::bubble.isInitialized) return
        val size = when {
            busy -> dp(58)
            unavailable -> dp(60)
            else -> dp(64)
        }
        params.width = size
        params.height = size
        applyVisualState()
        runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    private fun applyVisualState() {
        val ring = when (pttState) {
            PttClient.State.TALKING -> BLUE
            PttClient.State.BUSY -> MUTED
            PttClient.State.OFFLINE -> RED
            PttClient.State.CONNECTING -> ORANGE
            PttClient.State.READY -> when {
                unavailableMode -> MUTED
                busyMode -> AMBER
                else -> GREEN
            }
        }
        bubble.background = bubbleBackground(NAVY, ring)
        bubble.alpha = if (pttState == PttClient.State.OFFLINE) .88f else 1f
    }

    private fun toggleMenu() {
        if (menu != null) closeMenu() else openMenu()
    }

    private fun openMenu() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(0xF2152B3B.toInt(), 20f, 0x55FFFFFF)
            elevation = dp(16).toFloat()
        }
        panel.addView(menuTitle("QUICK ACTIONS"))
        panel.addView(menuItem("●  Go Available", GREEN) { changeDriverStatus("available") })
        panel.addView(menuItem("●  Go Unavailable", AMBER) { changeDriverStatus("unavailable") })
        panel.addView(menuItem("Current Trip", Color.WHITE) { openApp() })
        panel.addView(menuItem("Open App", Color.WHITE) { openApp() })
        panel.addView(menuItem("Settings", MUTED) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            closeMenu()
        })
        panel.addView(menuItem("End Shift", RED) { changeDriverStatus("offline") })

        val menuWidth = dp(190)
        val yBelow = params.y + params.height + dp(8)
        val displayHeight = resources.displayMetrics.heightPixels
        val y = if (yBelow + dp(310) < displayHeight) yBelow else (params.y - dp(310)).coerceAtLeast(dp(12))
        val x = params.x.coerceIn(dp(8), (resources.displayMetrics.widthPixels - menuWidth - dp(8)).coerceAtLeast(dp(8)))
        val lp = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        menu = panel
        menuParams = lp
        windowManager.addView(panel, lp)
    }

    private fun closeMenu() {
        menu?.let { runCatching { windowManager.removeView(it) } }
        menu = null
        menuParams = null
    }

    private fun changeDriverStatus(status: String) {
        closeMenu()
        Thread {
            runCatching { DriverApi.postStatus(SessionStore(this), status) }
                .onSuccess {
                    mainHandler.post {
                        when (status) {
                            "available" -> {
                                setMode(busy = false, unavailable = false)
                                startForegroundService(Intent(this, DriverLocationService::class.java))
                            }
                            "unavailable" -> {
                                setMode(busy = false, unavailable = true)
                                stopService(Intent(this, DriverLocationService::class.java))
                            }
                            "offline" -> {
                                stopService(Intent(this, DriverLocationService::class.java))
                                stopSelf()
                            }
                        }
                    }
                }
        }.start()
    }

    private fun openApp() {
        closeMenu()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun menuTitle(label: String): TextView = TextView(this).apply {
        text = label
        setTextColor(0xFF9FB2BE.toInt())
        textSize = 9f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        letterSpacing = .12f
        setPadding(dp(10), dp(8), dp(10), dp(7))
    }

    private fun menuItem(label: String, color: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(color)
        textSize = 12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(Color.TRANSPARENT, 12f)
        setOnClickListener { action() }
    }

    override fun onDestroy() {
        closeMenu()
        if (::pttClient.isInitialized) pttClient.close()
        if (::bubble.isInitialized) runCatching { windowManager.removeView(bubble) }
        super.onDestroy()
    }

    private fun bubbleBackground(color: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(3), stroke)
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null
}
