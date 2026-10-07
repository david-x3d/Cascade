package dev.cascade

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager

class MainActivity : Activity(), SensorEventListener {

    private lateinit var view: BeadView
    private lateinit var feedback: Feedback
    private lateinit var sensors: SensorManager
    private var sensor: Sensor? = null

    private val prefs by lazy { getSharedPreferences("cascade", Context.MODE_PRIVATE) }

    // Low-passed accelerometer, used as gravity when the device has no fused gravity sensor.
    private val smoothed = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedback = Feedback(this).apply {
            soundOn = prefs.getBoolean(KEY_SOUND, true)
            hapticsOn = prefs.getBoolean(KEY_HAPTICS, true)
        }
        view = BeadView(this, feedback, ::toggle)
        view.fill = prefs.getFloat(KEY_FILL, BeadView.DEFAULT_FILL)
        setContentView(view)

        sensors = getSystemService(SensorManager::class.java)
        // Raw accelerometer rather than TYPE_GRAVITY: it also carries shakes and flicks,
        // which is half the fun.
        sensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)

        if (savedInstanceState == null) view.showHint("Tilt your wrist")
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        view.running = true
        view.requestFocus()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        view.running = false
        prefs.edit().putFloat(KEY_FILL, view.fill).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        feedback.release()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        for (i in 0..2) smoothed[i] += (v[i] - smoothed[i]) * 0.5f
        // Device x points right and y points up, and the accelerometer reads +g on the axis
        // facing up. Screen y points down, so beads fall towards the side whose axis reads high.
        view.gravityX = -smoothed[0]
        view.gravityY = smoothed[1]
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
            view.fill += delta * 0.02f
            view.showHint("${(view.fill / BeadView.MAX_FILL * 100).toInt()}%")
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun toggle(what: String) {
        when (what) {
            "sound" -> {
                feedback.soundOn = !feedback.soundOn
                prefs.edit().putBoolean(KEY_SOUND, feedback.soundOn).apply()
                view.showHint(if (feedback.soundOn) "Sound on" else "Sound off")
            }
            "haptics" -> {
                feedback.hapticsOn = !feedback.hapticsOn
                prefs.edit().putBoolean(KEY_HAPTICS, feedback.hapticsOn).apply()
                view.showHint(if (feedback.hapticsOn) "Haptics on" else "Haptics off")
            }
        }
        feedback.confirm()
    }

    private companion object {
        const val KEY_SOUND = "sound"
        const val KEY_HAPTICS = "haptics"
        const val KEY_FILL = "fill"
    }
}
