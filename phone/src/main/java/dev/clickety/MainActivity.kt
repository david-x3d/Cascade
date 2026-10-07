package dev.clickety

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout

class MainActivity : Activity(), SensorEventListener {

    private lateinit var sfx: Sfx
    private lateinit var haptics: Haptics
    private lateinit var fidgets: List<FidgetView>
    private lateinit var container: FrameLayout
    private lateinit var bar: TabBar
    private lateinit var sensors: SensorManager
    private var sensor: Sensor? = null
    private var current = 0
    private var isPaused = true
    private val smoothed = floatArrayOf(0f, 9.81f, 0f)

    private val prefs by lazy { getSharedPreferences("clickety", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        sfx = Sfx(this)
        haptics = Haptics(this)
        fidgets = listOf(
            SandglassView(this, haptics),
            LavaLampView(this, haptics),
            SwitchesView(this, sfx, haptics),
            SlimeView(this, sfx, haptics),
        )

        container = FrameLayout(this)
        bar = TabBar(this, TABS) { select(it) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(container, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (64 * resources.displayMetrics.density).toInt()))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(root)

        sensors = getSystemService(SensorManager::class.java)
        sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)

        select(prefs.getInt(KEY_TAB, 0).coerceIn(0, fidgets.lastIndex))
    }

    private fun select(index: Int) {
        if (container.childCount > 0 && index == current) return
        fidgets[current].active = false
        current = index
        container.removeAllViews()
        container.addView(fidgets[index])
        fidgets[index].active = !isPaused
        bar.selected = index
        prefs.edit().putInt(KEY_TAB, index).apply()
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        fidgets[current].active = true
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        sensors.unregisterListener(this)
        fidgets[current].active = false
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        for (i in 0..2) smoothed[i] += (v[i] - smoothed[i]) * 0.35f
        // Device x points right and y up; the accelerometer reads +g on the axis facing up.
        // Screen y points down, so things fall towards the side whose axis reads high.
        val f = fidgets[current]
        f.gravityX = -smoothed[0]
        f.gravityY = smoothed[1]
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val KEY_TAB = "tab"
        val TABS = listOf("Sand", "Lava", "Switches", "Slime")
    }
}

/** Bottom navigation with small hand-drawn icons. */
private class TabBar(
    context: Context,
    private val labels: List<String>,
    private val onSelect: (Int) -> Unit,
) : View(context) {

    var selected = 0
        set(value) { field = value; invalidate() }

    private val dp = resources.displayMetrics.density
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11.5f * dp
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255) }
    private val bg = Paint().apply { color = Color.rgb(0x0B, 0x0B, 0x0D) }
    private val divider = Paint().apply { color = Color.argb(30, 255, 255, 255) }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        canvas.drawRect(0f, 0f, width.toFloat(), dp, divider)
        val w = width / labels.size.toFloat()
        for (i in labels.indices) {
            val cx = w * i + w / 2f
            val cy = height * 0.38f
            val on = i == selected
            if (on) {
                rect.set(cx - 30 * dp, cy - 14 * dp, cx + 30 * dp, cy + 14 * dp)
                canvas.drawRoundRect(rect, 14 * dp, 14 * dp, pill)
            }
            val c = if (on) Color.WHITE else Color.argb(150, 255, 255, 255)
            icon.color = c
            label.color = c
            drawIcon(canvas, i, cx, cy, 9 * dp)
            canvas.drawText(labels[i], cx, height * 0.84f, label)
        }
    }

    private fun drawIcon(c: Canvas, i: Int, x: Float, y: Float, s: Float) {
        path.reset()
        when (i) {
            0 -> { // hourglass
                path.moveTo(x - s * 0.7f, y - s); path.lineTo(x + s * 0.7f, y - s)
                path.lineTo(x - s * 0.7f, y + s); path.lineTo(x + s * 0.7f, y + s); path.close()
                c.drawPath(path, icon)
            }
            1 -> { // lamp
                path.moveTo(x - s * 0.3f, y - s); path.lineTo(x + s * 0.3f, y - s)
                path.lineTo(x + s * 0.55f, y + s * 0.45f); path.lineTo(x - s * 0.55f, y + s * 0.45f); path.close()
                c.drawPath(path, icon)
                c.drawLine(x - s * 0.7f, y + s, x + s * 0.7f, y + s, icon)
                c.drawCircle(x, y, s * 0.18f, icon)
            }
            2 -> { // toggle
                rect.set(x - s, y - s * 0.5f, x + s, y + s * 0.5f)
                c.drawRoundRect(rect, s * 0.5f, s * 0.5f, icon)
                c.drawCircle(x + s * 0.5f, y, s * 0.28f, icon)
            }
            else -> { // blob
                path.moveTo(x - s, y + s * 0.2f)
                path.cubicTo(x - s, y - s * 0.9f, x + s * 0.2f, y - s * 1.1f, x + s * 0.8f, y - s * 0.3f)
                path.cubicTo(x + s * 1.2f, y + s * 0.3f, x + s * 0.4f, y + s * 0.9f, x - s * 0.2f, y + s * 0.8f)
                path.cubicTo(x - s * 0.7f, y + s * 0.75f, x - s, y + s * 0.6f, x - s, y + s * 0.2f)
                c.drawPath(path, icon)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = (event.x / (width / labels.size.toFloat())).toInt().coerceIn(0, labels.lastIndex)
            if (i != selected) {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onSelect(i)
            }
        }
        return true
    }
}
