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
            writeRawTelemetry(sessionDir, audioData, imuData, cameraFrames)
            RoomModelBuilder.exportPly(roomModel, File(sessionDir, "room_model.ply"))
            writeAcousticReport(sessionDir, roomModel, materials, acousticReport)

            SessionExportResult(
                sessionDir = sessionDir,
                summary = buildSummary(sessionDir, roomModel, materials, acousticReport),
            )
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
        audioData: List<AudioBufferEntry>,
        imuData: List<ImuEntry>,
        cameraFrames: List<CameraFrameEntry>,
    ) {
        File(sessionDir, "raw_telemetry.csv").bufferedWriter().use { writer ->
            writer.appendLine("# AcousticLogger raw telemetry")
            writer.appendLine("# audio_sample_rate_hz=${AudioTelemetry.SAMPLE_RATE_HZ}")
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

            writer.appendLine()
            writer.appendLine("# SECTION:AUDIO_PCM")
            writer.appendLine("timestamp_ns,sample_index,sample_value")
            audioData.sortedBy { it.timestampNs }.forEach { entry ->
                entry.samples.forEachIndexed { index, sample ->
                    writer.appendLine("${entry.timestampNs},$index,$sample")
                }
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

    private fun buildSummary(
        sessionDir: File,
        roomModel: RoomModel,
        materials: List<MaterialEstimate>,
        acousticReport: AcousticReport,
    ): String {
        val topMaterial = materials.firstOrNull()?.type?.label ?: "brak danych"
        return buildString {
            appendLine("Folder: ${sessionDir.absolutePath}")
            appendLine("Model 3D: room_model.ply (${roomModel.points.size} punktów)")
            appendLine(
                "Wymiary ~ ${"%.1f".format(roomModel.widthM)} x " +
                    "${"%.1f".format(roomModel.depthM)} x ${"%.1f".format(roomModel.heightM)} m",
            )
            appendLine("Objętość ~ ${"%.1f".format(roomModel.volumeM3)} m³")
            appendLine("RT60 (broadband) ~ ${"%.2f".format(acousticReport.rt60BroadbandSec)} s")
            appendLine("Dominujący materiał: $topMaterial")
            append("Raport: acoustic_report.json")
        }
    }
}
