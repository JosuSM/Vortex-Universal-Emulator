package com.vortex.emulator.core

/**
 * Information about an emulation core.
 * Each core is a shared library (.so) that implements emulation for one or more platforms.
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
    val features: Set<CoreFeature> = emptySet()
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
