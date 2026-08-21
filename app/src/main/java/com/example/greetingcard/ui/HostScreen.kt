package com.example.greetingcard.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greetingcard.PeerInfo
import com.example.greetingcard.network.DeviceState
import com.example.greetingcard.ui.components.AcousticSyncTrimCard
import com.example.greetingcard.ui.components.TelemetryCard
import com.example.greetingcard.ui.theme.*
import com.example.greetingcard.viewmodel.ConnectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
fun HostScreen(
    viewModel: ConnectionViewModel,
    onDisconnect: () -> Unit,
    onLaunchCapture: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val peers by viewModel.peers.collectAsState()
    val isDeveloperMode by viewModel.isDeveloperMode.collectAsState()
    val appearanceMode by viewModel.appearanceMode.collectAsState()
    val manualTrimMs by viewModel.manualTrimMs.collectAsState()
    val hostDeviceId by viewModel.currentHostDeviceId.collectAsState()

    var showSettingsScreen by rememberSaveable { mutableStateOf(false) }

    // Streaming state tracking for UI rules
    var isCapturing by rememberSaveable { mutableStateOf(false) }
    var isStreamingFile by rememberSaveable { mutableStateOf(false) }
    var isCalibrating by rememberSaveable { mutableStateOf(false) }
    var lastActionEnded by rememberSaveable { mutableStateOf(false) }

    val audioPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val tempFile = java.io.File(context.cacheDir, "selected_audio.mp3")
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        tempFile.outputStream().use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    viewModel.startAudioStream(tempFile)
                    isStreamingFile = true
                    isCapturing = false
                    isCalibrating = false
                    lastActionEnded = false
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // Dynamic header text resolution
    val dynamicHeaderText = when {
        isCalibrating -> "Calibrating Peers"
        isCapturing -> "Capturing : Device Audio"
        isStreamingFile -> "Streaming : Audio File"
        lastActionEnded -> "Audio Stream ended"
        peers.isEmpty() -> "Waiting for Peers"
        else -> "Waiting for Peers"
    }

    val deviceIp = remember { getDeviceIpAddress() }

    BackHandler {
        if (showSettingsScreen) {
            showSettingsScreen = false
        } else {
            viewModel.disconnect()
            onDisconnect()
        }
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
                HostBottomNavigationBar(
                    onDevicesClick = {
                        Toast.makeText(context, "Devices discovery placeholder", Toast.LENGTH_SHORT).show()
                    },
                    onHostClick = {  },
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
                // Header Section with Back Navigation
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    IconButton(
                        onClick = {
                            viewModel.disconnect()
                            onDisconnect()
                        },
                        modifier = Modifier.align(Alignment.CenterStart)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Mode Select",
                            tint = TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        text = dynamicHeaderText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        fontSize = 22.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

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

                // Host IP Card
                HostIPCard(deviceIp = deviceIp)

                Spacer(Modifier.height(24.dp))

                // Audio Streaming Controls
                Text(
                    text = "Audio Streaming",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Local File Button
                    Button(
                        onClick = {
                            audioPickerLauncher.launch("audio/*")
                        },
                        enabled = !isCapturing && !isCalibrating,
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
                        Text("Local File", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // Capture Live / Stop Capture Button
                    if (isCapturing) {
                        Button(
                            onClick = {
                                viewModel.stopAudioStream()
                                isCapturing = false
                                lastActionEnded = true
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CaptureActivePurple,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier.weight(1.2f),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Text("Stop Capture", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = {
                                onLaunchCapture()
                                isCapturing = true
                                isStreamingFile = false
                                isCalibrating = false
                                lastActionEnded = false
                            },
                            enabled = viewModel.isAudioCaptureSupported && !isCalibrating,
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PinkPrimary,
                                contentColor = PinkOnPrimary,
                                disabledContainerColor = ButtonDisabledBg,
                                disabledContentColor = ButtonDisabledText
                            ),
                            modifier = Modifier.weight(1.2f),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            Text("Capture Live", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }

                    // Calibrate Button
                    val isCalibrateEnabled = !isCapturing && !isStreamingFile && peers.isNotEmpty()
                    Button(
                        onClick = {
                            isCalibrating = true
                            lastActionEnded = false
                            viewModel.startCalibration(10)
                            coroutineScope.launch {
                                delay(10500)
                                isCalibrating = false
                                lastActionEnded = true
                            }
                        },
                        enabled = isCalibrateEnabled,
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

                // Acoustic Sync Trim Card (Visible when Local File is streaming)
                AnimatedVisibility(
                    visible = isStreamingFile,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 16.dp)) {
                        AcousticSyncTrimCard(
                            manualTrimMs = manualTrimMs,
                            hostDeviceId = hostDeviceId,
                            onTrimChange = { viewModel.setManualTrimMs(it) }
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Disconnect Button
                Button(
                    onClick = {
                        viewModel.disconnect()
                        onDisconnect()
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonDisabledBg.copy(alpha = 0.5f),
                        contentColor = TextSecondary
                    ),
                    border = BorderStroke(1.dp, PlumSurfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Disconnect", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }

                Spacer(Modifier.height(28.dp))

                // Connected Peers Header
                Text(
                    text = "Connected Peers : ${if (peers.isEmpty()) "[N]" else peers.size.toString()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                // Connected Peers Grid
                if (peers.isNotEmpty()) {
                    PeersGrid(peers = peers)
                }

                // Audio Sync Telemetry (Visible ONLY when Developer Mode is ON)
                AnimatedVisibility(
                    visible = isDeveloperMode,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(modifier = Modifier.padding(top = 24.dp)) {
                        IsolatedTelemetryCard(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostIPCard(deviceIp: String) {
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
            Text(
                text = "Your IP Address",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )

            Spacer(Modifier.height(8.dp))

            // Large Pink Highlight Container
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PinkPrimary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = deviceIp,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    color = PinkOnPrimary,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.SansSerif,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp, horizontal = 16.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Share this IP with peers to connect",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun PeersGrid(peers: List<PeerInfo>) {
    val chunkedPeers = peers.chunked(4)

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        chunkedPeers.forEach { rowPeers ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top
            ) {
                rowPeers.forEach { peer ->
                    PeerAvatarItem(peer = peer)
                }
                // Fill empty slots to maintain 4-column alignment
                repeat(4 - rowPeers.size) {
                    Spacer(Modifier.width(64.dp))
                }
            }
        }
    }
}

@Composable
private fun PeerAvatarItem(peer: PeerInfo) {
    val isWeak = peer.state == DeviceState.DEGRADED || peer.state == DeviceState.FAILED || peer.lastPingMs > 100
    val statusColor = if (isWeak) StatusWeakRed else StatusConnectedGreen

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp)
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            // Main Circle Avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(PlumSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = peer.name,
                    tint = PinkPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }

            // Connection Health Dot / Badge
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(PlumBackground)
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = peer.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun IsolatedTelemetryCard(viewModel: ConnectionViewModel) {
    val telemetryList by viewModel.rendererTelemetry.collectAsState()
    if (telemetryList.isNotEmpty()) {
        TelemetryCard(telemetryList = telemetryList)
    }
}

@Composable
private fun HostBottomNavigationBar(
    onDevicesClick: () -> Unit,
    onHostClick: () -> Unit,
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
            onClick = onHostClick,
            icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Host Screen") },
            label = { Text("Host Screen", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
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

private fun getDeviceIpAddress(): String {
    return try {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "Unavailable"

        // 1. First search Wi-Fi / Hotspot interfaces (wlan, ap, softap, swlan, p2p)
        val wifiInterfaceIp = interfaces
            .filter { iface ->
                val name = iface.name.lowercase()
                name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap") ||
                        name.startsWith("swlan") || name.startsWith("p2p")
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.hostAddress?.startsWith("100.") == false }
            ?.hostAddress

        if (wifiInterfaceIp != null) return wifiInterfaceIp

        // 2. Fallback: Search non-cellular interfaces for private RFC 1918 IPv4 address
        interfaces
            .filterNot { iface ->
                val name = iface.name.lowercase()
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ||
                        name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("v4-")
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { addr ->
                if (addr.isLoopbackAddress || addr !is Inet4Address) return@firstOrNull false
                val ip = addr.hostAddress ?: return@firstOrNull false
                ip.startsWith("192.168.") || ip.startsWith("172.") || (ip.startsWith("10.") && !ip.startsWith("100."))
            }
            ?.hostAddress
            ?: // 3. Final fallback: Any non-loopback IPv4 address
            interfaces
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
            ?: "Unavailable"
    } catch (e: Exception) {
        "Unavailable"
    }
}

