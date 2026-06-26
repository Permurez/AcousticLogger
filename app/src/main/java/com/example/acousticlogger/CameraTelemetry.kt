package com.example.acousticlogger

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class CameraTelemetry(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val imuTelemetry: ImuTelemetry,
) {

    companion object {
        private const val GRID_WIDTH = 24
        private const val GRID_HEIGHT = 18
        private const val FRAME_INTERVAL_MS = 250L
    }

    private val buffer = Collections.synchronizedList(mutableListOf<CameraFrameEntry>())
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val lastFrameTimestampNs = AtomicLong(0L)

    private var cameraProvider: ProcessCameraProvider? = null

    val isRunning: Boolean
        get() = running.get()

    suspend fun start(scope: CoroutineScope) = withContext(Dispatchers.Main) {
        if (running.getAndSet(true)) return@withContext

        val provider = ProcessCameraProvider.getInstance(context).await()
        cameraProvider = provider
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(ContextCompatExecutor(context)) { image ->
            if (!running.get() || paused.get()) {
                image.close()
                return@setAnalyzer
            }

            val timestampNs = System.nanoTime()
            if (timestampNs - lastFrameTimestampNs.get() >= FRAME_INTERVAL_MS * 1_000_000L) {
                lastFrameTimestampNs.set(timestampNs)
                val orientation = imuTelemetry.snapshot()
                    ?: imuTelemetry.nearestEntry(timestampNs)
                    ?: ImuEntry(timestampNs, 0f, 0f, 0f, 1f)
                val cells = sampleGrid(image, GRID_WIDTH, GRID_HEIGHT)
                buffer.add(
                    CameraFrameEntry(
                        timestampNs = timestampNs,
                        orientation = orientation,
                        gridWidth = GRID_WIDTH,
                        gridHeight = GRID_HEIGHT,
                        cells = cells,
                    ),
                )
            }
            image.close()
        }

        provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
        )
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    suspend fun stop() = withContext(Dispatchers.Main) {
        if (!running.getAndSet(false)) return@withContext
        paused.set(false)
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    fun drainBuffer(): List<CameraFrameEntry> = synchronized(buffer) {
        buffer.toList().also { buffer.clear() }
    }

    private fun sampleGrid(image: ImageProxy, gridW: Int, gridH: Int): List<GridCellSample> {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val width = image.width
        val height = image.height
        val cells = ArrayList<GridCellSample>(gridW * gridH)

        for (gy in 0 until gridH) {
            for (gx in 0 until gridW) {
                val centerX = ((gx + 0.5f) * width / gridW).toInt().coerceIn(0, width - 1)
                val centerY = ((gy + 0.5f) * height / gridH).toInt().coerceIn(0, height - 1)

                val patchRadius = max(1, min(width, height) / (gridW * 4))
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                val luminances = FloatArray(9)
                var lumIndex = 0

                for (dy in -patchRadius..patchRadius) {
                    for (dx in -patchRadius..patchRadius) {
                        val x = (centerX + dx).coerceIn(0, width - 1)
                        val y = (centerY + dy).coerceIn(0, height - 1)

                        val yIndex = y * yRowStride + x
                        val yValue = yBuffer.get(yIndex).toInt() and 0xFF

                        val uvX = x / 2
                        val uvY = y / 2
                        val uvIndex = uvY * uvRowStride + uvX * uvPixelStride
                        val uValue = uBuffer.get(uvIndex).toInt() and 0xFF
                        val vValue = vBuffer.get(uvIndex).toInt() and 0xFF

                        val rgb = yuvToRgb(yValue, uValue, vValue)
                        sumR += rgb.first
                        sumG += rgb.second
                        sumB += rgb.third
                        count++

                        if (lumIndex < luminances.size) {
                            luminances[lumIndex++] = (0.299f * rgb.first + 0.587f * rgb.second + 0.114f * rgb.third)
                        }
                    }
                }

                val avgR = sumR / count
                val avgG = sumG / count
                val avgB = sumB / count
                val meanLum = luminances.take(lumIndex).average().toFloat()
                var variance = 0f
                for (i in 0 until lumIndex) {
                    val diff = luminances[i] - meanLum
                    variance += diff * diff
                }
                variance /= max(1, lumIndex)

                cells.add(
                    GridCellSample(
                        red = avgR,
                        green = avgG,
                        blue = avgB,
                        luminanceVariance = variance,
                    ),
                )
            }
        }
        return cells
    }

    private fun yuvToRgb(y: Int, u: Int, v: Int): Triple<Int, Int, Int> {
        val yf = y - 16
        val uf = u - 128
        val vf = v - 128

        var r = (1.164f * yf + 1.596f * vf).toInt()
        var g = (1.164f * yf - 0.392f * uf - 0.813f * vf).toInt()
        var b = (1.164f * yf + 2.017f * uf).toInt()

        r = r.coerceIn(0, 255)
        g = g.coerceIn(0, 255)
        b = b.coerceIn(0, 255)
        return Triple(r, g, b)
    }
}
