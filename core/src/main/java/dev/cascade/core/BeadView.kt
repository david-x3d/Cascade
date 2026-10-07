package dev.cascade.core

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.*
import kotlin.math.*

class BeadView(context: Context, val wear: Boolean, private val feedback: Feedback, private val toggle: (String) -> Unit) : View(context) {
    var sim: BeadSim? = null; private set
    var accelerationX = 0f
    var accelerationY = 0f
    var beadCount = if (wear) 350 else 900
        set(value) { field = value.coerceIn(if (wear) 250 else 600, if (wear) 450 else 1200); sim?.setCount(field); invalidate() }
    var theme = 0
        set(value) { field = value; if (width > 0) atlas = BeadSprites(width * beadRadius, field); invalidate() }
    var cornerPixels = 0f
        set(value) { field = value; sim?.corner = if (width > 0 && value > 0f) value / width else .075f }
    var running = false
        set(value) { field = value; lastFrame = 0L; accumulator = 0.0; if (value) postInvalidateOnAnimation() else { sim?.fingerOn?.fill(false); pointerIds.fill(-1) } }
    private val beadRadius get() = if (wear) .016f else .0135f
    private var atlas: BeadSprites? = null
    private var lastFrame = 0L
    private var accumulator = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val clip = Path()
    private var hint = ""
    private var hintUntil = 0L
    private val pointerIds = IntArray(10) { -1 }
    private val targetX = FloatArray(10); private val targetY = FloatArray(10)
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { if (wear) toggle("sound"); return true }
        override fun onLongPress(e: MotionEvent) { if (wear) toggle("haptics") }
    })
    init { isFocusable = true; isFocusableInTouchMode = true }
    fun showHint(value: String) { hint = value; hintUntil = SystemClock.uptimeMillis() + 4000; invalidate() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        sim = BeadSim(h.toFloat()/w, wear && resources.configuration.isScreenRound, beadRadius, if (wear) 450 else 1200).also {
            it.corner = if (cornerPixels > 0) cornerPixels / w else .075f
            it.setCount(beadCount)
        }
        atlas = BeadSprites(w * beadRadius, theme)
        text.textSize = if (wear) w * .052f else 15f * resources.displayMetrics.scaledDensity
    }
    override fun onDraw(canvas: Canvas) {
        val s = sim ?: return; val a = atlas ?: return
        val now = System.nanoTime()
        s.resetEvents()
        if (running) {
            if (lastFrame != 0L) accumulator += ((now - lastFrame) / 1e9).coerceAtMost(.05)
            lastFrame = now
            while (accumulator >= BeadSim.DT) {
                for (f in 0..9) if (s.fingerOn[f]) {
                    val dx = targetX[f] - s.fingerX[f]; val dy = targetY[f] - s.fingerY[f]
                    val distance = hypot(dx,dy)
                    val scale = min(1f, .025f / max(.00001f,distance))
                    s.fingerVx[f] = dx * scale / BeadSim.DT
                    s.fingerVy[f] = dy * scale / BeadSim.DT
                    s.fingerX[f] += dx * scale; s.fingerY[f] += dy * scale
                }
                s.step(accelerationX, accelerationY)
                accumulator -= BeadSim.DT
            }
            feedback.onFrame(s.peakImpulse,s.wallImpulse,s.scatterEnergy,s.impactRadius/s.radius)
        }
        canvas.drawColor(Color.BLACK)
        canvas.save()
        clip.rewind()
        if (s.round) clip.addCircle(width*.5f,height*.5f,width*.5f,Path.Direction.CW)
        else { rect.set(0f,0f,width.toFloat(),height.toFloat()); clip.addRoundRect(rect,s.corner*width,s.corner*width,Path.Direction.CW) }
        canvas.clipPath(clip)
        // A very faint cool floor stays OLED-friendly.
        paint.color = 0xff020409.toInt(); canvas.drawPaint(paint)
        for (i in 0 until s.count) {
            val px = s.x[i]*width; val py = s.y[i]*width
            val scale = s.r[i]/s.radius
            val half = a.center*scale
            val light = (s.glow[i]*7f).roundToInt().coerceIn(0,7)
            if (!wear && s.glow[i] > .55f) {
                paint.color = 0x184999ee; paint.strokeWidth = s.r[i]*width
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(px-s.vx[i]*width*.009f,py-s.vy[i]*width*.009f,px,py,paint)
            }
            paint.color = Color.WHITE; paint.alpha = 255
            rect.set(px-half,py-half,px+half,py+half)
            canvas.drawBitmap(a.sprites[light*4+s.tint[i]],null,rect,paint)
            canvas.save(); canvas.rotate(s.angle[i],px,py)
            canvas.drawBitmap(a.swirl,null,rect,paint); canvas.restore()
        }
        canvas.restore()
        val remaining = hintUntil-SystemClock.uptimeMillis()
        if (remaining > 0) {
            text.alpha = (min(1f,remaining/600f)*230).toInt()
            canvas.drawText(hint,width*.5f,height*.24f,text)
        }
        if (running) {
            if (s.awakeCount == 0 && remaining <= 0 && !s.fingerOn.any { it }) postInvalidateDelayed(100)
            else postInvalidateOnAnimation()
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (wear) gestures.onTouchEvent(event)
        val s = sim ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val slot = pointerIds.indexOf(-1)
                if (slot >= 0) {
                    val idx = event.actionIndex
                    pointerIds[slot] = event.getPointerId(idx)
                    targetX[slot] = event.getX(idx)/width; targetY[slot] = event.getY(idx)/width
                    s.fingerX[slot] = targetX[slot]; s.fingerY[slot] = targetY[slot]
                    s.fingerOn[slot] = true; s.wakeAll()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val slot = pointerIds.indexOf(event.getPointerId(event.actionIndex))
                if (slot >= 0) { pointerIds[slot] = -1; s.fingerOn[slot] = false }
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { pointerIds.fill(-1); s.fingerOn.fill(false) }
        }
        for (f in 0..9) if (pointerIds[f] >= 0) {
            val index = event.findPointerIndex(pointerIds[f])
            if (index >= 0) { targetX[f] = event.getX(index)/width; targetY[f] = event.getY(index)/width }
        }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
