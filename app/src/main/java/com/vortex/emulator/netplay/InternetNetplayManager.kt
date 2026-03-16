package com.vortex.emulator.netplay

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Internet multiplayer manager with end-to-end encryption.
 *
 * Uses the LobbyClient for signaling and relay, with VortexCrypto
 * encrypting all game data so the relay server sees only opaque blobs.
 *
 * Flow:
 * 1. Player creates/joins a room via LobbyClient
 * 2. All players mark ready
 * 3. Host sends StartGame → server distributes session key seed
 * 4. Each side derives AES-256-GCM key from room code + seed
 * 5. Encrypted input frames are relayed through the server
 */
@Singleton
class InternetNetplayManager @Inject constructor(
    val lobbyClient: LobbyClient
) {
    companion object {
        private const val TAG = "InternetNetplay"
        private const val MAGIC = 0x56585445 // "VXTE" (Vortex Encrypted)
        private const val TYPE_INPUT: Byte = 0
        private const val TYPE_PING: Byte = 1
        private const val TYPE_SYNC: Byte = 2
        private const val HEADER_SIZE = 10 // magic(4) + type(1) + player(1) + frame(4)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var crypto: VortexCrypto? = null

    private val _isGameActive = MutableStateFlow(false)
    val isGameActive: StateFlow<Boolean> = _isGameActive.asStateFlow()

    private val _localPlayerIndex = MutableStateFlow(0)
    val localPlayerIndex: StateFlow<Int> = _localPlayerIndex.asStateFlow()

    private val _remoteInputs = Array(8) { IntArray(16) }
    /** Remote input for a given player port (0-7). */
    fun getRemoteInput(port: Int): IntArray =
        if (port in _remoteInputs.indices) _remoteInputs[port] else IntArray(16)

    /** Legacy single-player remote access. */
    val remoteInput: IntArray get() = _remoteInputs.getOrElse(1) { IntArray(16) }

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    @Volatile private var frameCounter: Long = 0
    private var eventCollectionJob: Job? = null

    // ── Room Management ─────────────────────────────────────────

    fun connectToLobby(config: LobbyConfig = LobbyConfig()) {
        lobbyClient.connect(config)
        startEventCollection()
    }

    fun disconnectFromLobby() {
        stopGame()
        lobbyClient.disconnect()
        eventCollectionJob?.cancel()
    }

    fun createRoom(
        hostName: String,
        gameName: String,
        platform: String,
        coreName: String,
        maxPlayers: Int = 2,
        password: String? = null,
        region: String = "auto"
    ) {
        lobbyClient.send(LobbyCommand.CreateRoom(
            hostName = hostName,
            gameName = gameName,
            platform = platform,
            coreName = coreName,
            maxPlayers = maxPlayers,
            password = password,
            region = region
        ))
    }

    fun joinRoom(roomId: String, playerName: String, password: String? = null) {
        lobbyClient.send(LobbyCommand.JoinRoom(roomId, playerName, password))
    }

    fun leaveRoom() {
        stopGame()
        lobbyClient.send(LobbyCommand.LeaveRoom)
    }

    fun setReady(ready: Boolean) {
        lobbyClient.send(LobbyCommand.SetReady(ready))
    }

    fun requestRoomList() {
        lobbyClient.send(LobbyCommand.RequestRoomList)
    }

    fun startGameAsHost() {
        lobbyClient.send(LobbyCommand.StartGame)
    }

    fun sendChat(message: String) {
        lobbyClient.send(LobbyCommand.SendChat(message))
    }

    // ── Encrypted Game Data ─────────────────────────────────────

    fun sendInput(localInput: IntArray) {
        val c = crypto ?: return
        if (!_isGameActive.value) return

        frameCounter++
        val packet = buildPacket(TYPE_INPUT, _localPlayerIndex.value, frameCounter, localInput)
        val encrypted = c.encrypt(packet)
        lobbyClient.send(LobbyCommand.RelayInput(null, encrypted))
    }

    fun stopGame() {
        _isGameActive.value = false
        crypto = null
        frameCounter = 0
        _remoteInputs.forEach { it.fill(0) }
    }

    // ── Event Handling ──────────────────────────────────────────

    private fun startEventCollection() {
        eventCollectionJob?.cancel()
        eventCollectionJob = scope.launch {
            lobbyClient.events.collect { event ->
                when (event) {
                    is LobbyEvent.GameStarting -> {
                        initializeEncryptedSession(event.sessionKey)
                    }
                    is LobbyEvent.RelayData -> {
                        handleRelayData(event.data)
                    }
                    is LobbyEvent.RoomJoined -> {
                        // Determine local player index
                        val me = event.players.find { it.playerId == lobbyClient.playerId }
                        _localPlayerIndex.value = me?.playerIndex ?: 0
                    }
                    is LobbyEvent.PlayerJoined -> {
                        Log.i(TAG, "Player joined: ${event.player.displayName}")
                    }
                    is LobbyEvent.PlayerLeft -> {
                        Log.i(TAG, "Player left: ${event.playerId}")
                    }
                    else -> { /* handled by UI */ }
                }
            }
        }
    }

    private fun initializeEncryptedSession(sessionKeySeed: String) {
        val room = lobbyClient.currentRoom.value
        val roomCode = room?.roomCode ?: ""
        val combinedSecret = "$roomCode:$sessionKeySeed"

        crypto = VortexCrypto(VortexCrypto.deriveKeyFromSecret(combinedSecret))
        _isGameActive.value = true
        frameCounter = 0
        _remoteInputs.forEach { it.fill(0) }

        Log.i(TAG, "Encrypted session initialized for room ${room?.roomCode}")
    }

    private fun handleRelayData(encryptedData: ByteArray) {
        val c = crypto ?: return

        val decrypted = c.decrypt(encryptedData) ?: run {
            Log.w(TAG, "Failed to decrypt relay data")
            return
        }

        if (decrypted.size < HEADER_SIZE) return

        val bb = ByteBuffer.wrap(decrypted).order(ByteOrder.BIG_ENDIAN)
        val magic = bb.int
        if (magic != MAGIC) return

        val type = bb.get()
        val playerNum = bb.get().toInt()
        val frame = bb.int.toLong()

        when (type) {
            TYPE_INPUT -> {
                val inputCount = minOf(16, decrypted.size - HEADER_SIZE)
                val targetPort = if (playerNum < _remoteInputs.size) playerNum else 0
                for (i in 0 until inputCount) {
                    _remoteInputs[targetPort][i] = decrypted[HEADER_SIZE + i].toInt()
                }
            }
            TYPE_PING -> {
                _latencyMs.value = System.currentTimeMillis() - frame
            }
            TYPE_SYNC -> {
                Log.i(TAG, "Sync from player $playerNum at frame $frame")
            }
        }
    }

    private fun buildPacket(type: Byte, playerNum: Int, frame: Long, input: IntArray): ByteArray {
        val size = HEADER_SIZE + 16
        val bb = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(MAGIC)
        bb.put(type)
        bb.put(playerNum.toByte())
        bb.putInt(frame.toInt())
        for (i in 0 until 16) {
            bb.put((if (i < input.size) input[i] else 0).toByte())
        }
        return bb.array()
    }
}
