package dev.clickety

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Short sound effects, synthesised on first launch so the APK ships no audio assets. */
class Sfx(private val context: Context) {

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val ids = HashMap<String, Int>()

    init {
        add("toggle") { Synth.toggle() }
        add("rocker") { Synth.rocker() }
        add("keyDown") { Synth.keyDown() }
        add("keyUp") { Synth.keyUp() }
        add("detent") { Synth.tick(6200f, 0.0012f, 0.6f, seed = 3) }
        add("notch") { Synth.tick(3100f, 0.002f, 0.45f, seed = 4) }
        add("button") { Synth.arcade() }
        add("pop") { Synth.pop() }
        for (i in 0 until 3) add("squelch$i") { Synth.squelch(i) }
    }

    private fun add(name: String, make: () -> FloatArray) {
        val file = File(context.cacheDir, "sfx-$name-v$VERSION.wav")
        if (!file.exists()) Synth.writeWav(file, make())
        ids[name] = pool.load(file.path, 1)
    }

    fun play(name: String, volume: Float = 1f, rate: Float = 1f) {
        val id = ids[name] ?: return
        val v = volume.coerceIn(0f, 1f)
        pool.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    private companion object {
        /** Bump to regenerate cached sounds after changing a synth recipe. */
        const val VERSION = 1
    }
}

/** Vibration primitives with graceful fallbacks for phones that lack them. */
class Haptics(context: Context) {

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val supported = HashMap<Int, Boolean>()
    private var last = 0L

    fun play(primitive: Int, scale: Float = 1f, minGapMs: Long = 0) {
        if (!vibrator.hasVibrator()) return
        val now = SystemClock.uptimeMillis()
        if (now - last < minGapMs) return
        last = now
        val s = scale.coerceIn(0.05f, 1f)
        val ok = supported.getOrPut(primitive) { vibrator.areAllPrimitivesSupported(primitive) }
        val effect = if (ok) {
            VibrationEffect.startComposition().addPrimitive(primitive, s).compose()
        } else {
            val ms = when (primitive) {
                Composition.PRIMITIVE_THUD -> 30L
                Composition.PRIMITIVE_CLICK -> 14L
                else -> 8L
            }
            VibrationEffect.createOneShot(ms, (30 + 225 * s).toInt())
        }
        vibrator.vibrate(effect)
    }

    fun tick(scale: Float = 0.6f, minGapMs: Long = 0) = play(Composition.PRIMITIVE_TICK, scale, minGapMs)
    fun click(scale: Float = 1f) = play(Composition.PRIMITIVE_CLICK, scale)
    fun thud(scale: Float = 1f, minGapMs: Long = 0) = play(Composition.PRIMITIVE_THUD, scale, minGapMs)
    fun lowTick(scale: Float = 0.6f, minGapMs: Long = 0) =
        play(Composition.PRIMITIVE_LOW_TICK, scale, minGapMs)
}

/** Tiny additive/subtractive synth for mechanical clicks and squishy noises. */
object Synth {
    const val RATE = 44_100

    private fun buffer(seconds: Float) = FloatArray((RATE * seconds).toInt())

    /** Decaying sine, the "ring" of a small metal or plastic part. */
    private fun ring(buf: FloatArray, at: Float, hz: Float, decay: Float, amp: Float) {
        val start = (at * RATE).toInt()
        for (n in start until buf.size) {
            val t = (n - start).toFloat() / RATE
            val env = exp(-t / decay)
            if (env < 1e-4f) break
            buf[n] += (sin(2.0 * PI * hz * t).toFloat()) * env * amp
        }
    }

    /** Noise burst through a one-pole lowpass; [bright] 1 = white, smaller = duller. */
    private fun noise(buf: FloatArray, at: Float, decay: Float, amp: Float, bright: Float, rnd: Random) {
        val start = (at * RATE).toInt()
        var lp = 0f
        for (n in start until buf.size) {
            val t = (n - start).toFloat() / RATE
            val env = exp(-t / decay)
            if (env < 1e-4f) break
            lp += (rnd.nextFloat() * 2f - 1f - lp) * bright
            buf[n] += lp * env * amp
        }
    }

    /** Sine with an exponential pitch sweep: thumps, bloops and pops. */
    private fun sweep(buf: FloatArray, at: Float, from: Float, to: Float, length: Float, decay: Float, amp: Float) {
        val start = (at * RATE).toInt()
        var phase = 0.0
        for (n in start until buf.size) {
            val t = (n - start).toFloat() / RATE
            val env = exp(-t / decay)
            if (env < 1e-4f) break
            val k = (t / length).coerceIn(0f, 1f)
            val hz = from * Math.pow((to / from).toDouble(), k.toDouble())
            phase += 2.0 * PI * hz / RATE
            buf[n] += sin(phase).toFloat() * env * amp
        }
    }

    private fun finish(buf: FloatArray, peak: Float = 0.9f): FloatArray {
        var m = 1e-6f
        for (v in buf) m = max(m, abs(v))
        val k = peak / m
        // 2 ms fade-out so nothing ends on a click of its own.
        val fade = RATE / 500
        for (n in buf.indices) {
            buf[n] *= k
            val left = buf.size - n
            if (left < fade) buf[n] *= left.toFloat() / fade
        }
        return buf
    }

    fun tick(hz: Float, decay: Float, peak: Float, seed: Int): FloatArray {
        val b = buffer(0.03f)
        val rnd = Random(seed)
        ring(b, 0f, hz, decay, 1f)
        ring(b, 0f, hz * 1.73f, decay * 0.6f, 0.4f)
        noise(b, 0f, 0.0005f, 0.8f, 0.9f, rnd)
        return finish(b, peak)
    }

    /** Bat-handle toggle: lever snap followed by the contact settling. */
    fun toggle(): FloatArray {
        val b = buffer(0.08f)
        val rnd = Random(1)
        noise(b, 0f, 0.0007f, 1f, 0.95f, rnd)
        ring(b, 0f, 4300f, 0.004f, 0.8f)
        ring(b, 0f, 7900f, 0.0018f, 0.35f)
        sweep(b, 0f, 900f, 300f, 0.01f, 0.006f, 0.5f)
        noise(b, 0.009f, 0.0006f, 0.45f, 0.9f, rnd)
        ring(b, 0.009f, 5200f, 0.002f, 0.3f)
        return finish(b)
    }

    /** Rocker: deeper plastic clack with a body thump. */
    fun rocker(): FloatArray {
        val b = buffer(0.09f)
        val rnd = Random(2)
        noise(b, 0f, 0.0012f, 1f, 0.6f, rnd)
        ring(b, 0f, 1150f, 0.008f, 0.7f)
        ring(b, 0f, 2750f, 0.004f, 0.45f)
        sweep(b, 0f, 260f, 140f, 0.02f, 0.014f, 0.8f)
        return finish(b)
    }

    /** Clicky mechanical key: click jacket snap, then the bottom-out thock. */
    fun keyDown(): FloatArray {
        val b = buffer(0.09f)
        val rnd = Random(5)
        noise(b, 0f, 0.0004f, 1f, 1f, rnd)
        ring(b, 0f, 5600f, 0.0016f, 0.7f)
        noise(b, 0.007f, 0.006f, 0.8f, 0.25f, rnd)
        sweep(b, 0.007f, 520f, 300f, 0.012f, 0.01f, 0.7f)
        ring(b, 0.007f, 1650f, 0.004f, 0.25f)
        return finish(b, 0.85f)
    }

    fun keyUp(): FloatArray {
        val b = buffer(0.06f)
        val rnd = Random(6)
        noise(b, 0f, 0.0004f, 0.7f, 1f, rnd)
        ring(b, 0f, 4800f, 0.0014f, 0.5f)
        noise(b, 0.004f, 0.003f, 0.4f, 0.3f, rnd)
        sweep(b, 0.004f, 700f, 420f, 0.01f, 0.006f, 0.35f)
        return finish(b, 0.6f)
    }

    /** Arcade microswitch under a big plastic plunger. */
    fun arcade(): FloatArray {
        val b = buffer(0.12f)
        val rnd = Random(7)
        noise(b, 0f, 0.0005f, 0.9f, 1f, rnd)
        ring(b, 0f, 3300f, 0.0025f, 0.7f)
        sweep(b, 0f, 330f, 120f, 0.03f, 0.03f, 1f)
        noise(b, 0.002f, 0.01f, 0.5f, 0.15f, rnd)
        return finish(b)
    }

    fun pop(): FloatArray {
        val b = buffer(0.07f)
        sweep(b, 0f, 1100f, 260f, 0.025f, 0.02f, 1f)
        noise(b, 0f, 0.001f, 0.3f, 0.5f, Random(8))
        return finish(b, 0.7f)
    }

    /**
     * Wet squelch: noise through a resonant bandpass sweeping up, with a couple of small
     * bubble pops riding on top.
     */
    fun squelch(variant: Int): FloatArray {
        val length = 0.16f + variant * 0.04f
        val b = buffer(length + 0.05f)
        val rnd = Random(20 + variant)
        var low = 0f
        var band = 0f
        val from = 260f + variant * 70f
        val to = 900f + variant * 260f
        for (n in b.indices) {
            val t = n.toFloat() / RATE
            val k = (t / length).coerceIn(0f, 1f)
            val hz = from + (to - from) * k * k
            val f = 2f * sin(PI * hz / RATE).toFloat()
            val q = 0.18f
            val input = rnd.nextFloat() * 2f - 1f
            low += f * band
            val high = input - low - q * band
            band += f * high
            val env = (t / 0.012f).coerceAtMost(1f) * exp(-t / (length * 0.45f))
            // Slow amplitude wobble makes it sound sticky instead of like a hiss.
            val wobble = 0.65f + 0.35f * sin(2.0 * PI * (18 + variant * 5) * t).toFloat()
            b[n] = band * env * wobble
        }
        sweep(b, length * 0.35f, 380f, 900f, 0.018f, 0.012f, 0.12f)
        sweep(b, length * 0.7f, 520f, 1300f, 0.012f, 0.008f, 0.08f)
        return finish(b, 0.75f)
    }

    fun writeWav(file: File, samples: FloatArray) {
        val data = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples.size * 2)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        FileOutputStream(file).use { it.write(data.array()) }
    }
}
