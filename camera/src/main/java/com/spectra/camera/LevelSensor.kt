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

@Singleton
class LevelSensor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _rollAngle = MutableStateFlow(0f)
    val rollAngle: StateFlow<Float> = _rollAngle.asStateFlow()

    private val _pitchAngle = MutableStateFlow(0f)
    val pitchAngle: StateFlow<Float> = _pitchAngle.asStateFlow()

    private var filteredRoll = 0f
    private var filteredPitch = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val rawRoll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                    .coerceIn(-90f, 90f)
                filteredRoll = filteredRoll + SMOOTHING * (rawRoll - filteredRoll)
                _rollAngle.value = filteredRoll

                // 0° = camera horizontal, positive = tilted back (looking up), negative = tilted forward
                val rawPitch = Math.toDegrees(atan2(z.toDouble(), y.toDouble())).toFloat()
                    .coerceIn(-90f, 90f)
                filteredPitch = filteredPitch + SMOOTHING * (rawPitch - filteredPitch)
                _pitchAngle.value = filteredPitch
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
    }

    private companion object {
        const val SMOOTHING = 0.25f
    }
}
