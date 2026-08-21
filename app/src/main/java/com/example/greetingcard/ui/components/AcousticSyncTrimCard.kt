package com.example.greetingcard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.greetingcard.ui.theme.*
import java.util.Locale

@Composable
fun AcousticSyncTrimCard(
    manualTrimMs: Float,
    hostDeviceId: String?,
    onTrimChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PlumSurface),
        border = BorderStroke(1.dp, PinkPrimary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Acoustic Sync Trim",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Compensates speaker DSP & Wi-Fi asymmetry",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }

                val formattedTrim = when {
                    manualTrimMs > 0.0f -> "+%.1f ms".format(Locale.US, manualTrimMs)
                    manualTrimMs < 0.0f -> "%.1f ms".format(Locale.US, manualTrimMs)
                    else -> "0.0 ms"
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PlumSurfaceVariant
                ) {
                    Text(
                        text = formattedTrim,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PinkPrimary
                    )
                }
            }

            // Slider from -10.0 ms to +10.0 ms with 39 steps (0.5 ms increments)
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = manualTrimMs,
                    onValueChange = { onTrimChange(it) },
                    valueRange = -10.0f..10.0f,
                    steps = 39,
                    colors = SliderDefaults.colors(
                        thumbColor = PinkPrimary,
                        activeTrackColor = PinkPrimary,
                        inactiveTrackColor = PlumSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("-10.0 ms (Delay)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text("0.0 ms", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    Text("+10.0 ms (Advance)", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }

            // Quick Nudge and Reset Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onTrimChange(manualTrimMs - 0.5f) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, PinkPrimary.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text("-0.5ms", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = { onTrimChange(0.0f) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PinkPrimary,
                        contentColor = PinkOnPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text("Reset", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onTrimChange(manualTrimMs + 0.5f) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, PinkPrimary.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text("+0.5ms", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = PlumSurfaceVariant)

            // Host Info Section
            Text(
                text = if (hostDeviceId != null) "Host ID: ${hostDeviceId.take(8)}…" else "Host: Default",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

