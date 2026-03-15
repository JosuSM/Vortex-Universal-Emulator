package com.vortex.emulator.netplay

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

enum class NetplayRole { HOST, CLIENT, NONE }
enum class NetplayState { DISCONNECTED, WAITING, CONNECTED, ERROR }

data class NetplaySession(
    val roomCode: String,
    val hostAddress: String,
    val port: Int,
    val playerCount: Int = 1,
    val gameName: String = ""
)

/**
 * LAN-based netplay using UDP for low-latency input exchange.
 *
 * Protocol:
 * - Host listens on a UDP port, client connects.
 * - Each frame, both sides exchange their local input state.
 * - The host is Player 1, client is Player 2.
 *
 * Packet format (24 bytes):
 *   [0..3]: Magic "VRTX"
 *   [4]:    Packet type (0=INPUT, 1=PING, 2=SYNC, 3=DISCONNECT)
 *   [5]:    Player number (0 or 1)
 *   [6..9]: Frame number (uint32)
 *   [10..25]: 16 button states (int16 x 8 = buttons packed)
 */
@Singleton
class NetplayManager @Inject constructor() {

    companion object {
        private const val TAG = "NetplayManager"
        private const val DEFAULT_PORT = 55435
        private const val MAGIC = 0x56525458 // "VRTX" in ASCII
        private const val PACKET_SIZE = 26
        private const val TYPE_INPUT: Byte = 0
        private const val TYPE_PING: Byte = 1
        private const val TYPE_SYNC: Byte = 2
        private const val TYPE_DISCONNECT: Byte = 3
    }

    private val _role = MutableStateFlow(NetplayRole.NONE)
    val role: StateFlow<NetplayRole> = _role.asStateFlow()

    private val _state = MutableStateFlow(NetplayState.DISCONNECTED)
    val state: StateFlow<NetplayState> = _state.asStateFlow()

    private val _session = MutableStateFlow<NetplaySession?>(null)
    val session: StateFlow<NetplaySession?> = _session.asStateFlow()

    private val _latencyMs = MutableStateFlow(0L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    // Remote player's latest input (16 buttons)
    private val _remoteInput = IntArray(16)
    val remoteInput: IntArray get() = _remoteInput

    private var socket: DatagramSocket? = null
    private var peerAddress: InetAddress? = null
    private var peerPort: Int = 0
    private var networkJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var frameCounter: Long = 0

    fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return "VRTX-" + (1..4).map { chars.random() }.joinToString("")
    }

    fun getLocalIpAddress(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .map { it.hostAddress ?: "unknown" }
                .firstOrNull() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun hostSession(gameName: String): NetplaySession? {
        return try {
            disconnect()
            socket = DatagramSocket(DEFAULT_PORT)
            socket?.soTimeout = 100

            val roomCode = generateRoomCode()
            val localIp = getLocalIpAddress()

            val session = NetplaySession(
                roomCode = roomCode,
                hostAddress = localIp,
                port = DEFAULT_PORT,
                playerCount = 1,
                gameName = gameName
            )

            _role.value = NetplayRole.HOST
            _state.value = NetplayState.WAITING
            _session.value = session
            frameCounter = 0

            startListening()
            Log.i(TAG, "Hosting session: $roomCode on $localIp:$DEFAULT_PORT")
            session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to host session", e)
            _state.value = NetplayState.ERROR
            null
        }
    }

    fun joinSession(hostIp: String, port: Int = DEFAULT_PORT): Boolean {
        return try {
            disconnect()
            socket = DatagramSocket()
            socket?.soTimeout = 100

            peerAddress = InetAddress.getByName(hostIp)
            peerPort = port

            _role.value = NetplayRole.CLIENT
            _state.value = NetplayState.CONNECTED
            _session.value = NetplaySession(
                roomCode = "",
                hostAddress = hostIp,
                port = port,
                playerCount = 2
            )
            frameCounter = 0

            // Send initial sync packet to host
            sendPacket(TYPE_SYNC, 1, 0, IntArray(16))
            startListening()

            Log.i(TAG, "Joined session at $hostIp:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join session", e)
            _state.value = NetplayState.ERROR
            false
        }
    }

    fun sendInput(localInput: IntArray) {
        if (_state.value != NetplayState.CONNECTED) return

        val playerNum = if (_role.value == NetplayRole.HOST) 0 else 1
        frameCounter++
        sendPacket(TYPE_INPUT, playerNum, frameCounter, localInput)
    }

    fun disconnect() {
        networkJob?.cancel()
        networkJob = null

        if (_state.value == NetplayState.CONNECTED && socket != null) {
            val playerNum = if (_role.value == NetplayRole.HOST) 0 else 1
            sendPacket(TYPE_DISCONNECT, playerNum, frameCounter, IntArray(16))
        }

        socket?.close()
        socket = null
        peerAddress = null
        peerPort = 0
        _role.value = NetplayRole.NONE
        _state.value = NetplayState.DISCONNECTED
        _session.value = null
        _latencyMs.value = 0
        frameCounter = 0
        _remoteInput.fill(0)
        Log.i(TAG, "Disconnected")
    }

    private fun startListening() {
        networkJob?.cancel()
        networkJob = scope.launch {
            val buf = ByteArray(PACKET_SIZE)
            val packet = DatagramPacket(buf, buf.size)

            while (isActive) {
                try {
                    socket?.receive(packet) ?: break

                    val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
                    val magic = bb.int
                    if (magic != MAGIC) continue

                    val type = bb.get()
                    val playerNum = bb.get().toInt()
                    val frame = bb.int.toLong()

                    when (type) {
                        TYPE_INPUT -> {
                            // Read 16 button states (each as a byte, 0 or 1)
                            for (i in 0 until 16) {
                                _remoteInput[i] = bb.get().toInt()
                            }
                        }
                        TYPE_SYNC -> {
                            // Client connected to host
                            if (_role.value == NetplayRole.HOST) {
                                peerAddress = packet.address
                                peerPort = packet.port
                                _state.value = NetplayState.CONNECTED
                                _session.value = _session.value?.copy(playerCount = 2)
                                // Send sync ack
                                sendPacket(TYPE_SYNC, 0, 0, IntArray(16))
                                Log.i(TAG, "Client connected from ${packet.address}:${packet.port}")
                            }
                        }
                        TYPE_PING -> {
                            val now = System.currentTimeMillis()
                            _latencyMs.value = now - frame
                        }
                        TYPE_DISCONNECT -> {
                            Log.i(TAG, "Remote player disconnected")
                            _state.value = NetplayState.DISCONNECTED
                            _session.value = _session.value?.copy(playerCount = 1)
                            _remoteInput.fill(0)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // Normal — no data received this cycle
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Receive error", e)
                    }
                    break
                }
            }
        }
    }

    private fun sendPacket(type: Byte, playerNum: Int, frame: Long, input: IntArray) {
        val addr = peerAddress ?: return
        val port = peerPort
        if (port == 0) return

        val bb = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(MAGIC)
        bb.put(type)
        bb.put(playerNum.toByte())
        bb.putInt(frame.toInt())

        for (i in 0 until 16) {
            bb.put((if (i < input.size) input[i] else 0).toByte())
        }

        val data = bb.array()
        val packet = DatagramPacket(data, data.size, addr, port)
        try {
            socket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }
}
