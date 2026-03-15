package com.vortex.emulator.emulation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Plays interleaved stereo PCM audio produced by the libretro core.
 */
class AudioPlayer {
    private var track: AudioTrack? = null
    private var sampleRate = 44100

    fun start(sampleRate: Int) {
        this.sampleRate = sampleRate
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
            .build()

        track?.play()
        Log.i("AudioPlayer", "Started: ${sampleRate}Hz, buf=$bufSize")
    }

    fun writeSamples(samples: ShortArray) {
        track?.write(samples, 0, samples.size)
    }

    fun stop() {
        try {
            track?.stop()
        } catch (_: Exception) {}
        track?.release()
        track = null
    }
}
