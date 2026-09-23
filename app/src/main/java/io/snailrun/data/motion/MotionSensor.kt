package io.snailrun.data.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import io.snailrun.domain.metrics.Motion
import io.snailrun.domain.metrics.MotionClassifier

/** Whether the runner is striding or standing, for auto-pause. See [MotionClassifier]. */
interface MotionSensor {
    fun start()
    fun stop()
    /** Null when there is no sensor, or it is not running. */
    fun current(): Motion?
}

/**
 * The accelerometer, read at game rate.
 *
 * The raw accelerometer rather than linear acceleration: the latter is a fused virtual
 * sensor some phones lack, and the classifier's spread measure removes gravity anyway.
 * No permission is involved at this rate; only sampling above 200 Hz needs one.
 *
 * Samples are stamped on arrival from the same monotonic clock [current] reads, so no
 * reasoning about the sensor HAL's own timestamp base is needed. Events are not batched,
 * so arrival time is sample time to within a few milliseconds.
 */
class AccelerometerMotionSensor(context: Context) : MotionSensor {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val classifier = MotionClassifier()
    private val lock = Any()
    private var running = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val now = SystemClock.elapsedRealtimeNanos()
            synchronized(lock) {
                classifier.onSample(now, event.values[0], event.values[1], event.values[2])
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun start() {
        val sensor = sensor ?: return
        synchronized(lock) {
            if (running) return
            classifier.reset()
            running = true
        }
        manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        manager?.unregisterListener(listener)
    }

    override fun current(): Motion? = synchronized(lock) {
        if (!running) null else classifier.current(SystemClock.elapsedRealtimeNanos())
    }
}
