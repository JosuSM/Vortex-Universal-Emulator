package com.vortex.emulator.core

import com.vortex.emulator.emulation.CoreDownloader
import com.vortex.emulator.emulation.StandaloneLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages emulation cores — discovery, installation, selection and lifecycle.
 * Cores are downloaded on first use and cached permanently.
 * Standalone cores are launched via StandaloneLauncher (VortexFramework).
 */
@Singleton
class CoreManager @Inject constructor(
    private val coreDownloader: CoreDownloader,
    private val standaloneLauncher: StandaloneLauncher
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                id = "vortex_n64_mupen_gles3",
                name = "Mupen64Plus-Next (GLES3)",
                displayName = "Mupen64Plus GLES3 (N64)",
                version = "2.6",
                author = "Mupen64Plus Team",
                description = "High-compatibility N64 emulator with GLideN64 plugin (GLES3)",
                supportedPlatforms = listOf(Platform.N64),
                libraryName = "mupen64plus_next_gles3",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 2.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.RUMBLE
                )
            ),
            CoreInfo(
                id = "vortex_n64_mupen_gles2",
                name = "Mupen64Plus-Next (GLES2)",
                displayName = "Mupen64Plus GLES2 (N64)",
                version = "2.6",
                author = "Mupen64Plus Team",
                description = "N64 emulator for older devices with GLES2 support",
                supportedPlatforms = listOf(Platform.N64),
                libraryName = "mupen64plus_next_gles2",
                isBundled = true,
                downloadSizeMb = 2.4f,
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
                isLemuroidDefault = true,
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
                isBundled = true,                isLemuroidDefault = true,                downloadSizeMb = 2.4f,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
                downloadSizeMb = 12.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY
                )
            ),
            // ── PSP (Rocket PSP Engine) ─────────────────────────
            CoreInfo(
                id = "vortex_psp_rocket",
                name = "Rocket PSP",
                displayName = "🚀 Rocket PSP Engine",
                version = "1.0",
                author = "Vortex Team (based on PPSSPP by Henrik Rydgård)",
                description = "Built-in PSP engine optimized for speed and compatibility. " +
                    "Uses aggressive performance tuning with fast memory, GPU hardware transform, " +
                    "and auto-detected optimal settings per GPU chipset. Based on PPSSPP (GPL v2+).",
                supportedPlatforms = listOf(Platform.PSP),
                libraryName = "ppsspp",
                isBundled = true,
                downloadSizeMb = 0f,  // shares the same .so as vortex_psp_ppsspp
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
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
                isLemuroidDefault = true,
                downloadSizeMb = 9.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY
                )
            ),
            // ── GameCube / Wii ──────────────────────────────────
            CoreInfo(
                id = "vortex_gcn_dolphin",
                name = "Dolphin",
                displayName = "Dolphin (GCN/Wii)",
                version = "5.0",
                author = "Dolphin Team",
                description = "Leading GameCube and Wii emulator with high compatibility",
                supportedPlatforms = listOf(Platform.GAMECUBE, Platform.WII),
                libraryName = "dolphin",
                isBundled = false,
                downloadSizeMb = 5.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE,
                    CoreFeature.WIDESCREEN_HACK, CoreFeature.NETPLAY
                )
            ),
            // ── Nintendo 3DS ────────────────────────────────────
            CoreInfo(
                id = "vortex_3ds_citra",
                name = "Citra",
                displayName = "Citra (3DS)",
                version = "2024.06",
                author = "Citra Team",
                description = "Feature-rich Nintendo 3DS emulator with upscaling support",
                supportedPlatforms = listOf(Platform.THREEDS),
                libraryName = "citra",
                isBundled = false,
                isLemuroidDefault = true,
                downloadSizeMb = 9.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.TOUCH_OVERLAY, CoreFeature.SHADERS
                )
            ),
            CoreInfo(
                id = "vortex_3ds_panda3ds",
                name = "Panda3DS",
                displayName = "Panda3DS (3DS)",
                version = "2024.10",
                author = "Panda3DS Team",
                description = "Lightweight 3DS emulator with growing compatibility",
                supportedPlatforms = listOf(Platform.THREEDS),
                libraryName = "panda3ds",
                isBundled = false,
                downloadSizeMb = 4.9f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.TOUCH_OVERLAY
                )
            ),
            // ── PlayStation 2 ───────────────────────────────────
            CoreInfo(
                id = "vortex_ps2_play",
                name = "Play!",
                displayName = "Play! (PS2)",
                version = "2024.12",
                author = "Jean-Philip Desjardins",
                description = "PlayStation 2 emulator with Vulkan and OpenGL rendering",
                supportedPlatforms = listOf(Platform.PS2),
                libraryName = "play",
                isBundled = false,
                isLemuroidDefault = true,
                downloadSizeMb = 1.6f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE
                )
            ),

            // ═══════════════════════════════════════════════════════
            // ══ Lemuroid Extra Cores ═══════════════════════════════
            // ═══════════════════════════════════════════════════════
            // Curated Lemuroid cores not already in the catalog above.
            // These are known-reliable and auto-apply Rust presets.

            // ── Game Boy / Game Boy Color (extra) ───────────────
            CoreInfo(
                id = "vortex_gbc_gambatte",
                name = "Gambatte",
                displayName = "Gambatte (GBC)",
                version = "2024.12",
                author = "sinamas",
                description = "Highly accurate Game Boy / Game Boy Color emulator",
                supportedPlatforms = listOf(Platform.GBC),
                libraryName = "gambatte",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS
                )
            ),
            // ── PlayStation (extra) ─────────────────────────────
            CoreInfo(
                id = "vortex_psx_pcsx_rearmed",
                name = "PCSX ReARMed",
                displayName = "PCSX ReARMed (PSX)",
                version = "2024.12",
                author = "notaz / libretro",
                description = "Optimized PlayStation emulator with ARM DynaRec for Android",
                supportedPlatforms = listOf(Platform.PSX),
                libraryName = "pcsx_rearmed",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 2.2f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE
                )
            ),
            // ── SNES (extra) ────────────────────────────────────
            CoreInfo(
                id = "vortex_snes_bsnes",
                name = "bsnes",
                displayName = "bsnes (SNES)",
                version = "2024.12",
                author = "Near / Screwtape",
                description = "Cycle-accurate SNES emulator (high accuracy, more demanding)",
                supportedPlatforms = listOf(Platform.SNES),
                libraryName = "bsnes",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 1.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.HIGH_RESOLUTION
                )
            ),
            // ── PC Engine / TurboGrafx-16 ───────────────────────
            CoreInfo(
                id = "vortex_pce_mednafen_pce_fast",
                name = "Beetle PCE Fast",
                displayName = "Beetle PCE Fast (PCE)",
                version = "2024.12",
                author = "Mednafen / libretro",
                description = "Fast PC Engine / TurboGrafx-16 emulator",
                supportedPlatforms = listOf(Platform.PCE),
                libraryName = "mednafen_pce_fast",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.8f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS
                )
            ),
            // ── Atari 2600 ─────────────────────────────────────
            CoreInfo(
                id = "vortex_atari2600_stella2014",
                name = "Stella 2014",
                displayName = "Stella 2014 (Atari 2600)",
                version = "2024.12",
                author = "Stephen Anthony / libretro",
                description = "Lightweight Atari 2600 emulator",
                supportedPlatforms = listOf(Platform.ATARI2600),
                libraryName = "stella2014",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.3f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD
                )
            ),
            // ── Atari 7800 ─────────────────────────────────────
            CoreInfo(
                id = "vortex_atari7800_prosystem",
                name = "ProSystem",
                displayName = "ProSystem (Atari 7800)",
                version = "2024.12",
                author = "Greg Stanton / libretro",
                description = "Atari 7800 ProSystem emulator",
                supportedPlatforms = listOf(Platform.ATARI7800),
                libraryName = "prosystem",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.3f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD
                )
            ),
            // ── Atari Lynx ─────────────────────────────────────
            CoreInfo(
                id = "vortex_lynx_handy",
                name = "Handy",
                displayName = "Handy (Atari Lynx)",
                version = "2024.12",
                author = "K. Wilkins / libretro",
                description = "Atari Lynx emulator",
                supportedPlatforms = listOf(Platform.LYNX),
                libraryName = "handy",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.3f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD
                )
            ),
            // ── Neo Geo Pocket / Color ─────────────────────────
            CoreInfo(
                id = "vortex_ngp_mednafen_ngp",
                name = "Beetle NGP",
                displayName = "Beetle NGP (Neo Geo Pocket)",
                version = "2024.12",
                author = "Mednafen / libretro",
                description = "Neo Geo Pocket / Color emulator",
                supportedPlatforms = listOf(Platform.NGP),
                libraryName = "mednafen_ngp",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD
                )
            ),
            // ── WonderSwan / Color ─────────────────────────────
            CoreInfo(
                id = "vortex_ws_mednafen_wswan",
                name = "Beetle WonderSwan",
                displayName = "Beetle WonderSwan (WS)",
                version = "2024.12",
                author = "Mednafen / libretro",
                description = "WonderSwan / WonderSwan Color emulator",
                supportedPlatforms = listOf(Platform.WONDERSWAN),
                libraryName = "mednafen_wswan",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD
                )
            ),
            // ── Virtual Boy ────────────────────────────────────
            CoreInfo(
                id = "vortex_vb_mednafen_vb",
                name = "Beetle VB",
                displayName = "Beetle VB (Virtual Boy)",
                version = "2024.12",
                author = "Mednafen / libretro",
                description = "Nintendo Virtual Boy emulator",
                supportedPlatforms = listOf(Platform.VIRTUALBOY),
                libraryName = "mednafen_vb",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.4f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD
                )
            ),
            // ── DOS ────────────────────────────────────────────
            CoreInfo(
                id = "vortex_dos_dosbox_pure",
                name = "DOSBox Pure",
                displayName = "DOSBox Pure (DOS)",
                version = "2024.12",
                author = "Bernhard Schelling / libretro",
                description = "Easy-to-use DOS emulator for retro PC gaming",
                supportedPlatforms = listOf(Platform.DOS),
                libraryName = "dosbox_pure",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 2.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS
                )
            ),
            // ── 3DO ────────────────────────────────────────────
            CoreInfo(
                id = "vortex_3do_opera",
                name = "Opera",
                displayName = "Opera (3DO)",
                version = "2024.12",
                author = "trapexit / libretro",
                description = "3DO Interactive Multiplayer emulator",
                supportedPlatforms = listOf(Platform.THREEDO),
                libraryName = "opera",
                isBundled = true,
                isLemuroidDefault = true,
                downloadSizeMb = 0.5f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD
                )
            ),

            // ═══════════════════════════════════════════════════════
            // ══ VortexFramework — Standalone Emulators ═════════════
            // ═══════════════════════════════════════════════════════
            // These launch standalone emulator apps directly, bypassing
            // libretro. Better GPU compatibility, especially on Mali.

            // ── PSP (Standalone) ────────────────────────────────
            CoreInfo(
                id = "vortex_psp_standalone",
                name = "PPSSPP Standalone",
                displayName = "★ PPSSPP Standalone (PSP)",
                version = "1.17+",
                author = "Henrik Rydgård",
                description = "Launch PPSSPP standalone app. Perfect GPU compatibility on all chipsets including Mali. Recommended for PSP.",
                supportedPlatforms = listOf(Platform.PSP),
                libraryName = "ppsspp_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS,
                    CoreFeature.NETPLAY, CoreFeature.WIDESCREEN_HACK
                ),
                isStandalone = true
            ),
            // ── PSP (Standalone — RetroArch) ────────────────────
            CoreInfo(
                id = "vortex_psp_retroarch",
                name = "RetroArch (PSP)",
                displayName = "RetroArch Standalone (PSP)",
                version = "1.19+",
                author = "libretro Team",
                description = "Launch RetroArch standalone with PPSSPP core. Open-source multi-emulator with full shader and overlay support.",
                supportedPlatforms = listOf(Platform.PSP),
                libraryName = "retroarch_psp_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS
                ),
                isStandalone = true
            ),
            // ── PSP (Standalone — Lemuroid) ─────────────────────
            CoreInfo(
                id = "vortex_psp_lemuroid",
                name = "Lemuroid (PSP)",
                displayName = "Lemuroid Standalone (PSP)",
                version = "1.15+",
                author = "Nicholás Opuni",
                description = "Launch Lemuroid, an open-source multi-system emulator with PSP support. Simple UI, auto-scan ROMs.",
                supportedPlatforms = listOf(Platform.PSP),
                libraryName = "lemuroid_psp_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.ANALOG_STICK, CoreFeature.OPENGL_RENDERER
                ),
                isStandalone = true
            ),
            // ── N64 (Standalone) ────────────────────────────────
            CoreInfo(
                id = "vortex_n64_standalone",
                name = "Mupen64Plus FZ",
                displayName = "★ Mupen64Plus FZ Standalone (N64)",
                version = "3.0+",
                author = "Francisco Zurita",
                description = "Launch Mupen64Plus FZ standalone. Best N64 compatibility with GlideN64, all GPUs supported.",
                supportedPlatforms = listOf(Platform.N64),
                libraryName = "mupen64_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.NETPLAY
                ),
                isStandalone = true
            ),
            // ── GameCube / Wii (Standalone) ─────────────────────
            CoreInfo(
                id = "vortex_gcn_standalone",
                name = "Dolphin Standalone",
                displayName = "★ Dolphin Standalone (GCN/Wii)",
                version = "5.0+",
                author = "Dolphin Team",
                description = "Launch Dolphin standalone. Full Vulkan/OpenGL support, perfect on Mali GPUs.",
                supportedPlatforms = listOf(Platform.GAMECUBE, Platform.WII),
                libraryName = "dolphin_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE,
                    CoreFeature.WIDESCREEN_HACK, CoreFeature.NETPLAY
                ),
                isStandalone = true
            ),
            // ── 3DS (Standalone) ────────────────────────────────
            CoreInfo(
                id = "vortex_3ds_standalone",
                name = "Lime3DS / Citra",
                displayName = "★ Lime3DS Standalone (3DS)",
                version = "2024+",
                author = "Lime3DS / Citra Team",
                description = "Launch Lime3DS or Citra standalone. Full OpenGL rendering, touch screen support.",
                supportedPlatforms = listOf(Platform.THREEDS),
                libraryName = "citra_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.TOUCH_OVERLAY, CoreFeature.SHADERS
                ),
                isStandalone = true
            ),
            // ── Dreamcast (Standalone) ──────────────────────────
            CoreInfo(
                id = "vortex_dc_standalone",
                name = "Flycast / Redream",
                displayName = "★ Flycast Standalone (Dreamcast)",
                version = "2024+",
                author = "Flycast / Redream Team",
                description = "Launch Flycast or Redream standalone. Native Vulkan rendering, best Dreamcast experience.",
                supportedPlatforms = listOf(Platform.DREAMCAST),
                libraryName = "flycast_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.HIGH_RESOLUTION, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.RUMBLE
                ),
                isStandalone = true
            ),
            // ── PS2 (Standalone) ────────────────────────────────
            CoreInfo(
                id = "vortex_ps2_standalone",
                name = "AetherSX2 / NetherSX2",
                displayName = "★ AetherSX2 Standalone (PS2)",
                version = "2024+",
                author = "Tahlreth / Community",
                description = "Launch AetherSX2 or NetherSX2 standalone. The only viable PS2 emulation on Android.",
                supportedPlatforms = listOf(Platform.PS2),
                libraryName = "aethersx2_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.HIGH_RESOLUTION,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE,
                    CoreFeature.WIDESCREEN_HACK
                ),
                isStandalone = true
            ),
            // ── PSX (Standalone) ────────────────────────────────
            CoreInfo(
                id = "vortex_psx_standalone",
                name = "DuckStation",
                displayName = "★ DuckStation Standalone (PSX)",
                version = "2024+",
                author = "stenzek",
                description = "Launch DuckStation standalone. Best PSX emulator with Vulkan, hardware rendering, upscaling.",
                supportedPlatforms = listOf(Platform.PSX),
                libraryName = "duckstation_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.HIGH_RESOLUTION, CoreFeature.WIDESCREEN_HACK,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.ANALOG_STICK, CoreFeature.RUMBLE, CoreFeature.SHADERS
                ),
                isStandalone = true
            ),
            // ── Saturn (Standalone) ─────────────────────────────
            CoreInfo(
                id = "vortex_sat_standalone",
                name = "Yaba Sanshiro",
                displayName = "★ Yaba Sanshiro Standalone (Saturn)",
                version = "2024+",
                author = "devMiyax",
                description = "Launch Yaba Sanshiro standalone. Best Saturn emulation on Android with OpenGL rendering.",
                supportedPlatforms = listOf(Platform.SATURN),
                libraryName = "yabause_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK
                ),
                isStandalone = true
            ),
            // ── NDS (Standalone) ────────────────────────────────
            CoreInfo(
                id = "vortex_nds_standalone",
                name = "DraStic / melonDS",
                displayName = "★ DraStic Standalone (NDS)",
                version = "2024+",
                author = "Exophase / Arisotura",
                description = "Launch DraStic or melonDS standalone. Best DS experience with hardware rendering.",
                supportedPlatforms = listOf(Platform.NDS),
                libraryName = "drastic_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.TOUCH_OVERLAY,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.HIGH_RESOLUTION
                ),
                isStandalone = true
            ),
            // ── PS Vita (Standalone) ────────────────────────────
            CoreInfo(
                id = "vortex_vita_standalone",
                name = "Vita3K",
                displayName = "★ Vita3K Standalone (PS Vita)",
                version = "2024+",
                author = "Vita3K Team",
                description = "Launch Vita3K standalone. Experimental PS Vita emulation on Android with Vulkan support.",
                supportedPlatforms = listOf(Platform.VITA),
                libraryName = "vita3k_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.VULKAN_RENDERER,
                    CoreFeature.OPENGL_RENDERER, CoreFeature.ANALOG_STICK,
                    CoreFeature.TOUCH_OVERLAY, CoreFeature.RUMBLE
                ),
                isStandalone = true
            ),
            // ── Lemuroid (Multi-System Standalone) ──────────────
            CoreInfo(
                id = "vortex_multi_lemuroid",
                name = "Lemuroid",
                displayName = "★ Lemuroid (Multi-System)",
                version = "2024+",
                author = "nicholasopuni31",
                description = "All-in-one retro emulator. Supports NES, SNES, GBA, GBC, N64, Genesis, PSX, DS, Arcade and more in a single app.",
                supportedPlatforms = listOf(
                    Platform.NES, Platform.SNES, Platform.GBA, Platform.GBC,
                    Platform.N64, Platform.GENESIS, Platform.PSX, Platform.NDS, Platform.ARCADE
                ),
                libraryName = "lemuroid_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.SHADERS, CoreFeature.TOUCH_OVERLAY
                ),
                isStandalone = true
            ),
            // ── SNES (Standalone) ───────────────────────────────
            CoreInfo(
                id = "vortex_snes_standalone",
                name = "Snes9x EX+",
                displayName = "★ Snes9x EX+ Standalone (SNES)",
                version = "2024+",
                author = "Robert Broglia",
                description = "Launch Snes9x EX+ standalone. Lightweight SNES emulator with excellent performance.",
                supportedPlatforms = listOf(Platform.SNES),
                libraryName = "snes9x_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.SHADERS
                ),
                isStandalone = true
            ),
            // ── GBA (Standalone) ────────────────────────────────
            CoreInfo(
                id = "vortex_gba_standalone",
                name = "mGBA Standalone",
                displayName = "★ mGBA Standalone (GBA/GBC/GB)",
                version = "2024+",
                author = "endrift",
                description = "Launch mGBA standalone app. Accurate GBA/GBC/GB emulator with link cable and rumble support.",
                supportedPlatforms = listOf(Platform.GBA, Platform.GBC),
                libraryName = "mgba_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.FAST_FORWARD,
                    CoreFeature.CHEATS, CoreFeature.SHADERS, CoreFeature.RUMBLE,
                    CoreFeature.NETPLAY
                ),
                isStandalone = true
            ),
            // ── RetroArch (Multi-System Standalone) ─────────────
            CoreInfo(
                id = "vortex_multi_retroarch",
                name = "RetroArch",
                displayName = "★ RetroArch (Multi-System)",
                version = "1.19+",
                author = "Libretro Team",
                description = "The ultimate multi-system frontend. Supports 80+ emulator cores with unified UI, shaders, netplay.",
                supportedPlatforms = listOf(
                    Platform.NES, Platform.SNES, Platform.N64, Platform.GBA, Platform.GBC,
                    Platform.NDS, Platform.GENESIS, Platform.PSX, Platform.PSP,
                    Platform.DREAMCAST, Platform.ARCADE, Platform.SATURN,
                    Platform.GAMECUBE, Platform.WII, Platform.PS2
                ),
                libraryName = "retroarch_standalone",
                isBundled = false,
                downloadSizeMb = 0f,
                features = setOf(
                    CoreFeature.SAVE_STATES, CoreFeature.REWIND,
                    CoreFeature.FAST_FORWARD, CoreFeature.CHEATS,
                    CoreFeature.SHADERS, CoreFeature.NETPLAY,
                    CoreFeature.VULKAN_RENDERER, CoreFeature.OPENGL_RENDERER,
                    CoreFeature.RUMBLE, CoreFeature.ANALOG_STICK,
                    CoreFeature.WIDESCREEN_HACK, CoreFeature.HIGH_RESOLUTION
                ),
                isStandalone = true
            )
        )

        _availableCores.value = catalog
        // Mark cores as installed: libretro cores check disk cache, standalone cores check installed apps
        _installedCores.value = catalog.map {
            if (it.isStandalone) {
                it.copy(isInstalled = standaloneLauncher.isAnyEmulatorInstalled(it))
            } else {
                it.copy(isInstalled = coreDownloader.isCoreDownloaded(it.libraryName))
            }
        }
    }

    /** Refresh installed status (call after a core download completes). */
    fun refreshInstalled() {
        _installedCores.value = _availableCores.value.map {
            if (it.isStandalone) {
                it.copy(isInstalled = standaloneLauncher.isAnyEmulatorInstalled(it))
            } else {
                it.copy(isInstalled = coreDownloader.isCoreDownloaded(it.libraryName))
            }
        }
    }

    fun getCoresForPlatform(platform: Platform): List<CoreInfo> {
        return _availableCores.value.filter { platform in it.supportedPlatforms }
    }

    private val preferredCores = mutableMapOf<Platform, String>()

    fun getPreferredCore(platform: Platform): CoreInfo? {
        val preferredId = preferredCores[platform]
        val cores = getCoresForPlatform(platform)
        if (preferredId != null) {
            val preferred = cores.find { it.id == preferredId }
            if (preferred != null) return preferred
        }
        // Prefer libretro cores over standalone — the app's built-in multiplayer
        // (LAN + Internet netplay) only works with libretro cores.  Standalone
        // emulators are still available for manual selection by the user.
        val libretroCore = cores.firstOrNull { !it.isStandalone }
        if (libretroCore != null) return libretroCore
        // Fall back to installed standalone if no libretro core exists
        val installedStandalone = cores.find { it.isStandalone && standaloneLauncher.isAnyEmulatorInstalled(it) }
        if (installedStandalone != null) return installedStandalone
        return cores.firstOrNull()
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
