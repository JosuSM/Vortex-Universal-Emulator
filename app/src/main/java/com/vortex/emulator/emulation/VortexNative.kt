package com.vortex.emulator.emulation

/**
 * JNI bridge to the native libretro frontend (libvortex_frontend.so).
 */
object VortexNative {
    init {
        System.loadLibrary("vortex_frontend")
    }

    /** Load a libretro core .so file. Returns 0 on success, negative on error. */
    external fun loadCore(corePath: String, systemDir: String, saveDir: String): Int

    /** Load a ROM file into the currently loaded core. */
    external fun loadGame(romPath: String): Boolean

    /** Run one emulation frame (call at core FPS rate). */
    external fun runFrame()

    /** Get the last rendered frame as XRGB8888 pixel array. Null if no frame ready. */
    external fun getFrameBuffer(): IntArray?

    /** Width of the current frame buffer. */
    external fun getFrameWidth(): Int

    /** Height of the current frame buffer. */
    external fun getFrameHeight(): Int

    /** Get audio samples produced by the last frame (interleaved stereo int16). */
    external fun getAudioBuffer(): ShortArray?

    /** Core-reported FPS. */
    external fun getFps(): Double

    /** Core-reported audio sample rate. */
    external fun getSampleRate(): Double

    /** Set a joypad button state (0/1) for a given port. */
    external fun setInputState(port: Int, buttonId: Int, value: Int)

    /** Set an analog axis value (-32768..32767). */
    external fun setAnalogState(port: Int, index: Int, axisId: Int, value: Int)

    /** Reset the loaded game. */
    external fun resetGame()

    /** Unload the current game and core, freeing all resources. */
    external fun unloadGame()

    /** Save state to file. Returns 0 on success. */
    external fun saveState(path: String): Int

    /** Load state from file. Returns 0 on success. */
    external fun loadState(path: String): Int

    /** Save state to memory (for rewind). Returns serialized bytes or null. */
    external fun saveStateToMemory(): ByteArray?

    /** Load state from memory (for rewind). Returns true on success. */
    external fun loadStateFromMemory(stateData: ByteArray): Boolean

    /** Get the serialization size for the current core/game. */
    external fun getSerializeSize(): Long

    /** Returns true if the current core uses hardware-accelerated (OpenGL) rendering. */
    external fun isHardwareRendered(): Boolean

    /** Set a core option key/value pair. */
    external fun setCoreOption(key: String, value: String)

    /** Set pointer/touch state for NDS-like touch screens. x/y in libretro coords (-0x7FFF..0x7FFF). */
    external fun setPointerState(x: Int, y: Int, pressed: Boolean)

    /** Save SRAM (battery save) to file. */
    external fun saveSRAM(path: String): Boolean

    /** Load SRAM (battery save) from file. */
    external fun loadSRAM(path: String): Boolean

    /** Set frame skip level (0 = off, 1 = every other, 2 = skip 2 of 3, etc.). */
    external fun setFrameSkip(skip: Int)

    /* Libretro button ID constants (matching libretro.h) */
    const val RETRO_DEVICE_ID_JOYPAD_B      = 0
    const val RETRO_DEVICE_ID_JOYPAD_Y      = 1
    const val RETRO_DEVICE_ID_JOYPAD_SELECT = 2
    const val RETRO_DEVICE_ID_JOYPAD_START  = 3
    const val RETRO_DEVICE_ID_JOYPAD_UP     = 4
    const val RETRO_DEVICE_ID_JOYPAD_DOWN   = 5
    const val RETRO_DEVICE_ID_JOYPAD_LEFT   = 6
    const val RETRO_DEVICE_ID_JOYPAD_RIGHT  = 7
    const val RETRO_DEVICE_ID_JOYPAD_A      = 8
    const val RETRO_DEVICE_ID_JOYPAD_X      = 9
    const val RETRO_DEVICE_ID_JOYPAD_L      = 10
    const val RETRO_DEVICE_ID_JOYPAD_R      = 11
    const val RETRO_DEVICE_ID_JOYPAD_L2     = 12
    const val RETRO_DEVICE_ID_JOYPAD_R2     = 13
    const val RETRO_DEVICE_ID_JOYPAD_L3     = 14
    const val RETRO_DEVICE_ID_JOYPAD_R3     = 15
}
