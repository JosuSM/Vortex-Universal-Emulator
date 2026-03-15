package com.vortex.emulator.core

import com.vortex.emulator.emulation.CoreDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages emulation cores — discovery, installation, selection and lifecycle.
 * Cores are downloaded on first use and cached permanently.
 */
@Singleton
class CoreManager @Inject constructor(
    private val coreDownloader: CoreDownloader
) {
    private val _installedCores = MutableStateFlow<List<CoreInfo>>(emptyList())
    val installedCores: StateFlow<List<CoreInfo>> = _installedCores.asStateFlow()

    private val _availableCores = MutableStateFlow<List<CoreInfo>>(emptyList())
    val availableCores: StateFlow<List<CoreInfo>> = _availableCores.asStateFlow()

    init {
        loadCoresCatalog()
    }

    private fun loadCoresCatalog() {
        val catalog = listOf(
            // ── NES ─────────────────────────────────────────────
            CoreInfo(
                id = "vortex_nes_fce",
                name = "FCEUmm",
                displayName = "FCEUmm (NES)",
                version = "2024.12",
                author = "FCEUmm Team",
                description = "Accurate NES/Famicom emulator with excellent compatibility",
                supportedPlatforms = listOf(Platform.NES),
                libraryName = "fceumm",
                isBundled = true,
                downloadSizeMb = 1.2f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS
                )
            ),
            CoreInfo(
                id = "vortex_nes_mesen",
                name = "Mesen",
                displayName = "Mesen (NES)",
                version = "0.9.9",
                author = "Sour",
                description = "Feature-rich NES core with excellent accuracy and online play support.",
                supportedPlatforms = listOf(Platform.NES),
                libraryName = "mesen",
                isBundled = true,
                downloadSizeMb = 2.0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.NETPLAY
                )
            ),
            CoreInfo(
                id = "vortex_nes_nestopia",
                name = "Nestopia UE",
                displayName = "Nestopia UE (NES)",
                version = "1.52.1",
                author = "Libretro",
                description = "Cycle-accurate NES/Famicom core with low overhead.",
                supportedPlatforms = listOf(Platform.NES),
                libraryName = "nestopia",
                isBundled = true,
                downloadSizeMb = 1.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.NETPLAY
                )
            ),
            // ── SNES ────────────────────────────────────────────
            CoreInfo(
                id = "vortex_snes_snes9x",
                name = "Snes9x",
                displayName = "Snes9x (SNES)",
                version = "1.63",
                author = "Snes9x Team",
                description = "Fast and compatible SNES emulator, ideal for all devices",
                supportedPlatforms = listOf(Platform.SNES),
                libraryName = "snes9x",
                isBundled = true,
                downloadSizeMb = 2.1f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.RUMBLE,
                    CoreFeature.NETPLAY
                )
            ),
            // ── N64 ─────────────────────────────────────────────
            CoreInfo(
                id = "vortex_n64_mupen",
                name = "Mupen64Plus-Next",
                displayName = "Mupen64Plus (N64)",
                version = "2.6",
                author = "Mupen64Plus Team",
                description = "High-compatibility N64 emulator with GLideN64 plugin",
                supportedPlatforms = listOf(Platform.N64),
                libraryName = "mupen64plus_next",
                isBundled = true,
                downloadSizeMb = 8.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.RUMBLE
                )
            ),
            CoreInfo(
                id = "vortex_n64_parallel",
                name = "ParaLLEl N64",
                displayName = "ParaLLEl N64",
                version = "2024.10",
                author = "Libretro Team",
                description = "Accuracy-focused N64 core with Vulkan support.",
                supportedPlatforms = listOf(Platform.N64),
                libraryName = "parallel_n64",
                isBundled = true,
                downloadSizeMb = 9.1f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.HIGH_RESOLUTION, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE,
                    CoreFeature.NETPLAY
                )
            ),
            // ── GBA / GB / GBC ──────────────────────────────────
            CoreInfo(
                id = "vortex_gba_mgba",
                name = "mGBA",
                displayName = "mGBA (GBA/GBC/GB)",
                version = "0.10.3",
                author = "endrift",
                description = "Accurate and fast GBA emulator with GB/GBC support",
                supportedPlatforms = listOf(Platform.GBA, Platform.GBC),
                libraryName = "mgba",
                isBundled = true,
                downloadSizeMb = 1.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.RUMBLE,
                    CoreFeature.NETPLAY
                )
            ),
            CoreInfo(
                id = "vortex_gb_sameboy",
                name = "SameBoy",
                displayName = "SameBoy (GB/GBC)",
                version = "1.0",
                author = "LIJI",
                description = "Highly accurate Game Boy and Color core with link emulation support.",
                supportedPlatforms = listOf(Platform.GBC),
                libraryName = "sameboy",
                isBundled = true,
                downloadSizeMb = 1.6f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY
                )
            ),
            CoreInfo(
                id = "vortex_gba_vbam",
                name = "VBA-M",
                displayName = "VBA-M (GBA)",
                version = "2.1.9",
                author = "VBA-M Team",
                description = "Alternative GBA core with broad compatibility and link support.",
                supportedPlatforms = listOf(Platform.GBA),
                libraryName = "vbam",
                isBundled = true,
                downloadSizeMb = 2.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.NETPLAY, CoreFeature.RUMBLE
                )
            ),
            // ── NDS ─────────────────────────────────────────────
            CoreInfo(
                id = "vortex_nds_melonds",
                name = "melonDS",
                displayName = "melonDS (NDS)",
                version = "0.9.5",
                author = "Arisotura",
                description = "Accurate Nintendo DS emulator with Wi-Fi support",
                supportedPlatforms = listOf(Platform.NDS),
                libraryName = "melonds",
                isBundled = true,
                downloadSizeMb = 3.2f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.TOUCH_OVERLAY,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.NETPLAY
                )
            ),
            CoreInfo(
                id = "vortex_nds_desmume",
                name = "DeSmuME",
                displayName = "DeSmuME (NDS)",
                version = "0.9.13",
                author = "DeSmuME Team",
                description = "Well-established NDS emulator with strong game compatibility.",
                supportedPlatforms = listOf(Platform.NDS),
                libraryName = "desmume",
                isBundled = true,
                downloadSizeMb = 3.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.TOUCH_OVERLAY,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.NETPLAY
                )
            ),
            // ── Genesis / Mega Drive ────────────────────────────
            CoreInfo(
                id = "vortex_gen_genesis_plus",
                name = "Genesis Plus GX",
                displayName = "Genesis Plus GX (Genesis/MD)",
                version = "1.7.4",
                author = "ekeeke",
                description = "Accurate Sega Genesis/Mega Drive/CD/Master System emulator",
                supportedPlatforms = listOf(Platform.GENESIS),
                libraryName = "genesis_plus_gx",
                isBundled = true,
                downloadSizeMb = 1.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.NETPLAY
                )
            ),
            CoreInfo(
                id = "vortex_gen_picodrive",
                name = "PicoDrive",
                displayName = "PicoDrive (Genesis/32X)",
                version = "2.0",
                author = "notaz",
                description = "Fast Sega core with strong performance on lower-end devices.",
                supportedPlatforms = listOf(Platform.GENESIS),
                libraryName = "picodrive",
                isBundled = true,
                downloadSizeMb = 1.7f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.NETPLAY
                )
            ),
            // ── PSX ─────────────────────────────────────────────
            CoreInfo(
                id = "vortex_psx_swanstation",
                name = "SwanStation",
                displayName = "SwanStation (PSX)",
                version = "2024.01",
                author = "stenzek",
                description = "High-performance PlayStation emulator with Vulkan rendering",
                supportedPlatforms = listOf(Platform.PSX),
                libraryName = "swanstation",
                isBundled = true,
                downloadSizeMb = 5.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.HIGH_RESOLUTION, CoreFeature.WIDESCREEN_HACK,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY
                )
            ),
            CoreInfo(
                id = "vortex_psx_beetle",
                name = "Beetle PSX HW",
                displayName = "Beetle PSX HW",
                version = "0.9.44.1",
                author = "Mednafen / Libretro",
                description = "Hardware-accelerated PSX core with excellent accuracy.",
                supportedPlatforms = listOf(Platform.PSX),
                libraryName = "mednafen_psx_hw",
                isBundled = true,
                downloadSizeMb = 5.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY
                )
            ),
            // ── PSP ─────────────────────────────────────────────
            CoreInfo(
                id = "vortex_psp_ppsspp",
                name = "PPSSPP",
                displayName = "PPSSPP (PSP)",
                version = "1.17.1",
                author = "Henrik Rydgård",
                description = "The best PSP emulator with Vulkan and high-res rendering",
                supportedPlatforms = listOf(Platform.PSP),
                libraryName = "ppsspp",
                isBundled = true,
                downloadSizeMb = 12.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY
                )
            ),
            // ── Dreamcast ───────────────────────────────────────
            CoreInfo(
                id = "vortex_dc_flycast",
                name = "Flycast",
                displayName = "Flycast (Dreamcast)",
                version = "2.3",
                author = "Flycast Team",
                description = "Outstanding Dreamcast emulator with Vulkan and OIT",
                supportedPlatforms = listOf(Platform.DREAMCAST),
                libraryName = "flycast",
                isBundled = true,
                downloadSizeMb = 6.2f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.HIGH_RESOLUTION, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.RUMBLE, CoreFeature.NETPLAY
                )
            ),
            // ── Saturn ──────────────────────────────────────────
            CoreInfo(
                id = "vortex_sat_beetle",
                name = "Beetle Saturn",
                displayName = "Beetle Saturn (Saturn)",
                version = "2024.06",
                author = "Mednafen",
                description = "Accurate Saturn emulation based on Mednafen",
                supportedPlatforms = listOf(Platform.SATURN),
                libraryName = "mednafen_saturn",
                isBundled = true,
                downloadSizeMb = 4.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.ANALOG_STICK
                )
            ),
            CoreInfo(
                id = "vortex_sat_yabause",
                name = "Yabause",
                displayName = "Yabause (Saturn)",
                version = "0.9.15",
                author = "Yabause Team",
                description = "Alternative Saturn core with OpenGL rendering support.",
                supportedPlatforms = listOf(Platform.SATURN),
                libraryName = "yabause",
                isBundled = true,
                downloadSizeMb = 3.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK
                )
            ),
            // ── Arcade ──────────────────────────────────────────
            CoreInfo(
                id = "vortex_arc_fbneo",
                name = "FinalBurn Neo",
                displayName = "FinalBurn Neo (Arcade)",
                version = "1.0.0.3",
                author = "FBNeo Team",
                description = "Excellent arcade emulator supporting thousands of games",
                supportedPlatforms = listOf(Platform.ARCADE),
                libraryName = "fbneo",
                isBundled = true,
                downloadSizeMb = 7.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.NETPLAY
                )
            ),
            CoreInfo(
                id = "vortex_arc_mame2003",
                name = "MAME 2003-Plus",
                displayName = "MAME 2003-Plus (Arcade)",
                version = "0.78+",
                author = "Libretro Team",
                description = "Alternative arcade core with broad multiplayer support.",
                supportedPlatforms = listOf(Platform.ARCADE),
                libraryName = "mame2003_plus",
                isBundled = true,
                downloadSizeMb = 9.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY
                )
            )
        )

        _availableCores.value = catalog
        // Mark cores as installed if they're already cached on disk
        _installedCores.value = catalog.map {
            it.copy(isInstalled = coreDownloader.isCoreDownloaded(it.libraryName))
        }
    }

    /** Refresh installed status (call after a core download completes). */
    fun refreshInstalled() {
        _installedCores.value = _availableCores.value.map {
            it.copy(isInstalled = coreDownloader.isCoreDownloaded(it.libraryName))
        }
    }

    fun getCoresForPlatform(platform: Platform): List<CoreInfo> {
        return _availableCores.value.filter { platform in it.supportedPlatforms }
    }

    private val preferredCores = mutableMapOf<Platform, String>()

    fun getPreferredCore(platform: Platform): CoreInfo? {
        val preferredId = preferredCores[platform]
        val cores = getCoresForPlatform(platform)
        return if (preferredId != null) {
            cores.find { it.id == preferredId } ?: cores.firstOrNull()
        } else {
            cores.firstOrNull()
        }
    }

    fun setPreferredCore(platform: Platform, coreId: String) {
        preferredCores[platform] = coreId
    }

    fun getAllPlatforms(): List<Platform> {
        return _installedCores.value.flatMap { it.supportedPlatforms }.distinct()
    }

    fun getCoreById(id: String): CoreInfo? {
        return _availableCores.value.find { it.id == id }
    }
}
