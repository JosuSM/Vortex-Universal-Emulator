package com.vortex.emulator.gpu

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES30
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,        // codename (e.g. "e3q")
    val brand: String,         // marketed brand (e.g. "samsung")
    val androidVersion: String,
    val sdkVersion: Int,
    val buildId: String,
    val securityPatch: String,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val displayDensityDpi: Int,
    val gpuDriverVersion: String,
    val deviceCategory: DeviceCategory
)

enum class DeviceCategory(val displayName: String) {
    SAMSUNG_FLAGSHIP("Samsung Flagship"),
    SAMSUNG_MID("Samsung Mid-Range"),
    PIXEL("Google Pixel"),
    XIAOMI("Xiaomi / Redmi"),
    ONEPLUS("OnePlus"),
    OPPO_VIVO("OPPO / Vivo"),
    HUAWEI("Huawei / Honor"),
    MEDIATEK_DEVICE("MediaTek Device"),
    GENERIC_HIGH("High-End Device"),
    GENERIC_MID("Mid-Range Device"),
    GENERIC_LOW("Budget Device")
}

data class GpuInfo(
    val renderer: String,
    val vendor: String,
    val glVersion: String,
    val glExtensions: List<String>,
    val maxTextureSize: Int,
    val supportsVulkan: Boolean,
    val vulkanVersionMajor: Int,
    val vulkanVersionMinor: Int,
    val supportsOpenGLES32: Boolean,
    val estimatedVramMb: Int
)

data class ChipsetInfo(
    val socName: String,
    val cpuArchitecture: String,
    val cpuCores: Int,
    val cpuMaxFreqMhz: Long,
    val totalRamMb: Long,
    val is64Bit: Boolean,
    val abis: List<String>,
    val chipsetTier: ChipsetTier,
    val gpuInfo: GpuInfo?,
    val deviceInfo: DeviceInfo
)

enum class ChipsetTier(val displayName: String) {
    FLAGSHIP("Flagship"),
    HIGH_END("High End"),
    MID_RANGE("Mid Range"),
    ENTRY_LEVEL("Entry Level"),
    LOW_END("Low End")
}

/**
 * Detects the device's chipset, GPU capabilities, and determines optimal
 * emulation settings for the hardware.
 */
@Singleton
class ChipsetDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var _chipsetInfo: ChipsetInfo? = null
    val chipsetInfo: ChipsetInfo
        get() = _chipsetInfo ?: detect().also { _chipsetInfo = it }

    fun detect(): ChipsetInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        val abis = Build.SUPPORTED_ABIS.toList()

        val socName = detectSocName()
        val cpuMaxFreq = getCpuMaxFrequency()
        val gpuInfo = detectGpu()

        val tier = classifyChipset(cpuCores, cpuMaxFreq, totalRamMb, socName, gpuInfo)
        val deviceInfo = detectDevice(gpuInfo, tier)

        return ChipsetInfo(
            socName = socName,
            cpuArchitecture = if (is64Bit) "ARM64" else "ARM32",
            cpuCores = cpuCores,
            cpuMaxFreqMhz = cpuMaxFreq,
            totalRamMb = totalRamMb,
            is64Bit = is64Bit,
            abis = abis,
            chipsetTier = tier,
            gpuInfo = gpuInfo,
            deviceInfo = deviceInfo
        )
    }

    private fun detectDevice(gpuInfo: GpuInfo?, tier: ChipsetTier): DeviceInfo {
        val resources = context.resources
        val dm = resources.displayMetrics
        val secPatch = try { Build.VERSION.SECURITY_PATCH } catch (_: Exception) { "unknown" }

        return DeviceInfo(
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown",
            device = Build.DEVICE ?: "unknown",
            brand = Build.BRAND ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkVersion = Build.VERSION.SDK_INT,
            buildId = Build.ID ?: "unknown",
            securityPatch = secPatch,
            screenWidthPx = dm.widthPixels,
            screenHeightPx = dm.heightPixels,
            displayDensityDpi = dm.densityDpi,
            gpuDriverVersion = gpuInfo?.glVersion ?: "unknown",
            deviceCategory = classifyDevice(tier)
        )
    }

    private fun classifyDevice(tier: ChipsetTier): DeviceCategory {
        val mfr = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""

        return when {
            mfr.contains("samsung") -> when (tier) {
                ChipsetTier.FLAGSHIP, ChipsetTier.HIGH_END -> DeviceCategory.SAMSUNG_FLAGSHIP
                else -> DeviceCategory.SAMSUNG_MID
            }
            mfr.contains("google") -> DeviceCategory.PIXEL
            mfr.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                DeviceCategory.XIAOMI
            mfr.contains("oneplus") -> DeviceCategory.ONEPLUS
            mfr.contains("oppo") || mfr.contains("vivo") || mfr.contains("realme") ->
                DeviceCategory.OPPO_VIVO
            mfr.contains("huawei") || mfr.contains("honor") -> DeviceCategory.HUAWEI
            Build.HARDWARE?.lowercase()?.contains("mt") == true -> DeviceCategory.MEDIATEK_DEVICE
            else -> when (tier) {
                ChipsetTier.FLAGSHIP, ChipsetTier.HIGH_END -> DeviceCategory.GENERIC_HIGH
                ChipsetTier.MID_RANGE -> DeviceCategory.GENERIC_MID
                else -> DeviceCategory.GENERIC_LOW
            }
        }
    }

    private fun detectSocName(): String {
        return try {
            val board = Build.BOARD
            val hardware = Build.HARDWARE
            val soc = Build.SOC_MODEL
            when {
                soc.isNotBlank() && soc != "unknown" -> soc
                hardware.contains("qcom", ignoreCase = true) -> "Qualcomm $hardware"
                hardware.contains("mt", ignoreCase = true) -> "MediaTek $hardware"
                hardware.contains("exynos", ignoreCase = true) -> "Samsung $hardware"
                hardware.contains("kirin", ignoreCase = true) -> "HiSilicon $hardware"
                hardware.contains("tensor", ignoreCase = true) -> "Google $hardware"
                hardware.contains("dimensity", ignoreCase = true) -> "MediaTek $hardware"
                else -> "$hardware ($board)"
            }
        } catch (_: Exception) {
            Build.HARDWARE
        }
    }

    private fun getCpuMaxFrequency(): Long {
        return try {
            val path = "/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"
            java.io.File(path).readText().trim().toLong() / 1000 // KHz -> MHz
        } catch (_: Exception) {
            0L
        }
    }

    private fun detectGpu(): GpuInfo? {
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(display, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            val eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

            val surfAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0)

            EGL14.eglMakeCurrent(display, surface, surface, eglContext)

            val renderer = GLES30.glGetString(GLES30.GL_RENDERER) ?: "Unknown"
            val vendor = GLES30.glGetString(GLES30.GL_VENDOR) ?: "Unknown"
            val glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "Unknown"
            val extensions = (GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: "").split(" ")

            val maxTexSize = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTexSize, 0)

            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)

            val supportsVulkan = checkVulkanSupport()

            GpuInfo(
                renderer = renderer,
                vendor = vendor,
                glVersion = glVersion,
                glExtensions = extensions,
                maxTextureSize = maxTexSize[0],
                supportsVulkan = supportsVulkan,
                vulkanVersionMajor = if (supportsVulkan) 1 else 0,
                vulkanVersionMinor = if (supportsVulkan) 1 else 0,
                supportsOpenGLES32 = glVersion.contains("3.2"),
                estimatedVramMb = estimateVram(renderer)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun checkVulkanSupport(): Boolean {
        return try {
            val pm = context.packageManager
            pm.systemAvailableFeatures.any {
                it.name == "android.hardware.vulkan.level" ||
                it.name?.contains("vulkan") == true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun estimateVram(renderer: String): Int {
        val r = renderer.lowercase()
        return when {
            r.contains("adreno 7") -> 2048
            r.contains("adreno 6") -> 1024
            r.contains("mali-g7") -> 2048
            r.contains("mali-g6") -> 1024
            r.contains("mali-g5") -> 512
            r.contains("xclipse") -> 2048
            r.contains("immortalis") -> 2048
            r.contains("powervr") -> 512
            else -> 256
        }
    }

    private fun classifyChipset(
        cores: Int,
        maxFreq: Long,
        ramMb: Long,
        soc: String,
        gpu: GpuInfo?
    ): ChipsetTier {
        val socLower = soc.lowercase()
        val gpuRenderer = gpu?.renderer?.lowercase() ?: ""

        // Flagship detection
        if (socLower.containsAny("8 gen 3", "8 gen 2", "9 gen", "dimensity 9", "tensor g4", "exynos 2") ||
            gpuRenderer.containsAny("adreno 750", "adreno 740", "adreno 730", "immortalis", "xclipse")) {
            return ChipsetTier.FLAGSHIP
        }

        // High-end
        if (socLower.containsAny("8 gen 1", "870", "865", "dimensity 8", "tensor g3", "exynos 1") ||
            gpuRenderer.containsAny("adreno 660", "adreno 650", "mali-g710", "mali-g78")) {
            return ChipsetTier.HIGH_END
        }

        // Mid-range
        if (cores >= 8 && maxFreq >= 2200 && ramMb >= 4096) {
            return ChipsetTier.MID_RANGE
        }

        // Entry level
        if (cores >= 4 && ramMb >= 3072) {
            return ChipsetTier.ENTRY_LEVEL
        }

        return ChipsetTier.LOW_END
    }

    /**
     * Recommend optimal settings based on detected hardware.
     */
    fun getRecommendedSettings(): EmulationRecommendation {
        val info = chipsetInfo
        return when (info.chipsetTier) {
            ChipsetTier.FLAGSHIP -> EmulationRecommendation(
                maxResolutionMultiplier = 5,
                enableVulkan = info.gpuInfo?.supportsVulkan == true,
                enableWidescreen = true,
                enableRewind = true,
                maxFastForwardSpeed = 8f,
                supportedHeavyCores = true,
                note = "Your device is a powerhouse! All features enabled."
            )
            ChipsetTier.HIGH_END -> EmulationRecommendation(
                maxResolutionMultiplier = 3,
                enableVulkan = info.gpuInfo?.supportsVulkan == true,
                enableWidescreen = true,
                enableRewind = true,
                maxFastForwardSpeed = 4f,
                supportedHeavyCores = true,
                note = "Great performance expected for all platforms."
            )
            ChipsetTier.MID_RANGE -> EmulationRecommendation(
                maxResolutionMultiplier = 2,
                enableVulkan = info.gpuInfo?.supportsVulkan == true,
                enableWidescreen = true,
                enableRewind = false,
                maxFastForwardSpeed = 3f,
                supportedHeavyCores = false,
                note = "Good performance up to PSP/Dreamcast. GameCube/Wii may lag."
            )
            ChipsetTier.ENTRY_LEVEL -> EmulationRecommendation(
                maxResolutionMultiplier = 1,
                enableVulkan = false,
                enableWidescreen = false,
                enableRewind = false,
                maxFastForwardSpeed = 2f,
                supportedHeavyCores = false,
                note = "Best with retro platforms (NES, SNES, GBA, Genesis)."
            )
            ChipsetTier.LOW_END -> EmulationRecommendation(
                maxResolutionMultiplier = 1,
                enableVulkan = false,
                enableWidescreen = false,
                enableRewind = false,
                maxFastForwardSpeed = 1f,
                supportedHeavyCores = false,
                note = "Limited to 2D retro platforms."
            )
        }
    }

    /**
     * Get per-core performance profiles tuned for the detected hardware.
     */
    fun getCoreProfiles(): List<CorePerformanceProfile> {
        val info = chipsetInfo
        val isMali = info.gpuInfo?.renderer?.contains("mali", ignoreCase = true) == true ||
                     info.gpuInfo?.renderer?.contains("immortalis", ignoreCase = true) == true
        val isAdreno = info.gpuInfo?.renderer?.contains("adreno", ignoreCase = true) == true

        return when (info.chipsetTier) {
            ChipsetTier.FLAGSHIP -> listOf(
                CorePerformanceProfile("n64", "N64 (Mupen64Plus)", 2, useHardwareRenderer = true,
                    frameSkip = 0, audioLatency = 64,
                    customOptions = buildMap {
                        put("mupen64plus-EnableNativeResTexrects", "True")
                        put("mupen64plus-ThreadedRenderer", "True")
                        if (isMali) put("mupen64plus-EnableCopyColorToRDRAM", "Async")
                    },
                    notes = "Full speed at 2x. Mali: async color copy recommended."),
                CorePerformanceProfile("psp", "PSP (PPSSPP)", 3, useHardwareRenderer = true,
                    frameSkip = 0, audioLatency = 64,
                    customOptions = mapOf(
                        "ppsspp_rendering_mode" to "buffered",
                        "ppsspp_texture_scaling_level" to "2"
                    ),
                    notes = "3x resolution, hardware rendering. Smooth on all titles."),
                CorePerformanceProfile("nds", "NDS (melonDS)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 64,
                    customOptions = mapOf("melonds_threaded_renderer" to "enabled"),
                    notes = "Full speed with threaded renderer."),
                CorePerformanceProfile("snes", "SNES (Snes9x)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 32,
                    customOptions = emptyMap(),
                    notes = "Perfect performance. Enable rewind freely."),
                CorePerformanceProfile("nes", "NES (FCEUmm)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 32,
                    customOptions = emptyMap(),
                    notes = "Perfect performance. All features enabled."),
                CorePerformanceProfile("gba", "GBA (mGBA)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 32,
                    customOptions = emptyMap(),
                    notes = "Full speed. Enable solar sensor for Boktai games.")
            )
            ChipsetTier.HIGH_END -> listOf(
                CorePerformanceProfile("n64", "N64 (Mupen64Plus)", 1, useHardwareRenderer = true,
                    frameSkip = 0, audioLatency = 96,
                    customOptions = buildMap {
                        put("mupen64plus-ThreadedRenderer", "True")
                        if (isMali) put("mupen64plus-EnableCopyColorToRDRAM", "Async")
                    },
                    notes = "Native resolution for compatibility. Threaded renderer on."),
                CorePerformanceProfile("psp", "PSP (PPSSPP)", 2, useHardwareRenderer = true,
                    frameSkip = 0, audioLatency = 96,
                    customOptions = mapOf("ppsspp_rendering_mode" to "buffered"),
                    notes = "2x resolution. Most titles at full speed."),
                CorePerformanceProfile("nds", "NDS (melonDS)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 64,
                    customOptions = mapOf("melonds_threaded_renderer" to "enabled"),
                    notes = "Full speed."),
                CorePerformanceProfile("snes", "SNES (Snes9x)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 32,
                    customOptions = emptyMap(),
                    notes = "Perfect performance."),
                CorePerformanceProfile("gba", "GBA (mGBA)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 32,
                    customOptions = emptyMap(),
                    notes = "Perfect performance.")
            )
            ChipsetTier.MID_RANGE -> listOf(
                CorePerformanceProfile("n64", "N64 (Mupen64Plus)", 1, useHardwareRenderer = true,
                    frameSkip = 1, audioLatency = 128,
                    customOptions = buildMap {
                        put("mupen64plus-ThreadedRenderer", "True")
                        put("mupen64plus-EnableLOD", "False")
                        if (isMali) put("mupen64plus-EnableCopyColorToRDRAM", "Off")
                    },
                    notes = "Native res + frameskip 1. Disable LOD for speed."),
                CorePerformanceProfile("psp", "PSP (PPSSPP)", 1, useHardwareRenderer = true,
                    frameSkip = 1, audioLatency = 128,
                    customOptions = mapOf(
                        "ppsspp_rendering_mode" to "buffered",
                        "ppsspp_auto_frameskip" to "enabled"
                    ),
                    notes = "Native resolution with auto-frameskip."),
                CorePerformanceProfile("nds", "NDS (melonDS)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 96,
                    customOptions = mapOf("melonds_threaded_renderer" to "enabled"),
                    notes = "Should run well with threaded renderer."),
                CorePerformanceProfile("snes", "SNES (Snes9x)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 64,
                    customOptions = emptyMap(),
                    notes = "Full speed.")
            )
            ChipsetTier.ENTRY_LEVEL, ChipsetTier.LOW_END -> listOf(
                CorePerformanceProfile("n64", "N64 (Mupen64Plus)", 1, useHardwareRenderer = true,
                    frameSkip = 2, audioLatency = 192,
                    customOptions = mapOf(
                        "mupen64plus-ThreadedRenderer" to "True",
                        "mupen64plus-EnableLOD" to "False",
                        "mupen64plus-EnableCopyColorToRDRAM" to "Off"
                    ),
                    notes = "May struggle. Use GLES2 core for better compat."),
                CorePerformanceProfile("psp", "PSP (PPSSPP)", 1, useHardwareRenderer = true,
                    frameSkip = 2, audioLatency = 192,
                    customOptions = mapOf(
                        "ppsspp_auto_frameskip" to "enabled",
                        "ppsspp_rendering_mode" to "buffered"
                    ),
                    notes = "Native res + frameskip. 2D games recommended."),
                CorePerformanceProfile("snes", "SNES (Snes9x)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 96,
                    customOptions = emptyMap(),
                    notes = "Should run well."),
                CorePerformanceProfile("nes", "NES (FCEUmm)", 1, useHardwareRenderer = false,
                    frameSkip = 0, audioLatency = 64,
                    customOptions = emptyMap(),
                    notes = "Full speed expected.")
            )
        }
    }
}

data class CorePerformanceProfile(
    val coreId: String,
    val coreName: String,
    val resolutionMultiplier: Int,
    val useHardwareRenderer: Boolean,
    val frameSkip: Int,
    val audioLatency: Int,
    val customOptions: Map<String, String>,
    val notes: String
)

data class EmulationRecommendation(
    val maxResolutionMultiplier: Int,
    val enableVulkan: Boolean,
    val enableWidescreen: Boolean,
    val enableRewind: Boolean,
    val maxFastForwardSpeed: Float,
    val supportedHeavyCores: Boolean,
    val note: String
)

private fun String.containsAny(vararg terms: String): Boolean =
    terms.any { this.contains(it, ignoreCase = true) }
