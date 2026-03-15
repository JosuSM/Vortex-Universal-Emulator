package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.core.CoreFeature
import com.vortex.emulator.core.CoreInfo
import com.vortex.emulator.core.CoreManager
import com.vortex.emulator.core.Platform
import com.vortex.emulator.game.Game
import com.vortex.emulator.game.GameDao
import com.vortex.emulator.netplay.NetplayManager
import com.vortex.emulator.netplay.NetplayRole
import com.vortex.emulator.netplay.NetplaySession
import com.vortex.emulator.netplay.NetplayState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

enum class MultiplayerMode { NONE, HOST, JOIN, LOBBY }

@HiltViewModel
class MultiplayerViewModel @Inject constructor(
    private val coreManager: CoreManager,
    private val gameDao: GameDao,
    val netplayManager: NetplayManager
) : ViewModel() {

    val netplayCores: StateFlow<List<CoreInfo>> = coreManager.availableCores
        .map { cores -> cores.filter { CoreFeature.NETPLAY in it.features } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val netplayPlatforms: Flow<Set<Platform>> = netplayCores
        .map { cores -> cores.flatMap { it.supportedPlatforms }.toSet() }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<Platform?>(null)
    val selectedPlatform: StateFlow<Platform?> = _selectedPlatform.asStateFlow()

    private val _selectedCoreId = MutableStateFlow<String?>(null)
    val selectedCoreId: StateFlow<String?> = _selectedCoreId.asStateFlow()

    private val _multiplayerMode = MutableStateFlow(MultiplayerMode.NONE)
    val multiplayerMode: StateFlow<MultiplayerMode> = _multiplayerMode.asStateFlow()

    private val _joinIpAddress = MutableStateFlow("")
    val joinIpAddress: StateFlow<String> = _joinIpAddress.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    val netplayRole: StateFlow<NetplayRole> = netplayManager.role
    val netplayState: StateFlow<NetplayState> = netplayManager.state
    val netplaySession: StateFlow<NetplaySession?> = netplayManager.session
    val latencyMs: StateFlow<Long> = netplayManager.latencyMs

    val netplayPlatformList: StateFlow<List<Platform>> = netplayPlatforms
        .map { it.toList().sortedBy { p -> p.displayName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val allNetplayGames: Flow<List<Game>> = gameDao.getAllGames()
        .combine(netplayPlatforms) { games, platforms ->
            games.filter { it.platform in platforms }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val netplayGames: StateFlow<List<Game>> = combine(
        allNetplayGames,
        _searchQuery,
        _selectedPlatform,
        _selectedCoreId
    ) { games, query, platform, coreId ->
        var filtered = games
        if (query.isNotBlank()) {
            filtered = filtered.filter { it.title.contains(query, ignoreCase = true) }
        }
        if (platform != null) {
            filtered = filtered.filter { it.platform == platform }
        }
        if (coreId != null) {
            val corePlatforms = coreManager.getCoreById(coreId)?.supportedPlatforms ?: emptyList()
            filtered = filtered.filter { it.platform in corePlatforms }
        }
        filtered
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSelectedPlatform(platform: Platform?) {
        _selectedPlatform.value = platform
    }

    fun setSelectedCore(coreId: String?) {
        _selectedCoreId.value = coreId
    }

    fun setMultiplayerMode(mode: MultiplayerMode) {
        _multiplayerMode.value = mode
    }

    fun setJoinIpAddress(ip: String) {
        _joinIpAddress.value = ip
    }

    // ── Netplay Actions ─────────────────────────────────────────

    fun hostSession(gameName: String) {
        val session = netplayManager.hostSession(gameName)
        if (session != null) {
            _statusMessage.value = "Hosting on ${session.hostAddress}:${session.port}\nRoom: ${session.roomCode}"
        } else {
            _statusMessage.value = "Failed to start hosting"
        }
    }

    fun joinSession() {
        val ip = _joinIpAddress.value.trim()
        if (ip.isBlank()) {
            _statusMessage.value = "Enter host IP address"
            return
        }
        val success = netplayManager.joinSession(ip)
        _statusMessage.value = if (success) "Connected to $ip" else "Failed to connect"
    }

    fun disconnect() {
        netplayManager.disconnect()
        _statusMessage.value = "Disconnected"
        _multiplayerMode.value = MultiplayerMode.NONE
    }

    fun getLocalIp(): String = netplayManager.getLocalIpAddress()

    override fun onCleared() {
        super.onCleared()
        netplayManager.disconnect()
    }
}
