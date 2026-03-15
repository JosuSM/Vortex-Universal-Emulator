package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.game.Game
import com.vortex.emulator.game.GameDao
import com.vortex.emulator.game.GameScanner
import com.vortex.emulator.gpu.ChipsetDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val gameDao: GameDao,
    private val gameScanner: GameScanner,
    private val chipsetDetector: ChipsetDetector
) : ViewModel() {

    val recentGames: StateFlow<List<Game>> = gameDao.getRecentGames(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteGames: StateFlow<List<Game>> = gameDao.getFavoriteGames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gameCount: StateFlow<Int> = gameDao.getGameCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val chipsetTier: StateFlow<String> = flow {
        emit(chipsetDetector.chipsetInfo.chipsetTier.displayName)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "...")

    fun toggleFavorite(game: Game) {
        viewModelScope.launch {
            gameDao.setFavorite(game.id, !game.isFavorite)
        }
    }

    fun scanDefaultPaths() {
        viewModelScope.launch {
            val defaultPaths = listOf(
                "/sdcard/ROMs",
                "/sdcard/Download/ROMs",
                "/sdcard/Games",
                "/sdcard/RetroArch/roms"
            )
            for (path in defaultPaths) {
                gameScanner.scanPath(path)
            }
        }
    }
}
