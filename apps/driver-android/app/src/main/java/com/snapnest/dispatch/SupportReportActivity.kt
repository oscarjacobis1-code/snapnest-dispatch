package com.snapnest.dispatch

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

class SupportReportActivity : ComponentActivity() {
    private lateinit var sessionStore: SessionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D2A40.toInt()
        window.navigationBarColor = 0xFF061722.toInt()
        sessionStore = SessionStore(this)
        setContent { SupportReportScreen() }
    }

    @Composable
    private fun SupportReportScreen() {
        val ink = Color(0xFF061722)
        val navy = Color(0xFF0D2A40)
        val orange = Color(0xFFEF6A00)
        val white = Color(0xFFF7FAFC)
        val muted = Color(0xFF9FB0BC)
        val red = Color(0xFFEF5A67)
        var subject by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var screenshot by remember { mutableStateOf<ByteArray?>(null) }
        var preparing by remember { mutableStateOf(false) }
        var sending by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            preparing = true
            message = "Preparing screenshot…"
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { prepareScreenshot(uri) } }
                result.onSuccess {
                    screenshot = it
                    message = "Screenshot attached · ${max(1, it.size / 1024)} KB"
                }.onFailure {
                    screenshot = null
                    message = it.message ?: "Could not attach screenshot."
                }
                preparing = false
            }
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = orange, background = ink, surface = navy)) {
            Column(
                Modifier.fillMaxSize().background(ink).verticalScroll(rememberScrollState()).padding(20.dp)
            ) {
                TextButton(onClick = { finish() }, contentPadding = PaddingValues(0.dp)) {
                    Text("Back", color = muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("Report an issue", color = white, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("Send the problem directly to dispatch support.", color = muted, fontSize = 11.sp)
                Spacer(Modifier.height(22.dp))

                Surface(
                    color = Color(0xF2162C3A),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .08f))
                ) {
                    Column(Modifier.padding(18.dp)) {
                        OutlinedTextField(
                            value = subject,
                            onValueChange = { subject = it.take(120) },
                            label = { Text("Subject") },
                            singleLine = true,
                            colors = supportFieldColors(white, muted, orange),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it.take(2000) },
                            label = { Text("What happened?") },
                            minLines = 5,
                            colors = supportFieldColors(white, muted, orange),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(14.dp))

                        OutlinedButton(
                            onClick = { if (!preparing && !sending) picker.launch("image/*") },
                            enabled = !preparing && !sending,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = .16f)),
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp)
                        ) {
                            Text(if (screenshot == null) "Attach screenshot" else "Replace screenshot", color = white)
                        }
                        if (screenshot != null) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(
                                onClick = { screenshot = null; message = "Screenshot removed." },
                                enabled = !sending,
                                modifier = Modifier.align(Alignment.End)
                            ) { Text("Remove attachment", color = muted, fontSize = 10.sp) }
                        }

                        if (message.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                message,
                                color = if (message.contains("could not", true) || message.contains("failed", true)) red else muted,
                                fontSize = 10.sp
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                if (sending || subject.isBlank() || description.isBlank()) return@Button
                                sending = true
                                message = if (screenshot != null) "Uploading screenshot and sending report…" else "Sending report…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { SupportApi.report(sessionStore, subject.trim(), description.trim(), screenshot) }
                                    }
                                    result.onSuccess {
                                        message = "Issue sent to support."
                                        delay(700)
                                        finish()
                                    }.onFailure { message = it.message ?: "Could not submit issue." }
                                    sending = false
                                }
                            },
                            enabled = !sending && !preparing && subject.isNotBlank() && description.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = orange),
                            shape = RoundedCornerShape(13.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) { Text(if (sending) "Sending…" else "Send report", color = Color.White, fontWeight = FontWeight.SemiBold) }
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Screenshots are stored privately and opened by dispatch using a short-lived secure link.",
                    color = muted.copy(alpha = .72f),
                    fontSize = 9.sp
                )
            }
        }
    }

    @Composable
    private fun supportFieldColors(white: Color, muted: Color, orange: Color) = OutlinedTextFieldDefaults.colors(
        focusedTextColor = white,
        unfocusedTextColor = white,
        focusedBorderColor = orange,
        unfocusedBorderColor = Color.White.copy(alpha = .14f),
        focusedLabelColor = muted,
        unfocusedLabelColor = muted,
        cursorColor = orange
    )

    private fun prepareScreenshot(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("Could not read screenshot.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Unsupported screenshot file.")

        var sample = 1
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IllegalStateException("Could not decode screenshot.")

        val maxDimension = max(decoded.width, decoded.height)
        val bitmap = if (maxDimension > 1440) {
            val scale = 1440f / maxDimension.toFloat()
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else decoded

        fun encode(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw IllegalStateException("Could not compress screenshot.")
            output.toByteArray()
        }

        var bytes = encode(80)
        if (bytes.size > 2_800_000) bytes = encode(65)
        if (bytes.size > 2_800_000) bytes = encode(50)
        bitmap.recycle()
        if (bytes.size > 3 * 1024 * 1024) throw IllegalStateException("Screenshot is still larger than 3 MB. Crop it and try again.")
        return bytes
    }
}
