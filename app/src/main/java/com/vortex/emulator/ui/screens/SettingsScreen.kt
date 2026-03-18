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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vortex.emulator.emulation.FrontendBridge
import com.vortex.emulator.emulation.FrontendType
import com.vortex.emulator.ui.components.VortexHeader
import com.vortex.emulator.ui.theme.*
import com.vortex.emulator.ui.viewmodel.CoresViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun frontendBridge(): FrontendBridge
}

@Composable
fun SettingsScreen(
    onNavigateToChangelog: () -> Unit = {},
    onNavigateToPatcher: () -> Unit = {},
    coresViewModel: CoresViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val frontendBridge = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, SettingsEntryPoint::class.java)
            .frontendBridge()
    }
    var selectedFrontend by remember { mutableStateOf(frontendBridge.activeFrontend) }
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

        // Graphics Section (per-core)
        SettingSectionHeader(icon = Icons.Filled.Videocam, title = "Graphics")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = VortexCyan.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Graphics, resolution, backend and rendering settings are now configured per core. Long press any core in the Cores tab to access its settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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

        // Frontend Engine Section (moved to per-core settings)
        SettingSectionHeader(icon = Icons.Filled.Memory, title = "Frontend Engine")

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Global default: ${frontendBridge.activeFrontend.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose which native frontend runs emulation cores. " +
                           "Each core can override this in its own settings (long press a core).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                FrontendType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedFrontend = type
                                frontendBridge.activeFrontend = type
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedFrontend == type,
                            onClick = {
                                selectedFrontend = type
                                frontendBridge.activeFrontend = type
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = type.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = type.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

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
                    text = "Version 2.2-Nova",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "A next-generation emulator built for performance, compatibility, and style. Powered by the best open-source emulation cores with per-core advanced settings, automatic standalone emulator detection, and built-in multiplayer.",
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

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )

        // Open Source Licenses Section
        SettingSectionHeader(icon = Icons.Filled.Gavel, title = "Open Source Licenses")

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
                    text = "Vortex Emulator is built on the following open-source projects. " +
                        "We are grateful to all authors and contributors.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                // ── Framework ───────────────────────────────
                LicenseGroupHeader(title = "Framework")
                LicenseEntry(
                    name = "libretro / RetroArch",
                    author = "libretro Team",
                    license = "GPL v3+",
                    url = "github.com/libretro"
                )

                // ── PSP ─────────────────────────────────────
                LicenseGroupHeader(title = "PSP")
                LicenseEntry(
                    name = "PPSSPP",
                    author = "Henrik Rydgård",
                    license = "GPL v2+",
                    url = "github.com/hrydgard/ppsspp"
                )
                LicenseEntry(
                    name = "Rocket PSP Engine",
                    author = "Vortex Team (based on PPSSPP)",
                    license = "GPL v2+",
                    url = "github.com/hrydgard/ppsspp"
                )

                // ── NES ─────────────────────────────────────
                LicenseGroupHeader(title = "NES / Famicom")
                LicenseEntry(
                    name = "FCEUmm",
                    author = "FCEUmm Team",
                    license = "GPL v2+",
                    url = "github.com/libretro/libretro-fceumm"
                )
                LicenseEntry(
                    name = "Mesen",
                    author = "Sour",
                    license = "GPL v2+",
                    url = "github.com/SourMesen/Mesen2"
                )
                LicenseEntry(
                    name = "Nestopia UE",
                    author = "Libretro",
                    license = "GPL v2+",
                    url = "github.com/libretro/nestopia"
                )

                // ── SNES ────────────────────────────────────
                LicenseGroupHeader(title = "SNES / Super Famicom")
                LicenseEntry(
                    name = "Snes9x",
                    author = "Snes9x Team",
                    license = "Snes9x License",
                    url = "github.com/snes9xgit/snes9x"
                )
                LicenseEntry(
                    name = "bsnes",
                    author = "Near / Screwtape",
                    license = "GPL v3",
                    url = "github.com/libretro/bsnes-libretro"
                )

                // ── N64 ─────────────────────────────────────
                LicenseGroupHeader(title = "Nintendo 64")
                LicenseEntry(
                    name = "Mupen64Plus-Next",
                    author = "Mupen64Plus Team",
                    license = "GPL v2+",
                    url = "github.com/libretro/mupen64plus-libretro-nx"
                )
                LicenseEntry(
                    name = "ParaLLEl N64",
                    author = "Libretro Team",
                    license = "GPL v2+",
                    url = "github.com/libretro/parallel-n64"
                )

                // ── GB / GBC / GBA ──────────────────────────
                LicenseGroupHeader(title = "Game Boy / GBA")
                LicenseEntry(
                    name = "mGBA",
                    author = "endrift",
                    license = "MPL 2.0",
                    url = "github.com/mgba-emu/mgba"
                )
                LicenseEntry(
                    name = "Gambatte",
                    author = "sinamas",
                    license = "GPL v2",
                    url = "github.com/libretro/gambatte-libretro"
                )
                LicenseEntry(
                    name = "SameBoy",
                    author = "LIJI",
                    license = "MIT",
                    url = "github.com/LIJI32/SameBoy"
                )
                LicenseEntry(
                    name = "VBA-M",
                    author = "VBA-M Team",
                    license = "GPL v2+",
                    url = "github.com/visualboyadvance-m/visualboyadvance-m"
                )

                // ── NDS ─────────────────────────────────────
                LicenseGroupHeader(title = "Nintendo DS")
                LicenseEntry(
                    name = "melonDS",
                    author = "Arisotura",
                    license = "GPL v3",
                    url = "github.com/melonDS-emu/melonDS"
                )
                LicenseEntry(
                    name = "DeSmuME",
                    author = "DeSmuME Team",
                    license = "GPL v2+",
                    url = "github.com/TASEmulators/desmume"
                )

                // ── PSX ─────────────────────────────────────
                LicenseGroupHeader(title = "PlayStation")
                LicenseEntry(
                    name = "SwanStation",
                    author = "stenzek",
                    license = "GPL v3",
                    url = "github.com/libretro/swanstation"
                )
                LicenseEntry(
                    name = "Beetle PSX HW",
                    author = "Mednafen / Libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/beetle-psx-libretro"
                )
                LicenseEntry(
                    name = "PCSX ReARMed",
                    author = "notaz / libretro",
                    license = "GPL v2+",
                    url = "github.com/libretro/pcsx_rearmed"
                )

                // ── PS2 ─────────────────────────────────────
                LicenseGroupHeader(title = "PlayStation 2")
                LicenseEntry(
                    name = "Play!",
                    author = "Jean-Philip Desjardins",
                    license = "MIT",
                    url = "github.com/jpd002/Play-"
                )

                // ── Dreamcast ───────────────────────────────
                LicenseGroupHeader(title = "Dreamcast")
                LicenseEntry(
                    name = "Flycast",
                    author = "Flycast Team",
                    license = "GPL v2",
                    url = "github.com/flyinghead/flycast"
                )

                // ── Saturn ──────────────────────────────────
                LicenseGroupHeader(title = "Saturn")
                LicenseEntry(
                    name = "Beetle Saturn",
                    author = "Mednafen",
                    license = "GPL v2",
                    url = "github.com/libretro/beetle-saturn-libretro"
                )
                LicenseEntry(
                    name = "Yabause",
                    author = "Yabause Team",
                    license = "GPL v2",
                    url = "github.com/libretro/yabause"
                )

                // ── GameCube / Wii ──────────────────────────
                LicenseGroupHeader(title = "GameCube / Wii")
                LicenseEntry(
                    name = "Dolphin",
                    author = "Dolphin Team",
                    license = "GPL v2+",
                    url = "github.com/dolphin-emu/dolphin"
                )

                // ── 3DS ─────────────────────────────────────
                LicenseGroupHeader(title = "Nintendo 3DS")
                LicenseEntry(
                    name = "Citra",
                    author = "Citra Team",
                    license = "GPL v2+",
                    url = "github.com/citra-emu/citra"
                )
                LicenseEntry(
                    name = "Panda3DS",
                    author = "Panda3DS Team",
                    license = "GPL v3",
                    url = "github.com/wheremyfoodat/Panda3DS"
                )

                // ── Genesis / Mega Drive ────────────────────
                LicenseGroupHeader(title = "Genesis / Mega Drive")
                LicenseEntry(
                    name = "Genesis Plus GX",
                    author = "ekeeke",
                    license = "Non-commercial",
                    url = "github.com/libretro/Genesis-Plus-GX"
                )
                LicenseEntry(
                    name = "PicoDrive",
                    author = "notaz",
                    license = "MAME-like",
                    url = "github.com/libretro/picodrive"
                )

                // ── Arcade ──────────────────────────────────
                LicenseGroupHeader(title = "Arcade")
                LicenseEntry(
                    name = "FinalBurn Neo",
                    author = "FBNeo Team",
                    license = "Non-commercial",
                    url = "github.com/libretro/FBNeo"
                )
                LicenseEntry(
                    name = "MAME 2003-Plus",
                    author = "Libretro Team",
                    license = "MAME License",
                    url = "github.com/libretro/mame2003-plus-libretro"
                )

                // ── PC Engine ───────────────────────────────
                LicenseGroupHeader(title = "PC Engine / TurboGrafx-16")
                LicenseEntry(
                    name = "Beetle PCE Fast",
                    author = "Mednafen / libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/beetle-pce-fast-libretro"
                )

                // ── Atari ───────────────────────────────────
                LicenseGroupHeader(title = "Atari")
                LicenseEntry(
                    name = "Stella 2014",
                    author = "Stephen Anthony / libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/stella2014-libretro"
                )
                LicenseEntry(
                    name = "ProSystem",
                    author = "Greg Stanton / libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/prosystem-libretro"
                )
                LicenseEntry(
                    name = "Handy",
                    author = "K. Wilkins / libretro",
                    license = "Zlib",
                    url = "github.com/libretro/libretro-handy"
                )

                // ── Misc handhelds ──────────────────────────
                LicenseGroupHeader(title = "Other Handhelds")
                LicenseEntry(
                    name = "Beetle NGP",
                    author = "Mednafen / libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/beetle-ngp-libretro"
                )
                LicenseEntry(
                    name = "Beetle WonderSwan",
                    author = "Mednafen / libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/beetle-wswan-libretro"
                )
                LicenseEntry(
                    name = "Beetle VB",
                    author = "Mednafen / libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/beetle-vb-libretro"
                )

                // ── DOS / 3DO ───────────────────────────────
                LicenseGroupHeader(title = "DOS / 3DO")
                LicenseEntry(
                    name = "DOSBox Pure",
                    author = "Bernhard Schelling / libretro",
                    license = "GPL v2+",
                    url = "github.com/schellingb/dosbox-pure"
                )
                LicenseEntry(
                    name = "Opera",
                    author = "trapexit / libretro",
                    license = "GPL v2",
                    url = "github.com/libretro/opera-libretro"
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "All emulation cores are property of their respective authors. " +
                        "Vortex Emulator does not include or distribute any copyrighted game ROMs or BIOS files.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun LicenseGroupHeader(title: String) {
    Spacer(modifier = Modifier.height(10.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = VortexCyan.copy(alpha = 0.85f)
    )
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun LicenseEntry(
    name: String,
    author: String,
    license: String,
    url: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "\u2022",
            style = MaterialTheme.typography.bodySmall,
            color = VortexCyan,
            modifier = Modifier.padding(end = 6.dp, top = 1.dp)
        )
        Column {
            Text(
                text = "$name — $license",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "$author · $url",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
