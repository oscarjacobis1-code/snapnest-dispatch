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
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*

class MainActivity : Activity() {
    companion object {
        private const val NAVY = 0xFF173F5F.toInt()
        private const val NAVY_DARK = 0xFF0D2A40.toInt()
        private const val INK = 0xFF071A27.toInt()
        private const val PANEL = 0xFF102F45.toInt()
        private const val ORANGE = 0xFFEF6A00.toInt()
        private const val MUTED = 0xFF94A8B5.toInt()
        private const val GREEN = 0xFF22C55E.toInt()
        private const val AMBER = 0xFFF59E0B.toInt()
        private const val RED = 0xFFEF4444.toInt()
        private const val OFFWHITE = 0xFFF7F5F0.toInt()
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(30), dp(28), dp(30))
            setBackgroundColor(INK)
        }

        root.addView(TextView(this).apply {
            text = "SNAPNEST"
            setTextColor(Color.WHITE)
            textSize = 29f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .04f
        })
        root.addView(TextView(this).apply {
            text = "DISPATCH DRIVER"
            setTextColor(ORANGE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .16f
            setPadding(0, dp(5), 0, dp(34))
        })
        root.addView(TextView(this).apply {
            text = "Ready when you are."
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Sign in once. SnapNest keeps your driver session secured on this device."
            setTextColor(MUTED)
            textSize = 15f
            setPadding(0, dp(8), 0, dp(28))
        })

        emailInput = EditText(this).apply {
            hint = "Driver email"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(PANEL, 16f, 0xFF31566D.toInt(), 1)
        }
        root.addView(emailInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(12) })

        passwordInput = EditText(this).apply {
            hint = "Password"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(PANEL, 16f, 0xFF31566D.toInt(), 1)
        }
        root.addView(passwordInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(16) })

        root.addView(actionButton("SIGN IN", true).apply { setOnClickListener { signIn() } }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))

        statusText = TextView(this).apply {
            text = "Connected to the SnapNest Dispatch live service."
            setTextColor(MUTED)
            textSize = 13f
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(statusText)
        setContentView(root)
    }

    private fun showDriverHome() {
        val session = sessionStore.load() ?: return showLogin()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(INK)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(17), dp(20), dp(15))
        }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(this).apply {
            text = "SNAPNEST"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .04f
        })
        brand.addView(TextView(this).apply {
            text = "DISPATCH"
            setTextColor(ORANGE)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .18f
        })
        header.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
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
            textSize = 11f
        }
        identity.addView(driverNameText)
        identity.addView(vehicleText)
        header.addView(identity)
        root.addView(header)

        val mapFrame = FrameLayout(this).apply {
            background = rounded(NAVY_DARK, 22f)
            clipToOutline = true
        }
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
        mapFrame.addView(mapView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(mapFrame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(285)).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(12)
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            background = rounded(PANEL, 26f)
        }

        statusChip = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        sheet.addView(statusChip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(13) })

        headlineText = TextView(this).apply {
            text = "Checking duty status…"
            setTextColor(Color.WHITE)
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
        }
        sheet.addView(headlineText)
        sublineText = TextView(this).apply {
            text = "Connecting to dispatch."
            setTextColor(MUTED)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        }
        sheet.addView(sublineText)

        val connections = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        gpsChip = connectionChip("GPS…")
        radioChip = connectionChip("PTT BUBBLE")
        networkChip = connectionChip("CONNECTING")
        connections.addView(gpsChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(7) })
        connections.addView(radioChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(7) })
        connections.addView(networkChip, LinearLayout.LayoutParams(0, dp(36), 1f))
        sheet.addView(connections, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)).apply { bottomMargin = dp(18) })

        jobContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(NAVY_DARK, 18f, 0xFF2D5268.toInt(), 1)
        }
        sheet.addView(jobContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) })

        primaryButton = actionButton("CHECKING…", true)
        secondaryButton = actionButton("END SHIFT", false)
        sheet.addView(primaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(10) })
        sheet.addView(secondaryButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)))

        statusText = TextView(this).apply {
            text = ""
            setTextColor(MUTED)
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        }
        sheet.addView(statusText)

        scroll.addView(sheet, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
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
            "unavailable" -> styleStatusChip(chip, "UNAVAILABLE", 0xFF64748B.toInt())
            "offline" -> styleStatusChip(chip, "OFF DUTY", 0xFF64748B.toInt())
            else -> styleStatusChip(chip, "SYNCING", 0xFF64748B.toInt())
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
        headlineText?.text = "You're available"
        sublineText?.text = "Waiting for the next dispatch. Keep the PTT bubble nearby."
        fillJobCard(
            "WAITING FOR DISPATCH",
            "Your live location is being shared while you are on duty.",
            "SnapNest will surface the next trip here and in your notifications."
        )
        primaryButton?.apply {
            text = "GO UNAVAILABLE"
            setOnClickListener { setStatus("unavailable") }
        }
        secondaryButton?.apply {
            visibility = View.VISIBLE
            text = "END SHIFT"
            setOnClickListener {
                pendingDutyStatus = null
                setStatus("offline")
            }
        }
    }

    private fun renderOffDuty(status: String) {
        headlineText?.text = if (status == "unavailable") "You're unavailable" else "Shift ended"
        sublineText?.text = "Your GPS and driver radio are not active."
        fillJobCard("OFF DUTY", "No new trips will be assigned while you are offline.", "Tap Go Available when you're ready to work.")
        primaryButton?.apply {
            text = "GO AVAILABLE"
            setOnClickListener { enableDuty("available") }
        }
        secondaryButton?.visibility = View.GONE
    }

    private fun renderOffer(offer: DriverApi.ActiveOffer) {
        headlineText?.text = "New trip"
        sublineText?.text = "Review the pickup before accepting."
        jobContainer?.removeAllViews()
        addJobKicker("NEW TRIP")
        addJobRoute("PICKUP", offer.pickup)
        addJobRoute("DESTINATION", offer.destination)
        addJobMeta("${offer.passengerName} · ${offer.passengers} passenger${if (offer.passengers == 1) "" else "s"}")
        if (offer.notes.isNotBlank()) addJobMeta(offer.notes)

        primaryButton?.apply {
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
        sublineText?.text = if (inProgress) "Keep your attention on the road. PTT stays available over Maps." else "Navigate to the pickup, then start the trip."
        jobContainer?.removeAllViews()
        addJobKicker(if (inProgress) "ON TRIP" else "ASSIGNED")
        addJobRoute("PICKUP", trip.pickup)
        addJobRoute("DESTINATION", trip.destination)
        addJobMeta("${trip.passengerName} · ${trip.passengers} passenger${if (trip.passengers == 1) "" else "s"}")
        if (trip.notes.isNotBlank()) addJobMeta(trip.notes)

        primaryButton?.apply {
            text = if (inProgress) "NAVIGATE TO DESTINATION" else "NAVIGATE TO PICKUP"
            setOnClickListener {
                if (inProgress) navigate(trip.destinationLat, trip.destinationLng, trip.destination)
                else navigate(trip.pickupLat, trip.pickupLng, trip.pickup)
            }
        }
        secondaryButton?.apply {
            visibility = View.VISIBLE
            text = if (inProgress) "COMPLETE TRIP" else "START TRIP"
            setOnClickListener { updateTrip(trip, if (inProgress) "complete" else "start") }
        }
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
        secondaryButton?.isEnabled = false
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

    private fun setStatus(status: String) {
        if (sessionStore.load() == null) {
            statusText.text = "Driver session expired. Sign in again."
            return
        }
        statusText.text = if (status == "available") "Going available…" else if (status == "offline") "Ending shift…" else "Updating status…"
        Thread {
            runCatching { DriverApi.postStatus(sessionStore, status) }
                .onSuccess {
                    runOnUiThread {
                        when (status) {
                            "available" -> startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_AVAILABLE))
                            "unavailable", "offline" -> stopDutyServices()
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
        if (status !in setOf("available", "offered", "busy")) return
        if (!Settings.canDrawOverlays(this)) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        startForegroundService(Intent(this, OverlayService::class.java).setAction(if (status == "busy") OverlayService.ACTION_BUSY else OverlayService.ACTION_AVAILABLE))
        startForegroundService(Intent(this, DriverLocationService::class.java))
        styleConnectionChip(radioChip, "PTT READY", ORANGE)
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
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(9), 0, dp(6))
        })
        addJobMeta(detail)
    }

    private fun addJobKicker(textValue: String) {
        jobContainer?.addView(TextView(this).apply {
            text = textValue
            setTextColor(ORANGE)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .12f
        })
    }

    private fun addJobRoute(label: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(11), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(MUTED)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .1f
        })
        row.addView(TextView(this).apply {
            text = value.ifBlank { "Not provided" }
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        jobContainer?.addView(row)
    }

    private fun addJobMeta(value: String) {
        jobContainer?.addView(TextView(this).apply {
            text = value
            setTextColor(MUTED)
            textSize = 12f
            setPadding(0, dp(5), 0, 0)
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

    private fun actionButton(label: String, primary: Boolean): Button = Button(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else OFFWHITE)
        background = rounded(if (primary) ORANGE else NAVY_DARK, 17f, if (primary) ORANGE else 0xFF365C73.toInt(), 1)
        stateListAnimator = null
    }

    private fun connectionChip(label: String): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(MUTED)
        text = label
        background = rounded(NAVY_DARK, 12f)
    }

    private fun styleConnectionChip(view: TextView?, label: String, color: Int) {
        view ?: return
        view.text = label
        view.setTextColor(color)
        view.background = rounded(NAVY_DARK, 12f, color, 1)
    }

    private fun styleStatusChip(view: TextView, label: String, color: Int) {
        view.text = "●  $label"
        view.setTextColor(color)
        view.background = rounded(0xFF0B2333.toInt(), 14f, color, 1)
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null, strokeWidthDp: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (strokeColor != null && strokeWidthDp > 0) setStroke(dp(strokeWidthDp), strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
