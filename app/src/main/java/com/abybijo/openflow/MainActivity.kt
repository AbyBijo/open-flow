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
import androidx.compose.runtime.LaunchedEffect
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

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(applicationContext) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]
        setContent {
            val vm: MainViewModel = viewModel(factory = factory)
            val prefs by vm.prefs.collectAsState()
            val prefsReady by vm.prefsReady.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (prefs.theme) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            // Keep the floating bubble in sync with the toggle and the real
            // permissions. The toggle alone cannot do this: it only fires on
            // change, so a bubble left ON never started on a fresh launch.
            // This effect runs on launch and whenever any of these change.
            LaunchedEffect(prefs.bubbleEnabled, prefs.onboardingDone, overlayGranted, micGranted) {
                syncBubbleService(
                    enabled = prefs.bubbleEnabled,
                    onboarded = prefs.onboardingDone,
                    overlay = overlayGranted,
                    mic = micGranted
                )
            }
            OpenFlowApp(
                vm = vm,
                prefs = prefs,
                prefsReady = prefsReady,
                isDark = isDark,
                micGranted = micGranted,
                overlayGranted = overlayGranted,
                a11yGranted = a11yGranted,
                onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onOpenOverlaySettings = { openOverlaySettings() },
                onOpenA11ySettings = { openA11ySettings() },
                onOpenAppInfo = { openAppInfo() },
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

    /** App info screen — where Android 13+ hides "Allow restricted settings". */
    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
        )
    }

    /**
     * Silent sync used by the launch effect: start the bubble only when
     * everything is actually in place, stop it otherwise. Never opens
     * settings or toasts on its own — the toggle handler does that.
     */
    private fun syncBubbleService(enabled: Boolean, onboarded: Boolean, overlay: Boolean, mic: Boolean) {
        if (enabled && onboarded && overlay && mic) {
            ContextCompat.startForegroundService(this, Intent(this, BubbleService::class.java))
        } else {
            stopService(Intent(this, BubbleService::class.java))
        }
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            viewModel.setBubbleEnabled(false)
            Toast.makeText(this, "Allow “Display over other apps” first.", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        if (!micGranted) {
            viewModel.setBubbleEnabled(false)
            Toast.makeText(this, "Allow the microphone first.", Toast.LENGTH_LONG).show()
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
