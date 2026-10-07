package dev.cascade.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Bead clicks through the speaker and ticks through the vibration motor. */
class Feedback(context: Context) {

    var soundOn = true
    var hapticsOn = true

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    private val canTick = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)

    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loaded = BooleanArray(5)
    private val clicks: IntArray

    private var lastClick = 0L
    private var lastBuzz = 0L

    init {
        // Synthesised once into the cache so the APK ships no audio assets.
        clicks = CLICK_PITCHES.mapIndexed { i, hz ->
            val file = File(context.cacheDir, "cascade_glass_v2_$i.wav")
            if (!file.exists()) writeClick(file, hz)
            pool.load(file.path, 1)
        }.toIntArray()
        pool.setOnLoadCompleteListener { _, sample, status ->
            if (status == 0) for (i in clicks.indices) if (clicks[i] == sample) loaded[i] = true
        }
    }

    private var budget = 1.2f
    private var budgetAt = SystemClock.uptimeMillis()
    private var variant = 0

    fun onFrame(peak: Float, wall: Float, energy: Float, radiusRatio: Float) {
        val now = SystemClock.uptimeMillis()
        budget = min(1.2f, budget + (now - budgetAt) * .00065f)
        budgetAt = now
        if (soundOn && peak > .045f && now - lastClick >= 65) {
            val volume = (peak * .38f + kotlin.math.sqrt(energy) * .04f).coerceIn(.015f, .65f)
            val index = if (peak < .20f) 4 else (variant++ % 4)
            if (loaded[index]) {
                pool.play(clicks[index], volume, volume, 1, 0, (1f / radiusRatio).coerceIn(.8f,1.2f))
                lastClick = now
            }
        }
        // Large impacts only. Contact count and steady support forces never trigger vibration.
        val impulse = maxOf(wall, if (peak > .9f) peak * .65f else 0f)
        if (hapticsOn && impulse > .42f && now - lastBuzz >= 120) {
            val strength = ((impulse - .35f) * .65f).coerceIn(.12f,.8f)
            val cost = .25f + strength * .65f
            if (budget >= cost) { budget -= cost; lastBuzz = now; buzz(strength) }
        }
    }

    private fun buzz(strength: Float) {
        if (!hapticsOn) return
        val effect = if (canTick) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, strength)
                .compose()
        } else VibrationEffect.createOneShot(7, (25 + 150 * strength).toInt())
        vibrator.vibrate(effect)
    }

    fun release() = pool.release()

    private fun writeClick(file: File, hz: Float) {
        val rate = 44_100
        val samples = rate * 75 / 1000
        val pcm = ShortArray(samples)
        val rnd = Random(hz.toInt())
        for (n in 0 until samples) {
            val t = n.toFloat() / rate
            // A bright ceramic "tok": a fast-decaying tone with a burst of noise at the attack.
            val tone = sin(2.0 * PI * hz * t).toFloat() * exp(-t / 0.006f)
            val overtone = sin(2.0 * PI * hz * 2.7 * t).toFloat() * exp(-t / 0.0025f) * 0.4f
            val noise = (rnd.nextFloat() * 2f - 1f) * exp(-t / 0.0012f) * 0.5f
            val ring = sin(2.0 * PI * hz * 4.13 * t).toFloat() * exp(-t / .013f) * .16f
            val ripple = if (hz == 1400f) sin(2.0 * PI * 2900 * t).toFloat() * exp(-t / .025f) * .15f else 0f
            pcm[n] = ((tone + overtone + noise * .35f + ring + ripple) * 0.45f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
        data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        data.put("data".toByteArray()).putInt(samples * 2)
        pcm.forEach { data.putShort(it) }
        FileOutputStream(file).use { it.write(data.array()) }
    }

    private companion object {
        val CLICK_PITCHES = listOf(1900f, 2500f, 3200f, 4100f, 1400f)
    }
}
