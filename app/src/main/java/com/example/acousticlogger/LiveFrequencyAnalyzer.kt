package com.example.acousticlogger

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object LiveFrequencyAnalyzer {

    fun analyze(samples: ShortArray, sampleCount: Int): FloatArray {
        val count = sampleCount.coerceAtMost(samples.size)
        if (count <= 0) {
            return FloatArray(ScanConfig.SPECTRUM_BANDS_HZ.size)
        }

        val magnitudes = FloatArray(ScanConfig.SPECTRUM_BANDS_HZ.size)
        for (index in ScanConfig.SPECTRUM_BANDS_HZ.indices) {
            magnitudes[index] = goertzelMagnitude(
                samples = samples,
                count = count,
                targetHz = ScanConfig.SPECTRUM_BANDS_HZ[index],
                sampleRateHz = AudioTelemetry.SAMPLE_RATE_HZ,
            )
        }
        return normalize(magnitudes)
    }

    private fun goertzelMagnitude(
        samples: ShortArray,
        count: Int,
        targetHz: Int,
        sampleRateHz: Int,
    ): Float {
        val normalizedFreq = targetHz.toDouble() / sampleRateHz
        val coeff = 2.0 * cos(2.0 * Math.PI * normalizedFreq)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0

        for (i in 0 until count) {
            val sample = samples[i].toDouble() / Short.MAX_VALUE
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }

        val real = q1 - q2 * cos(2.0 * Math.PI * normalizedFreq)
        val imag = q2 * sin(2.0 * Math.PI * normalizedFreq)
        return sqrt(real * real + imag * imag).toFloat()
    }

    private fun normalize(values: FloatArray): FloatArray {
        val peak = values.maxOrNull() ?: 0f
        if (peak <= 1e-6f) return values
        return FloatArray(values.size) { index -> (values[index] / peak).coerceIn(0f, 1f) }
    }
}
