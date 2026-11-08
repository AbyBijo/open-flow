package com.abybijo.openflow

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    private var micGranted by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var a11yGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MainViewModel(applicationContext) as T
                }
            }
            val vm: MainViewModel = viewModel(factory = factory)
            val prefs by vm.prefs.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (prefs.theme) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            OpenFlowApp(
                vm = vm,
                prefs = prefs,
                isDark = isDark,
                micGranted = micGranted,
                overlayGranted = overlayGranted,
                a11yGranted = a11yGranted,
                onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenOverlaySettings = { openOverlaySettings() },
                onOpenA11ySettings = { openA11ySettings() },
                onRefreshPermissions = { refreshPermissions() },
                onStartBubble = { startBubble() },
                onStopBubble = { stopBubble() },
                onOpenUrl = { openUrl(it) },
                onCopyText = { copyText(it) },
                onToast = { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        overlayGranted = Settings.canDrawOverlays(this)
        a11yGranted = OpenFlowAccessibilityService.isEnabled()
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openA11ySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow “Display over other apps” first.", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, BubbleService::class.java))
    }

    private fun stopBubble() {
        stopService(Intent(this, BubbleService::class.java))
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open the link.", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("OpenFlow", text)
        )
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
