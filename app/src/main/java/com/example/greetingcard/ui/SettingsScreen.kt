package com.example.greetingcard.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greetingcard.BuildConfig
import com.example.greetingcard.ui.theme.*

@Composable
fun SettingsScreen(
    isDeveloperMode: Boolean,
    onDeveloperModeChange: (Boolean) -> Unit,
    selectedAppearance: String,
    onAppearanceChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val devCardBg by animateColorAsState(
        targetValue = if (isDeveloperMode) CaptureActivePurple else PlumSurfaceVariant,
        label = "devCardBg"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PlumBackground)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Bar: Close 'X' and Centered Title
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Settings",
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Spacer(Modifier.height(32.dp))

        // Appearance Section
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            fontSize = 15.sp
        )

        Spacer(Modifier.height(16.dp))

        val appearanceOptions = listOf("System default", "Light", "Dark")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            appearanceOptions.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAppearanceChange(option) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedAppearance == option,
                        onClick = { onAppearanceChange(option) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = PinkPrimary,
                            unselectedColor = TextSecondary.copy(alpha = 0.7f)
                        )
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                        fontSize = 16.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(36.dp))

        // Developer Mode Toggle Card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = devCardBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Developer mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    fontSize = 16.sp
                )

                Switch(
                    checked = isDeveloperMode,
                    onCheckedChange = onDeveloperModeChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4A144A),
                        uncheckedThumbColor = Color(0xFFD0C0D0),
                        uncheckedTrackColor = Color(0xFF271A27)
                    )
                )
            }
        }

        Spacer(Modifier.height(48.dp))

        // App Info Section
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "App version",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontSize = 15.sp
                )
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontSize = 15.sp
                )
            }
        }
    }
}

