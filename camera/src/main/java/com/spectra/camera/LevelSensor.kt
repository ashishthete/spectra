package com.spectra.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

@Singleton
class LevelSensor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _rollAngle = MutableStateFlow(0f)
    val rollAngle: StateFlow<Float> = _rollAngle.asStateFlow()

    private val _pitchAngle = MutableStateFlow(0f)
    val pitchAngle: StateFlow<Float> = _pitchAngle.asStateFlow()

    private val _angularVelocity = MutableStateFlow(0f)
    val angularVelocity: StateFlow<Float> = _angularVelocity.asStateFlow()

    private val _gyroValues = MutableStateFlow(floatArrayOf(0f, 0f, 0f))
    val gyroValues: StateFlow<FloatArray> = _gyroValues.asStateFlow()

    private val gyroAxisHistory = ArrayDeque<Int>(8)

    private val _consistentGyroFrames = MutableStateFlow(0)
    val consistentGyroFrames: StateFlow<Int> = _consistentGyroFrames.asStateFlow()

    private var filteredRoll = 0f
    private var filteredPitch = 0f
    private var filteredAngularVelocity = 0f

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val rawRoll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                val snapped = when {
                    abs(rawRoll) < 45f -> rawRoll
                    rawRoll >= 45f -> rawRoll - 90f
                    else -> rawRoll + 90f
                }
                filteredRoll = filteredRoll + SMOOTHING * (snapped - filteredRoll)
                _rollAngle.value = filteredRoll

                val rawPitch = Math.toDegrees(atan2(z.toDouble(), y.toDouble())).toFloat()
                    .coerceIn(-90f, 90f)
                filteredPitch = filteredPitch + SMOOTHING * (rawPitch - filteredPitch)
                _pitchAngle.value = filteredPitch
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]

                val rawOmega = sqrt(gx * gx + gy * gy + gz * gz)
                filteredAngularVelocity = filteredAngularVelocity + SMOOTHING * (rawOmega - filteredAngularVelocity)
                _angularVelocity.value = filteredAngularVelocity
                _gyroValues.value = floatArrayOf(gx, gy, gz)

                val dominantAxis = when {
                    abs(gx) >= abs(gy) && abs(gx) >= abs(gz) -> 0
                    abs(gy) >= abs(gx) && abs(gy) >= abs(gz) -> 1
                    else -> 2
                }
                gyroAxisHistory.addLast(dominantAxis)
                if (gyroAxisHistory.size > 8) gyroAxisHistory.removeFirst()

                if (gyroAxisHistory.size >= 2) {
                    val last = gyroAxisHistory.last()
                    var consistent = 0
                    for (i in gyroAxisHistory.indices.reversed()) {
                        if (gyroAxisHistory[i] == last) consistent++ else break
                    }
                    _consistentGyroFrames.value = consistent
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(accelListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sensorManager.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(gyroListener)
        filteredAngularVelocity = 0f
        gyroAxisHistory.clear()
        _consistentGyroFrames.value = 0
    }

    private companion object {
        const val SMOOTHING = 0.25f
    }
}
