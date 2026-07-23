package com.example.greetingcard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.greetingcard.ui.HostScreen
import com.example.greetingcard.ui.JoinScreen
import com.example.greetingcard.ui.ModeSelectScreen
import com.example.greetingcard.ui.PermissionScreen
import com.example.greetingcard.ui.theme.GreetingCardTheme
import com.example.greetingcard.viewmodel.ConnectionViewModel
import com.example.greetingcard.viewmodel.Role
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import androidx.activity.viewModels

import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : ComponentActivity() {

    private val viewModel: ConnectionViewModel by viewModels()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onProjectionGranted(result.resultCode, result.data!!)
        } else {
            viewModel.onProjectionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GreetingCardTheme {
                SyncCastApp(
                    viewModel = viewModel,
                    onLaunchCapture = {
                        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    }
                )
            }
        }
    }
}

private enum class Screen { PERMISSIONS, MODE_SELECT, HOST, JOIN }

@Composable
private fun SyncCastApp(
    viewModel: ConnectionViewModel,
    onLaunchCapture: () -> Unit
) {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.PERMISSIONS) }

    when (currentScreen) {
        Screen.PERMISSIONS -> {
            PermissionScreen(
                onAllGranted = { currentScreen = Screen.MODE_SELECT }
            )
        }

        Screen.MODE_SELECT -> {
            ModeSelectScreen(
                onHostSelected = {
                    viewModel.startHost()
                    currentScreen = Screen.HOST
                },
                onJoinSelected = { currentScreen = Screen.JOIN },
            )
        }

        Screen.HOST -> {
            HostScreen(
                viewModel = viewModel,
                onDisconnect = { currentScreen = Screen.MODE_SELECT },
                onLaunchCapture = onLaunchCapture
            )
        }

        Screen.JOIN -> {
            JoinScreen(
                viewModel = viewModel,
                onDisconnect = { currentScreen = Screen.MODE_SELECT },
            )
        }
    }
}

