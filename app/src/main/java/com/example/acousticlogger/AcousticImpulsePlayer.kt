package com.example.acousticlogger

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

object AcousticImpulsePlayer {

    private const val PRE_ROLL_DELAY_MS = 1500L
    private const val CHIRP_DURATION_SEC = 0.15f
    private const val START_HZ = 400f
    private const val END_HZ = 8000f

    suspend fun playImpulse(context: Context): Long = withContext(Dispatchers.IO) {
        delay(PRE_ROLL_DELAY_MS)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val targetVolume = (maxVolume * 0.85f).toInt().coerceAtLeast(1)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)

        val samples = generateLogChirp(
            sampleRate = AudioTelemetry.SAMPLE_RATE_HZ,
            durationSec = CHIRP_DURATION_SEC,
            startHz = START_HZ,
            endHz = END_HZ,
        )

        val impulseStartNs = System.nanoTime()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioTelemetry.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .build()

        try {
            track.write(samples, 0, samples.size)
            track.play()
            val playDurationMs = ((samples.size.toDouble() / AudioTelemetry.SAMPLE_RATE_HZ) * 1000).toLong() + 50
            delay(playDurationMs)
            track.stop()
        } finally {
            track.release()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        }

        impulseStartNs
    }

    private fun generateLogChirp(
        sampleRate: Int,
        durationSec: Float,
        startHz: Float,
        endHz: Float,
    ): ShortArray {
        val sampleCount = (sampleRate * durationSec).toInt().coerceAtLeast(1)
        val output = ShortArray(sampleCount)
        val k = ln(endHz / startHz) / durationSec
        val twoPi = (2.0 * PI).toFloat()

        for (i in 0 until sampleCount) {
            val t = i.toFloat() / sampleRate
            val envelope = hannEnvelope(i, sampleCount)
            val phase = twoPi * startHz * (expApprox(k * t) - 1f) / k
            val sample = (envelope * sin(phase) * 0.9f * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = sample.toShort()
        }
        return output
    }

    private fun hannEnvelope(index: Int, total: Int): Float {
        if (total <= 1) return 1f
        return (0.5f - 0.5f * kotlin.math.cos(2f * PI.toFloat() * index / (total - 1))).toFloat()
    }

    private fun expApprox(x: Float): Float {
        return kotlin.math.exp(x.toDouble()).toFloat()
    }
}
