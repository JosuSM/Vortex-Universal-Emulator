package com.vortex.emulator.core

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages emulation cores — discovery, installation, selection and lifecycle.
 * Supports both bundled and downloadable cores.
 */
@Singleton
class CoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val coresDir: File = File(context.filesDir, "cores")
    private val _installedCores = MutableStateFlow<List<CoreInfo>>(emptyList())
    val installedCores: StateFlow<List<CoreInfo>> = _installedCores.asStateFlow()

    private val _availableCores = MutableStateFlow<List<CoreInfo>>(emptyList())
    val availableCores: StateFlow<List<CoreInfo>> = _availableCores.asStateFlow()

    init {
        coresDir.mkdirs()
        loadBundledCoresCatalog()
    }

    private fun loadBundledCoresCatalog() {
        val catalog = listOf(
            // NES cores
            CoreInfo(
                id = "vortex_nes_fce",
                name = "FCEUmm",
                displayName = "FCEUmm (NES)",
                version = "2024.12",
                author = "FCEUmm Team",
                description = "Accurate NES/Famicom emulator with excellent compatibility",
                supportedPlatforms = listOf(Platform.NES),
                libraryName = "libfceumm",
                isBundled = true,
                downloadSizeMb = 1.2f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS
                )
            ),
            // SNES cores
            CoreInfo(
                id = "vortex_snes_snes9x",
                name = "Snes9x",
                displayName = "Snes9x (SNES)",
                version = "1.63",
                author = "Snes9x Team",
                description = "Fast and compatible SNES emulator, ideal for all devices",
                supportedPlatforms = listOf(Platform.SNES),
                libraryName = "libsnes9x",
                isBundled = true,
                downloadSizeMb = 2.1f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.RUMBLE
                )
            ),
            CoreInfo(
                id = "vortex_snes_bsnes",
                name = "bsnes",
                displayName = "bsnes (SNES — Accuracy)",
                version = "115+",
                author = "byuu / Near",
                description = "Cycle-accurate SNES emulation, requires powerful device",
                supportedPlatforms = listOf(Platform.SNES),
                libraryName = "libbsnes",
                downloadSizeMb = 3.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.SHADERS, CoreFeature.HIGH_RESOLUTION
                )
            ),
            // N64 cores
            CoreInfo(
                id = "vortex_n64_mupen",
                name = "Mupen64Plus-Next",
                displayName = "Mupen64Plus (N64)",
                version = "2.6",
                author = "Mupen64Plus Team",
                description = "High-compatibility N64 emulator with Vulkan and GLideN64",
                supportedPlatforms = listOf(Platform.N64),
                libraryName = "libmupen64plus_next",
                isBundled = true,
                downloadSizeMb = 8.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.WIDESCREEN_HACK, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.RUMBLE
                )
            ),
            // GBA cores
            CoreInfo(
                id = "vortex_gba_mgba",
                name = "mGBA",
                displayName = "mGBA (GBA/GBC/GB)",
                version = "0.10.3",
                author = "endrift",
                description = "Accurate and fast GBA emulator with GB/GBC support",
                supportedPlatforms = listOf(Platform.GBA, Platform.GBC),
                libraryName = "libmgba",
                isBundled = true,
                downloadSizeMb = 1.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.RUMBLE
                )
            ),
            // NDS core
            CoreInfo(
                id = "vortex_nds_melonds",
                name = "melonDS",
                displayName = "melonDS (NDS)",
                version = "0.9.5",
                author = "Arisotura",
                description = "Accurate Nintendo DS emulator with Wi-Fi support",
                supportedPlatforms = listOf(Platform.NDS),
                libraryName = "libmelonds",
                isBundled = true,
                downloadSizeMb = 3.2f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.TOUCH_OVERLAY,
                    CoreFeature.OPENGL_RENDERER
                )
            ),
            // Genesis / Mega Drive
            CoreInfo(
                id = "vortex_gen_genesis_plus",
                name = "Genesis Plus GX",
                displayName = "Genesis Plus GX (Genesis/MD)",
                version = "1.7.4",
                author = "ekeeke",
                description = "Accurate Sega Genesis/Mega Drive/CD/Master System emulator",
                supportedPlatforms = listOf(Platform.GENESIS),
                libraryName = "libgenesis_plus_gx",
                isBundled = true,
                downloadSizeMb = 1.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS
                )
            ),
            // PSX
            CoreInfo(
                id = "vortex_psx_swanstation",
                name = "SwanStation",
                displayName = "SwanStation (PSX)",
                version = "2024.01",
                author = "stenzek",
                description = "High-performance PlayStation emulator with Vulkan rendering",
                supportedPlatforms = listOf(Platform.PSX),
                libraryName = "libswanstation",
                isBundled = true,
                downloadSizeMb = 5.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.HIGH_RESOLUTION, CoreFeature.WIDESCREEN_HACK,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS
                )
            ),
            // PSP
            CoreInfo(
                id = "vortex_psp_ppsspp",
                name = "PPSSPP",
                displayName = "PPSSPP (PSP)",
                version = "1.17.1",
                author = "Henrik Rydgård",
                description = "The best PSP emulator with Vulkan and high-res rendering",
                supportedPlatforms = listOf(Platform.PSP),
                libraryName = "libppsspp",
                isBundled = true,
                downloadSizeMb = 12.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.WIDESCREEN_HACK, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.RUMBLE, CoreFeature.SHADERS, CoreFeature.NETPLAY
                )
            ),
            // Dreamcast
            CoreInfo(
                id = "vortex_dc_flycast",
                name = "Flycast",
                displayName = "Flycast (Dreamcast)",
                version = "2.3",
                author = "Flycast Team",
                description = "Outstanding Dreamcast emulator with Vulkan and OIT",
                supportedPlatforms = listOf(Platform.DREAMCAST),
                libraryName = "libflycast",
                downloadSizeMb = 6.2f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.HIGH_RESOLUTION, CoreFeature.WIDESCREEN_HACK,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE
                )
            ),
            // Saturn
            CoreInfo(
                id = "vortex_sat_beetle",
                name = "Beetle Saturn",
                displayName = "Beetle Saturn (Saturn)",
                version = "2024.06",
                author = "Mednafen",
                description = "Accurate Saturn emulation based on Mednafen",
                supportedPlatforms = listOf(Platform.SATURN),
                libraryName = "libbeetle_saturn",
                downloadSizeMb = 4.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.ANALOG_STICK
                )
            ),
            // Arcade
            CoreInfo(
                id = "vortex_arc_fbneo",
                name = "FinalBurn Neo",
                displayName = "FinalBurn Neo (Arcade)",
                version = "1.0.0.3",
                author = "FBNeo Team",
                description = "Excellent arcade emulator supporting thousands of games",
                supportedPlatforms = listOf(Platform.ARCADE),
                libraryName = "libfbneo",
                downloadSizeMb = 7.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.NETPLAY
                )
            ),
            // GameCube / Wii
            CoreInfo(
                id = "vortex_gcn_dolphin",
                name = "Dolphin",
                displayName = "Dolphin (GameCube/Wii)",
                version = "2409",
                author = "Dolphin Team",
                description = "Leading GameCube/Wii emulator with Vulkan and upscaling",
                supportedPlatforms = listOf(Platform.GAMECUBE, Platform.WII),
                libraryName = "libdolphin",
                downloadSizeMb = 18.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.WIDESCREEN_HACK, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.RUMBLE, CoreFeature.NETPLAY
                )
            )
        )

        _availableCores.value = catalog
        refreshInstalledCores(catalog)
    }

    private fun refreshInstalledCores(catalog: List<CoreInfo>) {
        val installed = catalog.filter { core ->
            core.isBundled || File(coresDir, "${core.libraryName}.so").exists()
        }.map { it.copy(isInstalled = true) }
        _installedCores.value = installed
    }

    fun getCoresForPlatform(platform: Platform): List<CoreInfo> {
        return _installedCores.value.filter { platform in it.supportedPlatforms }
    }

    fun getPreferredCore(platform: Platform): CoreInfo? {
        return getCoresForPlatform(platform).firstOrNull()
    }

    fun getAllPlatforms(): List<Platform> {
        return _installedCores.value.flatMap { it.supportedPlatforms }.distinct()
    }

    fun getCoreById(id: String): CoreInfo? {
        return _availableCores.value.find { it.id == id }
    }

    suspend fun installCore(coreInfo: CoreInfo): Boolean {
        // In production: download .so from CDN and place in coresDir
        // For now, mark as installed
        val updated = _availableCores.value.map {
            if (it.id == coreInfo.id) it.copy(isInstalled = true) else it
        }
        _availableCores.value = updated
        refreshInstalledCores(updated)
        return true
    }

    suspend fun uninstallCore(coreInfo: CoreInfo): Boolean {
        val file = File(coresDir, "${coreInfo.libraryName}.so")
        if (file.exists()) file.delete()
        val updated = _availableCores.value.map {
            if (it.id == coreInfo.id) it.copy(isInstalled = false) else it
        }
        _availableCores.value = updated
        refreshInstalledCores(updated)
        return true
    }
}
