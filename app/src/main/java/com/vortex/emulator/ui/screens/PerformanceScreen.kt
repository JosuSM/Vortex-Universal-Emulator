package com.vortex.emulator.ui.screens

import androidx.compose.foundation.background
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
    viewModel: PerformanceViewModel = hiltViewModel()
) {
    val chipsetInfo by viewModel.chipsetInfo.collectAsState()
    val recommendation by viewModel.recommendation.collectAsState()
    val drivers by viewModel.drivers.collectAsState()
    val activeDriver by viewModel.activeDriver.collectAsState()

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
            if (drivers.isNotEmpty()) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
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
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
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
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "v${driver.version} • ${driver.vendor} • ${driver.targetGpu}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
