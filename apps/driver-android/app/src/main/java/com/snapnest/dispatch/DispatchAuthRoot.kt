package com.snapnest.dispatch

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private val AuthInk = Color(0xFF071A27)
private val AuthNavy = Color(0xFF0D2A40)
private val AuthOrange = Color(0xFFEF6A00)
private val AuthWhite = Color(0xFFF7FAFC)
private val AuthMuted = Color(0xFFA7B6C0)
private val AuthRed = Color(0xFFEF5A67)
private val AuthPanel = Color(0xD9162D3C)

private enum class AuthMode { SIGN_IN, RESET_SENT, NEW_PASSWORD }

@Composable
fun DispatchAppRoot(activity: MainActivity) {
    var authenticated by remember { mutableStateOf(activity.sessionStore.load() != null) }
    var authMode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var recoveryToken by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val callback = activity.authCallbackUri

    LaunchedEffect(authenticated) {
        while (authenticated) {
            delay(400)
            if (activity.sessionStore.load() == null) authenticated = false
        }
    }

    LaunchedEffect(callback) {
        val raw = callback ?: return@LaunchedEffect
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@LaunchedEffect
        val values = parseAuthValues(uri)
        val error = values["error_description"] ?: values["error"]
        if (!error.isNullOrBlank()) {
            message = error
            authMode = AuthMode.SIGN_IN
            activity.consumeAuthCallback()
            return@LaunchedEffect
        }

        val access = values["access_token"].orEmpty()
        val refresh = values["refresh_token"].orEmpty()
        val type = values["type"].orEmpty()

        if (type == "recovery" && access.isNotBlank()) {
            recoveryToken = access
            authMode = AuthMode.NEW_PASSWORD
            message = ""
            activity.consumeAuthCallback()
            return@LaunchedEffect
        }

        if (access.isNotBlank() && refresh.isNotBlank()) {
            loading = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AuthApi.sessionFromTokens(
                        BuildConfig.DISPATCH_API_URL,
                        access,
                        refresh,
                        values["expires_in"]?.toLongOrNull()
                    )
                }
            }
            result.onSuccess {
                activity.sessionStore.save(it)
                authenticated = true
                message = ""
            }.onFailure { message = it.message ?: "Could not finish SSO sign-in." }
            loading = false
            activity.consumeAuthCallback()
        }
    }

    when {
        authenticated -> SnapNestDriverApp(activity)
        authMode == AuthMode.NEW_PASSWORD -> NewPasswordScreen(
            loading = loading,
            message = message,
            onSave = { password ->
                loading = true
                message = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { AuthApi.updatePassword(BuildConfig.DISPATCH_API_URL, recoveryToken, password) }
                    }
                    result.onSuccess {
                        recoveryToken = ""
                        authMode = AuthMode.SIGN_IN
                        message = "Password updated. Sign in with your new password."
                    }.onFailure { message = it.message ?: "Could not update password." }
                    loading = false
                }
            },
            onBack = {
                recoveryToken = ""
                authMode = AuthMode.SIGN_IN
                message = ""
            }
        )
        else -> EnhancedLoginScreen(
            activity = activity,
            loading = loading,
            message = message,
            resetSent = authMode == AuthMode.RESET_SENT,
            onMessage = { message = it },
            onLoading = { loading = it },
            onSignedIn = { session ->
                activity.sessionStore.save(session)
                authenticated = true
                message = ""
            },
            onResetSent = {
                authMode = AuthMode.RESET_SENT
                message = "Check your email for the password reset link."
            },
            onBackFromReset = {
                authMode = AuthMode.SIGN_IN
                message = ""
            }
        )
    }
}

@Composable
private fun EnhancedLoginScreen(
    activity: MainActivity,
    loading: Boolean,
    message: String,
    resetSent: Boolean,
    onMessage: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
    onSignedIn: (SessionStore.Session) -> Unit,
    onResetSent: () -> Unit,
    onBackFromReset: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    MaterialTheme(colorScheme = darkColorScheme(primary = AuthOrange, background = AuthInk, surface = AuthNavy)) {
        Box(Modifier.fillMaxSize().background(AuthInk)) {
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
                    Brush.verticalGradient(
                        listOf(Color(0x22071827), Color(0x66071827), Color(0xF5071827))
                    )
                )
            )

            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AndroidView(
                        factory = { SnapNestLogoView(it) },
                        modifier = Modifier.size(46.dp).clip(RoundedCornerShape(13.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("SnapNest Dispatch", color = AuthWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Driver", color = AuthMuted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(18.dp))

                Surface(
                    color = AuthPanel,
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .11f)),
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp)) {
                        if (resetSent) {
                            Text("Check your inbox", color = AuthWhite, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text("Use the secure link in the email to choose a new password.", color = AuthMuted, fontSize = 12.sp)
                            if (message.isNotBlank()) {
                                Spacer(Modifier.height(12.dp))
                                Text(message, color = AuthMuted, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            AuthPrimaryButton("Back to sign in", true, onBackFromReset)
                        } else {
                            AuthField(email, { email = it }, "Email")
                            Spacer(Modifier.height(9.dp))
                            AuthField(password, { password = it }, "Password", true)
                            Box(Modifier.fillMaxWidth()) {
                                TextButton(
                                    onClick = { showReset = true },
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) { Text("Forgot password?", color = AuthMuted, fontSize = 11.sp) }
                            }
                            AuthPrimaryButton(
                                if (loading) "Signing in…" else "Sign in",
                                !loading && email.isNotBlank() && password.isNotBlank()
                            ) {
                                onLoading(true)
                                onMessage("")
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { DriverApi.login(BuildConfig.DISPATCH_API_URL, email.trim(), password) }
                                    }
                                    result.onSuccess(onSignedIn)
                                        .onFailure { onMessage(it.message ?: "Sign-in failed.") }
                                    onLoading(false)
                                }
                            }

                            Spacer(Modifier.height(15.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = .10f))
                                Text("  or  ", color = AuthMuted.copy(alpha = .8f), fontSize = 10.sp)
                                HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = .10f))
                            }
                            Spacer(Modifier.height(12.dp))

                            SsoButton("Continue with Google", "G", !loading) {
                                launchSso(activity, scope, "google", onLoading, onMessage)
                            }
                            Spacer(Modifier.height(8.dp))
                            SsoButton("Continue with Microsoft", "M", !loading) {
                                launchSso(activity, scope, "azure", onLoading, onMessage)
                            }

                            if (message.isNotBlank()) {
                                Spacer(Modifier.height(11.dp))
                                Text(
                                    message,
                                    color = if (message.contains("updated", true)) AuthMuted else AuthRed,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(13.dp))
                Text(
                    "Powered by SnapNest Digital Solutions",
                    color = AuthMuted.copy(alpha = .68f),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { if (!loading) showReset = false },
            containerColor = AuthNavy,
            title = { Text("Reset password", color = AuthWhite) },
            text = {
                Column {
                    Text("Enter the email used for this driver account.", color = AuthMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    AuthField(email, { email = it }, "Email")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !loading && email.isNotBlank(),
                    onClick = {
                        onLoading(true)
                        onMessage("")
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { AuthApi.requestPasswordReset(BuildConfig.DISPATCH_API_URL, email) }
                            }
                            result.onSuccess {
                                showReset = false
                                onResetSent()
                            }.onFailure { onMessage(it.message ?: "Could not send reset email.") }
                            onLoading(false)
                        }
                    }
                ) { Text(if (loading) "Sending…" else "Send link", color = AuthOrange) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }, enabled = !loading) { Text("Cancel", color = AuthMuted) }
            }
        )
    }
}

private fun launchSso(
    activity: MainActivity,
    scope: kotlinx.coroutines.CoroutineScope,
    provider: String,
    onLoading: (Boolean) -> Unit,
    onMessage: (String) -> Unit
) {
    onLoading(true)
    onMessage("")
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { AuthApi.oauthUrl(BuildConfig.DISPATCH_API_URL, provider) }
        }
        result.onSuccess { url ->
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { onMessage("No browser is available for SSO sign-in.") }
        }.onFailure { onMessage(it.message ?: "Could not start SSO sign-in.") }
        onLoading(false)
    }
}

@Composable
private fun NewPasswordScreen(
    loading: Boolean,
    message: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= 8 && password == confirm

    MaterialTheme(colorScheme = darkColorScheme(primary = AuthOrange, background = AuthInk, surface = AuthNavy)) {
        Box(Modifier.fillMaxSize().background(AuthInk), contentAlignment = Alignment.Center) {
            Surface(
                color = AuthPanel,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .11f)),
                modifier = Modifier.fillMaxWidth().padding(22.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Choose a new password", color = AuthWhite, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("Use at least 8 characters.", color = AuthMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(18.dp))
                    AuthField(password, { password = it }, "New password", true)
                    Spacer(Modifier.height(9.dp))
                    AuthField(confirm, { confirm = it }, "Confirm password", true)
                    if (confirm.isNotBlank() && password != confirm) {
                        Spacer(Modifier.height(8.dp))
                        Text("Passwords do not match.", color = AuthRed, fontSize = 11.sp)
                    }
                    if (message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(message, color = AuthRed, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    AuthPrimaryButton(if (loading) "Saving…" else "Update password", valid && !loading) { onSave(password) }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onBack, enabled = !loading, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Back", color = AuthMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthField(value: String, onChange: (String) -> Unit, label: String, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AuthWhite,
            unfocusedTextColor = AuthWhite,
            focusedBorderColor = AuthOrange,
            unfocusedBorderColor = Color.White.copy(alpha = .16f),
            focusedLabelColor = AuthMuted,
            unfocusedLabelColor = AuthMuted,
            cursorColor = AuthOrange
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AuthPrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AuthOrange, disabledContainerColor = AuthOrange.copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) { Text(text, color = Color.White, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SsoButton(text: String, initial: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .14f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AuthWhite),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = .10f)),
            contentAlignment = Alignment.Center
        ) { Text(initial, color = AuthWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun parseAuthValues(uri: Uri): Map<String, String> {
    val values = linkedMapOf<String, String>()
    uri.queryParameterNames.forEach { key -> uri.getQueryParameter(key)?.let { values[key] = it } }
    val fragment = uri.fragment.orEmpty()
    if (fragment.isNotBlank()) {
        fragment.split('&').forEach { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2) {
                val key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.toString())
                val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.toString())
                values[key] = value
            }
        }
    }
    return values
}
