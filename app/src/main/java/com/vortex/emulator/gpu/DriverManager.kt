package com.vortex.emulator.gpu

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DriverInfo(
    val id: String,
    val name: String,
    val version: String,
    val vendor: String,
    val description: String,
    val targetGpu: String,
    val isInstalled: Boolean = false,
    val isBundled: Boolean = false,
    val downloadSizeMb: Float = 0f,
    val isActive: Boolean = false
)

/**
 * Manages GPU drivers for optimal rendering performance.
 * Supports custom Turnip (Adreno), Mesa/Freedreno, and PanVK drivers.
 */
@Singleton
class DriverManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chipsetDetector: ChipsetDetector
) {
    private val driversDir = File(context.filesDir, "gpu_drivers")
    private val _drivers = MutableStateFlow<List<DriverInfo>>(emptyList())
    val drivers: StateFlow<List<DriverInfo>> = _drivers.asStateFlow()

    private val _activeDriver = MutableStateFlow<DriverInfo?>(null)
    val activeDriver: StateFlow<DriverInfo?> = _activeDriver.asStateFlow()

    init {
        driversDir.mkdirs()
        loadDriverCatalog()
    }

    private fun loadDriverCatalog() {
        val gpu = chipsetDetector.chipsetInfo.gpuInfo
        val renderer = gpu?.renderer?.lowercase() ?: ""

        val catalog = mutableListOf(
            DriverInfo(
                id = "system_default",
                name = "System Default",
                version = "Device OEM",
                vendor = "System",
                description = "Use the device's built-in GPU driver",
                targetGpu = "All",
                isInstalled = true,
                isBundled = true,
                isActive = true
            )
        )

        // Adreno-specific drivers
        if (renderer.contains("adreno")) {
            catalog.addAll(listOf(
                DriverInfo(
                    id = "turnip_latest",
                    name = "Turnip (Mesa Vulkan)",
                    version = "24.3",
                    vendor = "Mesa",
                    description = "Open-source Vulkan driver for Adreno GPUs. Often faster than stock.",
                    targetGpu = "Adreno 610+",
                    isBundled = true,
                    downloadSizeMb = 4.2f
                ),
                DriverInfo(
                    id = "freedreno_latest",
                    name = "Freedreno (Mesa OpenGL)",
                    version = "24.3",
                    vendor = "Mesa",
                    description = "Open-source OpenGL driver for Adreno. Better shader compatibility.",
                    targetGpu = "Adreno 610+",
                    downloadSizeMb = 3.8f
                )
            ))
        }

        // Mali-specific drivers
        if (renderer.contains("mali")) {
            catalog.addAll(listOf(
                DriverInfo(
                    id = "panvk_latest",
                    name = "PanVK (Mesa Vulkan)",
                    version = "24.3",
                    vendor = "Mesa",
                    description = "Open-source Vulkan driver for Mali GPUs.",
                    targetGpu = "Mali-G52+",
                    downloadSizeMb = 4.0f
                ),
                DriverInfo(
                    id = "panfrost_latest",
                    name = "Panfrost (Mesa OpenGL)",
                    version = "24.3",
                    vendor = "Mesa",
                    description = "Open-source OpenGL driver for Mali GPUs.",
                    targetGpu = "Mali-G52+",
                    downloadSizeMb = 3.5f
                )
            ))
        }

        // Xclipse (Samsung) drivers
        if (renderer.contains("xclipse")) {
            catalog.add(
                DriverInfo(
                    id = "samsung_game_driver",
                    name = "Samsung Game Driver",
                    version = "Latest",
                    vendor = "Samsung/AMD",
                    description = "Samsung's optimized RDNA2 game driver.",
                    targetGpu = "Xclipse",
                    downloadSizeMb = 5.0f
                )
            )
        }

        _drivers.value = catalog
        _activeDriver.value = catalog.find { it.isActive }
    }

    suspend fun installDriver(driverInfo: DriverInfo): Boolean {
        // Production: download driver .so from CDN
        val updated = _drivers.value.map {
            if (it.id == driverInfo.id) it.copy(isInstalled = true) else it
        }
        _drivers.value = updated
        return true
    }

    suspend fun activateDriver(driverInfo: DriverInfo): Boolean {
        val updated = _drivers.value.map {
            it.copy(isActive = it.id == driverInfo.id)
        }
        _drivers.value = updated
        _activeDriver.value = updated.find { it.isActive }
        return true
    }

    suspend fun uninstallDriver(driverInfo: DriverInfo): Boolean {
        if (driverInfo.isBundled) return false
        val file = File(driversDir, "${driverInfo.id}.so")
        if (file.exists()) file.delete()
        val updated = _drivers.value.map {
            if (it.id == driverInfo.id) it.copy(isInstalled = false, isActive = false) else it
        }
        _drivers.value = updated
        if (_activeDriver.value?.id == driverInfo.id) {
            activateDriver(_drivers.value.first { it.id == "system_default" })
        }
        return true
    }
}
