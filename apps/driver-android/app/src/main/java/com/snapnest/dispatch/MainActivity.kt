package com.snapnest.dispatch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var baseInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dispatch", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(244, 246, 248))
        }
        val title = TextView(this).apply { text = "SnapNest Dispatch Driver"; textSize = 24f; setTextColor(Color.rgb(17, 24, 39)); setPadding(0, 0, 0, 20) }
        val description = TextView(this).apply { text = "Sign in once. While you are on duty the app shares your GPS position and keeps the floating push-to-talk control available above other apps."; textSize = 16f; setPadding(0, 0, 0, 20) }
        baseInput = EditText(this).apply { hint = "Dispatch API URL"; setText(prefs.getString("base_url", "")); inputType = InputType.TYPE_TEXT_VARIATION_URI }
        emailInput = EditText(this).apply { hint = "Driver email"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        passwordInput = EditText(this).apply { hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val login = Button(this).apply { text = "Sign in & go available"; setOnClickListener { signIn() } }
        val resume = Button(this).apply { text = "Resume duty"; isEnabled = !prefs.getString("access_token", "").isNullOrBlank(); setOnClickListener { enableDuty("available") } }
        val unavailable = Button(this).apply { text = "Go unavailable"; isEnabled = resume.isEnabled; setOnClickListener { setStatus("unavailable") } }
        val offDuty = Button(this).apply { text = "End shift"; isEnabled = resume.isEnabled; setOnClickListener { setStatus("offline"); stopService(Intent(this@MainActivity, DriverLocationService::class.java)); stopService(Intent(this@MainActivity, OverlayService::class.java)) } }
        statusText = TextView(this).apply { text = if (resume.isEnabled) "Saved driver session found." else "Not signed in."; setPadding(0, 16, 0, 0) }
        listOf(title, description, baseInput, emailInput, passwordInput, login, resume, unavailable, offDuty, statusText).forEach { view ->
            root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 10 })
        }
        setContentView(root)
    }

    private fun signIn() {
        val base = baseInput.text.toString().trim(); val email = emailInput.text.toString().trim(); val password = passwordInput.text.toString()
        if (base.isBlank() || email.isBlank() || password.isBlank()) { statusText.text = "API URL, email and password are required."; return }
        statusText.text = "Signing in…"
        Thread {
            runCatching { DriverApi.login(base, email, password) }
                .onSuccess { session ->
                    getSharedPreferences("dispatch", MODE_PRIVATE).edit().putString("base_url", base).putString("access_token", session.accessToken).putString("driver_id", session.driverId).apply()
                    runOnUiThread { statusText.text = "Signed in. Enabling duty services…"; enableDuty("available") }
                }
                .onFailure { error -> runOnUiThread { statusText.text = error.message ?: "Sign-in failed." } }
        }.start()
    }

    private fun setStatus(status: String) {
        val prefs = getSharedPreferences("dispatch", MODE_PRIVATE)
        val base = prefs.getString("base_url", "").orEmpty(); val token = prefs.getString("access_token", "").orEmpty(); val driver = prefs.getString("driver_id", "").orEmpty()
        if (base.isBlank() || token.isBlank() || driver.isBlank()) return
        Thread { runCatching { DriverApi.postStatus(base, token, driver, status) }.onSuccess { runOnUiThread { statusText.text = "Status: $status" } }.onFailure { runOnUiThread { statusText.text = it.message ?: "Status update failed" } } }.start()
    }

    private fun enableDuty(status: String) {
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions += Manifest.permission.POST_NOTIFICATIONS
        if (permissions.isNotEmpty()) { requestPermissions(permissions.toTypedArray(), 2401); return }
        startForegroundService(Intent(this, OverlayService::class.java))
        startForegroundService(Intent(this, DriverLocationService::class.java))
        setStatus(status)
    }

    override fun onResume() {
        super.onResume()
        val prefs=getSharedPreferences("dispatch", MODE_PRIVATE)
        if(Settings.canDrawOverlays(this) && !prefs.getString("access_token", "").isNullOrBlank() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, OverlayService::class.java))
            startForegroundService(Intent(this, DriverLocationService::class.java))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if(requestCode==2401) enableDuty("available")
    }
}
