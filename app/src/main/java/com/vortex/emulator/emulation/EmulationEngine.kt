package com.vortex.emulator.emulation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
            ?: return@withContext "Failed to download core '$libraryName'. Check your internet connection."

        val romPath = resolveRomPath(romUri)
            ?: return@withContext "Cannot access ROM file. It may have been moved or deleted."

        val result = VortexNative.loadCore(
            corePath,
            coreDownloader.systemDir,
            effectiveSaveDir
        )
        if (result != 0) {
            return@withContext "Failed to load emulation core (error $result)"
        }

        val loaded = VortexNative.loadGame(romPath)
        if (!loaded) {
            return@withContext "Failed to load ROM. The file may be corrupted or unsupported."
        }

        Log.i(TAG, "Ready: core=$libraryName, rom=$romPath, fps=${VortexNative.getFps()}")
        null
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
                    VortexNative.runFrame()
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

                // FPS counting
                frameCount++
                val now = System.nanoTime()
                if (now - fpsTimer >= 1_000_000_000L) {
                    currentFps = frameCount.toFloat()
                    frameCount = 0
                    fpsTimer = now
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
        emulationThread?.join(2000)
        emulationThread = null
        VortexNative.unloadGame()
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

    fun setButton(port: Int, buttonId: Int, pressed: Boolean) {
        VortexNative.setInputState(port, buttonId, if (pressed) 1 else 0)
    }

    fun setAnalog(port: Int, index: Int, axisId: Int, value: Int) {
        VortexNative.setAnalogState(port, index, axisId, value)
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

    // ── ROM path resolution ─────────────────────────────────────────

    private fun resolveRomPath(romUri: String): String? {
        return try {
            if (romUri.startsWith("content://")) {
                val uri = Uri.parse(romUri)
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "rom.bin"
                val tmpDir = File(context.cacheDir, "roms").also { it.mkdirs() }
                val tmpFile = File(tmpDir, fileName)

                if (!tmpFile.exists()) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    } ?: return null
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
}
