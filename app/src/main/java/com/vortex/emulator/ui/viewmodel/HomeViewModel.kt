package com.vortex.emulator.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.core.CoreManager
import com.vortex.emulator.core.CoreFeature
import com.vortex.emulator.emulation.CoreDownloader
import com.vortex.emulator.game.Game
import com.vortex.emulator.game.GameDao
import com.vortex.emulator.game.GameScanner
import com.vortex.emulator.game.ScanResult
import com.vortex.emulator.gpu.ChipsetDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val gameDao: GameDao,
    private val gameScanner: GameScanner,
    private val chipsetDetector: ChipsetDetector,
    private val coreManager: CoreManager,
    private val coreDownloader: CoreDownloader
) : ViewModel() {

    init {
        // Refresh installed status so standalone emulators installed
        // while the app was in the background are detected.
        coreManager.refreshInstalled()
    }

    val recentGames: StateFlow<List<Game>> = gameDao.getRecentGames(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteGames: StateFlow<List<Game>> = gameDao.getFavoriteGames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gameCount: StateFlow<Int> = gameDao.getGameCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val chipsetTier: StateFlow<String> = flow {
        emit(chipsetDetector.chipsetInfo.chipsetTier.displayName)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "...")

    val coreCount: StateFlow<Int> = coreManager.availableCores
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val installedCoreCount: StateFlow<Int> = coreManager.installedCores
        .map { cores -> cores.count { it.isInstalled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val multiplayerReadyCoreCount: StateFlow<Int> = coreManager.availableCores
        .map { cores -> cores.count { CoreFeature.NETPLAY in it.features } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun isOffline(): Boolean = !coreDownloader.isNetworkAvailable()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanFilesScanned = MutableStateFlow(0)
    val scanFilesScanned: StateFlow<Int> = _scanFilesScanned.asStateFlow()

    private val _scanResult = MutableSharedFlow<ScanResult>(extraBufferCapacity = 1)
    val scanResult: SharedFlow<ScanResult> = _scanResult.asSharedFlow()

    private val _scanError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val scanError: SharedFlow<String> = _scanError.asSharedFlow()

    fun toggleFavorite(game: Game) {
        viewModelScope.launch {
            gameDao.setFavorite(game.id, !game.isFavorite)
        }
    }

    fun scanDirectory(uri: Uri) {
        // Persist read permission so content:// URIs remain accessible across app restarts
        try {
            application.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* best effort */ }

        viewModelScope.launch {
            _isScanning.value = true
            _scanFilesScanned.value = 0
            try {
                val result = gameScanner.scanDirectory(uri) { filesScanned ->
                    _scanFilesScanned.value = filesScanned
                }
                _isScanning.value = false
                if (result.errors.isNotEmpty() && result.totalFound == 0) {
                    _scanError.emit(result.errors.first())
                } else {
                    _scanResult.emit(result)
                }
            } catch (e: Exception) {
                _isScanning.value = false
                _scanError.emit(e.message ?: "Scan failed unexpectedly")
            }
        }
    }
}
