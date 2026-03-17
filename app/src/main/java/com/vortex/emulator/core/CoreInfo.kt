package com.vortex.emulator.core

/**
 * Information about an emulation core.
 * A core can be either a libretro shared library (.so) or a standalone emulator
 * app launched via Android Intent (VortexFramework standalone path).
 */
data class CoreInfo(
    val id: String,
    val name: String,
    val displayName: String,
    val version: String,
    val author: String,
    val description: String,
    val supportedPlatforms: List<Platform>,
    val libraryName: String,
    val isInstalled: Boolean = false,
    val isBundled: Boolean = false,
    val downloadUrl: String = "",
    val downloadSizeMb: Float = 0f,
    val features: Set<CoreFeature> = emptySet(),
    /** If true, this core is launched as a standalone app via StandaloneLauncher. */
    val isStandalone: Boolean = false,
    /** If true, this core is Lemuroid-recommended (curated for reliability on Android). */
    val isLemuroidDefault: Boolean = false
)

enum class CoreFeature {
    SAVE_STATES,
    REWIND,
    FAST_FORWARD,
    CHEATS,
    NETPLAY,
    SHADERS,
    RUMBLE,
    ANALOG_STICK,
    TOUCH_OVERLAY,
    HIGH_RESOLUTION,
    WIDESCREEN_HACK,
    VULKAN_RENDERER,
    OPENGL_RENDERER
}
