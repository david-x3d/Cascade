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
    private var gravitySensor: Sensor? = null
    private var linearSensor: Sensor? = null
    private var gyroscope: Sensor? = null
    private val gravity = FloatArray(3)
    private val linear = FloatArray(3)
    private var sensorTime = 0L
    private var gyroTime = 0L
    private var spin = 0f
    private var lastJolt = 0L
    private var tiltScale = .28f
    private var shakeScale = .5f
    private var twistScale = .4f
    private val prefs by lazy { getSharedPreferences("cascade", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        feedback = Feedback(this)
        beads = BeadView(this,wear,feedback,::toggle)
        apply()
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
        // Fused gravity and linear acceleration separate tilt from shaking far better than a
        // low-pass filter; fall back to the raw accelerometer when they are missing.
        gravitySensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        linearSensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (gravitySensor == null || linearSensor == null) {
            gravitySensor = null; linearSensor = null
            accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        gyroscope = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (!prefs.getBoolean("introduced",false)) {
            beads.showHint(if (wear) "Tilt · drag · turn crown" else "Tilt, shake, twist & drag · ••• for settings")
            prefs.edit().putBoolean("introduced",true).apply()
        }
    }

    /** Reads every saved preference into the simulation, renderer and feedback. */
    private fun apply() {
        feedback.soundOn = prefs.getBoolean("sound",true)
        feedback.volume = prefs.getInt("volume",80)/100f
        feedback.hapticLevel = prefs.getInt("haptic_level",if (prefs.getBoolean("haptics",true)) 2 else 0)
        feedback.hapticTexture = prefs.getBoolean("texture",true)
        beads.beadSize = prefs.getInt("size",1)
        beads.beadCount = prefs.getInt("count",if (wear) 350 else 900)
        beads.theme = prefs.getInt("theme",0)
        beads.restitution = prefs.getInt("bounce",38)/100f
        beads.friction = prefs.getInt("friction",32)/100f
        beads.iterations = QUALITY_ITERATIONS[prefs.getInt("quality",1).coerceIn(0,3)]
        beads.trails = prefs.getBoolean("trails",!wear)
        beads.dynamicLight = prefs.getBoolean("light",true)
        beads.showStats = prefs.getBoolean("stats",false)
        beads.highRefresh = prefs.getBoolean("refresh",true)
        tiltScale = .28f*prefs.getInt("gravity",100)/100f
        shakeScale = .5f*prefs.getInt("shake",100)/100f
        twistScale = .4f*prefs.getInt("twist",100)/100f
        if (prefs.getBoolean("awake",false)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferRefreshRate(beads.highRefresh)
    }

    /** Picks the fastest (or a 60 Hz) display mode at the current resolution. */
    private fun preferRefreshRate(high: Boolean) {
        val d = display ?: return
        val current = d.mode
        val modes = d.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        val mode = if (high) modes.maxByOrNull { it.refreshRate } else modes.minByOrNull { abs(it.refreshRate - 60f) }
        mode?.let { window.attributes = window.attributes.apply { preferredDisplayModeId = it.modeId } }
    }

    override fun onResume() {
        super.onResume(); sensorTime = 0L; gyroTime = 0L; spin = 0f
        for (s in listOf(accelerometer,gravitySensor,linearSensor,gyroscope)) s?.let { sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_GAME) }
        beads.running = true; beads.requestFocus()
    }
    override fun onPause() {
        beads.running = false; sensors.unregisterListener(this); save(); super.onPause()
    }
    override fun onDestroy() { feedback.release(); super.onDestroy() }
    private fun save() { prefs.edit().putBoolean("sound",feedback.soundOn).putInt("haptic_level",feedback.hapticLevel)
        .putInt("count",beads.beadCount).putInt("theme",beads.theme).apply() }
    private fun toggle(key: String) {
        if (key == "sound") { feedback.soundOn = !feedback.soundOn; beads.showHint(if (feedback.soundOn) "Sound on" else "Sound off") }
        else { feedback.hapticsOn = !feedback.hapticsOn; beads.showHint(if (feedback.hapticsOn) "Haptics on" else "Haptics off") }
        save()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> { onGyro(event); return }
            Sensor.TYPE_GRAVITY -> { for (i in 0..2) gravity[i] = event.values[i]; updateTilt(); return }
            Sensor.TYPE_LINEAR_ACCELERATION -> for (i in 0..2) linear[i] = event.values[i]
            else -> {
                val dt = if (sensorTime == 0L) 0f else ((event.timestamp-sensorTime)*1e-9f).coerceIn(.001f,.1f)
                if (sensorTime == 0L) for (i in 0..2) gravity[i] = event.values[i]
                sensorTime = event.timestamp
                val alpha = 1f-exp(-dt/.25f)
                for (i in 0..2) { gravity[i] += alpha*(event.values[i]-gravity[i]); linear[i] = event.values[i]-gravity[i] }
            }
        }
        updateTilt()
        // Hard vertical jerks lift beads off the floor; they land scattered and rattle.
        val vertical = abs(linear[2])
        val now = SystemClock.uptimeMillis()
        if (vertical > 7f && now - lastJolt > 90 && shakeScale > 0f) {
            lastJolt = now
            beads.jolt(((vertical-7f)*.025f*shakeScale/.5f).coerceAtMost(.6f))
        }
        if (abs(linear[0])+abs(linear[1]) > .3f) beads.invalidate()
    }

    private fun updateTilt() {
        // Accelerometer includes support force; inertial response is opposite physical motion.
        var ax = -gravity[0]*tiltScale-linear[0]*shakeScale
        var ay = gravity[1]*tiltScale+linear[1]*shakeScale
        var lx = gravity[0]/9.81f; var ly = -gravity[1]/9.81f
        when (display?.rotation) {
            Surface.ROTATION_90 -> { var old = ax; ax = -ay; ay = old; old = lx; lx = -ly; ly = old }
            Surface.ROTATION_180 -> { ax = -ax; ay = -ay; lx = -lx; ly = -ly }
            Surface.ROTATION_270 -> { var old = ax; ax = ay; ay = -old; old = lx; lx = ly; ly = -old }
        }
        beads.accelerationX = ax.coerceIn(-30f,30f); beads.accelerationY = ay.coerceIn(-30f,30f)
        beads.lightX = lx.coerceIn(-1f,1f); beads.lightY = ly.coerceIn(-1f,1f)
        // Beads pressed harder into the floor roll with more resistance; in free fall they glide.
        beads.floorGravity = ((gravity[2]+linear[2])*tiltScale).coerceAtLeast(0f)
    }

    private fun onGyro(event: SensorEvent) {
        val dt = if (gyroTime == 0L) 0f else ((event.timestamp-gyroTime)*1e-9f).coerceIn(.001f,.1f)
        gyroTime = event.timestamp
        // Device z is out of the screen; positive z is counter-clockwise, i.e. -x→y in screen space.
        val z = event.values[2]
        val target = if (abs(z) < .06f) 0f else -z*twistScale
        val previous = spin
        spin += (target-spin)*(if (dt == 0f) 1f else 1f-exp(-dt/.03f))
        beads.spin = spin
        beads.spinAccel = if (dt == 0f) 0f else ((spin-previous)/dt).coerceIn(-60f,60f)
        if (abs(spin) > .25f) beads.invalidate()
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
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((20*dp).toInt(),(4*dp).toInt(),(20*dp).toInt(),(8*dp).toInt()) }
        fun header(title: String) = layout.addView(TextView(this).apply {
            text = title; textSize = 13f; setTextColor(0xff7fb3ff.toInt()); setPadding(0,(16*dp).toInt(),0,(4*dp).toInt())
        })
        fun switch(label: String, key: String, default: Boolean) = layout.addView(Switch(this).apply {
            text = label; isChecked = prefs.getBoolean(key,default); setPadding(0,(6*dp).toInt(),0,(6*dp).toInt())
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key,checked).apply(); apply() }
        })
        fun slider(label: String, key: String, default: Int, min: Int, max: Int, unit: String = "%", live: Boolean = true) {
            val caption = TextView(this)
            fun show(v: Int) { caption.text = "$label  ·  $v$unit" }
            show(prefs.getInt(key,default)); layout.addView(caption)
            layout.addView(SeekBar(this).apply {
                this.min = min; this.max = max; progress = prefs.getInt(key,default)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        show(value); if (live && fromUser) { prefs.edit().putInt(key,value).apply(); apply() }
                    }
                    override fun onStartTrackingTouch(bar: SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar) { prefs.edit().putInt(key,bar.progress).apply(); apply() }
                })
            })
        }
        fun choice(label: String, key: String, default: Int, options: Array<String>) {
            layout.addView(TextView(this).apply { text = label })
            layout.addView(Spinner(this).apply {
                adapter = ArrayAdapter(this@CascadeActivity,android.R.layout.simple_spinner_dropdown_item,options)
                setSelection(prefs.getInt(key,default).coerceIn(0,options.size-1))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (prefs.getInt(key,default) != position) { prefs.edit().putInt(key,position).apply(); apply() }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
            })
        }
        save()
        header("BEADS")
        slider("Bead count","count",900,beads.minCount,beads.maxCount,"",live = false)
        choice("Bead size","size",1,arrayOf("Small","Medium","Large"))
        choice("Colour","theme",0,BeadSprites.THEMES)
        header("PHYSICS")
        slider("Gravity","gravity",100,30,250)
        slider("Shake response","shake",100,0,250)
        slider("Twist response","twist",100,0,250)
        slider("Bounciness","bounce",38,5,80)
        slider("Friction","friction",32,0,80)
        choice("Simulation quality","quality",1,arrayOf("Battery saver","Balanced","High","Ultra"))
        header("SOUND & HAPTICS")
        switch("Glass sounds","sound",true)
        slider("Volume","volume",80,0,100)
        choice("Haptic strength","haptic_level",2,arrayOf("Off","Light","Medium","Strong"))
        switch("Rattle texture while beads tumble","texture",true)
        header("DISPLAY")
        switch("Motion trails","trails",true)
        switch("Highlights follow tilt","light",true)
        switch("High refresh rate","refresh",true)
        switch("Keep screen on","awake",false)
        switch("Show FPS","stats",false)
        val scroll = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this).setTitle("Cascade").setView(scroll)
            .setPositiveButton("Done",null)
            .setNeutralButton("Reset") { _, _ -> prefs.edit().clear().putBoolean("introduced",true).apply(); apply(); beads.showHint("Settings reset") }
            .show()
    }

    private companion object { val QUALITY_ITERATIONS = intArrayOf(4,6,10,16) }
}
