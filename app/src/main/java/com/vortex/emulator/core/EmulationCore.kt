package com.vortex.emulator.core

/**
 * Abstract interface for emulation cores.
 * Implementations wrap native libretro-compatible cores.
 */
interface EmulationCore {

    /** Core metadata */
    val info: CoreInfo

    /** Initialize the core with a ROM path */
    fun loadRom(romPath: String): Boolean

    /** Start emulation loop */
    fun start()

    /** Pause emulation */
    fun pause()

    /** Resume emulation */
    fun resume()

    /** Stop emulation and release resources */
    fun stop()

    /** Reset the emulated system */
    fun reset()

    /** Save state to a slot (0-9) */
    fun saveState(slot: Int): Boolean

    /** Load state from a slot (0-9) */
    fun loadState(slot: Int): Boolean

    /** Set emulation speed multiplier (1.0 = normal) */
    fun setSpeed(multiplier: Float)

    /** Enable/disable rewind buffer */
    fun setRewindEnabled(enabled: Boolean)

    /** Step backwards one frame (if rewind enabled) */
    fun rewindStep()

    /** Send button input */
    fun sendInput(port: Int, button: Int, pressed: Boolean)

    /** Send analog input */
    fun sendAnalogInput(port: Int, axis: Int, value: Float)

    /** Get current FPS */
    fun getCurrentFps(): Float

    /** Get frame time in ms */
    fun getFrameTimeMs(): Float

    /** Check if emulation is running */
    fun isRunning(): Boolean

    /** Set video output surface */
    fun setSurface(surface: android.view.Surface?)

    /** Set audio sample rate */
    fun setAudioSampleRate(sampleRate: Int)

    /** Apply a shader preset */
    fun setShader(shaderPath: String?)

    /** Get available cheat codes */
    fun setCheats(cheats: List<CheatCode>)
}

data class CheatCode(
    val name: String,
    val code: String,
    val enabled: Boolean = false
)
