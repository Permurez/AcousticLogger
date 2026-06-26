package com.example.acousticlogger

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

object AcousticAnalyzer {

    private val OCTAVE_BANDS_HZ = intArrayOf(125, 250, 500, 1000, 2000, 4000)
    private const val IMPULSE_SEARCH_SEC = 6
    private const val DECAY_ANALYSIS_SEC = 4

    fun analyze(audioData: List<AudioBufferEntry>, roomVolumeM3: Float): AcousticReport {
        val signal = flattenAudio(audioData)
        if (signal.isEmpty()) {
            return emptyReport(roomVolumeM3)
        }

        val searchLimit = min(signal.size, AudioTelemetry.SAMPLE_RATE_HZ * IMPULSE_SEARCH_SEC)
        val impulseIndex = findImpulseIndex(signal, searchLimit)
        val decayEnd = min(signal.size, impulseIndex + AudioTelemetry.SAMPLE_RATE_HZ * DECAY_ANALYSIS_SEC)
        if (impulseIndex >= decayEnd) {
            return emptyReport(roomVolumeM3)
        }

        val decaySegment = signal.copyOfRange(impulseIndex, decayEnd)
        val edc = schroederEdc(decaySegment, 0)
        val rt60Broadband = estimateRt60(edc, AudioTelemetry.SAMPLE_RATE_HZ)
        val edcDropDb = computeEdcDropDb(edc)

        val rt60ByBand = OCTAVE_BANDS_HZ.associateWith { bandHz ->
            val filtered = bandPass(decaySegment, bandHz)
            val bandEdc = schroederEdc(filtered, 0)
            estimateRt60(bandEdc, AudioTelemetry.SAMPLE_RATE_HZ)
        }

        val sabineAlpha = if (roomVolumeM3 > 0f && rt60Broadband > 0.0) {
            0.161 * roomVolumeM3 / rt60Broadband
        } else {
            0.0
        }

        return AcousticReport(
            rt60BroadbandSec = rt60Broadband,
            rt60ByBandHz = rt60ByBand,
            estimatedVolumeM3 = roomVolumeM3,
            sabineAverageAbsorption = sabineAlpha,
            edcDropDb = edcDropDb,
        )
    }

    private fun emptyReport(roomVolumeM3: Float) = AcousticReport(
        rt60BroadbandSec = 0.0,
        rt60ByBandHz = emptyMap(),
        estimatedVolumeM3 = roomVolumeM3,
        sabineAverageAbsorption = 0.0,
        edcDropDb = 0.0,
    )

    private fun flattenAudio(entries: List<AudioBufferEntry>): FloatArray {
        val totalSamples = entries.sumOf { it.samples.size }
        if (totalSamples == 0) return FloatArray(0)

        val output = FloatArray(totalSamples)
        var offset = 0
        entries.forEach { entry ->
            entry.samples.forEach { sample ->
                output[offset++] = sample / 32768f
            }
        }
        return output
    }

    private fun findImpulseIndex(signal: FloatArray, searchLimit: Int): Int {
        var maxIndex = 0
        var maxValue = 0f
        for (i in 0 until searchLimit) {
            val value = abs(signal[i])
            if (value > maxValue) {
                maxValue = value
                maxIndex = i
            }
        }
        return maxIndex
    }

    private fun schroederEdc(signal: FloatArray, startIndex: Int): FloatArray {
        if (startIndex >= signal.size) return FloatArray(0)
        val segmentLength = signal.size - startIndex
        val edc = FloatArray(segmentLength)
        var cumulative = 0.0
        for (i in segmentLength - 1 downTo 0) {
            val sample = signal[startIndex + i]
            cumulative += (sample * sample).toDouble()
            edc[i] = cumulative.toFloat()
        }
        return edc
    }

    private fun estimateRt60(edc: FloatArray, sampleRate: Int): Double {
        if (edc.isEmpty()) return 0.0
        val peak = edc.maxOrNull() ?: return 0.0
        if (peak <= 0f) return 0.0

        val dbCurve = FloatArray(edc.size) { index ->
            (10.0 * log10(edc[index] / peak + 1e-12)).toFloat()
        }

        val idx5 = findDbCrossing(dbCurve, -5f) ?: return 0.0
        val idx25 = findDbCrossing(dbCurve, -25f) ?: return 0.0
        if (idx25 <= idx5) return 0.0

        val rt20Sec = (idx25 - idx5).toDouble() / sampleRate
        return rt20Sec * 3.0
    }

    private fun findDbCrossing(dbCurve: FloatArray, targetDb: Float): Int? {
        for (i in 1 until dbCurve.size) {
            if (dbCurve[i - 1] >= targetDb && dbCurve[i] < targetDb) {
                return i
            }
        }
        return null
    }

    private fun computeEdcDropDb(edc: FloatArray): Double {
        if (edc.isEmpty()) return 0.0
        val peak = edc[0]
        val tail = edc.last()
        if (peak <= 0f || tail <= 0f) return 0.0
        return 10.0 * log10(tail / peak + 1e-12)
    }

    private fun bandPass(signal: FloatArray, centerHz: Int): FloatArray {
        val windowSize = max(3, AudioTelemetry.SAMPLE_RATE_HZ / centerHz / 4)
        val output = FloatArray(signal.size)
        val halfWindow = windowSize / 2

        for (i in signal.indices) {
            var sum = 0f
            var count = 0
            val start = max(0, i - halfWindow)
            val end = min(signal.lastIndex, i + halfWindow)
            for (j in start..end) {
                val phase = 2.0 * Math.PI * centerHz * (j - i).toDouble() / AudioTelemetry.SAMPLE_RATE_HZ
                sum += signal[j] * kotlin.math.sin(phase).toFloat()
                count++
            }
            output[i] = if (count > 0) sum / count else 0f
        }
        return output
    }
}
