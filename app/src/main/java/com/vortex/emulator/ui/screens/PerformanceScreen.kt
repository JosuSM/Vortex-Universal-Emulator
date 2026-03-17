package com.vortex.emulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.gpu.ChipsetTier
import com.vortex.emulator.ui.components.VortexHeader
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.PerformanceViewModel

@Composable
fun PerformanceScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: PerformanceViewModel = hiltViewModel()
) {
    val chipsetInfo by viewModel.chipsetInfo.collectAsState()
    val recommendation by viewModel.recommendation.collectAsState()
    val drivers by viewModel.drivers.collectAsState()
    val activeDriver by viewModel.activeDriver.collectAsState()
    val coreProfiles by viewModel.coreProfiles.collectAsState()
    var saveConfirmed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        VortexHeader(
            title = "Device Profile",
            subtitle = "Hardware detection & optimization"
        )

        Spacer(modifier = Modifier.height(16.dp))

        chipsetInfo?.let { info ->
            // Chipset Tier card
            val tierColor = when (info.chipsetTier) {
                ChipsetTier.FLAGSHIP -> VortexCyan
                ChipsetTier.HIGH_END -> VortexGreen
                ChipsetTier.MID_RANGE -> VortexOrange
                ChipsetTier.ENTRY_LEVEL -> VortexOrange
                ChipsetTier.LOW_END -> VortexRed
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    tierColor.copy(alpha = 0.12f),
                                    MaterialTheme.colorScheme.surfaceContainer
                                ),
                                start = Offset(0f, 0f),
                                end = Offset(600f, 300f)
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Bolt,
                                contentDescription = null,
                                tint = tierColor,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = info.chipsetTier.displayName,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black,
                                    color = tierColor
                                )
                                Text(
                                    text = info.socName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Hardware specs grid
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SpecItem(
                                icon = Icons.Filled.DeveloperBoard,
                                label = "CPU",
                                value = "${info.cpuCores} cores @ ${info.cpuMaxFreqMhz}MHz"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SpecItem(
                                icon = Icons.Filled.Memory,
                                label = "RAM",
                                value = "${info.totalRamMb} MB"
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            SpecItem(
                                icon = Icons.Filled.Architecture,
                                label = "Arch",
                                value = info.cpuArchitecture
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SpecItem(
                                icon = Icons.Filled.Layers,
                                label = "ABIs",
                                value = info.abis.joinToString(", ")
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // GPU Info
            info.gpuInfo?.let { gpu ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Videocam,
                                contentDescription = null,
                                tint = VortexPurple,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "GPU",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        GpuInfoRow("Renderer", gpu.renderer)
                        GpuInfoRow("Vendor", gpu.vendor)
                        GpuInfoRow("OpenGL ES", gpu.glVersion)
                        GpuInfoRow("Max Texture", "${gpu.maxTextureSize}x${gpu.maxTextureSize}")
                        GpuInfoRow("Est. VRAM", "${gpu.estimatedVramMb} MB")

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (gpu.supportsVulkan) {
                                ApiBadge("Vulkan ${gpu.vulkanVersionMajor}.${gpu.vulkanVersionMinor}", VortexCyan)
                            }
                            if (gpu.supportsOpenGLES32) {
                                ApiBadge("OpenGL ES 3.2", VortexGreen)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Device Info
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = VortexCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Device",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val dev = info.deviceInfo
                    GpuInfoRow("Model", "${dev.manufacturer} ${dev.model}")
                    GpuInfoRow("Brand", dev.brand.replaceFirstChar { it.uppercase() })
                    GpuInfoRow("Codename", dev.device)
                    GpuInfoRow("Android", "${dev.androidVersion} (SDK ${dev.sdkVersion})")
                    GpuInfoRow("Security", dev.securityPatch)
                    GpuInfoRow("Display", "${dev.screenWidthPx}x${dev.screenHeightPx} @ ${dev.displayDensityDpi}dpi")
                    GpuInfoRow("GPU Driver", dev.gpuDriverVersion)

                    Spacer(modifier = Modifier.height(8.dp))

                    ApiBadge(dev.deviceCategory.displayName, VortexCyan)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Per-Core Performance Profiles
            if (coreProfiles.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Tune,
                                contentDescription = null,
                                tint = VortexOrange,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Per-Core Profiles",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Tuned for your ${info.deviceInfo.deviceCategory.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        coreProfiles.forEach { profile ->
                            CoreProfileCard(profile)
                            if (profile != coreProfiles.last()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Recommendations
            recommendation?.let { rec ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.AutoFixHigh,
                                contentDescription = null,
                                tint = VortexGreen,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Recommended Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = rec.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        SettingRow("Resolution", "${rec.maxResolutionMultiplier}x Native")
                        SettingRow("Vulkan", if (rec.enableVulkan) "Enabled" else "Disabled")
                        SettingRow("Widescreen", if (rec.enableWidescreen) "Enabled" else "Disabled")
                        SettingRow("Rewind", if (rec.enableRewind) "Enabled" else "Disabled")
                        SettingRow("Max Fast-Forward", "${rec.maxFastForwardSpeed}x")
                        SettingRow("Heavy Cores (GCN/Wii)", if (rec.supportedHeavyCores) "Supported" else "Not recommended")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // GPU Drivers
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.SettingsApplications,
                            contentDescription = null,
                            tint = VortexMagenta,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GPU Drivers",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (drivers.isEmpty()) {
                        Text(
                            text = "No compatible drivers detected for this GPU.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        drivers.forEach { driver ->
                            DriverRow(
                                driver = driver,
                                isActive = driver.id == activeDriver?.id,
                                onActivate = { viewModel.activateDriver(driver) }
                            )
                            if (driver != drivers.last()) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action buttons — Save Changes & Exit to Main Menu
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onNavigateBack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Main Menu", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = {
                    viewModel.saveDriverSelection()
                    saveConfirmed = true
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VortexGreen
                )
            ) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (saveConfirmed) "Saved ✓" else "Save Changes",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.surface
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SpecItem(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GpuInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}

@Composable
fun ApiBadge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DriverRow(
    driver: com.vortex.emulator.gpu.DriverInfo,
    isActive: Boolean,
    onActivate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = driver.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = "v${driver.version} • ${driver.vendor} • ${driver.targetGpu}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }

        if (isActive) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = VortexGreen.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "Active",
                    color = VortexGreen,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        } else {
            OutlinedButton(
                onClick = onActivate,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Use", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun CoreProfileCard(profile: com.vortex.emulator.gpu.CorePerformanceProfile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = profile.coreName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ApiBadge("${profile.resolutionMultiplier}x Res", VortexCyan)
            if (profile.useHardwareRenderer)
                ApiBadge("HW Render", VortexGreen)
            if (profile.frameSkip > 0)
                ApiBadge("Skip ${profile.frameSkip}", VortexOrange)
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = profile.notes,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
