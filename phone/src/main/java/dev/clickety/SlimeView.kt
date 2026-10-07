package dev.clickety

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A jelly slime you can poke, grab, stretch with two fingers and fling around.
 *
 * The body is a ring of particles solved with position-based dynamics: shape matching pulls it
 * back towards a circle (with some affine give, so it squashes rather than just rotates), an
 * area constraint keeps its volume, and edge constraints keep the outline smooth. Glitter and air
 * bubbles inside follow the same affine transform, so they squash and swirl with the body.
 */
class SlimeView(
    context: Context,
    private val sfx: Sfx,
    private val haptics: Haptics,
) : FidgetView(context) {

    private val n = 96
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val ox = FloatArray(n)
    private val oy = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val qx = FloatArray(n)
    private val qy = FloatArray(n)
    private val nx = FloatArray(n)
    private val ny = FloatArray(n)
    private var restArea = 0f
    private var restEdge = 0f
    private var radius = 0f
    private var pxPerMetre = 0f
    private var initialised = false

    // Current shape transform (affine + translation) used to place the interior details.
    private var g00 = 1f
    private var g01 = 0f
    private var g10 = 0f
    private var g11 = 1f
    private var cx = 0f
    private var cy = 0f
    private var spin = 0f

    private class Grab(val idx: IntArray, val dx: FloatArray, val dy: FloatArray, val w: FloatArray) {
        var tx = 0f
        var ty = 0f
    }

    private val grabs = HashMap<Int, Grab>()
    private var lastStretch = 0f
    private var lastSquelch = 0L
    private var wallHit = 0f

    private class Fleck(val x: Float, val y: Float, val size: Float, val phase: Float, val color: Int, val angle: Float)
    private class Bubble(val x: Float, val y: Float, val r: Float)

    private val flecks = ArrayList<Fleck>()
    private val bubbles = ArrayList<Bubble>()

    private val colors = intArrayOf(
        Color.rgb(0x7E, 0xE0, 0x2C), // lime
        Color.rgb(0xFF, 0x3D, 0x86), // bubblegum
        Color.rgb(0x2E, 0xB4, 0xFF), // ocean
        Color.rgb(0x9B, 0x5C, 0xFF), // grape
        Color.rgb(0xFF, 0x9E, 0x1B), // mango
    )
    private var colorIndex = 0
    private var color = colors[0]

    private val body = Path()
    private val highlight = Path()
    private val bg = Paint()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depth = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spec = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val specCore = Paint(spec)
    private val hot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fleckPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val swatch = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swatchRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.argb(140, 255, 255, 255)
    }
    private var hintTime = 4f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        pxPerMetre = h * 0.4f
        if (!initialised) {
            // Fixed at first layout: the rest shape, area and solver constants all derive from it.
            radius = min(w, h) * 0.3f
            reset(w / 2f, h * 0.55f)
            initialised = true
        }
        bg.shader = RadialGradient(
            w / 2f, h * 0.4f, max(w, h) * 0.8f,
            intArrayOf(Color.rgb(0x2B, 0x2E, 0x36), Color.rgb(0x16, 0x18, 0x1D), Color.rgb(0x08, 0x09, 0x0B)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        shadow.maskFilter = BlurMaskFilter(radius * 0.14f, BlurMaskFilter.Blur.NORMAL)
        caustic.maskFilter = BlurMaskFilter(radius * 0.2f, BlurMaskFilter.Blur.NORMAL)
        depth.strokeWidth = radius * 0.5f
        depth.maskFilter = BlurMaskFilter(radius * 0.2f, BlurMaskFilter.Blur.NORMAL)
        rim.strokeWidth = 2.2f * dp
        rim.maskFilter = BlurMaskFilter(dp, BlurMaskFilter.Blur.NORMAL)
        spec.strokeWidth = radius * 0.11f
        spec.maskFilter = BlurMaskFilter(radius * 0.05f, BlurMaskFilter.Blur.NORMAL)
        specCore.strokeWidth = radius * 0.035f
        specCore.maskFilter = BlurMaskFilter(radius * 0.008f, BlurMaskFilter.Blur.NORMAL)
        hot.maskFilter = BlurMaskFilter(radius * 0.02f, BlurMaskFilter.Blur.NORMAL)
        bubbleRim.strokeWidth = dp
        hintPaint.textSize = 15f * dp
        swatchRing.strokeWidth = 2f * dp
    }

    private fun reset(cx0: Float, cy0: Float) {
        var area = 0f
        for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            qx[i] = radius * cos(a).toFloat()
            qy[i] = radius * sin(a).toFloat()
            x[i] = cx0 + qx[i]
            y[i] = cy0 + qy[i]
            vx[i] = 0f
            vy[i] = 0f
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += qx[i] * qy[j] - qx[j] * qy[i]
        }
        restArea = area / 2f
        restEdge = hypot(qx[1] - qx[0], qy[1] - qy[0])

        val r = Random(5)
        flecks.clear()
        repeat(90) {
            val rr = sqrt(r.nextFloat()) * radius * 0.88f
            val a = r.nextFloat() * 2f * PI.toFloat()
            val c = when (r.nextInt(4)) {
                0 -> Color.rgb(0xFF, 0xFF, 0xFF)
                1 -> Color.rgb(0xFF, 0xE6, 0x9A)
                2 -> Color.rgb(0xC8, 0xF0, 0xFF)
                else -> Color.rgb(0xFF, 0xC8, 0xF0)
            }
            flecks += Fleck(rr * cos(a), rr * sin(a), radius * (0.012f + r.nextFloat() * 0.018f), r.nextFloat() * 6.28f, c, r.nextFloat() * 180f)
        }
        bubbles.clear()
        repeat(11) {
            val rr = sqrt(r.nextFloat()) * radius * 0.8f
            val a = r.nextFloat() * 2f * PI.toFloat()
            bubbles += Bubble(rr * cos(a), rr * sin(a), radius * (0.015f + r.nextFloat().pow(2) * 0.05f))
        }
    }

    // ---- simulation ----------------------------------------------------------------------------

    override fun update(dt: Float) {
        hintTime -= dt
        val sub = 6
        val h = dt / sub
        val gx = gravityX * pxPerMetre
        val gy = gravityY * pxPerMetre
        wallHit = 0f
        repeat(sub) {
            for (i in 0 until n) {
                vx[i] += gx * h
                vy[i] += gy * h
                ox[i] = x[i]
                oy[i] = y[i]
                x[i] += vx[i] * h
                y[i] += vy[i] * h
            }
            repeat(2) {
                shapeMatch(0.06f)
                edges(0.5f)
                area(1f)
                pullGrabs()
            }
            walls()
            for (i in 0 until n) {
                vx[i] = (x[i] - ox[i]) / h * 0.9985f
                vy[i] = (y[i] - oy[i]) / h * 0.9985f
            }
        }
        computeNormals()
        feedback()
    }

    private fun shapeMatch(stiffness: Float) {
        var sx = 0f
        var sy = 0f
        for (i in 0 until n) { sx += x[i]; sy += y[i] }
        cx = sx / n
        cy = sy / n
        var a00 = 0f
        var a01 = 0f
        var a10 = 0f
        var a11 = 0f
        for (i in 0 until n) {
            val px = x[i] - cx
            val py = y[i] - cy
            a00 += px * qx[i]; a01 += px * qy[i]
            a10 += py * qx[i]; a11 += py * qy[i]
        }
        // Optimal rotation from the polar decomposition of Apq (2D closed form).
        val ang = atan2(a10 - a01, a00 + a11)
        spin = ang
        val c = cos(ang)
        val s = sin(ang)
        // Affine part: Apq * Aqq^-1, where Aqq = (n r² / 2) I for a circle; normalised to unit area.
        val inv = 2f / (n * radius * radius)
        var b00 = a00 * inv
        var b01 = a01 * inv
        var b10 = a10 * inv
        var b11 = a11 * inv
        val det = b00 * b11 - b01 * b10
        if (det > 1e-4f) {
            val k = 1f / sqrt(det)
            b00 *= k; b01 *= k; b10 *= k; b11 *= k
        } else {
            b00 = c; b01 = -s; b10 = s; b11 = c
        }
        val beta = 0.4f
        g00 = lerp(c, b00, beta); g01 = lerp(-s, b01, beta)
        g10 = lerp(s, b10, beta); g11 = lerp(c, b11, beta)
        for (i in 0 until n) {
            val tx = g00 * qx[i] + g01 * qy[i] + cx
            val ty = g10 * qx[i] + g11 * qy[i] + cy
            x[i] += (tx - x[i]) * stiffness
            y[i] += (ty - y[i]) * stiffness
        }
    }

    private fun edges(stiffness: Float) {
        for (i in 0 until n) {
            val j = (i + 1) % n
            val dx = x[j] - x[i]
            val dy = y[j] - y[i]
            val d = hypot(dx, dy)
            if (d < 1e-4f) continue
            val corr = (d - restEdge) / d * 0.5f * stiffness
            x[i] += dx * corr; y[i] += dy * corr
            x[j] -= dx * corr; y[j] -= dy * corr
        }
    }

    private fun area(stiffness: Float) {
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += x[i] * y[j] - x[j] * y[i]
        }
        a /= 2f
        var sum = 0f
        for (i in 0 until n) {
            val p = (i + n - 1) % n
            val q = (i + 1) % n
            val gx = 0.5f * (y[q] - y[p])
            val gy = 0.5f * (x[p] - x[q])
            nx[i] = gx; ny[i] = gy
            sum += gx * gx + gy * gy
        }
        if (sum < 1e-6f) return
        val lambda = -(a - restArea) / sum * stiffness
        for (i in 0 until n) {
            x[i] += nx[i] * lambda
            y[i] += ny[i] * lambda
        }
    }

    private fun pullGrabs() {
        for (g in grabs.values) {
            for (k in g.idx.indices) {
                val i = g.idx[k]
                val w = g.w[k] * 0.45f
                x[i] += (g.tx + g.dx[k] - x[i]) * w
                y[i] += (g.ty + g.dy[k] - y[i]) * w
            }
        }
    }

    private fun walls() {
        val m = 4f * dp
        val r = width - m
        val b = height - m
        for (i in 0 until n) {
            var hit = 0f
            if (x[i] < m) { hit = max(hit, (ox[i] - x[i])); x[i] = m; y[i] = lerp(y[i], oy[i], 0.25f) }
            if (x[i] > r) { hit = max(hit, (x[i] - ox[i])); x[i] = r; y[i] = lerp(y[i], oy[i], 0.25f) }
            if (y[i] < m) { hit = max(hit, (oy[i] - y[i])); y[i] = m; x[i] = lerp(x[i], ox[i], 0.25f) }
            if (y[i] > b) { hit = max(hit, (y[i] - oy[i])); y[i] = b; x[i] = lerp(x[i], ox[i], 0.25f) }
            if (hit > wallHit) wallHit = hit
        }
    }

    private fun computeNormals() {
        for (i in 0 until n) {
            val p = (i + n - 1) % n
            val q = (i + 1) % n
            var ex = y[q] - y[p]
            var ey = x[p] - x[q]
            val l = hypot(ex, ey)
            if (l > 1e-4f) { ex /= l; ey /= l }
            if ((x[i] - cx) * ex + (y[i] - cy) * ey < 0f) { ex = -ex; ey = -ey }
            nx[i] = ex
            ny[i] = ey
        }
    }

    private fun feedback() {
        // Wall impacts: px per substep → a wet slap.
        val impact = wallHit / (radius * 0.01f)
        if (impact > 1f) {
            squelch(min(1f, impact / 6f), 0.8f)
            haptics.thud(min(1f, impact / 8f), minGapMs = 90)
        }
        // Stretching while held: squish noises and a soft rumble as the outline lengthens.
        var perimeter = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            perimeter += hypot(x[j] - x[i], y[j] - y[i])
        }
        val stretch = perimeter / (restEdge * n)
        val rate = abs(stretch - lastStretch)
        lastStretch = stretch
        if (grabs.isNotEmpty() && rate > 0.004f) {
            squelch(min(1f, rate * 60f), 1f + (stretch - 1f) * 0.6f)
            haptics.lowTick(min(1f, rate * 80f), minGapMs = 60)
        }
    }

    private fun squelch(volume: Float, rate: Float) {
        val now = System.currentTimeMillis()
        if (now - lastSquelch < 110) return
        lastSquelch = now
        sfx.play("squelch${Random.nextInt(3)}", 0.25f + volume * 0.75f, rate * (0.9f + Random.nextFloat() * 0.2f))
    }

    // ---- rendering -----------------------------------------------------------------------------

    private fun buildBody() {
        body.reset()
        body.moveTo(x[0], y[0])
        for (i in 0 until n) {
            val p0 = (i + n - 1) % n
            val p2 = (i + 1) % n
            val p3 = (i + 2) % n
            body.cubicTo(
                x[i] + (x[p2] - x[p0]) / 6f, y[i] + (y[p2] - y[p0]) / 6f,
                x[p2] - (x[p3] - x[i]) / 6f, y[p2] - (y[p3] - y[i]) / 6f,
                x[p2], y[p2],
            )
        }
        body.close()
    }

    private fun tx(px: Float, py: Float) = g00 * px + g01 * py + cx
    private fun ty(px: Float, py: Float) = g10 * px + g11 * py + cy

    override fun render(canvas: Canvas) {
        canvas.drawPaint(bg)
        if (!initialised) return
        buildBody()
        val r = radius
        val light = color
        val bright = mixColor(color, Color.WHITE, 0.45f)
        val deep = scaleColor(mixColor(color, Color.BLACK, 0.35f), 0.9f)

        // Contact shadow and the coloured light the jelly focuses onto the table.
        canvas.save()
        canvas.translate(r * 0.07f, r * 0.11f)
        shadow.color = Color.argb(150, 0, 0, 0)
        canvas.drawPath(body, shadow)
        canvas.restore()
        canvas.save()
        canvas.translate(r * 0.16f, r * 0.2f)
        caustic.color = withAlpha(bright, 70)
        canvas.drawPath(body, caustic)
        canvas.restore()

        // Body: lighter where it is thin and lit, deeper towards the rim.
        fill.shader = RadialGradient(
            tx(-r * 0.2f, -r * 0.25f), ty(-r * 0.2f, -r * 0.25f), r * 1.35f,
            intArrayOf(withAlpha(bright, 225), withAlpha(light, 215), withAlpha(deep, 235)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(body, fill)

        canvas.save()
        canvas.clipPath(body)
        // Thicker jelly absorbs more light at the edges.
        depth.color = withAlpha(scaleColor(deep, 0.6f), 120)
        canvas.drawPath(body, depth)

        // Light passing through focuses into a glow on the far (lower right) side.
        val gxp = tx(r * 0.42f, r * 0.45f)
        val gyp = ty(r * 0.42f, r * 0.45f)
        glow.shader = RadialGradient(gxp, gyp, r * 0.6f,
            intArrayOf(withAlpha(mixColor(color, Color.WHITE, 0.6f), 150), withAlpha(bright, 0)),
            null, Shader.TileMode.CLAMP)
        canvas.drawCircle(gxp, gyp, r * 0.6f, glow)

        // Suspended glitter: flakes catch the light as the jelly turns and wobbles.
        val tiltPhase = spin * 3f + gravityX * 0.25f
        for (f in flecks) {
            val px = tx(f.x, f.y)
            val py = ty(f.x, f.y)
            val sparkle = abs(cos(tiltPhase + f.phase)).pow(14f)
            fleckPaint.color = withAlpha(f.color, (60 + 195 * sparkle).toInt())
            canvas.save()
            canvas.rotate(f.angle + Math.toDegrees(spin.toDouble()).toFloat(), px, py)
            canvas.drawRect(px - f.size, py - f.size * 0.45f, px + f.size, py + f.size * 0.45f, fleckPaint)
            canvas.restore()
            if (sparkle > 0.6f) {
                fleckPaint.color = withAlpha(Color.WHITE, (255 * (sparkle - 0.6f) / 0.4f).toInt())
                fleckPaint.strokeWidth = dp * 0.8f
                val s = f.size * 3.2f
                canvas.drawLine(px - s, py, px + s, py, fleckPaint)
                canvas.drawLine(px, py - s, px, py + s, fleckPaint)
            }
        }
        // Trapped air bubbles: clear centres, bright rims, a tiny highlight.
        for (b in bubbles) {
            val px = tx(b.x, b.y)
            val py = ty(b.x, b.y)
            bubblePaint.shader = RadialGradient(px, py, b.r,
                intArrayOf(withAlpha(bright, 30), withAlpha(bright, 60), withAlpha(Color.WHITE, 140)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(px, py, b.r, bubblePaint)
            bubbleRim.color = withAlpha(deep, 120)
            canvas.drawArc(px - b.r, py - b.r, px + b.r, py + b.r, 20f, 140f, false, bubbleRim)
            bubblePaint.shader = null
            bubblePaint.color = Color.argb(230, 255, 255, 255)
            canvas.drawCircle(px - b.r * 0.35f, py - b.r * 0.35f, b.r * 0.22f, bubblePaint)
        }
        canvas.restore()

        // Fresnel rim.
        rim.color = withAlpha(mixColor(color, Color.WHITE, 0.7f), 120)
        canvas.drawPath(body, rim)

        // Specular band along the edge facing the light (upper left), set slightly inside.
        val lx = -0.6f
        val ly = -0.8f
        var best = -1
        var bestLen = 0
        var run = 0
        var start = 0
        for (k in 0 until n * 2) {
            val i = k % n
            if (nx[i] * lx + ny[i] * ly > 0.55f) {
                if (run == 0) start = k
                run++
                if (run > bestLen && run <= n) { bestLen = run; best = start }
            } else run = 0
        }
        if (best >= 0 && bestLen > 3) {
            highlight.reset()
            val inset = r * 0.11f
            for (k in 0 until bestLen) {
                val i = (best + k) % n
                val hx = x[i] - nx[i] * inset
                val hy = y[i] - ny[i] * inset
                if (k == 0) highlight.moveTo(hx, hy) else highlight.lineTo(hx, hy)
            }
            spec.color = Color.argb(110, 255, 255, 255)
            canvas.drawPath(highlight, spec)
            specCore.color = Color.argb(220, 255, 255, 255)
            canvas.drawPath(highlight, specCore)
        }
        // Hotspot and a couple of tiny secondary reflections.
        val hx = tx(-r * 0.38f, -r * 0.42f)
        val hy = ty(-r * 0.38f, -r * 0.42f)
        hot.color = Color.argb(235, 255, 255, 255)
        canvas.save()
        canvas.rotate(-35f + Math.toDegrees(spin.toDouble()).toFloat() * 0.3f, hx, hy)
        canvas.drawOval(hx - r * 0.13f, hy - r * 0.06f, hx + r * 0.13f, hy + r * 0.06f, hot)
        canvas.restore()
        hot.color = Color.argb(200, 255, 255, 255)
        canvas.drawCircle(tx(-r * 0.15f, -r * 0.55f), ty(-r * 0.15f, -r * 0.55f), r * 0.025f, hot)
        canvas.drawCircle(tx(r * 0.5f, r * 0.3f), ty(r * 0.5f, r * 0.3f), r * 0.018f, hot)

        drawSwatches(canvas)
        if (hintTime > 0f) {
            hintPaint.alpha = (min(1f, hintTime) * 140).toInt()
            canvas.drawText("Poke, grab, stretch with two fingers", width / 2f, height - 18f * dp, hintPaint)
        }
    }

    private fun swatchCenter(i: Int): Pair<Float, Float> {
        val s = 34f * dp
        val total = s * (colors.size - 1)
        return (width / 2f - total / 2f + i * s) to 28f * dp
    }

    private fun drawSwatches(canvas: Canvas) {
        for (i in colors.indices) {
            val (sx, sy) = swatchCenter(i)
            swatch.shader = RadialGradient(sx - 3 * dp, sy - 3 * dp, 12f * dp,
                mixColor(colors[i], Color.WHITE, 0.4f), scaleColor(colors[i], 0.7f), Shader.TileMode.CLAMP)
            canvas.drawCircle(sx, sy, 10f * dp, swatch)
            if (i == colorIndex) {
                swatchRing.color = Color.argb(200, 255, 255, 255)
                canvas.drawCircle(sx, sy, 14f * dp, swatchRing)
            }
        }
    }

    // ---- touch ---------------------------------------------------------------------------------

    private fun inside(px: Float, py: Float): Boolean {
        var c = false
        var j = n - 1
        for (i in 0 until n) {
            if ((y[i] > py) != (y[j] > py) && px < (x[j] - x[i]) * (py - y[i]) / (y[j] - y[i]) + x[i]) c = !c
            j = i
        }
        return c
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val px = event.getX(i)
                val py = event.getY(i)
                for (k in colors.indices) {
                    val (sx, sy) = swatchCenter(k)
                    if (hypot(px - sx, py - sy) < 18f * dp) {
                        colorIndex = k
                        color = colors[k]
                        sfx.play("pop", 0.8f)
                        haptics.tick(0.5f)
                        return true
                    }
                }
                grab(event.getPointerId(i), px, py)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    grabs[event.getPointerId(i)]?.let { it.tx = event.getX(i); it.ty = event.getY(i) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (grabs.remove(event.getPointerId(event.actionIndex)) != null) {
                    squelch(0.6f, 1.25f)
                    haptics.lowTick(0.6f)
                }
            }
            MotionEvent.ACTION_CANCEL -> grabs.clear()
        }
        return true
    }

    private fun grab(id: Int, px: Float, py: Float) {
        val within = inside(px, py)
        var nearest = Float.MAX_VALUE
        for (i in 0 until n) nearest = min(nearest, hypot(x[i] - px, y[i] - py))
        if (!within && nearest > radius * 0.25f) return

        // Grab a patch of the outline around the finger; inside the body grab wider so the
        // whole lump follows, near the edge pinch a smaller bit.
        val reach = if (within) radius * 0.75f else radius * 0.45f
        val idx = ArrayList<Int>()
        val ws = ArrayList<Float>()
        for (i in 0 until n) {
            val d = hypot(x[i] - px, y[i] - py)
            val limit = reach + nearest
            if (d < limit) {
                idx += i
                ws += (1f - d / limit).pow(0.7f)
            }
        }
        if (idx.isEmpty()) return
        val g = Grab(
            idx.toIntArray(),
            FloatArray(idx.size) { x[idx[it]] - px },
            FloatArray(idx.size) { y[idx[it]] - py },
            ws.toFloatArray(),
        )
        g.tx = px
        g.ty = py
        grabs[id] = g
        squelch(0.5f, 0.85f)
        haptics.lowTick(0.7f)
    }
}
