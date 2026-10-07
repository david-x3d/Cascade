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
import kotlin.math.sqrt
import kotlin.random.Random

/** Bead clicks through the speaker and impacts through the vibration motor.
 * Called once per rendered frame from the physics thread. */
class Feedback(context: Context) {

    @Volatile var soundOn = true
    /** 0..1 master volume for bead sounds. */
    @Volatile var volume = .8f
    /** 0 off, 1 light, 2 medium, 3 strong. */
    @Volatile var hapticLevel = 2
    /** Granular rattle texture while beads tumble, in addition to hard hits. */
    @Volatile var hapticTexture = true
    var hapticsOn: Boolean
        get() = hapticLevel > 0
        set(value) { hapticLevel = if (value) lastLevel else 0 }
    private var lastLevel = 2

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    private fun supported(p: Int) = vibrator.areAllPrimitivesSupported(p)
    private val canTick = supported(VibrationEffect.Composition.PRIMITIVE_TICK)
    private val canClick = supported(VibrationEffect.Composition.PRIMITIVE_CLICK)
    private val canLowTick = Build.VERSION.SDK_INT >= 31 && supported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    private val canThud = Build.VERSION.SDK_INT >= 31 && supported(VibrationEffect.Composition.PRIMITIVE_THUD)
    private val amplitude = vibrator.hasAmplitudeControl()

    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loaded = BooleanArray(CLICK_PITCHES.size)
    private val clicks: IntArray

    private var lastClick = 0L
    private var lastHit = 0L
    private var lastTexture = 0L
    private var rattle = 0f
    private val random = Random(97)

    init {
        // Synthesised once into the cache so the APK ships no audio assets.
        clicks = CLICK_PITCHES.mapIndexed { i, hz ->
            val file = File(context.cacheDir, "cascade_glass_v3_$i.wav")
            if (!file.exists()) writeClick(file, hz, i >= SOFT)
            pool.load(file.path, 1)
        }.toIntArray()
        pool.setOnLoadCompleteListener { _, sample, status ->
            if (status == 0) for (i in clicks.indices) if (clicks[i] == sample) loaded[i] = true
        }
    }

    private var budget = 1.5f
    private var budgetAt = SystemClock.uptimeMillis()

    fun onFrame(peak: Float, wall: Float, energy: Float, impacts: Int, radiusRatio: Float, impactX: Float, finger: Float) {
        val now = SystemClock.uptimeMillis()
        budget = min(1.5f, budget + (now - budgetAt) * .0009f)
        budgetAt = now
        if (soundOn && volume > 0f) sound(now, peak, energy, impacts, radiusRatio, impactX)
        val level = hapticLevel
        if (level == 0) return
        if (level > 0) lastLevel = level
        val gain = GAIN[level]
        // Hard hits: a bead slamming a wall, or a strong collision deep in the pile.
        val hit = maxOf(wall, if (peak > .9f) peak * .7f else 0f, finger * .5f)
        val threshold = HIT_THRESHOLD[level]
        if (hit > threshold && now - lastHit >= 70) {
            val strength = ((hit - threshold * .7f) * .7f).coerceIn(.18f, 1f) * gain
            val cost = .2f + strength * .5f
            if (budget >= cost) {
                budget -= cost; lastHit = now; lastTexture = now
                vibrate(strength, hit > threshold * 3.5f, extra = (rattle * .5f).coerceAtMost(1f))
                rattle = 0f
                return
            }
        }
        if (!hapticTexture) return
        // Rattle: many small collisions felt as a dense, irregular grain, like a box of beads.
        rattle = rattle * .7f + sqrt(energy) * .12f + impacts * .004f + finger * .06f
        if (rattle > .22f && now - lastTexture >= 32 && budget > .05f) {
            val strength = ((rattle - .15f) * .55f).coerceIn(.08f, .55f) * gain
            budget -= .03f + strength * .08f; lastTexture = now
            texture(strength, (rattle * 1.6f).toInt().coerceIn(1, 3))
            rattle *= .4f
        }
    }

    private fun sound(now: Long, peak: Float, energy: Float, impacts: Int, radiusRatio: Float, impactX: Float) {
        if (peak <= .045f || now - lastClick < 18) return
        val base = (peak * .34f + sqrt(energy) * .035f).coerceIn(.012f, .7f) * volume
        val index = if (peak < .2f) SOFT + random.nextInt(CLICK_PITCHES.size - SOFT) else random.nextInt(SOFT)
        // Pan towards the side where the hardest impact happened.
        val pan = impactX.coerceIn(0f, 1f)
        play(index, base, pan, radiusRatio)
        // Dense pours are a shower of clicks, not a single tone.
        val extra = min(3, impacts / 14)
        for (k in 0 until extra) {
            val v = base * (.25f + random.nextFloat() * .45f)
            play(random.nextInt(CLICK_PITCHES.size), v, random.nextFloat(), radiusRatio * (.92f + random.nextFloat() * .16f))
        }
        lastClick = now
    }

    private fun play(index: Int, volume: Float, pan: Float, radiusRatio: Float) {
        if (!loaded[index]) return
        val left = volume * min(1f, 2f - 2f * pan); val right = volume * min(1f, 2f * pan)
        pool.play(clicks[index], left, right, 1, 0, ((1f / radiusRatio) * (.96f + random.nextFloat() * .08f)).coerceIn(.75f, 1.3f))
    }

    private fun vibrate(strength: Float, heavy: Boolean, extra: Float) {
        val effect = if (canClick) {
            val c = VibrationEffect.startComposition()
            if (heavy && canThud) c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, strength * .8f)
            c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, strength)
            // Beads rebounding off the wall after the first hit.
            if (extra > .1f && canTick) {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, strength * .45f, 18)
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, strength * .25f, 14)
            }
            c.compose()
        } else oneShot(if (heavy) 16 else 9, strength)
        vibrator.vibrate(effect)
    }

    private fun texture(strength: Float, grains: Int) {
        val effect = if (canLowTick || canTick) {
            val primitive = if (canLowTick) VibrationEffect.Composition.PRIMITIVE_LOW_TICK else VibrationEffect.Composition.PRIMITIVE_TICK
            val c = VibrationEffect.startComposition()
            for (g in 0 until grains) {
                c.addPrimitive(primitive, (strength * (.65f + random.nextFloat() * .35f)).coerceIn(.05f, 1f), if (g == 0) 0 else 6 + random.nextInt(6))
            }
            c.compose()
        } else oneShot(4, strength * .6f)
        vibrator.vibrate(effect)
    }

    private fun oneShot(ms: Long, strength: Float) =
        VibrationEffect.createOneShot(ms, if (amplitude) (20 + 235 * strength).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE)

    fun release() = pool.release()

    private fun writeClick(file: File, hz: Float, soft: Boolean) {
        val rate = 44_100
        val samples = rate * (if (soft) 90 else 70) / 1000
        val pcm = ShortArray(samples)
        val rnd = Random(hz.toInt())
        // Glass spheres ring at inharmonic modes; small detunes keep repeated clicks from sounding synthetic.
        val decay = if (soft) .009f else .0045f
        for (n in 0 until samples) {
            val t = n.toFloat() / rate
            val tone = sin(2.0 * PI * hz * t).toFloat() * exp(-t / decay)
            val overtone = sin(2.0 * PI * hz * 2.76 * t).toFloat() * exp(-t / (decay * .45f)) * .45f
            val noise = (rnd.nextFloat() * 2f - 1f) * exp(-t / .0009f) * (if (soft) .25f else .55f)
            val ring = sin(2.0 * PI * hz * 5.40 * t).toFloat() * exp(-t / .011f) * .14f
            val body = if (soft) sin(2.0 * PI * hz * .5 * t).toFloat() * exp(-t / .02f) * .2f else 0f
            pcm[n] = ((tone + overtone + noise + ring + body) * .42f * Short.MAX_VALUE).toInt()
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
        /** Bright hard clicks first, then softer, lower taps for gentle contacts. */
        val CLICK_PITCHES = listOf(2300f, 2900f, 3500f, 4200f, 5100f, 1500f, 1800f, 2100f)
        const val SOFT = 5
        val GAIN = floatArrayOf(0f, .5f, .78f, 1f)
        val HIT_THRESHOLD = floatArrayOf(9f, .65f, .45f, .3f)
    }
}
