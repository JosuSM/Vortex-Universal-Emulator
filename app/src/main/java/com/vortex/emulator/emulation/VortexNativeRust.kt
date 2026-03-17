package com.vortex.emulator.emulation

/**
 * JNI bridge to the Rust libretro frontend (libvortex_frontend_rust.so).
 *
 * GPU-adaptive frontend with runtime vendor detection (Mali/Adreno/PowerVR),
 * lock-free audio ring buffer, and memory-safe libretro environment handling.
 */
object VortexNativeRust {
    init {
        System.loadLibrary("vortex_frontend_rust")
    }

    external fun loadCore(corePath: String, systemDir: String, saveDir: String): Int
    external fun loadGame(romPath: String): Boolean
    external fun runFrame()
    external fun getFrameBuffer(): IntArray?
    external fun getFrameWidth(): Int
    external fun getFrameHeight(): Int
    external fun getAudioBuffer(): ShortArray?
    external fun getFps(): Double
    external fun getSampleRate(): Double
    external fun setInputState(port: Int, buttonId: Int, value: Int)
    external fun setAnalogState(port: Int, index: Int, axisId: Int, value: Int)
    external fun resetGame()
    external fun unloadGame()
    external fun saveState(path: String): Int
    external fun loadState(path: String): Int
    external fun saveStateToMemory(): ByteArray?
    external fun loadStateFromMemory(stateData: ByteArray): Boolean
    external fun getSerializeSize(): Long
    external fun isHardwareRendered(): Boolean
    external fun setCoreOption(key: String, value: String)
    external fun setPointerState(x: Int, y: Int, pressed: Boolean)
    external fun saveSRAM(path: String): Boolean
    external fun loadSRAM(path: String): Boolean
    external fun setFrameSkip(skip: Int)
    external fun setRenderBackend(backend: Int)
    external fun setSurface(surface: android.view.Surface?)
    external fun surfaceChanged(width: Int, height: Int)

    // ── Lemuroid Core Catalog & Presets ──────────────────
    /** Returns JSON array of the curated Lemuroid-compatible core catalog. */
    external fun getLemuroidCatalog(): String

    /** Returns the default core library_name for a platform (e.g. "NES" → "fceumm"). */
    external fun getDefaultCoreForPlatform(platform: String): String?

    /** Returns JSON object of preset options for a core library_name. */
    external fun getCorePreset(libraryName: String): String

    /** Detects platform from ROM file extension (e.g. "nes" → "NES"). */
    external fun detectPlatform(extension: String): String?

    /** Returns the libretro buildbot download URL for a core + ABI. */
    external fun getBuildbotUrl(libraryName: String, abi: String): String
}
