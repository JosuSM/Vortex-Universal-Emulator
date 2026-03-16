package com.vortex.emulator.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
    val availableCores by viewModel.availableCores.collectAsState()
    val isOffline = viewModel.isOffline()
    val cacheSizeMb = viewModel.getCacheSizeMb()
    val isBatchDownloading by viewModel.isBatchDownloading.collectAsState()
    val batchProgress by viewModel.batchProgress.collectAsState()
    val batchCurrentCore by viewModel.batchCurrentCore.collectAsState()
    val downloadingCoreId by viewModel.downloadingCoreId.collectAsState()

    // Separate installed vs not-installed
    val installed = installedCores.filter { it.isInstalled }
    val notInstalled = availableCores.filter { core -> !installedCores.any { it.id == core.id && it.isInstalled } }

    Column(modifier = Modifier.fillMaxSize()) {
        VortexHeader(
            title = "Emulation Cores",
            subtitle = "Manage your emulation engines"
        )

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Offline banner
            if (isOffline) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = VortexOrange.copy(alpha = 0.12f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Filled.WifiOff,
                                contentDescription = null,
                                tint = VortexOrange,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "Offline Mode",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = VortexOrange
                                )
                                Text(
                                    text = "${installed.size} cores ready • ${String.format("%.1f", cacheSizeMb)} MB cached",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VortexOrange.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            // Download All / Batch progress
            if (notInstalled.isNotEmpty() && !isOffline) {
                item {
                    if (isBatchDownloading) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = VortexCyan.copy(alpha = 0.08f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Downloading cores…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = VortexCyan
                                    )
                                    Text(
                                        text = "${(batchProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = VortexCyan,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (batchCurrentCore.isNotEmpty()) {
                                    Text(
                                        text = batchCurrentCore,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { batchProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    color = VortexCyan,
                                    trackColor = VortexCyan.copy(alpha = 0.15f),
                                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { viewModel.downloadAllCores() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VortexCyan)
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Download All ${notInstalled.size} Cores for Offline",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "INSTALLED (${installed.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = VortexGreen,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (installed.isEmpty()) {
                item {
                    Text(
                        text = "No cores downloaded yet. Play a game to auto-download its core.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }

            items(installed, key = { it.id }) { core ->
                CoreCard(
                    core = core,
                    isDownloading = downloadingCoreId == core.id,
                    onDownload = { viewModel.downloadCore(core) },
                    onDelete = { viewModel.deleteCore(core) },
                    isOffline = isOffline
                )
            }

            if (notInstalled.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "AVAILABLE (${notInstalled.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isOffline) VortexOrange.copy(alpha = 0.5f) else VortexCyan,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                items(notInstalled, key = { it.id }) { core ->
                    CoreCard(
                        core = core,
                        isDownloading = downloadingCoreId == core.id,
                        onDownload = { viewModel.downloadCore(core) },
                        onDelete = { viewModel.deleteCore(core) },
                        isOffline = isOffline
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoreCard(
    core: CoreInfo,
    isDownloading: Boolean = false,
    onDownload: () -> Unit = {},
    onDelete: () -> Unit = {},
    isOffline: Boolean = false
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

                // Status badge + action
                if (core.isInstalled) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete core",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = VortexCyan
                    )
                } else {
                    FilledIconButton(
                        onClick = onDownload,
                        enabled = !isOffline,
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = VortexCyan.copy(alpha = 0.15f),
                            contentColor = VortexCyan,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = "Download core",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = core.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Size info for not-installed cores
            if (!core.isInstalled && core.downloadSizeMb > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "~${String.format("%.1f", core.downloadSizeMb)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

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
