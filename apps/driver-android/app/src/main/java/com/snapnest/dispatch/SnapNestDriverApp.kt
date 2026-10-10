package com.snapnest.dispatch

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.roundToInt

private val Ink = Color(0xFF071A27)
private val Navy = Color(0xFF0D2A40)
private val Navy2 = Color(0xFF173F5F)
private val Orange = Color(0xFFEF6A00)
private val White = Color(0xFFF8FAFB)
private val Muted = Color(0xFFA7B6C0)
private val Green = Color(0xFF2DD67B)
private val Amber = Color(0xFFF2A321)
private val Red = Color(0xFFEF5A67)
private val Panel = Color(0xE61A3344)
private val PanelSoft = Color(0xD6112836)

private enum class DriverTab { Home, Activity, Account }

@Composable
fun SnapNestDriverApp(activity: MainActivity) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orange,
            background = Ink,
            surface = Navy,
            onPrimary = Color.White,
            onBackground = White,
            onSurface = White
        )
    ) {
        val store = activity.sessionStore
        var signedIn by remember { mutableStateOf(store.load() != null) }
        var snapshot by remember { mutableStateOf<DriverApi.DriverSnapshot?>(null) }
        var tab by remember { mutableStateOf(DriverTab.Home) }
        var message by remember { mutableStateOf("") }
        var refreshKey by remember { mutableIntStateOf(0) }
        var history by remember { mutableStateOf<List<DriverApi.TripHistoryItem>>(emptyList()) }
        var supportTickets by remember { mutableStateOf<List<DriverApi.SupportTicket>>(emptyList()) }
        var historyLoading by remember { mutableStateOf(false) }
        var supportLoading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun refreshNow() { refreshKey += 1 }

        LaunchedEffect(signedIn, refreshKey) {
            if (!signedIn) return@LaunchedEffect
            while (signedIn && store.load() != null) {
                val result = withContext(Dispatchers.IO) { runCatching { DriverApi.driverSnapshot(store) } }
                result.onSuccess {
                    snapshot = it
                    activity.ensureDutyForStatus(it.driverStatus)
                    message = ""
                }.onFailure {
                    message = it.message ?: "Could not refresh dispatch."
                    if (store.load() == null) signedIn = false
                }
                delay(5_000)
            }
        }

        LaunchedEffect(tab, signedIn, refreshKey) {
            if (!signedIn) return@LaunchedEffect
            when (tab) {
                DriverTab.Activity -> {
                    historyLoading = true
                    val result = withContext(Dispatchers.IO) { runCatching { DriverApi.tripHistory(store, 40) } }
                    result.onSuccess { history = it }.onFailure { message = it.message ?: "Could not load activity." }
                    historyLoading = false
                }
                DriverTab.Account -> {
                    supportLoading = true
                    val result = withContext(Dispatchers.IO) { runCatching { DriverApi.supportTickets(store, 20) } }
                    result.onSuccess { supportTickets = it }.onFailure { message = it.message ?: "Could not load support tickets." }
                    supportLoading = false
                }
                else -> Unit
            }
        }

        if (!signedIn) {
            LoginScreen(
                activity = activity,
                message = message,
                onMessage = { message = it },
                onSignedIn = {
                    signedIn = true
                    tab = DriverTab.Home
                    activity.enableDuty("available")
                    refreshNow()
                }
            )
        } else {
            DriverShell(
                activity = activity,
                snapshot = snapshot,
                tab = tab,
                message = message,
                history = history,
                historyLoading = historyLoading,
                supportTickets = supportTickets,
                supportLoading = supportLoading,
                onTab = { tab = it },
                onMessage = { message = it },
                onRefresh = { refreshNow() },
                onLoggedOut = {
                    signedIn = false
                    snapshot = null
                    history = emptyList()
                    supportTickets = emptyList()
                    message = ""
                },
                scope = scope
            )
        }
    }
}

@Composable
private fun LoginScreen(
    activity: MainActivity,
    message: String,
    onMessage: (String) -> Unit,
    onSignedIn: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Ink)) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    loadUrl("file:///android_asset/login_backdrop.html")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x22071827), Color(0x55071827), Color(0xF5071827)))
            )
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SnapNestLogo(48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("SnapNest Dispatch", color = White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Driver", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(20.dp))

            Surface(
                color = Color(0xC7142B3A),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
                shadowElevation = 12.dp
            ) {
                Column(Modifier.padding(18.dp)) {
                    DriverTextField(value = email, onValueChange = { email = it }, label = "Email")
                    Spacer(Modifier.height(10.dp))
                    DriverTextField(value = password, onValueChange = { password = it }, label = "Password", password = true)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        text = if (loading) "Signing in…" else "Sign in",
                        enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                        onClick = {
                            loading = true
                            onMessage("")
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { DriverApi.login(BuildConfig.DISPATCH_API_URL.trimEnd('/'), email.trim(), password) }
                                }
                                result.onSuccess {
                                    activity.sessionStore.save(it)
                                    password = ""
                                    onSignedIn()
                                }.onFailure { onMessage(it.message ?: "Sign-in failed.") }
                                loading = false
                            }
                        }
                    )
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(message, color = Red, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Powered by SnapNest Digital Solutions",
                color = Muted.copy(alpha = .72f),
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun DriverShell(
    activity: MainActivity,
    snapshot: DriverApi.DriverSnapshot?,
    tab: DriverTab,
    message: String,
    history: List<DriverApi.TripHistoryItem>,
    historyLoading: Boolean,
    supportTickets: List<DriverApi.SupportTicket>,
    supportLoading: Boolean,
    onTab: (DriverTab) -> Unit,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoggedOut: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                DriverTab.Home -> HomeScreen(activity, snapshot, message, onMessage, onRefresh, scope)
                DriverTab.Activity -> ActivityScreen(history, historyLoading)
                DriverTab.Account -> AccountScreen(activity, snapshot, supportTickets, supportLoading, onMessage, onRefresh, onLoggedOut, scope)
            }
        }
        DriverBottomNav(tab, onTab)
    }
}

@Composable
private fun HomeScreen(
    activity: MainActivity,
    snapshot: DriverApi.DriverSnapshot?,
    message: String,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var cancelTarget by remember { mutableStateOf<Pair<DriverApi.ActiveTrip, Boolean>?>(null) }
    var actionBusy by remember { mutableStateOf(false) }

    fun runAction(workingText: String, action: () -> Unit) {
        if (actionBusy) return
        actionBusy = true
        onMessage(workingText)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(action) }
            result.onSuccess {
                onMessage("")
                onRefresh()
            }.onFailure { onMessage(it.message ?: "Action failed.") }
            actionBusy = false
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        DriverMap(activity, snapshot, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xA5071827), Color.Transparent, Color.Transparent))))

        DriverIdentity(
            name = snapshot?.driverName ?: activity.sessionStore.load()?.driverName.orEmpty().ifBlank { "Driver" },
            vehicle = snapshot?.vehicle ?: activity.sessionStore.load()?.vehicle.orEmpty(),
            status = snapshot?.driverStatus ?: "syncing",
            modifier = Modifier.align(Alignment.TopCenter).padding(14.dp)
        )

        DriverStateSheet(
            snapshot = snapshot,
            message = message,
            busy = actionBusy,
            modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
            onAvailable = {
                runAction("Going available…") {
                    DriverApi.postStatus(activity.sessionStore, "available")
                    activity.ensureDutyForStatus("available")
                }
            },
            onUnavailable = {
                runAction("Going unavailable…") {
                    DriverApi.postStatus(activity.sessionStore, "unavailable")
                    activity.ensureDutyForStatus("unavailable")
                }
            },
            onAccept = { offer -> runAction("Accepting trip…") { DriverApi.respondToOffer(activity.sessionStore, offer.bookingId, true) } },
            onDecline = { offer -> runAction("Passing trip…") { DriverApi.respondToOffer(activity.sessionStore, offer.bookingId, false) } },
            onNavigate = { lat, lng, label -> activity.navigate(lat, lng, label) },
            onArrive = { trip -> runAction("Marking arrived…") { DriverApi.updateTrip(activity.sessionStore, trip.bookingId, "arrive") } },
            onStart = { trip -> runAction("Starting trip…") { DriverApi.updateTrip(activity.sessionStore, trip.bookingId, "start") } },
            onComplete = { trip -> runAction("Completing trip…") { DriverApi.updateTrip(activity.sessionStore, trip.bookingId, "complete") } },
            onCall = { activity.contactPassenger(it, false) },
            onMessagePassenger = { activity.contactPassenger(it, true) },
            onCancel = { trip, noShow -> cancelTarget = trip to noShow }
        )
    }

    cancelTarget?.let { (trip, noShow) ->
        CancelTripDialog(
            noShow = noShow,
            onDismiss = { cancelTarget = null },
            onConfirm = { reason ->
                cancelTarget = null
                runAction(if (noShow) "Recording no-show…" else "Cancelling trip…") {
                    DriverApi.cancelTrip(activity.sessionStore, trip.bookingId, reason, if (noShow) "passenger_no_show" else "driver_cancelled", noShow)
                }
            }
        )
    }
}

@Composable
private fun DriverStateSheet(
    snapshot: DriverApi.DriverSnapshot?,
    message: String,
    busy: Boolean,
    modifier: Modifier,
    onAvailable: () -> Unit,
    onUnavailable: () -> Unit,
    onAccept: (DriverApi.ActiveOffer) -> Unit,
    onDecline: (DriverApi.ActiveOffer) -> Unit,
    onNavigate: (Double?, Double?, String) -> Unit,
    onArrive: (DriverApi.ActiveTrip) -> Unit,
    onStart: (DriverApi.ActiveTrip) -> Unit,
    onComplete: (DriverApi.ActiveTrip) -> Unit,
    onCall: (String) -> Unit,
    onMessagePassenger: (String) -> Unit,
    onCancel: (DriverApi.ActiveTrip, Boolean) -> Unit
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
        shadowElevation = 14.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            when {
                snapshot == null -> {
                    StateLabel("Connecting", Muted)
                    Spacer(Modifier.height(10.dp))
                    Text("Syncing with dispatch", color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                snapshot.offer != null -> OfferState(snapshot.offer, busy, onAccept, onDecline)
                snapshot.trip != null -> TripState(snapshot.trip, busy, onNavigate, onArrive, onStart, onComplete, onCall, onMessagePassenger, onCancel)
                snapshot.driverStatus == "available" -> {
                    StateLabel("Available", Green)
                    Spacer(Modifier.height(8.dp))
                    Text("Waiting for trip", color = White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton("Go unavailable", !busy, onUnavailable)
                }
                snapshot.driverStatus == "unavailable" || snapshot.driverStatus == "offline" -> {
                    StateLabel(if (snapshot.driverStatus == "offline") "Off duty" else "Unavailable", Muted)
                    Spacer(Modifier.height(8.dp))
                    Text("Not receiving trips", color = White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton("Go available", !busy, onAvailable)
                }
                else -> {
                    StateLabel("Syncing", Muted)
                    Spacer(Modifier.height(8.dp))
                    Text("Checking driver status", color = White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(message, color = if (message.contains("failed", true) || message.contains("could not", true)) Red else Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun OfferState(offer: DriverApi.ActiveOffer, busy: Boolean, onAccept: (DriverApi.ActiveOffer) -> Unit, onDecline: (DriverApi.ActiveOffer) -> Unit) {
    var remaining by remember(offer.bookingId, offer.expiresAt) { mutableLongStateOf(offer.expiresAt?.minus(System.currentTimeMillis()) ?: 0L) }
    LaunchedEffect(offer.bookingId, offer.expiresAt) {
        while (offer.expiresAt != null && remaining > 0) {
            remaining = (offer.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            delay(250)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StateLabel("New trip", Orange)
        Spacer(Modifier.weight(1f))
        if (offer.expiresAt != null) Text("${(remaining / 1000).coerceAtLeast(0)}s", color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(14.dp))
    RoutePair(offer.pickup, offer.destination)
    Spacer(Modifier.height(12.dp))
    Text("${offer.passengerName} · ${offer.passengers} passenger${if (offer.passengers == 1) "" else "s"}", color = Muted, fontSize = 12.sp)
    if (offer.notes.isNotBlank()) {
        Spacer(Modifier.height(5.dp))
        Text(offer.notes, color = Muted, fontSize = 11.sp)
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Accept trip", !busy, { onAccept(offer) })
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { onDecline(offer) }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Decline", color = Muted) }
}

@Composable
private fun TripState(
    trip: DriverApi.ActiveTrip,
    busy: Boolean,
    onNavigate: (Double?, Double?, String) -> Unit,
    onArrive: (DriverApi.ActiveTrip) -> Unit,
    onStart: (DriverApi.ActiveTrip) -> Unit,
    onComplete: (DriverApi.ActiveTrip) -> Unit,
    onCall: (String) -> Unit,
    onMessagePassenger: (String) -> Unit,
    onCancel: (DriverApi.ActiveTrip, Boolean) -> Unit
) {
    val arrived = trip.status == "arrived"
    val inProgress = trip.status == "in_progress"
    val waitSeconds = rememberWaitingSeconds(trip.arrivedAt, arrived)

    Row(verticalAlignment = Alignment.CenterVertically) {
        StateLabel(when { inProgress -> "On trip"; arrived -> "At pickup"; else -> "Pickup" }, when { inProgress -> Orange; arrived -> Green; else -> Amber })
        Spacer(Modifier.weight(1f))
        if (arrived) Text(formatTimer(waitSeconds), color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(13.dp))
    RoutePair(trip.pickup, trip.destination)
    Spacer(Modifier.height(10.dp))
    Text(trip.passengerName, color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    if (trip.notes.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(trip.notes, color = Muted, fontSize = 11.sp)
    }

    Spacer(Modifier.height(14.dp))
    if (!arrived) {
        PrimaryButton(if (inProgress) "Navigate to destination" else "Navigate to pickup", !busy) {
            if (inProgress) onNavigate(trip.destinationLat, trip.destinationLng, trip.destination)
            else onNavigate(trip.pickupLat, trip.pickupLng, trip.pickup)
        }
        Spacer(Modifier.height(10.dp))
    } else if (trip.passengerPhone.isNotBlank()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Call", !busy, { onCall(trip.passengerPhone) }, Modifier.weight(1f))
            SecondaryButton("Message", !busy, { onMessagePassenger(trip.passengerPhone) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }

    SwipeAction(
        label = when { inProgress -> "Slide to complete"; arrived -> "Slide to start"; else -> "Slide when arrived" },
        enabled = !busy,
        onComplete = { when { inProgress -> onComplete(trip); arrived -> onStart(trip); else -> onArrive(trip) } }
    )

    if (arrived) {
        Spacer(Modifier.height(7.dp))
        val remainingNoShow = (180 - waitSeconds).coerceAtLeast(0)
        TextButton(
            onClick = { if (remainingNoShow == 0L) onCancel(trip, true) },
            enabled = remainingNoShow == 0L && !busy,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(if (remainingNoShow == 0L) "Passenger no-show" else "No-show in ${formatTimer(remainingNoShow)}", color = if (remainingNoShow == 0L) Red else Muted)
        }
    } else if (!inProgress) {
        TextButton(onClick = { onCancel(trip, false) }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancel trip", color = Muted)
        }
    }
}

@Composable
private fun ActivityScreen(history: List<DriverApi.TripHistoryItem>, loading: Boolean) {
    Column(Modifier.fillMaxSize().background(Ink).padding(20.dp)) {
        Text("Activity", color = White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("Your completed and cancelled trips", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        if (loading && history.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Orange, trackColor = Navy)
        } else if (history.isEmpty()) {
            EmptyState("No trip history yet", "Finished trips will appear here.")
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                history.forEachIndexed { index, trip ->
                    HistoryRow(trip)
                    if (index < history.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = .08f))
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(trip: DriverApi.TripHistoryItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (trip.status == "completed") Green else Red))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${trip.pickup} → ${trip.destination}", color = White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Text(trip.passengerName, color = Muted, fontSize = 10.sp)
            if (trip.cancellationReason.isNotBlank()) Text(trip.cancellationReason, color = Muted, fontSize = 10.sp)
        }
        Text(trip.status.replace('_', ' '), color = if (trip.status == "completed") Green else Muted, fontSize = 10.sp)
    }
}

@Composable
private fun AccountScreen(
    activity: MainActivity,
    snapshot: DriverApi.DriverSnapshot?,
    supportTickets: List<DriverApi.SupportTicket>,
    supportLoading: Boolean,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
    onLoggedOut: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val session = activity.sessionStore.load()
    var showSupport by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Ink).verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Account", color = White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SnapNestLogo(54.dp)
            Spacer(Modifier.width(13.dp))
            Column {
                Text(snapshot?.driverName ?: session?.driverName.orEmpty().ifBlank { "Driver" }, color = White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(snapshot?.vehicle ?: session?.vehicle.orEmpty().ifBlank { "Vehicle not set" }, color = Muted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Access")
        PermissionRow("Display over apps", Settings.canDrawOverlays(activity))
        PermissionRow("Location", activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        PermissionRow("Microphone", activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (android.os.Build.VERSION.SDK_INT >= 33) PermissionRow("Notifications", activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Overlay settings", true, { activity.openOverlaySettings() })

        Spacer(Modifier.height(24.dp))
        SectionHeader("Support")
        PrimaryButton("Report an issue", !working, { showSupport = true })
        Spacer(Modifier.height(12.dp))
        if (supportLoading && supportTickets.isEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Orange, trackColor = Navy)
        } else {
            supportTickets.take(4).forEachIndexed { index, ticket ->
                SupportTicketRow(ticket)
                if (index < minOf(3, supportTickets.lastIndex)) HorizontalDivider(color = Color.White.copy(alpha = .08f))
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Shift")
        SecondaryButton("End shift", !working, {
            working = true
            activity.updateDriverStatus("offline") { result ->
                working = false
                result.onFailure { onMessage(it.message ?: "Could not end shift.") }
                onRefresh()
            }
        })
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                if (working) return@OutlinedButton
                working = true
                activity.logout {
                    working = false
                    onLoggedOut()
                }
            },
            enabled = !working,
            border = BorderStroke(1.dp, Red.copy(alpha = .7f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Log out", color = Red) }

        Spacer(Modifier.height(20.dp))
        Text("SnapNest Dispatch Driver · v${BuildConfig.VERSION_NAME}", color = Muted.copy(alpha = .7f), fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }

    if (showSupport) {
        SupportDialog(
            onDismiss = { showSupport = false },
            onSubmit = { subject, description ->
                showSupport = false
                working = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { DriverApi.createSupportTicket(activity.sessionStore, subject, description) } }
                    result.onSuccess {
                        onMessage("Issue sent to support.")
                        onRefresh()
                    }.onFailure { onMessage(it.message ?: "Could not submit issue.") }
                    working = false
                }
            }
        )
    }
}

@Composable
private fun DriverBottomNav(selected: DriverTab, onSelect: (DriverTab) -> Unit) {
    Surface(color = Color(0xFF091E2B), border = BorderStroke(1.dp, Color.White.copy(alpha = .06f))) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 10.dp)) {
            DriverTab.entries.forEach { tab ->
                Column(
                    Modifier.weight(1f).fillMaxHeight().pointerInput(tab) {
                        detectHorizontalDragGestures(onHorizontalDrag = { _, _ -> })
                    },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(onClick = { onSelect(tab) }) {
                        Text(tab.name, color = if (selected == tab) White else Muted, fontSize = 12.sp, fontWeight = if (selected == tab) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    Box(Modifier.width(22.dp).height(2.dp).clip(CircleShape).background(if (selected == tab) Orange else Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun DriverIdentity(name: String, vehicle: String, status: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xD9152B3B),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SnapNestLogo(38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(vehicle.ifBlank { "Vehicle not set" }, color = Muted, fontSize = 10.sp)
            }
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor(status)))
        }
    }
}

@Composable
private fun DriverMap(activity: MainActivity, snapshot: DriverApi.DriverSnapshot?, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.let { updateMap(it, activity, snapshot) }
                    }
                }
                loadUrl("file:///android_asset/driver_map.html")
            }
        },
        update = { updateMap(it, activity, snapshot) }
    )
}

private fun updateMap(view: WebView, activity: MainActivity, snapshot: DriverApi.DriverSnapshot?) {
    activity.lastKnownLocation()?.let { (lat, lng) -> view.evaluateJavascript("window.snapnestSetDriverLocation($lat,$lng)", null) }
    val offer = snapshot?.offer
    val trip = snapshot?.trip
    val pLat = offer?.pickupLat ?: trip?.pickupLat
    val pLng = offer?.pickupLng ?: trip?.pickupLng
    val dLat = offer?.destinationLat ?: trip?.destinationLat
    val dLng = offer?.destinationLng ?: trip?.destinationLng
    if (pLat != null || dLat != null) {
        view.evaluateJavascript("window.snapnestSetJob(${pLat ?: "null"},${pLng ?: "null"},${dLat ?: "null"},${dLng ?: "null"},'')", null)
    } else {
        view.evaluateJavascript("window.snapnestClearJob()", null)
    }
}

@Composable
private fun RoutePair(pickup: String, destination: String) {
    Column {
        RouteRow("Pickup", pickup, Green)
        Box(Modifier.padding(start = 4.dp).width(1.dp).height(13.dp).background(Color.White.copy(alpha = .14f)))
        RouteRow("Destination", destination, Orange)
    }
}

@Composable
private fun RouteRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = Muted, fontSize = 9.sp)
            Text(value.ifBlank { "Not set" }, color = White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SwipeAction(label: String, enabled: Boolean, onComplete: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0C2330)).border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(14.dp))
    ) {
        val density = LocalDensity.current
        val maxDrag = with(density) { (maxWidth - 50.dp).toPx() }.coerceAtLeast(1f)
        var drag by remember(label) { mutableFloatStateOf(0f) }
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier
                .offset { IntOffset(drag.roundToInt(), 0) }
                .padding(4.dp)
                .size(46.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (enabled) Orange else Color(0xFF52636D))
                .pointerInput(label, enabled, maxDrag) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (drag >= maxDrag * .72f) onComplete()
                            drag = 0f
                        },
                        onDragCancel = { drag = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            drag = (drag + amount).coerceIn(0f, maxDrag)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) { Text("›", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun DriverTextField(value: String, onValueChange: (String) -> Unit, label: String, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = White,
            unfocusedTextColor = White,
            focusedBorderColor = Orange,
            unfocusedBorderColor = Color.White.copy(alpha = .16f),
            focusedLabelColor = Muted,
            unfocusedLabelColor = Muted,
            cursorColor = Orange
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Orange, disabledContainerColor = Orange.copy(alpha = .35f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .16f)),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = White),
        modifier = modifier.fillMaxWidth().height(48.dp)
    ) { Text(text, fontWeight = FontWeight.Medium) }
}

@Composable
private fun StateLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SnapNestLogo(size: androidx.compose.ui.unit.Dp) {
    AndroidView(factory = { context -> SnapNestLogoView(context) }, modifier = Modifier.size(size))
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (granted) Green else Red))
    }
}

@Composable
private fun SupportTicketRow(ticket: DriverApi.SupportTicket) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(30.dp).clip(CircleShape).background(when (ticket.priority) { "urgent" -> Red; "high" -> Orange; else -> Muted }))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ticket.subject, color = White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(ticket.status.replace('_', ' '), color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        Text(text, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun CancelTripDialog(noShow: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Navy,
        title = { Text(if (noShow) "Passenger no-show" else "Cancel trip", color = White) },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Reason") },
                minLines = 2,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = White, unfocusedTextColor = White, focusedBorderColor = Orange, unfocusedBorderColor = Color.White.copy(alpha = .16f)),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { if (reason.isNotBlank()) onConfirm(reason.trim()) }, enabled = reason.isNotBlank()) { Text(if (noShow) "Record no-show" else "Cancel trip", color = if (noShow) Red else Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back", color = Muted) } }
    )
}

@Composable
private fun SupportDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Navy,
        title = { Text("Report an issue", color = White) },
        text = {
            Column {
                OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Subject") }, singleLine = true, colors = dialogFieldColors(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("What happened?") }, minLines = 4, colors = dialogFieldColors(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { if (subject.isNotBlank() && description.isNotBlank()) onSubmit(subject.trim(), description.trim()) }, enabled = subject.isNotBlank() && description.isNotBlank()) { Text("Send", color = Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) } }
    )
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = White,
    unfocusedTextColor = White,
    focusedBorderColor = Orange,
    unfocusedBorderColor = Color.White.copy(alpha = .16f),
    focusedLabelColor = Muted,
    unfocusedLabelColor = Muted,
    cursorColor = Orange
)

@Composable
private fun rememberWaitingSeconds(arrivedAt: String, active: Boolean): Long {
    var seconds by remember(arrivedAt, active) { mutableLongStateOf(0L) }
    LaunchedEffect(arrivedAt, active) {
        while (active) {
            seconds = runCatching { ((System.currentTimeMillis() - Instant.parse(arrivedAt).toEpochMilli()) / 1000).coerceAtLeast(0) }.getOrDefault(0)
            delay(1000)
        }
    }
    return seconds
}

private fun formatTimer(seconds: Long): String = "%02d:%02d".format(seconds / 60, seconds % 60)

private fun statusColor(status: String): Color = when (status) {
    "available" -> Green
    "busy" -> Amber
    "offered" -> Orange
    "offline" -> Red
    else -> Muted
}
