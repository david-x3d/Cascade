package dev.clickety.wear

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
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val clicks: IntArray

    private var lastClick = 0L
    private var lastBuzz = 0L

    init {
        // Synthesised once into the cache so the APK ships no audio assets.
        clicks = CLICK_PITCHES.mapIndexed { i, hz ->
            val file = File(context.cacheDir, "click_$i.wav")
            if (!file.exists()) writeClick(file, hz)
            pool.load(file.path, 1)
        }.toIntArray()
    }

    /** Called once per frame with the simulation's collision stats. */
    fun onFrame(peakImpact: Float, hits: Int, energy: Float) {
        val now = SystemClock.uptimeMillis()
        if (soundOn && hits > 0 && now - lastClick >= CLICK_INTERVAL_MS) {
            lastClick = now
            val volume = min(1f, peakImpact / 6f) * 0.8f
            if (volume > 0.04f) {
                pool.play(clicks.random(), volume, volume, 1, 0, 0.85f + Random.nextFloat() * 0.35f)
            }
        }
        if (hapticsOn && energy > BUZZ_ENERGY && now - lastBuzz >= BUZZ_INTERVAL_MS) {
            lastBuzz = now
            val strength = min(1f, energy / (BUZZ_ENERGY * 12f))
            buzz(strength)
        }
    }

    /** A single confirmation tick for UI toggles. */
    fun confirm() = buzz(0.8f)

    private fun buzz(strength: Float) {
        val effect = if (canTick) {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.25f + 0.75f * strength)
                .compose()
        } else {
            VibrationEffect.createOneShot(12, (40 + 215 * strength).toInt())
        }
        vibrator.vibrate(effect)
    }

    fun release() = pool.release()

    private fun writeClick(file: File, hz: Float) {
        val rate = 44_100
        val samples = rate * 45 / 1000
        val pcm = ShortArray(samples)
        val rnd = Random(hz.toInt())
        for (n in 0 until samples) {
            val t = n.toFloat() / rate
            // A bright ceramic "tok": a fast-decaying tone with a burst of noise at the attack.
            val tone = sin(2.0 * PI * hz * t).toFloat() * exp(-t / 0.006f)
            val overtone = sin(2.0 * PI * hz * 2.7 * t).toFloat() * exp(-t / 0.0025f) * 0.4f
            val noise = (rnd.nextFloat() * 2f - 1f) * exp(-t / 0.0012f) * 0.5f
            pcm[n] = ((tone + overtone + noise) * 0.55f * Short.MAX_VALUE).toInt()
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
        val CLICK_PITCHES = listOf(1900f, 2500f, 3200f, 4100f)
        const val CLICK_INTERVAL_MS = 30L
        const val BUZZ_INTERVAL_MS = 55L
        const val BUZZ_ENERGY = 4f
    }
}
