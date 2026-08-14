package com.example.greetingcard.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greetingcard.network.DeviceState
import com.example.greetingcard.ui.components.AcousticSyncTrimCard
import com.example.greetingcard.ui.components.TelemetryCard
import com.example.greetingcard.ui.theme.*
import com.example.greetingcard.viewmodel.ConnectionViewModel

@Composable
fun JoinScreen(
    viewModel: ConnectionViewModel,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val localState by viewModel.deviceState.collectAsState()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsState()
    val appearanceMode by viewModel.appearanceMode.collectAsState()
    val manualTrimMs by viewModel.manualTrimMs.collectAsState()
    val hostDeviceId by viewModel.currentHostDeviceId.collectAsState()

    var showSettingsScreen by rememberSaveable { mutableStateOf(false) }
    var ipText by rememberSaveable { mutableStateOf("") }

    val isConnected = localState.isConnectedOrActive
    val syncState by viewModel.clockSyncState.collectAsState()
    val isCalibrating = syncState.statusMessage.contains("Calibrat", ignoreCase = true)

    // Dynamic header text resolution
    val dynamicHeaderText = when {
        isCalibrating -> "Calibrating"
        isConnected -> "Connected"
        else -> "Disconnected"
    }

    if (showSettingsScreen) {
        SettingsScreen(
            isDeveloperMode = isDeveloperMode,
            onDeveloperModeChange = { viewModel.setDeveloperMode(it) },
            selectedAppearance = appearanceMode,
            onAppearanceChange = { viewModel.setAppearanceMode(it) },
            onClose = { showSettingsScreen = false }
        )
    } else {
        Scaffold(
            containerColor = PlumBackground,
            bottomBar = {
                JoinBottomNavigationBar(
                    onDevicesClick = {
                        Toast.makeText(context, "Devices discovery placeholder", Toast.LENGTH_SHORT).show()
                    },
                    onJoinClick = {  },
                    onSettingsClick = { showSettingsScreen = true }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PlumBackground)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 18.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Section
                Text(
                    text = dynamicHeaderText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    fontSize = 24.sp
                )

                if (isDeveloperMode) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "In Developer Mode",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PinkPrimary,
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Leader IP Input Card
                LeaderIPCard(
                    ipText = ipText,
                    onIpChange = { ipText = it },
                    isConnected = isConnected,
                    isDeveloperMode = isDeveloperMode,
                    viewModel = viewModel,
                    onDone = {
                        if (ipText.isNotBlank() && !isConnected) {
                            viewModel.joinHost(ipText)
                            keyboard?.hide()
                        }
                    }
                )

                Spacer(Modifier.height(24.dp))

                // Audio Streaming Section Header
                Text(
                    text = "Audio Streaming",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )

                // 3 Aux Streaming Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {  },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PinkPrimary,
                            contentColor = PinkOnPrimary
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("Local File", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {  },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PinkPrimary,
                            contentColor = PinkOnPrimary
                        ),
                        modifier = Modifier.weight(1.2f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("Capture Live", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Button(
                        onClick = { viewModel.startCalibration(10) },
                        enabled = isConnected,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PinkPrimary,
                            contentColor = PinkOnPrimary,
                            disabledContainerColor = ButtonDisabledBg,
                            disabledContentColor = ButtonDisabledText
                        ),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("Calibrate", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Main Connection Action Button (Connect / Disconnect)
                if (!isConnected) {
                    Button(
                        onClick = {
                            if (ipText.isNotBlank()) {
                                viewModel.joinHost(ipText)
                                keyboard?.hide()
                            }
                        },
                        enabled = ipText.isNotBlank(),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PinkPrimary,
                            contentColor = PinkOnPrimary,
                            disabledContainerColor = ButtonDisabledBg,
                            disabledContentColor = ButtonDisabledText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Connect", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            viewModel.disconnect()
                            onDisconnect()
                        },
                        shape = RoundedCornerShape(24.dp),
                        border = BorderStroke(1.dp, PinkPrimary.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Disconnect", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Acoustic Manual Sync Trim Card
                AcousticSyncTrimCard(
                    manualTrimMs = manualTrimMs,
                    hostDeviceId = hostDeviceId,
                    onTrimChange = { viewModel.setManualTrimMs(it) },
                    onStartCalibrationClick = { viewModel.startCalibration(10) }
                )

                // Audio Sync Telemetry (Visible ONLY when Developer Mode is ON)
                AnimatedVisibility(
                    visible = isDeveloperMode,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        IsolatedJoinTelemetryCard(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun LeaderIPCard(
    ipText: String,
    onIpChange: (String) -> Unit,
    isConnected: Boolean,
    isDeveloperMode: Boolean,
    viewModel: ConnectionViewModel,
    onDone: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PlumSurface),
        border = BorderStroke(1.dp, PinkPrimary.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Leader IP Address",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                if (isDeveloperMode) {
                    IsolatedDeveloperTelemetryHeader(viewModel = viewModel)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Dark Purple Input Container
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PlumSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = ipText,
                        onValueChange = onIpChange,
                        enabled = !isConnected,
                        singleLine = true,
                        cursorBrush = SolidColor(PinkPrimary),
                        textStyle = TextStyle(
                            color = if (isConnected) TextSecondary.copy(alpha = 0.6f) else TextPrimary,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 1.sp
                        ),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onDone() }),
                        decorationBox = { innerTextField ->
                            if (ipText.isEmpty() && !isConnected) {
                                Text(
                                    text = "10.17.225.",
                                    color = TextSecondary.copy(alpha = 0.35f),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun IsolatedDeveloperTelemetryHeader(viewModel: ConnectionViewModel) {
    val syncState by viewModel.clockSyncState.collectAsState()
    val telemetryText = if (syncState.isSynced) {
        "Offset: ${syncState.formattedOffsetMs} | RTT: ${syncState.formattedRttMs}"
    } else {
        syncState.statusMessage
    }

    Text(
        text = telemetryText,
        style = MaterialTheme.typography.labelSmall,
        color = TextSecondary.copy(alpha = 0.7f),
        fontSize = 11.sp
    )
}

@Composable
private fun IsolatedJoinTelemetryCard(viewModel: ConnectionViewModel) {
    val telemetryList by viewModel.rendererTelemetry.collectAsState()
    if (telemetryList.isNotEmpty()) {
        TelemetryCard(telemetryList = telemetryList)
    }
}

@Composable
private fun JoinBottomNavigationBar(
    onDevicesClick: () -> Unit,
    onJoinClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    NavigationBar(
        containerColor = PlumBackground,
        tonalElevation = 8.dp
    ) {
        NavigationBarItem(
            selected = false,
            onClick = onDevicesClick,
            icon = { Icon(Icons.Default.Devices, contentDescription = "Devices") },
            label = { Text("Devices", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )

        NavigationBarItem(
            selected = true,
            onClick = onJoinClick,
            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Join Screen") },
            label = { Text("Join Screen", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = Color.Black,
                selectedTextColor = PinkPrimary,
                indicatorColor = PinkPrimary
            )
        )

        NavigationBarItem(
            selected = false,
            onClick = onSettingsClick,
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )
    }
}

