package dev.cascade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

/** Renders [BeadSim] and turns touch into a push on the beads. */
class BeadView(
    context: Context,
    private val feedback: Feedback,
    private val onToggle: (String) -> Unit,
) : View(context) {

    var sim: BeadSim? = null
        private set

    /** Gravity in screen space, m/s². Written by the sensor listener. */
    @Volatile var gravityX = 0f
    @Volatile var gravityY = 9.81f

    /** Fraction of the face covered by beads; changed with the rotary crown. */
    var fill = DEFAULT_FILL
        set(value) {
            field = value.coerceIn(MIN_FILL, MAX_FILL)
            sim?.let { it.setCount(beadsFor(it)) }
        }

    var running = false
        set(value) {
            field = value
            lastFrame = 0L
            if (value) postInvalidateOnAnimation()
        }

    private var sprites: Array<Bitmap> = emptyArray()
    private var lastFrame = 0L
    private var pxPerMetre = 0f

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private var hint = ""
    private var hintUntil = 0L

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onToggle("sound"); return true
        }

        override fun onLongPress(e: MotionEvent) = onToggle("haptics")
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Bitmap sprites draw much faster than per-bead gradients on watch GPUs.
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun showHint(text: String) {
        hint = text
        hintUntil = System.currentTimeMillis() + 1600
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val size = min(w, h).toFloat()
        val radius = size * 0.016f
        val round = resources.configuration.isScreenRound
        val newSim = BeadSim(w.toFloat(), h.toFloat(), round, radius, maxBeads(size, radius))
        newSim.setCount(beadsFor(newSim))
        sim = newSim
        // Roughly: a fall across the whole face takes about half a second.
        pxPerMetre = size * 0.8f
        sprites = Array(SHADES) { buildSprite(radius, it / (SHADES - 1f)) }
        hintPaint.textSize = size * 0.075f
    }

    private fun maxBeads(size: Float, radius: Float): Int {
        val r = size / 2f / radius
        return (MAX_FILL * r * r).toInt() + 1
    }

    private fun beadsFor(sim: BeadSim): Int {
        val r = min(width, height) / 2f / sim.radius
        return (fill * r * r).roundToInt()
    }

    override fun onDraw(canvas: Canvas) {
        val sim = sim ?: return
        val now = System.nanoTime()
        if (running) {
            val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1e9f).coerceIn(1f / 120f, 1f / 30f)
            lastFrame = now
            sim.step(gravityX * pxPerMetre, gravityY * pxPerMetre, dt)
            feedback.onFrame(sim.peakImpact, sim.hits, sim.impactEnergy)
        }

        val r = sim.radius
        for (i in 0 until sim.count) {
            val shade = (sim.tint[i] * 0.42f + sim.glow[i] * 0.75f).coerceIn(0f, 1f)
            canvas.drawBitmap(sprites[(shade * (SHADES - 1)).roundToInt()], sim.x[i] - r, sim.y[i] - r, null)
        }

        val remaining = hintUntil - System.currentTimeMillis()
        if (remaining > 0) {
            hintPaint.alpha = (min(1f, remaining / 400f) * 255).toInt()
            canvas.drawText(hint, width / 2f, height * 0.2f, hintPaint)
            if (!running) postInvalidateOnAnimation()
        }

        if (running) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        val sim = sim ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                sim.touchX = event.x
                sim.touchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sim.touchX = Float.NaN
                sim.touchY = Float.NaN
            }
        }
        return true
    }

    private fun buildSprite(radius: Float, t: Float): Bitmap {
        val size = (radius * 2f).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val base = shadeColor(t)
        val highlight = blend(base, Color.WHITE, 0.55f)
        val rim = blend(base, Color.BLACK, 0.45f)
        val c = size / 2f
        // Slightly smaller than the collision radius so neighbours read as separate beads.
        val drawn = c * 0.9f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                c - drawn * 0.35f, c - drawn * 0.35f, drawn * 1.35f,
                intArrayOf(highlight, base, rim),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(c, c, drawn, paint)
        return bmp
    }

    /** Deep blue at rest, through bright azure, to near-white for beads in flight. */
    private fun shadeColor(t: Float): Int = if (t < 0.5f) {
        blend(Color.rgb(0x12, 0x2C, 0x9E), Color.rgb(0x3D, 0x7D, 0xF0), t / 0.5f)
    } else {
        blend(Color.rgb(0x3D, 0x7D, 0xF0), Color.rgb(0xE6, 0xF0, 0xFF), (t - 0.5f) / 0.5f)
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    companion object {
        const val DEFAULT_FILL = 0.42f
        const val MIN_FILL = 0.12f
        const val MAX_FILL = 0.62f
        private const val SHADES = 24
    }
}
