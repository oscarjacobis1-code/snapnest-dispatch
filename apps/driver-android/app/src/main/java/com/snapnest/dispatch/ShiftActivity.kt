package com.snapnest.dispatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ShiftInk = Color(0xFF061722)
private val ShiftPanel = Color(0xFF0A202D)
private val ShiftNavy = Color(0xFF0D2A40)
private val ShiftOrange = Color(0xFFEF6A00)
private val ShiftGreen = Color(0xFF2DD67B)
private val ShiftWhite = Color(0xFFF7FAFC)
private val ShiftMuted = Color(0xFF9FB0BC)
private val ShiftRed = Color(0xFFEF5A67)

class ShiftActivity : ComponentActivity() {
    lateinit var sessionStore: SessionStore
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = SessionStore(this)
        if (sessionStore.load() == null) {
            finish()
            return
        }
        window.statusBarColor = ShiftInk.hashCode()
        window.navigationBarColor = ShiftInk.hashCode()
        setContent { ShiftSummaryScreen(this) }
    }
}

@Composable
private fun ShiftSummaryScreen(activity: ShiftActivity) {
    var summary by remember { mutableStateOf<ShiftSummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = ""
        val result = withContext(Dispatchers.IO) { runCatching { ShiftApi.summary(activity.sessionStore) } }
        result.onSuccess { summary = it }.onFailure { error = it.message ?: "Could not load shift summary." }
        loading = false
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = ShiftOrange, background = ShiftInk, surface = ShiftNavy)) {
        Column(Modifier.fillMaxSize().background(ShiftInk).padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = ShiftWhite)
                }
                Column(Modifier.weight(1f)) {
                    Text("Shift summary", color = ShiftWhite, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Your current or most recent shift", color = ShiftMuted, fontSize = 10.sp)
                }
                IconButton(onClick = { refreshKey += 1 }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = ShiftWhite)
                }
            }

            if (loading && summary == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = ShiftOrange, trackColor = ShiftNavy)
                return@Column
            }

            if (error.isNotBlank() && summary == null) {
                Spacer(Modifier.height(36.dp))
                Text(error, color = ShiftRed, fontSize = 12.sp)
                return@Column
            }

            val shift = summary ?: return@Column
            Spacer(Modifier.height(12.dp))
            Surface(color = ShiftPanel, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (shift.active) "ON SHIFT" else "LAST SHIFT", color = if (shift.active) ShiftGreen else ShiftMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(shift.durationMinutes), color = ShiftWhite, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (shift.startedAt.isBlank()) "No shift recorded yet" else "Started ${formatTime(shift.startedAt)}${if (!shift.active && shift.endedAt.isNotBlank()) " · Ended ${formatTime(shift.endedAt)}" else ""}",
                        color = ShiftMuted,
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ShiftMetric("Completed", shift.completed.toString(), ShiftGreen, Modifier.weight(1f))
                ShiftMetric("Cancelled", shift.cancelled.toString(), ShiftMuted, Modifier.weight(1f))
                ShiftMetric("No-show", shift.noShows.toString(), ShiftOrange, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Surface(color = ShiftPanel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Recorded trips", color = ShiftMuted, fontSize = 9.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(shift.trips.toString(), color = ShiftWhite, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Text("A shift begins when you go online and closes only when you End Shift or go offline. Going unavailable does not end it.", color = ShiftMuted, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ShiftMetric(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(color = ShiftPanel, shape = RoundedCornerShape(16.dp), modifier = modifier) {
        Column(Modifier.padding(13.dp)) {
            Text(value, color = accent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = ShiftMuted, fontSize = 9.sp)
        }
    }
}

private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

private fun formatTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d · h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.parse(value))
}.getOrDefault("—")
