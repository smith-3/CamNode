package com.fragmind.camnode.feature.node

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Decides from accelerometer samples whether a resting phone was picked up or moved.
 * It learns the resting position during [armDelayMillis], then alerts on a tilt beyond
 * [tiltThresholdDegrees] or a jolt beyond [joltThreshold] m/s², at most once per cooldown.
 * After an alert, the phone must settle for [armDelayMillis] before another movement can alert.
 */
class MovementAnalyzer(
    private val armDelayMillis: Long = 15_000,
    private val cooldownMillis: Long = 60_000,
    private val tiltThresholdDegrees: Double = 15.0,
    private val joltThreshold: Double = 2.0,
) {
    private var startedAt: Long? = null
    private var gravity: DoubleArray? = null
    private var resting: DoubleArray? = null
    private var lastAlertAt: Long? = null
    private var settling: Settling? = null

    private data class Settling(val position: DoubleArray, val sinceMillis: Long)

    fun onSample(x: Float, y: Float, z: Float, atMillis: Long): Boolean {
        val start = startedAt ?: atMillis.also { startedAt = it }
        val sample = doubleArrayOf(x.toDouble(), y.toDouble(), z.toDouble())
        val smoothed = gravity?.let { previous -> DoubleArray(3) { previous[it] * SMOOTHING + sample[it] * (1 - SMOOTHING) } } ?: sample
        gravity = smoothed
        if (atMillis - start < armDelayMillis) {
            resting = smoothed
            return false
        }
        val jolt = magnitude(DoubleArray(3) { sample[it] - smoothed[it] })
        settling?.let { quiet ->
            if (jolt > joltThreshold || angleDegrees(smoothed, quiet.position) > SETTLE_THRESHOLD_DEGREES) {
                settling = Settling(smoothed, atMillis)
            } else if (atMillis - quiet.sinceMillis >= armDelayMillis) {
                resting = smoothed
                settling = null
            }
            return false
        }
        val restingPosition = resting ?: smoothed.also { resting = it }
        val moved = angleDegrees(smoothed, restingPosition) > tiltThresholdDegrees || jolt > joltThreshold
        val coolingDown = lastAlertAt?.let { atMillis - it < cooldownMillis } == true
        if (!moved || coolingDown) return false
        lastAlertAt = atMillis
        settling = Settling(smoothed, atMillis)
        return true
    }

    private fun magnitude(v: DoubleArray) = sqrt(v.sumOf { it * it })

    private fun angleDegrees(a: DoubleArray, b: DoubleArray): Double {
        val cos = (a.indices.sumOf { a[it] * b[it] } / (magnitude(a) * magnitude(b))).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private companion object {
        const val SMOOTHING = 0.8
        const val SETTLE_THRESHOLD_DEGREES = 5.0
    }
}

/** Feeds the accelerometer into a [MovementAnalyzer] while a camera streams. */
class MovementDetector @Inject constructor(@ApplicationContext context: Context) {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var listener: SensorEventListener? = null

    val available: Boolean get() = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun start(onMoved: () -> Unit) {
        stop()
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val analyzer = MovementAnalyzer()
        val newListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (analyzer.onSample(event.values[0], event.values[1], event.values[2], SystemClock.elapsedRealtime())) onMoved()
            }
            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        // About 5 samples per second: enough to notice a phone being picked up, cheap on old phones.
        sensors.registerListener(newListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        listener = newListener
    }

    fun stop() {
        listener?.let { sensors?.unregisterListener(it) }
        listener = null
    }
}
