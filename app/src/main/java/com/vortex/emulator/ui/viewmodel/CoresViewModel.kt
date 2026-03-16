package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.core.CoreInfo
import com.vortex.emulator.core.CoreManager
import com.vortex.emulator.emulation.CoreDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CoresViewModel @Inject constructor(
    private val coreManager: CoreManager,
    private val coreDownloader: CoreDownloader
) : ViewModel() {

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

    fun isOffline(): Boolean = !coreDownloader.isNetworkAvailable()

    fun getCacheSizeMb(): Float = coreDownloader.getCacheSize() / (1024f * 1024f)

    fun downloadCore(core: CoreInfo) {
        viewModelScope.launch {
            _downloadingCoreId.value = core.id
            coreDownloader.ensureCore(core.libraryName)
            coreManager.refreshInstalled()
            _downloadingCoreId.value = null
        }
    }

    fun deleteCore(core: CoreInfo) {
        coreDownloader.deleteCore(core.libraryName)
        coreManager.refreshInstalled()
    }

    fun downloadAllCores() {
        if (_isBatchDownloading.value) return
        viewModelScope.launch {
            _isBatchDownloading.value = true
            _batchResult.value = null
            val notInstalled = availableCores.value
                .filter { !coreDownloader.isCoreDownloaded(it.libraryName) }
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
