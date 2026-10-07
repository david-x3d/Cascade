package dev.clickety

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/**
 * Continuous sand sound: a soft band-limited hiss for moving sand plus individual grain ticks
 * whose density follows how many grains are landing.
 */
class SandAudio {
    /** 0..1 loudness of the moving-sand hiss. */
    @Volatile var hiss = 0f

    /** 0..1 density of individual grain impacts. */
    @Volatile var crackle = 0f

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "sand-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val rate = 44_100
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(format)
                .setBufferSizeInBytes(max(min, 2048 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            return
        }
        track.play()

        val buf = FloatArray(512)
        var seed = 0x1234567
        fun white(): Float {
            seed = seed xor (seed shl 13)
            seed = seed xor (seed ushr 17)
            seed = seed xor (seed shl 5)
            return (seed ushr 8) * (2f / (1 shl 24)) - 1f
        }

        var h = 0f
        var c = 0f
        var lpA = 0f
        var lpB = 0f
        var grainEnv = 0f
        var grainAmp = 0f
        var grainTone = 0f
        var tone = 0f
        while (running) {
            val th = hiss
            val tc = crackle
            for (n in buf.indices) {
                h += (th - h) * 0.0006f
                c += (tc - c) * 0.002f
                val w = white()
                // Band-pass roughly 1.5–7 kHz: the dry rustle of grains sliding over each other.
                lpA += (w - lpA) * 0.6f
                lpB += (lpA - lpB) * 0.12f
                val rustle = lpA - lpB

                if (grainEnv < 0.02f && white() * 0.5f + 0.5f < c * 0.01f) {
                    grainEnv = 1f
                    grainAmp = 0.25f + (white() * 0.5f + 0.5f) * 0.75f
                    grainTone = 0.25f + (white() * 0.5f + 0.5f) * 0.5f
                }
                grainEnv *= 0.86f
                // Each grain tick is a tiny, slightly tonal click.
                tone += (w - tone) * grainTone
                val tick = tone * grainEnv * grainAmp

                buf[n] = rustle * h * 0.32f + tick * 0.55f * min(1f, c * 3f)
            }
            if (track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING) < 0) break
        }
        track.stop()
        track.release()
    }
}
