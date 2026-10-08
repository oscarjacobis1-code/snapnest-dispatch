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
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(244, 246, 248))
        }
        val title = TextView(this).apply {
            text = "SnapNest Dispatch Driver"
            textSize = 24f
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(0, 0, 0, 28)
        }
        val description = TextView(this).apply {
            text = "Turn on the floating push-to-talk control while you are on duty. The microphone is idle until you press and hold the bubble."
            textSize = 16f
            setPadding(0, 0, 0, 28)
        }
        val button = Button(this).apply {
            text = "Enable floating PTT"
            setOnClickListener { enableOverlay() }
        }
        root.addView(title)
        root.addView(description)
        root.addView(button)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        maybeStartOverlay()
    }

    private fun enableOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 2401)
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    private fun maybeStartOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        startForegroundService(Intent(this, OverlayService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2401 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            maybeStartOverlay()
        }
    }
}
