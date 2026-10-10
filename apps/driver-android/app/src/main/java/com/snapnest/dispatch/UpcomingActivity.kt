package com.snapnest.dispatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val UpcomingInk = Color(0xFF061722)
private val UpcomingPanel = Color(0xFF0A202D)
private val UpcomingNavy = Color(0xFF0D2A40)
private val UpcomingOrange = Color(0xFFEF6A00)
private val UpcomingGreen = Color(0xFF2DD67B)
private val UpcomingWhite = Color(0xFFF7FAFC)
private val UpcomingMuted = Color(0xFF9FB0BC)
private val UpcomingRed = Color(0xFFEF5A67)

class UpcomingActivity : ComponentActivity() {
    lateinit var sessionStore: SessionStore
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = SessionStore(this)
        if (sessionStore.load() == null) {
            finish()
            return
        }
        window.statusBarColor = UpcomingInk.hashCode()
        window.navigationBarColor = UpcomingInk.hashCode()
        setContent { UpcomingJobsScreen(this) }
    }
}

@Composable
private fun UpcomingJobsScreen(activity: UpcomingActivity) {
    var jobs by remember { mutableStateOf<List<UpcomingApi.Job>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() { refreshKey += 1 }

    LaunchedEffect(refreshKey) {
        loading = true
        error = ""
        val result = withContext(Dispatchers.IO) { runCatching { UpcomingApi.list(activity.sessionStore, 30) } }
        result.onSuccess { jobs = it }.onFailure { error = it.message ?: "Could not load upcoming jobs." }
        loading = false
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = UpcomingOrange, background = UpcomingInk, surface = UpcomingNavy)) {
        Column(Modifier.fillMaxSize().background(UpcomingInk)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { activity.finish() }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = UpcomingWhite)
                }
                Column(Modifier.weight(1f)) {
                    Text("Upcoming jobs", color = UpcomingWhite, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("Reservations assigned to you", color = UpcomingMuted, fontSize = 10.sp)
                }
                IconButton(onClick = { refresh() }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = if (loading) UpcomingMuted else UpcomingWhite)
                }
            }

            if (loading && jobs.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = UpcomingOrange, trackColor = UpcomingNavy)
            }

            when {
                error.isNotBlank() && jobs.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(error, color = UpcomingRed, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { refresh() }, colors = ButtonDefaults.buttonColors(containerColor = UpcomingOrange)) {
                            Text("Try again")
                        }
                    }
                }
                jobs.isEmpty() && !loading -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(color = UpcomingPanel, shape = CircleShape, modifier = Modifier.size(62.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.CalendarMonth, null, tint = UpcomingMuted, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("No upcoming reservations", color = UpcomingWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text("Reserved future jobs will appear here.", color = UpcomingMuted, fontSize = 10.sp)
                    }
                }
                else -> {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        jobs.forEachIndexed { index, job ->
                            UpcomingJobCard(job)
                            if (index < jobs.lastIndex) Spacer(Modifier.height(10.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingJobCard(job: UpcomingApi.Job) {
    Surface(color = UpcomingPanel, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(formatUpcoming(job.scheduledFor), color = UpcomingOrange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(job.passengerName, color = UpcomingWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Surface(color = UpcomingNavy, shape = RoundedCornerShape(999.dp)) {
                    Text(
                        "${job.passengers} pax",
                        color = UpcomingMuted,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(15.dp))
            UpcomingRouteRow("Pickup", job.pickup, UpcomingGreen)
            Box(Modifier.padding(start = 4.dp).width(1.dp).height(11.dp).background(Color.White.copy(alpha = .12f)))
            UpcomingRouteRow("Destination", job.destination, UpcomingOrange)

            if (job.notes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(job.notes, color = UpcomingMuted, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text("Dispatch reserved this job for you. It will move into your live workflow near pickup time.", color = UpcomingMuted, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun UpcomingRouteRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, color = UpcomingMuted, fontSize = 9.sp)
            Text(value.ifBlank { "Not set" }, color = UpcomingWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatUpcoming(value: String): String {
    if (value.isBlank()) return "Scheduled"
    return runCatching {
        DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a")
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(value))
    }.getOrDefault("Scheduled")
}
