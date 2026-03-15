package com.vortex.emulator.performance

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frame pacer that ensures smooth emulation by controlling frame timing.
 * Synchronizes emulation frames to the target refresh rate.
 */
@Singleton
class FramePacer @Inject constructor() {

    private var targetFps = 60.0
    private var targetFrameTimeNs = (1_000_000_000.0 / targetFps).toLong()
    private var lastFrameTime = 0L
    private var enabled = true

    fun setTargetFps(fps: Double) {
        targetFps = fps
        targetFrameTimeNs = (1_000_000_000.0 / fps).toLong()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) lastFrameTime = 0L
    }

    /**
     * Call this after each frame. Will busy-wait to maintain target frame rate.
     * Returns the actual time spent waiting in nanoseconds.
     */
    fun pace(): Long {
        if (!enabled) return 0L

        val now = System.nanoTime()
        if (lastFrameTime == 0L) {
            lastFrameTime = now
            return 0L
        }

        val elapsed = now - lastFrameTime
        val remaining = targetFrameTimeNs - elapsed

        if (remaining > 0) {
            // Hybrid approach: Thread.sleep for bulk, busy-wait for precision
            val sleepMs = remaining / 1_000_000 - 1
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {}
            }

            // Busy-wait for remaining time (more precise than sleep)
            @Suppress("ControlFlowWithEmptyBody")
            while (System.nanoTime() - lastFrameTime < targetFrameTimeNs) { }
        }

        val actual = System.nanoTime() - lastFrameTime
        lastFrameTime = System.nanoTime()
        return actual
    }

    fun reset() {
        lastFrameTime = 0L
    }
}
