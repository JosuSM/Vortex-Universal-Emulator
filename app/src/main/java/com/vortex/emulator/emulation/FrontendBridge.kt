package com.vortex.emulator.emulation

import android.content.Context
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delegates all native calls to [VortexNative] (C++), [VortexNativeC] (C),
 * or [VortexNativeRust] (Rust) depending on the user-selected [FrontendType].
 *
 * The selection is persisted in SharedPreferences.
 */
@Singleton
class FrontendBridge @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FrontendBridge"
    }

    private val prefs = context.getSharedPreferences("vortex_frontend_prefs", Context.MODE_PRIVATE)

    var activeFrontend: FrontendType
        get() = FrontendType.fromName(prefs.getString("frontend_type", FrontendType.CPP.name) ?: FrontendType.CPP.name)
        set(value) {
            Log.i(TAG, "Frontend changed to: ${value.name} (${value.displayName})")
            prefs.edit().putString("frontend_type", value.name).apply()
        }

    /* ── Core lifecycle ───────────────────────────────────────── */

    fun loadCore(corePath: String, systemDir: String, saveDir: String): Int {
        val fe = activeFrontend
        Log.i(TAG, "loadCore via ${fe.name} frontend: $corePath")
        return when (fe) {
            FrontendType.CPP  -> VortexNative.loadCore(corePath, systemDir, saveDir)
            FrontendType.C    -> VortexNativeC.loadCore(corePath, systemDir, saveDir)
            FrontendType.RUST -> VortexNativeRust.loadCore(corePath, systemDir, saveDir)
        }
    }

    fun loadGame(romPath: String): Boolean = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.loadGame(romPath)
        FrontendType.C    -> VortexNativeC.loadGame(romPath)
        FrontendType.RUST -> VortexNativeRust.loadGame(romPath)
    }

    fun runFrame() = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.runFrame()
        FrontendType.C    -> VortexNativeC.runFrame()
        FrontendType.RUST -> VortexNativeRust.runFrame()
    }

    fun resetGame() = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.resetGame()
        FrontendType.C    -> VortexNativeC.resetGame()
        FrontendType.RUST -> VortexNativeRust.resetGame()
    }

    fun unloadGame() = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.unloadGame()
        FrontendType.C    -> VortexNativeC.unloadGame()
        FrontendType.RUST -> VortexNativeRust.unloadGame()
    }

    /* ── Video ────────────────────────────────────────────────── */

    fun getFrameBuffer(): IntArray? = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.getFrameBuffer()
        FrontendType.C    -> VortexNativeC.getFrameBuffer()
        FrontendType.RUST -> VortexNativeRust.getFrameBuffer()
    }

    fun getFrameWidth(): Int = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.getFrameWidth()
        FrontendType.C    -> VortexNativeC.getFrameWidth()
        FrontendType.RUST -> VortexNativeRust.getFrameWidth()
    }

    fun getFrameHeight(): Int = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.getFrameHeight()
        FrontendType.C    -> VortexNativeC.getFrameHeight()
        FrontendType.RUST -> VortexNativeRust.getFrameHeight()
    }

    fun isHardwareRendered(): Boolean = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.isHardwareRendered()
        FrontendType.C    -> VortexNativeC.isHardwareRendered()
        FrontendType.RUST -> VortexNativeRust.isHardwareRendered()
    }

    fun isHwContextFailed(): Boolean = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.isHwContextFailed()
        FrontendType.C    -> false // C frontend doesn't have this yet
        FrontendType.RUST -> false // Rust frontend doesn't have this yet
    }

    /* ── Audio ────────────────────────────────────────────────── */

    fun getAudioBuffer(): ShortArray? = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.getAudioBuffer()
        FrontendType.C    -> VortexNativeC.getAudioBuffer()
        FrontendType.RUST -> VortexNativeRust.getAudioBuffer()
    }

    fun getFps(): Double = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.getFps()
        FrontendType.C    -> VortexNativeC.getFps()
        FrontendType.RUST -> VortexNativeRust.getFps()
    }

    fun getSampleRate(): Double = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.getSampleRate()
        FrontendType.C    -> VortexNativeC.getSampleRate()
        FrontendType.RUST -> VortexNativeRust.getSampleRate()
    }

    /* ── Input ────────────────────────────────────────────────── */

    fun setInputState(port: Int, buttonId: Int, value: Int) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.setInputState(port, buttonId, value)
        FrontendType.C    -> VortexNativeC.setInputState(port, buttonId, value)
        FrontendType.RUST -> VortexNativeRust.setInputState(port, buttonId, value)
    }

    fun setAnalogState(port: Int, index: Int, axisId: Int, value: Int) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.setAnalogState(port, index, axisId, value)
        FrontendType.C    -> VortexNativeC.setAnalogState(port, index, axisId, value)
        FrontendType.RUST -> VortexNativeRust.setAnalogState(port, index, axisId, value)
    }

    fun setPointerState(x: Int, y: Int, pressed: Boolean) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.setPointerState(x, y, pressed)
        FrontendType.C    -> VortexNativeC.setPointerState(x, y, pressed)
        FrontendType.RUST -> VortexNativeRust.setPointerState(x, y, pressed)
    }

    /* ── Save states ──────────────────────────────────────────── */

    fun saveState(path: String): Int = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.saveState(path)
        FrontendType.C    -> VortexNativeC.saveState(path)
        FrontendType.RUST -> VortexNativeRust.saveState(path)
    }

    fun loadState(path: String): Int = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.loadState(path)
        FrontendType.C    -> VortexNativeC.loadState(path)
        FrontendType.RUST -> VortexNativeRust.loadState(path)
    }

    fun saveStateToMemory(): ByteArray? = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.saveStateToMemory()
        FrontendType.C    -> VortexNativeC.saveStateToMemory()
        FrontendType.RUST -> VortexNativeRust.saveStateToMemory()
    }

    fun loadStateFromMemory(stateData: ByteArray): Boolean = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.loadStateFromMemory(stateData)
        FrontendType.C    -> VortexNativeC.loadStateFromMemory(stateData)
        FrontendType.RUST -> VortexNativeRust.loadStateFromMemory(stateData)
    }

    fun getSerializeSize(): Long = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.getSerializeSize()
        FrontendType.C    -> VortexNativeC.getSerializeSize()
        FrontendType.RUST -> VortexNativeRust.getSerializeSize()
    }

    /* ── SRAM ─────────────────────────────────────────────────── */

    fun saveSRAM(path: String): Boolean = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.saveSRAM(path)
        FrontendType.C    -> VortexNativeC.saveSRAM(path)
        FrontendType.RUST -> VortexNativeRust.saveSRAM(path)
    }

    fun loadSRAM(path: String): Boolean = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.loadSRAM(path)
        FrontendType.C    -> VortexNativeC.loadSRAM(path)
        FrontendType.RUST -> VortexNativeRust.loadSRAM(path)
    }

    /* ── Options ──────────────────────────────────────────────── */

    fun setCoreOption(key: String, value: String) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.setCoreOption(key, value)
        FrontendType.C    -> VortexNativeC.setCoreOption(key, value)
        FrontendType.RUST -> VortexNativeRust.setCoreOption(key, value)
    }

    fun setFrameSkip(skip: Int) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.setFrameSkip(skip)
        FrontendType.C    -> VortexNativeC.setFrameSkip(skip)
        FrontendType.RUST -> VortexNativeRust.setFrameSkip(skip)
    }

    /** Set display render backend: 0=GL, 1=Vulkan. */
    fun setRenderBackend(backend: Int) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.setRenderBackend(backend)
        FrontendType.C    -> VortexNativeC.setRenderBackend(backend)
        FrontendType.RUST -> VortexNativeRust.setRenderBackend(backend)
    }

    /* ── Surface ──────────────────────────────────────────────── */

    fun setSurface(surface: Surface?) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.setSurface(surface)
        FrontendType.C    -> VortexNativeC.setSurface(surface)
        FrontendType.RUST -> VortexNativeRust.setSurface(surface)
    }

    fun surfaceChanged(width: Int, height: Int) = when (activeFrontend) {
        FrontendType.CPP  -> VortexNative.surfaceChanged(width, height)
        FrontendType.C    -> VortexNativeC.surfaceChanged(width, height)
        FrontendType.RUST -> VortexNativeRust.surfaceChanged(width, height)
    }
}
