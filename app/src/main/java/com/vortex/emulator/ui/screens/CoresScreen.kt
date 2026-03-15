package com.vortex.emulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.core.CoreFeature
import com.vortex.emulator.core.CoreInfo
import com.vortex.emulator.ui.components.VortexHeader
import com.vortex.emulator.ui.components.toColor
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.CoresViewModel

@Composable
fun CoresScreen(
    viewModel: CoresViewModel = hiltViewModel()
) {
    val installedCores by viewModel.installedCores.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        VortexHeader(
            title = "Emulation Cores",
            subtitle = "Manage your emulation engines"
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "INSTALLED",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = VortexGreen,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(installedCores, key = { it.id }) { core ->
                CoreCard(core = core)
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoreCard(
    core: CoreInfo
) {
    val primaryPlatform = core.supportedPlatforms.firstOrNull()
    val platformColor = primaryPlatform?.toColor() ?: VortexCyan

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Core icon
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(platformColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Memory,
                        contentDescription = null,
                        tint = platformColor,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = core.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Text(
                        text = "v${core.version} • ${core.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = VortexGreen.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Installed",
                        color = VortexGreen,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = core.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Feature chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                core.features.forEach { feature ->
                    FeatureChip(feature = feature)
                }
            }

            // Platforms
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                core.supportedPlatforms.forEach { platform ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = platform.toColor().copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = platform.abbreviation,
                            style = MaterialTheme.typography.labelSmall,
                            color = platform.toColor(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FeatureChip(feature: CoreFeature) {
    val (icon, label) = when (feature) {
        CoreFeature.SAVE_STATES -> Icons.Filled.Save to "Save States"
        CoreFeature.REWIND -> Icons.Filled.FastRewind to "Rewind"
        CoreFeature.FAST_FORWARD -> Icons.Filled.FastForward to "Fast Fwd"
        CoreFeature.CHEATS -> Icons.Filled.Code to "Cheats"
        CoreFeature.NETPLAY -> Icons.Filled.Wifi to "Netplay"
        CoreFeature.SHADERS -> Icons.Filled.AutoAwesome to "Shaders"
        CoreFeature.RUMBLE -> Icons.Filled.Vibration to "Rumble"
        CoreFeature.ANALOG_STICK -> Icons.Filled.Gamepad to "Analog"
        CoreFeature.TOUCH_OVERLAY -> Icons.Filled.TouchApp to "Touch"
        CoreFeature.HIGH_RESOLUTION -> Icons.Filled.HighQuality to "Hi-Res"
        CoreFeature.WIDESCREEN_HACK -> Icons.Filled.AspectRatio to "Widescreen"
        CoreFeature.VULKAN_RENDERER -> Icons.Filled.Bolt to "Vulkan"
        CoreFeature.OPENGL_RENDERER -> Icons.Filled.Layers to "OpenGL"
    } as Pair<ImageVector, String>

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
