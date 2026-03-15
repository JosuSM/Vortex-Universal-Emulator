package com.vortex.emulator.performance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PerformanceStats(
    val fps: Float = 0f,
    val frameTimeMs: Float = 0f,
    val cpuUsagePercent: Float = 0f,
    val memoryUsageMb: Long = 0,
    val gpuFrameTimeMs: Float = 0f,
    val emulationSpeed: Float = 100f,
    val droppedFrames: Int = 0,
    val totalFrames: Long = 0
)

@Singleton
class PerformanceMonitor @Inject constructor() {

    private val _stats = MutableStateFlow(PerformanceStats())
    val stats: StateFlow<PerformanceStats> = _stats.asStateFlow()

    private var frameTimestamps = ArrayDeque<Long>(120)
    private var lastFrameTime = 0L
    private var droppedFrames = 0
    private var totalFrames = 0L

    fun onFrameRendered() {
        val now = System.nanoTime()
        val frameTime = if (lastFrameTime > 0) (now - lastFrameTime) / 1_000_000f else 16.67f
        lastFrameTime = now

        frameTimestamps.addLast(now)
        totalFrames++

        // Keep only last second of timestamps
        val oneSecondAgo = now - 1_000_000_000L
        while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < oneSecondAgo) {
            frameTimestamps.removeFirst()
        }

        val fps = frameTimestamps.size.toFloat()

        // Detect dropped frames (target 60fps = 16.67ms per frame)
        if (frameTime > 20f) {
            droppedFrames++
        }

        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        _stats.value = PerformanceStats(
            fps = fps,
            frameTimeMs = frameTime,
            cpuUsagePercent = estimateCpuUsage(frameTime),
            memoryUsageMb = usedMemory,
            emulationSpeed = (fps / 60f) * 100f,
            droppedFrames = droppedFrames,
            totalFrames = totalFrames
        )
    }

    fun reset() {
        frameTimestamps.clear()
        lastFrameTime = 0L
        droppedFrames = 0
        totalFrames = 0L
        _stats.value = PerformanceStats()
    }

    private fun estimateCpuUsage(frameTimeMs: Float): Float {
        // Rough estimation: if frame takes 16.67ms, ~100% of one core
        return (frameTimeMs / 16.67f * 100f).coerceIn(0f, 100f)
    }
}
