package dev.cascade.core

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.*

/** Physics and vertex generation run on a worker thread one frame ahead of drawing, and all
 * beads are drawn from one texture atlas in a single drawVertices call. */
class BeadView(context: Context, val wear: Boolean, private val feedback: Feedback, private val toggle: (String) -> Unit) : View(context) {
    var sim: BeadSim? = null; private set
    @Volatile var accelerationX = 0f
    @Volatile var accelerationY = 0f
    /** Tray angular velocity and acceleration in screen coordinates (rad/s, rad/s²). */
    @Volatile var spin = 0f
    @Volatile var spinAccel = 0f
    @Volatile var floorGravity = 2.15f
    /** Highlight offset from device tilt, -1..1 on each axis. */
    @Volatile var lightX = 0f
    @Volatile var lightY = 0f
    private var pendingJolt = 0f
    val minCount get() = if (wear) 100 else 100
    val maxCount get() = if (wear) 600 else 2000
    var beadCount = if (wear) 350 else 900
        set(value) { val v = value.coerceIn(minCount, maxCount); if (v == field) return; field = v; settle(); sim?.setCount(field); rebuild() }
    /** 0 small, 1 medium, 2 large. */
    var beadSize = 1
        set(value) { val v = value.coerceIn(0, 2); if (v == field) return; field = v; settle(); if (width > 0) create(width, height) }
    var theme = 0
        set(value) { if (value == field) return; field = value; atlas?.let { useAtlas(BeadSprites(it.radius, field)) }; invalidate() }
    var trails = !wear
        set(value) { if (value == field) return; field = value; rebuild() }
    var dynamicLight = true
    var showStats = false
        set(value) { field = value; invalidate() }
    var iterations = 6
        set(value) { field = value; sim?.iterations = value }
    var restitution = .38f
        set(value) { field = value; sim?.restitution = value }
    var friction = .32f
        set(value) { field = value; sim?.friction = value }
    var highRefresh = true
        set(value) { field = value; requestRate() }
    var cornerPixels = 0f
        set(value) { field = value; sim?.corner = if (width > 0 && value > 0f) value / width else .075f; updateClip() }
    var running = false
        set(value) {
            field = value; lastFrame = 0L; accumulator = 0.0; settle()
            if (value) { requestRate(); postInvalidateOnAnimation() }
            else { sim?.fingerOn?.fill(false); pointerIds.fill(-1); uiOn.fill(false) }
        }
    private val beadRadius get() = (if (wear) floatArrayOf(.013f, .016f, .02f) else floatArrayOf(.0105f, .0135f, .0175f))[beadSize]
    private var atlas: BeadSprites? = null
    private var lastFrame = 0L
    private var accumulator = 0.0
    private val atlasPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val floor = Paint().apply { color = 0xff020409.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val stats = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffb8c8d8.toInt(); textAlign = Paint.Align.LEFT }
    private val clip = Path()
    private val rect = RectF()
    private var hint = ""
    private var hintUntil = 0L

    // Touch state lives on the UI thread and is handed to the worker as a snapshot.
    private val pointerIds = IntArray(10) { -1 }
    private val uiOn = BooleanArray(10); private val uiSeq = IntArray(10)
    private val uiX = FloatArray(10); private val uiY = FloatArray(10)
    private val jobOn = BooleanArray(10); private val jobSeq = IntArray(10)
    private val jobX = FloatArray(10); private val jobY = FloatArray(10)
    private val seenSeq = IntArray(10)
    private var jobSteps = 0; private var jobAx = 0f; private var jobAy = 0f
    private var jobSpin = 0f; private var jobSpinAccel = 0f; private var jobJolt = 0f
    private var jobLightX = 0f; private var jobLightY = 0f

    private class Frame(capacity: Int) {
        val verts = FloatArray(capacity * 32); val texs = FloatArray(capacity * 32)
        var floats = 0; var indices = 0; var awake = 0
    }
    private var frames = arrayOf(Frame(1), Frame(1))
    private var indices = ShortArray(0)
    private var front = 0
    private var back = 1
    private var job: Future<*>? = null
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY); r.run() }, "cascade-physics")
    }
    @Volatile private var physicsNanos = 0L
    private var fpsFrames = 0; private var fpsAt = 0L; private var fps = 0

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { if (wear) toggle("sound"); return true }
        override fun onLongPress(e: MotionEvent) { if (wear) toggle("haptics") }
    })
    init { isFocusable = true; isFocusableInTouchMode = true }

    fun showHint(value: String) { hint = value; hintUntil = SystemClock.uptimeMillis() + 4000; invalidate() }

    /** Adds a vertical jolt (beads hopping off the floor) to the next physics frame. */
    fun jolt(strength: Float) { pendingJolt = max(pendingJolt, strength); invalidate() }

    /** Waits for the in-flight physics frame so the simulation can be changed safely. */
    private fun settle() { job?.let { it.get(); job = null; front = back } }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        settle(); create(w, h)
        text.textSize = if (wear) w * .052f else 15f * resources.displayMetrics.scaledDensity
        stats.textSize = text.textSize * .8f
    }

    private fun create(w: Int, h: Int) {
        val capacity = maxCount
        sim = BeadSim(h.toFloat() / w, wear && resources.configuration.isScreenRound, beadRadius, capacity).also {
            it.corner = if (cornerPixels > 0) cornerPixels / w else .075f
            it.iterations = iterations; it.restitution = restitution; it.friction = friction
            it.setCount(beadCount)
        }
        if (frames[0].verts.size < capacity * 32) {
            frames = arrayOf(Frame(capacity), Frame(capacity))
            // Four quads per bead at most: trail, body, swirl and glint.
            indices = ShortArray(capacity * 4 * 6)
            for (q in 0 until capacity * 4) {
                val v = q * 4; val k = q * 6
                indices[k] = v.toShort(); indices[k + 1] = (v + 1).toShort(); indices[k + 2] = (v + 2).toShort()
                indices[k + 3] = v.toShort(); indices[k + 4] = (v + 2).toShort(); indices[k + 5] = (v + 3).toShort()
            }
        }
        useAtlas(BeadSprites(w * beadRadius, theme))
        updateClip(); rebuild()
    }

    private fun useAtlas(a: BeadSprites) { atlas = a; atlasPaint.shader = a.shader }

    private fun updateClip() {
        val s = sim ?: return
        clip.rewind()
        if (s.round) clip.addCircle(width * .5f, height * .5f, width * .5f, Path.Direction.CW)
        else { rect.set(0f, 0f, width.toFloat(), height.toFloat()); clip.addRoundRect(rect, s.corner * width, s.corner * width, Path.Direction.CW) }
    }

    private fun rebuild() {
        settle()
        if (sim != null && atlas != null) build(frames[front], jobLightX, jobLightY)
        invalidate()
    }

    private fun requestRate() {
        if (Build.VERSION.SDK_INT >= 35) requestedFrameRate =
            if (highRefresh) REQUESTED_FRAME_RATE_CATEGORY_HIGH else REQUESTED_FRAME_RATE_CATEGORY_NORMAL
    }

    private val physics = Runnable {
        val s = sim ?: return@Runnable
        val start = System.nanoTime()
        s.resetEvents()
        for (f in 0..9) {
            if (!jobOn[f]) { s.fingerOn[f] = false; continue }
            if (!s.fingerOn[f] || seenSeq[f] != jobSeq[f]) {
                seenSeq[f] = jobSeq[f]; s.fingerOn[f] = true
                s.fingerX[f] = jobX[f]; s.fingerY[f] = jobY[f]; s.wakeAll()
            }
        }
        if (jobJolt > 0f) s.jolt(jobJolt)
        repeat(jobSteps) {
            for (f in 0..9) if (s.fingerOn[f]) {
                val dx = jobX[f] - s.fingerX[f]; val dy = jobY[f] - s.fingerY[f]
                val distance = sqrt(dx * dx + dy * dy)
                val scale = min(1f, .025f / max(.00001f, distance))
                s.fingerVx[f] = dx * scale / BeadSim.DT
                s.fingerVy[f] = dy * scale / BeadSim.DT
                s.fingerX[f] += dx * scale; s.fingerY[f] += dy * scale
            }
            s.step(jobAx, jobAy, jobSpin, jobSpinAccel)
        }
        build(frames[back], jobLightX, jobLightY)
        feedback.onFrame(s.peakImpulse, s.wallImpulse, s.scatterEnergy, s.impacts, s.impactRadius / s.radius, s.impactX, s.fingerImpulse)
        physicsNanos = System.nanoTime() - start
    }

    private fun build(f: Frame, lx: Float, ly: Float) {
        val s = sim ?: return; val a = atlas ?: return
        val w = width.toFloat()
        target = f; cursor = 0; cellSize = a.size.toFloat()
        val n = s.count
        if (trails) for (i in 0 until n) {
            if (s.glow[i] <= .45f) continue
            // A soft streak behind fast beads, stretched along their velocity.
            val px = s.x[i] * w; val py = s.y[i] * w
            val sx = s.vx[i] * w * .011f; val sy = s.vy[i] * w * .011f
            val len = sqrt(sx * sx + sy * sy)
            if (len < 1f) continue
            val ux = sx / len; val uy = sy / len
            val hw = s.r[i] * w * 1.15f
            val bx = px - sx - ux * hw; val by = py - sy - uy * hw
            val fx = px + ux * hw * .4f; val fy = py + uy * hw * .4f
            quad(bx - uy * hw, by + ux * hw, fx - uy * hw, fy + ux * hw, fx + uy * hw, fy - ux * hw, bx + uy * hw, by - ux * hw, BeadSprites.TRAIL)
        }
        val glintX = -.38f + lx * .2f; val glintY = -.43f + ly * .2f
        for (i in 0 until n) {
            val px = s.x[i] * w; val py = s.y[i] * w
            val rp = s.r[i] * w
            val half = a.center * rp / a.radius
            val light = (s.glow[i] * 7f).roundToInt().coerceIn(0, 7)
            quad(px - half, py - half, px + half, py - half, px + half, py + half, px - half, py + half, light * 4 + s.tint[i])
            val angle = s.angle[i] * (PI.toFloat() / 180f)
            val c = cos(angle) * half; val sn = sin(angle) * half
            quad(px - c + sn, py - sn - c, px + c + sn, py + sn - c, px + c - sn, py + sn + c, px - c - sn, py - sn + c, BeadSprites.SWIRL)
            val rr = rp * .96f
            val gx = px + glintX * rr; val gy = py + glintY * rr; val gh = rr * .4f
            quad(gx - gh, gy - gh, gx + gh, gy - gh, gx + gh, gy + gh, gx - gh, gy + gh, BeadSprites.GLINT)
        }
        f.floats = cursor; f.indices = cursor / 8 * 6; f.awake = s.awakeCount
    }

    private var target = frames[0]; private var cursor = 0; private var cellSize = 0f

    private fun quad(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, k: Int) {
        val a = atlas ?: return
        val v = target.verts; val t = target.texs; val p = cursor; val cell = cellSize
        val u = a.cellX(k); val vv = a.cellY(k)
        v[p] = x0; v[p + 1] = y0; v[p + 2] = x1; v[p + 3] = y1
        v[p + 4] = x2; v[p + 5] = y2; v[p + 6] = x3; v[p + 7] = y3
        t[p] = u; t[p + 1] = vv; t[p + 2] = u + cell; t[p + 3] = vv
        t[p + 4] = u + cell; t[p + 5] = vv + cell; t[p + 6] = u; t[p + 7] = vv + cell
        cursor = p + 8
    }

    override fun onDraw(canvas: Canvas) {
        val s = sim ?: return
        settle()
        val f = frames[front]
        if (running) {
            val now = System.nanoTime()
            if (lastFrame != 0L) accumulator += ((now - lastFrame) / 1e9).coerceAtMost(.1)
            lastFrame = now
            // Cap catch-up work so a slow frame never snowballs into a slower one.
            val steps = min(MAX_STEPS, (accumulator / BeadSim.DT).toInt())
            accumulator -= steps * BeadSim.DT
            if (steps == MAX_STEPS) accumulator = min(accumulator, BeadSim.DT.toDouble())
            if (steps > 0 || pendingJolt > 0f) {
                jobSteps = steps; jobAx = accelerationX; jobAy = accelerationY
                jobSpin = spin; jobSpinAccel = spinAccel; jobJolt = pendingJolt; pendingJolt = 0f
                s.floorGravity = floorGravity
                if (dynamicLight) { jobLightX = lightX; jobLightY = lightY } else { jobLightX = 0f; jobLightY = 0f }
                for (k in 0..9) { jobOn[k] = uiOn[k]; jobSeq[k] = uiSeq[k]; jobX[k] = uiX[k]; jobY[k] = uiY[k] }
                back = 1 - front
                job = worker.submit(physics)
            }
        }
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.clipPath(clip)
        // A very faint cool floor stays OLED-friendly.
        canvas.drawPaint(floor)
        if (f.floats > 0) canvas.drawVertices(Canvas.VertexMode.TRIANGLES, f.floats, f.verts, 0, f.texs, 0,
            null, 0, indices, 0, f.indices, atlasPaint)
        canvas.restore()
        val remaining = hintUntil - SystemClock.uptimeMillis()
        if (remaining > 0) {
            text.alpha = (min(1f, remaining / 600f) * 230).toInt()
            canvas.drawText(hint, width * .5f, height * .24f, text)
        }
        if (showStats) {
            fpsFrames++
            val ms = SystemClock.uptimeMillis()
            if (ms - fpsAt >= 1000) { fps = (fpsFrames * 1000 / max(1L, ms - fpsAt)).toInt(); fpsFrames = 0; fpsAt = ms }
            canvas.drawText("$fps fps · physics ${"%.1f".format(physicsNanos / 1e6)} ms · ${s.count} beads",
                width * .05f, height - stats.textSize * 2.5f, stats)
        }
        if (running) {
            if (f.awake == 0 && remaining <= 0 && !uiOn.any { it } && !showStats) postInvalidateDelayed(100)
            else postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (wear) gestures.onTouchEvent(event)
        if (sim == null) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val slot = pointerIds.indexOf(-1)
                if (slot >= 0) {
                    val idx = event.actionIndex
                    pointerIds[slot] = event.getPointerId(idx)
                    uiX[slot] = event.getX(idx) / width; uiY[slot] = event.getY(idx) / width
                    uiOn[slot] = true; uiSeq[slot]++
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val slot = pointerIds.indexOf(event.getPointerId(event.actionIndex))
                if (slot >= 0) { pointerIds[slot] = -1; uiOn[slot] = false }
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pointerIds.fill(-1); uiOn.fill(false) }
        }
        for (f in 0..9) if (pointerIds[f] >= 0) {
            val index = event.findPointerIndex(pointerIds[f])
            if (index >= 0) { uiX[f] = event.getX(index) / width; uiY[f] = event.getY(index) / width }
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() { settle(); worker.shutdown(); super.onDetachedFromWindow() }

    private companion object { const val MAX_STEPS = 6 }
}
