package com.example.acousticlogger

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class AudioTelemetry(private val context: Context) {

    companion object {
        const val SAMPLE_RATE_HZ = 48_000
        private const val READ_SAMPLES = 1024
        private const val MAX_BUFFER_SECONDS = 60
        private val MAX_BUFFER_CHUNKS = (MAX_BUFFER_SECONDS * SAMPLE_RATE_HZ / READ_SAMPLES) + 1
        private const val SPECTRUM_EMIT_INTERVAL_NS = 100_000_000L
    }

    private val buffer = Collections.synchronizedList(mutableListOf<AudioBufferEntry>())
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var lastSpectrumEmitNs = 0L

    val isRunning: Boolean
        get() = running.get()

    fun start(scope: CoroutineScope, onLiveSpectrum: ((FloatArray) -> Unit)? = null) {
        if (running.getAndSet(true)) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, channelConfig, encoding)
        require(minBufferSize > 0) { "AudioRecord min buffer size invalid: $minBufferSize" }

        val recordBufferSize = maxOf(minBufferSize, READ_SAMPLES * 2)
        val audioSource = resolveUnprocessedSource()

        audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE_HZ,
            channelConfig,
            encoding,
            recordBufferSize,
        ).also { record ->
            require(record.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialize"
            }
            record.startRecording()
        }

        captureJob = scope.launch(Dispatchers.Default) {
            val readBuffer = ShortArray(READ_SAMPLES)
            while (isActive && running.get()) {
                if (paused.get()) {
                    delay(10)
                    continue
                }
                val record = audioRecord ?: break
                val readCount = record.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    val timestampNs = System.nanoTime()
                    val samples = readBuffer.copyOf(readCount)
                    buffer.add(AudioBufferEntry(timestampNs, samples))
                    trimBufferIfNeeded()
                    emitLiveSpectrum(readBuffer, readCount, onLiveSpectrum)
                }
            }
        }
    }

    private fun emitLiveSpectrum(
        readBuffer: ShortArray,
        readCount: Int,
        onLiveSpectrum: ((FloatArray) -> Unit)?,
    ) {
        if (onLiveSpectrum == null) return
        val now = System.nanoTime()
        if (now - lastSpectrumEmitNs < SPECTRUM_EMIT_INTERVAL_NS) return
        lastSpectrumEmitNs = now
        onLiveSpectrum(LiveFrequencyAnalyzer.analyze(readBuffer, readCount))
    }

    private fun trimBufferIfNeeded() {
        while (buffer.size > MAX_BUFFER_CHUNKS) {
            buffer.removeAt(0)
        }
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
        captureJob?.cancel()
        captureJob = null
        audioRecord?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        audioRecord = null
    }

    fun drainBuffer(): List<AudioBufferEntry> = synchronized(buffer) {
        buffer.toList().also { buffer.clear() }
    }

    fun peekBufferSize(): Int = buffer.size

    private fun resolveUnprocessedSource(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val supported = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            if (supported == "true") {
                return MediaRecorder.AudioSource.UNPROCESSED
            }
        }
        return MediaRecorder.AudioSource.MIC
    }
}
