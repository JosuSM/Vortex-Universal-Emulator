package com.vortex.emulator.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vortex.emulator.ui.components.VortexHeader
import com.vortex.emulator.ui.theme.*

@Composable
fun SettingsScreen() {
    var vulkanEnabled by remember { mutableStateOf(true) }
    var rewindEnabled by remember { mutableStateOf(false) }
    var fastForwardSpeed by remember { mutableFloatStateOf(2f) }
    var resolutionMultiplier by remember { mutableFloatStateOf(2f) }
    var hapticFeedback by remember { mutableStateOf(true) }
    var showFps by remember { mutableStateOf(true) }
    var autoSaveState by remember { mutableStateOf(true) }
    var bilinearFiltering by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        VortexHeader(
            title = "Settings",
            subtitle = "Configure your experience"
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Graphics Section
        SettingSectionHeader(icon = Icons.Filled.Videocam, title = "Graphics")

        SettingSwitch(
            title = "Vulkan Renderer",
            description = "Use Vulkan API for better performance (when available)",
            checked = vulkanEnabled,
            onCheckedChange = { vulkanEnabled = it }
        )

        SettingSlider(
            title = "Internal Resolution",
            description = "${resolutionMultiplier.toInt()}x Native",
            value = resolutionMultiplier,
            valueRange = 1f..5f,
            steps = 3,
            onValueChange = { resolutionMultiplier = it }
        )

        SettingSwitch(
            title = "Bilinear Filtering",
            description = "Smooth texture filtering for retro games",
            checked = bilinearFiltering,
            onCheckedChange = { bilinearFiltering = it }
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )

        // Emulation Section
        SettingSectionHeader(icon = Icons.Filled.Speed, title = "Emulation")

        SettingSwitch(
            title = "Rewind",
            description = "Record frames for rewind (uses more memory)",
            checked = rewindEnabled,
            onCheckedChange = { rewindEnabled = it }
        )

        SettingSlider(
            title = "Fast Forward Speed",
            description = "${fastForwardSpeed.toInt()}x",
            value = fastForwardSpeed,
            valueRange = 1f..8f,
            steps = 6,
            onValueChange = { fastForwardSpeed = it }
        )

        SettingSwitch(
            title = "Auto Save State",
            description = "Save progress when leaving a game",
            checked = autoSaveState,
            onCheckedChange = { autoSaveState = it }
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )

        // Interface Section
        SettingSectionHeader(icon = Icons.Filled.DesignServices, title = "Interface")

        SettingSwitch(
            title = "Show FPS Counter",
            description = "Display frame rate during emulation",
            checked = showFps,
            onCheckedChange = { showFps = it }
        )

        SettingSwitch(
            title = "Haptic Feedback",
            description = "Vibrate on button press in on-screen controls",
            checked = hapticFeedback,
            onCheckedChange = { hapticFeedback = it }
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )

        // About Section
        SettingSectionHeader(icon = Icons.Filled.Info, title = "About")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Vortex Emulator",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = VortexCyan
                )
                Text(
                    text = "Version 1.0.0-alpha",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A next-generation emulator built for performance, compatibility, and style. Powered by the best open-source emulation cores.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SettingSectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = VortexCyan,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = VortexCyan
        )
    }
}

@Composable
fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = VortexCyan,
                checkedTrackColor = VortexCyan.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
fun SettingSlider(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = VortexCyan,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = VortexCyan,
                activeTrackColor = VortexCyan,
                inactiveTrackColor = VortexCyan.copy(alpha = 0.2f)
            )
        )
    }
}
