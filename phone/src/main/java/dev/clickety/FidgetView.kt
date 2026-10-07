package dev.clickety

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Base for each fidget: owns the frame loop and receives gravity from the activity. */
abstract class FidgetView(context: Context) : View(context) {

    /** Gravity in screen space, m/s². x points right, y points down. */
    @Volatile var gravityX = 0f
    @Volatile var gravityY = 9.81f

    var active = false
        set(value) {
            if (field == value) return
            field = value
            lastFrame = 0L
            onActiveChanged(value)
            if (value) postInvalidateOnAnimation()
        }

    private var lastFrame = 0L

    protected val dp = resources.displayMetrics.density

    final override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (active) {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 1f / 60f else ((now - lastFrame) / 1e9f).coerceIn(1f / 240f, 1f / 30f)
            lastFrame = now
            update(dt)
        }
        render(canvas)
        if (active) postInvalidateOnAnimation()
    }

    protected abstract fun update(dt: Float)
    protected abstract fun render(canvas: Canvas)
    protected open fun onActiveChanged(active: Boolean) {}
}

internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

internal fun mixColor(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    val aa = (a ushr 24) and 0xFF
    val ar = (a shr 16) and 0xFF
    val ag = (a shr 8) and 0xFF
    val ab = a and 0xFF
    val ba = (b ushr 24) and 0xFF
    val br = (b shr 16) and 0xFF
    val bg = (b shr 8) and 0xFF
    val bb = b and 0xFF
    return ((aa + (ba - aa) * k).toInt() shl 24) or
        ((ar + (br - ar) * k).toInt() shl 16) or
        ((ag + (bg - ag) * k).toInt() shl 8) or
        (ab + (bb - ab) * k).toInt()
}

internal fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/** Multiplies RGB by [k], clamped. */
internal fun scaleColor(color: Int, k: Float): Int {
    val r = (((color shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
    val g = (((color shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
    val b = ((color and 0xFF) * k).toInt().coerceIn(0, 255)
    return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
}
