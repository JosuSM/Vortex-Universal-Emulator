package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.core.Platform
import com.vortex.emulator.game.Game
import com.vortex.emulator.game.GameDao
import com.vortex.emulator.ui.screens.ViewMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val gameDao: GameDao
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<Platform?>(null)
    val selectedPlatform: StateFlow<Platform?> = _selectedPlatform.asStateFlow()

    private val _viewMode = MutableStateFlow(ViewMode.LIST)
    val viewMode: StateFlow<ViewMode> = _viewMode.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredGames: StateFlow<List<Game>> = combine(
        _searchQuery,
        _selectedPlatform
    ) { query, platform ->
        Pair(query, platform)
    }.flatMapLatest { (query, platform) ->
        when {
            query.isNotBlank() -> gameDao.searchGames(query)
            platform != null -> gameDao.getGamesByPlatform(platform)
            else -> gameDao.getAllGames()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSelectedPlatform(platform: Platform?) {
        _selectedPlatform.value = platform
    }

    fun setViewMode(mode: ViewMode) {
        _viewMode.value = mode
    }
}
