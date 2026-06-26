package com.example.acousticlogger

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavExporter {

    fun export(audioData: List<AudioBufferEntry>, wavFile: File) {
        val sortedChunks = audioData.sortedBy { it.timestampNs }
        val totalSamples = sortedChunks.sumOf { it.samples.size }
        val dataBytes = totalSamples * 2

        BufferedOutputStream(FileOutputStream(wavFile)).use { output ->
            output.write(buildWavHeader(dataBytes, AudioTelemetry.SAMPLE_RATE_HZ))
            sortedChunks.forEach { chunk ->
                val bytes = ByteBuffer.allocate(chunk.samples.size * Short.SIZE_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                chunk.samples.forEach { sample -> bytes.putShort(sample) }
                output.write(bytes.array())
            }
        }
    }

    fun exportChunkIndex(audioData: List<AudioBufferEntry>, indexFile: File) {
        indexFile.bufferedWriter().use { writer ->
            writer.appendLine("timestamp_ns,sample_offset,sample_count")
            var offset = 0
            audioData.sortedBy { it.timestampNs }.forEach { chunk ->
                writer.appendLine("${chunk.timestampNs},$offset,${chunk.samples.size}")
                offset += chunk.samples.size
            }
        }
    }

    private fun buildWavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val chunkSize = 36 + dataBytes

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        return header.array()
    }
}
