package com.drivesentinel.sensors.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.drivesentinel.core.Vec3
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

data class MotionSample(val timeMs: Long, val linear: Vec3, val gravity: Vec3)

/**
 * Linear acceleration + gravity at SENSOR_DELAY_GAME (~50 Hz) on a background HandlerThread.
 * Falls back to raw accelerometer minus a low-passed gravity estimate when the fused
 * TYPE_LINEAR_ACCELERATION / TYPE_GRAVITY sensors are missing.
 */
class MotionSource(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val isAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun samples(): Flow<MotionSample> = callbackFlow {
        val thread = HandlerThread("motion").apply { start() }
        val handler = Handler(thread.looper)
        val linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val fused = linearSensor != null && gravitySensor != null

        var gravity = Vec3(0f, 0f, 9.81f)
        var gravityInit = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = Vec3(e.values[0], e.values[1], e.values[2])
                val tMs = e.timestamp / 1_000_000
                when (e.sensor.type) {
                    Sensor.TYPE_GRAVITY -> gravity = v
                    Sensor.TYPE_LINEAR_ACCELERATION -> trySend(MotionSample(tMs, v, gravity))
                    Sensor.TYPE_ACCELEROMETER -> {
                        // Fallback path: gravity = slow low-pass of raw acceleration.
                        gravity = if (!gravityInit) v.also { gravityInit = true } else gravity + (v - gravity) * 0.02f
                        trySend(MotionSample(tMs, v - gravity, gravity))
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (fused) {
            sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_GAME, handler)
            sensorManager.registerListener(listener, linearSensor, SensorManager.SENSOR_DELAY_GAME, handler)
        } else if (accelSensor != null) {
            sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_GAME, handler)
        } else {
            close(IllegalStateException("No accelerometer"))
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
            thread.quitSafely()
        }
    }.buffer(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
