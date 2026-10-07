package dev.clickety

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A panel of satisfying switches: toggles, rockers, clicky keys, a detented knob, a big button and a fader. */
class SwitchesView(
    context: Context,
    private val sfx: Sfx,
    private val haptics: Haptics,
) : FidgetView(context) {

    private abstract inner class Control {
        abstract fun hit(x: Float, y: Float): Boolean
        open fun down(x: Float, y: Float) {}
        open fun move(x: Float, y: Float) {}
        open fun up() {}
        open fun update(dt: Float) {}
        abstract fun draw(c: Canvas)
    }

    private val controls = ArrayList<Control>()
    private val owners = HashMap<Int, Control>()
    private var u = 0f

    private val panel = Paint()
    private val engrave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.18f
    }
    private val engraveLight = Paint(engrave)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rect = RectF()
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        u = min(w / 4.2f, h / 8.6f)
        val top = (h - 8.3f * u) / 2f
        val cx = w / 2f
        controls.clear()

        val y0 = top + 1.15f * u
        for (k in -1..1) controls += Toggle(cx + k * 1.15f * u, y0, "ABC"[k + 1].toString(), on = k == 0)
        val y1 = y0 + 1.8f * u
        controls += Rocker(cx - 0.85f * u, y1, on = true)
        controls += Rocker(cx + 0.85f * u, y1, on = false)
        val y2 = y1 + 1.4f * u
        val caps = listOf(
            Triple("C", Color.rgb(0xEC, 0xE6, 0xD6), Color.rgb(0x3A, 0x36, 0x30)),
            Triple("L", Color.rgb(0x8E, 0x93, 0x9A), Color.rgb(0xF4, 0xF4, 0xF4)),
            Triple("I", Color.rgb(0xF0, 0x7A, 0x2B), Color.rgb(0x3A, 0x1A, 0x08)),
            Triple("K", Color.rgb(0x2B, 0xB3, 0xA0), Color.rgb(0x08, 0x2E, 0x28)),
        )
        caps.forEachIndexed { i, (label, color, ink) ->
            controls += Key(cx + (i - 1.5f) * 0.92f * u, y2, label, color, ink)
        }
        val y3 = y2 + 1.75f * u
        controls += Knob(cx - 0.95f * u, y3)
        controls += BigButton(cx + 1.05f * u, y3)
        val y4 = y3 + 1.55f * u
        controls += Fader(cx, y4, w * 0.36f)

        panel.shader = BitmapShader(brushedMetal(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        engrave.textSize = u * 0.13f
        engrave.color = Color.argb(150, 0, 0, 0)
        engraveLight.textSize = u * 0.13f
        engraveLight.color = Color.argb(60, 255, 255, 255)
        sectionLabels = listOf(
            "POWER" to y0 - 0.95f * u, "MAINS" to y1 - 0.62f * u, "KEYS" to y2 - 0.62f * u,
            "GAIN" to y3 - 1.08f * u, "FADER" to y4 - 0.45f * u,
        )
        labelX = listOf(cx, cx, cx, cx - 0.95f * u, cx)
        screwInset = u * 0.28f
    }

    private var sectionLabels = emptyList<Pair<String, Float>>()
    private var labelX = emptyList<Float>()
    private var screwInset = 0f

    private fun brushedMetal(): Bitmap {
        val w = 512
        val h = 256
        val r = java.util.Random(4)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            var row = 0.9f + r.nextFloat() * 0.2f
            for (x in 0 until w) {
                row += (r.nextFloat() - 0.5f) * 0.02f
                row = row.coerceIn(0.85f, 1.15f)
                val v = row * (0.96f + r.nextFloat() * 0.08f)
                px[y * w + x] = scaleColor(Color.rgb(0x2E, 0x31, 0x37), v)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun update(dt: Float) {
        for (c in controls) c.update(dt)
    }

    override fun render(canvas: Canvas) {
        canvas.drawPaint(panel)
        // Soft vignette so the panel reads as lit from above.
        p.shader = RadialGradient(
            width / 2f, height * 0.35f, height * 0.8f,
            intArrayOf(Color.argb(30, 255, 255, 255), Color.argb(0, 0, 0, 0), Color.argb(150, 0, 0, 0)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPaint(p)
        p.shader = null

        for (x in listOf(screwInset, width - screwInset)) for (y in listOf(screwInset, height - screwInset)) {
            drawScrew(canvas, x, y, u * 0.09f)
        }
        sectionLabels.forEachIndexed { i, (label, y) ->
            canvas.drawText(label, labelX[i], y + dp * 0.8f, engraveLight)
            canvas.drawText(label, labelX[i], y, engrave)
        }
        for (c in controls) c.draw(canvas)
    }

    private fun drawScrew(c: Canvas, x: Float, y: Float, r: Float) {
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.4f, Color.rgb(0xC8, 0xCC, 0xD2), Color.rgb(0x40, 0x44, 0x4A), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
        p.shader = null
        stroke.color = Color.argb(200, 20, 20, 24)
        stroke.strokeWidth = r * 0.28f
        c.drawLine(x - r * 0.7f, y + r * 0.2f, x + r * 0.7f, y - r * 0.2f, stroke)
    }

    private fun softShadow(c: Canvas, r: RectF, radius: Float, blur: Float, dy: Float, alpha: Int) {
        shadow.color = Color.argb(alpha, 0, 0, 0)
        shadow.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(r.left, r.top + dy, r.right, r.bottom + dy, radius, radius, shadow)
    }

    // ---- controls ------------------------------------------------------------------------------

    private inner class Toggle(val x: Float, val y: Float, val label: String, on: Boolean) : Control() {
        var on = on
        var pos = if (on) 1f else -1f
        var led = if (on) 1f else 0f
        var startY = 0f

        override fun hit(x: Float, y: Float) = abs(x - this.x) < u * 0.5f && abs(y - this.y) < u * 0.85f
        private var flippedThisTouch = false

        override fun down(x: Float, y: Float) {
            startY = y
            flippedThisTouch = false
        }

        override fun move(x: Float, y: Float) {
            // Flicking the lever works as well as tapping it.
            if (!flippedThisTouch && ((on && y - startY > u * 0.25f) || (!on && startY - y > u * 0.25f))) {
                flippedThisTouch = true
                flip()
            }
        }

        override fun up() {
            if (!flippedThisTouch) flip()
        }

        fun flip() {
            on = !on
            sfx.play("toggle", 0.9f, 0.95f + (Math.random() * 0.1).toFloat())
            haptics.click(0.9f)
        }

        override fun update(dt: Float) {
            pos += ((if (on) 1f else -1f) - pos) * min(1f, dt * 32f)
            led += ((if (on) 1f else 0f) - led) * min(1f, dt * 14f)
        }

        override fun draw(c: Canvas) {
            // Plate.
            rect.set(x - u * 0.42f, y - u * 0.72f, x + u * 0.42f, y + u * 0.72f)
            softShadow(c, rect, u * 0.08f, u * 0.05f, u * 0.03f, 120)
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.rgb(0x52, 0x56, 0x5E), Color.rgb(0x26, 0x29, 0x2E), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.08f, u * 0.08f, p)
            p.shader = null
            stroke.strokeWidth = dp
            stroke.color = Color.argb(60, 255, 255, 255)
            c.drawRoundRect(rect, u * 0.08f, u * 0.08f, stroke)
            text.textSize = u * 0.11f
            text.color = Color.argb(170, 220, 225, 230)
            c.drawText("ON", x, y - u * 0.5f, text)
            c.drawText("OFF", x, y + u * 0.6f, text)

            // LED above the plate.
            val ly = y - u * 0.92f
            if (led > 0.02f) {
                p.shader = RadialGradient(x, ly, u * 0.32f, Color.argb((led * 150).toInt(), 80, 255, 120), Color.argb(0, 80, 255, 120), Shader.TileMode.CLAMP)
                c.drawCircle(x, ly, u * 0.32f, p)
            }
            p.shader = RadialGradient(
                x - u * 0.02f, ly - u * 0.03f, u * 0.09f,
                mixColor(Color.rgb(0x10, 0x30, 0x18), Color.rgb(0xC8, 0xFF, 0xD0), led),
                mixColor(Color.rgb(0x06, 0x16, 0x0A), Color.rgb(0x20, 0xD0, 0x50), led),
                Shader.TileMode.CLAMP,
            )
            c.drawCircle(x, ly, u * 0.075f, p)
            p.shader = null
            p.color = Color.argb(180, 255, 255, 255)
            c.drawCircle(x - u * 0.025f, ly - u * 0.025f, u * 0.018f, p)

            // Hex nut and threaded bushing.
            hexagon(x, y, u * 0.26f)
            p.shader = LinearGradient(x - u * 0.26f, y - u * 0.26f, x + u * 0.26f, y + u * 0.26f,
                intArrayOf(Color.rgb(0xF0, 0xF2, 0xF5), Color.rgb(0x9A, 0x9E, 0xA6), Color.rgb(0x4A, 0x4E, 0x56)), null, Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = RadialGradient(x, y, u * 0.16f, intArrayOf(Color.rgb(0x30, 0x32, 0x36), Color.rgb(0x70, 0x74, 0x7A), Color.rgb(0x22, 0x24, 0x28)), floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, u * 0.16f, p)
            p.shader = null

            // Lever, seen in perspective: it shortens as it passes through vertical.
            val len = u * 0.62f
            val tipY = y - pos * len
            val tipR = u * (0.095f + 0.025f * (1f - abs(pos)))
            shadow.maskFilter = BlurMaskFilter(u * 0.04f, BlurMaskFilter.Blur.NORMAL)
            shadow.color = Color.argb(110, 0, 0, 0)
            c.drawCircle(x + u * 0.06f, tipY + u * 0.1f, tipR, shadow)
            path.reset()
            val base = u * 0.075f
            val neck = u * 0.045f
            path.moveTo(x - base, y)
            path.lineTo(x - neck, tipY)
            path.lineTo(x + neck, tipY)
            path.lineTo(x + base, y)
            path.close()
            p.shader = LinearGradient(x - base, 0f, x + base, 0f,
                intArrayOf(Color.rgb(0x5A, 0x5E, 0x66), Color.rgb(0xFF, 0xFF, 0xFF), Color.rgb(0xA8, 0xAC, 0xB4), Color.rgb(0x40, 0x44, 0x4A)),
                floatArrayOf(0f, 0.35f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
            p.shader = RadialGradient(x - tipR * 0.35f, tipY - tipR * 0.35f, tipR * 1.3f,
                intArrayOf(Color.WHITE, Color.rgb(0xB0, 0xB4, 0xBC), Color.rgb(0x40, 0x44, 0x4C)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, tipY, tipR, p)
            p.shader = null

            engrave.textSize = u * 0.13f
            c.drawText(label, x, y + u * 1.0f, engraveLight)
        }
    }

    private fun hexagon(x: Float, y: Float, r: Float) {
        path.reset()
        for (k in 0 until 6) {
            val a = PI / 3 * k + PI / 6
            val px = x + r * cos(a).toFloat()
            val py = y + r * sin(a).toFloat()
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
    }

    private inner class Rocker(val x: Float, val y: Float, on: Boolean) : Control() {
        var on = on
        var pos = if (on) 1f else 0f
        val hw = u * 0.62f
        val hh = u * 0.42f

        override fun hit(x: Float, y: Float) = abs(x - this.x) < hw && abs(y - this.y) < hh * 1.2f
        override fun down(x: Float, y: Float) {
            val wantOn = y < this.y
            if (wantOn != on) {
                on = wantOn
                sfx.play("rocker", 1f, 0.95f + (Math.random() * 0.1).toFloat())
                haptics.play(android.os.VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
            }
        }

        override fun update(dt: Float) {
            pos += ((if (on) 1f else 0f) - pos) * min(1f, dt * 28f)
        }

        override fun draw(c: Canvas) {
            // Bezel.
            rect.set(x - hw - u * 0.08f, y - hh - u * 0.08f, x + hw + u * 0.08f, y + hh + u * 0.08f)
            softShadow(c, rect, u * 0.1f, u * 0.06f, u * 0.04f, 140)
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, Color.rgb(0x2A, 0x2A, 0x2E), Color.rgb(0x0C, 0x0C, 0x0E), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.1f, u * 0.1f, p)
            p.shader = null
            rect.set(x - hw, y - hh, x + hw, y + hh)
            p.color = Color.rgb(0x05, 0x05, 0x06)
            c.drawRoundRect(rect, u * 0.06f, u * 0.06f, p)

            // Rocker body: the raised half catches light, the pressed half falls into shadow.
            // When on, the red plastic glows from inside.
            val lit = pos
            val baseRed = mixColor(Color.rgb(0x5A, 0x0C, 0x0C), Color.rgb(0xFF, 0x2A, 0x1E), lit)
            val topShade = lerp(0.55f, 1.15f, 1f - pos)
            val botShade = lerp(1.15f, 0.55f, 1f - pos)
            val mid = y + (pos - 0.5f) * hh * 0.2f
            rect.set(x - hw + u * 0.03f, y - hh + u * 0.03f, x + hw - u * 0.03f, mid)
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, scaleColor(baseRed, topShade * 1.1f), scaleColor(baseRed, topShade * 0.8f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.04f, u * 0.04f, p)
            rect.set(x - hw + u * 0.03f, mid, x + hw - u * 0.03f, y + hh - u * 0.03f)
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, scaleColor(baseRed, botShade * 0.85f), scaleColor(baseRed, botShade * 1.05f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.04f, u * 0.04f, p)
            p.shader = null
            if (lit > 0.05f) {
                p.shader = RadialGradient(x, y, hw * 1.6f, Color.argb((lit * 110).toInt(), 255, 60, 40), Color.argb(0, 255, 60, 40), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, hw * 1.6f, p)
                p.shader = null
            }
            stroke.strokeWidth = dp
            stroke.color = Color.argb(70, 0, 0, 0)
            c.drawLine(x - hw + u * 0.03f, mid, x + hw - u * 0.03f, mid, stroke)

            text.textSize = u * 0.22f
            text.color = Color.argb((140 + 115 * lit).toInt(), 255, 235, 230)
            c.drawText("I", x, y - hh * 0.32f, text)
            text.color = Color.argb(150, 255, 220, 215)
            stroke.color = text.color
            stroke.strokeWidth = u * 0.03f
            c.drawCircle(x, y + hh * 0.48f, u * 0.075f, stroke)
        }
    }

    private inner class Key(val x: Float, val y: Float, val label: String, val color: Int, val ink: Int) : Control() {
        var pressed = false
        var depth = 0f
        val s = u * 0.4f

        override fun hit(x: Float, y: Float) = abs(x - this.x) < s && abs(y - this.y) < s * 1.1f
        override fun down(x: Float, y: Float) {
            pressed = true
            sfx.play("keyDown", 0.95f, 0.96f + (Math.random() * 0.08).toFloat())
            haptics.click(0.75f)
        }

        override fun up() {
            pressed = false
            sfx.play("keyUp", 0.7f, 0.96f + (Math.random() * 0.08).toFloat())
            haptics.tick(0.35f)
        }

        override fun update(dt: Float) {
            depth += ((if (pressed) 1f else 0f) - depth) * min(1f, dt * 40f)
        }

        override fun draw(c: Canvas) {
            val lift = lerp(u * 0.09f, u * 0.025f, depth)
            // Plate hole and shadow.
            rect.set(x - s, y - s + u * 0.04f, x + s, y + s + u * 0.04f)
            softShadow(c, rect, u * 0.08f, lerp(u * 0.08f, u * 0.03f, depth), lerp(u * 0.06f, u * 0.02f, depth), 170)
            // Skirt.
            rect.set(x - s, y - s, x + s, y + s)
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, scaleColor(color, 0.82f), scaleColor(color, 0.55f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.09f, u * 0.09f, p)
            // Top, sculpted with a shallow dish.
            val inset = s * 0.2f
            rect.set(x - s + inset, y - s + inset * 0.6f - lift, x + s - inset, y + s - inset * 1.3f - lift)
            p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, scaleColor(color, 1.08f), scaleColor(color, 0.92f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.07f, u * 0.07f, p)
            p.shader = RadialGradient(rect.centerX(), rect.centerY() + rect.height() * 0.15f, rect.width() * 0.6f,
                intArrayOf(Color.argb(40, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(50, 255, 255, 255)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.07f, u * 0.07f, p)
            p.shader = null
            stroke.strokeWidth = dp
            stroke.color = Color.argb(80, 255, 255, 255)
            c.drawLine(rect.left + u * 0.06f, rect.top + dp, rect.right - u * 0.06f, rect.top + dp, stroke)
            text.textSize = u * 0.24f
            text.color = ink
            c.drawText(label, rect.centerX(), rect.centerY() + text.textSize * 0.36f, text)
        }
    }

    private inner class Knob(val x: Float, val y: Float) : Control() {
        val r = u * 0.66f
        var value = 0f // degrees, -135..135
        var lastAngle = 0f
        var lastDetent = 0

        override fun hit(x: Float, y: Float) = hypot(x - this.x, y - this.y) < r * 1.35f
        override fun down(x: Float, y: Float) { lastAngle = angleAt(x, y) }
        override fun move(x: Float, y: Float) {
            val a = angleAt(x, y)
            var d = a - lastAngle
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            lastAngle = a
            value = (value + d).coerceIn(-135f, 135f)
            val detent = floor((value + 135f) / 15f + 0.5f).toInt()
            if (detent != lastDetent) {
                lastDetent = detent
                sfx.play("detent", 0.8f, 0.9f + detent * 0.012f)
                haptics.tick(0.55f)
            }
        }

        private fun angleAt(px: Float, py: Float) = Math.toDegrees(atan2((py - y).toDouble(), (px - x).toDouble())).toFloat()

        init { lastDetent = 9 }

        override fun draw(c: Canvas) {
            // Scale ticks and a light ring showing the level.
            stroke.strokeCap = Paint.Cap.ROUND
            for (k in 0..18) {
                val a = Math.toRadians((-225.0 + k * 15.0))
                val r0 = r * 1.18f
                val r1 = r * (if (k % 3 == 0) 1.32f else 1.26f)
                stroke.strokeWidth = if (k % 3 == 0) dp * 2f else dp
                stroke.color = Color.argb(150, 210, 215, 220)
                c.drawLine(x + r0 * cos(a).toFloat(), y + r0 * sin(a).toFloat(), x + r1 * cos(a).toFloat(), y + r1 * sin(a).toFloat(), stroke)
            }
            val ringR = r * 1.1f
            rect.set(x - ringR, y - ringR, x + ringR, y + ringR)
            stroke.strokeWidth = u * 0.035f
            stroke.color = Color.argb(160, 10, 12, 14)
            c.drawArc(rect, 135f, 270f, false, stroke)
            stroke.color = Color.rgb(0x40, 0xD8, 0xFF)
            stroke.maskFilter = BlurMaskFilter(u * 0.02f, BlurMaskFilter.Blur.SOLID)
            c.drawArc(rect, 135f, value + 135f, false, stroke)
            stroke.maskFilter = null
            stroke.strokeCap = Paint.Cap.BUTT

            rect.set(x - r, y - r, x + r, y + r)
            softShadow(c, rect, r, u * 0.08f, u * 0.08f, 190)

            // Knurled skirt: ridges rotate with the knob.
            p.color = Color.rgb(0x22, 0x24, 0x28)
            c.drawCircle(x, y, r, p)
            val ridges = 48
            for (k in 0 until ridges) {
                val a = Math.toRadians(value + k * 360.0 / ridges)
                val light = 0.5f + 0.5f * cos(a - Math.toRadians(-135.0)).toFloat()
                stroke.color = mixColor(Color.rgb(0x30, 0x33, 0x38), Color.rgb(0xB8, 0xBC, 0xC4), light)
                stroke.strokeWidth = dp * 1.4f
                c.drawLine(x + r * 0.84f * cos(a).toFloat(), y + r * 0.84f * sin(a).toFloat(), x + r * 0.99f * cos(a).toFloat(), y + r * 0.99f * sin(a).toFloat(), stroke)
            }
            // Spun aluminium face: the conic reflection stays put while the knob turns.
            p.shader = SweepGradient(x, y,
                intArrayOf(Color.rgb(0x9A, 0x9E, 0xA6), Color.rgb(0xF4, 0xF6, 0xF8), Color.rgb(0x80, 0x84, 0x8C), Color.rgb(0xD8, 0xDC, 0xE2), Color.rgb(0x7A, 0x7E, 0x86), Color.rgb(0xEE, 0xF0, 0xF4), Color.rgb(0x9A, 0x9E, 0xA6)),
                null)
            c.drawCircle(x, y, r * 0.8f, p)
            p.shader = RadialGradient(x, y, r * 0.8f, intArrayOf(Color.argb(0, 0, 0, 0), Color.argb(70, 0, 0, 0)), floatArrayOf(0.75f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r * 0.8f, p)
            p.shader = null
            // Pointer groove.
            val a = Math.toRadians(value - 90.0)
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.strokeWidth = u * 0.045f
            stroke.color = Color.rgb(0x1A, 0x1C, 0x20)
            c.drawLine(x + r * 0.25f * cos(a).toFloat(), y + r * 0.25f * sin(a).toFloat(), x + r * 0.7f * cos(a).toFloat(), y + r * 0.7f * sin(a).toFloat(), stroke)
            stroke.strokeCap = Paint.Cap.BUTT
        }
    }

    private inner class BigButton(val x: Float, val y: Float) : Control() {
        val r = u * 0.55f
        var pressed = false
        var depth = 0f

        override fun hit(x: Float, y: Float) = hypot(x - this.x, y - this.y) < r * 1.25f
        override fun down(x: Float, y: Float) {
            pressed = true
            sfx.play("button", 1f, 0.95f + (Math.random() * 0.1).toFloat())
            haptics.thud(1f)
        }
        override fun up() {
            pressed = false
            sfx.play("keyUp", 0.6f, 0.7f)
            haptics.tick(0.4f)
        }
        override fun update(dt: Float) {
            depth += ((if (pressed) 1f else 0f) - depth) * min(1f, dt * 36f)
        }

        override fun draw(c: Canvas) {
            val bezel = r * 1.22f
            rect.set(x - bezel, y - bezel, x + bezel, y + bezel)
            softShadow(c, rect, bezel, u * 0.08f, u * 0.07f, 190)
            p.shader = SweepGradient(x, y,
                intArrayOf(Color.rgb(0x60, 0x64, 0x6C), Color.rgb(0xF0, 0xF2, 0xF6), Color.rgb(0x70, 0x74, 0x7C), Color.rgb(0xE0, 0xE4, 0xEA), Color.rgb(0x60, 0x64, 0x6C)), null)
            c.drawCircle(x, y, bezel, p)
            p.shader = null
            p.color = Color.rgb(0x10, 0x10, 0x12)
            c.drawCircle(x, y, r * 1.04f, p)

            val dr = r * lerp(1f, 0.95f, depth)
            val dy = lerp(-u * 0.04f, u * 0.01f, depth)
            val red = scaleColor(Color.rgb(0xE8, 0x1E, 0x1E), lerp(1f, 0.82f, depth))
            p.shader = RadialGradient(x - dr * 0.25f, y + dy - dr * 0.35f, dr * 1.4f,
                intArrayOf(scaleColor(red, 1.35f), red, scaleColor(red, 0.45f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y + dy, dr, p)
            // Glossy dome highlight.
            p.shader = LinearGradient(0f, y + dy - dr, 0f, y + dy, Color.argb(170, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            c.drawOval(x - dr * 0.6f, y + dy - dr * 0.88f, x + dr * 0.6f, y + dy - dr * 0.12f, p)
            p.shader = null
        }
    }

    private inner class Fader(val x: Float, val y: Float, val half: Float) : Control() {
        var value = 0.35f
        var lastNotch = notchOf(0.35f)
        var grabOffset = 0f

        fun notchOf(v: Float) = (v * 10f).roundToInt()
        fun thumbX() = x - half + value * half * 2f

        override fun hit(x: Float, y: Float) = abs(y - this.y) < u * 0.45f && x > this.x - half - u * 0.3f && x < this.x + half + u * 0.3f
        override fun down(x: Float, y: Float) {
            grabOffset = if (abs(x - thumbX()) < u * 0.3f) thumbX() - x else 0f
            move(x, y)
        }
        override fun move(x: Float, y: Float) {
            value = ((x + grabOffset - (this.x - half)) / (half * 2f)).coerceIn(0f, 1f)
            val n = notchOf(value)
            if (n != lastNotch) {
                lastNotch = n
                sfx.play("notch", 0.7f, 0.85f + n * 0.03f)
                haptics.tick(0.4f)
            }
        }

        override fun draw(c: Canvas) {
            // Notch marks.
            for (k in 0..10) {
                val nx = x - half + k * half * 0.2f
                stroke.strokeWidth = if (k % 5 == 0) dp * 2f else dp
                stroke.color = Color.argb(140, 210, 215, 220)
                val l = if (k % 5 == 0) u * 0.2f else u * 0.13f
                c.drawLine(nx, y - u * 0.28f - l, nx, y - u * 0.28f, stroke)
            }
            // Track with inner shadow and a glowing fill.
            rect.set(x - half, y - u * 0.05f, x + half, y + u * 0.05f)
            p.color = Color.rgb(0x08, 0x09, 0x0B)
            c.drawRoundRect(rect, u * 0.05f, u * 0.05f, p)
            stroke.strokeWidth = dp
            stroke.color = Color.argb(60, 255, 255, 255)
            c.drawLine(rect.left + u * 0.05f, rect.bottom, rect.right - u * 0.05f, rect.bottom, stroke)
            val tx = thumbX()
            rect.set(x - half + u * 0.02f, y - u * 0.022f, tx, y + u * 0.022f)
            p.color = Color.rgb(0x40, 0xD8, 0xFF)
            p.maskFilter = BlurMaskFilter(u * 0.03f, BlurMaskFilter.Blur.SOLID)
            c.drawRoundRect(rect, u * 0.02f, u * 0.02f, p)
            p.maskFilter = null

            // Fader cap.
            rect.set(tx - u * 0.17f, y - u * 0.3f, tx + u * 0.17f, y + u * 0.3f)
            softShadow(c, rect, u * 0.05f, u * 0.06f, u * 0.06f, 200)
            p.shader = LinearGradient(rect.left, 0f, rect.right, 0f,
                intArrayOf(Color.rgb(0x50, 0x54, 0x5C), Color.rgb(0xE8, 0xEA, 0xEE), Color.rgb(0xA0, 0xA4, 0xAC), Color.rgb(0x44, 0x48, 0x50)), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, u * 0.05f, u * 0.05f, p)
            p.shader = null
            for (k in -3..3) {
                val gy = y + k * u * 0.06f
                stroke.strokeWidth = dp
                stroke.color = Color.argb(120, 0, 0, 0)
                c.drawLine(rect.left + u * 0.04f, gy, rect.right - u * 0.04f, gy, stroke)
                stroke.color = Color.argb(90, 255, 255, 255)
                c.drawLine(rect.left + u * 0.04f, gy + dp, rect.right - u * 0.04f, gy + dp, stroke)
            }
            stroke.strokeWidth = dp * 2f
            stroke.color = Color.rgb(0xFF, 0xFF, 0xFF)
            c.drawLine(rect.left + u * 0.02f, y, rect.right - u * 0.02f, y, stroke)
        }
    }

    // ---- touch ---------------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i)
                val y = event.getY(i)
                val c = controls.firstOrNull { it.hit(x, y) && it !in owners.values } ?: return true
                owners[event.getPointerId(i)] = c
                c.down(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) owners[event.getPointerId(i)]?.move(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                owners.remove(event.getPointerId(event.actionIndex))?.up()
            }
            MotionEvent.ACTION_CANCEL -> {
                owners.values.forEach { it.up() }
                owners.clear()
            }
        }
        return true
    }
}
