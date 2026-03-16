package com.vortex.emulator.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.patcher.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PatcherUiState(
    val romUri: Uri? = null,
    val romInfo: PatchInfo? = null,
    val patchUris: List<Uri> = emptyList(),
    val patchInfos: List<PatchInfo> = emptyList(),
    val outputUri: Uri? = null,
    val createBackup: Boolean = true,
    val isPatching: Boolean = false,
    val isAnalyzing: Boolean = false,
    val patchProgress: Pair<Int, Int>? = null,
    val results: List<PatchResult> = emptyList(),
    val error: String? = null,
    val backupCount: Int = 0,
    val canRestore: Boolean = false
)

@HiltViewModel
class PatcherViewModel @Inject constructor(
    private val romPatcher: RomPatcher
) : ViewModel() {

    private val _state = MutableStateFlow(PatcherUiState())
    val state: StateFlow<PatcherUiState> = _state.asStateFlow()

    init {
        refreshBackupInfo()
    }

    fun setRomUri(uri: Uri) {
        _state.value = _state.value.copy(
            romUri = uri,
            romInfo = null,
            results = emptyList(),
            error = null
        )
        analyzeRom(uri)
    }

    fun addPatchUri(uri: Uri) {
        val current = _state.value
        val newUris = current.patchUris + uri
        _state.value = current.copy(patchUris = newUris, results = emptyList(), error = null)
        detectPatch(uri, newUris.size - 1)
    }

    fun removePatch(index: Int) {
        val current = _state.value
        if (index in current.patchUris.indices) {
            _state.value = current.copy(
                patchUris = current.patchUris.toMutableList().apply { removeAt(index) },
                patchInfos = current.patchInfos.toMutableList().apply {
                    if (index < size) removeAt(index)
                },
                results = emptyList()
            )
        }
    }

    fun movePatch(from: Int, to: Int) {
        val current = _state.value
        if (from in current.patchUris.indices && to in current.patchUris.indices) {
            val uris = current.patchUris.toMutableList().apply {
                add(to, removeAt(from))
            }
            val infos = current.patchInfos.toMutableList().apply {
                if (from < size && to < size) add(to, removeAt(from))
            }
            _state.value = current.copy(patchUris = uris, patchInfos = infos, results = emptyList())
        }
    }

    fun setOutputUri(uri: Uri?) {
        _state.value = _state.value.copy(outputUri = uri)
    }

    fun setCreateBackup(enabled: Boolean) {
        _state.value = _state.value.copy(createBackup = enabled)
    }

    fun applyPatches() {
        val current = _state.value
        val romUri = current.romUri ?: return
        if (current.patchUris.isEmpty()) return

        viewModelScope.launch {
            _state.value = current.copy(
                isPatching = true,
                results = emptyList(),
                error = null,
                patchProgress = Pair(0, current.patchUris.size)
            )

            val results = if (current.patchUris.size == 1) {
                val result = romPatcher.applyPatch(
                    PatchJob(
                        romUri = romUri,
                        patchUri = current.patchUris.first(),
                        outputUri = current.outputUri,
                        createBackup = current.createBackup
                    )
                )
                listOf(result)
            } else {
                romPatcher.applyPatches(
                    romUri = romUri,
                    patchUris = current.patchUris,
                    outputUri = current.outputUri,
                    createBackup = current.createBackup,
                    onProgress = { done, total ->
                        _state.value = _state.value.copy(patchProgress = Pair(done, total))
                    }
                )
            }

            _state.value = _state.value.copy(
                isPatching = false,
                results = results,
                patchProgress = null
            )
            refreshBackupInfo()
        }
    }

    fun restoreBackup() {
        val romUri = _state.value.romUri ?: return
        viewModelScope.launch {
            val success = romPatcher.restoreBackup(romUri)
            if (success) {
                _state.value = _state.value.copy(
                    results = listOf(PatchResult(true, message = "ROM restored from backup")),
                    error = null
                )
                analyzeRom(romUri)
            } else {
                _state.value = _state.value.copy(error = "No backup found for this ROM")
            }
            refreshBackupInfo()
        }
    }

    fun clearBackups() {
        val freed = romPatcher.clearBackups()
        _state.value = _state.value.copy(backupCount = 0, canRestore = false)
    }

    fun clearAll() {
        _state.value = PatcherUiState(backupCount = _state.value.backupCount)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun analyzeRom(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isAnalyzing = true)
            try {
                val info = romPatcher.analyzeRom(uri)
                _state.value = _state.value.copy(
                    romInfo = info,
                    isAnalyzing = false,
                    canRestore = romPatcher.hasBackup(uri)
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isAnalyzing = false,
                    error = "Cannot analyze ROM: ${e.message}"
                )
            }
        }
    }

    private fun detectPatch(uri: Uri, index: Int) {
        viewModelScope.launch {
            try {
                val info = romPatcher.detectPatchType(uri)
                val current = _state.value
                val infos = current.patchInfos.toMutableList()
                while (infos.size <= index) infos.add(PatchInfo(PatchType.UNKNOWN, 0, ""))
                infos[index] = info
                _state.value = current.copy(patchInfos = infos)
            } catch (_: Exception) {}
        }
    }

    private fun refreshBackupInfo() {
        val backups = romPatcher.listBackups()
        _state.value = _state.value.copy(backupCount = backups.size)
        val romUri = _state.value.romUri
        if (romUri != null) {
            _state.value = _state.value.copy(canRestore = romPatcher.hasBackup(romUri))
        }
    }
}
