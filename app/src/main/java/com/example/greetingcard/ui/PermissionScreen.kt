package com.example.greetingcard.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// The permissions we need to request at runtime, grouped by API level.
private fun requiredPermissions(): List<String> = buildList {
    // Always needed
    add(Manifest.permission.ACCESS_FINE_LOCATION)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // API 33+ — replaces location for Wi-Fi scan
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}

private fun friendlyName(permission: String): String = when (permission) {
    Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location"
    Manifest.permission.NEARBY_WIFI_DEVICES  -> "Nearby Wi-Fi Devices"
    Manifest.permission.BLUETOOTH_SCAN       -> "Bluetooth Scan"
    Manifest.permission.BLUETOOTH_CONNECT    -> "Bluetooth Connect"
    Manifest.permission.BLUETOOTH_ADVERTISE  -> "Bluetooth Advertise"
    else -> permission.substringAfterLast('.')
}

@Composable
fun PermissionScreen(onAllGranted: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val needed = remember { requiredPermissions() }

    // Helper function to check if a permission is currently granted by OS
    fun isGranted(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    // Track granted state for each permission, checking system state on initial render.
    val grantedMap = remember {
        mutableStateMapOf<String, Boolean>().apply {
            needed.forEach { perm -> put(perm, isGranted(perm)) }
        }
    }

    // Function to refresh state against OS
    fun refreshPermissions() {
        needed.forEach { perm ->
            grantedMap[perm] = isGranted(perm)
        }
    }

    // Re-check permissions when activity resumes (e.g. after coming back from Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Whether any permission was permanently denied (user chose "Don't ask again").
    var anyPermanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results.forEach { (perm, granted) -> grantedMap[perm] = granted }
        anyPermanentlyDenied = results.any { (_, granted) -> !granted }
    }

    val allGranted = grantedMap.values.all { it }

    // Auto-proceed once granted.
    LaunchedEffect(allGranted) {
        if (allGranted) onAllGranted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "🔐",
            fontSize = 56.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Permissions Required",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "SyncCast needs the following permissions to discover and connect to nearby devices over Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(28.dp))

        // Permission status cards
        needed.forEach { perm ->
            val granted = grantedMap[perm] == true
            PermissionRow(name = friendlyName(perm), granted = granted)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(24.dp))

        // Permanently-denied warning + settings button
        AnimatedVisibility(visible = anyPermanentlyDenied && !allGranted) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "One or more permissions were denied. Please grant them in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        )
                    }
                ) {
                    Text("Open Settings")
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        Button(
            onClick = { launcher.launch(needed.toTypedArray()) },
            enabled = !allGranted,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = if (allGranted) "All Granted ✓" else "Grant Permissions",
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PermissionRow(name: String, granted: Boolean) {
    val bg = if (granted)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val textColor = if (granted)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium, color = textColor)
        Text(
            text = if (granted) "✓ Granted" else "✗ Needed",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

