package com.vortex.emulator.emulation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

enum class AudioLatency(val multiplier: Int, val label: String) {
    LOW(2, "Low"),
    MEDIUM(4, "Medium"),
    HIGH(8, "High")
}

class AudioPlayer {
    companion object {
        private const val TAG = "AudioPlayer"
    }

    private var track: AudioTrack? = null
    private var sampleRate = 44100

    @Volatile var volume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            track?.setVolume(field)
        }

    @Volatile var latency: AudioLatency = AudioLatency.MEDIUM

    fun start(sampleRate: Int) {
        this.sampleRate = sampleRate
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = (minBuf * latency.multiplier).coerceAtLeast(minBuf * 2)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(
                if (latency == AudioLatency.LOW) AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                else AudioTrack.PERFORMANCE_MODE_NONE
            )
            .build()

        track?.setVolume(volume)
        track?.play()
        Log.i(TAG, "Started: ${sampleRate}Hz, buf=$bufSize (${latency.label} latency), vol=$volume")
    }

    fun writeSamples(samples: ShortArray) {
        // Non-blocking write to prevent emulation thread stalls on heavy cores
        track?.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    fun restart() {
        val rate = sampleRate
        stop()
        start(rate)
    }

    fun stop() {
        try {
            track?.stop()
        } catch (_: Exception) {}
        track?.release()
        track = null
    }
}
