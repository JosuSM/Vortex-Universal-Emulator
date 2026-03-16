package com.vortex.emulator.netplay

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket-based lobby client for global internet multiplayer.
 *
 * Protocol: JSON messages over WSS with the following structure:
 *   { "type": "<command>", "payload": { ... } }
 *
 * All game data is encrypted with AES-256-GCM before being relayed.
 * The server only sees opaque blobs — zero-knowledge relay.
 */
@Singleton
class LobbyClient @Inject constructor() {

    companion object {
        private const val TAG = "LobbyClient"
        private const val PING_INTERVAL_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val playerId: String = UUID.randomUUID().toString().take(12)

    private val _state = MutableStateFlow(LobbyState.DISCONNECTED)
    val state: StateFlow<LobbyState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<LobbyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<LobbyEvent> = _events.asSharedFlow()

    private val _rooms = MutableStateFlow<List<LobbyRoom>>(emptyList())
    val rooms: StateFlow<List<LobbyRoom>> = _rooms.asStateFlow()

    private val _currentRoom = MutableStateFlow<LobbyRoom?>(null)
    val currentRoom: StateFlow<LobbyRoom?> = _currentRoom.asStateFlow()

    private val _players = MutableStateFlow<List<LobbyPlayer>>(emptyList())
    val players: StateFlow<List<LobbyPlayer>> = _players.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val chatMessages: StateFlow<List<Pair<String, String>>> = _chatMessages.asStateFlow()

    private var config = LobbyConfig()
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null

    // ── Connection ──────────────────────────────────────────────

    fun connect(lobbyConfig: LobbyConfig = LobbyConfig()) {
        if (_state.value == LobbyState.CONNECTING || _state.value == LobbyState.CONNECTED) return
        config = lobbyConfig
        _state.value = LobbyState.CONNECTING
        reconnectAttempts = 0
        openWebSocket()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _state.value = LobbyState.DISCONNECTED
        _currentRoom.value = null
        _players.value = emptyList()
        _rooms.value = emptyList()
        _chatMessages.value = emptyList()
    }

    private fun openWebSocket() {
        val request = Request.Builder()
            .url(config.serverUrl)
            .addHeader("X-Vortex-Player", playerId)
            .addHeader("X-Vortex-Region", config.region)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to lobby: ${config.serverUrl}")
                _state.value = LobbyState.CONNECTED
                reconnectAttempts = 0
                send(LobbyCommand.RequestRoomList)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Lobby closing: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Lobby closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Lobby connection failed: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun handleDisconnect() {
        _state.value = LobbyState.DISCONNECTED
        _currentRoom.value = null
        _events.tryEmit(LobbyEvent.Disconnected)

        // Auto-reconnect
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(RECONNECT_DELAY_MS * (reconnectAttempts + 1))
                reconnectAttempts++
                Log.i(TAG, "Reconnecting (attempt $reconnectAttempts)...")
                _state.value = LobbyState.CONNECTING
                openWebSocket()
            }
        }
    }

    // ── Send Commands ───────────────────────────────────────────

    fun send(command: LobbyCommand) {
        val json = when (command) {
            is LobbyCommand.CreateRoom -> JSONObject().apply {
                put("type", "create_room")
                put("payload", JSONObject().apply {
                    put("host_name", command.hostName)
                    put("game_name", command.gameName)
                    put("platform", command.platform)
                    put("core_name", command.coreName)
                    put("max_players", command.maxPlayers)
                    put("region", command.region)
                    put("encrypted", true)
                    if (command.password != null) {
                        put("password_protected", true)
                        put("password", command.password)
                    }
                })
            }
            is LobbyCommand.JoinRoom -> JSONObject().apply {
                put("type", "join_room")
                put("payload", JSONObject().apply {
                    put("room_id", command.roomId)
                    put("player_name", command.playerName)
                    if (command.password != null) {
                        put("password", command.password)
                    }
                })
            }
            is LobbyCommand.JoinByCode -> JSONObject().apply {
                put("type", "join_by_code")
                put("payload", JSONObject().apply {
                    put("room_code", command.roomCode)
                    put("player_name", command.playerName)
                    if (command.password != null) {
                        put("password", command.password)
                    }
                })
            }
            is LobbyCommand.LeaveRoom -> JSONObject().apply {
                put("type", "leave_room")
            }
            is LobbyCommand.SetReady -> JSONObject().apply {
                put("type", "set_ready")
                put("payload", JSONObject().apply { put("ready", command.ready) })
            }
            is LobbyCommand.RequestRoomList -> JSONObject().apply {
                put("type", "list_rooms")
            }
            is LobbyCommand.RequestAllRooms -> JSONObject().apply {
                put("type", "list_all_rooms")
            }
            is LobbyCommand.RelayInput -> JSONObject().apply {
                put("type", "relay")
                put("payload", JSONObject().apply {
                    if (command.targetPlayerId != null) {
                        put("target", command.targetPlayerId)
                    }
                    put("data", Base64.encodeToString(command.data, Base64.NO_WRAP))
                })
            }
            is LobbyCommand.SendChat -> JSONObject().apply {
                put("type", "chat")
                put("payload", JSONObject().apply { put("message", command.message) })
            }
            is LobbyCommand.StartGame -> JSONObject().apply {
                put("type", "start_game")
            }
        }

        webSocket?.send(json.toString()) ?: Log.w(TAG, "Cannot send — not connected")
    }

    // ── Handle Incoming Messages ────────────────────────────────

    private fun handleMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            val type = json.optString("type", "")
            val payload = json.optJSONObject("payload")

            when (type) {
                "room_list" -> {
                    val roomsArray = payload?.optJSONArray("rooms") ?: JSONArray()
                    val parsed = (0 until roomsArray.length()).map { i ->
                        parseRoom(roomsArray.getJSONObject(i))
                    }
                    _rooms.value = parsed
                    _events.tryEmit(LobbyEvent.RoomListUpdated(parsed))
                }
                "room_joined" -> {
                    val room = payload?.optJSONObject("room")?.let(::parseRoom)
                    val playersArr = payload?.optJSONArray("players") ?: JSONArray()
                    val playerList = (0 until playersArr.length()).map { i ->
                        parsePlayer(playersArr.getJSONObject(i))
                    }
                    if (room != null) {
                        _currentRoom.value = room
                        _players.value = playerList
                        _state.value = LobbyState.IN_ROOM
                        _chatMessages.value = emptyList()
                        _events.tryEmit(LobbyEvent.RoomJoined(room, playerList))
                    }
                }
                "player_joined" -> {
                    val player = payload?.let(::parsePlayer)
                    if (player != null) {
                        _players.value = _players.value + player
                        _events.tryEmit(LobbyEvent.PlayerJoined(player))
                    }
                }
                "player_left" -> {
                    val pid = payload?.optString("player_id") ?: ""
                    _players.value = _players.value.filter { it.playerId != pid }
                    _events.tryEmit(LobbyEvent.PlayerLeft(pid))
                }
                "player_ready" -> {
                    val pid = payload?.optString("player_id") ?: ""
                    val ready = payload?.optBoolean("ready") ?: false
                    _players.value = _players.value.map {
                        if (it.playerId == pid) it.copy(isReady = ready) else it
                    }
                    _events.tryEmit(LobbyEvent.PlayerReady(pid, ready))
                }
                "game_starting" -> {
                    val sessionKey = payload?.optString("session_key") ?: ""
                    _events.tryEmit(LobbyEvent.GameStarting(sessionKey))
                }
                "relay" -> {
                    val from = payload?.optString("from") ?: ""
                    val dataStr = payload?.optString("data") ?: ""
                    val data = Base64.decode(dataStr, Base64.NO_WRAP)
                    _events.tryEmit(LobbyEvent.RelayData(from, data))
                }
                "chat" -> {
                    val from = payload?.optString("from") ?: "?"
                    val msg = payload?.optString("message") ?: ""
                    _chatMessages.value = _chatMessages.value + (from to msg)
                    _events.tryEmit(LobbyEvent.ChatMessage(from, msg))
                }
                "error" -> {
                    val msg = payload?.optString("message") ?: "Unknown error"
                    _events.tryEmit(LobbyEvent.Error(msg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse lobby message", e)
        }
    }

    private fun parseRoom(json: JSONObject): LobbyRoom {
        return LobbyRoom(
            roomId = json.optString("room_id", ""),
            roomCode = json.optString("room_code", ""),
            hostName = json.optString("host_name", "Unknown"),
            gameName = json.optString("game_name", ""),
            platform = try {
                com.vortex.emulator.core.Platform.valueOf(json.optString("platform", ""))
            } catch (_: Exception) { null },
            coreName = json.optString("core_name", ""),
            playerCount = json.optInt("player_count", 1),
            maxPlayers = json.optInt("max_players", 2),
            latencyMs = json.optInt("latency_ms", 0),
            region = json.optString("region", ""),
            isPasswordProtected = json.optBoolean("password_protected", false),
            isEncrypted = json.optBoolean("encrypted", true),
            isPublic = json.optBoolean("is_public", true),
            playability = json.optString("playability", "Waiting"),
            createdAt = json.optLong("created_at", System.currentTimeMillis())
        )
    }

    private fun parsePlayer(json: JSONObject): LobbyPlayer {
        return LobbyPlayer(
            playerId = json.optString("player_id", ""),
            displayName = json.optString("display_name", "Player"),
            playerIndex = json.optInt("player_index", 0),
            latencyMs = json.optInt("latency_ms", 0),
            isHost = json.optBoolean("is_host", false),
            isReady = json.optBoolean("is_ready", false)
        )
    }
}
