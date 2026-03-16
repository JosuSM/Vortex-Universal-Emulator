package com.vortex.emulator.emulation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EmulationEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coreDownloader: CoreDownloader
) {
    companion object {
        private const val TAG = "EmulationEngine"
        private const val REWIND_BUFFER_SIZE = 120 // ~2 seconds at 60fps
    }

    private val audioPlayer = AudioPlayer()

    @Volatile var isRunning = false; private set
    @Volatile var isPaused = false
    @Volatile var currentFps = 0f; private set
    @Volatile var fastForwardEnabled = false
    @Volatile var rewindEnabled = false
    @Volatile var audioEnabled = true
    @Volatile var autoFrameSkip = true   // Auto-adjust frame skip for performance
    @Volatile var manualFrameSkip = 0    // Manual frame skip level (0 = auto/off)

    /** Called each frame before runFrame(). Used to apply remote inputs in multiplayer. */
    @Volatile var onPreFrame: (() -> Unit)? = null
    /** Called each frame after runFrame(). Used to send local input in multiplayer. */
    @Volatile var onPostFrame: (() -> Unit)? = null

    private var emulationThread: Thread? = null

    // Rewind buffer: circular list of serialized states
    private val rewindBuffer = ArrayDeque<ByteArray>(REWIND_BUFFER_SIZE)
    private var rewindActive = false

    // Screenshot callback
    private var screenshotCallback: ((Bitmap) -> Unit)? = null

    // Custom save directory (null = default)
    var customSaveDir: String? = null

    private val effectiveSaveDir: String
        get() = customSaveDir ?: coreDownloader.saveDir

    suspend fun prepare(libraryName: String, romUri: String): String? = withContext(Dispatchers.IO) {
        val corePath = coreDownloader.ensureCore(libraryName)
            ?: return@withContext when (coreDownloader.lastError) {
                "offline" -> "Core '$libraryName' is not downloaded. Connect to the internet to download it, or use an already cached core."
                else -> "Failed to download core '$libraryName'. Check your internet connection."
            }

        val romPath = resolveRomPath(romUri)
            ?: return@withContext "Cannot access ROM file. It may have been moved or deleted."

        Log.i(TAG, "Resolved ROM path: $romPath (${File(romPath).length()} bytes)")

        val result = VortexNative.loadCore(
            corePath,
            coreDownloader.systemDir,
            effectiveSaveDir
        )
        if (result != 0) {
            return@withContext "Failed to load emulation core (error $result)"
        }

        // Set performance-friendly defaults for demanding cores before loading game
        applyPerformanceDefaults(libraryName)

        val loaded = VortexNative.loadGame(romPath)
        if (!loaded) {
            // If failed, try invalidating cached ROM and re-resolve
            Log.w(TAG, "First loadGame attempt failed, invalidating ROM cache...")
            invalidateRomCache(romUri)
            val freshRomPath = resolveRomPath(romUri)
            if (freshRomPath != null) {
                Log.i(TAG, "Retrying with fresh ROM: $freshRomPath (${File(freshRomPath).length()} bytes)")
                val retryLoaded = VortexNative.loadGame(freshRomPath)
                if (retryLoaded) {
                    Log.i(TAG, "Ready (after retry): core=$libraryName, rom=$freshRomPath, fps=${VortexNative.getFps()}")
                    return@withContext null
                }
            }
            return@withContext "Failed to load ROM. The file may be corrupted or unsupported."
        }

        Log.i(TAG, "Ready: core=$libraryName, rom=$romPath, fps=${VortexNative.getFps()}")
        null
    }

    /** Apply performance-friendly core option defaults for demanding platforms. */
    private fun applyPerformanceDefaults(libraryName: String) {
        when {
            libraryName.startsWith("mupen64plus") -> {
                VortexNative.setCoreOption("mupen64plus-cpucore", "dynamic_recompiler")
                VortexNative.setCoreOption("mupen64plus-EnableFBEmulation", "False")
                VortexNative.setCoreOption("mupen64plus-EnableCopyColorToRDRAM", "Off")
                VortexNative.setCoreOption("mupen64plus-EnableCopyDepthToRDRAM", "Off")
                VortexNative.setCoreOption("mupen64plus-EnableN64DepthCompare", "False")
                VortexNative.setCoreOption("mupen64plus-FrameDuping", "True")
                VortexNative.setCoreOption("mupen64plus-ThreadedRenderer", "True")
                VortexNative.setCoreOption("mupen64plus-Framerate", "Original")
                Log.i(TAG, "Applied N64 performance defaults")
            }
            libraryName == "ppsspp" -> {
                VortexNative.setCoreOption("ppsspp_internal_resolution", "1x")
                VortexNative.setCoreOption("ppsspp_frameskip", "1")
                Log.i(TAG, "Applied PSP performance defaults")
            }
            libraryName == "flycast" -> {
                VortexNative.setCoreOption("flycast_internal_resolution", "640x480")
                Log.i(TAG, "Applied Dreamcast performance defaults")
            }
        }
    }

    /** Remove cached ROM file so next resolveRomPath forces a fresh copy. */
    private fun invalidateRomCache(romUri: String) {
        if (romUri.startsWith("content://")) {
            val uri = Uri.parse(romUri)
            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "rom.bin"
            val tmpFile = File(File(context.cacheDir, "roms"), fileName)
            if (tmpFile.exists()) {
                tmpFile.delete()
                Log.i(TAG, "Invalidated cached ROM: ${tmpFile.name}")
            }
        }
    }

    fun start(onFrame: (Bitmap?) -> Unit) {
        if (isRunning) return
        isRunning = true
        isPaused = false
        rewindBuffer.clear()

        val targetFps = VortexNative.getFps().let { if (it > 0) it else 60.0 }
        val sampleRate = VortexNative.getSampleRate().let { if (it > 0) it else 44100.0 }
        val frameTimeNs = (1_000_000_000.0 / targetFps).toLong()

        audioPlayer.start(sampleRate.toInt())

        emulationThread = Thread({
            Log.i(TAG, "Emulation loop started: ${targetFps}fps, frameTime=${frameTimeNs}ns")
            var frameCount = 0L
            var fpsTimer = System.nanoTime()
            var autoSkipLevel = 0
            var slowFrames = 0

            // Apply manual frame skip if set
            if (manualFrameSkip > 0) {
                VortexNative.setFrameSkip(manualFrameSkip)
            }

            while (isRunning) {
                if (isPaused) {
                    Thread.sleep(50)
                    continue
                }

                val frameStart = System.nanoTime()

                // Handle rewind (step backward through saved states)
                if (rewindActive && rewindBuffer.isNotEmpty()) {
                    val state = rewindBuffer.removeLast()
                    VortexNative.loadStateFromMemory(state)
                } else {
                    // Save rewind state if enabled
                    if (rewindEnabled) {
                        val state = VortexNative.saveStateToMemory()
                        if (state != null) {
                            if (rewindBuffer.size >= REWIND_BUFFER_SIZE) {
                                rewindBuffer.removeFirst()
                            }
                            rewindBuffer.addLast(state)
                        }
                    }

                    // Run one frame
                    onPreFrame?.invoke()
                    VortexNative.runFrame()
                    onPostFrame?.invoke()
                }

                // Get video frame
                val pixels = VortexNative.getFrameBuffer()
                val w = VortexNative.getFrameWidth()
                val h = VortexNative.getFrameHeight()

                if (pixels != null && w > 0 && h > 0) {
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                    onFrame(bitmap)

                    // Handle screenshot request
                    screenshotCallback?.let { cb ->
                        screenshotCallback = null
                        cb(bitmap.copy(Bitmap.Config.ARGB_8888, false))
                    }
                }

                // Push audio (skip during rewind or if audio disabled)
                if (audioEnabled && !rewindActive) {
                    val audio = VortexNative.getAudioBuffer()
                    if (audio != null && audio.isNotEmpty()) {
                        audioPlayer.writeSamples(audio)
                    }
                }

                // FPS counting + auto frame skip
                frameCount++
                val now = System.nanoTime()
                if (now - fpsTimer >= 1_000_000_000L) {
                    currentFps = frameCount.toFloat()
                    frameCount = 0
                    fpsTimer = now

                    // Auto frame skip: if FPS drops below 80% of target, increase skip
                    if (autoFrameSkip && manualFrameSkip == 0) {
                        val threshold = (targetFps * 0.8).toFloat()
                        if (currentFps < threshold) {
                            slowFrames++
                            if (slowFrames >= 2 && autoSkipLevel < 2) {
                                autoSkipLevel++
                                VortexNative.setFrameSkip(autoSkipLevel)
                                Log.i(TAG, "Auto frame skip → $autoSkipLevel (fps=$currentFps)")
                            }
                        } else {
                            slowFrames = 0
                            if (autoSkipLevel > 0 && currentFps >= targetFps.toFloat() * 0.95f) {
                                autoSkipLevel--
                                VortexNative.setFrameSkip(autoSkipLevel)
                                Log.i(TAG, "Auto frame skip → $autoSkipLevel (fps=$currentFps)")
                            }
                        }
                    }
                }

                // Frame pacing (skip during fast-forward)
                if (!fastForwardEnabled) {
                    val elapsed = System.nanoTime() - frameStart
                    val sleepNs = frameTimeNs - elapsed
                    if (sleepNs > 0) {
                        Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                    }
                }
            }

            audioPlayer.stop()
            Log.i(TAG, "Emulation loop stopped")
        }, "VortexEmu").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    fun reset() { VortexNative.resetGame() }

    fun stop() {
        isRunning = false
        val t = emulationThread
        emulationThread = null
        t?.join(5000) // longer timeout for heavy cores like N64
        if (t?.isAlive == true) {
            Log.w(TAG, "Emulation thread still alive after timeout – interrupting")
            t.interrupt()
            t.join(1000)
        }
        try {
            VortexNative.unloadGame()
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading game", e)
        }
        rewindBuffer.clear()
    }

    // ── Save / Load States ──────────────────────────────────────────

    fun saveState(slotName: String): Boolean {
        val path = File(effectiveSaveDir, "$slotName.state").absolutePath
        return VortexNative.saveState(path) == 0
    }

    fun loadState(slotName: String): Boolean {
        val path = File(effectiveSaveDir, "$slotName.state").absolutePath
        return if (File(path).exists()) VortexNative.loadState(path) == 0 else false
    }

    fun saveStateToPath(absolutePath: String): Boolean {
        return VortexNative.saveState(absolutePath) == 0
    }

    fun loadStateFromPath(absolutePath: String): Boolean {
        return if (File(absolutePath).exists()) VortexNative.loadState(absolutePath) == 0 else false
    }

    fun exportStateTo(slotName: String, destUri: Uri): Boolean {
        val src = File(effectiveSaveDir, "$slotName.state")
        if (!src.exists()) return false
        return try {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export state failed", e)
            false
        }
    }

    fun importStateFrom(slotName: String, srcUri: Uri): Boolean {
        return try {
            val dest = File(effectiveSaveDir, "$slotName.state")
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Import state failed", e)
            false
        }
    }

    // ── Input ───────────────────────────────────────────────────────

    /** Tracks local player's input state for multiplayer relay. */
    val localInputState = IntArray(16)

    /** Which port belongs to the local player (set by multiplayer relay). */
    @Volatile var localPlayerPort: Int = 0

    fun setButton(port: Int, buttonId: Int, pressed: Boolean) {
        val value = if (pressed) 1 else 0
        if (port == localPlayerPort && buttonId in localInputState.indices) {
            localInputState[buttonId] = value
        }
        VortexNative.setInputState(port, buttonId, value)
    }

    fun setAnalog(port: Int, index: Int, axisId: Int, value: Int) {
        VortexNative.setAnalogState(port, index, axisId, value)
    }

    fun setPointer(x: Int, y: Int, pressed: Boolean) {
        VortexNative.setPointerState(x, y, pressed)
    }

    /** Whether the current core is using hardware-accelerated rendering. */
    fun isHardwareRendered(): Boolean = VortexNative.isHardwareRendered()

    // ── SRAM (battery save) ─────────────────────────────────────────

    fun saveSRAM(gameName: String): Boolean {
        val path = File(effectiveSaveDir, "$gameName.srm").absolutePath
        return VortexNative.saveSRAM(path)
    }

    fun loadSRAM(gameName: String): Boolean {
        val path = File(effectiveSaveDir, "$gameName.srm").absolutePath
        return if (File(path).exists()) VortexNative.loadSRAM(path) else false
    }

    // ── Rewind ──────────────────────────────────────────────────────

    fun startRewind() { rewindActive = true }
    fun stopRewind() { rewindActive = false }

    // ── Screenshot ──────────────────────────────────────────────────

    fun requestScreenshot(callback: (Bitmap) -> Unit) {
        screenshotCallback = callback
    }

    fun takeScreenshot(): Bitmap? {
        val pixels = VortexNative.getFrameBuffer() ?: return null
        val w = VortexNative.getFrameWidth()
        val h = VortexNative.getFrameHeight()
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        return bitmap
    }

    // ── Audio ───────────────────────────────────────────────────────

    fun updateAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
    }

    fun setAudioVolume(volume: Float) {
        audioPlayer.volume = volume
    }

    fun setAudioLatency(latency: AudioLatency) {
        audioPlayer.latency = latency
        if (isRunning) {
            audioPlayer.restart()
        }
    }

    fun getAudioVolume(): Float = audioPlayer.volume
    fun getAudioLatency(): AudioLatency = audioPlayer.latency

    fun setFrameSkip(level: Int) {
        manualFrameSkip = level.coerceIn(0, 4)
        VortexNative.setFrameSkip(manualFrameSkip)
    }

    // ── ROM path resolution ─────────────────────────────────────────

    private fun resolveRomPath(romUri: String): String? {
        return try {
            if (romUri.startsWith("content://")) {
                val uri = Uri.parse(romUri)
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "rom.bin"
                val tmpDir = File(context.cacheDir, "roms").also { it.mkdirs() }
                val tmpFile = File(tmpDir, fileName)

                if (!tmpFile.exists() || tmpFile.length() == 0L) {
                    tmpFile.delete()
                    val stream = openContentStream(uri)
                    if (stream == null) {
                        Log.e(TAG, "Cannot open ROM — grant file access or re-scan: $romUri")
                        return null
                    }
                    stream.use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmpFile.length() == 0L) {
                        Log.e(TAG, "Copied ROM file is empty: $romUri")
                        tmpFile.delete()
                        return null
                    }
                }
                tmpFile.absolutePath
            } else {
                val f = File(romUri)
                if (f.exists()) f.absolutePath else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve ROM: $romUri", e)
            null
        }
    }

    /**
     * Try multiple strategies to open the content URI:
     * 1. Direct SAF openInputStream (works when URI permission is active)
     * 2. Rebuild URI via persisted tree permissions
     * 3. Extract real file path (works with MANAGE_EXTERNAL_STORAGE)
     */
    private fun openContentStream(uri: Uri): InputStream? {
        // Strategy 1: Direct SAF access
        try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream != null) return stream
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct SAF access denied, trying fallbacks...")
        }

        // Strategy 2: Rebuild document URI via persisted tree permissions
        try {
            val docId = DocumentsContract.getDocumentId(uri)
            for (perm in context.contentResolver.persistedUriPermissions) {
                if (!perm.isReadPermission) continue
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(perm.uri)
                    if (docId.startsWith(treeDocId)) {
                        val rebuilt = DocumentsContract.buildDocumentUriUsingTree(perm.uri, docId)
                        val stream = context.contentResolver.openInputStream(rebuilt)
                        if (stream != null) return stream
                    }
                } catch (_: Exception) { /* try next */ }
            }
        } catch (_: Exception) { /* fall through */ }

        // Strategy 3: Extract real file path and read directly
        // Works when MANAGE_EXTERNAL_STORAGE is granted
        val filePath = extractFilePathFromUri(uri)
        if (filePath != null) {
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                Log.i(TAG, "Opened ROM via file path fallback: $filePath")
                return file.inputStream()
            }
        }

        return null
    }

    /**
     * Extract the real file system path from a SAF document URI.
     * E.g. "content://.../tree/primary:ROMS/document/primary:ROMS/n64/game.n64"
     *   -> "/storage/emulated/0/ROMS/n64/game.n64"
     */
    private fun extractFilePathFromUri(uri: Uri): String? {
        return try {
            val docId = try {
                DocumentsContract.getDocumentId(uri)
            } catch (_: Exception) {
                uri.pathSegments.getOrNull(3)
            } ?: return null

            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return null

            val volumeId = parts[0]
            val relativePath = parts[1]

            if (volumeId == "primary") {
                File(Environment.getExternalStorageDirectory(), relativePath).absolutePath
            } else {
                File("/storage/$volumeId", relativePath).absolutePath
            }
        } catch (_: Exception) { null }
    }
}
