package com.vortex.emulator.core

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rendering backend that a core can use. Overrides the emulator-level default.
 */
enum class RenderBackend(val displayName: String, val description: String) {
    VULKAN("Vulkan", "Best performance on modern GPUs"),
    ASHES("Ashes", "Vulkan translation layer — wider compatibility"),
    OPENGL("OpenGL", "Legacy desktop-class API, broadest driver support"),
    OPENGL_ES("OpenGL ES", "Mobile-optimized subset of OpenGL"),
    SOFTWARE("Software Rendering", "CPU-only — slowest but most accurate");

    companion object {
        fun fromName(name: String): RenderBackend =
            entries.firstOrNull { it.name == name } ?: VULKAN
    }
}

/**
 * Display resolution presets for the emulated screen.
 */
enum class DisplayResolution(val displayName: String, val multiplier: Int) {
    NATIVE("1× Native", 1),
    X2("2× Native", 2),
    X3("3× Native", 3),
    X4("4× Native", 4),
    X5("5× Native", 5),
    X6("6× Native", 6),
    X8("8× Native", 8),
    X10("10× Native", 10);

    companion object {
        fun fromName(name: String): DisplayResolution =
            entries.firstOrNull { it.name == name } ?: X2
    }
}

/**
 * MSAA anti-aliasing levels.
 */
enum class AntiAliasing(val displayName: String, val samples: Int) {
    OFF("Off", 0),
    X2("2× MSAA", 2),
    X4("4× MSAA", 4),
    X8("8× MSAA", 8);

    companion object {
        fun fromName(name: String): AntiAliasing =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Texture filtering methods.
 */
enum class TextureFilter(val displayName: String) {
    AUTO("Auto"),
    NEAREST("Nearest (sharp pixels)"),
    LINEAR("Linear (smooth)");

    companion object {
        fun fromName(name: String): TextureFilter =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Texture upscaling type.
 */
enum class TextureUpscaleType(val displayName: String) {
    XBRZ("xBRZ"),
    HYBRID("Hybrid"),
    BICUBIC("Bicubic"),
    HYBRID_BICUBIC("Hybrid + Bicubic");

    companion object {
        fun fromName(name: String): TextureUpscaleType =
            entries.firstOrNull { it.name == name } ?: XBRZ
    }
}

/**
 * Texture upscaling level.
 */
enum class TextureUpscaleLevel(val displayName: String, val multiplier: Int) {
    OFF("Off", 1),
    X2("2×", 2),
    X3("3×", 3),
    X4("4×", 4),
    X5("5×", 5);

    companion object {
        fun fromName(name: String): TextureUpscaleLevel =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Frame skip mode.
 */
enum class FrameSkipMode(val displayName: String) {
    OFF("Off"),
    AUTO("Auto"),
    SKIP_1("1 frame"),
    SKIP_2("2 frames"),
    SKIP_3("3 frames");

    companion object {
        fun fromName(name: String): FrameSkipMode =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Device screen output resolution.
 */
enum class ScreenResolution(val displayName: String) {
    NATIVE("Native Device Resolution"),
    HD("1280×720"),
    FHD("1920×1080");

    companion object {
        fun fromName(name: String): ScreenResolution =
            entries.firstOrNull { it.name == name } ?: NATIVE
    }
}

/**
 * Lens flare occlusion quality.
 */
enum class LensFlareOcclusion(val displayName: String) {
    OFF("Off"),
    LOW("Low (Default)"),
    MEDIUM("Medium"),
    HIGH("High");

    companion object {
        fun fromName(name: String): LensFlareOcclusion =
            entries.firstOrNull { it.name == name } ?: LOW
    }
}

/**
 * Spline / Bezier curve quality.
 */
enum class SplineBezierQuality(val displayName: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High (Default)");

    companion object {
        fun fromName(name: String): SplineBezierQuality =
            entries.firstOrNull { it.name == name } ?: HIGH
    }
}

/**
 * Input polling strategy.
 */
enum class InputPollingMode(val displayName: String, val description: String) {
    NORMAL("Normal", "Core decides when to poll (default)"),
    EARLY("Early", "Poll inputs before the frame — fixes controllers in some cores"),
    LATE("Late", "Poll inputs after the frame — may reduce latency");

    companion object {
        fun fromName(name: String): InputPollingMode =
            entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * Shader precision override for GPU compatibility.
 */
enum class ShaderPrecision(val displayName: String, val description: String) {
    AUTO("Auto", "Let the GPU choose (default)"),
    FORCE_LOW("Force Medium/Low", "Better compatibility on Mali GPUs — may fix black screens"),
    FORCE_HIGH("Force High", "More accurate — may cause issues on some mobile GPUs");

    companion object {
        fun fromName(name: String): ShaderPrecision =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * Persisted per-core settings stored in SharedPreferences.
 */
@Singleton
class CoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private fun prefsFor(coreId: String): SharedPreferences =
        context.getSharedPreferences("vortex_core_settings_$coreId", Context.MODE_PRIVATE)

    fun load(coreId: String): CoreSettingsData {
        val prefs = prefsFor(coreId)
        return CoreSettingsData(
            // Rendering
            renderBackend = RenderBackend.fromName(prefs.getString("render_backend", RenderBackend.OPENGL_ES.name)!!),
            displayResolution = DisplayResolution.fromName(prefs.getString("display_resolution", DisplayResolution.X2.name)!!),
            softwareRendering = prefs.getBoolean("software_rendering", false),
            antiAliasing = AntiAliasing.fromName(prefs.getString("anti_aliasing", AntiAliasing.OFF.name)!!),
            textureReplacement = prefs.getBoolean("texture_replacement", true),

            // Display
            showLayoutEditor = prefs.getBoolean("show_layout_editor", false),
            lowLatencyDisplay = prefs.getBoolean("low_latency_display", true),
            screenResolution = ScreenResolution.fromName(prefs.getString("screen_resolution", ScreenResolution.NATIVE.name)!!),

            // FPS Control
            frameSkip = FrameSkipMode.fromName(prefs.getString("frame_skip", FrameSkipMode.OFF.name)!!),
            autoFrameSkip = prefs.getBoolean("auto_frame_skip", false),
            altSpeed = prefs.getInt("alt_speed", 0),
            altSpeed2 = prefs.getInt("alt_speed_2", 0),

            // Speed Hacks
            skipBufferEffects = prefs.getBoolean("skip_buffer_effects", false),
            disableCulling = prefs.getBoolean("disable_culling", false),
            skipGpuReadbacks = prefs.getBoolean("skip_gpu_readbacks", false),
            lazyCaching = prefs.getBoolean("lazy_caching", false),
            lowerEffectsResolution = prefs.getBoolean("lower_effects_resolution", false),
            lensFlareOcclusion = LensFlareOcclusion.fromName(prefs.getString("lens_flare_occlusion", LensFlareOcclusion.LOW.name)!!),

            // Performance
            duplicateFrames60Hz = prefs.getBoolean("duplicate_frames_60hz", false),
            commandBuffer = prefs.getInt("command_buffer", 2),
            geometryShaderCulling = prefs.getBoolean("geometry_shader_culling", false),
            hardwareTransform = prefs.getBoolean("hardware_transform", true),
            softwareSkinning = prefs.getBoolean("software_skinning", true),
            hardwareTessellation = prefs.getBoolean("hardware_tessellation", false),
            splineBezierQuality = SplineBezierQuality.fromName(prefs.getString("spline_bezier_quality", SplineBezierQuality.HIGH.name)!!),

            // Texture Scaling
            textureShaderGpu = prefs.getBoolean("texture_shader_gpu", false),
            textureUpscaleType = TextureUpscaleType.fromName(prefs.getString("texture_upscale_type", TextureUpscaleType.XBRZ.name)!!),
            textureUpscaleLevel = TextureUpscaleLevel.fromName(prefs.getString("texture_upscale_level", TextureUpscaleLevel.OFF.name)!!),
            deposterize = prefs.getBoolean("deposterize", false),

            // Texture Filtering
            anisotropicFiltering = prefs.getInt("anisotropic_filtering", 16),
            textureFilterMode = TextureFilter.fromName(prefs.getString("texture_filter_mode", TextureFilter.AUTO.name)!!),
            smart2DFiltering = prefs.getBoolean("smart_2d_filtering", false),

            // Overlay
            showFpsCounter = prefs.getBoolean("show_fps_counter", false),
            showSpeed = prefs.getBoolean("show_speed", false),

            // Controls
            hapticFeedback = prefs.getBoolean("haptic_feedback", true),
            touchControlOpacity = prefs.getFloat("touch_control_opacity", 0.65f),
            touchControlScale = prefs.getFloat("touch_control_scale", 1.0f),

            // Advanced (Gemini suggestions)
            inputPollingMode = InputPollingMode.fromName(prefs.getString("input_polling_mode", InputPollingMode.NORMAL.name)!!),
            threadedRendering = prefs.getBoolean("threaded_rendering", false),
            forceShaderPrecision = ShaderPrecision.fromName(prefs.getString("force_shader_precision", ShaderPrecision.AUTO.name)!!),
            rewindEnabled = prefs.getBoolean("rewind_enabled", false),
            rewindBufferSeconds = prefs.getInt("rewind_buffer_seconds", 10),

            // Frontend
            frontendType = prefs.getString("frontend_type", null)
        )
    }

    fun save(coreId: String, settings: CoreSettingsData) {
        prefsFor(coreId).edit().apply {
            putString("render_backend", settings.renderBackend.name)
            putString("display_resolution", settings.displayResolution.name)
            putBoolean("software_rendering", settings.softwareRendering)
            putString("anti_aliasing", settings.antiAliasing.name)
            putBoolean("texture_replacement", settings.textureReplacement)

            putBoolean("show_layout_editor", settings.showLayoutEditor)
            putBoolean("low_latency_display", settings.lowLatencyDisplay)
            putString("screen_resolution", settings.screenResolution.name)

            putString("frame_skip", settings.frameSkip.name)
            putBoolean("auto_frame_skip", settings.autoFrameSkip)
            putInt("alt_speed", settings.altSpeed)
            putInt("alt_speed_2", settings.altSpeed2)

            putBoolean("skip_buffer_effects", settings.skipBufferEffects)
            putBoolean("disable_culling", settings.disableCulling)
            putBoolean("skip_gpu_readbacks", settings.skipGpuReadbacks)
            putBoolean("lazy_caching", settings.lazyCaching)
            putBoolean("lower_effects_resolution", settings.lowerEffectsResolution)
            putString("lens_flare_occlusion", settings.lensFlareOcclusion.name)

            putBoolean("duplicate_frames_60hz", settings.duplicateFrames60Hz)
            putInt("command_buffer", settings.commandBuffer)
            putBoolean("geometry_shader_culling", settings.geometryShaderCulling)
            putBoolean("hardware_transform", settings.hardwareTransform)
            putBoolean("software_skinning", settings.softwareSkinning)
            putBoolean("hardware_tessellation", settings.hardwareTessellation)
            putString("spline_bezier_quality", settings.splineBezierQuality.name)

            putBoolean("texture_shader_gpu", settings.textureShaderGpu)
            putString("texture_upscale_type", settings.textureUpscaleType.name)
            putString("texture_upscale_level", settings.textureUpscaleLevel.name)
            putBoolean("deposterize", settings.deposterize)

            putInt("anisotropic_filtering", settings.anisotropicFiltering)
            putString("texture_filter_mode", settings.textureFilterMode.name)
            putBoolean("smart_2d_filtering", settings.smart2DFiltering)

            putBoolean("show_fps_counter", settings.showFpsCounter)
            putBoolean("show_speed", settings.showSpeed)

            putBoolean("haptic_feedback", settings.hapticFeedback)
            putFloat("touch_control_opacity", settings.touchControlOpacity)
            putFloat("touch_control_scale", settings.touchControlScale)

            putString("input_polling_mode", settings.inputPollingMode.name)
            putBoolean("threaded_rendering", settings.threadedRendering)
            putString("force_shader_precision", settings.forceShaderPrecision.name)
            putBoolean("rewind_enabled", settings.rewindEnabled)
            putInt("rewind_buffer_seconds", settings.rewindBufferSeconds)

            settings.frontendType?.let { putString("frontend_type", it) }
                ?: remove("frontend_type")

            apply()
        }
    }
}

/**
 * All per-core configurable settings.
 */
data class CoreSettingsData(
    // Rendering Mode
    val renderBackend: RenderBackend = RenderBackend.OPENGL_ES,
    val displayResolution: DisplayResolution = DisplayResolution.X2,
    val softwareRendering: Boolean = false,
    val antiAliasing: AntiAliasing = AntiAliasing.OFF,
    val textureReplacement: Boolean = true,

    // Display
    val showLayoutEditor: Boolean = false,
    val lowLatencyDisplay: Boolean = true,
    val screenResolution: ScreenResolution = ScreenResolution.NATIVE,

    // FPS Control
    val frameSkip: FrameSkipMode = FrameSkipMode.OFF,
    val autoFrameSkip: Boolean = false,
    val altSpeed: Int = 0,          // 0 = unlimited
    val altSpeed2: Int = 0,         // 0 = disabled

    // Speed Hacks
    val skipBufferEffects: Boolean = false,
    val disableCulling: Boolean = false,
    val skipGpuReadbacks: Boolean = false,
    val lazyCaching: Boolean = false,
    val lowerEffectsResolution: Boolean = false,
    val lensFlareOcclusion: LensFlareOcclusion = LensFlareOcclusion.LOW,

    // Performance
    val duplicateFrames60Hz: Boolean = false,
    val commandBuffer: Int = 2,      // Up to N
    val geometryShaderCulling: Boolean = false,
    val hardwareTransform: Boolean = true,
    val softwareSkinning: Boolean = true,
    val hardwareTessellation: Boolean = false,
    val splineBezierQuality: SplineBezierQuality = SplineBezierQuality.HIGH,

    // Texture Scaling
    val textureShaderGpu: Boolean = false,
    val textureUpscaleType: TextureUpscaleType = TextureUpscaleType.XBRZ,
    val textureUpscaleLevel: TextureUpscaleLevel = TextureUpscaleLevel.OFF,
    val deposterize: Boolean = false,

    // Texture Filtering
    val anisotropicFiltering: Int = 16,
    val textureFilterMode: TextureFilter = TextureFilter.AUTO,
    val smart2DFiltering: Boolean = false,

    // Overlay / Debug
    val showFpsCounter: Boolean = false,
    val showSpeed: Boolean = false,

    // Controls
    val hapticFeedback: Boolean = true,
    val touchControlOpacity: Float = 0.65f,
    val touchControlScale: Float = 1.0f,

    // Advanced (Gemini-suggested fixes)
    val inputPollingMode: InputPollingMode = InputPollingMode.NORMAL,
    val threadedRendering: Boolean = false,
    val forceShaderPrecision: ShaderPrecision = ShaderPrecision.AUTO,
    val rewindEnabled: Boolean = false,
    val rewindBufferSeconds: Int = 10,

    // Frontend engine override (null = use global setting)
    val frontendType: String? = null
)
