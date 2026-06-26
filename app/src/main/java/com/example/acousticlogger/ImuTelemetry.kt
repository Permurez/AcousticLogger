package com.example.acousticlogger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class ImuTelemetry(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val buffer = Collections.synchronizedList(mutableListOf<ImuEntry>())
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var pollJob: Job? = null

    @Volatile
    private var latestEntry: ImuEntry? = null

    val isRunning: Boolean
        get() = running.get()

    fun start(scope: CoroutineScope) {
        if (running.getAndSet(true)) return
        require(rotationSensor != null) { "Brak czujnika orientacji (ROTATION_VECTOR)" }

        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)

        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive && running.get()) {
                if (!paused.get()) {
                    latestEntry?.let {
                        buffer.add(it)
                        while (buffer.size > MAX_BUFFER_ENTRIES) {
                            buffer.removeAt(0)
                        }
                    }
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val w = if (event.values.size >= 4) {
            event.values[3]
        } else {
            sqrt(maxOf(0f, 1f - x * x - y * y - z * z))
        }

        latestEntry = ImuEntry(
            timestampNs = System.nanoTime(),
            qx = x,
            qy = y,
            qz = z,
            qw = w,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun snapshot(): ImuEntry? = latestEntry

    fun nearestEntry(timestampNs: Long): ImuEntry? {
        if (buffer.isEmpty()) return latestEntry
        return buffer.minByOrNull { kotlin.math.abs(it.timestampNs - timestampNs) } ?: latestEntry
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        paused.set(false)
        pollJob?.cancel()
        pollJob = null
        sensorManager.unregisterListener(this)
    }

    fun drainBuffer(): List<ImuEntry> = synchronized(buffer) {
        buffer.toList().also { buffer.clear() }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 50L
        private const val MAX_BUFFER_ENTRIES = 1200
    }
}
