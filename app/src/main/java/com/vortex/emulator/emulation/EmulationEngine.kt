package com.vortex.emulator.emulation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.vortex.emulator.core.*
import com.vortex.emulator.gpu.ChipsetDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmulationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coreDownloader: CoreDownloader,
    val frontend: FrontendBridge,
    private val coreSettingsRepo: CoreSettingsRepository,
    private val chipsetDetector: ChipsetDetector
) {
    companion object {
        private const val TAG = "EmulationEngine"
        private const val REWIND_BUFFER_SIZE = 120 // ~2 seconds at 60fps
    }

    private val audioPlayer = AudioPlayer()

    @Volatile var isRunning = false; private set
    @Volatile var isPaused = false
    @Volatile var currentFps = 0f; private set
    @Volatile var fastForwardEnabled = false
    @Volatile var rewindEnabled = false
    @Volatile var audioEnabled = true
    @Volatile var autoFrameSkip = true   // Auto-adjust frame skip for performance
    @Volatile var manualFrameSkip = 0    // Manual frame skip level (0 = auto/off)

    /** Called each frame before runFrame(). Used to apply remote inputs in multiplayer. */
    @Volatile var onPreFrame: (() -> Unit)? = null
    /** Called each frame after runFrame(). Used to send local input in multiplayer. */
    @Volatile var onPostFrame: (() -> Unit)? = null

    private var emulationThread: Thread? = null

    // Rewind buffer: circular list of serialized states
    private val rewindBuffer = ArrayDeque<ByteArray>(REWIND_BUFFER_SIZE)
    private var rewindActive = false
    @Volatile var rewindBufferSize = REWIND_BUFFER_SIZE

    // Screenshot callback
    private var screenshotCallback: ((Bitmap) -> Unit)? = null

    // Custom save directory (null = default)
    var customSaveDir: String? = null

    private val effectiveSaveDir: String
        get() = customSaveDir ?: coreDownloader.saveDir

    suspend fun prepare(libraryName: String, romUri: String, coreId: String? = null): String? = withContext(Dispatchers.IO) {
        val corePath = coreDownloader.ensureCore(libraryName)
            ?: return@withContext when (coreDownloader.lastError) {
                "offline" -> "Core '$libraryName' is not downloaded. Connect to the internet to download it, or use an already cached core."
                else -> "Failed to download core '$libraryName'. Check your internet connection."
            }

        val romPath = resolveRomPath(romUri)
            ?: return@withContext "Cannot access ROM file. It may have been moved or deleted."

        Log.i(TAG, "Resolved ROM path: $romPath (${File(romPath).length()} bytes)")

        // Set Vulkan display backend BEFORE loadCore if user selected Vulkan/Ashes.
        // Note: PPSSPP's libretro core uses GLES internally. Vulkan display uses a
        // GL→Vulkan readback path that may cause issues on some GPUs. GL display is
        // recommended for PPSSPP, but we respect the user's explicit choice.
        if (coreId != null) {
            val settings = coreSettingsRepo.load(coreId)
            val nativeBackend = when {
                settings.renderBackend == RenderBackend.VULKAN || settings.renderBackend == RenderBackend.ASHES -> 1
                else -> 0
            }
            frontend.setRenderBackend(nativeBackend)
            if (libraryName == "ppsspp" && nativeBackend == 1) {
                Log.w(TAG, "Vulkan display selected for PPSSPP — may cause rendering issues. GL recommended.")
            }
            Log.i(TAG, "Display backend set to ${if (nativeBackend == 1) "Vulkan" else "GL"}")
        }

        val result = frontend.loadCore(
            corePath,
            coreDownloader.systemDir,
            effectiveSaveDir
        )
        if (result != 0) {
            return@withContext "Failed to load emulation core (error $result)"
        }

        // Set performance-friendly defaults for demanding cores before loading game.
        // Skip defaults if user has already customized settings for this core —
        // applyCoreSettings will apply the user's choices instead.
        val hasCustom = coreId != null && coreSettingsRepo.hasCustomSettings(coreId)
        if (!hasCustom) {
            applyPerformanceDefaults(libraryName, coreId)
        } else {
            Log.i(TAG, "Skipping performance defaults — user has custom settings for $coreId")
        }

        // Apply user's per-core settings BEFORE loadGame — cores read options during init
        if (coreId != null) {
            applyCoreSettings(coreId, libraryName)
        }

        val loaded = frontend.loadGame(romPath)
        if (!loaded) {
            // If failed, try invalidating cached ROM and re-resolve
            Log.w(TAG, "First loadGame attempt failed, invalidating ROM cache...")
            invalidateRomCache(romUri)
            val freshRomPath = resolveRomPath(romUri)
            if (freshRomPath != null) {
                Log.i(TAG, "Retrying with fresh ROM: $freshRomPath (${File(freshRomPath).length()} bytes)")
                val retryLoaded = frontend.loadGame(freshRomPath)
                if (retryLoaded) {
                    // Re-apply per-core settings after loadGame (native may overwrite some options)
                    if (coreId != null) applyCoreSettings(coreId, libraryName)
                    Log.i(TAG, "Ready (after retry): core=$libraryName, rom=$freshRomPath, fps=${frontend.getFps()}")
                    return@withContext null
                }
            }
            return@withContext "Failed to load ROM. The file may be corrupted or unsupported."
        }

        // Re-apply per-core settings after loadGame — the native loadGame() hardcodes
        // some options (e.g. mupen64plus-EnableFBEmulation=True) which may override
        // user settings. Re-applying here ensures user choices take final precedence
        // and cores will pick them up via GET_VARIABLE_UPDATE on next retro_run().
        if (coreId != null) {
            applyCoreSettings(coreId, libraryName)
        }

        Log.i(TAG, "Ready: core=$libraryName, rom=$romPath, fps=${frontend.getFps()}")
        null
    }

    /** Apply performance-friendly core option defaults for demanding platforms. */
    private fun applyPerformanceDefaults(libraryName: String, coreId: String? = null) {
        when {
            libraryName.startsWith("mupen64plus") -> {
                frontend.setCoreOption("mupen64plus-cpucore", "dynamic_recompiler")
                // ThreadedRenderer MUST be False — we don't support shared EGL contexts
                // and the render thread would crash without one
                frontend.setCoreOption("mupen64plus-ThreadedRenderer", "False")
                // FB emulation ON — required for GlideN64 to produce pixels on mobile GPUs.
                // Without this, the renderer outputs zero (black) pixels.
                frontend.setCoreOption("mupen64plus-EnableFBEmulation", "True")
                frontend.setCoreOption("mupen64plus-EnableCopyColorToRDRAM", "Off")
                frontend.setCoreOption("mupen64plus-EnableCopyDepthToRDRAM", "Off")
                frontend.setCoreOption("mupen64plus-EnableN64DepthCompare", "False")
                frontend.setCoreOption("mupen64plus-FrameDuping", "True")
                frontend.setCoreOption("mupen64plus-Framerate", "Original")
                frontend.setCoreOption("mupen64plus-EnableHWLighting", "False")
                frontend.setCoreOption("mupen64plus-txFilterMode", "None")
                frontend.setCoreOption("mupen64plus-txHiresEnable", "False")
                Log.i(TAG, "Applied N64 performance defaults (ThreadedRenderer=False, EnableFBEmulation=True)")
            }
            libraryName == "ppsspp" -> {
                // Detect GPU vendor to apply optimal defaults.
                // Mali GPUs crash with PPSSPP's HW context_reset (GL_INVALID_VALUE → SIGABRT),
                // so software rendering is forced only on Mali. Adreno/PowerVR/other GPUs
                // can safely use hardware rendering for much better performance.
                val gpuRenderer = chipsetDetector.chipsetInfo.gpuInfo?.renderer?.lowercase() ?: ""
                val isMaliGpu = gpuRenderer.contains("mali")

                // Rocket PSP Engine profile: aggressive tuning for maximum performance
                val isRocket = coreId?.contains("rocket") == true
                if (isRocket) {
                    if (isMaliGpu) {
                        frontend.setCoreOption("ppsspp_software_rendering", "enabled")
                        frontend.setCoreOption("ppsspp_internal_resolution", "480x272")
                    } else {
                        frontend.setCoreOption("ppsspp_software_rendering", "disabled")
                        frontend.setCoreOption("ppsspp_internal_resolution", "2x")
                    }
                    frontend.setCoreOption("ppsspp_frameskip", "0")
                    frontend.setCoreOption("ppsspp_auto_frameskip", "disabled")
                    frontend.setCoreOption("ppsspp_fast_memory", "enabled")
                    frontend.setCoreOption("ppsspp_gpu_hardware_transform", "enabled")
                    frontend.setCoreOption("ppsspp_software_skinning", "enabled")
                    frontend.setCoreOption("ppsspp_texture_scaling_level", "1")
                    frontend.setCoreOption("ppsspp_texture_scaling_type", "xbrz")
                    // Rocket-specific aggressive performance options
                    frontend.setCoreOption("ppsspp_block_transfer_gpu", "enabled")
                    frontend.setCoreOption("ppsspp_skip_gpu_readbacks", "enabled")
                    frontend.setCoreOption("ppsspp_inflight_frames", "Up to 2")
                    frontend.setCoreOption("ppsspp_io_timing_method", "Fast")
                    frontend.setCoreOption("ppsspp_frame_duplication", "enabled")
                    frontend.setCoreOption("ppsspp_lower_resolution_for_effects", "Off")
                    Log.i(TAG, "Applied Rocket PSP Engine defaults (${if (isMaliGpu) "Mali/SW" else "HW/$gpuRenderer"})")
                } else {
                    if (isMaliGpu) {
                        frontend.setCoreOption("ppsspp_software_rendering", "enabled")
                        frontend.setCoreOption("ppsspp_internal_resolution", "480x272")
                        Log.i(TAG, "Applied PSP performance defaults (software rendering — Mali GPU detected)")
                    } else {
                        frontend.setCoreOption("ppsspp_software_rendering", "disabled")
                        frontend.setCoreOption("ppsspp_internal_resolution", "2x")
                        Log.i(TAG, "Applied PSP performance defaults (hardware rendering — ${gpuRenderer})")
                    }
                    // Disable PPSSPP's internal frameskip to avoid audio-video desync
                    frontend.setCoreOption("ppsspp_frameskip", "0")
                    frontend.setCoreOption("ppsspp_auto_frameskip", "disabled")
                    // Use fast memory access for better performance
                    frontend.setCoreOption("ppsspp_fast_memory", "enabled")
                    // GPU hardware transform for proper rendering (used when GPU mode)
                    frontend.setCoreOption("ppsspp_gpu_hardware_transform", "enabled")
                    // Software skinning for better compatibility
                    frontend.setCoreOption("ppsspp_software_skinning", "enabled")
                    // Texture scaling off for performance
                    frontend.setCoreOption("ppsspp_texture_scaling_level", "1")
                    frontend.setCoreOption("ppsspp_texture_scaling_type", "xbrz")
                }
            }
            libraryName == "flycast" -> {
                frontend.setCoreOption("flycast_internal_resolution", "640x480")
                frontend.setCoreOption("flycast_enable_dsp", "enabled")
                frontend.setCoreOption("flycast_synchronous_rendering", "enabled")
                frontend.setCoreOption("flycast_threaded_rendering", "disabled")
                Log.i(TAG, "Applied Dreamcast performance defaults")
            }
            libraryName == "dolphin" -> {
                frontend.setCoreOption("dolphin_efb_scale", "1x")
                frontend.setCoreOption("dolphin_enable_dual_core", "disabled")
                frontend.setCoreOption("dolphin_cpu_core", "JIT64")
                Log.i(TAG, "Applied GameCube/Wii performance defaults")
            }
            libraryName == "citra" -> {
                frontend.setCoreOption("citra_resolution_factor", "1")
                frontend.setCoreOption("citra_use_hw_renderer", "enabled")
                Log.i(TAG, "Applied 3DS performance defaults")
            }
            libraryName == "play" -> {
                frontend.setCoreOption("play_resolution_factor", "1")
                Log.i(TAG, "Applied PS2 performance defaults")
            }
        }
    }

    /**
     * Apply user's per-core settings from CoreSettingsRepository.
     * These override the hardcoded defaults above wherever applicable.
     * Maps CoreSettingsData fields → actual libretro core option keys.
     * Returns the loaded CoreSettingsData so callers can read UI-related fields.
     */
    fun applyCoreSettings(coreId: String, libraryName: String): CoreSettingsData {
        val s = coreSettingsRepo.load(coreId)
        Log.i(TAG, "Applying per-core settings for $coreId (backend=${s.renderBackend}, res=${s.displayResolution})")

        // ── Frontend override ──
        if (s.frontendType != null) {
            val type = FrontendType.fromName(s.frontendType)
            if (type != frontend.activeFrontend) {
                frontend.activeFrontend = type
                Log.i(TAG, "Core override: frontend → ${type.displayName}")
            }
        }

        // ── Render Backend ──
        // Vulkan/Ashes use Vulkan display layer (core still renders via GLES internally).
        // OpenGL/OpenGL ES use the native GLES display path.
        // Software forces the core's software renderer plugin.
        when (s.renderBackend) {
            RenderBackend.SOFTWARE -> {
                frontend.setRenderBackend(0) // GL display for software cores
                when {
                    libraryName == "ppsspp" ->
                        frontend.setCoreOption("ppsspp_software_rendering", "enabled")
                    libraryName.startsWith("mupen64plus") ->
                        frontend.setCoreOption("mupen64plus-rdp-plugin", "angrylion")
                }
                Log.i(TAG, "Backend: Software rendering forced")
            }
            RenderBackend.OPENGL, RenderBackend.OPENGL_ES -> {
                frontend.setRenderBackend(0) // GL display
                when {
                    libraryName == "ppsspp" ->
                        frontend.setCoreOption("ppsspp_software_rendering", "disabled")
                    libraryName.startsWith("mupen64plus") ->
                        frontend.setCoreOption("mupen64plus-rdp-plugin", "gliden64")
                }
                Log.i(TAG, "Backend: OpenGL ES (native display)")
            }
            RenderBackend.VULKAN, RenderBackend.ASHES -> {
                // Respect user's choice — even for PPSSPP which uses GLES internally.
                // The Vulkan display path uses GL→Vulkan readback which may have issues.
                frontend.setRenderBackend(1)
                when {
                    libraryName == "ppsspp" ->
                        frontend.setCoreOption("ppsspp_software_rendering", "disabled")
                    libraryName.startsWith("mupen64plus") ->
                        frontend.setCoreOption("mupen64plus-rdp-plugin", "gliden64")
                }
                Log.i(TAG, "Backend: Vulkan display (core uses GLES internally)")
            }
        }

        // ── Resolution ──
        val resMult = s.displayResolution.multiplier.toString()
        when {
            libraryName.startsWith("mupen64plus") -> {
                // GlideN64 uses aspect ratio + resolution factor, not screensize
                frontend.setCoreOption("mupen64plus-aspect", "4:3")
                frontend.setCoreOption("mupen64plus-43screensize",
                    "${s.displayResolution.multiplier * 320}x${s.displayResolution.multiplier * 240}")
                // Also set the internal resolution factor for GLideN64
                frontend.setCoreOption("mupen64plus-GLideN64InternalResolution",
                    "${s.displayResolution.multiplier}")
            }
            libraryName == "ppsspp" ->
                frontend.setCoreOption("ppsspp_internal_resolution", "${resMult}x")
            libraryName == "flycast" -> {
                val w = s.displayResolution.multiplier * 640
                val h = s.displayResolution.multiplier * 480
                frontend.setCoreOption("flycast_internal_resolution", "${w}x${h}")
            }
            libraryName == "dolphin" ->
                frontend.setCoreOption("dolphin_efb_scale", "${resMult}x")
            libraryName == "citra" || libraryName == "citra_canary" ->
                frontend.setCoreOption("citra_resolution_factor", resMult)
            libraryName == "play" ->
                frontend.setCoreOption("play_resolution_factor", resMult)
            libraryName == "swanstation" || libraryName == "beetle_psx_hw" ->
                frontend.setCoreOption("${libraryName}_gpu_resolution_scale", resMult)
        }

        // ── Anti-Aliasing ──
        if (s.antiAliasing != AntiAliasing.OFF) {
            val msaaVal = s.antiAliasing.samples.toString()
            when {
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_texture_anisotropic_filtering", msaaVal)
                libraryName.startsWith("mupen64plus") ->
                    frontend.setCoreOption("mupen64plus-MultiSampling", msaaVal)
                libraryName == "flycast" ->
                    frontend.setCoreOption("flycast_anisotropic_filtering", msaaVal)
                libraryName == "dolphin" ->
                    frontend.setCoreOption("dolphin_anti_aliasing", msaaVal)
                libraryName == "swanstation" || libraryName == "beetle_psx_hw" ->
                    frontend.setCoreOption("${libraryName}_gpu_msaa", msaaVal)
            }
        }

        // ── Frame Skip ──
        if (s.frameSkip != FrameSkipMode.OFF) {
            val skipVal = when (s.frameSkip) {
                FrameSkipMode.AUTO -> "auto"
                FrameSkipMode.SKIP_1 -> "1"
                FrameSkipMode.SKIP_2 -> "2"
                FrameSkipMode.SKIP_3 -> "3"
                else -> "0"
            }
            when {
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_frameskip", skipVal)
                else -> frontend.setFrameSkip(skipVal.toIntOrNull() ?: 0)
            }
        }

        // ── Auto Frame Skip ──
        if (s.autoFrameSkip) {
            autoFrameSkip = true
            if (libraryName == "ppsspp")
                frontend.setCoreOption("ppsspp_auto_frameskip", "enabled")
        }

        // ── Hardware Transform (PSP) ──
        if (libraryName == "ppsspp") {
            frontend.setCoreOption("ppsspp_gpu_hardware_transform",
                if (s.hardwareTransform) "enabled" else "disabled")
            frontend.setCoreOption("ppsspp_software_skinning",
                if (s.softwareSkinning) "enabled" else "disabled")
        }

        // ── Hardware Tessellation ──
        if (s.hardwareTessellation) {
            when {
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_hardware_tesselation", "enabled")
            }
        }

        // ── Geometry Shader Culling ──
        if (s.geometryShaderCulling) {
            when {
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_disable_range_culling", "disabled")
            }
        }

        // ── Texture Scaling ──
        if (libraryName == "ppsspp") {
            frontend.setCoreOption("ppsspp_texture_scaling_level",
                s.textureUpscaleLevel.multiplier.toString())
            frontend.setCoreOption("ppsspp_texture_scaling_type",
                s.textureUpscaleType.name.lowercase())
            frontend.setCoreOption("ppsspp_texture_deposterize",
                if (s.deposterize) "enabled" else "disabled")
            frontend.setCoreOption("ppsspp_texture_shader",
                if (s.textureShaderGpu) "enabled" else "disabled")
        }

        // ── Texture Filtering ──
        if (s.anisotropicFiltering > 1) {
            when {
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_texture_anisotropic_filtering",
                        s.anisotropicFiltering.toString())
                libraryName.startsWith("mupen64plus") ->
                    frontend.setCoreOption("mupen64plus-txFilterMode",
                        if (s.anisotropicFiltering >= 4) "Smooth filtering 2" else "Smooth filtering 1")
            }
        }

        // ── Smart 2D Texture Filtering ──
        if (s.smart2DFiltering && libraryName == "ppsspp") {
            frontend.setCoreOption("ppsspp_smart_2d_texture_filtering", "enabled")
        }

        // ── Texture Replacement ──
        if (s.textureReplacement) {
            when {
                libraryName.startsWith("mupen64plus") ->
                    frontend.setCoreOption("mupen64plus-txHiresEnable", "True")
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_texture_replacement", "enabled")
            }
        }

        // ── Speed Hacks ──
        if (libraryName.startsWith("mupen64plus")) {
            if (s.skipBufferEffects) {
                frontend.setCoreOption("mupen64plus-EnableFBEmulation", "False")
                frontend.setCoreOption("mupen64plus-EnableCopyColorToRDRAM", "Off")
            }
            if (s.lazyCaching) {
                frontend.setCoreOption("mupen64plus-txCacheCompression", "True")
            }
        }
        if (libraryName == "ppsspp") {
            if (s.disableCulling)
                frontend.setCoreOption("ppsspp_disable_range_culling", "enabled")
            if (s.skipGpuReadbacks)
                frontend.setCoreOption("ppsspp_skip_gpu_readbacks", "enabled")
            if (s.lowerEffectsResolution)
                frontend.setCoreOption("ppsspp_lower_resolution_for_effects", "enabled")
        }

        // ── Duplicate Frames for 60Hz ──
        if (s.duplicateFrames60Hz) {
            when {
                libraryName.startsWith("mupen64plus") ->
                    frontend.setCoreOption("mupen64plus-FrameDuping", "True")
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_frame_duplication", "enabled")
            }
        }

        // ── Command Buffer Count ──
        if (s.commandBuffer != 2 && libraryName == "ppsspp") {
            frontend.setCoreOption("ppsspp_inflight_frames", "Up to ${s.commandBuffer}")
        }

        // ── Spline / Bezier Quality (PSP) ──
        if (libraryName == "ppsspp") {
            val splineVal = when (s.splineBezierQuality) {
                SplineBezierQuality.LOW -> "low"
                SplineBezierQuality.MEDIUM -> "medium"
                SplineBezierQuality.HIGH -> "high"
            }
            frontend.setCoreOption("ppsspp_spline_quality", splineVal)
        }

        // ── Lens Flare Occlusion ──
        if (s.lensFlareOcclusion != LensFlareOcclusion.OFF && libraryName == "ppsspp") {
            val occlusionVal = when (s.lensFlareOcclusion) {
                LensFlareOcclusion.LOW -> "low"
                LensFlareOcclusion.MEDIUM -> "medium"
                LensFlareOcclusion.HIGH -> "high"
                else -> "low"
            }
            frontend.setCoreOption("ppsspp_gpu_occlusion_query", occlusionVal)
        }

        // ── Input Polling Mode (Gemini fix: "Early" fixes dead controllers) ──
        if (s.inputPollingMode != InputPollingMode.NORMAL) {
            val pollVal = when (s.inputPollingMode) {
                InputPollingMode.EARLY -> "early"
                InputPollingMode.LATE -> "late"
                else -> "normal"
            }
            // Generic libretro core option used by RetroArch-compatible cores
            frontend.setCoreOption("input_poll_type_behavior", pollVal)
            Log.i(TAG, "Input polling mode: ${s.inputPollingMode.name}")
        }

        // ── Threaded Video Rendering (Gemini fix: may reduce stuttering) ──
        if (s.threadedRendering) {
            when {
                libraryName.startsWith("mupen64plus") ->
                    frontend.setCoreOption("mupen64plus-ThreadedRenderer", "True")
                libraryName == "flycast" ->
                    frontend.setCoreOption("flycast_threaded_rendering", "enabled")
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_io_timing_method", "Fast")
            }
            Log.i(TAG, "Threaded rendering: enabled (user override)")
        }

        // ── Shader Precision Override (Gemini fix: Mali black screen workaround) ──
        if (s.forceShaderPrecision != ShaderPrecision.AUTO) {
            val precisionVal = when (s.forceShaderPrecision) {
                ShaderPrecision.FORCE_LOW -> "mediump"
                ShaderPrecision.FORCE_HIGH -> "highp"
                else -> "auto"
            }
            // Pass as a frontend-level option that native code can read
            frontend.setCoreOption("vortex_shader_precision", precisionVal)
            when {
                libraryName.startsWith("mupen64plus") ->
                    frontend.setCoreOption("mupen64plus-EnableShadersStorage", if (s.forceShaderPrecision == ShaderPrecision.FORCE_LOW) "False" else "True")
                libraryName == "ppsspp" ->
                    frontend.setCoreOption("ppsspp_shader_precision", precisionVal)
            }
            Log.i(TAG, "Shader precision: ${s.forceShaderPrecision.name}")
        }

        // ── Rewind ──
        rewindEnabled = s.rewindEnabled
        if (s.rewindEnabled) {
            rewindBufferSize = s.rewindBufferSeconds * 60 // ~60fps
            Log.i(TAG, "Rewind: enabled (buffer=${s.rewindBufferSeconds}s, ${rewindBufferSize} frames)")
        } else {
            rewindBufferSize = REWIND_BUFFER_SIZE
        }

        Log.i(TAG, "Per-core settings applied for $coreId")
        return s
    }

    /** Remove cached ROM file so next resolveRomPath forces a fresh copy. */
    private fun invalidateRomCache(romUri: String) {
        if (romUri.startsWith("content://")) {
            val uri = Uri.parse(romUri)
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "rom.bin"
            val tmpFile = File(File(context.cacheDir, "roms"), fileName)
            if (tmpFile.exists()) {
                tmpFile.delete()
                Log.i(TAG, "Invalidated cached ROM: ${tmpFile.name}")
            }
        }
    }

    /** Whether a SurfaceView surface is attached for direct GPU rendering. */
    @Volatile var surfaceReady = false; private set

    /** Attach a SurfaceView's Surface for direct GPU rendering. */
    fun setSurface(surface: android.view.Surface?) {
        if (surface == null) {
            // Signal emulation thread to stop BEFORE destroying native resources.
            // This prevents the emu thread from calling GL on a dead EGL context.
            surfaceReady = false
        }
        frontend.setSurface(surface)
        surfaceReady = surface != null
    }

    fun surfaceChanged(width: Int, height: Int) {
        frontend.surfaceChanged(width, height)
    }

    fun start(onFrame: (Bitmap?) -> Unit) {
        if (isRunning) return
        isRunning = true
        isPaused = false
        rewindBuffer.clear()

        val targetFps = frontend.getFps().let { if (it > 0) it else 60.0 }
        val sampleRate = frontend.getSampleRate().let { if (it > 0) it else 44100.0 }
        val frameTimeNs = (1_000_000_000.0 / targetFps).toLong()
        val isHwRendered = frontend.isHardwareRendered()

        audioPlayer.start(sampleRate.toInt())

        emulationThread = Thread({
            Log.i(TAG, "Emulation loop started: ${targetFps}fps, frameTime=${frameTimeNs}ns, hwRendered=$isHwRendered, surfaceDirect=$surfaceReady")
            var frameCount = 0L
            var fpsTimer = System.nanoTime()
            var autoSkipLevel = 0
            var slowFrames = 0

            // Persistent bitmap — only needed when no window surface (fallback)
            var cachedBitmap: Bitmap? = null
            var cachedWidth = 0
            var cachedHeight = 0

            // Disable auto-frameskip for HW-rendered cores
            val effectiveAutoFrameSkip = autoFrameSkip && !isHwRendered

            if (manualFrameSkip > 0) {
                frontend.setFrameSkip(manualFrameSkip)
            }

            while (isRunning) {
                if (isPaused || (!surfaceReady && isHwRendered)) {
                    Thread.sleep(16)
                    continue
                }

                val frameStart = System.nanoTime()

                // Handle rewind
                if (rewindActive && rewindBuffer.isNotEmpty()) {
                    val state = rewindBuffer.removeLast()
                    frontend.loadStateFromMemory(state)
                } else {
                    if (rewindEnabled) {
                        val state = frontend.saveStateToMemory()
                        if (state != null) {
                            if (rewindBuffer.size >= rewindBufferSize) {
                                rewindBuffer.removeFirst()
                            }
                            rewindBuffer.addLast(state)
                        }
                    }

                    try {
                        onPreFrame?.invoke()
                        frontend.runFrame()
                        onPostFrame?.invoke()
                    } catch (e: Exception) {
                        Log.e(TAG, "runFrame exception: ${e.message}", e)
                        continue
                    }
                }

                // Get video frame — when surface is attached, native presents
                // directly via eglSwapBuffers.  Bitmap path is fallback only.
                if (!surfaceReady) {
                    val pixels = frontend.getFrameBuffer()
                    val w = frontend.getFrameWidth()
                    val h = frontend.getFrameHeight()

                    if (pixels != null && w > 0 && h > 0) {
                        val bitmap: Bitmap
                        if (cachedBitmap != null && cachedWidth == w && cachedHeight == h) {
                            bitmap = cachedBitmap!!
                        } else {
                            cachedBitmap?.recycle()
                            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            cachedBitmap = bitmap
                            cachedWidth = w
                            cachedHeight = h
                        }
                        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                        onFrame(bitmap)

                        screenshotCallback?.let { cb ->
                            screenshotCallback = null
                            cb(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                        }
                    }
                } else {
                    // Surface rendering — just notify for FPS counting
                    onFrame(null)
                }

                // Push audio (skip during rewind or if audio disabled)
                if (audioEnabled && !rewindActive) {
                    val audio = frontend.getAudioBuffer()
                    if (audio != null && audio.isNotEmpty()) {
                        audioPlayer.writeSamples(audio)
                    }
                }

                // FPS counting + auto frame skip
                frameCount++
                val now = System.nanoTime()
                if (now - fpsTimer >= 1_000_000_000L) {
                    currentFps = frameCount.toFloat()
                    frameCount = 0
                    fpsTimer = now

                    // Auto frame skip: if FPS drops below 80% of target, increase skip
                    // (disabled for HW-rendered cores — it breaks GL pipeline)
                    if (effectiveAutoFrameSkip && manualFrameSkip == 0) {
                        val threshold = (targetFps * 0.8).toFloat()
                        if (currentFps < threshold) {
                            slowFrames++
                            if (slowFrames >= 2 && autoSkipLevel < 2) {
                                autoSkipLevel++
                                frontend.setFrameSkip(autoSkipLevel)
                                Log.i(TAG, "Auto frame skip → $autoSkipLevel (fps=$currentFps)")
                            }
                        } else {
                            slowFrames = 0
                            if (autoSkipLevel > 0 && currentFps >= targetFps.toFloat() * 0.95f) {
                                autoSkipLevel--
                                frontend.setFrameSkip(autoSkipLevel)
                                Log.i(TAG, "Auto frame skip → $autoSkipLevel (fps=$currentFps)")
                            }
                        }
                    }
                }

                // Frame pacing (skip during fast-forward)
                if (!fastForwardEnabled) {
                    val elapsed = System.nanoTime() - frameStart
                    val sleepNs = frameTimeNs - elapsed
                    if (sleepNs > 0) {
                        Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                    }
                }
            }

            // Clean up persistent bitmap
            cachedBitmap?.recycle()
            audioPlayer.stop()
            Log.i(TAG, "Emulation loop stopped")
        }, "VortexEmu").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    fun reset() { frontend.resetGame() }

    fun stop() {
        isRunning = false
        val t = emulationThread
        emulationThread = null
        t?.join(5000) // longer timeout for heavy cores like N64
        if (t?.isAlive == true) {
            Log.w(TAG, "Emulation thread still alive after timeout – interrupting")
            t.interrupt()
            t.join(1000)
        }
        try {
            frontend.unloadGame()
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading game", e)
        }
        rewindBuffer.clear()
    }

    // ── Save / Load States ──────────────────────────────────────────

    fun saveState(slotName: String): Boolean {
        val path = File(effectiveSaveDir, "$slotName.state").absolutePath
        return frontend.saveState(path) == 0
    }

    fun loadState(slotName: String): Boolean {
        val path = File(effectiveSaveDir, "$slotName.state").absolutePath
        return if (File(path).exists()) frontend.loadState(path) == 0 else false
    }

    fun saveStateToPath(absolutePath: String): Boolean {
        return frontend.saveState(absolutePath) == 0
    }

    fun loadStateFromPath(absolutePath: String): Boolean {
        return if (File(absolutePath).exists()) frontend.loadState(absolutePath) == 0 else false
    }

    fun exportStateTo(slotName: String, destUri: Uri): Boolean {
        val src = File(effectiveSaveDir, "$slotName.state")
        if (!src.exists()) return false
        return try {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export state failed", e)
            false
        }
    }

    fun importStateFrom(slotName: String, srcUri: Uri): Boolean {
        return try {
            val dest = File(effectiveSaveDir, "$slotName.state")
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import state failed", e)
            false
        }
    }

    // ── Input ───────────────────────────────────────────────────────

    /** Tracks local player's input state for multiplayer relay. */
    val localInputState = IntArray(16)

    /** Which port belongs to the local player (set by multiplayer relay). */
    @Volatile var localPlayerPort: Int = 0

    fun setButton(port: Int, buttonId: Int, pressed: Boolean) {
        val value = if (pressed) 1 else 0
        if (port == localPlayerPort && buttonId in localInputState.indices) {
            localInputState[buttonId] = value
        }
        frontend.setInputState(port, buttonId, value)
    }

    fun setAnalog(port: Int, index: Int, axisId: Int, value: Int) {
        frontend.setAnalogState(port, index, axisId, value)
    }

    fun setPointer(x: Int, y: Int, pressed: Boolean) {
        frontend.setPointerState(x, y, pressed)
    }

    /** Whether the current core is using hardware-accelerated rendering. */
    fun isHardwareRendered(): Boolean = frontend.isHardwareRendered()

    // ── SRAM (battery save) ─────────────────────────────────────────

    fun saveSRAM(gameName: String): Boolean {
        val path = File(effectiveSaveDir, "$gameName.srm").absolutePath
        return frontend.saveSRAM(path)
    }

    fun loadSRAM(gameName: String): Boolean {
        val path = File(effectiveSaveDir, "$gameName.srm").absolutePath
        return if (File(path).exists()) frontend.loadSRAM(path) else false
    }

    // ── Rewind ──────────────────────────────────────────────────────

    fun startRewind() { rewindActive = true }
    fun stopRewind() { rewindActive = false }

    // ── Screenshot ──────────────────────────────────────────────────

    fun requestScreenshot(callback: (Bitmap) -> Unit) {
        screenshotCallback = callback
    }

    fun takeScreenshot(): Bitmap? {
        val pixels = frontend.getFrameBuffer() ?: return null
        val w = frontend.getFrameWidth()
        val h = frontend.getFrameHeight()
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    // ── Audio ───────────────────────────────────────────────────────

    fun updateAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
    }

    fun setAudioVolume(volume: Float) {
        audioPlayer.volume = volume
    }

    fun setAudioLatency(latency: AudioLatency) {
        audioPlayer.latency = latency
        if (isRunning) {
            audioPlayer.restart()
        }
    }

    fun getAudioVolume(): Float = audioPlayer.volume
    fun getAudioLatency(): AudioLatency = audioPlayer.latency

    fun setFrameSkip(level: Int) {
        manualFrameSkip = level.coerceIn(0, 4)
        frontend.setFrameSkip(manualFrameSkip)
    }

    // ── ROM path resolution ─────────────────────────────────────────

    internal fun resolveRomPath(romUri: String): String? {
        return try {
            if (romUri.startsWith("content://")) {
                val uri = Uri.parse(romUri)
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "rom.bin"
                val tmpDir = File(context.cacheDir, "roms").also { it.mkdirs() }
                val tmpFile = File(tmpDir, fileName)

                if (!tmpFile.exists() || tmpFile.length() == 0L) {
                    tmpFile.delete()
                    val stream = openContentStream(uri)
                    if (stream == null) {
                        Log.e(TAG, "Cannot open ROM — grant file access or re-scan: $romUri")
                        return null
                    }
                    stream.use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmpFile.length() == 0L) {
                        Log.e(TAG, "Copied ROM file is empty: $romUri")
                        tmpFile.delete()
                        return null
                    }
                }
                tmpFile.absolutePath
            } else {
                val f = File(romUri)
                if (f.exists()) f.absolutePath else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve ROM: $romUri", e)
            null
        }
    }

    /**
     * Try multiple strategies to open the content URI:
     * 1. Direct SAF openInputStream (works when URI permission is active)
     * 2. Rebuild URI via persisted tree permissions
     * 3. Extract real file path (works with MANAGE_EXTERNAL_STORAGE)
     */
    private fun openContentStream(uri: Uri): InputStream? {
        // Strategy 1: Direct SAF access
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) return stream
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct SAF access denied, trying fallbacks...")
        }

        // Strategy 2: Rebuild document URI via persisted tree permissions
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            for (perm in context.contentResolver.persistedUriPermissions) {
                if (!perm.isReadPermission) continue
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(perm.uri)
                    if (docId.startsWith(treeDocId)) {
                        val rebuilt = DocumentsContract.buildDocumentUriUsingTree(perm.uri, docId)
                        val stream = context.contentResolver.openInputStream(rebuilt)
                        if (stream != null) return stream
                    }
                } catch (_: Exception) { /* try next */ }
            }
        } catch (_: Exception) { /* fall through */ }

        // Strategy 3: Extract real file path and read directly
        // Works when MANAGE_EXTERNAL_STORAGE is granted
        val filePath = extractFilePathFromUri(uri)
        if (filePath != null) {
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                Log.i(TAG, "Opened ROM via file path fallback: $filePath")
                return file.inputStream()
            }
        }

        return null
    }

    /**
     * Extract the real file system path from a SAF document URI.
     * E.g. "content://.../tree/primary:ROMS/document/primary:ROMS/n64/game.n64"
     *   -> "/storage/emulated/0/ROMS/n64/game.n64"
     */
    private fun extractFilePathFromUri(uri: Uri): String? {
        return try {
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) {
                uri.pathSegments.getOrNull(3)
            } ?: return null

            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null

            val volumeId = parts[0]
            val relativePath = parts[1]

            if (volumeId == "primary") {
                File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
            } else {
                File("/storage/$volumeId", relativePath).absolutePath
            }
        } catch (_: Exception) { null }
    }
}
