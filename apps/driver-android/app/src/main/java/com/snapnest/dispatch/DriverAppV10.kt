package com.snapnest.dispatch

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

private val V10Ink = Color(0xFF061722)
private val V10Navy = Color(0xFF0D2A40)
private val V10Panel = Color(0xF2162C3A)
private val V10PanelSoft = Color(0xE80D2230)
private val V10Orange = Color(0xFFEF6A00)
private val V10White = Color(0xFFF7FAFC)
private val V10Muted = Color(0xFF9FB0BC)
private val V10Green = Color(0xFF2DD67B)
private val V10Amber = Color(0xFFF1A32B)
private val V10Red = Color(0xFFEF5A67)

private enum class V10Tab { HOME, ACTIVITY, ACCOUNT }

@Composable
fun DispatchAppRootV10(activity: MainActivity) {
    var signedIn by remember { mutableStateOf(activity.sessionStore.load() != null) }
    LaunchedEffect(Unit) {
        while (true) {
            val next = activity.sessionStore.load() != null
            if (next != signedIn) signedIn = next
            delay(250)
        }
    }
    if (signedIn) SnapNestDriverAppV10(activity) else DispatchAppRoot(activity)
}

@Composable
fun SnapNestDriverAppV10(activity: MainActivity) {
    val store = activity.sessionStore
    var tab by remember { mutableStateOf(V10Tab.HOME) }
    var snapshot by remember { mutableStateOf<DriverApi.DriverSnapshot?>(null) }
    var history by remember { mutableStateOf<List<DriverApi.TripHistoryItem>>(emptyList()) }
    var tickets by remember { mutableStateOf<List<DriverApi.SupportTicket>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var ticketsLoading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var safetyOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() { refreshKey += 1 }

    LaunchedEffect(refreshKey) {
        while (store.load() != null) {
            val result = withContext(Dispatchers.IO) { runCatching { DriverApi.driverSnapshot(store) } }
            result.onSuccess {
                snapshot = it
                activity.ensureDutyForStatus(it.driverStatus)
                if (!message.startsWith("Safety alert")) message = ""
            }.onFailure {
                message = it.message ?: "Could not sync with dispatch."
            }
            delay(5_000)
        }
    }

    LaunchedEffect(tab, refreshKey) {
        if (store.load() == null) return@LaunchedEffect
        if (tab == V10Tab.ACTIVITY) {
            historyLoading = true
            val result = withContext(Dispatchers.IO) { runCatching { DriverApi.tripHistory(store, 60) } }
            result.onSuccess { history = it }.onFailure { message = it.message ?: "Could not load activity." }
            historyLoading = false
        }
        if (tab == V10Tab.ACCOUNT) {
            ticketsLoading = true
            val result = withContext(Dispatchers.IO) { runCatching { DriverApi.supportTickets(store, 20) } }
            result.onSuccess { tickets = it }.onFailure { message = it.message ?: "Could not load support." }
            ticketsLoading = false
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = V10Orange,
            background = V10Ink,
            surface = V10Navy,
            onPrimary = Color.White,
            onBackground = V10White,
            onSurface = V10White
        )
    ) {
        Column(Modifier.fillMaxSize().background(V10Ink)) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    V10Tab.HOME -> V10Home(
                        activity = activity,
                        snapshot = snapshot,
                        message = message,
                        onMessage = { message = it },
                        onRefresh = ::refresh,
                        onSafety = { safetyOpen = true },
                        scope = scope
                    )
                    V10Tab.ACTIVITY -> V10Activity(history, historyLoading)
                    V10Tab.ACCOUNT -> V10Account(
                        activity = activity,
                        snapshot = snapshot,
                        tickets = tickets,
                        ticketsLoading = ticketsLoading,
                        onMessage = { message = it },
                        onRefresh = ::refresh,
                        onSafety = { safetyOpen = true },
                        scope = scope
                    )
                }
            }
            V10BottomNav(tab) { tab = it }
        }

        if (safetyOpen) {
            V10SafetyDialog(
                activity = activity,
                snapshot = snapshot,
                onDismiss = { safetyOpen = false },
                onSent = {
                    safetyOpen = false
                    message = "Safety alert sent to dispatch."
                    refresh()
                }
            )
        }
    }
}

@Composable
private fun V10Home(
    activity: MainActivity,
    snapshot: DriverApi.DriverSnapshot?,
    message: String,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
    onSafety: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var working by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<Pair<DriverApi.ActiveTrip, Boolean>?>(null) }

    fun runAction(label: String, block: () -> Unit) {
        if (working) return
        working = true
        onMessage(label)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            result.onSuccess {
                onMessage("")
                onRefresh()
            }.onFailure { onMessage(it.message ?: "Action failed.") }
            working = false
        }
    }

    Box(Modifier.fillMaxSize().background(V10Ink)) {
        V10Map(activity, snapshot, Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color(0xB8061722), Color.Transparent, Color.Transparent, Color(0x55061722))
                )
            )
        )

        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            V10DriverPill(
                name = snapshot?.driverName ?: activity.sessionStore.load()?.driverName.orEmpty().ifBlank { "Driver" },
                vehicle = snapshot?.vehicle ?: activity.sessionStore.load()?.vehicle.orEmpty(),
                status = snapshot?.driverStatus ?: "syncing",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(9.dp))
            FilledIconButton(
                onClick = onSafety,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = V10PanelSoft),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = "Safety", tint = V10White)
            }
        }

        V10StateSheet(
            snapshot = snapshot,
            message = message,
            busy = working,
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
            onAccept = { runAction("Accepting trip…") { DriverApi.respondToOffer(activity.sessionStore, it.bookingId, true) } },
            onDecline = { runAction("Passing trip…") { DriverApi.respondToOffer(activity.sessionStore, it.bookingId, false) } },
            onNavigate = activity::navigate,
            onArrive = { runAction("Marking arrived…") { DriverApi.updateTrip(activity.sessionStore, it.bookingId, "arrive") } },
            onStart = { runAction("Starting trip…") { DriverApi.updateTrip(activity.sessionStore, it.bookingId, "start") } },
            onComplete = { runAction("Completing trip…") { DriverApi.updateTrip(activity.sessionStore, it.bookingId, "complete") } },
            onCall = { activity.contactPassenger(it, false) },
            onText = { activity.contactPassenger(it, true) },
            onCancel = { trip, noShow -> cancelTarget = trip to noShow }
        )
    }

    cancelTarget?.let { pair ->
        V10CancelDialog(
            noShow = pair.second,
            onDismiss = { cancelTarget = null },
            onConfirm = { reason ->
                val target = pair
                cancelTarget = null
                runAction(if (target.second) "Recording no-show…" else "Cancelling trip…") {
                    DriverApi.cancelTrip(
                        activity.sessionStore,
                        target.first.bookingId,
                        reason,
                        if (target.second) "passenger_no_show" else "driver_cancelled",
                        target.second
                    )
                }
            }
        )
    }
}

@Composable
private fun V10StateSheet(
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
    onText: (String) -> Unit,
    onCancel: (DriverApi.ActiveTrip, Boolean) -> Unit
) {
    Surface(
        color = V10Panel,
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 18.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            when {
                snapshot == null -> {
                    V10Eyebrow("CONNECTING", V10Muted)
                    Spacer(Modifier.height(7.dp))
                    Text("Syncing with dispatch", color = V10White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                }
                snapshot.offer != null -> V10Offer(snapshot.offer, busy, onAccept, onDecline)
                snapshot.trip != null -> V10Trip(snapshot.trip, busy, onNavigate, onArrive, onStart, onComplete, onCall, onText, onCancel)
                snapshot.driverStatus == "available" -> {
                    V10Eyebrow("AVAILABLE", V10Green)
                    Spacer(Modifier.height(7.dp))
                    Text("Ready for the next trip", color = V10White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("Dispatch can see your location while you are online.", color = V10Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(15.dp))
                    V10SecondaryButton("Go unavailable", !busy, onUnavailable)
                }
                else -> {
                    V10Eyebrow(if (snapshot.driverStatus == "offline") "OFF DUTY" else "UNAVAILABLE", V10Muted)
                    Spacer(Modifier.height(7.dp))
                    Text("You’re not receiving trips", color = V10White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(15.dp))
                    V10PrimaryButton("Go available", !busy, onAvailable)
                }
            }

            if (message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    color = if (message.contains("failed", true) || message.contains("could not", true)) V10Red else V10Muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun V10Offer(
    offer: DriverApi.ActiveOffer,
    busy: Boolean,
    onAccept: (DriverApi.ActiveOffer) -> Unit,
    onDecline: (DriverApi.ActiveOffer) -> Unit
) {
    var remaining by remember(offer.bookingId, offer.expiresAt) {
        mutableLongStateOf(offer.expiresAt?.minus(System.currentTimeMillis()) ?: 0L)
    }
    LaunchedEffect(offer.bookingId, offer.expiresAt) {
        while (offer.expiresAt != null && remaining > 0) {
            remaining = (offer.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
            delay(250)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        V10Eyebrow("NEW TRIP", V10Orange)
        Spacer(Modifier.weight(1f))
        if (offer.expiresAt != null) Text("${(remaining / 1000).coerceAtLeast(0)}s", color = V10Orange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(13.dp))
    V10Route(offer.pickup, offer.destination)
    Spacer(Modifier.height(12.dp))
    Text("${offer.passengerName} · ${offer.passengers} passenger${if (offer.passengers == 1) "" else "s"}", color = V10Muted, fontSize = 11.sp)
    if (offer.notes.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(offer.notes, color = V10Muted, fontSize = 11.sp)
    }
    Spacer(Modifier.height(15.dp))
    V10PrimaryButton("Accept trip", !busy) { onAccept(offer) }
    TextButton(onClick = { onDecline(offer) }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text("Decline", color = V10Muted)
    }
}

@Composable
private fun V10Trip(
    trip: DriverApi.ActiveTrip,
    busy: Boolean,
    onNavigate: (Double?, Double?, String) -> Unit,
    onArrive: (DriverApi.ActiveTrip) -> Unit,
    onStart: (DriverApi.ActiveTrip) -> Unit,
    onComplete: (DriverApi.ActiveTrip) -> Unit,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onCancel: (DriverApi.ActiveTrip, Boolean) -> Unit
) {
    val arrived = trip.status == "arrived"
    val active = trip.status == "in_progress"
    val wait = rememberV10Wait(trip.arrivedAt, arrived)

    Row(verticalAlignment = Alignment.CenterVertically) {
        V10Eyebrow(when { active -> "ON TRIP"; arrived -> "AT PICKUP"; else -> "TO PICKUP" }, when { active -> V10Orange; arrived -> V10Green; else -> V10Amber })
        Spacer(Modifier.weight(1f))
        if (arrived) Text(v10Timer(wait), color = V10White, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(12.dp))
    V10Route(trip.pickup, trip.destination)
    Spacer(Modifier.height(9.dp))
    Text(trip.passengerName, color = V10White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    if (trip.notes.isNotBlank()) Text(trip.notes, color = V10Muted, fontSize = 10.sp)
    Spacer(Modifier.height(13.dp))

    if (!arrived) {
        V10PrimaryButton(if (active) "Navigate to destination" else "Navigate to pickup", !busy) {
            if (active) onNavigate(trip.destinationLat, trip.destinationLng, trip.destination)
            else onNavigate(trip.pickupLat, trip.pickupLng, trip.pickup)
        }
        Spacer(Modifier.height(9.dp))
    } else if (trip.passengerPhone.isNotBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            V10SecondaryButton("Call", !busy, { onCall(trip.passengerPhone) }, Modifier.weight(1f))
            V10SecondaryButton("Message", !busy, { onText(trip.passengerPhone) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(9.dp))
    }

    V10Swipe(
        label = when { active -> "Slide to complete"; arrived -> "Slide to start"; else -> "Slide when arrived" },
        enabled = !busy
    ) {
        when { active -> onComplete(trip); arrived -> onStart(trip); else -> onArrive(trip) }
    }

    if (arrived) {
        val remaining = (180 - wait).coerceAtLeast(0)
        TextButton(
            onClick = { if (remaining == 0L) onCancel(trip, true) },
            enabled = remaining == 0L && !busy,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(if (remaining == 0L) "Passenger no-show" else "No-show in ${v10Timer(remaining)}", color = if (remaining == 0L) V10Red else V10Muted)
        }
    } else if (!active) {
        TextButton(onClick = { onCancel(trip, false) }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Cancel trip", color = V10Muted)
        }
    }
}

@Composable
private fun V10Activity(history: List<DriverApi.TripHistoryItem>, loading: Boolean) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val todayTrips = remember(history, today) {
        history.filter { item ->
            runCatching { Instant.parse(item.createdAt).atZone(zone).toLocalDate() == today }.getOrDefault(false)
        }
    }
    val completed = todayTrips.count { it.status == "completed" }
    val exceptions = todayTrips.count { it.status == "cancelled" || it.status == "no_show" }

    Column(Modifier.fillMaxSize().background(V10Ink).padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text("Activity", color = V10White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Today and recent trips", color = V10Muted, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            V10Metric("Trips", completed.toString(), Modifier.weight(1f))
            V10Metric("Exceptions", exceptions.toString(), Modifier.weight(1f))
            V10Metric("Total", todayTrips.size.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))

        Text("Recent", color = V10White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        if (loading && history.isEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = V10Orange, trackColor = V10Navy)
        } else if (history.isEmpty()) {
            V10Empty("No trip history yet", "Completed and cancelled trips will appear here.")
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                history.forEachIndexed { index, trip ->
                    V10HistoryRow(trip)
                    if (index < history.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = .06f))
                }
            }
        }
    }
}

@Composable
private fun V10Account(
    activity: MainActivity,
    snapshot: DriverApi.DriverSnapshot?,
    tickets: List<DriverApi.SupportTicket>,
    ticketsLoading: Boolean,
    onMessage: (String) -> Unit,
    onRefresh: () -> Unit,
    onSafety: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val session = activity.sessionStore.load()
    var showSupport by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(V10Ink).verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Account", color = V10White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            V10Logo(56.dp)
            Spacer(Modifier.width(13.dp))
            Column {
                Text(snapshot?.driverName ?: session?.driverName.orEmpty().ifBlank { "Driver" }, color = V10White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(snapshot?.vehicle ?: session?.vehicle.orEmpty().ifBlank { "Vehicle not set" }, color = V10Muted, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        V10Section("Safety")
        V10RowAction(Icons.Outlined.Shield, "Safety tools", "Send an urgent alert with your live location", V10Red, onSafety)

        Spacer(Modifier.height(22.dp))
        V10Section("Permissions")
        V10Permission("Display over apps", Settings.canDrawOverlays(activity))
        V10Permission("Location", activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        V10Permission("Microphone", activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (android.os.Build.VERSION.SDK_INT >= 33) V10Permission("Notifications", activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        Spacer(Modifier.height(10.dp))
        V10SecondaryButton("Overlay settings", true, activity::openOverlaySettings)

        Spacer(Modifier.height(22.dp))
        V10Section("Support")
        V10RowAction(Icons.Outlined.SupportAgent, "Report an issue", "Send a problem directly to dispatch support", V10Orange) { showSupport = true }
        if (ticketsLoading && tickets.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = V10Orange, trackColor = V10Navy)
        } else if (tickets.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            tickets.take(3).forEach { ticket ->
                Text(ticket.subject, color = V10White, fontSize = 12.sp)
                Text(ticket.status.replace('_', ' '), color = V10Muted, fontSize = 9.sp)
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(22.dp))
        V10Section("Shift")
        V10SecondaryButton("End shift", !working) {
            working = true
            activity.updateDriverStatus("offline") { result ->
                working = false
                result.onFailure { onMessage(it.message ?: "Could not end shift.") }
                onRefresh()
            }
        }
        Spacer(Modifier.height(9.dp))
        OutlinedButton(
            onClick = {
                if (working) return@OutlinedButton
                working = true
                activity.logout { working = false }
            },
            enabled = !working,
            border = BorderStroke(1.dp, V10Red.copy(alpha = .65f)),
            shape = RoundedCornerShape(13.dp),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("Log out", color = V10Red) }

        Spacer(Modifier.height(18.dp))
        Text("SnapNest Dispatch Driver · v${BuildConfig.VERSION_NAME}", color = V10Muted.copy(alpha = .62f), fontSize = 9.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
    }

    if (showSupport) {
        V10SupportDialog(
            onDismiss = { showSupport = false },
            onSubmit = { subject, description ->
                showSupport = false
                working = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { DriverApi.createSupportTicket(activity.sessionStore, subject, description) }
                    }
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
private fun V10SafetyDialog(
    activity: MainActivity,
    snapshot: DriverApi.DriverSnapshot?,
    onDismiss: () -> Unit,
    onSent: () -> Unit
) {
    var reason by remember { mutableStateOf("I feel unsafe") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val options = listOf("I feel unsafe", "Medical issue", "Vehicle breakdown", "Passenger incident", "Other")

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        containerColor = V10Navy,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ReportProblem, null, tint = V10Red)
                Spacer(Modifier.width(9.dp))
                Text("Safety", color = V10White)
            }
        },
        text = {
            Column {
                Text("Send an urgent alert to dispatch with your current location and trip reference.", color = V10Muted, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                options.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = reason == option, onClick = { reason = option }, colors = RadioButtonDefaults.colors(selectedColor = V10Orange))
                        Text(option, color = V10White, fontSize = 12.sp)
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("Optional note") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = V10White,
                        unfocusedTextColor = V10White,
                        focusedBorderColor = V10Orange,
                        unfocusedBorderColor = Color.White.copy(alpha = .14f),
                        focusedLabelColor = V10Muted,
                        unfocusedLabelColor = V10Muted
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = V10Red, fontSize = 10.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (sending) return@Button
                    sending = true
                    error = ""
                    val location = activity.lastKnownLocation()
                    val bookingId = snapshot?.trip?.bookingId ?: snapshot?.offer?.bookingId
                    val description = buildString {
                        append(reason)
                        if (note.isNotBlank()) append(" — ").append(note.trim())
                        append("\nLocation: ")
                        if (location != null) append(location.first).append(", ").append(location.second) else append("unavailable")
                        if (!bookingId.isNullOrBlank()) append("\nBooking: ").append(bookingId)
                    }
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                DriverApi.createSupportTicket(
                                    activity.sessionStore,
                                    subject = "SAFETY: $reason",
                                    description = description,
                                    category = "safety",
                                    priority = "urgent",
                                    bookingId = bookingId
                                )
                            }
                        }
                        result.onSuccess { onSent() }.onFailure { error = it.message ?: "Could not send safety alert." }
                        sending = false
                    }
                },
                enabled = !sending,
                colors = ButtonDefaults.buttonColors(containerColor = V10Red),
                shape = RoundedCornerShape(12.dp)
            ) { Text(if (sending) "Sending…" else "Send safety alert", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel", color = V10Muted) } }
    )
}

@Composable
private fun V10BottomNav(selected: V10Tab, onSelect: (V10Tab) -> Unit) {
    Surface(color = Color(0xFF081D2A), tonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            V10NavItem("Home", Icons.Outlined.Home, selected == V10Tab.HOME, { onSelect(V10Tab.HOME) }, Modifier.weight(1f))
            V10NavItem("Activity", Icons.Outlined.History, selected == V10Tab.ACTIVITY, { onSelect(V10Tab.ACTIVITY) }, Modifier.weight(1f))
            V10NavItem("Account", Icons.Outlined.AccountCircle, selected == V10Tab.ACCOUNT, { onSelect(V10Tab.ACCOUNT) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun V10NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = label, tint = if (selected) V10Orange else V10Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(3.dp))
            Text(label, color = if (selected) V10White else V10Muted, fontSize = 9.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun V10Map(activity: MainActivity, snapshot: DriverApi.DriverSnapshot?, modifier: Modifier = Modifier) {
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
                    override fun onPageFinished(view: WebView?, url: String?) { view?.let { v10UpdateMap(it, activity, snapshot) } }
                }
                loadUrl("file:///android_asset/driver_map.html")
            }
        },
        update = { v10UpdateMap(it, activity, snapshot) }
    )
}

private fun v10UpdateMap(view: WebView, activity: MainActivity, snapshot: DriverApi.DriverSnapshot?) {
    activity.lastKnownLocation()?.let { (lat, lng) -> view.evaluateJavascript("window.snapnestSetDriverLocation($lat,$lng)", null) }
    val offer = snapshot?.offer
    val trip = snapshot?.trip
    val pLat = offer?.pickupLat ?: trip?.pickupLat
    val pLng = offer?.pickupLng ?: trip?.pickupLng
    val dLat = offer?.destinationLat ?: trip?.destinationLat
    val dLng = offer?.destinationLng ?: trip?.destinationLng
    if (pLat != null || dLat != null) {
        view.evaluateJavascript("window.snapnestSetJob(${pLat ?: "null"},${pLng ?: "null"},${dLat ?: "null"},${dLng ?: "null"},'')", null)
    } else view.evaluateJavascript("window.snapnestClearJob()", null)
}

@Composable
private fun V10DriverPill(name: String, vehicle: String, status: String, modifier: Modifier = Modifier) {
    Surface(color = V10PanelSoft, shape = RoundedCornerShape(18.dp), shadowElevation = 10.dp, modifier = modifier) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            V10Logo(36.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = V10White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(vehicle.ifBlank { "Vehicle not set" }, color = V10Muted, fontSize = 9.sp)
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(v10StatusColor(status)))
        }
    }
}

@Composable
private fun V10Logo(size: androidx.compose.ui.unit.Dp) {
    AndroidView(factory = { SnapNestLogoView(it) }, modifier = Modifier.size(size).clip(RoundedCornerShape(size * .24f)))
}

@Composable
private fun V10Route(pickup: String, destination: String) {
    Column {
        V10RouteRow("Pickup", pickup, V10Green)
        Box(Modifier.padding(start = 4.dp).width(1.dp).height(11.dp).background(Color.White.copy(alpha = .12f)))
        V10RouteRow("Destination", destination, V10Orange)
    }
}

@Composable
private fun V10RouteRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = V10Muted, fontSize = 9.sp)
            Text(value.ifBlank { "Not set" }, color = V10White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        }
    }
}

@Composable
private fun V10Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFF0A202D), shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(value, color = V10White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = V10Muted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun V10HistoryRow(trip: DriverApi.TripHistoryItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (trip.status == "completed") V10Green else V10Red))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("${trip.pickup} → ${trip.destination}", color = V10White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(trip.passengerName, color = V10Muted, fontSize = 9.sp)
            if (trip.cancellationReason.isNotBlank()) Text(trip.cancellationReason, color = V10Muted, fontSize = 9.sp, maxLines = 1)
        }
        Text(trip.status.replace('_', ' '), color = if (trip.status == "completed") V10Green else V10Muted, fontSize = 9.sp)
    }
}

@Composable
private fun V10Section(text: String) {
    Text(text.uppercase(), color = V10Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun V10Permission(label: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = V10White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(if (granted) "Ready" else "Needs access", color = if (granted) V10Green else V10Amber, fontSize = 10.sp)
    }
}

@Composable
private fun V10RowAction(icon: ImageVector, title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color(0xFF0A202D), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = V10White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = V10Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun V10Eyebrow(text: String, color: Color) {
    Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable
private fun V10PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = V10Orange, disabledContainerColor = V10Orange.copy(alpha = .32f)),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
}

@Composable
private fun V10SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
        modifier = modifier.fillMaxWidth().height(50.dp)
    ) { Text(text, color = V10White, fontSize = 12.sp) }
}

@Composable
private fun V10Swipe(label: String, enabled: Boolean, onComplete: () -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A202D))
    ) {
        val density = LocalDensity.current
        val maxDrag = with(density) { (maxWidth - 50.dp).toPx() }.coerceAtLeast(1f)
        var drag by remember(label) { mutableFloatStateOf(0f) }
        Text(label, color = V10Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier.offset { IntOffset(drag.roundToInt(), 0) }.padding(4.dp).size(46.dp).clip(RoundedCornerShape(11.dp))
                .background(if (enabled) V10Orange else Color(0xFF52636D))
                .pointerInput(label, enabled, maxDrag) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = { if (drag >= maxDrag * .72f) onComplete(); drag = 0f },
                        onDragCancel = { drag = 0f },
                        onHorizontalDrag = { change, amount -> change.consume(); drag = (drag + amount).coerceIn(0f, maxDrag) }
                    )
                },
            contentAlignment = Alignment.Center
        ) { Text("›", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun V10CancelDialog(noShow: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = V10Navy,
        title = { Text(if (noShow) "Passenger no-show" else "Cancel trip", color = V10White) },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(300) },
                label = { Text("Reason") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = V10White,
                    unfocusedTextColor = V10White,
                    focusedBorderColor = V10Orange,
                    unfocusedBorderColor = Color.White.copy(alpha = .14f),
                    focusedLabelColor = V10Muted,
                    unfocusedLabelColor = V10Muted
                )
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason.trim()) }, enabled = reason.isNotBlank()) { Text("Confirm", color = V10Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back", color = V10Muted) } }
    )
}

@Composable
private fun V10SupportDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = V10Navy,
        title = { Text("Report an issue", color = V10White) },
        text = {
            Column {
                OutlinedTextField(value = subject, onValueChange = { subject = it.take(120) }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(value = description, onValueChange = { description = it.take(2000) }, label = { Text("What happened?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(subject.trim(), description.trim()) }, enabled = subject.isNotBlank() && description.isNotBlank()) { Text("Send", color = V10Orange) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = V10Muted) } }
    )
}

@Composable
private fun V10Empty(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = V10White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = V10Muted, fontSize = 10.sp)
    }
}

@Composable
private fun rememberV10Wait(arrivedAt: String, active: Boolean): Long {
    var seconds by remember(arrivedAt, active) { mutableLongStateOf(v10Elapsed(arrivedAt)) }
    LaunchedEffect(arrivedAt, active) {
        while (active) {
            seconds = v10Elapsed(arrivedAt)
            delay(1_000)
        }
    }
    return seconds
}

private fun v10Elapsed(value: String): Long {
    if (value.isBlank()) return 0
    return runCatching { ((System.currentTimeMillis() - Instant.parse(value).toEpochMilli()) / 1000).coerceAtLeast(0) }.getOrDefault(0)
}

private fun v10Timer(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

private fun v10StatusColor(status: String): Color = when (status) {
    "available" -> V10Green
    "busy", "offered" -> V10Amber
    "offline" -> V10Red
    else -> V10Muted
}
