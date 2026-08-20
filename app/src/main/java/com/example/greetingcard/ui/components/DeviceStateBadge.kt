package com.example.greetingcard.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greetingcard.network.DeviceState

@Composable
fun DeviceStateBadge(
    state: DeviceState,
    modifier: Modifier = Modifier,
    pingMs: Long = 0L,
) {
    val (backgroundColor, contentColor, label, icon) = when (state) {
        DeviceState.DISCOVERED -> StateConfig(
            bgColor = Color(0xFFE0E0E0),
            fgColor = Color(0xFF424242),
            label = "Discovered",
            icon = "🔍"
        )
        DeviceState.CONNECTING -> StateConfig(
            bgColor = Color(0xFFE3F2FD),
            fgColor = Color(0xFF1565C0),
            label = "Connecting…",
            icon = "📡"
        )
        DeviceState.AUTHENTICATING -> StateConfig(
            bgColor = Color(0xFFFFF8E1),
            fgColor = Color(0xFFF57F17),
            label = "Authenticating…",
            icon = "🔐"
        )
        DeviceState.CONNECTED -> StateConfig(
            bgColor = Color(0xE8E8F5E9),
            fgColor = Color(0xFF2E7D32),
            label = "Connected",
            icon = "🟢"
        )
        DeviceState.BUFFERING -> StateConfig(
            bgColor = Color(0xFFF3E5F5),
            fgColor = Color(0xFF6A1B9A),
            label = "Buffering…",
            icon = "⏳"
        )
        DeviceState.READY -> StateConfig(
            bgColor = Color(0xFFE0F2F1),
            fgColor = Color(0xFF00695C),
            label = "Ready",
            icon = "✨"
        )
        DeviceState.PLAYING -> StateConfig(
            bgColor = Color(0xFFDCEDC8),
            fgColor = Color(0xFF33691E),
            label = "Playing",
            icon = "🎵"
        )
        DeviceState.DEGRADED -> StateConfig(
            bgColor = Color(0xFFFFF3E0),
            fgColor = Color(0xFFE65100),
            label = if (pingMs > 0) "Weak ($pingMs ms)" else "Weak Connection",
            icon = "⚠️"
        )
        DeviceState.RECONNECTING -> StateConfig(
            bgColor = Color(0xFFFFFDE7),
            fgColor = Color(0xFFF57F17),
            label = "Reconnecting…",
            icon = "🔄"
        )
        DeviceState.DISCONNECTED -> StateConfig(
            bgColor = Color(0xFFECEFF1),
            fgColor = Color(0xFF546E7A),
            label = "Disconnected",
            icon = "⚪"
        )
        DeviceState.FAILED -> StateConfig(
            bgColor = Color(0xFFFFEBEE),
            fgColor = Color(0xFFC62828),
            label = "Connection Failed",
            icon = "❌"
        )
    }

    val animatedBg by animateColorAsState(targetValue = backgroundColor, label = "badgeBg")
    val animatedFg by animateColorAsState(targetValue = contentColor, label = "badgeFg")

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = animatedBg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state.isInProgress) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = animatedFg,
                    strokeWidth = 2.dp
                )
            } else {
                Text(text = icon, fontSize = 12.sp)
            }

            Text(
                text = label,
                color = animatedFg,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

private data class StateConfig(
    val bgColor: Color,
    val fgColor: Color,
    val label: String,
    val icon: String,
)

