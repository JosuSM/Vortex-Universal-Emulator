package com.vortex.emulator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortex.emulator.core.*
import com.vortex.emulator.emulation.FrontendBridge
import com.vortex.emulator.emulation.FrontendType
import com.vortex.emulator.ui.components.toColor
import com.vortex.emulator.ui.theme.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CoreSettingsEntryPoint {
    fun coreSettingsRepository(): CoreSettingsRepository
    fun coreManager(): CoreManager
    fun frontendBridge(): FrontendBridge
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreSettingsScreen(
    coreId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            CoreSettingsEntryPoint::class.java
        )
    }
    val repo = remember { entryPoint.coreSettingsRepository() }
    val coreManager = remember { entryPoint.coreManager() }
    val frontendBridge = remember { entryPoint.frontendBridge() }

    val coreInfo = remember(coreId) {
        coreManager.getCoreById(coreId)
    }
    val coreName = coreInfo?.displayName ?: coreId
    val primaryPlatform = coreInfo?.supportedPlatforms?.firstOrNull()
    val platformColor = primaryPlatform?.toColor() ?: VortexCyan

    var settings by remember(coreId) { mutableStateOf(repo.load(coreId)) }

    // Auto-save on every change
    fun update(block: CoreSettingsData.() -> CoreSettingsData) {
        settings = settings.block()
        repo.save(coreId, settings)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = coreName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (primaryPlatform != null) {
                            Text(
                                text = "${primaryPlatform.abbreviation} Core Settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = platformColor
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Reset to defaults
                    var showResetDialog by remember { mutableStateOf(false) }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(
                            Icons.Filled.RestartAlt,
                            contentDescription = "Reset to Defaults",
                            tint = VortexOrange
                        )
                    }
                    if (showResetDialog) {
                        AlertDialog(
                            onDismissRequest = { showResetDialog = false },
                            icon = { Icon(Icons.Filled.RestartAlt, null, tint = VortexOrange) },
                            title = { Text("Reset Core Settings") },
                            text = { Text("Reset all settings for $coreName to their defaults?") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        settings = CoreSettingsData()
                                        repo.save(coreId, settings)
                                        showResetDialog = false
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = VortexOrange)
                                ) { Text("Reset", fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
                            }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // ─── Rendering Mode ─────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Videocam,
                title = "Rendering Mode"
            )

            // Backend selector
            CoreSettingsDropdown(
                title = "Backend",
                description = "Rendering API preference. Vulkan uses Vulkan display layer with GLES core rendering for best Mali compatibility",
                selectedValue = settings.renderBackend.displayName,
                options = RenderBackend.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(renderBackend = RenderBackend.entries[idx]) }
                }
            )

            CoreSettingsDropdown(
                title = "Rendering Resolution",
                description = "Internal resolution multiplier",
                selectedValue = settings.displayResolution.displayName,
                options = DisplayResolution.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(displayResolution = DisplayResolution.entries[idx]) }
                }
            )

            CoreSettingsToggle(
                title = "Software Rendering (slow, more accurate)",
                description = "Use CPU for rendering — fixes visual glitches in some games",
                checked = settings.softwareRendering,
                onCheckedChange = { update { copy(softwareRendering = it) } }
            )

            CoreSettingsDropdown(
                title = "Antialiasing (MSAA)",
                selectedValue = settings.antiAliasing.displayName,
                options = AntiAliasing.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(antiAliasing = AntiAliasing.entries[idx]) }
                }
            )

            CoreSettingsDropdown(
                title = "Screen Resolution (display scaler)",
                description = "Output resolution on the device screen",
                selectedValue = settings.screenResolution.displayName,
                options = ScreenResolution.entries.map { it.displayName },
                onSelected = { idx -> update { copy(screenResolution = ScreenResolution.entries[idx]) } }
            )

            CoreSettingsToggle(
                title = "Texture Replacement",
                description = "Load HD texture packs when available",
                checked = settings.textureReplacement,
                onCheckedChange = { update { copy(textureReplacement = it) } }
            )

            CoreSettingsDivider()

            // ─── Display ────────────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.PhoneAndroid,
                title = "Display"
            )

            CoreSettingsToggle(
                title = "Show Layout Editor",
                description = "Open the on-screen controls layout editor",
                checked = settings.showLayoutEditor,
                onCheckedChange = { update { copy(showLayoutEditor = it) } }
            )

            CoreSettingsToggle(
                title = "Low Latency Display",
                description = "Reduce display latency — may cause tearing on some devices",
                checked = settings.lowLatencyDisplay,
                onCheckedChange = { update { copy(lowLatencyDisplay = it) } }
            )

            CoreSettingsDivider()

            // ─── FPS Control ────────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Speed,
                title = "FPS Control"
            )

            CoreSettingsDropdown(
                title = "Frame Skip",
                selectedValue = settings.frameSkip.displayName,
                options = FrameSkipMode.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(frameSkip = FrameSkipMode.entries[idx]) }
                }
            )

            CoreSettingsToggle(
                title = "Auto Frame Skip",
                description = "Automatically skip frames when performance drops",
                checked = settings.autoFrameSkip,
                onCheckedChange = { update { copy(autoFrameSkip = it) } }
            )

            CoreSettingsSlider(
                title = "Alternative Speed (%, 0 = unlimited)",
                value = settings.altSpeed.toFloat(),
                valueRange = 0f..300f,
                steps = 5,
                displayValue = if (settings.altSpeed == 0) "Unlimited" else "${settings.altSpeed}%",
                onValueChange = { update { copy(altSpeed = it.toInt()) } }
            )

            CoreSettingsSlider(
                title = "Alt Speed 2 (%, 0 = disabled)",
                value = settings.altSpeed2.toFloat(),
                valueRange = 0f..300f,
                steps = 5,
                displayValue = if (settings.altSpeed2 == 0) "Disabled" else "${settings.altSpeed2}%",
                onValueChange = { update { copy(altSpeed2 = it.toInt()) } }
            )

            CoreSettingsDivider()

            // ─── Speed Hacks ────────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Bolt,
                title = "Speed Hacks (may cause visual glitches)"
            )

            CoreSettingsToggle(
                title = "Skip Buffer Effects (no buffer, faster)",
                description = "Faster, but may render nothing in some games",
                checked = settings.skipBufferEffects,
                onCheckedChange = { update { copy(skipBufferEffects = it) } }
            )

            CoreSettingsToggle(
                title = "Disable Culling",
                checked = settings.disableCulling,
                onCheckedChange = { update { copy(disableCulling = it) } }
            )

            CoreSettingsDropdown(
                title = "Skip GPU Readbacks",
                selectedValue = if (settings.skipGpuReadbacks) "Yes" else "No (Default)",
                options = listOf("No (Default)", "Yes"),
                onSelected = { idx -> update { copy(skipGpuReadbacks = idx == 1) } }
            )

            CoreSettingsDropdown(
                title = "Lens Flare Occlusion",
                selectedValue = settings.lensFlareOcclusion.displayName,
                options = LensFlareOcclusion.entries.map { it.displayName },
                onSelected = { idx -> update { copy(lensFlareOcclusion = LensFlareOcclusion.entries[idx]) } }
            )

            CoreSettingsToggle(
                title = "Lazy Texture Caching (faster)",
                description = "Faster, but may cause text glitches in some games",
                checked = settings.lazyCaching,
                onCheckedChange = { update { copy(lazyCaching = it) } }
            )

            CoreSettingsDropdown(
                title = "Spline / Bezier Curve Quality",
                description = "Used only in some games, controls curve smoothness",
                selectedValue = settings.splineBezierQuality.displayName,
                options = SplineBezierQuality.entries.map { it.displayName },
                onSelected = { idx -> update { copy(splineBezierQuality = SplineBezierQuality.entries[idx]) } }
            )

            CoreSettingsToggle(
                title = "Lower Effects Resolution",
                description = "Reduces artifacts",
                checked = settings.lowerEffectsResolution,
                onCheckedChange = { update { copy(lowerEffectsResolution = it) } }
            )

            CoreSettingsDivider()

            // ─── Performance ────────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.AutoMirrored.Filled.TrendingUp,
                title = "Performance"
            )

            CoreSettingsToggle(
                title = "Duplicate Frames for 60 Hz",
                description = "May make FPS smoother in games that run at lower framerates",
                checked = settings.duplicateFrames60Hz,
                onCheckedChange = { update { copy(duplicateFrames60Hz = it) } }
            )

            CoreSettingsSlider(
                title = "Graphics Command Buffer",
                value = settings.commandBuffer.toFloat(),
                valueRange = 1f..4f,
                steps = 2,
                displayValue = "Up to ${settings.commandBuffer}",
                onValueChange = { update { copy(commandBuffer = it.toInt()) } }
            )

            CoreSettingsToggle(
                title = "Geometry Shader Culling",
                checked = settings.geometryShaderCulling,
                onCheckedChange = { update { copy(geometryShaderCulling = it) } }
            )

            CoreSettingsToggle(
                title = "Hardware Transform",
                checked = settings.hardwareTransform,
                onCheckedChange = { update { copy(hardwareTransform = it) } }
            )

            CoreSettingsToggle(
                title = "Software Skinning",
                description = "Combines skinning draw calls on CPU, faster in most games",
                checked = settings.softwareSkinning,
                onCheckedChange = { update { copy(softwareSkinning = it) } }
            )

            CoreSettingsToggle(
                title = "Hardware Tessellation",
                description = "Use the hardware to generate curves",
                checked = settings.hardwareTessellation,
                onCheckedChange = { update { copy(hardwareTessellation = it) } }
            )

            CoreSettingsDivider()

            // ─── Texture Scaling ────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Texture,
                title = "Texture Scaling"
            )

            CoreSettingsToggle(
                title = "Texture Shader (GPU)",
                checked = settings.textureShaderGpu,
                onCheckedChange = { update { copy(textureShaderGpu = it) } }
            )

            CoreSettingsDropdown(
                title = "Upscale Type (CPU)",
                selectedValue = settings.textureUpscaleType.displayName,
                options = TextureUpscaleType.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(textureUpscaleType = TextureUpscaleType.entries[idx]) }
                }
            )

            CoreSettingsDropdown(
                title = "Upscale Level",
                description = "Heavy on CPU — some scales may be delayed to avoid stalls",
                selectedValue = settings.textureUpscaleLevel.displayName,
                options = TextureUpscaleLevel.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(textureUpscaleLevel = TextureUpscaleLevel.entries[idx]) }
                }
            )

            CoreSettingsToggle(
                title = "Deposterize",
                description = "Fixes visual artifacts on upscaled textures",
                checked = settings.deposterize,
                onCheckedChange = { update { copy(deposterize = it) } }
            )

            CoreSettingsDivider()

            // ─── Texture Filtering ──────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.FilterAlt,
                title = "Texture Filtering"
            )

            CoreSettingsDropdown(
                title = "Anisotropic Filtering",
                selectedValue = "${settings.anisotropicFiltering}×",
                options = listOf("1×", "2×", "4×", "8×", "16×"),
                onSelected = { idx ->
                    val values = listOf(1, 2, 4, 8, 16)
                    update { copy(anisotropicFiltering = values[idx]) }
                }
            )

            CoreSettingsDropdown(
                title = "Texture Filter",
                selectedValue = settings.textureFilterMode.displayName,
                options = TextureFilter.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(textureFilterMode = TextureFilter.entries[idx]) }
                }
            )

            CoreSettingsToggle(
                title = "Smart 2D Texture Filtering",
                checked = settings.smart2DFiltering,
                onCheckedChange = { update { copy(smart2DFiltering = it) } }
            )

            CoreSettingsDivider()

            // ─── Overlay Information ────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Analytics,
                title = "Overlay Information"
            )

            CoreSettingsToggle(
                title = "Show FPS Counter",
                checked = settings.showFpsCounter,
                onCheckedChange = { update { copy(showFpsCounter = it) } }
            )

            CoreSettingsToggle(
                title = "Show Speed",
                checked = settings.showSpeed,
                onCheckedChange = { update { copy(showSpeed = it) } }
            )

            CoreSettingsDivider()

            // ─── Controls ───────────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Gamepad,
                title = "Controls"
            )

            CoreSettingsToggle(
                title = "Haptic Feedback",
                description = "Vibrate on button press in on-screen controls",
                checked = settings.hapticFeedback,
                onCheckedChange = { update { copy(hapticFeedback = it) } }
            )

            CoreSettingsSlider(
                title = "Touch Control Opacity",
                value = settings.touchControlOpacity,
                valueRange = 0.1f..1.0f,
                steps = 8,
                displayValue = "${(settings.touchControlOpacity * 100).toInt()}%",
                onValueChange = { update { copy(touchControlOpacity = it) } }
            )

            CoreSettingsSlider(
                title = "Touch Control Scale",
                value = settings.touchControlScale,
                valueRange = 0.5f..2.0f,
                steps = 5,
                displayValue = "${(settings.touchControlScale * 100).toInt()}%",
                onValueChange = { update { copy(touchControlScale = it) } }
            )

            CoreSettingsDivider()

            // ─── Advanced / Compatibility (Gemini-suggested) ────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Build,
                title = "Advanced / Compatibility"
            )

            CoreSettingsDropdown(
                title = "Input Polling Mode",
                description = "When to poll inputs. \"Early\" may fix dead controllers in some cores",
                selectedValue = settings.inputPollingMode.displayName,
                options = InputPollingMode.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(inputPollingMode = InputPollingMode.entries[idx]) }
                }
            )

            CoreSettingsToggle(
                title = "Threaded Video Rendering",
                description = "Run video on a separate thread — may fix stuttering but can cause crashes",
                checked = settings.threadedRendering,
                onCheckedChange = { update { copy(threadedRendering = it) } }
            )

            CoreSettingsDropdown(
                title = "Shader Precision Override",
                description = "Force low precision on Mali GPUs to fix black screens",
                selectedValue = settings.forceShaderPrecision.displayName,
                options = ShaderPrecision.entries.map { it.displayName },
                onSelected = { idx ->
                    update { copy(forceShaderPrecision = ShaderPrecision.entries[idx]) }
                }
            )

            CoreSettingsToggle(
                title = "Rewind",
                description = "Enable time-rewind — uses extra memory for state buffers",
                checked = settings.rewindEnabled,
                onCheckedChange = { update { copy(rewindEnabled = it) } }
            )

            if (settings.rewindEnabled) {
                CoreSettingsSlider(
                    title = "Rewind Buffer (seconds)",
                    value = settings.rewindBufferSeconds.toFloat(),
                    valueRange = 2f..60f,
                    steps = 28,
                    displayValue = "${settings.rewindBufferSeconds}s",
                    onValueChange = { update { copy(rewindBufferSeconds = it.toInt()) } }
                )
            }

            CoreSettingsDivider()

            // ─── Frontend Engine ─────────────────────────────────
            CoreSettingsSectionHeader(
                icon = Icons.Filled.Memory,
                title = "Frontend Engine"
            )

            val currentFrontend = settings.frontendType?.let { FrontendType.fromName(it) }
            val globalFrontend = frontendBridge.activeFrontend

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
                        text = "Override the global frontend engine for this core. " +
                               "\"Use Global\" inherits from Settings → Frontend Engine (currently ${globalFrontend.displayName}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // "Use Global" option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { update { copy(frontendType = null) } }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentFrontend == null,
                            onClick = { update { copy(frontendType = null) } }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Use Global (${globalFrontend.displayName})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Inherit from emulator-wide settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    FrontendType.entries.forEach { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { update { copy(frontendType = type.name) } }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentFrontend == type,
                                onClick = { update { copy(frontendType = type.name) } }
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

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─── Reusable Core Settings Composables ──────────────────────────────────

@Composable
fun CoreSettingsSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
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
fun CoreSettingsToggle(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoreSettingsDropdown(
    title: String,
    description: String? = null,
    selectedValue: String,
    options: List<String>,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = VortexCyan.copy(alpha = 0.12f)
            ) {
                Text(
                    text = selectedValue,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = VortexCyan,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            fontWeight = if (option == selectedValue) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == selectedValue) VortexCyan
                                    else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onSelected(index)
                        expanded = false
                    },
                    leadingIcon = if (option == selectedValue) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = VortexCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
fun CoreSettingsSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = displayValue,
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

@Composable
fun CoreSettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
    )
}
