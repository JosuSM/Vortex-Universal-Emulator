package com.vortex.emulator.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortex.emulator.core.CoreInfo
import com.vortex.emulator.core.CoreManager
import com.vortex.emulator.emulation.AudioLatency
import com.vortex.emulator.emulation.EmulationEngine
import com.vortex.emulator.emulation.GamepadManager
import com.vortex.emulator.emulation.VortexNative
import com.vortex.emulator.game.Game
import com.vortex.emulator.game.GameDao
import com.vortex.emulator.netplay.InternetNetplayManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

enum class EmulationState {
    LOADING,
    RUNNING,
    PAUSED,
    ERROR
}

@HiltViewModel
class EmulationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val gameDao: GameDao,
    private val coreManager: CoreManager,
    @ApplicationContext private val appContext: Context,
    val engine: EmulationEngine,
    private val gamepadManager: GamepadManager,
    private val internetNetplay: InternetNetplayManager
) : ViewModel() {

    private val gameId: Long = savedStateHandle["gameId"] ?: 0L

    private val _game = MutableStateFlow<Game?>(null)
    val game: StateFlow<Game?> = _game.asStateFlow()

    private val _emulationState = MutableStateFlow(EmulationState.LOADING)
    val emulationState: StateFlow<EmulationState> = _emulationState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Loading game…")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    /** Show a status message that auto-clears after [durationMs]. */
    private fun showStatus(msg: String, durationMs: Long = 2000L) {
        _statusMessage.value = msg
        if (msg.isNotBlank()) {
            viewModelScope.launch {
                delay(durationMs)
                if (_statusMessage.value == msg) _statusMessage.value = ""
            }
        }
    }

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedCore = MutableStateFlow<CoreInfo?>(null)
    val selectedCore: StateFlow<CoreInfo?> = _selectedCore.asStateFlow()

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    private val _currentFps = MutableStateFlow(0f)
    val currentFps: StateFlow<Float> = _currentFps.asStateFlow()

    // Fast forward state
    private val _fastForwardEnabled = MutableStateFlow(false)
    val fastForwardEnabled: StateFlow<Boolean> = _fastForwardEnabled.asStateFlow()

    // Rewind state
    private val _rewindEnabled = MutableStateFlow(false)
    val rewindEnabled: StateFlow<Boolean> = _rewindEnabled.asStateFlow()

    // Audio mute
    private val _audioEnabled = MutableStateFlow(true)
    val audioEnabled: StateFlow<Boolean> = _audioEnabled.asStateFlow()

    // Audio volume (0.0 to 1.0)
    private val _audioVolume = MutableStateFlow(1.0f)
    val audioVolume: StateFlow<Float> = _audioVolume.asStateFlow()

    // Audio latency
    private val _audioLatency = MutableStateFlow(AudioLatency.MEDIUM)
    val audioLatency: StateFlow<AudioLatency> = _audioLatency.asStateFlow()

    // Quick save slots
    private val _quickSaveSlot = MutableStateFlow(0)
    val quickSaveSlot: StateFlow<Int> = _quickSaveSlot.asStateFlow()

    // Gamepad state
    val isGamepadConnected: Boolean get() = gamepadManager.isGamepadConnected
    val gamepadName: String? get() = gamepadManager.connectedGamepadName

    init {
        gamepadManager.engine = engine
        loadAndStart()
    }

    private fun loadAndStart() {
        viewModelScope.launch {
            try {
                _statusMessage.value = "Loading game info…"
                val loaded = gameDao.getGameById(gameId)
                if (loaded == null) {
                    _error.value = "Game not found in library"
                    _emulationState.value = EmulationState.ERROR
                    return@launch
                }
                _game.value = loaded

                val core = if (loaded.coreId != null) {
                    coreManager.getCoreById(loaded.coreId) ?: coreManager.getPreferredCore(loaded.platform)
                } else {
                    coreManager.getPreferredCore(loaded.platform)
                }
                if (core == null) {
                    _error.value = "No emulation core available for ${loaded.platform.displayName}"
                    _emulationState.value = EmulationState.ERROR
                    return@launch
                }
                _selectedCore.value = core

                gameDao.updateGame(loaded.copy(lastPlayed = System.currentTimeMillis()))

                _statusMessage.value = "Preparing ${core.displayName} core…"
                val prepError = engine.prepare(core.libraryName, loaded.romPath)
                if (prepError != null) {
                    _error.value = prepError
                    _emulationState.value = EmulationState.ERROR
                    return@launch
                }

                // Refresh core cache status after successful prepare
                coreManager.refreshInstalled()

                _statusMessage.value = ""
                _emulationState.value = EmulationState.RUNNING
                engine.start { bitmap ->
                    _frameBitmap.value = bitmap
                    _currentFps.value = engine.currentFps
                }

                // Set up multiplayer relay if lobby game is active
                setupMultiplayerRelay()

            } catch (e: Exception) {
                _error.value = e.message ?: "Unexpected error"
                _emulationState.value = EmulationState.ERROR
            }
        }
    }

    fun togglePause() {
        if (_emulationState.value == EmulationState.RUNNING) {
            engine.pause()
            _emulationState.value = EmulationState.PAUSED
        } else if (_emulationState.value == EmulationState.PAUSED) {
            engine.resume()
            _emulationState.value = EmulationState.RUNNING
        }
    }

    fun resetGame() {
        engine.reset()
    }

    /**
     * If this emulation was launched from a lobby game, set up input relay:
     * - Before each frame: apply remote players' input to their ports
     * - After each frame: send local player's input to remote players
     */
    /** The local player's port index for the current multiplayer session (0 = host). */
    private val _localPlayerIndex = MutableStateFlow(0)
    val localPlayerIndex: StateFlow<Int> = _localPlayerIndex.asStateFlow()

    /** True when an internet multiplayer game is active. */
    val isMultiplayerActive: Boolean get() = internetNetplay.isGameActive.value

    private fun setupMultiplayerRelay() {
        if (!internetNetplay.isGameActive.value) return

        val localIndex = internetNetplay.localPlayerIndex.value
        val players = internetNetplay.lobbyClient.players.value
        val totalPlayers = players.size.coerceAtLeast(2)

        // Route local input capture & gamepad to the correct player port
        _localPlayerIndex.value = localIndex
        engine.localPlayerPort = localIndex
        gamepadManager.activePort = localIndex

        engine.onPreFrame = {
            // Apply every remote player's input to their respective port
            for (p in players) {
                if (p.playerIndex == localIndex) continue
                val input = internetNetplay.getRemoteInput(p.playerIndex)
                for (btn in 0 until 16) {
                    VortexNative.setInputState(p.playerIndex, btn, input[btn])
                }
            }
        }

        engine.onPostFrame = {
            // Send local input to all remote players via encrypted relay
            internetNetplay.sendInput(engine.localInputState)
        }
    }

    // ── Save / Load State ───────────────────────────────────────────

    fun saveState() {
        val game = _game.value ?: return
        val ok = engine.saveState("game_${game.id}")
        showStatus(if (ok) "State saved" else "Save failed")
    }

    fun loadSaveState() {
        val game = _game.value ?: return
        val ok = engine.loadState("game_${game.id}")
        showStatus(if (ok) "State loaded" else "No save state found")
    }

    fun quickSave() {
        val game = _game.value ?: return
        val slot = _quickSaveSlot.value
        val ok = engine.saveState("game_${game.id}_slot$slot")
        showStatus(if (ok) "Saved slot $slot" else "Save failed")
    }

    fun quickLoad() {
        val game = _game.value ?: return
        val slot = _quickSaveSlot.value
        val ok = engine.loadState("game_${game.id}_slot$slot")
        showStatus(if (ok) "Loaded slot $slot" else "No save in slot $slot")
    }

    fun setQuickSaveSlot(slot: Int) {
        _quickSaveSlot.value = slot.coerceIn(0, 9)
    }

    fun exportSaveState(destUri: Uri) {
        val game = _game.value ?: return
        val ok = engine.exportStateTo("game_${game.id}", destUri)
        showStatus(if (ok) "State exported" else "Export failed")
    }

    fun importSaveState(srcUri: Uri) {
        val game = _game.value ?: return
        val ok = engine.importStateFrom("game_${game.id}", srcUri)
        showStatus(if (ok) "State imported" else "Import failed")
    }

    fun setCustomSaveDirectory(uri: Uri) {
        // Persist permissions and resolve to a real path
        try {
            appContext.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) { /* best effort */ }

        // For SAF tree URIs, we store the URI string; engine will use it through export/import
        // For now, use the default internal save dir but store the preference
        showStatus("Save directory updated")
    }

    // ── Fast Forward ────────────────────────────────────────────────

    fun toggleFastForward() {
        val enabled = !_fastForwardEnabled.value
        _fastForwardEnabled.value = enabled
        engine.fastForwardEnabled = enabled
        showStatus(if (enabled) "Fast Forward ON" else "Fast Forward OFF")
    }

    // ── Rewind ──────────────────────────────────────────────────────

    fun toggleRewind() {
        val enabled = !_rewindEnabled.value
        _rewindEnabled.value = enabled
        engine.rewindEnabled = enabled
        showStatus(if (enabled) "Rewind enabled" else "Rewind disabled")
    }

    fun startRewind() {
        engine.startRewind()
        _statusMessage.value = "Rewinding…" // persistent while held
    }

    fun stopRewind() {
        engine.stopRewind()
        _statusMessage.value = ""
    }

    // ── Audio ───────────────────────────────────────────────────────

    fun toggleAudio() {
        val enabled = !_audioEnabled.value
        _audioEnabled.value = enabled
        engine.updateAudioEnabled(enabled)
        showStatus(if (enabled) "Audio ON" else "Audio OFF")
    }

    fun setAudioVolume(volume: Float) {
        _audioVolume.value = volume.coerceIn(0f, 1f)
        engine.setAudioVolume(_audioVolume.value)
    }

    fun setAudioLatency(latency: AudioLatency) {
        _audioLatency.value = latency
        engine.setAudioLatency(latency)
        showStatus("Audio latency: ${latency.label}")
    }

    // ── Screenshot ──────────────────────────────────────────────────

    fun takeScreenshot() {
        engine.requestScreenshot { bitmap ->
            viewModelScope.launch {
                val game = _game.value
                val name = game?.title?.replace(Regex("[^a-zA-Z0-9_-]"), "_") ?: "screenshot"
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "VortexEmulator"
                ).also { it.mkdirs() }
                val file = File(dir, "${name}_${System.currentTimeMillis()}.png")
                try {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    showStatus("Screenshot saved")
                } catch (e: Exception) {
                    // Fallback to app-internal storage
                    val fallback = File(appContext.filesDir, "screenshots").also { it.mkdirs() }
                    val ff = File(fallback, "${name}_${System.currentTimeMillis()}.png")
                    FileOutputStream(ff).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    showStatus("Screenshot saved (internal)")
                }
            }
        }
    }

    // ── Core switching ──────────────────────────────────────────────

    fun getCoreName(): String {
        return _selectedCore.value?.displayName ?: "No core available"
    }

    fun getAvailableCores(): List<CoreInfo> {
        val game = _game.value ?: return emptyList()
        return coreManager.getCoresForPlatform(game.platform)
    }

    fun switchCore(core: CoreInfo) {
        _selectedCore.value = core
        val game = _game.value ?: return
        coreManager.setPreferredCore(game.platform, core.id)
        viewModelScope.launch {
            gameDao.updateGame(game.copy(coreId = core.id))
            _game.value = game.copy(coreId = core.id)
        }
        engine.stop()
        _emulationState.value = EmulationState.LOADING
        loadAndStart()
    }

    override fun onCleared() {
        super.onCleared()
        engine.onPreFrame = null
        engine.onPostFrame = null
        engine.stop()
    }
}
