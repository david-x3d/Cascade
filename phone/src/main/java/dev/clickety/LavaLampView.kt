package dev.clickety

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * A lava lamp. Wax blobs are metaballs that heat up in the pool at the bottom, rise, cool at
 * the top and sink again. Tilting the phone tilts their buoyancy; a finger stirs and warms them.
 */
class LavaLampView(
    context: Context,
    private val haptics: Haptics,
) : FidgetView(context) {

    private class Blob(var x: Float, var y: Float, val r: Float, var temp: Float, val phase: Float) {
        var vx = 0f
        var vy = 0f
    }

    private val blobs = ArrayList<Blob>()
    private var time = 0f

    // Geometry, in view pixels. Glass space has its origin at the top centre of the glass.
    private var glassX = 0f
    private var glassTop = 0f
    private var glassH = 0f
    private var topHalf = 0f
    private var botHalf = 0f
    private var capH = 0f
    private var baseH = 0f
    private var pool = 0f

    // Low-res wax field.
    private val q = 3
    private var fw = 0
    private var fh = 0
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var lut = IntArray(0)
    private val src = Rect()
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var rowHalf = FloatArray(0)

    private val glassPath = Path()
    private val capPath = Path()
    private val basePath = Path()
    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val floorGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thinStreak = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val metal = Paint(Paint.ANTI_ALIAS_FLAG)
    private val metalTint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val metalBand = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leftStreak = Path()
    private val rightStreak = Path()

    private var touchX = Float.NaN
    private var touchY = Float.NaN
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        val lampH = min(h * 0.92f, w * 2.6f)
        capH = lampH * 0.12f
        glassH = lampH * 0.56f
        baseH = lampH * 0.32f
        topHalf = lampH * 0.072f
        botHalf = lampH * 0.148f
        pool = glassH * 0.065f
        glassX = w / 2f
        glassTop = (h - lampH) / 2f + capH

        fw = ceil(botHalf * 2f / q).toInt() + 2
        fh = ceil(glassH / q).toInt()
        pixels = IntArray(fw * fh)
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        src.set(0, 0, fw, fh)
        dst.set(glassX - fw * q / 2f, glassTop, glassX + fw * q / 2f, glassTop + fh * q)
        rowHalf = FloatArray(fh) { j -> halfAt((j + 0.5f) * q) / q }
        buildLut()

        if (blobs.isEmpty()) {
            val r = Random(9)
            repeat(8) { i ->
                val rad = botHalf * (0.2f + r.nextFloat() * 0.17f)
                blobs += Blob(
                    x = (r.nextFloat() - 0.5f) * botHalf * 0.6f,
                    y = glassH * (0.15f + 0.8f * r.nextFloat()),
                    r = rad,
                    temp = r.nextFloat(),
                    phase = i * 1.7f,
                )
            }
        }
        buildShapes(w, h)
    }

    private fun halfAt(y: Float): Float {
        val t = (y / glassH).coerceIn(0f, 1f)
        return topHalf + (botHalf - topHalf) * t.pow(0.85f)
    }

    /** Colour for every (row, field strength) pair, so the per-pixel loop is a lookup. */
    private fun buildLut() {
        lut = IntArray(fh * LEVELS)
        val liquidTop = Color.rgb(0x16, 0x07, 0x2A)
        val liquidMid = Color.rgb(0x3B, 0x0C, 0x48)
        val liquidBot = Color.rgb(0x8A, 0x1E, 0x4C)
        val rim = Color.rgb(0xFF, 0xB8, 0x50)
        val body = Color.rgb(0xFF, 0x6A, 0x22)
        val core = Color.rgb(0xD8, 0x2A, 0x12)
        for (j in 0 until fh) {
            val t = j / (fh - 1f)
            val liquid = if (t < 0.6f) mixColor(liquidTop, liquidMid, t / 0.6f)
            else mixColor(liquidMid, liquidBot, ((t - 0.6f) / 0.4f).pow(1.4f))
            // The bulb is under the glass: wax glows brighter the lower it is.
            val light = 0.62f + 0.5f * t.pow(1.6f)
            for (l in 0 until LEVELS) {
                val f = l * FIELD_MAX / (LEVELS - 1)
                val halo = mixColor(liquid, rim, 0.22f * smoothstep(0.45f, 1f, f) * light)
                val s = smoothstep(1f, 2.8f, f)
                val wax = scaleColor(
                    if (s < 0.4f) mixColor(rim, body, s / 0.4f) else mixColor(body, core, (s - 0.4f) / 0.6f),
                    light,
                )
                lut[j * LEVELS + l] = mixColor(halo, wax, smoothstep(0.93f, 1.07f, f)) or 0xFF000000.toInt()
            }
        }
    }

    private fun buildShapes(w: Int, h: Int) {
        // Glass silhouette.
        glassPath.reset()
        val n = 80
        for (k in 0..n) {
            val y = glassH * k / n
            val x = glassX - halfAt(y)
            if (k == 0) glassPath.moveTo(x, glassTop + y) else glassPath.lineTo(x, glassTop + y)
        }
        for (k in n downTo 0) {
            val y = glassH * k / n
            glassPath.lineTo(glassX + halfAt(y), glassTop + y)
        }
        glassPath.close()

        leftStreak.reset()
        rightStreak.reset()
        for (k in 0..n) {
            val y = glassH * (0.04f + 0.92f * k / n)
            val lx = glassX - halfAt(y) * 0.68f
            val rx = glassX + halfAt(y) * 0.8f
            if (k == 0) { leftStreak.moveTo(lx, glassTop + y); rightStreak.moveTo(rx, glassTop + y) }
            else { leftStreak.lineTo(lx, glassTop + y); rightStreak.lineTo(rx, glassTop + y) }
        }
        val fade = { a: Int ->
            LinearGradient(
                0f, glassTop, 0f, glassTop + glassH,
                intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(a, 255, 255, 255), Color.argb(a, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.2f, 0.75f, 1f), Shader.TileMode.CLAMP,
            )
        }
        streakPaint.apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = botHalf * 0.09f
            maskFilter = BlurMaskFilter(botHalf * 0.04f, BlurMaskFilter.Blur.NORMAL)
            shader = fade(95)
        }
        thinStreak.apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = botHalf * 0.03f
            maskFilter = BlurMaskFilter(botHalf * 0.012f, BlurMaskFilter.Blur.NORMAL)
            shader = fade(55)
        }
        sideShade.shader = LinearGradient(
            glassX - botHalf, 0f, glassX + botHalf, 0f,
            intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(190, 0, 0, 0)),
            floatArrayOf(0f, 0.3f, 0.62f, 1f), Shader.TileMode.CLAMP,
        )
        outline.strokeWidth = 1.2f * dp
        outline.color = Color.argb(70, 255, 220, 240)

        // Cap: a short cone with a rounded top.
        val capBottom = glassTop + dp
        val capTop = glassTop - capH
        val cb = topHalf * 1.1f
        val ct = topHalf * 0.5f
        capPath.reset()
        capPath.moveTo(glassX - cb, capBottom)
        capPath.lineTo(glassX - ct, capTop + capH * 0.12f)
        capPath.quadTo(glassX - ct, capTop, glassX - ct * 0.6f, capTop)
        capPath.lineTo(glassX + ct * 0.6f, capTop)
        capPath.quadTo(glassX + ct, capTop, glassX + ct, capTop + capH * 0.12f)
        capPath.lineTo(glassX + cb, capBottom)
        capPath.close()

        // Base: a cone flaring out to a foot.
        val baseTop = glassTop + glassH - dp
        val bt = botHalf * 1.03f
        val bm = botHalf * 1.25f
        val bb = botHalf * 1.62f
        basePath.reset()
        basePath.moveTo(glassX - bt, baseTop)
        basePath.cubicTo(glassX - bt, baseTop + baseH * 0.4f, glassX - bm, baseTop + baseH * 0.75f, glassX - bb, baseTop + baseH * 0.9f)
        basePath.lineTo(glassX - bb, baseTop + baseH)
        basePath.lineTo(glassX + bb, baseTop + baseH)
        basePath.lineTo(glassX + bb, baseTop + baseH * 0.9f)
        basePath.cubicTo(glassX + bm, baseTop + baseH * 0.75f, glassX + bt, baseTop + baseH * 0.4f, glassX + bt, baseTop)
        basePath.close()

        metal.shader = LinearGradient(
            glassX - bb, 0f, glassX + bb, 0f,
            intArrayOf(
                Color.rgb(0x18, 0x18, 0x1C), Color.rgb(0x55, 0x55, 0x5E), Color.rgb(0xC8, 0xC8, 0xD2),
                Color.rgb(0xF2, 0xF2, 0xF8), Color.rgb(0x8A, 0x8A, 0x94), Color.rgb(0x3A, 0x3A, 0x42),
                Color.rgb(0x70, 0x70, 0x7A), Color.rgb(0x16, 0x16, 0x1A),
            ),
            floatArrayOf(0f, 0.18f, 0.3f, 0.36f, 0.5f, 0.68f, 0.82f, 1f), Shader.TileMode.CLAMP,
        )
        // Warm light spilling down from the glass onto the metal.
        metalTint.shader = LinearGradient(
            0f, baseTop, 0f, baseTop + baseH,
            intArrayOf(Color.argb(150, 255, 90, 60), Color.argb(30, 255, 80, 60), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        metalBand.color = Color.argb(90, 0, 0, 0)

        bgPaint.shader = RadialGradient(
            glassX, glassTop + glassH * 0.55f, max(w, h) * 0.7f,
            intArrayOf(Color.rgb(0x22, 0x0C, 0x24), Color.rgb(0x0C, 0x05, 0x10), Color.rgb(0x03, 0x02, 0x05)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        glowPaint.shader = RadialGradient(
            glassX, glassTop + glassH * 0.6f, botHalf * 3.2f,
            intArrayOf(Color.argb(90, 255, 70, 90), Color.argb(30, 200, 40, 120), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        floorGlow.shader = RadialGradient(
            glassX, baseTop + baseH, bb * 2.2f,
            intArrayOf(Color.argb(70, 255, 90, 70), Color.argb(0, 0, 0, 0)),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun update(dt: Float) {
        time += dt
        val g = hypot(gravityX, gravityY)
        val ux = if (g > 0.1f) gravityX / g else 0f
        val uy = if (g > 0.1f) gravityY / g else 1f
        val gScale = min(1f, g / 9.81f)
        val buoyancy = glassH * 0.42f * gScale
        val drag = 1f - min(0.9f, dt * 1.1f)

        val tx = touchX - glassX
        val ty = touchY - glassTop
        val fdx = touchX - lastTouchX
        val fdy = touchY - lastTouchY
        lastTouchX = touchX
        lastTouchY = touchY

        for (b in blobs) {
            // Heat in the pool, cool slowly everywhere and faster near the cap.
            if (b.y > glassH - pool - b.r * 0.5f) b.temp += dt * 0.32f
            b.temp -= dt * 0.045f
            if (b.y < glassH * 0.22f) b.temp -= dt * 0.16f
            b.temp = b.temp.coerceIn(0f, 1.2f)

            val lift = (b.temp - 0.5f) * buoyancy
            b.vx += (-ux * lift + sin(time * 0.31f + b.phase) * glassH * 0.012f) * dt
            b.vy += (-uy * lift) * dt

            if (!tx.isNaN()) {
                val d = hypot(b.x - tx, b.y - ty)
                if (d < b.r * 1.6f) {
                    val k = 1f - d / (b.r * 1.6f)
                    b.vx += fdx / dt * k * 0.08f
                    b.vy += fdy / dt * k * 0.08f
                    b.temp += dt * 0.4f * k
                    haptics.lowTick(0.25f * k + 0.05f, minGapMs = 140)
                }
            }
            b.vx *= drag
            b.vy *= drag
        }
        // Soft separation keeps blobs from collapsing into one lump.
        for (i in blobs.indices) for (j in i + 1 until blobs.size) {
            val a = blobs[i]
            val b = blobs[j]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val d = hypot(dx, dy)
            val min = (a.r + b.r) * 0.6f
            if (d < min && d > 1e-3f) {
                val push = (min - d) / d * 0.9f * dt
                a.vx -= dx * push; a.vy -= dy * push
                b.vx += dx * push; b.vy += dy * push
            }
        }
        for (b in blobs) {
            b.x += b.vx * dt
            b.y += b.vy * dt
            val minY = b.r * 0.4f
            val maxY = glassH - b.r * 0.45f
            if (b.y < minY) { b.y = minY; b.vy = abs(b.vy) * 0.1f }
            if (b.y > maxY) { b.y = maxY; b.vy = -abs(b.vy) * 0.1f }
            val lim = max(0f, halfAt(b.y) - b.r * 0.5f)
            if (b.x < -lim) { b.x = -lim; b.vx = abs(b.vx) * 0.2f }
            if (b.x > lim) { b.x = lim; b.vx = -abs(b.vx) * 0.2f }
        }
    }

    private fun paintField() {
        val n = blobs.size
        val bx = FloatArray(n)
        val by = FloatArray(n)
        val r2 = FloatArray(n)
        val sy = FloatArray(n)
        for (i in 0 until n) {
            val b = blobs[i]
            bx[i] = b.x / q + fw / 2f
            by[i] = b.y / q
            val wobble = 1f + 0.06f * sin(time * 0.8f + b.phase)
            val rr = b.r / q * wobble
            r2[i] = rr * rr
            // Moving wax stretches into a teardrop along its path.
            sy[i] = 1f / (1f + min(0.6f, abs(b.vy) / (glassH * 0.22f)))
        }
        val poolTop = (glassH - pool) / q
        val poolDepth = pool / q
        val half = fw / 2f
        val scale = (LEVELS - 1) / FIELD_MAX
        for (j in 0 until fh) {
            val y = j + 0.5f
            val limit = rowHalf[j]
            val dyPool = max(0.5f, fh - y)
            val poolField = (poolDepth / dyPool).let { it * it } +
                0.12f * sin(time * 0.6f) * smoothstep(poolTop - 4f, poolTop + 2f, y)
            val base = j * fw
            val lutRow = j * LEVELS
            for (i in 0 until fw) {
                val x = i + 0.5f
                if (abs(x - half) > limit + 1f) {
                    pixels[base + i] = 0
                    continue
                }
                var f = poolField
                for (k in 0 until n) {
                    val dx = x - bx[k]
                    val dy = (y - by[k]) * sy[k]
                    f += r2[k] / (dx * dx + dy * dy + 0.5f)
                }
                val l = (f * scale).toInt()
                pixels[base + i] = lut[lutRow + if (l >= LEVELS) LEVELS - 1 else l]
            }
        }
        bitmap?.setPixels(pixels, 0, fw, 0, 0, fw, fh)
    }

    override fun render(canvas: Canvas) {
        canvas.drawPaint(bgPaint)
        canvas.drawPaint(glowPaint)
        canvas.drawPaint(floorGlow)
        val bmp = bitmap ?: return
        paintField()

        canvas.save()
        canvas.clipPath(glassPath)
        canvas.drawBitmap(bmp, src, dst, bitmapPaint)
        canvas.drawPath(glassPath, sideShade)
        canvas.restore()
        canvas.drawPath(leftStreak, streakPaint)
        canvas.drawPath(leftStreak, thinStreak)
        canvas.drawPath(rightStreak, thinStreak)
        canvas.drawPath(glassPath, outline)

        canvas.drawPath(capPath, metal)
        canvas.drawPath(basePath, metal)
        canvas.drawPath(basePath, metalTint)
        // Two machined bands on the base.
        val baseTop = glassTop + glassH
        for (k in 0..1) {
            val y = baseTop + baseH * (0.12f + k * 0.05f)
            metalBand.strokeWidth = dp
            canvas.drawLine(glassX - botHalf * 1.06f, y, glassX + botHalf * 1.06f, y, metalBand)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x; touchY = event.y
                lastTouchX = event.x; lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> { touchX = event.x; touchY = event.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touchX = Float.NaN; touchY = Float.NaN }
        }
        return true
    }

    private companion object {
        const val LEVELS = 160
        const val FIELD_MAX = 4f
    }
}
