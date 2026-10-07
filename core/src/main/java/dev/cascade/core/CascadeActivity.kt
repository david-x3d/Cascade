package dev.cascade.core

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.*
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.*

open class CascadeActivity : Activity(), SensorEventListener {
    protected open val wear = false
    private lateinit var beads: BeadView
    private lateinit var feedback: Feedback
    private lateinit var sensors: SensorManager
    private var accelerometer: Sensor? = null
    private val gravity = FloatArray(3)
    private var sensorTime = 0L
    private val prefs by lazy { getSharedPreferences("cascade", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        feedback = Feedback(this).apply {
            soundOn = prefs.getBoolean("sound",true); hapticsOn = prefs.getBoolean("haptics",true)
        }
        beads = BeadView(this,wear,feedback,::toggle).apply {
            beadCount = prefs.getInt("count",if (wear) 350 else 900)
            theme = prefs.getInt("theme",0)
        }
        val root = FrameLayout(this)
        root.addView(beads,FrameLayout.LayoutParams(-1,-1))
        if (!wear) {
            val menu = TextView(this).apply {
                text = "•••"; contentDescription = "Cascade settings"
                textSize = 22f; gravity = Gravity.CENTER
                setTextColor(0xffc9d6e6.toInt()); setBackgroundColor(0x44000000)
                setOnClickListener { showSettings() }
            }
            val dp = resources.displayMetrics.density
            val lp = FrameLayout.LayoutParams((48*dp).toInt(),(48*dp).toInt(),Gravity.TOP or Gravity.END)
            root.addView(menu,lp)
            root.setOnApplyWindowInsetsListener { _, insets ->
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                lp.topMargin = safe.top + (8*dp).toInt(); lp.rightMargin = safe.right + (12*dp).toInt()
                menu.layoutParams = lp
                if (Build.VERSION.SDK_INT >= 31) {
                    var radius = 0
                    for (position in 0..3) radius = max(radius,insets.getRoundedCorner(position)?.radius ?: 0)
                    beads.cornerPixels = if (radius > 0) radius.toFloat() else 28*dp
                } else beads.cornerPixels = 28*dp
                insets
            }
        }
        setContentView(root)
        sensors = getSystemService(SensorManager::class.java)
        accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (!prefs.getBoolean("introduced",false)) {
            beads.showHint(if (wear) "Tilt · drag · turn crown" else "Tilt, shake & drag · ••• for settings")
            prefs.edit().putBoolean("introduced",true).apply()
        }
    }
    override fun onResume() {
        super.onResume(); sensorTime = 0L
        accelerometer?.let { sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) }
        beads.running = true; beads.requestFocus()
    }
    override fun onPause() {
        beads.running = false; sensors.unregisterListener(this); save(); super.onPause()
    }
    override fun onDestroy() { feedback.release(); super.onDestroy() }
    private fun save() { prefs.edit().putBoolean("sound",feedback.soundOn).putBoolean("haptics",feedback.hapticsOn)
        .putInt("count",beads.beadCount).putInt("theme",beads.theme).apply() }
    private fun toggle(key: String) {
        if (key == "sound") { feedback.soundOn = !feedback.soundOn; beads.showHint(if (feedback.soundOn) "Sound on" else "Sound off") }
        else { feedback.hapticsOn = !feedback.hapticsOn; beads.showHint(if (feedback.hapticsOn) "Haptics on" else "Haptics off") }
        save()
    }
    override fun onSensorChanged(event: SensorEvent) {
        val dt = if (sensorTime == 0L) 0f else ((event.timestamp-sensorTime)*1e-9f).coerceIn(.001f,.1f)
        if (sensorTime == 0L) for (i in 0..2) gravity[i] = event.values[i]
        sensorTime = event.timestamp
        val alpha = 1f-exp(-dt/.25f)
        for (i in 0..2) gravity[i] += alpha*(event.values[i]-gravity[i])
        val linearX = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0f else event.values[0]-gravity[0]
        val linearY = if (event.sensor.type == Sensor.TYPE_GRAVITY) 0f else event.values[1]-gravity[1]
        // Accelerometer includes support force; inertial response is opposite physical motion.
        var ax = -gravity[0]*.22f-linearX*.32f
        var ay = gravity[1]*.22f+linearY*.32f
        when (display?.rotation) {
            Surface.ROTATION_90 -> { val old = ax; ax = -ay; ay = old }
            Surface.ROTATION_180 -> { ax = -ax; ay = -ay }
            Surface.ROTATION_270 -> { val old = ax; ax = ay; ay = -old }
        }
        beads.accelerationX = ax.coerceIn(-12f,12f); beads.accelerationY = ay.coerceIn(-12f,12f)
        if (abs(linearX)+abs(linearY) > .3f) beads.invalidate()
    }
    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (wear && event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            beads.beadCount += (-event.getAxisValue(MotionEvent.AXIS_SCROLL)*15f).roundToInt()
            beads.showHint("${beads.beadCount} beads"); save(); return true
        }
        return super.onGenericMotionEvent(event)
    }
    private fun showSettings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,16,32,16) }
        layout.addView(Switch(this).apply { text = "Glass sounds"; isChecked = feedback.soundOn
            setOnCheckedChangeListener { _, checked -> feedback.soundOn = checked; save() } })
        layout.addView(Switch(this).apply { text = "Impact haptics"; isChecked = feedback.hapticsOn
            setOnCheckedChangeListener { _, checked -> feedback.hapticsOn = checked; save() } })
        val label = TextView(this).apply { text = "${beads.beadCount} beads" }; layout.addView(label)
        layout.addView(SeekBar(this).apply {
            max = 600; progress = beads.beadCount-600
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { label.text = "${value+600} beads" }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) { beads.beadCount = bar.progress+600; save() }
            })
        })
        layout.addView(TextView(this).apply { text = "Colour" })
        layout.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@CascadeActivity,android.R.layout.simple_spinner_dropdown_item,arrayOf("Azure","Pearl","Amber","Emerald"))
            setSelection(beads.theme)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (beads.theme != position) { beads.theme = position; save() }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        })
        AlertDialog.Builder(this).setTitle("Cascade").setView(layout).setPositiveButton("Done",null).show()
    }
}
