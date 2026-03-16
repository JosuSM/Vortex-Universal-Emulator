package com.vortex.emulator.netplay

import com.vortex.emulator.core.Platform

/** A public lobby room visible to all connected players. */
data class LobbyRoom(
    val roomId: String,
    val roomCode: String,
    val hostName: String,
    val gameName: String,
    val platform: Platform?,
    val coreName: String,
    val playerCount: Int,
    val maxPlayers: Int,
    val latencyMs: Int,
    val region: String,
    val isPasswordProtected: Boolean,
    val isEncrypted: Boolean = true,
    val isPublic: Boolean = true,
    val playability: String = "Waiting",
    val createdAt: Long = System.currentTimeMillis()
)

/** A player in a lobby room. */
data class LobbyPlayer(
    val playerId: String,
    val displayName: String,
    val playerIndex: Int,
    val latencyMs: Int = 0,
    val isHost: Boolean = false,
    val isReady: Boolean = false
)

/** State of the lobby connection. */
enum class LobbyState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    IN_ROOM,
    ERROR
}

/** Events from the lobby server. */
sealed class LobbyEvent {
    data class RoomListUpdated(val rooms: List<LobbyRoom>) : LobbyEvent()
    data class RoomJoined(val room: LobbyRoom, val players: List<LobbyPlayer>) : LobbyEvent()
    data class PlayerJoined(val player: LobbyPlayer) : LobbyEvent()
    data class PlayerLeft(val playerId: String) : LobbyEvent()
    data class PlayerReady(val playerId: String, val ready: Boolean) : LobbyEvent()
    data class GameStarting(val sessionKey: String) : LobbyEvent()
    data class RelayData(val fromPlayerId: String, val data: ByteArray) : LobbyEvent() {
        override fun equals(other: Any?) = other is RelayData && fromPlayerId == other.fromPlayerId
        override fun hashCode() = fromPlayerId.hashCode()
    }
    data class ChatMessage(val fromPlayer: String, val message: String) : LobbyEvent()
    data class Error(val message: String) : LobbyEvent()
    data object Disconnected : LobbyEvent()
}

/** Outbound messages to the lobby server. */
sealed class LobbyCommand {
    data class CreateRoom(
        val hostName: String,
        val gameName: String,
        val platform: String,
        val coreName: String,
        val maxPlayers: Int,
        val password: String?,
        val region: String
    ) : LobbyCommand()

    data class JoinRoom(
        val roomId: String,
        val playerName: String,
        val password: String?
    ) : LobbyCommand()

    data class JoinByCode(
        val roomCode: String,
        val playerName: String,
        val password: String?
    ) : LobbyCommand()

    data object LeaveRoom : LobbyCommand()
    data class SetReady(val ready: Boolean) : LobbyCommand()
    data object RequestRoomList : LobbyCommand()
    data object RequestAllRooms : LobbyCommand()
    data class RelayInput(val targetPlayerId: String?, val data: ByteArray) : LobbyCommand() {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = data.contentHashCode()
    }
    data class SendChat(val message: String) : LobbyCommand()
    data object StartGame : LobbyCommand()
}

/** Configuration for the lobby server connection. */
data class LobbyConfig(
    val serverUrl: String = DEFAULT_LOBBY_URL,
    val region: String = "auto"
) {
    companion object {
        const val DEFAULT_LOBBY_URL = "wss://vortex-lobby.onrender.com/ws"
    }
}
