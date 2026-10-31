package com.abybijo.openflow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val isDark = when (state.themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            if (!state.onboardingDone) {
                OnboardingScreen(onFinish = { viewModel.completeOnboarding() })
            } else {
                OpenFlowApp(
                    state = state,
                    isDark = isDark,
                    onSelectModel = { viewModel.selectModel(it) },
                    onDownload = { viewModel.downloadSelected() },
                    onCancelDownload = { viewModel.cancelDownload() },
                    onDeleteModel = { viewModel.deleteModel(it) },
                    onToggleRecording = {
                        if (state.isRecording) {
                            viewModel.stopRecording()
                        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            viewModel.startRecording()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onDeleteCurrent = { viewModel.deleteCurrent() },
                    onDeleteHistory = { viewModel.deleteHistoryEntry(it) },
                    onClearError = { viewModel.clearError() },
                    onSetThemeMode = { viewModel.setThemeMode(it) },
                    onClearHistory = { viewModel.clearHistory() },
                    onShowTutorial = { viewModel.showTutorialAgain() }
                )
            }
        }
    }
}
