package com.snapnest.dispatch

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import kotlin.math.max

class MainActivity : Activity() {
    companion object {
        private const val NAVY = 0xFF173F5F.toInt()
        private const val NAVY_DARK = 0xFF0D2A40.toInt()
        private const val INK = 0xFF071A27.toInt()
        private const val PANEL = 0xE61A3344.toInt()
        private const val PANEL_SOFT = 0xC91C3648.toInt()
        private const val ORANGE = 0xFFEF6A00.toInt()
        private const val MUTED = 0xFFA7B6C0.toInt()
        private const val GREEN = 0xFF2DD67B.toInt()
        private const val AMBER = 0xFFF2A321.toInt()
        private const val RED = 0xFFEF5A67.toInt()
        private const val OFFWHITE = 0xFFF7F5F0.toInt()
        private const val GLASS_STROKE = 0x55FFFFFF
    }

    private lateinit var sessionStore: SessionStore
    private lateinit var statusText: TextView
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private var pendingDutyStatus: String? = null

    private var driverNameText: TextView? = null
    private var vehicleText: TextView? = null
    private var statusChip: TextView? = null
    private var headlineText: TextView? = null
    private var sublineText: TextView? = null
    private var gpsChip: TextView? = null
    private var networkChip: TextView? = null
    private var radioChip: TextView? = null
    private var jobContainer: LinearLayout? = null
    private var primaryButton: Button? = null
    private var secondaryButton: Button? = null
    private var slideAction: FrameLayout? = null
    private var mapView: WebView? = null
    private var mapReady = false
    private var currentSnapshot: DriverApi.DriverSnapshot? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pollInFlight = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (sessionStore.load() == null || pollInFlight) {
                handler.postDelayed(this, 5000)
                return
            }
            pollInFlight = true
            Thread {
                runCatching { DriverApi.driverSnapshot(sessionStore) }
                    .onSuccess { snapshot ->
                        runOnUiThread {
                            pollInFlight = false
                            networkChip?.let { styleConnectionChip(it, "ONLINE", GREEN) }
                            applySnapshot(snapshot)
                            handler.postDelayed(this, 5000)
                        }
                    }
                    .onFailure { error ->
                        runOnUiThread {
                            pollInFlight = false
                            networkChip?.let { styleConnectionChip(it, "OFFLINE", RED) }
                            statusText.text = error.message ?: "Could not refresh dispatch status."
                            if (sessionStore.load() == null) showLogin()
                            else handler.postDelayed(this, 5000)
                        }
                    }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NAVY_DARK
        window.navigationBarColor = NAVY_DARK
        sessionStore = SessionStore(this)
        if (sessionStore.load() == null) showLogin() else showDriverHome()
    }

    private fun showLogin() {
        stopPolling()
        mapView?.destroy()
        mapView = null
        mapReady = false

        val root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF061722.toInt(), NAVY_DARK, 0xFF0A3956.toInt())
            )
        }

        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(0x552B89FF, Color.TRANSPARENT)
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(230).toFloat()
            }
            alpha = .65f
        }, FrameLayout.LayoutParams(dp(360), dp(360)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = -dp(100)
            rightMargin = -dp(120)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(56), dp(22), dp(28))
        }

        val logo = SnapNestLogoView(this)
        content.addView(logo, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(8) })
        content.addView(TextView(this).apply {
            text = "SnapNest Dispatch"
            setTextColor(Color.WHITE)
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "DRIVER"
            setTextColor(ORANGE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .18f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(52))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(26), dp(24), dp(24))
            background = glass(PANEL_SOFT, 28f)
            elevation = dp(14).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "WELCOME BACK"
            setTextColor(0xFFD8E3E9.toInt())
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .14f
        })
        card.addView(TextView(this).apply {
            text = "Start your shift"
            setTextColor(Color.WHITE)
            textSize = 29f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        })
        card.addView(TextView(this).apply {
            text = "Sign in once. Stay connected while you're on duty."
            setTextColor(MUTED)
            textSize = 13f
            setPadding(0, 0, 0, dp(24))
        })

        emailInput = glassInput("Driver email", false)
        passwordInput = glassInput("Password", true)
        card.addView(emailInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(12) })
        card.addView(passwordInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(18) })
        card.addView(actionButton("SIGN IN", true).apply { setOnClickListener { signIn() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        statusText = TextView(this).apply {
            text = "Connected to SnapNest Dispatch."
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }
        card.addView(statusText)
        content.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        content.addView(TextView(this).apply {
            text = "Powered by SnapNest Digital Solutions"
            setTextColor(0xFF7F96A4.toInt())
            textSize = 9f
            gravity = Gravity.CENTER
            setPadding(0, dp(38), 0, 0)
        })

        scroll.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
    }

    private fun showDriverHome() {
        val session = sessionStore.load() ?: return showLogin()
        val root = FrameLayout(this).apply { setBackgroundColor(INK) }

        mapView = WebView(this).apply {
            setBackgroundColor(NAVY_DARK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    mapReady = true
                    pushMapLocation()
                    pushMapJob(currentSnapshot)
                }
            }
            loadUrl("file:///android_asset/driver_map.html")
        }
        root.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xD900111A.toInt(), 0x4400111A, Color.TRANSPARENT)
            )
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(190)).apply { gravity = Gravity.TOP })

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(14), dp(10))
            background = glass(0xD9152B3B.toInt(), 22f)
            elevation = dp(8).toFloat()
        }
        header.addView(SnapNestLogoView(this), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        driverNameText = TextView(this).apply {
            text = session.driverName.ifBlank { "Driver" }
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        vehicleText = TextView(this).apply {
            text = session.vehicle.ifBlank { "Checking vehicle…" }
            setTextColor(MUTED)
            textSize = 10f
        }
        identity.addView(driverNameText)
        identity.addView(vehicleText)
        header.addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val live = TextView(this).apply {
            text = "● LIVE"
            setTextColor(GREEN)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(live)
        root.addView(header, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(62)).apply {
            gravity = Gravity.TOP
            leftMargin = dp(14)
            rightMargin = dp(14)
            topMargin = dp(20)
        })

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = glass(PANEL, 28f)
            elevation = dp(18).toFloat()
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusChip = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(11), dp(7), dp(11), dp(7))
        }
        topRow.addView(statusChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        topRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        networkChip = connectionChip("CONNECTING")
        topRow.addView(networkChip, LinearLayout.LayoutParams(dp(76), dp(30)))
        sheet.addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        headlineText = TextView(this).apply {
            text = "Checking duty status…"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }
        sheet.addView(headlineText)
        sublineText = TextView(this).apply {
            text = "Connecting to dispatch."
            setTextColor(MUTED)
            textSize = 13f
            setPadding(0, dp(3), 0, dp(14))
        }
        sheet.addView(sublineText)

        jobContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = glass(0xAA0C2330.toInt(), 20f)
        }
        sheet.addView(jobContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })

        primaryButton = actionButton("CHECKING…", true)
        secondaryButton = actionButton("END SHIFT", false)
        sheet.addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { bottomMargin = dp(9) })
        sheet.addView(secondaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        slideAction = buildSlideAction("SLIDE TO START") { }
        slideAction?.visibility = View.GONE
        sheet.addView(slideAction, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(2) })

        val connectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        gpsChip = connectionChip("GPS…")
        radioChip = connectionChip("PTT")
        connectionRow.addView(gpsChip, LinearLayout.LayoutParams(0, dp(30), 1f).apply { rightMargin = dp(7) })
        connectionRow.addView(radioChip, LinearLayout.LayoutParams(0, dp(30), 1f))
        sheet.addView(connectionRow)

        statusText = TextView(this).apply {
            text = ""
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        sheet.addView(statusText)

        root.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(14)
            rightMargin = dp(14)
            bottomMargin = dp(14)
        })

        setContentView(root)
        applySnapshot(currentSnapshot ?: DriverApi.DriverSnapshot(session.driverName.ifBlank { "Driver" }, session.vehicle, "unknown", null, null))
        startPolling()
    }

    private fun signIn() {
        val base = BuildConfig.DISPATCH_API_URL.trimEnd('/')
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (email.isBlank() || password.isBlank()) {
            statusText.text = "Email and password are required."
            return
        }
        statusText.text = "Signing in securely…"
        Thread {
            runCatching { DriverApi.login(base, email, password) }
                .onSuccess { session ->
                    sessionStore.save(session)
                    runOnUiThread {
                        passwordInput.text.clear()
                        showDriverHome()
                        statusText.text = "Signed in. Setting you available…"
                        enableDuty("available")
                    }
                }
                .onFailure { error -> runOnUiThread { statusText.text = error.message ?: "Sign-in failed." } }
        }.start()
    }

    private fun applySnapshot(snapshot: DriverApi.DriverSnapshot) {
        currentSnapshot = snapshot
        driverNameText?.text = snapshot.driverName
        vehicleText?.text = snapshot.vehicle.ifBlank { "Driver vehicle" }

        sessionStore.load()?.let { session ->
            if ((session.driverName != snapshot.driverName || session.vehicle != snapshot.vehicle) && snapshot.driverName.isNotBlank()) {
                sessionStore.save(session.copy(driverName = snapshot.driverName, vehicle = snapshot.vehicle))
            }
        }

        val chip = statusChip ?: return
        when (snapshot.driverStatus) {
            "available" -> styleStatusChip(chip, "AVAILABLE", GREEN)
            "offered" -> styleStatusChip(chip, "NEW JOB", ORANGE)
            "busy" -> styleStatusChip(chip, "ON JOB", AMBER)
            "unavailable" -> styleStatusChip(chip, "UNAVAILABLE", 0xFF94A3B8.toInt())
            "offline" -> styleStatusChip(chip, "OFF DUTY", 0xFF94A3B8.toInt())
            else -> styleStatusChip(chip, "SYNCING", 0xFF94A3B8.toInt())
        }

        val offer = snapshot.offer
        val trip = snapshot.trip
        when {
            offer != null -> renderOffer(offer)
            trip != null -> renderTrip(trip)
            snapshot.driverStatus == "available" -> renderWaiting()
            snapshot.driverStatus == "unavailable" || snapshot.driverStatus == "offline" -> renderOffDuty(snapshot.driverStatus)
            else -> renderWaiting()
        }
        ensureDutyServicesForStatus(snapshot.driverStatus)
        pushMapLocation()
        pushMapJob(snapshot)
    }

    private fun renderWaiting() {
        headlineText?.text = "You're online"
        sublineText?.text = "Waiting for your next dispatch."
        fillJobCard(
            "READY FOR WORK",
            "Dispatch is watching your live position while you're available.",
            "New trips will appear here and in your notification tray."
        )
        slideAction?.visibility = View.GONE
        primaryButton?.apply {
            visibility = View.VISIBLE
            text = "GO UNAVAILABLE"
            setOnClickListener { setStatus("unavailable") }
        }
        secondaryButton?.apply {
            visibility = View.VISIBLE
            text = "END SHIFT"
            setOnClickListener { setStatus("offline") }
        }
    }

    private fun renderOffDuty(status: String) {
        headlineText?.text = if (status == "unavailable") "You're unavailable" else "Shift ended"
        sublineText?.text = if (status == "unavailable") "You're still connected to dispatch, but not receiving jobs." else "Location sharing and driver radio are off."
        fillJobCard(
            if (status == "unavailable") "PAUSED" else "OFF DUTY",
            if (status == "unavailable") "Your GPS is paused until you go available again." else "No new trips will be assigned while your shift is ended.",
            "Tap Go Available when you're ready to work."
        )
        slideAction?.visibility = View.GONE
        primaryButton?.apply {
            visibility = View.VISIBLE
            text = "GO AVAILABLE"
            setOnClickListener { enableDuty("available") }
        }
        secondaryButton?.visibility = if (status == "unavailable") View.VISIBLE else View.GONE
        secondaryButton?.apply {
            text = "END SHIFT"
            setOnClickListener { setStatus("offline") }
        }
    }

    private fun renderOffer(offer: DriverApi.ActiveOffer) {
        headlineText?.text = "New trip"
        sublineText?.text = "Review the trip before accepting."
        jobContainer?.removeAllViews()
        addJobKicker("NEW TRIP")
        addJobRoute("PICKUP", offer.pickup, GREEN)
        addJobRoute("DESTINATION", offer.destination, ORANGE)
        addJobMeta("${offer.passengerName} · ${offer.passengers} passenger${if (offer.passengers == 1) "" else "s"}")
        if (offer.notes.isNotBlank()) addJobMeta(offer.notes)

        slideAction?.visibility = View.GONE
        primaryButton?.apply {
            visibility = View.VISIBLE
            text = "ACCEPT TRIP"
            setOnClickListener { respondToOffer(offer, true) }
        }
        secondaryButton?.apply {
            visibility = View.VISIBLE
            text = "DECLINE"
            setOnClickListener { respondToOffer(offer, false) }
        }
    }

    private fun renderTrip(trip: DriverApi.ActiveTrip) {
        val inProgress = trip.status == "in_progress"
        headlineText?.text = if (inProgress) "Trip in progress" else "Heading to pickup"
        sublineText?.text = if (inProgress) "Navigation and PTT stay available while you drive." else "Navigate to the pickup, then start the trip."
        jobContainer?.removeAllViews()
        addJobKicker(if (inProgress) "ON TRIP" else "ASSIGNED")
        addJobRoute("PICKUP", trip.pickup, GREEN)
        addJobRoute("DESTINATION", trip.destination, ORANGE)
        addJobMeta("${trip.passengerName} · ${trip.passengers} passenger${if (trip.passengers == 1) "" else "s"}")
        if (trip.notes.isNotBlank()) addJobMeta(trip.notes)

        primaryButton?.apply {
            visibility = View.VISIBLE
            text = if (inProgress) "NAVIGATE TO DESTINATION" else "NAVIGATE TO PICKUP"
            setOnClickListener {
                if (inProgress) navigate(trip.destinationLat, trip.destinationLng, trip.destination)
                else navigate(trip.pickupLat, trip.pickupLng, trip.pickup)
            }
        }
        secondaryButton?.visibility = View.GONE
        slideAction = replaceSlideAction(if (inProgress) "SLIDE TO COMPLETE" else "SLIDE TO START") {
            updateTrip(trip, if (inProgress) "complete" else "start")
        }
    }

    private fun replaceSlideAction(label: String, action: () -> Unit): FrameLayout? {
        val current = slideAction ?: return null
        val parent = current.parent as? LinearLayout ?: return current
        val index = parent.indexOfChild(current)
        parent.removeView(current)
        val next = buildSlideAction(label, action)
        parent.addView(next, index, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(2) })
        next.visibility = View.VISIBLE
        return next.also { slideAction = it }
    }

    private fun respondToOffer(offer: DriverApi.ActiveOffer, accept: Boolean) {
        statusText.text = if (accept) "Accepting trip…" else "Passing trip to the next driver…"
        primaryButton?.isEnabled = false
        secondaryButton?.isEnabled = false
        Thread {
            runCatching { DriverApi.respondToOffer(sessionStore, offer.bookingId, accept) }
                .onSuccess {
                    runOnUiThread {
                        getSystemService(NotificationManager::class.java).cancel(DriverLocationService.OFFER_ID)
                        startForegroundService(Intent(this, OverlayService::class.java).setAction(if (accept) OverlayService.ACTION_BUSY else OverlayService.ACTION_AVAILABLE))
                        statusText.text = if (accept) "Trip accepted." else "Trip declined."
                        primaryButton?.isEnabled = true
                        secondaryButton?.isEnabled = true
                        refreshNow()
                    }
                }
                .onFailure { error -> runOnUiThread {
                    statusText.text = error.message ?: "Could not update trip."
                    primaryButton?.isEnabled = true
                    secondaryButton?.isEnabled = true
                    refreshNow()
                } }
        }.start()
    }

    private fun updateTrip(trip: DriverApi.ActiveTrip, action: String) {
        statusText.text = if (action == "start") "Starting trip…" else "Completing trip…"
        primaryButton?.isEnabled = false
        Thread {
            runCatching { DriverApi.updateTrip(sessionStore, trip.bookingId, action) }
                .onSuccess {
                    runOnUiThread {
                        if (action == "complete") {
                            getSystemService(NotificationManager::class.java).cancel(DriverLocationService.ACCEPTED_JOB_ID)
                            startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_AVAILABLE))
                            statusText.text = "Trip complete. You're available again."
                        } else {
                            statusText.text = "Trip started."
                        }
                        primaryButton?.isEnabled = true
                        refreshNow()
                    }
                }
                .onFailure { error -> runOnUiThread {
                    statusText.text = error.message ?: "Could not update trip."
                    primaryButton?.isEnabled = true
                    refreshNow()
                } }
        }.start()
    }

    private fun setStatus(status: String) {
        if (sessionStore.load() == null) {
            statusText.text = "Driver session expired. Sign in again."
            return
        }
        statusText.text = when (status) {
            "available" -> "Going available…"
            "offline" -> "Ending shift…"
            else -> "Updating status…"
        }
        Thread {
            runCatching { DriverApi.postStatus(sessionStore, status) }
                .onSuccess {
                    runOnUiThread {
                        when (status) {
                            "available" -> {
                                startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_AVAILABLE))
                                startForegroundService(Intent(this, DriverLocationService::class.java))
                            }
                            "unavailable" -> {
                                stopService(Intent(this, DriverLocationService::class.java))
                                startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_UNAVAILABLE))
                            }
                            "offline" -> stopDutyServices()
                        }
                        statusText.text = when (status) {
                            "available" -> "You're available."
                            "unavailable" -> "You're unavailable."
                            else -> "Shift ended."
                        }
                        refreshNow()
                    }
                }
                .onFailure { error -> runOnUiThread {
                    statusText.text = error.message ?: "Status update failed."
                    if (sessionStore.load() == null) {
                        stopDutyServices()
                        showLogin()
                    }
                } }
        }.start()
    }

    private fun enableDuty(status: String) {
        if (sessionStore.load() == null) {
            pendingDutyStatus = null
            statusText.text = "Sign in before starting duty."
            return
        }
        pendingDutyStatus = status
        if (!Settings.canDrawOverlays(this)) {
            statusText.text = "Allow Display over other apps, then return to SnapNest Dispatch."
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.isNotEmpty()) {
            statusText.text = "Allow location, microphone and notifications so duty can start."
            requestPermissions(permissions.toTypedArray(), 2401)
            return
        }
        pendingDutyStatus = null
        startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_AVAILABLE))
        startForegroundService(Intent(this, DriverLocationService::class.java))
        setStatus(status)
    }

    private fun ensureDutyServicesForStatus(status: String) {
        if (!Settings.canDrawOverlays(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        when (status) {
            "available", "offered", "busy" -> {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
                startForegroundService(Intent(this, OverlayService::class.java).setAction(if (status == "busy") OverlayService.ACTION_BUSY else OverlayService.ACTION_AVAILABLE))
                startForegroundService(Intent(this, DriverLocationService::class.java))
                styleConnectionChip(radioChip, "PTT READY", ORANGE)
            }
            "unavailable" -> {
                stopService(Intent(this, DriverLocationService::class.java))
                startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_UNAVAILABLE))
                styleConnectionChip(radioChip, "PTT READY", 0xFF94A3B8.toInt())
            }
        }
    }

    private fun stopDutyServices() {
        stopService(Intent(this, DriverLocationService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        getSystemService(NotificationManager::class.java).cancel(DriverLocationService.OFFER_ID)
        getSystemService(NotificationManager::class.java).cancel(DriverLocationService.ACCEPTED_JOB_ID)
        styleConnectionChip(radioChip, "PTT OFF", 0xFF64748B.toInt())
    }

    private fun navigate(lat: Double?, lng: Double?, label: String) {
        val target = if (lat != null && lng != null) "$lat,$lng" else label
        val googleIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(target)}")).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(googleIntent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(target)}")))
        }
    }

    private fun fillJobCard(kicker: String, title: String, detail: String) {
        jobContainer?.removeAllViews()
        addJobKicker(kicker)
        jobContainer?.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(5))
        })
        addJobMeta(detail)
    }

    private fun addJobKicker(textValue: String) {
        jobContainer?.addView(TextView(this).apply {
            text = textValue
            setTextColor(ORANGE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
        })
    }

    private fun addJobRoute(label: String, value: String, dotColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(10), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = "●"
            setTextColor(dotColor)
            textSize = 14f
            gravity = Gravity.TOP
            setPadding(0, dp(1), dp(10), 0)
        })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = label
            setTextColor(MUTED)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .1f
        })
        copy.addView(TextView(this).apply {
            text = value.ifBlank { "Not provided" }
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        jobContainer?.addView(row)
    }

    private fun addJobMeta(value: String) {
        jobContainer?.addView(TextView(this).apply {
            text = value
            setTextColor(MUTED)
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun pushMapLocation() {
        val web = mapView ?: return
        if (!mapReady || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            styleConnectionChip(gpsChip, "GPS WAIT", 0xFF64748B.toInt())
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locations = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        val location: Location? = locations.maxByOrNull { it.time }
        if (location == null) {
            styleConnectionChip(gpsChip, "GPS WAIT", AMBER)
            return
        }
        styleConnectionChip(gpsChip, "GPS LIVE", GREEN)
        web.evaluateJavascript("window.snapnestSetDriverLocation(${location.latitude},${location.longitude})", null)
    }

    private fun pushMapJob(snapshot: DriverApi.DriverSnapshot?) {
        val web = mapView ?: return
        if (!mapReady || snapshot == null) return
        val offer = snapshot.offer
        val trip = snapshot.trip
        val pickupLat = offer?.pickupLat ?: trip?.pickupLat
        val pickupLng = offer?.pickupLng ?: trip?.pickupLng
        val destinationLat = offer?.destinationLat ?: trip?.destinationLat
        val destinationLng = offer?.destinationLng ?: trip?.destinationLng
        if (offer == null && trip == null) {
            web.evaluateJavascript("window.snapnestClearJob()", null)
            return
        }
        fun n(value: Double?) = value?.toString() ?: "NaN"
        val label = when {
            offer != null -> "NEW TRIP"
            trip?.status == "in_progress" -> "TRIP IN PROGRESS"
            else -> "TO PICKUP"
        }
        web.evaluateJavascript("window.snapnestSetJob(${n(pickupLat)},${n(pickupLng)},${n(destinationLat)},${n(destinationLng)},'$label')", null)
    }

    private fun buildSlideAction(label: String, action: () -> Unit): FrameLayout {
        val frame = FrameLayout(this).apply {
            background = glass(0xE6F2F4F5.toInt(), 18f, 0x77FFFFFF)
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(0xFF1C2A33.toInt())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = .04f
        }
        frame.addView(labelView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val handle = TextView(this).apply {
            text = "›"
            setTextColor(Color.WHITE)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(ORANGE, 16f)
            elevation = dp(5).toFloat()
        }
        frame.addView(handle, FrameLayout.LayoutParams(dp(46), dp(46)).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(5)
        })

        var downX = 0f
        var startTranslation = 0f
        frame.setOnTouchListener { _, event ->
            val maxTravel = max(0f, frame.width - dp(56).toFloat())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    startTranslation = handle.translationX
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handle.translationX = (startTranslation + event.rawX - downX).coerceIn(0f, maxTravel)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val complete = maxTravel > 0f && handle.translationX >= maxTravel * .72f
                    if (complete) {
                        handle.animate().translationX(maxTravel).setDuration(120).withEndAction {
                            action()
                            handle.translationX = 0f
                        }.start()
                    } else {
                        handle.animate().translationX(0f).setDuration(160).start()
                    }
                    true
                }
                else -> false
            }
        }
        return frame
    }

    private fun startPolling() {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        handler.removeCallbacks(pollRunnable)
        pollInFlight = false
    }

    private fun refreshNow() {
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (!::sessionStore.isInitialized) return
        val pending = pendingDutyStatus
        if (pending != null && Settings.canDrawOverlays(this)) {
            enableDuty(pending)
            return
        }
        if (sessionStore.load() != null && driverNameText != null) startPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroy() {
        stopPolling()
        mapView?.destroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2401 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            enableDuty(pendingDutyStatus ?: "available")
        } else if (requestCode == 2401) {
            pendingDutyStatus = null
            statusText.text = "Location, microphone and notifications are required while on duty."
        }
    }

    private fun glassInput(hintValue: String, password: Boolean): EditText = EditText(this).apply {
        hint = hintValue
        setHintTextColor(0xFFB9C6CD.toInt())
        setTextColor(Color.WHITE)
        textSize = 14f
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        setSingleLine(true)
        setPadding(dp(18), 0, dp(18), 0)
        background = glass(0x663A5261, 17f)
    }

    private fun actionButton(label: String, primary: Boolean): Button = Button(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        letterSpacing = .04f
        setTextColor(if (primary) Color.WHITE else OFFWHITE)
        background = if (primary) rounded(ORANGE, 17f) else glass(0xAA102737.toInt(), 17f)
        stateListAnimator = null
        elevation = if (primary) dp(4).toFloat() else 0f
    }

    private fun connectionChip(label: String): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 8f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(MUTED)
        text = label
        background = rounded(0x990B1F2B.toInt(), 12f)
    }

    private fun styleConnectionChip(view: TextView?, label: String, color: Int) {
        view ?: return
        view.text = label
        view.setTextColor(color)
        view.background = rounded(0x990B1F2B.toInt(), 12f, color, 1)
    }

    private fun styleStatusChip(view: TextView, label: String, color: Int) {
        view.text = "●  $label"
        view.setTextColor(color)
        view.background = rounded(0xAA091C26.toInt(), 14f, color, 1)
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (strokeColor != null && strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
    }

    private fun glass(color: Int, radiusDp: Float, strokeColor: Int = GLASS_STROKE): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
