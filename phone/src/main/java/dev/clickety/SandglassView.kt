package dev.clickety

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An hourglass full of falling sand.
 *
 * The sand is a cellular automaton at roughly one grain per millimetre-fifth: every grain is a
 * cell with its own mineral colour and speed, so streams accelerate, piles keep a realistic
 * angle of repose, and the neck meters grains through a few at a time. Gravity comes from the
 * accelerometer, so tilting the phone tilts the sand; double-tap turns the hourglass over.
 */
class SandglassView(
    context: Context,
    private val haptics: Haptics,
) : FidgetView(context) {

    // ---- grid -------------------------------------------------------------------------------

    private var cell = 3
    private var gw = 0
    private var gh = 0
    private var solid = BooleanArray(0)
    private var grain = IntArray(0) // 0 = empty, else palette index + 1
    private var vel = FloatArray(0)
    private var stamp = IntArray(0)
    private var tick = 0
    private val off = IntArray(8)

    private var pixels = IntArray(0)
    private var sandBitmap: Bitmap? = null
    private val sandSrc = Rect()
    private val sandDst = RectF()
    private val sandPaint = Paint()

    private var stepClock = 0f
    private var rng = 0x2545F491

    // ---- geometry (hourglass space: origin at the centre of the glass) ----------------------

    private var glassH = 0f
    private var halfMax = 0f
    private var neckHalf = 0f
    private var thickness = 0f
    private var capH = 0f
    private var capHalf = 0f
    private var postX = 0f
    private var postR = 0f

    private val outerPath = Path()
    private val innerPath = Path()
    private val ringPath = Path()
    private val postPath = Path()
    private val streaks = ArrayList<Pair<Path, Paint>>()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val innerLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val woodPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capShade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val brassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val postPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val postGrain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 240, 220)
        textAlign = Paint.Align.CENTER
    }
    private val glints = ArrayList<Pair<Float, Float>>()

    // ---- flip animation ----------------------------------------------------------------------

    private var angle = 0f
    private var flipFrom = 0f
    private var flipTo = 0f
    private var flipT = 1f
    private var hintTime = 4f

    private val audio = SandAudio()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            flip(); return true
        }
    })

    // ---- palette -------------------------------------------------------------------------------

    /** Shaded palette: [level * PALETTE + index]. Levels run shadow, body, lit edge, highlight. */
    private val shaded = IntArray(LEVELS.size * PALETTE)

    init {
        val r = java.util.Random(11)
        val base = IntArray(PALETTE)
        for (i in 0 until PALETTE) {
            val p = r.nextFloat()
            val mineral = when {
                p < 0.52f -> Color.rgb(0xD9, 0xBF, 0x93) // quartz-feldspar beige
                p < 0.72f -> Color.rgb(0xEC, 0xDC, 0xBC) // pale quartz
                p < 0.84f -> Color.rgb(0xC6, 0x98, 0x5E) // iron-stained tan
                p < 0.93f -> Color.rgb(0x93, 0x6E, 0x4A) // brown lithics
                p < 0.975f -> Color.rgb(0x4C, 0x3D, 0x30) // dark heavy minerals
                else -> Color.rgb(0xFF, 0xF8, 0xEA) // clear quartz glint
            }
            val jitter = 0.9f + r.nextFloat() * 0.18f
            // Slight warm/cool drift between grains so the mass doesn't read as one flat colour.
            val warm = (r.nextFloat() - 0.5f) * 14f
            val c = scaleColor(mineral, jitter)
            base[i] = Color.rgb(
                (Color.red(c) + warm).toInt().coerceIn(0, 255),
                Color.green(c),
                (Color.blue(c) - warm).toInt().coerceIn(0, 255),
            )
        }
        for (l in LEVELS.indices) for (i in 0 until PALETTE) {
            shaded[l * PALETTE + i] = scaleColor(base[i], LEVELS[l])
        }
    }

    // ---- layout --------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        cell = max(2, (dp * 1.15f).roundToInt())

        // capH = 0.06 glassH; total height 1.12 glassH; total half width 0.385 glassH.
        val fit = min(h * 0.86f / 1.12f, w * 0.47f / 0.385f)
        gh = (fit / cell).toInt()
        glassH = (gh * cell).toFloat()
        halfMax = glassH * 0.26f
        thickness = max(cell * 1.4f, dp * 2.2f)
        neckHalf = cell * 2.3f + thickness
        capH = glassH * 0.06f
        postR = glassH * 0.026f
        postX = halfMax + glassH * 0.065f
        capHalf = postX + postR * 2.2f
        gw = ceil(2f * halfMax / cell).toInt() + 4

        buildGrid()
        buildGlass()
        buildFrame()

        bgPaint.shader = RadialGradient(
            w / 2f, h * 0.45f, max(w, h) * 0.75f,
            intArrayOf(Color.rgb(0x2A, 0x23, 0x1C), Color.rgb(0x12, 0x0F, 0x0C), Color.rgb(0x06, 0x05, 0x04)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        hintPaint.textSize = 15f * dp
    }

    private fun outerHalf(t: Float): Float {
        val s = abs(t - 0.5f) * 2f
        val f = sin(PI * 0.85 * s).pow(0.7).toFloat()
        return neckHalf + (halfMax - neckHalf) * f
    }

    private fun innerHalf(t: Float) = outerHalf(t) - thickness

    private fun buildGrid() {
        val n = gw * gh
        solid = BooleanArray(n)
        grain = IntArray(n)
        vel = FloatArray(n)
        stamp = IntArray(n)
        pixels = IntArray(n)
        sandBitmap?.recycle()
        sandBitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        sandSrc.set(0, 0, gw, gh)
        sandDst.set(-gw * cell / 2f, -glassH / 2f, gw * cell / 2f, glassH / 2f)

        for (j in 0 until gh) {
            val t = (j + 0.5f) / gh
            val limit = innerHalf(t) - cell * 0.35f
            for (i in 0 until gw) {
                val x = (i + 0.5f) * cell - gw * cell / 2f
                solid[j * gw + i] = abs(x) > limit || i == 0 || i == gw - 1 || j == 0 || j == gh - 1
            }
        }
        for (k in 0 until 8) off[k] = DY[k] * gw + DX[k]

        // Pour the top bulb full, settled against the neck as if it had just been turned over.
        var topCells = 0
        for (j in 0 until gh / 2) for (i in 0 until gw) if (!solid[j * gw + i]) topCells++
        var left = (topCells * 0.64f).toInt()
        val r = java.util.Random(7)
        var j = gh / 2 - 1
        while (j > 0 && left > 0) {
            for (i in 0 until gw) {
                val idx = j * gw + i
                if (!solid[idx] && left > 0) {
                    grain[idx] = 1 + r.nextInt(PALETTE)
                    left--
                }
            }
            j--
        }
    }

    private fun buildGlass() {
        val samples = 220
        fun side(path: Path, half: (Float) -> Float, inset: Float) {
            path.reset()
            for (k in 0..samples) {
                val t = k / samples.toFloat()
                val y = -glassH / 2f + t * glassH
                val x = -(half(t) - inset)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (k in samples downTo 0) {
                val t = k / samples.toFloat()
                path.lineTo(half(t) - inset, -glassH / 2f + t * glassH)
            }
            path.close()
        }
        side(outerPath, ::outerHalf, 0f)
        side(innerPath, ::outerHalf, thickness)
        ringPath.reset()
        ringPath.fillType = Path.FillType.EVEN_ODD
        ringPath.addPath(outerPath)
        ringPath.addPath(innerPath)

        // Glass wall: brighter at the silhouette where we look through more glass.
        ringPaint.shader = LinearGradient(
            -halfMax, 0f, halfMax, 0f,
            intArrayOf(
                Color.argb(150, 255, 255, 255), Color.argb(70, 235, 245, 255),
                Color.argb(35, 220, 235, 255), Color.argb(55, 235, 245, 255), Color.argb(110, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP,
        )
        backPaint.shader = LinearGradient(
            -halfMax, 0f, halfMax, 0f,
            intArrayOf(Color.argb(14, 255, 255, 255), Color.argb(4, 255, 255, 255), Color.argb(22, 255, 255, 255)),
            null, Shader.TileMode.CLAMP,
        )
        edgeLine.strokeWidth = 1.3f * dp
        edgeLine.color = Color.argb(150, 255, 255, 255)
        innerLine.strokeWidth = 0.8f * dp
        innerLine.color = Color.argb(70, 255, 255, 255)
        depthPaint.strokeWidth = thickness * 7f
        depthPaint.color = Color.argb(70, 10, 6, 2)
        depthPaint.maskFilter = BlurMaskFilter(thickness * 3f, BlurMaskFilter.Blur.NORMAL)

        // Specular streaks following each bulb's curve, like a window reflected in the glass.
        streaks.clear()
        for (bulb in 0..1) {
            fun t(s: Float) = if (bulb == 0) 0.5f - s / 2f else 0.5f + s / 2f
            fun streak(from: Float, to: Float, across: Float, width: Float, alpha: Int) {
                val p = Path()
                val n = 60
                for (k in 0..n) {
                    val s = from + (to - from) * k / n
                    val tt = t(s)
                    val x = across * innerHalf(tt)
                    val y = -glassH / 2f + tt * glassH
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                val y0 = -glassH / 2f + t(from) * glassH
                val y1 = -glassH / 2f + t(to) * glassH
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = width
                    maskFilter = BlurMaskFilter(width * 0.35f, BlurMaskFilter.Blur.NORMAL)
                    shader = LinearGradient(
                        0f, y0, 0f, y1,
                        intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(alpha, 255, 255, 255), Color.argb(alpha, 255, 255, 255), Color.argb(0, 255, 255, 255)),
                        floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP,
                    )
                }
                streaks += p to paint
            }
            streak(0.2f, 0.88f, -0.74f, halfMax * 0.075f, 120)
            streak(0.3f, 0.8f, -0.56f, halfMax * 0.025f, 70)
            streak(0.25f, 0.85f, 0.84f, halfMax * 0.03f, 50)
            streak(0.05f, 0.18f, -0.5f, halfMax * 0.02f, 90)
        }
        glints.clear()
        for (bulb in 0..1) {
            val tt = if (bulb == 0) 0.5f - 0.36f else 0.5f + 0.62f / 2f
            glints += (-innerHalf(tt) * 0.66f) to (-glassH / 2f + tt * glassH)
        }
        glintPaint.color = Color.argb(220, 255, 255, 255)
        glintPaint.maskFilter = BlurMaskFilter(halfMax * 0.02f, BlurMaskFilter.Blur.NORMAL)
    }

    private fun buildFrame() {
        val wood = woodTexture()
        val shader = BitmapShader(wood, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        val m = Matrix()
        m.setScale(capHalf * 2f / wood.width, capH * 1.6f / wood.height)
        shader.setLocalMatrix(m)
        woodPaint.shader = shader
        capShade.shader = LinearGradient(
            0f, 0f, 0f, capH,
            intArrayOf(Color.argb(70, 255, 220, 180), Color.argb(0, 0, 0, 0), Color.argb(140, 0, 0, 0)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
        )
        val collar = outerHalf(0f) + thickness * 1.5f
        brassPaint.shader = LinearGradient(
            -collar, 0f, collar, 0f,
            intArrayOf(
                Color.rgb(0x5A, 0x3F, 0x14), Color.rgb(0xC9, 0xA2, 0x55), Color.rgb(0xFF, 0xF0, 0xC0),
                Color.rgb(0xB8, 0x91, 0x3F), Color.rgb(0x7A, 0x58, 0x22), Color.rgb(0xD8, 0xB4, 0x6A), Color.rgb(0x4A, 0x32, 0x10),
            ),
            floatArrayOf(0f, 0.18f, 0.3f, 0.5f, 0.7f, 0.85f, 1f), Shader.TileMode.CLAMP,
        )

        // A turned spindle: beads near each end and a ring in the middle.
        postPath.reset()
        val top = -glassH / 2f
        val n = 160
        fun r(t: Float): Float {
            fun bump(c: Float, wdt: Float, a: Float) = a * exp(-((t - c) / wdt).pow(2))
            return postR * (0.72f + bump(0.04f, 0.03f, 0.5f) + bump(0.96f, 0.03f, 0.5f) +
                bump(0.12f, 0.02f, 0.25f) + bump(0.88f, 0.02f, 0.25f) + bump(0.5f, 0.025f, 0.32f))
        }
        for (k in 0..n) {
            val t = k / n.toFloat()
            val y = top + t * glassH
            if (k == 0) postPath.moveTo(-r(t), y) else postPath.lineTo(-r(t), y)
        }
        for (k in n downTo 0) {
            val t = k / n.toFloat()
            postPath.lineTo(r(t), top + t * glassH)
        }
        postPath.close()
        postPaint.shader = LinearGradient(
            -postR * 1.3f, 0f, postR * 1.3f, 0f,
            intArrayOf(
                Color.rgb(0x1E, 0x12, 0x08), Color.rgb(0x5C, 0x3A, 0x20), Color.rgb(0x9C, 0x6E, 0x44),
                Color.rgb(0xC4, 0x92, 0x62), Color.rgb(0x6E, 0x47, 0x28), Color.rgb(0x22, 0x14, 0x09),
            ),
            floatArrayOf(0f, 0.2f, 0.38f, 0.45f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        val grainShader = BitmapShader(wood, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        val gm = Matrix()
        gm.setRotate(90f)
        gm.postScale(postR * 3f / wood.height, glassH / wood.width)
        grainShader.setLocalMatrix(gm)
        postGrain.shader = grainShader
        postGrain.alpha = 70

        shadowPaint.color = Color.argb(160, 0, 0, 0)
        shadowPaint.maskFilter = BlurMaskFilter(capH * 0.8f, BlurMaskFilter.Blur.NORMAL)
    }

    /** Procedural walnut: wavy growth rings plus fine pores. */
    private fun woodTexture(): Bitmap {
        val w = 512
        val h = 96
        val px = IntArray(w * h)
        val r = java.util.Random(3)
        val pores = FloatArray(w * h) { r.nextFloat() }
        val dark = Color.rgb(0x2E, 0x1B, 0x0E)
        val mid = Color.rgb(0x5E, 0x3B, 0x21)
        val light = Color.rgb(0x8A, 0x5C, 0x36)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = x / w.toFloat()
            val fy = y / h.toFloat()
            val warp = sin(fx * 9f + sin(fy * 5f) * 1.5f) * 0.6f + sin(fx * 23f + fy * 3f) * 0.2f
            val ring = sin((fy * 26f + warp * 3f) * PI.toFloat())
            val v = (ring * 0.5f + 0.5f).pow(2.2f)
            var c = if (v < 0.5f) mixColor(dark, mid, v * 2f) else mixColor(mid, light, (v - 0.5f) * 2f)
            val pore = pores[y * w + x]
            if (pore > 0.985f) c = scaleColor(c, 0.7f)
            c = scaleColor(c, 0.94f + pore * 0.1f)
            px[y * w + x] = c
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---- simulation ----------------------------------------------------------------------------

    private fun nextInt(): Int {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return x
    }

    private fun nextFloat() = (nextInt() ushr 8) * (1f / (1 shl 24))

    private fun free(i: Int) = !solid[i] && grain[i] == 0

    private var moved = 0
    private var impacts = 0

    override fun update(dt: Float) {
        hintTime -= dt
        if (flipT < 1f) {
            flipT = min(1f, flipT + dt / FLIP_SECONDS)
            angle = lerp(flipFrom, flipTo, smoothstep(0f, 1f, flipT))
            if (flipT >= 1f) {
                angle = flipTo % (2f * PI.toFloat())
                haptics.thud(0.5f)
            }
        }

        // Gravity in the hourglass' own frame.
        val c = cos(angle)
        val s = sin(angle)
        val gx = gravityX * c + gravityY * s
        val gy = -gravityX * s + gravityY * c

        stepClock += dt
        var steps = 0
        moved = 0
        impacts = 0
        while (stepClock >= STEP && steps < 6) {
            step(gx, gy)
            stepClock -= STEP
            steps++
        }
        if (steps == 6) stepClock = 0f

        val perStep = if (steps > 0) 1f / steps else 0f
        audio.hiss = (min(1f, moved * perStep / 2200f)).pow(0.6f) * 0.85f
        audio.crackle = min(1f, impacts * perStep / 5f)
        if (impacts * perStep > 40f) haptics.lowTick(min(1f, impacts * perStep / 260f), minGapMs = 70)
    }

    private fun step(gx: Float, gy: Float) {
        val g = hypot(gx, gy)
        if (g < 0.35f) return
        tick++
        val chance = min(1f, g / 9.81f * 1.15f)
        val accel = 0.11f * min(1.4f, g / 9.81f)
        val a8 = (atan2(gy, gx) / (PI / 4)).toFloat()
        val k0 = floor(a8).toInt()
        val frac = a8 - k0
        val kA = (k0 % 8 + 8) % 8
        val kB = (kA + 1) % 8

        val rowsDown = gy >= 0f
        val colsRight = gx >= 0f
        for (jj in 0 until gh) {
            val j = if (rowsDown) gh - 1 - jj else jj
            val right = if (abs(gx) > 0.6f) colsRight else (nextInt() and 1) == 0
            val rowBase = j * gw
            for (ii in 0 until gw) {
                val idx = rowBase + if (right) gw - 1 - ii else ii
                val c = grain[idx]
                if (c == 0 || stamp[idx] == tick) continue
                if (chance < 1f && nextFloat() > chance) continue

                val k = if (nextFloat() < frac) kB else kA
                var v = vel[idx] + accel
                if (v > VMAX) v = VMAX
                var n = v.toInt()
                if (nextFloat() < v - n) n++
                if (n < 1) n = 1

                var cur = idx
                var stepped = 0
                val o = off[k]
                while (stepped < n) {
                    val nx = cur + o
                    if (free(nx)) { cur = nx; stepped++ } else break
                }
                if (stepped == 0) {
                    if (v > 1.4f) impacts++
                    val flipSide = (nextInt() and 1) == 0
                    val d1 = off[(k + if (flipSide) 1 else 7) % 8]
                    val d2 = off[(k + if (flipSide) 7 else 1) % 8]
                    if (free(idx + d1)) {
                        cur = idx + d1; v *= 0.55f
                    } else if (free(idx + d2)) {
                        cur = idx + d2; v *= 0.55f
                    } else {
                        // Creep sideways when there is a drop two cells over: this flattens
                        // piles from the automaton's 45° to sand's ~32° angle of repose.
                        val side = off[(k + if (flipSide) 2 else 6) % 8]
                        val beyond = idx + side * 2 + o
                        if (nextFloat() < SLIDE && free(idx + side) && beyond in grain.indices && free(beyond)) {
                            cur = idx + side
                        }
                        v = 0f
                    }
                } else if (stepped < n) {
                    if (v > 1.4f) impacts++
                    v *= 0.35f
                }

                if (cur != idx) {
                    grain[cur] = c
                    grain[idx] = 0
                    vel[cur] = v
                    vel[idx] = 0f
                    stamp[cur] = tick
                    moved++
                } else {
                    vel[idx] = v
                }
            }
        }
    }

    private fun flip() {
        if (flipT < 1f) return
        flipFrom = angle
        flipTo = angle + PI.toFloat()
        flipT = 0f
        haptics.click(0.6f)
    }

    // ---- rendering -----------------------------------------------------------------------------

    private fun paintSand() {
        // Light comes from the screen's upper left whichever way up the glass is.
        val la = atan2(-1f, -1f) - angle
        val lk = ((la / (PI / 4)).roundToInt() % 8 + 8) % 8
        val lo = off[lk]
        val lo2 = lo * 2
        val n = grain.size
        for (idx in 0 until n) {
            val c = grain[idx]
            if (c == 0) {
                pixels[idx] = 0
                continue
            }
            val a = idx + lo
            val b = idx + lo2
            val s = idx - lo
            val level = when {
                grain[a] == 0 && !solid[a] -> 3
                b in 0 until n && grain[b] == 0 && !solid[b] -> 2
                grain[s] == 0 && !solid[s] -> 0
                else -> 1
            }
            pixels[idx] = shaded[level * PALETTE + c - 1]
        }
        sandBitmap?.setPixels(pixels, 0, gw, 0, 0, gw, gh)
    }

    override fun render(canvas: Canvas) {
        canvas.drawPaint(bgPaint)
        val bmp = sandBitmap ?: return
        paintSand()

        val cx = width / 2f
        val cy = height / 2f
        // Lift the glass away from the edges while it turns so it never clips.
        val lift = sin(PI * smoothstep(0f, 1f, flipT)).toFloat()
        val fitSideways = min(width * 0.94f / (glassH + capH * 2f), 1f)
        val scale = lerp(1f, fitSideways, lift)

        // Table shadow stays put; it only shrinks while the glass is lifted.
        canvas.drawOval(
            cx - capHalf * 1.05f * (1f - lift * 0.4f), cy + glassH / 2f + capH * 0.7f,
            cx + capHalf * 1.05f * (1f - lift * 0.4f), cy + glassH / 2f + capH * 1.6f, shadowPaint,
        )

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())

        // Back posts, then the back wall of the glass.
        drawPost(canvas, -postX)
        drawPost(canvas, postX)
        canvas.drawPath(outerPath, backPaint)

        // Sand, crisp grain-for-pixel unless it is mid-rotation.
        sandPaint.isFilterBitmap = flipT < 1f
        canvas.drawBitmap(bmp, sandSrc, sandDst, sandPaint)

        // Curved glass darkens what is seen near its edges.
        canvas.save()
        canvas.clipPath(innerPath)
        canvas.drawPath(innerPath, depthPaint)
        canvas.restore()

        canvas.drawPath(ringPath, ringPaint)
        canvas.drawPath(outerPath, edgeLine)
        canvas.drawPath(innerPath, innerLine)
        for ((p, paint) in streaks) canvas.drawPath(p, paint)
        for ((gx, gy) in glints) canvas.drawCircle(gx, gy, halfMax * 0.022f, glintPaint)

        drawCap(canvas, -glassH / 2f - capH, top = true)
        drawCap(canvas, glassH / 2f, top = false)
        canvas.restore()

        if (hintTime > 0f) {
            hintPaint.alpha = (min(1f, hintTime) * 150).toInt()
            canvas.drawText("Tilt to pour · double-tap to flip", cx, height - 18f * dp, hintPaint)
        }
    }

    private fun drawPost(canvas: Canvas, x: Float) {
        canvas.save()
        canvas.translate(x, 0f)
        canvas.drawPath(postPath, postPaint)
        canvas.drawPath(postPath, postGrain)
        canvas.restore()
    }

    private fun drawCap(canvas: Canvas, y: Float, top: Boolean) {
        val r = capH * 0.22f
        canvas.save()
        canvas.translate(0f, y)
        canvas.drawRoundRect(-capHalf, 0f, capHalf, capH, r, r, woodPaint)
        if (!top) {
            canvas.save()
            canvas.scale(1f, -1f, 0f, capH / 2f)
        }
        canvas.drawRoundRect(-capHalf, 0f, capHalf, capH, r, r, capShade)
        if (!top) canvas.restore()
        // Groove routed around the cap.
        val groove = if (top) capH * 0.3f else capH * 0.7f
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0); strokeWidth = dp }
        canvas.drawLine(-capHalf + r, groove, capHalf - r, groove, line)
        line.color = Color.argb(45, 255, 220, 170)
        canvas.drawLine(-capHalf + r, groove + dp, capHalf - r, groove + dp, line)
        // Brass collar holding the glass.
        val collar = outerHalf(0f) + thickness * 1.5f
        val ch = capH * 0.3f
        val cyTop = if (top) capH - ch * 0.4f else -ch * 0.6f
        canvas.drawRoundRect(-collar, cyTop, collar, cyTop + ch, ch * 0.3f, ch * 0.3f, brassPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return true
    }

    override fun onActiveChanged(active: Boolean) {
        if (active) audio.start() else audio.stop()
    }

    private companion object {
        val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
        val DY = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
        val LEVELS = floatArrayOf(0.7f, 0.9f, 1.02f, 1.14f)
        const val PALETTE = 64
        const val STEP = 1f / 160f
        const val VMAX = 5f
        const val SLIDE = 0.45f
        const val FLIP_SECONDS = 1.2f
    }
}
