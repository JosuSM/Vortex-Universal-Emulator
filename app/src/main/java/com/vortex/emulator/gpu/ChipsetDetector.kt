package com.vortex.emulator.gpu

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES30
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
    val gpuInfo: GpuInfo?
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

        return ChipsetInfo(
            socName = socName,
            cpuArchitecture = if (is64Bit) "ARM64" else "ARM32",
            cpuCores = cpuCores,
            cpuMaxFreqMhz = cpuMaxFreq,
            totalRamMb = totalRamMb,
            is64Bit = is64Bit,
            abis = abis,
            chipsetTier = tier,
            gpuInfo = gpuInfo
        )
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
}

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
