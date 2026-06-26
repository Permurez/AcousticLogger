package com.example.acousticlogger

import android.content.Context
import android.os.Environment
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class CoreController(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
) {

    private val appContext = context.applicationContext
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(supervisorJob + Dispatchers.Default)
    private val imuTelemetry = ImuTelemetry(appContext)
    private val audioTelemetry = AudioTelemetry(appContext)
    private val cameraTelemetry = CameraTelemetry(appContext, lifecycleOwner, previewView, imuTelemetry)
    private val recording = AtomicBoolean(false)

    val isRecording: Boolean
        get() = recording.get()

    suspend fun startRecording(): Result<Unit> = runCatching {
        if (recording.get()) {
            throw IllegalStateException("Sesja już trwa")
        }

        coroutineScope {
            val imuStart = async(Dispatchers.Default) { imuTelemetry.start(scope) }
            val audioStart = async(Dispatchers.Default) { audioTelemetry.start(scope) }
            val cameraStart = async(Dispatchers.Main) { cameraTelemetry.start(scope) }
            imuStart.await()
            audioStart.await()
            cameraStart.await()
        }
        recording.set(true)
    }

    suspend fun stopRecordingAndExport(): Result<SessionExportResult> = withContext(Dispatchers.IO) {
        if (!recording.get()) {
            return@withContext Result.failure(IllegalStateException("Brak aktywnej sesji"))
        }

        runCatching {
            coroutineScope {
                val audioStop = async(Dispatchers.Default) { audioTelemetry.stop() }
                val imuStop = async(Dispatchers.Default) { imuTelemetry.stop() }
                val cameraStop = async(Dispatchers.Main) { cameraTelemetry.stop() }
                audioStop.await()
                imuStop.await()
                cameraStop.await()
            }
            recording.set(false)

            val audioData = audioTelemetry.drainBuffer()
            val imuData = imuTelemetry.drainBuffer()
            val cameraFrames = cameraTelemetry.drainBuffer()

            val roomModel = RoomModelBuilder.build(cameraFrames)
            val materials = MaterialAbsorptionEstimator.estimate(cameraFrames)
            val acousticReport = AcousticAnalyzer.analyze(audioData, roomModel.volumeM3)

            val sessionDir = createSessionDirectory()
            WavExporter.export(audioData, File(sessionDir, "recording.wav"))
            WavExporter.exportChunkIndex(audioData, File(sessionDir, "audio_chunks.csv"))
            writeRawTelemetry(sessionDir, imuData, cameraFrames)
            RoomModelBuilder.exportPly(roomModel, File(sessionDir, "room_model.ply"))
            writeAcousticReport(sessionDir, roomModel, materials, acousticReport)

            val results = buildSessionResults(sessionDir, roomModel, materials, acousticReport)
            SessionExportResult(sessionDir = sessionDir, results = results)
        }
    }

    fun pause() {
        if (!recording.get()) return
        imuTelemetry.pause()
        audioTelemetry.pause()
        cameraTelemetry.pause()
    }

    fun resume() {
        if (!recording.get()) return
        imuTelemetry.resume()
        audioTelemetry.resume()
        cameraTelemetry.resume()
    }

    fun release() {
        if (recording.get()) {
            audioTelemetry.stop()
            imuTelemetry.stop()
            runBlocking(Dispatchers.Main) { cameraTelemetry.stop() }
            recording.set(false)
        }
        supervisorJob.cancel()
    }

    private fun createSessionDirectory(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(downloadsDir, "AcousticLogger_$timestamp").also { it.mkdirs() }
    }

    private fun writeRawTelemetry(
        sessionDir: File,
        imuData: List<ImuEntry>,
        cameraFrames: List<CameraFrameEntry>,
    ) {
        File(sessionDir, "raw_telemetry.csv").bufferedWriter().use { writer ->
            writer.appendLine("# AcousticLogger raw telemetry")
            writer.appendLine("# audio_pcm=recording.wav (${AudioTelemetry.SAMPLE_RATE_HZ} Hz, mono, 16-bit)")
            writer.appendLine("# audio_chunk_timestamps=audio_chunks.csv")
            writer.appendLine()

            writer.appendLine("# SECTION:IMU")
            writer.appendLine("timestamp_ns,qx,qy,qz,qw")
            imuData.sortedBy { it.timestampNs }.forEach { entry ->
                writer.appendLine("${entry.timestampNs},${entry.qx},${entry.qy},${entry.qz},${entry.qw}")
            }

            writer.appendLine()
            writer.appendLine("# SECTION:CAMERA_FRAMES")
            writer.appendLine("timestamp_ns,frame_index,grid_w,grid_h,cell_count")
            cameraFrames.sortedBy { it.timestampNs }.forEachIndexed { index, frame ->
                writer.appendLine(
                    "${frame.timestampNs},$index,${frame.gridWidth},${frame.gridHeight},${frame.cells.size}",
                )
            }
        }
    }

    private fun writeAcousticReport(
        sessionDir: File,
        roomModel: RoomModel,
        materials: List<MaterialEstimate>,
        acousticReport: AcousticReport,
    ) {
        val json = JSONObject().apply {
            put("room_width_m", roomModel.widthM)
            put("room_height_m", roomModel.heightM)
            put("room_depth_m", roomModel.depthM)
            put("room_volume_m3", roomModel.volumeM3)
            put("point_cloud_points", roomModel.points.size)
            put("rt60_broadband_sec", acousticReport.rt60BroadbandSec)
            put("sabine_average_absorption", acousticReport.sabineAverageAbsorption)
            put("edc_drop_db", acousticReport.edcDropDb)
            put("audio_file", "recording.wav")
            put("audio_sample_rate_hz", AudioTelemetry.SAMPLE_RATE_HZ)

            put(
                "rt60_by_band_hz",
                JSONObject().apply {
                    acousticReport.rt60ByBandHz.forEach { (band, value) ->
                        put(band.toString(), value)
                    }
                },
            )

            put(
                "detected_materials",
                JSONArray().apply {
                    materials.forEach { estimate ->
                        put(
                            JSONObject().apply {
                                put("type", estimate.type.name)
                                put("label", estimate.type.label)
                                put("share_percent", estimate.sharePercent)
                                put("alpha_125_hz", estimate.type.alpha125Hz)
                                put("alpha_500_hz", estimate.type.alpha500Hz)
                                put("alpha_1000_hz", estimate.type.alpha1000Hz)
                                put("alpha_2000_hz", estimate.type.alpha2000Hz)
                            },
                        )
                    }
                },
            )

            put(
                "estimated_weighted_absorption",
                JSONObject().apply {
                    put("125_hz", MaterialAbsorptionEstimator.weightedAverageAbsorption(materials) { it.alpha125Hz })
                    put("500_hz", MaterialAbsorptionEstimator.weightedAverageAbsorption(materials) { it.alpha500Hz })
                    put("1000_hz", MaterialAbsorptionEstimator.weightedAverageAbsorption(materials) { it.alpha1000Hz })
                    put("2000_hz", MaterialAbsorptionEstimator.weightedAverageAbsorption(materials) { it.alpha2000Hz })
                },
            )
        }

        File(sessionDir, "acoustic_report.json").writeText(json.toString(2))
    }

    private fun buildSessionResults(
        sessionDir: File,
        roomModel: RoomModel,
        materials: List<MaterialEstimate>,
        acousticReport: AcousticReport,
    ): SessionResults {
        val topMaterial = materials.firstOrNull()?.type?.label ?: "brak danych"
        val materialsSummary = if (materials.isEmpty()) {
            "Brak danych o materiałach"
        } else {
            materials.joinToString(separator = "\n") { estimate ->
                "• ${estimate.type.label}: ${"%.1f".format(estimate.sharePercent)}%"
            }
        }
        val exportFilesSummary = listOf(
            "recording.wav",
            "audio_chunks.csv",
            "raw_telemetry.csv",
            "room_model.ply",
            "acoustic_report.json",
        ).joinToString(separator = "\n") { "• $it" }

        return SessionResults(
            sessionDirPath = sessionDir.absolutePath,
            roomWidthM = roomModel.widthM,
            roomHeightM = roomModel.heightM,
            roomDepthM = roomModel.depthM,
            roomVolumeM3 = roomModel.volumeM3,
            pointCount = roomModel.points.size,
            rt60BroadbandSec = acousticReport.rt60BroadbandSec,
            sabineAverageAbsorption = acousticReport.sabineAverageAbsorption,
            edcDropDb = acousticReport.edcDropDb,
            topMaterialLabel = topMaterial,
            materialsSummary = materialsSummary,
            exportFilesSummary = exportFilesSummary,
        )
    }
}
