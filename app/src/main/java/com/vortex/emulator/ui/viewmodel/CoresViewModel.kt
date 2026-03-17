package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.core.CoreInfo
import com.vortex.emulator.core.CoreManager
import com.vortex.emulator.emulation.CoreDownloader
import com.vortex.emulator.emulation.StandaloneDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoresViewModel @Inject constructor(
    private val coreManager: CoreManager,
    private val coreDownloader: CoreDownloader,
    private val standaloneDownloader: StandaloneDownloader
) : ViewModel() {

    init {
        // Refresh installed status each time the Cores screen is opened,
        // so standalone emulators installed externally are detected.
        coreManager.refreshInstalled()
    }

    val installedCores: StateFlow<List<CoreInfo>> = coreManager.installedCores
    val availableCores: StateFlow<List<CoreInfo>> = coreManager.availableCores

    // ── Batch download state ────────────────────────────────
    private val _isBatchDownloading = MutableStateFlow(false)
    val isBatchDownloading: StateFlow<Boolean> = _isBatchDownloading.asStateFlow()

    private val _batchProgress = MutableStateFlow(0f) // 0..1
    val batchProgress: StateFlow<Float> = _batchProgress.asStateFlow()

    private val _batchCurrentCore = MutableStateFlow("")
    val batchCurrentCore: StateFlow<String> = _batchCurrentCore.asStateFlow()

    private val _batchResult = MutableStateFlow<String?>(null)
    val batchResult: StateFlow<String?> = _batchResult.asStateFlow()

    // ── Single core download state ──────────────────────────
    private val _downloadingCoreId = MutableStateFlow<String?>(null)
    val downloadingCoreId: StateFlow<String?> = _downloadingCoreId.asStateFlow()

    // ── Standalone download state ───────────────────────────
    private val _standaloneProgress = MutableStateFlow(0f) // 0..1
    val standaloneProgress: StateFlow<Float> = _standaloneProgress.asStateFlow()

    private val _standaloneError = MutableStateFlow<String?>(null)
    val standaloneError: StateFlow<String?> = _standaloneError.asStateFlow()

    private val _standaloneFailedLibrary = MutableStateFlow<String?>(null)
    val standaloneFailedLibrary: StateFlow<String?> = _standaloneFailedLibrary.asStateFlow()

    fun isOffline(): Boolean = !coreDownloader.isNetworkAvailable()

    fun getCacheSizeMb(): Float = coreDownloader.getCacheSize() / (1024f * 1024f)

    fun downloadCore(core: CoreInfo) {
        if (core.isStandalone) {
            downloadStandaloneCore(core)
            return
        }
        viewModelScope.launch {
            _downloadingCoreId.value = core.id
            coreDownloader.ensureCore(core.libraryName)
            coreManager.refreshInstalled()
            _downloadingCoreId.value = null
        }
    }

    private fun downloadStandaloneCore(core: CoreInfo) {
        viewModelScope.launch {
            _downloadingCoreId.value = core.id
            _standaloneProgress.value = 0f
            _standaloneError.value = null
            _standaloneFailedLibrary.value = null

            val result = standaloneDownloader.downloadAndInstall(core.libraryName) { downloaded, total ->
                _standaloneProgress.value = if (total > 0) downloaded.toFloat() / total else 0f
            }

            when (result) {
                is StandaloneDownloader.DownloadResult.Success -> {
                    // Install dialog shown; refresh when user returns
                    _standaloneProgress.value = 1f
                }
                is StandaloneDownloader.DownloadResult.NoSource -> {
                    _standaloneError.value = "No download source configured for ${core.displayName}"
                    _standaloneFailedLibrary.value = core.libraryName
                }
                is StandaloneDownloader.DownloadResult.NoRelease -> {
                    _standaloneError.value = "No APK found in releases"
                    _standaloneFailedLibrary.value = core.libraryName
                }
                is StandaloneDownloader.DownloadResult.DownloadFailed -> {
                    _standaloneError.value = "Download failed"
                    _standaloneFailedLibrary.value = core.libraryName
                }
            }

            _downloadingCoreId.value = null
            coreManager.refreshInstalled()
        }
    }

    fun hasStandaloneDownload(libraryName: String): Boolean =
        standaloneDownloader.hasDownloadSource(libraryName)

    fun openStandaloneFallback(libraryName: String) {
        standaloneDownloader.openFallbackInBrowser(libraryName)
    }

    fun clearStandaloneError() {
        _standaloneError.value = null
        _standaloneFailedLibrary.value = null
    }

    fun deleteCore(core: CoreInfo) {
        if (core.isStandalone) return // Can't uninstall standalone apps from here
        coreDownloader.deleteCore(core.libraryName)
        coreManager.refreshInstalled()
    }

    fun downloadAllCores() {
        if (_isBatchDownloading.value) return
        viewModelScope.launch {
            _isBatchDownloading.value = true
            _batchResult.value = null
            val notInstalled = availableCores.value
                .filter { !it.isStandalone && !coreDownloader.isCoreDownloaded(it.libraryName) }
                .map { it.libraryName }

            if (notInstalled.isEmpty()) {
                _batchResult.value = "All cores are already downloaded!"
                _isBatchDownloading.value = false
                return@launch
            }

            val successCount = coreDownloader.downloadAllCores(notInstalled) { completed, total, current ->
                _batchProgress.value = if (total > 0) completed.toFloat() / total else 0f
                _batchCurrentCore.value = current
            }

            coreManager.refreshInstalled()
            _batchResult.value = "Downloaded $successCount of ${notInstalled.size} cores"
            _isBatchDownloading.value = false
        }
    }

    fun clearBatchResult() {
        _batchResult.value = null
    }

    fun clearCache() {
        coreDownloader.clearCache()
        coreManager.refreshInstalled()
    }
}
