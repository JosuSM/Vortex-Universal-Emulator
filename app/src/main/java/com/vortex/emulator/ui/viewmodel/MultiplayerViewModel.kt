package com.vortex.emulator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.core.CoreFeature
import com.vortex.emulator.core.CoreInfo
import com.vortex.emulator.core.CoreManager
import com.vortex.emulator.core.Platform
import com.vortex.emulator.game.Game
import com.vortex.emulator.game.GameDao
import com.vortex.emulator.netplay.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MultiplayerMode { NONE, HOST, JOIN, LOBBY }
enum class LobbyViewMode { FEATURED, SEARCH, ALL }

@HiltViewModel
class MultiplayerViewModel @Inject constructor(
    private val coreManager: CoreManager,
    private val gameDao: GameDao,
    val netplayManager: NetplayManager,
    val internetNetplay: InternetNetplayManager
) : ViewModel() {

    private val lobbyClient get() = internetNetplay.lobbyClient

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

    // LAN netplay state
    val netplayRole: StateFlow<NetplayRole> = netplayManager.role
    val netplayState: StateFlow<NetplayState> = netplayManager.state
    val netplaySession: StateFlow<NetplaySession?> = netplayManager.session
    val latencyMs: StateFlow<Long> = netplayManager.latencyMs

    // Internet lobby state
    val lobbyState: StateFlow<LobbyState> = lobbyClient.state
    val lobbyRooms: StateFlow<List<LobbyRoom>> = lobbyClient.rooms
    val currentRoom: StateFlow<LobbyRoom?> = lobbyClient.currentRoom
    val lobbyPlayers: StateFlow<List<LobbyPlayer>> = lobbyClient.players
    val lobbyChatMessages: StateFlow<List<Pair<String, String>>> = lobbyClient.chatMessages
    val internetLatency: StateFlow<Long> = internetNetplay.latencyMs
    val isGameActive: StateFlow<Boolean> = internetNetplay.isGameActive

    // Game start navigation: emits a gameId when the lobby game should launch
    private val _gameStartId = MutableStateFlow<Long?>(null)
    val gameStartId: StateFlow<Long?> = _gameStartId.asStateFlow()

    fun clearGameStartId() { _gameStartId.value = null }

    // Lobby UI state
    private val _playerName = MutableStateFlow("Player")
    val playerName: StateFlow<String> = _playerName.asStateFlow()

    private val _lobbySearchQuery = MutableStateFlow("")
    val lobbySearchQuery: StateFlow<String> = _lobbySearchQuery.asStateFlow()

    private val _selectedLobbyPlatform = MutableStateFlow<Platform?>(null)
    val selectedLobbyPlatform: StateFlow<Platform?> = _selectedLobbyPlatform.asStateFlow()

    private val _roomPassword = MutableStateFlow("")
    val roomPassword: StateFlow<String> = _roomPassword.asStateFlow()

    private val _showCreateRoom = MutableStateFlow(false)
    val showCreateRoom: StateFlow<Boolean> = _showCreateRoom.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _chatInput = MutableStateFlow("")
    val chatInput: StateFlow<String> = _chatInput.asStateFlow()

    // Join by code
    private val _joinRoomCode = MutableStateFlow("")
    val joinRoomCode: StateFlow<String> = _joinRoomCode.asStateFlow()

    // Local sessions (LAN-discovered or host's own)
    private val _localSessions = MutableStateFlow<List<LobbyRoom>>(emptyList())
    val localSessions: StateFlow<List<LobbyRoom>> = _localSessions.asStateFlow()

    // Lobby view mode (Featured / Search / All)
    private val _lobbyViewMode = MutableStateFlow(LobbyViewMode.FEATURED)
    val lobbyViewMode: StateFlow<LobbyViewMode> = _lobbyViewMode.asStateFlow()

    // Show all public lobbies flag
    private val _showAllPublicLobbies = MutableStateFlow(false)
    val showAllPublicLobbies: StateFlow<Boolean> = _showAllPublicLobbies.asStateFlow()

    // Filtered lobby rooms
    val filteredRooms: StateFlow<List<LobbyRoom>> = combine(
        lobbyRooms,
        _lobbySearchQuery,
        _selectedLobbyPlatform
    ) { rooms, query, platform ->
        var filtered = rooms
        if (query.isNotBlank()) {
            val q = query.lowercase()
            filtered = filtered.filter {
                it.gameName.lowercase().contains(q) ||
                    it.hostName.lowercase().contains(q) ||
                    it.roomCode.lowercase().contains(q) ||
                    it.coreName.lowercase().contains(q) ||
                    it.playability.lowercase().contains(q) ||
                    (it.platform?.displayName?.lowercase()?.contains(q) == true) ||
                    (it.platform?.abbreviation?.lowercase()?.contains(q) == true)
            }
        }
        if (platform != null) {
            filtered = filtered.filter { it.platform == platform }
        }
        filtered.sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Featured rooms: first 5 public rooms with available slots
    val featuredRooms: StateFlow<List<LobbyRoom>> = lobbyRooms
        .map { rooms ->
            rooms.filter { it.isPublic && it.playerCount < it.maxPlayers }
                .sortedByDescending { it.playerCount }
                .take(5)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    init {
        // Collect lobby events for status messages
        viewModelScope.launch {
            lobbyClient.events.collect { event ->
                when (event) {
                    is LobbyEvent.RoomJoined -> {
                        _statusMessage.value = "Joined room: ${event.room.roomCode}"
                        _showCreateRoom.value = false
                    }
                    is LobbyEvent.PlayerJoined -> {
                        _statusMessage.value = "${event.player.displayName} joined"
                    }
                    is LobbyEvent.PlayerLeft -> {
                        _statusMessage.value = "A player left the room"
                    }
                    is LobbyEvent.GameStarting -> {
                        _statusMessage.value = "Game starting — resolving local ROM…"
                        // Resolve the lobby room's gameName to a local game ID
                        val room = currentRoom.value
                        if (room != null) {
                            viewModelScope.launch {
                                val resolved = resolveGameFromRoom(room)
                                if (resolved != null) {
                                    _gameStartId.value = resolved.id
                                    _statusMessage.value = "Launching ${resolved.title}…"
                                } else {
                                    _statusMessage.value = "Game not found in library — add the ROM first"
                                }
                            }
                        }
                    }
                    is LobbyEvent.Error -> {
                        _statusMessage.value = "Error: ${event.message}"
                    }
                    is LobbyEvent.Disconnected -> {
                        _statusMessage.value = "Disconnected from lobby"
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Setters ─────────────────────────────────────────────────

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSelectedPlatform(platform: Platform?) { _selectedPlatform.value = platform }
    fun setSelectedCore(coreId: String?) { _selectedCoreId.value = coreId }
    fun setMultiplayerMode(mode: MultiplayerMode) { _multiplayerMode.value = mode }
    fun setJoinIpAddress(ip: String) { _joinIpAddress.value = ip }
    fun setPlayerName(name: String) { _playerName.value = name }
    fun setLobbySearchQuery(query: String) { _lobbySearchQuery.value = query }
    fun setSelectedLobbyPlatform(platform: Platform?) { _selectedLobbyPlatform.value = platform }
    fun setRoomPassword(password: String) { _roomPassword.value = password }
    fun setShowCreateRoom(show: Boolean) { _showCreateRoom.value = show }
    fun setChatInput(text: String) { _chatInput.value = text }
    fun setJoinRoomCode(code: String) { _joinRoomCode.value = code }
    fun setLobbyViewMode(mode: LobbyViewMode) { _lobbyViewMode.value = mode }

    // ── LAN Netplay Actions ─────────────────────────────────────

    fun hostSession(gameName: String) {
        val session = netplayManager.hostSession(gameName)
        if (session != null) {
            _statusMessage.value = "Hosting on ${session.hostAddress}:${session.port}\nRoom: ${session.roomCode}"
            // Add to local sessions list so others see it
            val localRoom = LobbyRoom(
                roomId = session.roomCode,
                roomCode = session.roomCode,
                hostName = _playerName.value.ifBlank { "Host" },
                gameName = gameName,
                platform = null,
                coreName = "",
                playerCount = session.playerCount,
                maxPlayers = 2,
                latencyMs = 0,
                region = "LAN",
                isPasswordProtected = false,
                isPublic = false,
                playability = "Waiting for Players"
            )
            _localSessions.value = _localSessions.value + localRoom
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

    // ── Internet Lobby Actions ──────────────────────────────────

    fun connectToLobby() {
        internetNetplay.connectToLobby()
        _statusMessage.value = "Connecting to global lobby…"
    }

    fun disconnectFromLobby() {
        internetNetplay.disconnectFromLobby()
        _isReady.value = false
        _statusMessage.value = "Disconnected from lobby"
    }

    fun refreshRooms() {
        internetNetplay.requestRoomList()
    }

    fun requestAllPublicRooms() {
        internetNetplay.lobbyClient.send(LobbyCommand.RequestAllRooms)
        _lobbyViewMode.value = LobbyViewMode.ALL
        _statusMessage.value = "Loading all public lobbies…"
    }

    fun joinByRoomCode() {
        val code = _joinRoomCode.value.trim().uppercase()
        if (code.isBlank()) {
            _statusMessage.value = "Enter a room code"
            return
        }
        val name = _playerName.value.trim().ifBlank { "Player" }
        internetNetplay.lobbyClient.send(LobbyCommand.JoinByCode(code, name, null))
        _statusMessage.value = "Joining room $code…"
    }

    fun createRoom(gameName: String, platform: Platform, coreName: String, maxPlayers: Int = 2) {
        val name = _playerName.value.trim().ifBlank { "Player" }
        val password = _roomPassword.value.trim().takeIf { it.isNotBlank() }
        internetNetplay.createRoom(
            hostName = name,
            gameName = gameName,
            platform = platform.name,
            coreName = coreName,
            maxPlayers = maxPlayers,
            password = password
        )
        _statusMessage.value = "Creating room…"
    }

    fun joinLobbyRoom(room: LobbyRoom) {
        val name = _playerName.value.trim().ifBlank { "Player" }
        val password = if (room.isPasswordProtected) _roomPassword.value.trim() else null
        internetNetplay.joinRoom(room.roomId, name, password)
        _statusMessage.value = "Joining ${room.hostName}'s room…"
    }

    fun leaveLobbyRoom() {
        internetNetplay.leaveRoom()
        _isReady.value = false
        _statusMessage.value = "Left room"
    }

    fun toggleReady() {
        _isReady.value = !_isReady.value
        internetNetplay.setReady(_isReady.value)
    }

    fun startGameAsHost() {
        internetNetplay.startGameAsHost()
    }

    fun sendChatMessage() {
        val msg = _chatInput.value.trim()
        if (msg.isBlank()) return
        internetNetplay.sendChat(msg)
        _chatInput.value = ""
    }

    /** Resolve a lobby room's game name + platform to a local Game entity. */
    private suspend fun resolveGameFromRoom(room: LobbyRoom): Game? {
        val name = room.gameName
        val platform = room.platform
        // Try exact title + platform match first
        if (platform != null) {
            val byPlatform = gameDao.searchGamesByPlatformOnce(name, platform)
            val exact = byPlatform.firstOrNull { it.title.equals(name, ignoreCase = true) }
            if (exact != null) return exact
            if (byPlatform.isNotEmpty()) return byPlatform.first()
        }
        // Fallback: search by title across all platforms
        val all = gameDao.searchGamesOnce(name)
        return all.firstOrNull { it.title.equals(name, ignoreCase = true) } ?: all.firstOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        netplayManager.disconnect()
        internetNetplay.disconnectFromLobby()
    }
}
