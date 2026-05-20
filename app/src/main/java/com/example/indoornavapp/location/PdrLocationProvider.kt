package com.example.indoornavapp.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.indoornavapp.model.Graph
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin

class PdrLocationProvider(
    private val context: Context
) : LocationProvider, SensorEventListener {
    override val providerName: String = "PDR"

    private val listeners = mutableSetOf<LocationListener>()
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var accelerometerValues: FloatArray? = null
    private var magneticValues: FloatArray? = null
    private var currentLocation: IndoorLocation? = null
    private var lastStepTimestampNs: Long = 0L
    private var hasStepDetector = false
    private var lastAccelStepMs: Long = 0L
    private var previousAccelMagnitude = 0f

    var stepLengthMetres: Double = 0.7
    var mapUnitsPerMetre: Double = 1.4

    override fun start() {
        hasStepDetector = canUseStepDetector() && sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            true
        } ?: false

        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun addListener(listener: LocationListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: LocationListener) {
        listeners.remove(listener)
    }

    fun emitPredictedLocation(location: IndoorLocation) {
        listeners.forEach { it.onLocation(location.copy(source = providerName)) }
    }

    fun resetToNode(graph: Graph, nodeId: String) {
        val node = graph.nodes[nodeId] ?: return
        currentLocation = IndoorLocation(
            nodeId = node.id,
            x = node.x,
            y = node.y,
            floor = node.floor,
            confidence = 0.65,
            source = providerName
        )
        publishCurrentLocation()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerValues = event.values.clone()
                updateFallbackOrientation()
                detectStepFromAcceleration(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticValues = event.values.clone()
                updateFallbackOrientation()
            }
            Sensor.TYPE_STEP_DETECTOR -> onStep(event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateFallbackOrientation() {
        val accelerometer = accelerometerValues ?: return
        val magnetic = magneticValues ?: return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometer, magnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
        }
    }

    private fun onStep(timestampNs: Long) {
        val previous = currentLocation ?: return
        if (timestampNs - lastStepTimestampNs < 250_000_000L) return
        lastStepTimestampNs = timestampNs

        val headingRad = orientationAngles[0].toDouble()
        val mapStep = stepLengthMetres * mapUnitsPerMetre
        val next = previous.copy(
            x = previous.x + sin(headingRad) * mapStep,
            y = previous.y - cos(headingRad) * mapStep,
            confidence = 0.55,
            timestampMs = System.currentTimeMillis(),
            source = providerName
        )
        currentLocation = next
        publishCurrentLocation()
    }

    private fun detectStepFromAcceleration(values: FloatArray) {
        val now = System.currentTimeMillis()
        if (now - lastAccelStepMs < 380L) return

        val magnitude = sqrt(
            values[0] * values[0] +
                values[1] * values[1] +
                values[2] * values[2]
        )
        val crossedStepPeak = previousAccelMagnitude <= 10.7f && magnitude > 10.7f
        previousAccelMagnitude = magnitude

        if (crossedStepPeak) {
            lastAccelStepMs = now
            onStep(now * 1_000_000L)
        }
    }

    private fun publishCurrentLocation() {
        val location = currentLocation ?: return
        listeners.forEach { it.onLocation(location.copy(source = providerName)) }
    }

    private fun canUseStepDetector(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
    }
}
