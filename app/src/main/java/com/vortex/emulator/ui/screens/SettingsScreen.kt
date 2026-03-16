package com.vortex.emulator.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.ui.components.VortexHeader
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.CoresViewModel

@Composable
fun SettingsScreen(
    onNavigateToChangelog: () -> Unit = {},
    onNavigateToPatcher: () -> Unit = {},
    coresViewModel: CoresViewModel = hiltViewModel()
) {
    var vulkanEnabled by remember { mutableStateOf(true) }
    var rewindEnabled by remember { mutableStateOf(false) }
    var fastForwardSpeed by remember { mutableFloatStateOf(2f) }
    var resolutionMultiplier by remember { mutableFloatStateOf(2f) }
    var hapticFeedback by remember { mutableStateOf(true) }
    var showFps by remember { mutableStateOf(true) }
    var autoSaveState by remember { mutableStateOf(true) }
    var bilinearFiltering by remember { mutableStateOf(true) }

    val isBatchDownloading by coresViewModel.isBatchDownloading.collectAsState()
    val batchProgress by coresViewModel.batchProgress.collectAsState()
    val batchCurrentCore by coresViewModel.batchCurrentCore.collectAsState()
    val batchResult by coresViewModel.batchResult.collectAsState()
    val installedCores by coresViewModel.installedCores.collectAsState()
    val availableCores by coresViewModel.availableCores.collectAsState()
    val isOffline = coresViewModel.isOffline()
    val cacheSizeMb = coresViewModel.getCacheSizeMb()
    val installedCount = installedCores.count { it.isInstalled }
    val totalCount = availableCores.size
    var showClearCacheDialog by remember { mutableStateOf(false) }

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

        // Storage & Offline Section
        SettingSectionHeader(icon = Icons.Filled.CloudDownload, title = "Storage & Offline")

        // Offline readiness status
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (installedCount == totalCount) Icons.Filled.CloudDone else Icons.Filled.CloudQueue,
                            contentDescription = null,
                            tint = if (installedCount == totalCount) VortexGreen else VortexCyan,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (installedCount == totalCount) "Fully Offline Ready"
                                   else "$installedCount of $totalCount cores downloaded",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "${String.format("%.1f", cacheSizeMb)} MB",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val animatedProgress by animateFloatAsState(
                    targetValue = if (totalCount > 0) installedCount.toFloat() / totalCount else 0f,
                    label = "offlineProgress"
                )
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    color = if (installedCount == totalCount) VortexGreen else VortexCyan,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }

        // Download All button
        if (isBatchDownloading) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = VortexCyan.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { coresViewModel.downloadAllCores() },
                    enabled = !isOffline && installedCount < totalCount,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VortexCyan,
                        disabledContainerColor = VortexCyan.copy(alpha = 0.3f)
                    )
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (installedCount == totalCount) "All Downloaded" else "Download All for Offline",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    enabled = installedCount > 0,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = VortexRed
                    )
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        batchResult?.let { result ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = VortexGreen.copy(alpha = 0.08f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = VortexGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = VortexGreen
                        )
                    }
                    IconButton(
                        onClick = { coresViewModel.clearBatchResult() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        if (isOffline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.WifiOff,
                    contentDescription = null,
                    tint = VortexOrange,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "You're offline — download requires internet connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexOrange.copy(alpha = 0.7f)
                )
            }
        }

        // Clear cache confirmation dialog
        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = VortexRed) },
                title = { Text("Clear Core Cache") },
                text = {
                    Text("Delete all $installedCount downloaded cores (${String.format("%.1f", cacheSizeMb)} MB)? You'll need to re-download them to play.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            coresViewModel.clearCache()
                            showClearCacheDialog = false
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = VortexRed)
                    ) {
                        Text("Clear", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearCacheDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )

        // Tools Section
        SettingSectionHeader(icon = Icons.Filled.Build, title = "Tools")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .clickable(onClick = onNavigateToPatcher),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(VortexPurple.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AutoFixHigh,
                        contentDescription = null,
                        tint = VortexPurple,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ROM Patcher",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Patch ROMs with IPS, UPS, BPS, xdelta, PPF, APS",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

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
                    text = "Version 2.1-Galaxy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A next-generation emulator built for performance, compatibility, and style. Powered by the best open-source emulation cores.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onNavigateToChangelog,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VortexCyan),
                    border = androidx.compose.foundation.BorderStroke(1.dp, VortexCyan.copy(alpha = 0.5f))
                ) {
                    Icon(
                        Icons.Filled.NewReleases,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("What's New", fontWeight = FontWeight.SemiBold)
                }
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
